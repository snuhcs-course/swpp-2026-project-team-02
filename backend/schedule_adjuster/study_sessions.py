"""Continuous study sessions formed by back-to-back study rows.

A session is a maximal run of study rows in which each row ends exactly when
the next begins.  External rows are not study, so they end a run like any gap.
"""
from __future__ import annotations

from collections.abc import Iterable
from datetime import datetime, timedelta

from .models import ScheduleEntry

MIN_STUDY_SESSION_MINUTES = 30  # Shortest session an automatic placement may leave or create.
MIN_STUDY_SESSION = timedelta(minutes=MIN_STUDY_SESSION_MINUTES)


def study_sessions(entries: Iterable[ScheduleEntry]) -> list[tuple[datetime, datetime]]:
    """Return the (start, end) of every maximal back-to-back run of study rows, in time order."""
    runs: list[tuple[datetime, datetime]] = []
    for start, end in sorted((entry.start_datetime, entry.end_datetime)
                             for entry in entries if entry.schedule_type == "study"):
        if runs and start <= runs[-1][1]:
            runs[-1] = (runs[-1][0], max(runs[-1][1], end))
        else:
            runs.append((start, end))
    return runs


def session_containing(entries: Iterable[ScheduleEntry], row: ScheduleEntry) -> tuple[datetime, datetime]:
    """Return the session of ``entries`` that contains ``row``."""
    return next((start, end) for start, end in study_sessions(entries)
                if start <= row.start_datetime and row.end_datetime <= end)


def keeps_study_sessions(candidate: Iterable[ScheduleEntry], placed_ids: set[str],
                         original: Iterable[ScheduleEntry], future_from: datetime | None) -> bool:
    """Whether a candidate schedule keeps automatic placements in long-enough sessions.

    Every session holding a row in ``placed_ids`` must last at least
    ``MIN_STUDY_SESSION``.  When ``future_from`` is given, a session starting
    at or after it must not have been cut below the minimum by taking rows out
    of an original session that met it.  Earlier sessions are not checked.
    """
    candidate = tuple(candidate)
    placed = [(entry.start_datetime, entry.end_datetime) for entry in candidate if entry.schedule_id in placed_ids]
    original_runs = study_sessions(original)
    for start, end in study_sessions(candidate):
        if end - start >= MIN_STUDY_SESSION:
            continue
        if any(start <= placed_start and placed_end <= end for placed_start, placed_end in placed):
            return False
        if future_from is not None and start >= future_from and any(
                run_start <= start and end <= run_end and run_end - run_start >= MIN_STUDY_SESSION
                for run_start, run_end in original_runs):
            return False
    return True


def next_entry_end(after: datetime, entries: Iterable[ScheduleEntry]) -> datetime | None:
    """The earliest row end strictly after ``after``; later free time can only start at such an end."""
    return min((entry.end_datetime for entry in entries if entry.end_datetime > after), default=None)
