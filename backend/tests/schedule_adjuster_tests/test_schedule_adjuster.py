import unittest
from datetime import datetime, timedelta

from schedule_adjuster.csv_io import parse_schedule_csv, render_schedule_csv
from schedule_adjuster.availability import parse_weekly_availability
from schedule_adjuster.models import AdjustmentRequest
from schedule_adjuster.schedule_logic import ManualMoveError, move_study_entry, place_study_entry_manually
from schedule_adjuster.tools import ScheduleAdjustmentTools, tool_schemas


SAMPLE_CSV = """schedule_id,start_date,end_date,topic
external-10,2026/10/06/18/00,2026/10/06/20/00,
study-11,2026/10/05/19/00,2026/10/05/20/00,Charts
study-12,2026/10/08/18/00,2026/10/08/20/00,Functions
external-13,2026/10/10/10/00,2026/10/10/12/00,
external-14,2026/10/05/20/00,2026/10/05/21/00,
external-15,2026/10/08/20/00,2026/10/08/21/00,
"""


class SharedAvailabilityParserTests(unittest.TestCase):
    """The adjuster reads the same window forms and overlap rule as the planner."""

    def test_duration_and_legacy_windows_match_the_planner_parser(self):
        from license_planner.user_availability import parse_weekly_availability as planner_parse
        value = {"monday": [{"start": "23:00", "duration_minutes": 90}],
                 "tuesday": [{"start": "09:00", "end": "11:00"}]}
        self.assertEqual(parse_weekly_availability(value), planner_parse(value))

    def test_next_day_overlap_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "overlap"):
            parse_weekly_availability({"monday": [{"start": "23:00", "duration_minutes": 90}],
                                       "tuesday": [{"start": "00:15", "duration_minutes": 60}]})


class EffectiveWindowMovementTests(unittest.TestCase):
    """Automatic movement reads week replacements and keeps overnight sessions whole."""

    ROWS = """schedule_id,start_date,end_date,topic
study-1,2026/11/16/18/00,2026/11/16/19/30,Functions
"""

    def setUp(self):
        self.entries = parse_schedule_csv(self.ROWS)
        self.exam = datetime(2026, 12, 7, 0, 0)

    def moved(self, availability, exam=None):
        result = move_study_entry(self.entries, "study-1", availability, exam or self.exam)
        target = result[0]
        return target.start_datetime, target.end_datetime

    def test_ninety_minutes_fit_a_future_override_across_midnight(self):
        context = parse_weekly_availability({
            "weekly_availability": {"wednesday": [{"start": "19:00", "duration_minutes": 60}]},
            "availability_overrides": {"2026/11/23": {"monday": [{"start": "23:00", "duration_minutes": 90}]}}})
        self.assertEqual(self.moved(context), (datetime(2026, 11, 23, 23, 0), datetime(2026, 11, 24, 0, 30)))

    def test_empty_override_week_is_skipped_without_base_fallback(self):
        base = {"monday": [{"start": "20:00", "duration_minutes": 90}]}
        self.assertEqual(self.moved(parse_weekly_availability(base))[0], datetime(2026, 11, 16, 20, 0))
        context = parse_weekly_availability({"weekly_availability": base,
                                             "availability_overrides": {"2026/11/16": {}}})
        self.assertEqual(self.moved(context)[0], datetime(2026, 11, 23, 20, 0))

    def test_exam_clips_an_overnight_window(self):
        context = parse_weekly_availability({"monday": [{"start": "23:00", "duration_minutes": 90}]})
        with self.assertRaisesRegex(ValueError, "NOT_ENOUGH_TIME"):
            self.moved(context, datetime(2026, 11, 17, 0, 0))

    def test_override_only_context_without_exam_terminates(self):
        context = parse_weekly_availability({
            "weekly_availability": {},
            "availability_overrides": {"2026/11/09": {"monday": [{"start": "20:00", "duration_minutes": 90}]}}})
        with self.assertRaisesRegex(ValueError, "NO_AVAILABILITY_WINDOW"):
            move_study_entry(self.entries, "study-1", context, None)


class ManualMoveRuleTests(unittest.TestCase):
    """A chosen time is checked in a fixed order; confirmation only waives study windows."""

    ROWS = """schedule_id,start_date,end_date,topic
study-1,2026/11/16/18/00,2026/11/16/19/00,Functions
study-2,2026/11/18/18/00,2026/11/18/19/00,Functions
external-3,2026/11/17/12/00,2026/11/17/13/00,
"""

    def setUp(self):
        self.entries = parse_schedule_csv(self.ROWS)
        self.context = parse_weekly_availability({"tuesday": [{"start": "18:00", "duration_minutes": 60}],
                                                  "sunday": [{"start": "23:30", "duration_minutes": 60}]})

    def move(self, start, confirm=False, now=datetime(2026, 11, 16, 12, 0), availability="default"):
        return place_study_entry_manually(
            self.entries, "study-1", start,
            availability=self.context if availability == "default" else availability,
            preparation_start=datetime(2026, 11, 16, 0, 0), exam_date=datetime(2026, 12, 7, 0, 0),
            now=now, confirm_outside_availability=confirm)

    def code(self, start, **kwargs):
        with self.assertRaises(ManualMoveError) as caught:
            self.move(start, **kwargs)
        return caught.exception.code

    def test_chosen_time_precedes_same_topic_order_and_keeps_other_rows(self):
        result = self.move(datetime(2026, 11, 17, 18, 0))
        self.assertEqual(result[0].start_datetime, datetime(2026, 11, 17, 18, 0))
        self.assertEqual(result[0].end_datetime, datetime(2026, 11, 17, 19, 0))
        self.assertEqual(result[0].topic, "Functions")
        self.assertEqual(result[1:], self.entries[1:])

    def test_overnight_window_fit_and_confirmation_scope(self):
        self.assertEqual(self.move(datetime(2026, 11, 22, 23, 30))[0].end_datetime, datetime(2026, 11, 23, 0, 30))
        self.assertEqual(self.code(datetime(2026, 11, 22, 23, 45)), "OUTSIDE_STUDY_WINDOWS")
        self.assertEqual(self.code(datetime(2026, 11, 17, 14, 0)), "OUTSIDE_STUDY_WINDOWS")
        self.assertEqual(self.move(datetime(2026, 11, 17, 14, 0), confirm=True)[0].start_datetime,
                         datetime(2026, 11, 17, 14, 0))
        self.assertEqual(self.code(datetime(2026, 11, 17, 18, 0), availability=None), "OUTSIDE_STUDY_WINDOWS")

    def test_hard_failures_are_not_waived_by_confirmation(self):
        cases = {
            datetime(2026, 11, 16, 11, 59): "MANUAL_MOVE_IN_PAST",
            datetime(2026, 12, 6, 23, 30): "EXAM_BOUND_EXCEEDED",
            datetime(2026, 11, 18, 18, 30): "STUDY_SESSION_OVERLAP",
            datetime(2026, 11, 17, 12, 30): "EXTERNAL_EVENT_OVERLAP",
        }
        for start, code in cases.items():
            with self.subTest(code=code):
                self.assertEqual(self.code(start, confirm=True), code)
        self.assertEqual(self.code(datetime(2026, 11, 15, 20, 0), confirm=True, now=datetime(2026, 11, 1)),
                         "BEFORE_PREPARATION_START")

    def test_error_precedence_and_exact_boundaries(self):
        # In the past and overlapping a study row: the past check comes first.
        self.assertEqual(self.code(datetime(2026, 11, 16, 11, 30), confirm=True,
                                   now=datetime(2026, 11, 16, 18, 30)), "MANUAL_MOVE_IN_PAST")
        for start in (datetime(2026, 11, 16, 12, 0), datetime(2026, 11, 17, 13, 0),
                      datetime(2026, 11, 18, 19, 0), datetime(2026, 12, 6, 23, 0)):
            with self.subTest(start=start):
                self.assertEqual(self.move(start, confirm=True)[0].start_datetime, start)


class ExamDeadlineTests(unittest.TestCase):
    """A moved study session must end by the exam date on both search paths."""

    def setUp(self):
        self.entries = parse_schedule_csv(SAMPLE_CSV)

    def assert_only_target_moved(self, moved, target_id, start, end):
        before = {entry.schedule_id: entry for entry in self.entries}
        after = {entry.schedule_id: entry for entry in moved}
        self.assertEqual(after.keys(), before.keys())
        self.assertEqual((after[target_id].start_datetime, after[target_id].end_datetime), (start, end))
        self.assertEqual(after[target_id].topic, before[target_id].topic)
        self.assertEqual({k: v for k, v in after.items() if k != target_id},
                         {k: v for k, v in before.items() if k != target_id})

    def assert_not_enough_time(self, *args):
        with self.assertRaisesRegex(ValueError, "^NOT_ENOUGH_TIME:"):
            move_study_entry(*args)

    def test_conflict_only_slot_may_end_exactly_at_exam(self):
        moved = move_study_entry(self.entries, "study-11", None, datetime(2026, 10, 5, 22, 0))
        self.assert_only_target_moved(moved, "study-11", datetime(2026, 10, 5, 21, 0), datetime(2026, 10, 5, 22, 0))

    def test_conflict_only_overrun_is_rejected(self):
        self.assert_not_enough_time(self.entries, "study-11", None, datetime(2026, 10, 5, 21, 59))

    def test_conflict_chain_past_exam_terminates(self):
        rows = ["schedule_id,start_date,end_date,topic", "study-1,2026/10/05/09/00,2026/10/05/10/00,Charts"]
        rows += [f"external-{hour},2026/10/05/{hour:02d}/00,2026/10/05/{hour + 1:02d}/00,"
                 for hour in range(10, 22)]
        entries = parse_schedule_csv("\n".join(rows) + "\n")
        self.assert_not_enough_time(entries, "study-1", None, datetime(2026, 10, 5, 15, 30))

    def test_same_topic_order_applies_before_the_exam_bound(self):
        entries = parse_schedule_csv("""schedule_id,start_date,end_date,topic
study-30,2026/10/01/09/00,2026/10/01/10/00,Charts
study-31,2026/10/01/12/00,2026/10/01/13/00,Charts
study-32,2026/10/01/14/00,2026/10/01/15/00,Functions
""")
        moved = move_study_entry(entries, "study-30", None, datetime(2026, 10, 1, 14, 0))
        self.assertEqual(next(e for e in moved if e.schedule_id == "study-30").start_datetime,
                         datetime(2026, 10, 1, 13, 0))
        self.assert_not_enough_time(entries, "study-30", None, datetime(2026, 10, 1, 13, 59))

    def test_availability_slot_may_end_exactly_at_exam(self):
        availability = parse_weekly_availability({"monday": [{"start": "18:00", "end": "23:00"}]})
        moved = move_study_entry(self.entries, "study-11", availability, datetime(2026, 10, 5, 22, 0))
        self.assert_only_target_moved(moved, "study-11", datetime(2026, 10, 5, 21, 0), datetime(2026, 10, 5, 22, 0))

    def test_availability_window_crossing_exam_is_rejected(self):
        availability = parse_weekly_availability({"monday": [{"start": "21:30", "end": "23:00"}]})
        self.assert_not_enough_time(self.entries, "study-11", availability, datetime(2026, 10, 5, 22, 0))

    def test_availability_exhausted_before_exam_on_a_later_day(self):
        availability = parse_weekly_availability({"wednesday": [{"start": "09:00", "end": "10:00"}]})
        self.assert_not_enough_time(self.entries, "study-11", availability, datetime(2026, 10, 7, 9, 30))
        moved = move_study_entry(self.entries, "study-11", availability, datetime(2026, 10, 7, 10, 0))
        self.assert_only_target_moved(moved, "study-11", datetime(2026, 10, 7, 9, 0), datetime(2026, 10, 7, 10, 0))

    def test_too_short_windows_still_report_no_availability(self):
        availability = parse_weekly_availability({"wednesday": [{"start": "10:00", "end": "11:00"}]})
        with self.assertRaisesRegex(ValueError, "^NO_AVAILABILITY_WINDOW:"):
            move_study_entry(self.entries, "study-12", availability, datetime(2026, 10, 8, 18, 0))


class CsvAdjustmentTests(unittest.TestCase):
    def test_round_trip_preserves_datetime_header_and_rows(self):
        """
        입력 값: 테스트 픽스처와 해당 시나리오의 입력
        출력 값: 모든 검증 단언 통과 시 테스트 통과
        기능: CSV 변환 전후 헤더·날짜·일정 행을 보존합니다.
        """
        entries = parse_schedule_csv(SAMPLE_CSV)
        output = render_schedule_csv(entries)
        self.assertTrue(output.startswith("schedule_id,start_date,end_date,topic\n"))
        self.assertIn("external-10,2026/10/06/18/00,2026/10/06/20/00,", output)
        self.assertIn("study-11,2026/10/05/19/00,2026/10/05/20/00,Charts", output)
        self.assertEqual(len(parse_schedule_csv(output)), 6)

    def test_accepts_korean_header_and_datetime_values(self):
        """
        입력 값: 테스트 픽스처와 해당 시나리오의 입력
        출력 값: 모든 검증 단언 통과 시 테스트 통과
        기능: 한국어 일정 CSV 헤더와 날짜 형식을 읽습니다.
        """
        entries = parse_schedule_csv("공부 일정 ID,시작 시간,끝나는 시간\nexternal-2,2026/10/01/09/30,2026/10/01/10/30\n")
        self.assertEqual(entries[0].start_datetime, datetime(2026, 10, 1, 9, 30))

    def test_rejects_unknown_prefix_duplicate_id_bad_timestamp_and_empty_interval(self):
        """
        입력 값: 테스트 픽스처와 해당 시나리오의 입력
        출력 값: 모든 검증 단언 통과 시 테스트 통과
        기능: 잘못된 ID·중복·시각 형식·빈 구간을 거부합니다.
        """
        for invalid in (
            "other-1,2026/10/01/09/00,2026/10/01/10/00\n",
            "study-1,2026/10/01/09/00,2026/10/01/10/00\nstudy-1,2026/10/01/10/00,2026/10/01/11/00\n",
            "study-1,2026-10-01,2026-10-02\n",
            "study-1,2026/10/01/09/00,2026/10/01/09/00\n",
        ):
            with self.subTest(invalid=invalid), self.assertRaises(ValueError):
                parse_schedule_csv(invalid)


class AdjustmentToolTests(unittest.TestCase):
    def setUp(self):
        """
        입력 값: 없음
        출력 값: 테스트 공통 데이터 준비 상태
        기능: 테스트 메서드에서 재사용할 픽스처를 초기화합니다.
        """
        self.entries = parse_schedule_csv(SAMPLE_CSV)

    def test_failed_study_moves_to_first_conflict_free_datetime(self):
        """
        입력 값: 테스트 픽스처와 해당 시나리오의 입력
        출력 값: 모든 검증 단언 통과 시 테스트 통과
        기능: 실패한 공부 일정을 충돌 없는 첫 시각으로 옮깁니다.
        """
        tools = ScheduleAdjustmentTools(AdjustmentRequest("study-11", self.entries))
        found = tools.call("inspect_schedule", {"schedule_id": "study-11"})
        topic_context = tools.call("inspect_topic_schedule", {"topic": found["topic"]})
        changed = tools.call("reschedule_failed_study", {"schedule_id": "study-11"})
        moved = next(entry for entry in tools.schedule if entry.schedule_id == "study-11")
        self.assertEqual(found["schedule_type"], "study")
        self.assertEqual(topic_context["topic"], "Charts")
        self.assertEqual(moved.start_datetime, datetime(2026, 10, 5, 21, 0))
        self.assertEqual(moved.end_datetime, datetime(2026, 10, 5, 22, 0))
        self.assertEqual(moved.topic, "Charts")
        self.assertEqual(changed["action"], "rescheduled_study")
        self.assertEqual([entry.schedule_id for entry in tools.schedule if entry.schedule_type == "external"],
                         ["external-10", "external-13", "external-14", "external-15"])

    def test_failed_multi_hour_study_preserves_duration_and_avoids_conflict(self):
        """
        입력 값: 테스트 픽스처와 해당 시나리오의 입력
        출력 값: 모든 검증 단언 통과 시 테스트 통과
        기능: 다시간 공부 일정의 길이를 유지하고 충돌을 피합니다.
        """
        tools = ScheduleAdjustmentTools(AdjustmentRequest("study-12", self.entries))
        tools.call("inspect_schedule", {"schedule_id": "study-12"})
        tools.call("inspect_topic_schedule", {"topic": "Functions"})
        tools.call("reschedule_failed_study", {"schedule_id": "study-12"})
        moved = next(entry for entry in tools.schedule if entry.schedule_id == "study-12")
        self.assertEqual(moved.start_datetime, datetime(2026, 10, 8, 21, 0))
        self.assertEqual(moved.end_datetime - moved.start_datetime, timedelta(hours=2))
        self.assertEqual(moved.topic, "Functions")
        self.assertIn("변경 전: 2026-10-08 18:00 ~ 2026-10-08 20:00", tools.outcome.message)
        self.assertIn("변경 후: 2026-10-08 21:00 ~ 2026-10-08 23:00", tools.outcome.message)
        self.assertIn("학습 내용과 공부 시간은 이전과 같습니다.", tools.outcome.message)

    def test_failed_session_moves_after_existing_session_for_same_topic(self):
        """
        입력 값: 테스트 픽스처와 해당 시나리오의 입력
        출력 값: 모든 검증 단언 통과 시 테스트 통과
        기능: 같은 주제의 기존 학습 이후로 실패 일정을 옮깁니다.
        """
        entries = parse_schedule_csv("""schedule_id,start_date,end_date,topic
study-30,2026/10/01/09/00,2026/10/01/10/00,Charts
study-31,2026/10/01/12/00,2026/10/01/13/00,Charts
study-32,2026/10/01/14/00,2026/10/01/15/00,Functions
""")
        tools = ScheduleAdjustmentTools(AdjustmentRequest("study-30", entries))
        inspected = tools.call("inspect_schedule", {"schedule_id": "study-30"})
        topic_context = tools.call("inspect_topic_schedule", {"topic": inspected["topic"]})
        self.assertEqual(len(topic_context["sessions"]), 1)
        moved = tools.call("reschedule_failed_study", {"schedule_id": "study-30"})
        self.assertEqual(moved["new_start_date"], "2026/10/01/13/00")
        self.assertEqual(moved["topic"], "Charts")

    def test_failed_study_moves_to_next_personal_availability_window(self):
        """입력 값: 주제 일정과 다음 날만 가능한 주간 시간표
        출력 값: 가능 시간 안으로 이동한 일정 결과
        기능: 주제 순서와 사용자 개인 가능 시간을 함께 적용하는지 검증합니다.
        """
        availability = parse_weekly_availability({
            "tuesday": [{"start": "09:00", "end": "11:00"}],
        })
        tools = ScheduleAdjustmentTools(AdjustmentRequest("study-11", self.entries, availability))
        inspected = tools.call("inspect_schedule", {"schedule_id": "study-11"})
        topic_context = tools.call("inspect_topic_schedule", {"topic": inspected["topic"]})
        self.assertEqual(inspected["weekly_availability"]["tuesday"][0]["start"], "09:00")
        self.assertEqual(topic_context["topic"], "Charts")

        changed = tools.call("reschedule_failed_study", {"schedule_id": "study-11"})
        moved = next(entry for entry in tools.schedule if entry.schedule_id == "study-11")
        self.assertEqual(moved.start_datetime, datetime(2026, 10, 6, 9, 0))
        self.assertEqual(moved.end_datetime, datetime(2026, 10, 6, 10, 0))
        self.assertTrue(changed["personal_availability_applied"])

    def test_study_move_reports_when_availability_cannot_fit_session(self):
        """입력 값: 두 시간 공부 일정과 한 시간 길이의 개인 가능 시간
        출력 값: 배치 불가 오류와 미변경 도구 상태
        기능: 가능 구간이 공부 세션보다 짧을 때 일정을 임의 이동하지 않는지 검증합니다.
        """
        availability = parse_weekly_availability({
            "wednesday": [{"start": "10:00", "end": "11:00"}],
        })
        tools = ScheduleAdjustmentTools(AdjustmentRequest("study-12", self.entries, availability))
        tools.call("inspect_schedule", {"schedule_id": "study-12"})
        tools.call("inspect_topic_schedule", {"topic": "Functions"})

        response = tools.call("reschedule_failed_study", {"schedule_id": "study-12"})
        self.assertIn("NO_AVAILABILITY_WINDOW", response)
        self.assertIsNone(tools.outcome)

    def test_missing_personal_availability_keeps_legacy_rescheduling(self):
        """입력 값: 개인 시간표가 없는 공부 일정 조정 요청
        출력 값: 기존 규칙으로 이동된 일정
        기능: 시간표 파일이 없는 환경에서 이전 충돌 회피 동작을 유지하는지 검증합니다.
        """
        tools = ScheduleAdjustmentTools(AdjustmentRequest("study-11", self.entries))
        tools.call("inspect_schedule", {"schedule_id": "study-11"})
        tools.call("inspect_topic_schedule", {"topic": "Charts"})
        tools.call("reschedule_failed_study", {"schedule_id": "study-11"})
        moved = next(entry for entry in tools.schedule if entry.schedule_id == "study-11")
        self.assertEqual(moved.start_datetime, datetime(2026, 10, 5, 21, 0))

    def test_topic_study_must_be_inspected_before_rescheduling(self):
        """
        입력 값: 테스트 픽스처와 해당 시나리오의 입력
        출력 값: 모든 검증 단언 통과 시 테스트 통과
        기능: 주제 일정을 확인하기 전 재조정 도구 사용을 막습니다.
        """
        tools = ScheduleAdjustmentTools(AdjustmentRequest("study-11", self.entries))
        tools.call("inspect_schedule", {"schedule_id": "study-11"})
        response = tools.call("reschedule_failed_study", {"schedule_id": "study-11"})
        self.assertIn("PREREQUISITE", response)

    def test_cancelled_external_removed_but_other_rows_kept(self):
        """
        입력 값: 테스트 픽스처와 해당 시나리오의 입력
        출력 값: 모든 검증 단언 통과 시 테스트 통과
        기능: 취소된 외부 일정만 제거하고 나머지 행을 보존합니다.
        """
        tools = ScheduleAdjustmentTools(AdjustmentRequest("external-10", self.entries))
        tools.call("inspect_schedule", {"schedule_id": "external-10"})
        changed = tools.call("remove_cancelled_external_schedule", {"schedule_id": "external-10"})
        self.assertEqual(changed["action"], "cancelled_external")
        self.assertNotIn("external-10", [entry.schedule_id for entry in tools.schedule])
        self.assertEqual(len(tools.schedule), 5)

    def test_wrong_type_unknown_id_and_wrong_target_are_rejected(self):
        """
        입력 값: 테스트 픽스처와 해당 시나리오의 입력
        출력 값: 모든 검증 단언 통과 시 테스트 통과
        기능: 잘못된 일정 종류·ID·변경 대상을 거부합니다.
        """
        tools = ScheduleAdjustmentTools(AdjustmentRequest("study-11", self.entries))
        tools.call("inspect_schedule", {"schedule_id": "study-11"})
        self.assertIn("WRONG_SCHEDULE_TYPE", tools.call("remove_cancelled_external_schedule", {"schedule_id": "study-11"}))
        unknown = ScheduleAdjustmentTools(AdjustmentRequest("study-999", self.entries))
        self.assertIn("NOT_FOUND", unknown.call("inspect_schedule", {"schedule_id": "study-999"}))
        self.assertIn("WRONG_TARGET", tools.call("reschedule_failed_study", {"schedule_id": "study-12"}))

    def test_trace_records_tool_calls_in_order(self):
        """
        입력 값: 테스트 픽스처와 해당 시나리오의 입력
        출력 값: 모든 검증 단언 통과 시 테스트 통과
        기능: 도구 호출 trace가 실제 호출 순서를 보존합니다.
        """
        tools = ScheduleAdjustmentTools(AdjustmentRequest("external-13", self.entries))
        tools.call("inspect_schedule", {"schedule_id": "external-13"})
        tools.call("remove_cancelled_external_schedule", {"schedule_id": "external-13"})
        self.assertEqual([call["tool"] for call in tools.trace.calls],
                         ["inspect_schedule", "remove_cancelled_external_schedule"])

    def test_change_tool_requires_inspection(self):
        """
        입력 값: 테스트 픽스처와 해당 시나리오의 입력
        출력 값: 모든 검증 단언 통과 시 테스트 통과
        기능: 대상 일정 확인 전 변경 도구 실행을 막습니다.
        """
        tools = ScheduleAdjustmentTools(AdjustmentRequest("study-11", self.entries))
        response = tools.call("reschedule_failed_study", {"schedule_id": "study-11"})
        self.assertIn("PREREQUISITE", response)
        self.assertIsNone(tools.outcome)

    def test_agent_schema_exposes_topic_context_tool(self):
        """
        입력 값: 테스트 픽스처와 해당 시나리오의 입력
        출력 값: 모든 검증 단언 통과 시 테스트 통과
        기능: Agent 스키마에 주제별 일정 확인 도구가 포함되는지 확인합니다.
        """
        schemas = tool_schemas()
        topic_schema = next(schema for schema in schemas if schema["name"] == "inspect_topic_schedule")
        self.assertEqual(topic_schema["parameters"]["required"], ["topic"])

    def test_agent_schemas_use_lowercase_json_schema_types(self):
        """
        입력 값: 없음
        출력 값: 모든 검증 단언 통과 시 테스트 통과
        기능: 조정 도구 선언이 JSON Schema 소문자 타입만 쓰는지 확인합니다.
        """
        for schema in tool_schemas():
            parameters = schema["parameters"]
            self.assertEqual(parameters["type"], "object")
            self.assertEqual({prop["type"] for prop in parameters["properties"].values()}, {"string"})


if __name__ == "__main__":
    unittest.main()
