import json
import unittest
from datetime import date, datetime, timedelta
from unittest.mock import patch

from license_planner.agent import GeminiToolAgent
from license_planner.catalog import get_certification
from license_planner.models import (
    AssessmentItem, BusyPeriod, InsufficientCapacityError, PlanRequest, ProblemResult,
)
from license_planner.scheduler import (
    available_minutes, generate_schedule, summarize_topic_results, usable_minutes_by_date,
)
from license_planner.tools import PlannerTools, check_initial_capacity
from license_planner.user_availability import parse_weekly_availability

CERT = get_certification("computer_specialist_level_2")
BASICS, FUNCTIONS, CHARTS = CERT.topics
MONDAY = date(2026, 10, 19)


def at(day, hour, minute=0, second=0):
    return datetime(day.year, day.month, day.day, hour, minute, second)


def busy(schedule_id, start, end):
    return BusyPeriod(schedule_id, start, end, "외부 일정")


def make_request(*, start, exam, windows, busy_periods=(), questions=1, problem_results=()):
    items = tuple(AssessmentItem(problem_id, f"문항 {problem_id}", {"A": "가", "B": "나"}, "A", "B", False)
                  for problem_id in range(1, questions + 1))
    return PlanRequest(
        self_assessment="기본 기능은 사용해 보았습니다.",
        problem_results=tuple(problem_results),
        preparation_start=start,
        exam_date=exam,
        assessment_items=items,
        busy_periods=tuple(busy_periods),
        weekly_availability=parse_weekly_availability(windows),
    )


def one_minute_request(**kwargs):
    return make_request(start=at(MONDAY, 18), exam=at(MONDAY, 18, 1),
                        windows={"monday": [{"start": "18:00", "end": "20:00"}]}, **kwargs)


def mapping(*topics, questions=1):
    weights = [round(1 / len(topics), 4)] * len(topics)
    weights[0] = round(1 - sum(weights[1:]), 4)
    return [{"problem_id": problem_id,
             "topics": [{"topic": topic, "weight": weight} for topic, weight in zip(topics, weights)]}
            for problem_id in range(1, questions + 1)]


def minutes(*rows):
    return [{"date": day, "topic": topic, "minutes": amount} for day, topic, amount in rows]


def model_reply(*calls, text=None):
    parts = [{"functionCall": {"name": name, "args": args}} for name, args in calls]
    if text is not None:
        parts.append({"text": text})
    return {"candidates": [{"content": {"role": "model", "parts": parts}}]}


def run_agent(request, replies, max_steps=6):
    with patch.object(GeminiToolAgent, "_ask_model", side_effect=replies) as ask_model:
        agent = GeminiToolAgent(api_key="test-key", max_steps=max_steps)
        try:
            return agent.run(request), ask_model
        except Exception as error:
            error.ask_model = ask_model
            raise


class UsableCapacityTests(unittest.TestCase):
    def test_clipped_day_with_overlapping_busy_periods_has_thirty_minutes(self):
        request = make_request(
            start=at(MONDAY, 18, 30), exam=at(MONDAY, 19, 30),
            windows={"monday": [{"start": "18:00", "end": "20:00"}]},
            busy_periods=[busy("1", at(MONDAY, 18, 45), at(MONDAY, 19, 5)),
                          busy("2", at(MONDAY, 19), at(MONDAY, 19, 15))])
        stats = summarize_topic_results((ProblemResult(1, CHARTS, 1, 0),))

        self.assertEqual(available_minutes(request), 30)
        self.assertEqual(usable_minutes_by_date(request), {MONDAY: 30})
        blocks = generate_schedule(request, CERT, stats, minutes(("2026/10/19", CHARTS, 30)))
        self.assertEqual([(b.start_datetime, b.end_datetime) for b in blocks],
                         [(at(MONDAY, 18, 30), at(MONDAY, 18, 45)), (at(MONDAY, 19, 15), at(MONDAY, 19, 30))])
        with self.assertRaisesRegex(ValueError, "NOT_ENOUGH_TIME: 31 planned minutes exceed 30"):
            generate_schedule(request, CERT, stats, minutes(("2026/10/19", CHARTS, 31)))

    def test_nested_and_adjacent_busy_periods_are_subtracted_once(self):
        windows = {"monday": [{"start": "18:00", "end": "21:00"}]}
        nested = make_request(start=at(MONDAY, 0), exam=at(MONDAY, 23), windows=windows,
                              busy_periods=[busy("1", at(MONDAY, 18), at(MONDAY, 20)),
                                            busy("2", at(MONDAY, 18, 30), at(MONDAY, 19))])
        adjacent = make_request(start=at(MONDAY, 0), exam=at(MONDAY, 23), windows=windows,
                                busy_periods=[busy("1", at(MONDAY, 17), at(MONDAY, 18)),
                                              busy("2", at(MONDAY, 18), at(MONDAY, 19)),
                                              busy("3", at(MONDAY, 19), at(MONDAY, 20))])

        self.assertEqual(available_minutes(nested), 60)
        self.assertEqual(available_minutes(adjacent), 60)
        stats = summarize_topic_results((ProblemResult(1, CHARTS, 1, 0),))
        block, = generate_schedule(adjacent, CERT, stats, minutes(("2026/10/19", CHARTS, 60)))
        self.assertEqual((block.start_datetime, block.end_datetime), (at(MONDAY, 20), at(MONDAY, 21)))

    def test_disjoint_and_weekend_windows_count_only_dates_inside_the_period(self):
        windows = {"monday": [{"start": "18:00", "end": "19:00"}],
                   "saturday": [{"start": "10:00", "end": "12:00"}],
                   "sunday": [{"start": "09:00", "end": "09:30"}, {"start": "14:00", "end": "15:00"}]}
        request = make_request(start=at(date(2026, 10, 23), 0), exam=at(date(2026, 10, 26), 18, 30),
                               windows=windows)

        self.assertEqual(usable_minutes_by_date(request), {
            date(2026, 10, 23): 0, date(2026, 10, 24): 120, date(2026, 10, 25): 90, date(2026, 10, 26): 30})
        weekend_only = make_request(start=at(date(2026, 10, 24), 0), exam=at(date(2026, 10, 25), 23),
                                    windows={"monday": [{"start": "18:00", "end": "22:00"}]})
        self.assertEqual(available_minutes(weekend_only), 0)

    def test_fully_blocked_windows_and_sub_minute_pieces_have_no_capacity(self):
        blocked = make_request(start=at(MONDAY, 0), exam=at(MONDAY, 23),
                               windows={"monday": [{"start": "18:00", "end": "20:00"}]},
                               busy_periods=[busy("1", at(MONDAY, 17), at(MONDAY, 21))])
        sub_minute = make_request(start=at(MONDAY, 0), exam=at(MONDAY, 23),
                                  windows={"monday": [{"start": "18:00", "end": "18:01"}]},
                                  busy_periods=[busy("1", at(MONDAY, 18), at(MONDAY, 18, 0, 30))])
        stats = summarize_topic_results((ProblemResult(1, CHARTS, 1, 0),))

        for request in (blocked, sub_minute):
            with self.subTest(busy=request.busy_periods):
                self.assertEqual(available_minutes(request), 0)
                with self.assertRaisesRegex(ValueError, "NOT_ENOUGH_TIME"):
                    generate_schedule(request, CERT, stats, minutes(("2026/10/19", CHARTS, 1)))


class InitialCapacityTests(unittest.TestCase):
    def test_question_inputs_require_only_one_minute_before_topic_mapping(self):
        check_initial_capacity(one_minute_request(questions=5))

        no_minutes = make_request(start=at(MONDAY, 17), exam=at(MONDAY, 18),
                                  windows={"monday": [{"start": "18:00", "end": "20:00"}]})
        with self.assertRaisesRegex(InsufficientCapacityError,
                                    "NOT_ENOUGH_TIME: .*시간이 0분이라 학습 주제 1개에도"
                                    ".*공부 가능 시간을 늘리거나, 준비를 더 일찍 시작하거나, 시험일을 늦춰 주세요"):
            check_initial_capacity(no_minutes)

    def test_zero_capacity_stops_before_any_model_request(self):
        request = make_request(start=at(MONDAY, 17), exam=at(MONDAY, 18),
                               windows={"monday": [{"start": "18:00", "end": "20:00"}]})

        with self.assertRaises(InsufficientCapacityError) as raised:
            run_agent(request, [])
        self.assertEqual(raised.exception.ask_model.call_count, 0)

    def test_legacy_topic_results_use_their_exact_distinct_topics(self):
        results = (ProblemResult(1, CHARTS, 1, 0), ProblemResult(2, CHARTS, 1, 1), ProblemResult(3, BASICS, 1, 0))
        request = one_minute_request(questions=0, problem_results=results)

        with self.assertRaisesRegex(InsufficientCapacityError, "학습 주제 2개에") as raised:
            run_agent(request, [])
        self.assertEqual(raised.exception.ask_model.call_count, 0)
        two_minutes = make_request(start=at(MONDAY, 18), exam=at(MONDAY, 18, 2), problem_results=results,
                                   questions=0, windows={"monday": [{"start": "18:00", "end": "20:00"}]})
        check_initial_capacity(two_minutes)

    def test_assessment_items_take_precedence_over_legacy_topic_results(self):
        results = (ProblemResult(1, CHARTS, 1, 0), ProblemResult(2, BASICS, 1, 0))

        check_initial_capacity(one_minute_request(questions=1, problem_results=results))

    def test_no_result_fallback_uses_the_selected_catalog_topics(self):
        def request_until(minute):
            return make_request(start=at(MONDAY, 18), exam=at(MONDAY, 18, minute), questions=0,
                                windows={"monday": [{"start": "18:00", "end": "20:00"}]})

        with self.assertRaisesRegex(InsufficientCapacityError, f"학습 주제 {len(CERT.topics)}개에"):
            check_initial_capacity(request_until(len(CERT.topics) - 1))
        check_initial_capacity(request_until(len(CERT.topics)))


class AnalyzedTopicCapacityTests(unittest.TestCase):
    def test_one_minute_suffices_for_one_mapped_topic(self):
        tools = PlannerTools(one_minute_request())

        self.assertIsInstance(tools.call("analyze_exam_results", {"problem_topics": mapping(CHARTS)}), dict)
        result = tools.call("build_study_schedule", {"daily_topic_minutes": minutes(("2026/10/19", CHARTS, 1))})
        self.assertIsInstance(result, dict, result)
        self.assertEqual([(b.topic, b.start_datetime, b.end_datetime) for b in tools.schedule],
                         [(CHARTS, at(MONDAY, 18), at(MONDAY, 18, 1))])

    def test_more_mapped_topics_than_minutes_stops_at_analysis(self):
        for topics in ((CHARTS, BASICS), (CHARTS, BASICS, FUNCTIONS)):
            for capacity in (len(topics) - 1, len(topics)):
                request = make_request(start=at(MONDAY, 18), exam=at(MONDAY, 18, capacity),
                                       windows={"monday": [{"start": "18:00", "end": "20:00"}]})
                tools = PlannerTools(request)
                with self.subTest(topics=len(topics), capacity=capacity):
                    if capacity < len(topics):
                        with self.assertRaisesRegex(InsufficientCapacityError,
                                                    f"시간이 {capacity}분이라 .*학습 주제 {len(topics)}개에"):
                            tools.call("analyze_exam_results", {"problem_topics": mapping(*topics)})
                        with self.assertRaises(InsufficientCapacityError):
                            tools.call("build_study_schedule", {"daily_topic_minutes": minutes(
                                *(("2026/10/19", topic, 1) for topic in topics))})
                        self.assertIsNone(tools.schedule)
                    else:
                        self.assertIsInstance(tools.call("analyze_exam_results",
                                                         {"problem_topics": mapping(*topics)}), dict)

    def test_incomplete_mapping_is_an_ordinary_tool_error(self):
        tools = PlannerTools(one_minute_request(questions=2))

        result = tools.call("analyze_exam_results", {"problem_topics": mapping(CHARTS, BASICS)[:1]})

        self.assertRegex(result, "^INVALID_TOOL_CALL: problem_topics must map every assessment problem")
        self.assertFalse(tools.results_analyzed)

    def test_bundled_analysis_and_build_stop_before_the_build_runs(self):
        reply = model_reply(("analyze_exam_results", {"problem_topics": mapping(CHARTS, BASICS)}),
                            ("build_study_schedule", {"daily_topic_minutes": minutes(
                                ("2026/10/19", CHARTS, 1), ("2026/10/19", BASICS, 1))}))

        with self.assertRaisesRegex(InsufficientCapacityError, "학습 주제 2개에") as raised:
            run_agent(one_minute_request(), [reply, reply])
        self.assertEqual(raised.exception.ask_model.call_count, 1)

    def test_oversized_proposal_for_feasible_input_can_be_corrected(self):
        request = make_request(start=at(MONDAY, 18), exam=at(MONDAY, 19),
                               windows={"monday": [{"start": "18:00", "end": "20:00"}]})
        replies = [
            model_reply(("analyze_exam_results", {"problem_topics": mapping(CHARTS, BASICS)})),
            model_reply(("build_study_schedule", {"daily_topic_minutes": minutes(
                ("2026/10/19", CHARTS, 100), ("2026/10/19", BASICS, 50))})),
            model_reply(("build_study_schedule", {"daily_topic_minutes": minutes(
                ("2026/10/19", CHARTS, 40), ("2026/10/19", BASICS, 20))})),
        ]

        (tools, _), ask_model = run_agent(request, replies)

        self.assertEqual(ask_model.call_count, 3)
        self.assertEqual(sum((b.end_datetime - b.start_datetime for b in tools.schedule), timedelta()),
                         timedelta(minutes=60))

    def test_model_context_lists_usable_minutes_by_date(self):
        request = make_request(start=at(MONDAY, 18), exam=at(date(2026, 10, 21), 19),
                               windows={"monday": [{"start": "18:00", "end": "20:00"}],
                                        "wednesday": [{"start": "18:00", "end": "20:00"}]})
        replies = [model_reply(("analyze_exam_results", {"problem_topics": mapping(CHARTS)})),
                   model_reply(("build_study_schedule", {"daily_topic_minutes": minutes(("2026/10/19", CHARTS, 30))}))]

        _, ask_model = run_agent(request, replies)

        prompt = ask_model.call_args_list[0].args[0][0]["parts"][0]["text"]
        metadata = json.loads(prompt.split("Input metadata: ", 1)[1])
        self.assertEqual(metadata["usable_minutes_by_date"], {"2026/10/19": 120, "2026/10/21": 60})


class TerminalBuildTests(unittest.TestCase):
    VALID_BUILD = ("build_study_schedule", {"daily_topic_minutes": minutes(("2026/10/19", CHARTS, 60))})

    def request(self):
        return make_request(start=at(MONDAY, 18), exam=at(date(2026, 10, 26), 18),
                            windows={"monday": [{"start": "18:00", "end": "20:00"}]})

    def test_bundled_analysis_and_build_succeed_within_one_request(self):
        reply = model_reply(("analyze_exam_results", {"problem_topics": mapping(CHARTS)}), self.VALID_BUILD)

        (tools, summary), ask_model = run_agent(self.request(), [reply], max_steps=1)

        self.assertEqual(ask_model.call_count, 1)
        self.assertEqual(len(tools.schedule), 1)
        self.assertEqual(summary, "1개 과목에 총 60분을 학습 일정 1개로 배치했습니다.")

    def test_build_on_the_last_permitted_request_is_returned(self):
        rejected = [model_reply(("build_study_schedule", {"daily_topic_minutes": minutes(
            ("2026/10/19", CHARTS, 121 + extra))})) for extra in range(4)]
        replies = [model_reply(("analyze_exam_results", {"problem_topics": mapping(CHARTS)})),
                   *rejected, model_reply(self.VALID_BUILD)]

        (tools, summary), ask_model = run_agent(self.request(), replies)

        self.assertEqual(ask_model.call_count, 6)
        self.assertEqual(len(tools.schedule), 1)
        self.assertTrue(summary)
        self.assertEqual(len(tools.trace.calls), 6)

    def test_calls_after_a_successful_build_are_not_executed(self):
        reply = model_reply(self.VALID_BUILD,
                            ("build_study_schedule", {"daily_topic_minutes": minutes(("2026/10/26", CHARTS, 500))}),
                            ("get_certification_profile", {"certification_id": "unknown"}))
        replies = [model_reply(("analyze_exam_results", {"problem_topics": mapping(CHARTS)})), reply]

        (tools, _), ask_model = run_agent(self.request(), replies)

        self.assertEqual(ask_model.call_count, 2)
        self.assertEqual([call["tool"] for call in tools.trace.calls],
                         ["analyze_exam_results", "build_study_schedule"])
        self.assertEqual(tools.schedule[0].start_datetime, at(MONDAY, 18))

    def test_text_claims_and_rejected_builds_are_not_success(self):
        analysis = model_reply(("analyze_exam_results", {"problem_topics": mapping(CHARTS)}))
        rejected = model_reply(("build_study_schedule", {"daily_topic_minutes": minutes(("2026/10/19", CHARTS, 500))}))
        empty = model_reply(("build_study_schedule", {"daily_topic_minutes": []}))
        cases = {
            "text only": [analysis, model_reply(text="일정을 만들었습니다.")],
            "rejected then text": [analysis, rejected, model_reply(text="완료했습니다.")],
            "empty build then text": [analysis, empty, model_reply(text="완료했습니다.")],
            "rejected on last request": [analysis, rejected],
        }
        for label, replies in cases.items():
            with self.subTest(label), self.assertRaises(RuntimeError) as raised:
                run_agent(self.request(), replies, max_steps=len(replies))
            self.assertNotIsInstance(raised.exception, ValueError)


class RepeatedFailureTests(unittest.TestCase):
    def request(self):
        return make_request(start=at(MONDAY, 18), exam=at(date(2026, 10, 26), 18),
                            windows={"monday": [{"start": "18:00", "end": "20:00"}]})

    def analysis(self):
        return model_reply(("analyze_exam_results", {"problem_topics": mapping(CHARTS)}))

    def test_same_failure_with_reordered_keys_stops_before_another_request(self):
        first = {"daily_topic_minutes": [{"date": "2026/10/19", "topic": CHARTS, "minutes": 500}]}
        reordered = {"daily_topic_minutes": [{"minutes": 500, "topic": CHARTS, "date": "2026/10/19"}]}
        replies = [self.analysis(), model_reply(("build_study_schedule", first)),
                   model_reply(("build_study_schedule", reordered)), model_reply(TerminalBuildTests.VALID_BUILD)]

        with self.assertRaisesRegex(RuntimeError, "unchanged failing build_study_schedule call: SCHEDULE_ERROR") as raised:
            run_agent(self.request(), replies)
        self.assertEqual(raised.exception.ask_model.call_count, 3)

    def test_identical_failures_in_one_response_stop_the_run(self):
        call = ("get_certification_profile", {"certification_id": "unknown"})

        with self.assertRaisesRegex(RuntimeError, "unchanged failing get_certification_profile") as raised:
            run_agent(self.request(), [model_reply(call, call), self.analysis()])
        self.assertEqual(raised.exception.ask_model.call_count, 1)

    def test_corrected_arguments_and_intervening_success_keep_the_run_going(self):
        failing = ("build_study_schedule", {"daily_topic_minutes": minutes(("2026/10/19", CHARTS, 500))})
        replies = [self.analysis(), model_reply(failing),
                   model_reply(("get_certification_profile", {"certification_id": CERT.certification_id})),
                   model_reply(failing),
                   model_reply(("build_study_schedule", {"daily_topic_minutes": minutes(("2026/10/19", CHARTS, 501))})),
                   model_reply(TerminalBuildTests.VALID_BUILD)]

        (tools, _), ask_model = run_agent(self.request(), replies)

        self.assertEqual(ask_model.call_count, 6)
        self.assertEqual(len(tools.schedule), 1)


if __name__ == "__main__":
    unittest.main()
