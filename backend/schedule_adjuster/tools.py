"""Gemini Agent에 노출되는 제한된 일정 조정 도구입니다."""
from __future__ import annotations

from typing import Any

from .models import AdjustmentOutcome, AdjustmentRequest, ToolTrace
from .schedule_logic import move_study_entry, remove_external_entry
from .settings import DATETIME_FORMAT


class ScheduleAdjustmentTools:
    """원본 일정을 보관하고 검증된 도구 호출만 처리합니다."""

    def __init__(self, request: AdjustmentRequest):
        """입력 값: 일정 ID와 원본 일정이 담긴 조정 요청
        출력 값: 일정 조정 도구 객체
        기능: 조정 상태와 도구 호출 trace를 초기화합니다.
        """
        # 요청, 불변 원본, 현재 결과, 도구 호출 기록입니다.
        self.request = request
        self.original_schedule = request.original_schedule
        self.schedule = request.original_schedule
        self.trace = ToolTrace()
        self.weekly_availability = request.weekly_availability  # 공부 일정 이동에 적용할 개인 시간표입니다.
        self.inspected_schedule = False  # 대상 검증을 통과한 뒤에만 변경 도구를 허용합니다.
        self.inspected_topic = False  # 주제 세션 조회가 필요한 공부 일정의 prerequisite 상태입니다.
        self.outcome: AdjustmentOutcome | None = None
        self.last_tool_error: str | None = None  # 마지막 도구 오류를 Agent와 CLI에 전달합니다.

    def inspect_schedule(self, schedule_id: str) -> dict[str, Any] | str:
        """입력 값: 확인할 일정 ID
        출력 값: 대상 종류/기간 또는 NOT_FOUND 메시지
        기능: 요청 ID를 원본 일정에서 찾아 Agent의 다음 도구 선택을 돕습니다.
        """
        self.trace.calls.append({"tool": "inspect_schedule", "arguments": {"schedule_id": schedule_id}})
        if schedule_id != self.request.schedule_id:
            return "WRONG_TARGET: inspect only the schedule_id supplied by the user."
        matches = [entry for entry in self.original_schedule if entry.schedule_id == schedule_id]
        if not matches:
            return f"NOT_FOUND: {schedule_id!r} is absent from the supplied schedule CSV. Do not guess another ID."
        target = matches[0]
        self.inspected_schedule = True
        availability = None
        if self.weekly_availability is not None and target.schedule_type == "study":
            weekday_names = ("monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday")
            availability = {
                weekday: [{"start": window.start_time.strftime("%H:%M"),
                           "end": window.end_time.strftime("%H:%M")}
                          for window in self.weekly_availability.for_weekday(index)]
                for index, weekday in enumerate(weekday_names)
            }
        return {"schedule_id": target.schedule_id, "schedule_type": target.schedule_type,
                "start_date": target.start_datetime.strftime(DATETIME_FORMAT),
                "end_date": target.end_datetime.strftime(DATETIME_FORMAT),
                "topic": target.topic,
                "weekly_availability": availability,
                "instruction": (("Inspect the exact topic with inspect_topic_schedule, then use reschedule_failed_study."
                                 if target.topic else "Use reschedule_failed_study; this legacy row has no topic.")
                                if target.schedule_type == "study"
                                else "This external event was cancelled; use remove_cancelled_external_schedule.")}

    def inspect_topic_schedule(self, topic: str) -> dict[str, Any] | str:
        """입력 값: 조회할 학습 주제
        출력 값: 실패 일정과 같은 주제의 다른 공부 일정 목록 또는 오류 메시지
        기능: 실패한 공부 세션과 같은 주제의 일정들을 찾아 재배치 순서를 결정하도록 제공합니다.
        """
        self.trace.calls.append({"tool": "inspect_topic_schedule", "arguments": {"topic": topic}})
        if not self.inspected_schedule:
            return "PREREQUISITE: call inspect_schedule before inspecting a topic."
        target = next((entry for entry in self.original_schedule
                       if entry.schedule_id == self.request.schedule_id), None)
        if target is None:
            return f"NOT_FOUND: {self.request.schedule_id!r} is absent from the supplied schedule CSV."
        if target.schedule_type != "study" or not target.topic:
            return "WRONG_SCHEDULE_TYPE: topic schedule lookup applies only to study entries with a topic."
        if topic.casefold() != target.topic.casefold():
            return f"WRONG_TOPIC: inspect the target's exact topic {target.topic!r}."
        self.inspected_topic = True
        matches = sorted((entry for entry in self.original_schedule
                          if entry.schedule_type == "study" and entry.topic.casefold() == topic.casefold()),
                         key=lambda entry: (entry.start_datetime, entry.schedule_id))
        return {
            "topic": target.topic,
            "failed_session_id": target.schedule_id,
            "sessions": [
                {"schedule_id": entry.schedule_id,
                 "start_date": entry.start_datetime.strftime(DATETIME_FORMAT),
                 "end_date": entry.end_datetime.strftime(DATETIME_FORMAT)}
                for entry in matches if entry.schedule_id != target.schedule_id
            ],
            "instruction": "Reschedule the failed session after the latest-ending other session for this topic."}

    def reschedule_failed_study(self, schedule_id: str) -> dict[str, Any] | str:
        """입력 값: 실패한 study-N 일정 ID
        출력 값: 이동한 일정과 새 전체 일정 또는 오류 메시지
        기능: 기존 기간 길이와 주제를 보존하고 같은 주제 순서를 반영한 빈 구간으로 이동합니다.
        """
        self.trace.calls.append({"tool": "reschedule_failed_study", "arguments": {"schedule_id": schedule_id}})
        if not self.inspected_schedule:
            return "PREREQUISITE: call inspect_schedule for the exact target before changing a schedule."
        if self.outcome is not None:
            return "ALREADY_ADJUSTED: this request can change only one schedule once."
        if schedule_id != self.request.schedule_id:
            return "WRONG_TARGET: only adjust the schedule_id supplied by the user."
        try:
            old_entry = next(entry for entry in self.original_schedule if entry.schedule_id == schedule_id)
            if old_entry.topic and not self.inspected_topic:
                return "PREREQUISITE: inspect_topic_schedule for the target topic before rescheduling this study session."
            new_schedule = move_study_entry(self.original_schedule, schedule_id, self.weekly_availability)
            new_entry = next(entry for entry in new_schedule if entry.schedule_id == schedule_id)
        except (ValueError, StopIteration) as error:
            return str(error) or f"NOT_FOUND: {schedule_id!r} is absent from the input schedule."
        self.schedule = new_schedule
        self.outcome = AdjustmentOutcome("rescheduled_study", schedule_id, new_schedule,
                                         f"Moved {schedule_id} from {old_entry.start_datetime.strftime(DATETIME_FORMAT)}–{old_entry.end_datetime.strftime(DATETIME_FORMAT)} to {new_entry.start_datetime.strftime(DATETIME_FORMAT)}–{new_entry.end_datetime.strftime(DATETIME_FORMAT)}.")
        return {"action": self.outcome.action, "schedule_id": schedule_id,
                "new_start_date": new_entry.start_datetime.strftime(DATETIME_FORMAT),
                "new_end_date": new_entry.end_datetime.strftime(DATETIME_FORMAT),
                "topic": new_entry.topic,
                "personal_availability_applied": self.weekly_availability is not None,
                "preserved_external_count": sum(entry.schedule_type == "external" for entry in new_schedule)}

    def remove_cancelled_external_schedule(self, schedule_id: str) -> dict[str, Any] | str:
        """입력 값: 취소된 external-N 일정 ID
        출력 값: 삭제 결과와 남은 일정 개수 또는 오류 메시지
        기능: 취소된 외부 일정만 제거하고 나머지 외부/공부 일정을 보존합니다.
        """
        self.trace.calls.append({"tool": "remove_cancelled_external_schedule", "arguments": {"schedule_id": schedule_id}})
        if not self.inspected_schedule:
            return "PREREQUISITE: call inspect_schedule for the exact target before changing a schedule."
        if self.outcome is not None:
            return "ALREADY_ADJUSTED: this request can change only one schedule once."
        if schedule_id != self.request.schedule_id:
            return "WRONG_TARGET: only adjust the schedule_id supplied by the user."
        try:
            new_schedule = remove_external_entry(self.original_schedule, schedule_id)
        except ValueError as error:
            return str(error)
        self.schedule = new_schedule
        self.outcome = AdjustmentOutcome("cancelled_external", schedule_id, new_schedule,
                                         f"Removed cancelled external event {schedule_id}; all other entries were retained.")
        return {"action": self.outcome.action, "schedule_id": schedule_id,
                "remaining_schedule_count": len(new_schedule),
                "preserved_external_count": sum(entry.schedule_type == "external" for entry in new_schedule)}

    def call(self, name: str, arguments: dict[str, Any]) -> Any:
        """입력 값: 도구 이름과 인자 딕셔너리
        출력 값: 해당 도구 응답 또는 사용 가능한 오류 안내
        기능: 허용 목록의 도구만 호출하고 실패 원인을 Agent에 반환합니다.
        """
        # 모델이 호출할 수 있는 기능은 이 registry에 한정됩니다.
        registry = {"inspect_schedule": self.inspect_schedule,
                    "inspect_topic_schedule": self.inspect_topic_schedule,
                    "reschedule_failed_study": self.reschedule_failed_study,
                    "remove_cancelled_external_schedule": self.remove_cancelled_external_schedule}
        if name not in registry:
            error = f"NOT_FOUND: unknown tool {name!r}. Available tools: {', '.join(registry)}."
            self.last_tool_error = error
            return error
        try:
            result = registry[name](**arguments)
        except TypeError as error:
            result = f"INVALID_TOOL_CALL: {error}. Review the documented tool arguments."
        self.last_tool_error = result if isinstance(result, str) else None
        return result


def tool_schemas() -> list[dict[str, Any]]:
    """입력 값: 없음
    출력 값: Gemini function declarations 목록
    기능: Agent가 ID 검사와 해당 유형에 맞는 조정 도구를 호출하도록 공개합니다.
    """
    schedule_id = {"type": "STRING", "description": "Exact schedule_id supplied by the user, including its external- or study- prefix."}
    topic = {"type": "STRING", "description": "Exact topic returned by inspect_schedule for the target study session."}
    return [
        {"name": "inspect_schedule", "description": "Look up the user's exact schedule ID before changing anything. Returns whether it is external or study, its topic, and the personal weekly availability used when moving study sessions.",
         "parameters": {"type": "OBJECT", "properties": {"schedule_id": schedule_id}, "required": ["schedule_id"]}},
        {"name": "inspect_topic_schedule", "description": "For a study row with a topic, inspect all study sessions on that exact topic before rescheduling. Must follow inspect_schedule.",
         "parameters": {"type": "OBJECT", "properties": {"topic": topic}, "required": ["topic"]}},
        {"name": "reschedule_failed_study", "description": "Only for a failed study-N entry. For topic-labelled sessions, first inspect_topic_schedule; moves the failed session after all other sessions on the same topic, inside the personal weekly availability returned by inspect_schedule when present, and to a conflict-free interval. Preserves topic, duration, and all other rows.",
         "parameters": {"type": "OBJECT", "properties": {"schedule_id": schedule_id}, "required": ["schedule_id"]}},
        {"name": "remove_cancelled_external_schedule", "description": "Only for a cancelled external-N entry. Removes that cancelled row and preserves every other row.",
         "parameters": {"type": "OBJECT", "properties": {"schedule_id": schedule_id}, "required": ["schedule_id"]}},
    ]

