import json
import tempfile
import unittest
from datetime import datetime, timedelta
from pathlib import Path
from unittest.mock import patch

from api_service import build_plan_request
from license_planner.catalog import CERTIFICATIONS, get_certification
from license_planner.csv_io import parse_busy_csv, render_schedule_csv
from license_planner.models import BusyPeriod, PlanRequest, ProblemResult
from license_planner.scheduler import generate_schedule, summarize_topic_results
from license_planner.tools import PlannerTools, tool_schemas
from license_planner.user_availability import (
    load_personal_availability,
    parse_weekly_availability,
    save_personal_availability,
)


def make_plan_request(*, availability=None, busy_periods=()):
    return PlanRequest(
        self_assessment="함수와 차트 분석이 아직 익숙하지 않습니다.",
        problem_results=(),
        preparation_start=datetime(2026, 10, 5, 18, 0),
        exam_date=datetime(2026, 11, 30, 23, 0),
        busy_periods=tuple(busy_periods),
        weekly_availability=availability or parse_weekly_availability({
            "monday": [{"start": "18:00", "end": "22:00"}],
        }),
    )


def make_api_payload():
    certification_id = "computer_specialist_level_2"
    return {
        "certification_id": certification_id,
        "self_assessment": "함수와 차트 분석이 아직 익숙하지 않습니다.",
        "preparation_start": "2026/10/05/18/00",
        "exam_date": "2026/11/30/23/00",
        "weekly_availability": {
            "monday": [{"start": "18:00", "end": "21:00"}],
        },
        "assessment_results": {
            "certification_id": certification_id,
            "results": [{
                "problem_id": 1,
                "question": "표의 데이터로 가장 적절한 차트를 고르세요.",
                "choices": {"A": "막대형", "B": "원형"},
                "correct_answer": "A",
                "user_answer": "B",
                "is_correct": False,
                "possible_score": 1,
            }],
        },
    }


class AvailabilityAndInputTests(unittest.TestCase):
    def test_busy_csv_round_trip_preserves_external_schedule(self):
        content = (
            "schedule_id,start_date,end_date,topic\n"
            "99,2026/10/05/18/00,2026/10/05/20/00,work\n"
        )
        periods = parse_busy_csv(content)

        self.assertEqual(periods[0].start_datetime, datetime(2026, 10, 5, 18, 0))
        rendered = render_schedule_csv(periods, [])
        self.assertIn("external-99,2026/10/05/18/00,2026/10/05/20/00,work", rendered)

    def test_availability_profile_is_saved_and_loaded(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            config_path = Path(temp_dir) / "availability.json"
            expected = {
                "monday": [{"start": "18:00", "end": "21:00"}],
                "saturday": [{"start": "10:00", "end": "12:00"}],
            }
            saved = save_personal_availability(expected, config_path)
            loaded = load_personal_availability(config_path)
            document = json.loads(config_path.read_text(encoding="utf-8"))

        self.assertEqual(saved, loaded)
        self.assertEqual(document["weekly_availability"], expected)

    def test_overlapping_or_invalid_availability_is_rejected(self):
        invalid_values = (
            {"monday": [{"start": "20:00", "end": "18:00"}]},
            {"monday": [
                {"start": "18:00", "end": "20:00"},
                {"start": "19:30", "end": "21:00"},
            ]},
            {"Funday": []},
        )
        for value in invalid_values:
            with self.subTest(value=value), self.assertRaises(ValueError):
                parse_weekly_availability(value)

    def test_topic_statistics_aggregate_weighted_results(self):
        stats = summarize_topic_results((
            ProblemResult(1, "함수와 데이터 관리", 0.7, 0.0, 0.7),
            ProblemResult(1, "스프레드시트 기본", 0.3, 0.3, 0.3),
            ProblemResult(2, "함수와 데이터 관리", 1.0, 1.0),
        ))
        by_topic = {item.topic: item for item in stats}

        self.assertEqual(by_topic["함수와 데이터 관리"].possible_score, 1.7)
        self.assertEqual(by_topic["함수와 데이터 관리"].earned_score, 1.0)
        self.assertEqual(by_topic["스프레드시트 기본"].score_percent, 100)


class PlanningScheduleTests(unittest.TestCase):
    def setUp(self):
        self.certification = get_certification("computer_specialist_level_2")
        self.stats = summarize_topic_results((ProblemResult(1, "차트와 분석", 1, 0),))

    def test_one_study_block_can_exceed_one_hour(self):
        request = make_plan_request()
        blocks = generate_schedule(request, self.certification, self.stats, [{
            "date": "2026/10/05", "topic": "차트와 분석", "minutes": 150,
        }])

        self.assertEqual(len(blocks), 1)
        self.assertEqual(blocks[0].end_datetime - blocks[0].start_datetime,
                         timedelta(minutes=150))
        self.assertEqual(blocks[0].start_datetime, datetime(2026, 10, 5, 18, 0))
        self.assertEqual(blocks[0].end_datetime, datetime(2026, 10, 5, 20, 30))

    def test_busy_period_splits_a_plan_only_at_the_unavailable_interval(self):
        busy = BusyPeriod(
            "99", datetime(2026, 10, 5, 19, 0), datetime(2026, 10, 5, 19, 30), "work"
        )
        request = make_plan_request(
            availability=parse_weekly_availability({
                "monday": [{"start": "18:00", "end": "21:00"}],
            }),
            busy_periods=(busy,),
        )
        blocks = generate_schedule(request, self.certification, self.stats, [{
            "date": "2026/10/05", "topic": "차트와 분석", "minutes": 120,
        }])

        self.assertEqual(len(blocks), 2)
        self.assertEqual(sum((block.end_datetime - block.start_datetime
                              for block in blocks), timedelta()), timedelta(minutes=120))
        self.assertEqual(blocks[0].end_datetime, busy.start_datetime)
        self.assertEqual(blocks[1].start_datetime, busy.end_datetime)

    def test_requested_minutes_cannot_exceed_available_capacity(self):
        request = make_plan_request()

        with self.assertRaisesRegex(ValueError, "NOT_ENOUGH_TIME"):
            generate_schedule(request, self.certification, self.stats, [{
                "date": "2026/10/05", "topic": "차트와 분석", "minutes": 241,
            }])

    def test_schedule_requires_personal_weekly_availability(self):
        request = PlanRequest(
            self_assessment=None,
            problem_results=(),
            preparation_start=datetime(2026, 10, 5, 18, 0),
            exam_date=datetime(2026, 11, 30, 23, 0),
            weekly_availability=None,
        )

        with self.assertRaisesRegex(ValueError, "weekly_availability is required"):
            generate_schedule(request, self.certification, self.stats, [{
                "date": "2026/10/05", "topic": "차트와 분석", "minutes": 30,
            }])


class PlanningApiContractTests(unittest.TestCase):
    def test_api_request_maps_one_question_to_multiple_catalog_topics(self):
        request = build_plan_request(make_api_payload(), require_assessment=True)
        tools = PlannerTools(request)
        mapping = [{
            "problem_id": 1,
            "topics": [
                {"topic": "차트와 분석", "weight": 0.7},
                {"topic": "스프레드시트 기본", "weight": 0.3},
            ],
        }]

        profile = tools.call("get_certification_profile", {
            "certification_id": request.certification_id,
        })
        result = tools.call("analyze_exam_results", {"problem_topics": mapping})

        self.assertEqual(profile["topics"], list(CERTIFICATIONS[request.certification_id].topics))
        self.assertEqual({row["topic"] for row in result["topic_statistics"]},
                         {"차트와 분석", "스프레드시트 기본"})
        self.assertEqual(sum(row["possible_score"] for row in result["topic_statistics"]), 1)

    def test_planner_tool_builds_schedule_after_question_topic_analysis(self):
        request = build_plan_request(make_api_payload(), require_assessment=True)
        tools = PlannerTools(request)
        tools.call("analyze_exam_results", {"problem_topics": [{
            "problem_id": 1,
            "topics": [{"topic": "차트와 분석", "weight": 1}],
        }]})

        result = tools.call("build_study_schedule", {"daily_topic_minutes": [{
            "date": "2026/10/05", "topic": "차트와 분석", "minutes": 90,
        }]})

        self.assertIsInstance(result, dict, result)
        self.assertEqual(result["total_planned_minutes"], 90)
        self.assertEqual(len(tools.schedule), 1)
        self.assertEqual(tools.schedule[0].end_datetime - tools.schedule[0].start_datetime,
                         timedelta(minutes=90))

    def test_api_requires_preparation_exam_and_weekly_availability(self):
        valid = make_api_payload()
        for key in ("preparation_start", "exam_date", "weekly_availability"):
            payload = dict(valid)
            payload.pop(key)
            with (self.subTest(missing=key),
                  patch("api_service.load_personal_availability", return_value=None),
                  self.assertRaises(ValueError)):
                build_plan_request(payload, require_assessment=True)

    def test_api_rejects_preparation_start_after_exam_date(self):
        payload = make_api_payload()
        payload["preparation_start"] = "2026/12/01/18/00"

        with self.assertRaisesRegex(ValueError, "preparation_start must be before exam_date"):
            build_plan_request(payload, require_assessment=True)

    def test_planning_tools_do_not_offer_readiness_levels(self):
        tool_names = [schema["name"] for schema in tool_schemas()]

        self.assertEqual(tool_names, [
            "get_certification_profile", "analyze_exam_results", "build_study_schedule",
        ])


if __name__ == "__main__":
    unittest.main()
