"""도구가 사용하는 결정적 일정 조정 규칙입니다."""
from __future__ import annotations

from datetime import datetime, timedelta

from .models import ScheduleEntry, WeeklyAvailability
from .settings import EXTERNAL_ID_PREFIX, FIRST_STUDY_ID, STUDY_ID_PREFIX


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
                          availability: WeeklyAvailability) -> datetime:
    """입력 값: 가장 이른 시작 시각, 지속 시간, 기존 일정, 사용자 시간표
    출력 값: 시간표 안에서 충돌하지 않는 시작 시각
    기능: 개인 가능 요일과 하루 시간 구간을 순회해 첫 배치 가능 슬롯을 찾습니다.
    """
    windows = [window for weekday in range(7) for window in availability.for_weekday(weekday)]
    if not windows:
        raise ValueError("NO_AVAILABILITY_WINDOW: user has no available study time.")
    if not any(datetime.combine(datetime.min.date(), window.end_time)
               - datetime.combine(datetime.min.date(), window.start_time) >= duration
               for window in windows):
        raise ValueError("NO_AVAILABILITY_WINDOW: no personal window is long enough for this study session.")

    current_date = candidate_start.date()
    while True:
        weekday_windows = availability.for_weekday(current_date.weekday())
        for window in weekday_windows:
            window_start = datetime.combine(current_date, window.start_time)
            window_end = datetime.combine(current_date, window.end_time)
            slot_start = max(candidate_start, window_start)
            while slot_start + duration <= window_end:
                conflict = next((entry for entry in other_entries
                                 if intervals_overlap(slot_start, slot_start + duration,
                                                      entry.start_datetime, entry.end_datetime)), None)
                if conflict is None:
                    return slot_start
                slot_start = max(slot_start, conflict.end_datetime)
        current_date += timedelta(days=1)
        candidate_start = datetime.combine(current_date, datetime.min.time())


def move_study_entry(schedule: tuple[ScheduleEntry, ...], schedule_id: str,
                     availability: WeeklyAvailability | None = None) -> tuple[ScheduleEntry, ...]:
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
        candidate_start = _find_available_start(candidate_start, duration, other_entries, availability)
    else:
        while True:
            candidate_end = candidate_start + duration
            conflict = next((entry for entry in other_entries
                             if intervals_overlap(candidate_start, candidate_end,
                                                  entry.start_datetime, entry.end_datetime)), None)
            if conflict is None:
                break
            candidate_start = conflict.end_datetime

    candidate_end = candidate_start + duration
    moved = ScheduleEntry(target.schedule_id, candidate_start, candidate_end, target.topic)
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

