import unittest
from datetime import datetime, timedelta

from schedule_adjuster.csv_io import parse_schedule_csv, render_schedule_csv
from schedule_adjuster.availability import parse_weekly_availability
from schedule_adjuster.models import AdjustmentRequest
from schedule_adjuster.tools import ScheduleAdjustmentTools, tool_schemas


SAMPLE_CSV = """schedule_id,start_date,end_date,topic
external-10,2026/10/06/18/00,2026/10/06/20/00,
study-11,2026/10/05/19/00,2026/10/05/20/00,Charts
study-12,2026/10/08/18/00,2026/10/08/20/00,Functions
external-13,2026/10/10/10/00,2026/10/10/12/00,
external-14,2026/10/05/20/00,2026/10/05/21/00,
external-15,2026/10/08/20/00,2026/10/08/21/00,
"""


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


if __name__ == "__main__":
    unittest.main()
