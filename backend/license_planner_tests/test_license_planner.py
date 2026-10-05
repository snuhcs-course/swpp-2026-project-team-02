import json
import tempfile
import unittest
from datetime import datetime, timedelta
from pathlib import Path
from unittest.mock import patch

from license_planner.catalog import CERTIFICATIONS, get_certification
from api_service import build_plan_request
from license_planner.csv_io import (parse_busy_csv, parse_datetime,
                                    parse_problem_results_csv, render_schedule_csv)
from license_planner.models import PlanRequest, ToolTrace
from license_planner.scheduler import generate_schedule, readiness_level, summarize_topic_results
from license_planner.tools import PlannerTools, tool_schemas
from license_planner.settings import _load_schedule_config
from license_planner.user_availability import (load_personal_availability,
                                               parse_weekly_availability,
                                               save_personal_availability)


RESULTS_CSV = """problem_id,topic,possible_score,earned_score
1,Spreadsheet Basics,10,8
2,Functions,10,3
3,Charts,5,1
4,Functions,10,7
"""
BUSY_CSV = """schedule_id,start_date,end_date,topic
99,2026/10/05/18/00,2026/10/05/20/00,work
"""


def make_request(self_assessment="엑셀은 처음이라 잘 모릅니다"):
    """입력 값: 선택적 자기평가 문장
    출력 값: 테스트에 사용할 PlanRequest
    기능: 날짜·점수·일정 CSV를 파싱해 사용자 요청 객체를 구성합니다.
    """
    return PlanRequest(
        self_assessment=self_assessment,
        problem_results=parse_problem_results_csv(RESULTS_CSV),
        preparation_start=parse_datetime("2026/10/05/17/00"),
        exam_date=parse_datetime("2026/11/30/23/00"),
        busy_periods=parse_busy_csv(BUSY_CSV),
        weekly_availability=parse_weekly_availability({
            "monday": [{"start": "18:00", "end": "22:00"}],
            "tuesday": [{"start": "18:00", "end": "22:00"}],
            "wednesday": [{"start": "18:00", "end": "22:00"}],
            "thursday": [{"start": "18:00", "end": "22:00"}],
            "friday": [{"start": "18:00", "end": "22:00"}],
        }),
    )


class CsvTests(unittest.TestCase):
    def test_timestamp_format_and_busy_csv_round_trip(self):
        """
        입력 값: 테스트 픽스처와 해당 시나리오의 입력
        출력 값: 모든 검증 단언 통과 시 테스트 통과
        기능: 일정 시각 형식과 CSV 변환 후 행 보존을 확인합니다.
        """
        periods = parse_busy_csv(BUSY_CSV)
        self.assertEqual(periods[0].start_datetime, datetime(2026, 10, 5, 18, 0))
        output = render_schedule_csv(periods, [])
        self.assertIn("schedule_id,start_date,end_date,topic", output)
        self.assertIn("external-99,2026/10/05/18/00,2026/10/05/20/00,work", output)

    def test_external_schedule_config_overrides_session_settings(self):
        """
        입력 값: 테스트 픽스처와 해당 시나리오의 입력
        출력 값: 모든 검증 단언 통과 시 테스트 통과
        기능: 외부 JSON 설정이 공부 시간대와 세션 정책에 적용되는지 확인합니다.
        """
        with tempfile.TemporaryDirectory() as temp_dir:
            config_path = Path(temp_dir) / "planner.json"
            config_path.write_text(json.dumps({
                "session_length_minutes": 45, "max_sessions_per_day": 2,
            }), encoding="utf-8")
            config = _load_schedule_config(config_path)
        self.assertEqual(config["session_length_minutes"], 45)
        self.assertEqual(config["max_sessions_per_day"], 2)

    def test_user_availability_is_saved_and_loaded_from_user_json(self):
        """
        입력 값: 테스트 픽스처와 해당 시나리오의 입력
        출력 값: 모든 검증 단언 통과 시 테스트 통과
        기능: 개인 가능 시간 JSON 저장과 재로딩 결과를 비교합니다.
        """
        with tempfile.TemporaryDirectory() as temp_dir:
            config_path = Path(temp_dir) / "availability.json"
            saved = save_personal_availability({
                "monday": [{"start": "18:00", "end": "21:00"}],
                "saturday": [{"start": "10:00", "end": "12:00"}],
            }, config_path)
            loaded = load_personal_availability(config_path)
            stored_json = json.loads(config_path.read_text(encoding="utf-8"))
        self.assertEqual(saved, loaded)
        self.assertEqual(stored_json["weekly_availability"]["monday"][0],
                         {"start": "18:00", "end": "21:00"})

    def test_user_availability_rejects_invalid_or_overlapping_windows(self):
        """
        입력 값: 테스트 픽스처와 해당 시나리오의 입력
        출력 값: 모든 검증 단언 통과 시 테스트 통과
        기능: 역순·중복 시간 구간과 알 수 없는 요일을 거부합니다.
        """
        for payload in (
            {"monday": [{"start": "20:00", "end": "18:00"}]},
            {"monday": [{"start": "18:00", "end": "20:00"},
                        {"start": "19:00", "end": "21:00"}]},
            {"Funday": []},
        ):
            with self.subTest(payload=payload), self.assertRaises(ValueError):
                parse_weekly_availability(payload)

    def test_problem_results_aggregate_dynamic_topics(self):
        """
        입력 값: 테스트 픽스처와 해당 시나리오의 입력
        출력 값: 모든 검증 단언 통과 시 테스트 통과
        기능: 입력 주제를 고정 목록 없이 점수 통계로 합산합니다.
        """
        stats = summarize_topic_results(parse_problem_results_csv(RESULTS_CSV))
        self.assertEqual([item.topic for item in stats], ["Charts", "Functions", "Spreadsheet Basics"])
        functions = next(item for item in stats if item.topic == "Functions")
        self.assertEqual(functions.possible_score, 20)
        self.assertEqual(functions.earned_score, 10)
        self.assertEqual(functions.score_percent, 50)

    def test_empty_results_and_busy_csv_are_allowed(self):
        """
        입력 값: 테스트 픽스처와 해당 시나리오의 입력
        출력 값: 모든 검증 단언 통과 시 테스트 통과
        기능: 빈 시험 결과와 바쁜 일정 입력을 허용합니다.
        """
        self.assertEqual(parse_problem_results_csv(None), ())
        self.assertEqual(parse_busy_csv(None), ())

    def test_rejects_invalid_problem_scores_ids_and_timestamps(self):
        """
        입력 값: 테스트 픽스처와 해당 시나리오의 입력
        출력 값: 모든 검증 단언 통과 시 테스트 통과
        기능: 잘못된 점수, ID, 날짜·시간 입력을 거부합니다.
        """
        invalid_results = (
            "problem_id,topic,possible_score,earned_score\n1,Functions,10,11\n",
            "1,,10,5\n",
            "1,Functions,0,0\n",
            "1,Functions,10,5\n1,Charts,10,5\n",
        )
        for content in invalid_results:
            with self.subTest(content=content), self.assertRaises(ValueError):
                parse_problem_results_csv(content)
        with self.assertRaises(ValueError):
            parse_datetime("2026/10/05")
        with self.assertRaisesRegex(ValueError, "positive integer"):
            parse_busy_csv("abc,2026/10/05/18/00,2026/10/05/20/00\n")
        with self.assertRaisesRegex(ValueError, "duplicate schedule ID"):
            parse_busy_csv("7,2026/10/05/18/00,2026/10/05/20/00\n7,2026/10/06/18/00,2026/10/06/20/00\n")


class PlanTests(unittest.TestCase):
    def setUp(self):
        """
        입력 값: 없음
        출력 값: 테스트 공통 데이터 준비 상태
        기능: 테스트 메서드에서 재사용할 픽스처를 초기화합니다.
        """
        self.cert = get_certification("computer_specialist_level_2")

    def test_weighted_problem_score_and_self_assessment_determine_level(self):
        """
        입력 값: 테스트 픽스처와 해당 시나리오의 입력
        출력 값: 모든 검증 단언 통과 시 테스트 통과
        기능: 점수와 자기평가를 함께 사용해 더 필요한 학습 수준을 선택합니다.
        """
        stats = summarize_topic_results(parse_problem_results_csv(RESULTS_CSV))
        self.assertEqual(readiness_level("엑셀은 처음입니다", stats, self.cert), "beginner")
        self.assertEqual(readiness_level("함수에 능숙합니다", stats, self.cert), "beginner")
        self.assertEqual(readiness_level("함수에 능숙합니다", (), self.cert), "advanced")
        self.assertEqual(readiness_level(None, (), self.cert), "intermediate")

    def test_weakest_dynamic_topic_is_scheduled_first_and_busy_time_is_skipped(self):
        """
        입력 값: 테스트 픽스처와 해당 시나리오의 입력
        출력 값: 모든 검증 단언 통과 시 테스트 통과
        기능: 취약 토픽을 먼저 배치하고 기존 일정과의 충돌을 피합니다.
        """
        request = make_request()
        stats = summarize_topic_results(request.problem_results)
        blocks = generate_schedule(request, self.cert, stats, "beginner")
        self.assertGreater(len(blocks), 0)
        self.assertEqual(blocks[0].topic, "Charts")
        self.assertNotEqual(blocks[0].start_datetime, datetime(2026, 10, 5, 18, 0))
        self.assertTrue(all(block.end_datetime - block.start_datetime == timedelta(hours=1) for block in blocks))
        self.assertTrue(all(block.start_datetime < request.exam_date for block in blocks))
        output = render_schedule_csv(request.busy_periods, blocks)
        self.assertIn("external-99,2026/10/05/18/00,2026/10/05/20/00,work", output)
        self.assertIn("study-100,2026/10/05/20/00,2026/10/05/21/00,Charts", output)

    def test_plan_uses_personal_weekday_and_time_windows(self):
        """
        입력 값: 테스트 픽스처와 해당 시나리오의 입력
        출력 값: 모든 검증 단언 통과 시 테스트 통과
        기능: 공부 계획이 개인 요일·시간표 안에 배치되는지 확인합니다.
        """
        request = make_request()
        input_availability = {
            "monday": [{"start": "20:00", "end": "22:00"}],
            "tuesday": [{"start": "09:00", "end": "12:00"}],
            "wednesday": [{"start": "10:00", "end": "12:00"}],
        }
        with tempfile.TemporaryDirectory() as temp_dir:
            config_path = Path(temp_dir) / "availability.json"
            save_personal_availability(input_availability, config_path)
            availability = load_personal_availability(config_path)
        personal_request = PlanRequest(
            request.self_assessment, request.problem_results,
            request.preparation_start, request.exam_date, request.busy_periods,
            weekly_availability=availability,
        )
        blocks = generate_schedule(personal_request, self.cert,
                                   summarize_topic_results(personal_request.problem_results), "beginner")
        self.assertEqual(blocks[0].start_datetime, datetime(2026, 10, 5, 20, 0))
        self.assertTrue(all(
            (block.start_datetime.weekday() == 0 and block.start_datetime.hour == 20)
            or (block.start_datetime.weekday() == 1 and block.start_datetime.hour == 9)
            or (block.start_datetime.weekday() == 2 and block.start_datetime.hour == 10)
            for block in blocks
        ))
        tools = PlannerTools(personal_request)
        tools.call("analyze_exam_results", {})
        tools.call("assess_readiness", {})
        tool_result = tools.call("build_study_schedule", {})
        self.assertTrue(tool_result["uses_personal_availability"])

    def test_insufficient_time_has_actionable_error(self):
        """
        입력 값: 테스트 픽스처와 해당 시나리오의 입력
        출력 값: 모든 검증 단언 통과 시 테스트 통과
        기능: 학습 시간을 배치할 수 없을 때 명시적인 오류를 반환합니다.
        """
        request = PlanRequest(
            None, (), datetime(2026, 10, 5, 8), datetime(2026, 10, 5, 9),
            weekly_availability=parse_weekly_availability({
                "monday": [{"start": "08:00", "end": "09:00"}],
            }),
        )
        with self.assertRaisesRegex(ValueError, "NOT_ENOUGH_TIME"):
            generate_schedule(request, self.cert, (), "beginner")

    def test_plan_requires_user_weekday_availability(self):
        """기본 공통 시간대를 대신 쓰지 않고 입력 오류를 반환합니다."""
        request = PlanRequest(None, (), datetime(2026, 10, 5, 8), datetime(2026, 11, 30, 23))
        with self.assertRaisesRegex(ValueError, "weekly_availability is required"):
            generate_schedule(request, self.cert, (), "beginner")

        payload = {
            "preparation_start": "2026/10/05/08/00",
            "exam_date": "2026/11/30/23/00",
        }
        with patch("api_service.load_personal_availability", return_value=None):
            for request_payload in (payload, {**payload, "weekly_availability": {}}):
                with self.subTest(request_payload=request_payload), self.assertRaisesRegex(
                        ValueError, "weekly_availability is required"):
                    build_plan_request(request_payload)


class ToolUseEvaluationTests(unittest.TestCase):
    """도구 호출 순서와 결과를 Tutorial 03의 tool trajectory 방식으로 평가합니다."""

    def test_assessment_tools_and_final_schedule(self):
        """
        입력 값: 테스트 픽스처와 해당 시나리오의 입력
        출력 값: 모든 검증 단언 통과 시 테스트 통과
        기능: 평가 도구 호출 순서와 최종 일정 생성을 확인합니다.
        """
        request = make_request()
        trace = ToolTrace()
        tools = PlannerTools(request, trace)
        profile = tools.call("get_certification_profile", {"certification_id": request.certification_id})
        statistics = tools.call("analyze_exam_results", {})
        level = tools.call("assess_readiness", {})
        schedule = tools.call("build_study_schedule", {})
        self.assertEqual(profile["name"], "컴퓨터활용능력 2급")
        self.assertEqual(statistics["topic_statistics"][0]["topic"], "Charts")
        self.assertEqual(level["level"], "beginner")
        self.assertGreater(schedule["study_block_count"], 0)
        self.assertEqual([call["tool"] for call in trace.calls], [
            "get_certification_profile", "analyze_exam_results", "assess_readiness", "build_study_schedule"
        ])

    def test_tools_explain_prerequisites_and_unknown_certification(self):
        """
        입력 값: 테스트 픽스처와 해당 시나리오의 입력
        출력 값: 모든 검증 단언 통과 시 테스트 통과
        기능: 도구 선행 조건과 미지원 자격증 오류를 확인합니다.
        """
        tools = PlannerTools(make_request())
        self.assertIn("PREREQUISITE", tools.call("assess_readiness", {}))
        self.assertIn("NOT_FOUND", tools.call("get_certification_profile", {"certification_id": "invented"}))

    def test_schemas_constrain_certification_but_leave_topics_data_driven(self):
        """
        입력 값: 테스트 픽스처와 해당 시나리오의 입력
        출력 값: 모든 검증 단언 통과 시 테스트 통과
        기능: 자격증 인자는 제한하고 시험 주제는 자유 입력으로 둡니다.
        """
        schemas = tool_schemas()
        profile_schema = schemas[0]["parameters"]["properties"]["certification_id"]
        self.assertEqual(profile_schema["enum"], sorted(CERTIFICATIONS))
        self.assertIn("analyze_exam_results", [schema["name"] for schema in schemas])

    def test_all_new_agent_case_fixtures_match_expected_local_tools(self):
        """입력 값: JSON Planning API 요청 fixture
        출력 값: 각 케이스가 도구 예상 수준과 취약 토픽을 통과하는지 여부
        기능: Gemini 없이 API JSON 변환과 deterministic tool path를 검증합니다.
        """
        project_root = Path(__file__).resolve().parents[1]
        input_dir = project_root / "license_planner_test_inputs"
        cases = json.loads((input_dir / "license_planner_test_cases.json").read_text(encoding="utf-8-sig"))
        for case in cases:
            with self.subTest(case=case["case_id"]):
                request = build_plan_request(case["request"])
                self.assertIsNotNone(request.weekly_availability)
                tools = PlannerTools(request)
                tools.call("get_certification_profile", {"certification_id": request.certification_id})
                tools.call("analyze_exam_results", {})
                level = tools.call("assess_readiness", {})
                plan = tools.call("build_study_schedule", {})
                self.assertEqual(level["level"], case["expected_level"])
                self.assertIsInstance(plan, dict, plan)
                self.assertGreater(plan["study_block_count"], 0)
                self.assertEqual(tools.schedule[0].topic, case["expected_first_topic"])


if __name__ == "__main__":
    unittest.main()
