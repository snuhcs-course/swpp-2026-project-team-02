"""Deterministic whole-batch placement of remaining study minutes.

Every instructed source row is removed first, then each contribution is placed
as one whole session in the earliest free effective study window, in the
sources' original chronological order.  The result is either a complete new
schedule or a ``ValueError``; nothing is partially applied.
"""
from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, time, timedelta

from study_availability import AvailabilityContext

from .models import ScheduleEntry
from .schedule_logic import classify_schedule_id, intervals_overlap
from .settings import STUDY_ID_PREFIX

NEXT_MINUTE = timedelta(minutes=1)


@dataclass(frozen=True)
class RemainingSession:
    """One source study row and the whole minutes it still needs."""
    schedule_id: str
    remaining_minutes: int
    replacement_schedule_id: str


def _suffix(schedule_id: str) -> int:
    return int(schedule_id.removeprefix(STUDY_ID_PREFIX))


def _validated_sources(schedule: tuple[ScheduleEntry, ...],
                       instructions: tuple[RemainingSession, ...]) -> list[tuple[ScheduleEntry, RemainingSession]]:
    """Pair each instruction with its source row, rejecting any inconsistent instruction."""
    if not instructions:
        raise ValueError("remaining_sessions must contain at least one session")
    rows = {entry.schedule_id: entry for entry in schedule}
    highest_study = max((_suffix(entry.schedule_id) for entry in schedule if entry.schedule_type == "study"),
                        default=0)
    sources, replacements, pairs = set(), set(), []
    for instruction in instructions:
        source = rows.get(instruction.schedule_id)
        if source is None:
            raise ValueError(f"remaining_sessions: {instruction.schedule_id!r} is not in schedules")
        if source.schedule_type != "study":
            raise ValueError(f"remaining_sessions: {instruction.schedule_id!r} is not a study session")
        if instruction.schedule_id in sources:
            raise ValueError(f"remaining_sessions: {instruction.schedule_id!r} is listed more than once")
        duration = int((source.end_datetime - source.start_datetime).total_seconds() // 60)
        if not 0 < instruction.remaining_minutes <= duration:
            raise ValueError(f"remaining_sessions: remaining_minutes for {instruction.schedule_id!r} "
                             f"must be from 1 to its scheduled {duration} minutes")
        replacement = instruction.replacement_schedule_id
        if replacement in replacements:
            raise ValueError(f"remaining_sessions: replacement_schedule_id {replacement!r} is used more than once")
        if replacement == instruction.schedule_id:
            if instruction.remaining_minutes != duration:
                raise ValueError(f"remaining_sessions: {replacement!r} keeps its ID only with its full "
                                 f"{duration} minutes; use a new study ID for a shorter remainder")
        elif (classify_schedule_id(replacement) != "study" or replacement in rows
              or _suffix(replacement) <= highest_study):
            raise ValueError(f"remaining_sessions: replacement_schedule_id {replacement!r} must be a new "
                             f"study-N ID numbered above every current study session")
        sources.add(instruction.schedule_id)
        replacements.add(replacement)
        pairs.append((source, instruction))
    pairs.sort(key=lambda pair: (pair[0].start_datetime, pair[0].end_datetime, _suffix(pair[0].schedule_id)))
    return pairs


def _earliest_fit(lower_bound: datetime, duration: timedelta, busy: list[ScheduleEntry],
                  availability: AvailabilityContext, exam_date: datetime) -> datetime | None:
    """Earliest start at or after ``lower_bound`` whose whole interval lies in one window before the exam."""
    day = lower_bound.date()
    while datetime.combine(day, time()) < exam_date:
        for window_start, window_end in availability.windows_for(day):
            window_end = min(window_end, exam_date)
            start = max(lower_bound, window_start)
            while start + duration <= window_end:
                conflict = next((entry for entry in busy if intervals_overlap(
                    start, start + duration, entry.start_datetime, entry.end_datetime)), None)
                if conflict is None:
                    return start
                # Any start before this busy row's end would still overlap it.
                start = conflict.end_datetime
        day += timedelta(days=1)
    return None


def place_remaining_sessions(schedule: tuple[ScheduleEntry, ...], instructions: tuple[RemainingSession, ...], *,
                             availability: AvailabilityContext, preparation_start: datetime,
                             exam_date: datetime, replan_from: datetime,
                             now: datetime) -> tuple[ScheduleEntry, ...]:
    """Return the complete schedule with every instructed source replaced by its placed remainder.

    Unselected rows are kept unchanged and act as busy time.  Each remainder
    keeps its source topic and starts no earlier than the latest of the
    preparation start, ``replan_from`` and the minute after ``now``.
    """
    pairs = _validated_sources(schedule, instructions)
    selected = {source.schedule_id for source, _ in pairs}
    candidate = [entry for entry in schedule if entry.schedule_id not in selected]
    lower_bound = max(preparation_start, replan_from, now + NEXT_MINUTE)
    for source, instruction in pairs:
        duration = timedelta(minutes=instruction.remaining_minutes)
        start = _earliest_fit(lower_bound, duration, candidate, availability, exam_date)
        if start is None:
            raise ValueError(
                f"NOT_ENOUGH_TIME: {instruction.remaining_minutes} remaining minutes of "
                f"{source.schedule_id!r} do not fit as one session in a free study window before the "
                "exam date when sessions are placed in their original order. Nothing was changed.")
        candidate.append(ScheduleEntry(instruction.replacement_schedule_id, start, start + duration,
                                       source.topic))
    return tuple(candidate)
