"""도구가 사용하는 결정적 일정 조정 규칙입니다."""
from __future__ import annotations

from datetime import datetime, time, timedelta

from study_availability import AvailabilityContext, as_context

from .models import ScheduleEntry, WeeklyAvailability
from .settings import EXTERNAL_ID_PREFIX, FIRST_STUDY_ID, STUDY_ID_PREFIX

NOT_ENOUGH_TIME_MESSAGE = "NOT_ENOUGH_TIME: no available study slot remains before the exam date."


class ManualMoveError(ValueError):
    """A rejected manual move with a public error code."""

    def __init__(self, code: str, message: str):
        super().__init__(message)
        self.code = code


def classify_schedule_id(schedule_id: str) -> str | None:
    """입력 값: 일정 ID 문자열
    출력 값: external/study 종류 또는 None
    기능: ID 접두어와 양의 정수 번호가 유효한지 판별합니다.
    """
    for prefix, kind in ((EXTERNAL_ID_PREFIX, "external"), (STUDY_ID_PREFIX, "study")):
        suffix = schedule_id.removeprefix(prefix)
        if schedule_id.startswith(prefix) and suffix.isdigit() and int(suffix) >= FIRST_STUDY_ID:
            return kind
    return None


def intervals_overlap(start_a: datetime, end_a: datetime,
                      start_b: datetime, end_b: datetime) -> bool:
    """입력 값: 두 일정의 시작일과 종료일
    출력 값: 겹치면 True, 아니면 False
    기능: 시작 포함·종료 제외하는 두 datetime 구간의 겹침 여부를 계산합니다.
    """
    return start_a < end_b and start_b < end_a


def _find_available_start(candidate_start: datetime, duration: timedelta,
                          other_entries: tuple[ScheduleEntry, ...],
                          availability: AvailabilityContext | WeeklyAvailability,
                          exam_date: datetime | None = None) -> datetime:
    """입력 값: 가장 이른 시작 시각, 지속 시간, 기존 일정, 선택된 가능 시간 문맥
    출력 값: 가능 시간 안에서 충돌하지 않는 시작 시각
    기능: 공용 해석기의 날짜별 절대 구간(자정을 넘는 구간 포함)을 순회해 첫 배치 가능 슬롯을 찾습니다.
    """
    context = as_context(availability)
    windows = [window for week in (context.base, *(week for _, week in context.overrides))
               for weekday in range(7) for window in week.for_weekday(weekday)]
    if not windows:
        raise ValueError("NO_AVAILABILITY_WINDOW: user has no available study time.")
    if not any(timedelta(minutes=window.duration_minutes) >= duration for window in windows):
        raise ValueError("NO_AVAILABILITY_WINDOW: no personal window is long enough for this study session.")

    horizon = None
    if exam_date is None:
        # 시험일이 없으면 모든 기존 일정과 주별 교체 시간을 지난 뒤 반복 주간 한 바퀴까지만 찾습니다.
        latest = max([candidate_start, *(entry.end_datetime for entry in other_entries)])
        last_override_day = context.last_override_day()
        if last_override_day is not None:
            latest = max(latest, datetime.combine(last_override_day, time()))
        horizon = latest + timedelta(days=8)
    current_date = candidate_start.date()
    while not ((exam_date is not None and candidate_start >= exam_date)
               or (horizon is not None and candidate_start >= horizon)):
        for window_start, window_end in context.windows_for(current_date):
            if exam_date is not None:
                window_end = min(window_end, exam_date)
            slot_start = max(candidate_start, window_start)
            while slot_start + duration <= window_end:
                conflict = next((entry for entry in other_entries
                                 if intervals_overlap(slot_start, slot_start + duration,
                                                      entry.start_datetime, entry.end_datetime)), None)
                if conflict is None:
                    return slot_start
                slot_start = max(slot_start, conflict.end_datetime)
        current_date += timedelta(days=1)
        candidate_start = max(candidate_start, datetime.combine(current_date, time()))
    if exam_date is not None:
        raise ValueError(NOT_ENOUGH_TIME_MESSAGE)
    raise ValueError("NO_AVAILABILITY_WINDOW: no personal window is free for this study session.")


def move_study_entry(schedule: tuple[ScheduleEntry, ...], schedule_id: str,
                     availability: AvailabilityContext | WeeklyAvailability | None = None,
                     exam_date: datetime | None = None) -> tuple[ScheduleEntry, ...]:
    """입력 값: 전체 일정 튜플, 실패한 공부 일정 ID, 선택적 주간 가능 시간
    출력 값: 해당 공부 일정이 이동된 새 일정 튜플
    기능: 주제 순서, 사용자 가능 시간, 다른 일정 충돌을 고려해 기존 기간 길이로 이동합니다.
    """
    target = next((entry for entry in schedule if entry.schedule_id == schedule_id), None)
    if target is None:
        raise ValueError(f"NOT_FOUND: schedule_id {schedule_id!r} is not in the original schedule.")
    if target.schedule_type != "study":
        raise ValueError(f"WRONG_SCHEDULE_TYPE: {schedule_id!r} is external; only study entries can be rescheduled.")

    # 이동 대상을 제외한 일정만 충돌 검사에 사용합니다.
    other_entries = tuple(entry for entry in schedule if entry.schedule_id != schedule_id)
    duration = target.end_datetime - target.start_datetime
    # 같은 주제의 기존 학습이 있다면 그 뒤에 재배치해 학습 순서를 이어갑니다.
    same_topic = [entry for entry in other_entries if target.topic and entry.schedule_type == "study"
                  and entry.topic.casefold() == target.topic.casefold()]
    candidate_start = max(target.end_datetime,
                          max((entry.end_datetime for entry in same_topic), default=target.end_datetime))
    if availability is not None:
        candidate_start = _find_available_start(candidate_start, duration, other_entries, availability, exam_date)
    else:
        while True:
            candidate_end = candidate_start + duration
            if exam_date is not None and candidate_end > exam_date:
                raise ValueError(NOT_ENOUGH_TIME_MESSAGE)
            conflict = next((entry for entry in other_entries
                             if intervals_overlap(candidate_start, candidate_end,
                                                  entry.start_datetime, entry.end_datetime)), None)
            if conflict is None:
                break
            candidate_start = conflict.end_datetime

    # Both searches only return a slot whose full duration ends by the exam date.
    moved = ScheduleEntry(target.schedule_id, candidate_start, candidate_start + duration, target.topic)
    return tuple(moved if entry.schedule_id == schedule_id else entry for entry in schedule)


def fits_one_window(start: datetime, end: datetime,
                    availability: AvailabilityContext | WeeklyAvailability | None) -> bool:
    """입력 값: 일정 시작/종료 시각과 선택된 가능 시간 문맥
    출력 값: 하나의 절대 가능 구간이 일정 전체를 포함하면 True
    기능: 자정을 넘는 구간을 포함해 공용 해석기로 일정이 한 구간 안에 들어가는지 확인합니다.
    """
    context = as_context(availability)
    if context is None:
        return False
    return any(window_start <= start and end <= window_end
               for window_start, window_end in context.windows_for(start.date()))


def place_study_entry_manually(schedule: tuple[ScheduleEntry, ...], schedule_id: str, new_start: datetime, *,
                               availability: AvailabilityContext | WeeklyAvailability | None,
                               preparation_start: datetime, exam_date: datetime, now: datetime,
                               confirm_outside_availability: bool) -> tuple[ScheduleEntry, ...]:
    """입력 값: 전체 일정, 공부 일정 ID, 사용자가 고른 시작 시각, 문맥·기간·현재 분, 시간 밖 확인 여부
    출력 값: 해당 공부 일정만 선택한 시각으로 옮긴 새 일정 튜플
    기능: 자동 순서 규칙 없이 지정 시각으로 옮기고, 과거·기간·겹침·가능 시간 순서로 거절합니다.
    가능 시간 밖 확인은 마지막 검사만 통과시키며 기간과 겹침 검사는 항상 적용합니다.
    """
    target = next((entry for entry in schedule if entry.schedule_id == schedule_id), None)
    if target is None or target.schedule_type != "study":
        raise ValueError("schedule_id must identify a study entry for a manual move")
    new_end = new_start + (target.end_datetime - target.start_datetime)
    if new_start < now:
        raise ManualMoveError("MANUAL_MOVE_IN_PAST", "The chosen start time has already passed.")
    if new_start < preparation_start:
        raise ManualMoveError("BEFORE_PREPARATION_START", "The chosen start time is before the preparation period.")
    if new_end > exam_date:
        raise ManualMoveError("EXAM_BOUND_EXCEEDED", "The moved session would end after the exam date.")
    others = [entry for entry in schedule if entry.schedule_id != schedule_id
              and intervals_overlap(new_start, new_end, entry.start_datetime, entry.end_datetime)]
    if any(entry.schedule_type == "study" for entry in others):
        raise ManualMoveError("STUDY_SESSION_OVERLAP", "The chosen time overlaps another study session.")
    if others:
        raise ManualMoveError("EXTERNAL_EVENT_OVERLAP", "The chosen time overlaps an external calendar event.")
    if not confirm_outside_availability and not fits_one_window(new_start, new_end, availability):
        raise ManualMoveError("OUTSIDE_STUDY_WINDOWS",
                              "The chosen time is outside the study windows. Confirm this one-time exception.")
    moved = ScheduleEntry(target.schedule_id, new_start, new_end, target.topic)
    return tuple(moved if entry.schedule_id == schedule_id else entry for entry in schedule)


def remove_external_entry(schedule: tuple[ScheduleEntry, ...], schedule_id: str) -> tuple[ScheduleEntry, ...]:
    """입력 값: 전체 일정 튜플과 취소된 외부 일정 ID
    출력 값: 취소 대상만 제외한 새 일정 튜플
    기능: 취소된 외부 일정 행을 제거하고 나머지 일정을 그대로 보존합니다.
    """
    target = next((entry for entry in schedule if entry.schedule_id == schedule_id), None)
    if target is None:
        raise ValueError(f"NOT_FOUND: schedule_id {schedule_id!r} is not in the original schedule.")
    if target.schedule_type != "external":
        raise ValueError(f"WRONG_SCHEDULE_TYPE: {schedule_id!r} is a study entry; it cannot be cancelled as external.")
    return tuple(entry for entry in schedule if entry.schedule_id != schedule_id)

