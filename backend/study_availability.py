"""Shared study-availability windows, week replacements and date resolution.

Both the License Planner and the Schedule Adjuster read the same JSON shape and
must interpret it identically, so the parser, overlap rule and per-date
resolver live here.  A window is owned by the weekday it starts on and lasts
``duration_minutes`` (1-1439), so it may end on the next civil date.  Week
replacements (``availability_overrides``) are keyed by the Monday of the week
they replace; a window always follows the week of the date it starts on.

The module is pure apart from the explicit profile load/save helpers, which
take their path as an argument.
"""
from __future__ import annotations

import json
import os
import re
import tempfile
from dataclasses import dataclass
from datetime import date, datetime, time, timedelta
from pathlib import Path
from typing import Any, Iterable

WEEKDAY_NAMES = ("monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday")
TIME_PATTERN = re.compile(r"(?:[01]\d|2[0-3]):[0-5]\d")
OVERRIDE_KEY_FORMAT = "%Y/%m/%d"
MIN_DURATION_MINUTES = 1
MAX_DURATION_MINUTES = 1439
MINUTES_PER_DAY = 24 * 60
_WINDOW_KEYS = ({"start", "end"}, {"start", "duration_minutes"}, {"start", "end", "duration_minutes"})
_WRAPPER_KEYS = {"weekly_availability", "availability_overrides"}
# Any Monday works as the reference week for checking the recurring base cycle.
_REFERENCE_MONDAY = date(2001, 1, 1)

Interval = tuple[datetime, datetime]


@dataclass(frozen=True)
class AvailabilityWindow:
    """One study window owned by the weekday it starts on."""
    start_time: time
    duration_minutes: int

    @property
    def end_time(self) -> time:
        """Clock time the window ends; earlier than start_time when it ends the next day."""
        total = (self.start_time.hour * 60 + self.start_time.minute + self.duration_minutes) % MINUTES_PER_DAY
        return time(total // 60, total % 60)

    @property
    def ends_next_day(self) -> bool:
        return self.start_time.hour * 60 + self.start_time.minute + self.duration_minutes > MINUTES_PER_DAY

    def interval_on(self, origin: date) -> Interval:
        """Absolute half-open interval when this window starts on ``origin``."""
        start = datetime.combine(origin, self.start_time)
        return start, start + timedelta(minutes=self.duration_minutes)


@dataclass(frozen=True)
class WeeklyAvailability:
    """Windows for Monday (index 0) through Sunday (index 6)."""
    weekday_windows: tuple[tuple[AvailabilityWindow, ...], ...]

    def for_weekday(self, weekday: int) -> tuple[AvailabilityWindow, ...]:
        return self.weekday_windows[weekday]

    def has_windows(self) -> bool:
        return any(self.weekday_windows)


EMPTY_WEEK = WeeklyAvailability(tuple(() for _ in WEEKDAY_NAMES))


@dataclass(frozen=True)
class AvailabilityContext:
    """A recurring base week plus whole-week replacements keyed by Monday."""
    base: WeeklyAvailability
    overrides: tuple[tuple[date, WeeklyAvailability], ...] = ()

    def for_weekday(self, weekday: int) -> tuple[AvailabilityWindow, ...]:
        """Base windows for one weekday (kept for callers that predate week overrides)."""
        return self.base.for_weekday(weekday)

    def week_for(self, origin: date) -> WeeklyAvailability:
        """The weekly windows that apply to windows starting on ``origin``."""
        monday = origin - timedelta(days=origin.weekday())
        for key, week in self.overrides:
            if key == monday:
                return week
        return self.base

    def has_windows(self) -> bool:
        """Whether any window exists anywhere in the context."""
        return self.base.has_windows() or any(week.has_windows() for _, week in self.overrides)

    def windows_for(self, day: date) -> tuple[Interval, ...]:
        """Sorted absolute intervals intersecting ``day`` with their full endpoints.

        Includes windows starting on ``day`` and the previous date's window that
        spills past midnight, each chosen by its own origin date's week.
        """
        day_start = datetime.combine(day, time())
        day_end = day_start + timedelta(days=1)
        intervals = set()
        for origin in (day - timedelta(days=1), day):
            for window in self.week_for(origin).for_weekday(origin.weekday()):
                start, end = window.interval_on(origin)
                if start < day_end and day_start < end:
                    intervals.add((start, end))
        return tuple(sorted(intervals))

    def last_override_day(self) -> date | None:
        """Last date on which an override-week window can still end."""
        if not self.overrides:
            return None
        return self.overrides[-1][0] + timedelta(days=7)


def as_context(value: AvailabilityContext | WeeklyAvailability | None) -> AvailabilityContext | None:
    """Accept either a full context or a bare recurring week."""
    if value is None or isinstance(value, AvailabilityContext):
        return value
    return AvailabilityContext(value)


# ---------------------------------------------------------------------------
# Parsing


def _parse_clock(value: Any, label: str) -> time:
    if not isinstance(value, str) or not TIME_PATTERN.fullmatch(value):
        raise ValueError(f"{label} times must use HH:MM")
    return time.fromisoformat(value)


def _minutes(value: time) -> int:
    return value.hour * 60 + value.minute


def parse_window(raw: Any, label: str) -> AvailabilityWindow:
    """Parse ``{start,end}``, ``{start,duration_minutes}`` or all three keys."""
    if not isinstance(raw, dict) or set(raw) not in _WINDOW_KEYS:
        raise ValueError(f"{label} must contain start with end and/or duration_minutes only")
    start = _parse_clock(raw["start"], label)
    end = _parse_clock(raw["end"], label) if "end" in raw else None
    if "duration_minutes" in raw:
        duration = raw["duration_minutes"]
        if (not isinstance(duration, int) or isinstance(duration, bool)
                or not MIN_DURATION_MINUTES <= duration <= MAX_DURATION_MINUTES):
            raise ValueError(f"{label} duration_minutes must be an integer from "
                             f"{MIN_DURATION_MINUTES} to {MAX_DURATION_MINUTES}")
        window = AvailabilityWindow(start, duration)
        if end is not None and window.end_time != end:
            raise ValueError(f"{label} end does not match start plus duration_minutes")
        return window
    if end is None or start >= end:
        raise ValueError(f"{label} start must be before end")
    return AvailabilityWindow(start, _minutes(end) - _minutes(start))


def parse_weekday_map(data: Any, label: str = "weekly_availability") -> WeeklyAvailability:
    """Parse one weekday-keyed map; omitted weekdays become empty lists."""
    if not isinstance(data, dict):
        raise ValueError(f"{label} must be a JSON object keyed by weekday")
    unknown_days = set(data) - set(WEEKDAY_NAMES)
    if unknown_days:
        raise ValueError(f"unknown weekday names: {', '.join(sorted(map(str, unknown_days)))}")
    days: list[tuple[AvailabilityWindow, ...]] = []
    for weekday in WEEKDAY_NAMES:
        raw_windows = data.get(weekday, [])
        if not isinstance(raw_windows, list):
            raise ValueError(f"{weekday} must contain a JSON list of time windows")
        windows = [parse_window(raw, f"{weekday} window {position}")
                   for position, raw in enumerate(raw_windows, start=1)]
        windows.sort(key=lambda window: (window.start_time, window.duration_minutes))
        days.append(tuple(windows))
    return WeeklyAvailability(tuple(days))


def parse_override_key(key: Any) -> date:
    """A canonical YYYY/MM/DD Monday."""
    try:
        if not isinstance(key, str):
            raise ValueError
        monday = datetime.strptime(key, OVERRIDE_KEY_FORMAT).date()
    except ValueError as error:
        raise ValueError("availability_overrides keys must be YYYY/MM/DD Monday dates") from error
    if monday.strftime(OVERRIDE_KEY_FORMAT) != key or monday.weekday() != 0:
        raise ValueError("availability_overrides keys must be YYYY/MM/DD Monday dates")
    return monday


def parse_overrides(data: Any) -> tuple[tuple[date, WeeklyAvailability], ...]:
    """Parse the Monday-keyed whole-week replacement map."""
    if not isinstance(data, dict):
        raise ValueError("availability_overrides must be a JSON object keyed by Monday date")
    parsed = []
    for key, week in data.items():
        monday = parse_override_key(key)
        if not isinstance(week, dict):
            raise ValueError(f"availability_overrides {key} must be a JSON object keyed by weekday")
        parsed.append((monday, parse_weekday_map(week, f"availability_overrides {key}")))
    return tuple(sorted(parsed, key=lambda item: item[0]))


def parse_availability_payload(payload: Any) -> tuple[WeeklyAvailability, tuple | None]:
    """Parse a direct weekday map or the ``weekly_availability`` wrapper.

    Returns the base week and the override map, or ``None`` for overrides when
    the payload did not contain an ``availability_overrides`` member.
    """
    try:
        data = json.loads(payload) if isinstance(payload, (str, bytes)) else payload
    except json.JSONDecodeError as error:
        raise ValueError(f"availability JSON is invalid: {error.msg}") from error
    if not isinstance(data, dict):
        raise ValueError("availability must be a JSON object keyed by weekday")
    if not _WRAPPER_KEYS & set(data):
        return parse_weekday_map(data), None
    unknown = set(data) - _WRAPPER_KEYS
    if unknown:
        raise ValueError(f"unknown availability fields: {', '.join(sorted(map(str, unknown)))}")
    if "weekly_availability" not in data:
        raise ValueError("weekly_availability is required")
    base = parse_weekday_map(data["weekly_availability"])
    if "availability_overrides" not in data:
        return base, None
    if data["availability_overrides"] is None:
        raise ValueError("availability_overrides must be an object; omit it to keep inherited weeks")
    return base, parse_overrides(data["availability_overrides"])


def _describe(origin: date, window: AvailabilityWindow) -> str:
    return (f"{origin:%Y/%m/%d} ({WEEKDAY_NAMES[origin.weekday()]}) "
            f"{window.start_time:%H:%M} for {window.duration_minutes} minutes")


def _check_no_overlap(context: AvailabilityContext, origins: Iterable[date]) -> None:
    spans = sorted(
        ((window.interval_on(origin) + (origin, window))
         for origin in set(origins)
         for window in context.week_for(origin).for_weekday(origin.weekday())),
        key=lambda span: (span[0], span[1]),
    )
    for previous, current in zip(spans, spans[1:]):
        if current[0] < previous[1]:
            raise ValueError("availability windows cannot overlap: "
                             f"{_describe(previous[2], previous[3])} and {_describe(current[2], current[3])}")


def validate_context(context: AvailabilityContext) -> AvailabilityContext:
    """Reject overlapping effective windows, including next-day and week-boundary spills.

    The recurring base is checked over one week plus the following Monday.
    Each override week is checked with the day before it and the following
    Monday, so override/base and adjacent-override boundaries are covered.
    """
    _check_no_overlap(AvailabilityContext(context.base),
                      (_REFERENCE_MONDAY + timedelta(days=offset) for offset in range(8)))
    origins: set[date] = set()
    for monday, _ in context.overrides:
        origins.update(monday + timedelta(days=offset) for offset in range(-1, 8))
    if origins:
        _check_no_overlap(context, origins)
    return context


def make_context(base: WeeklyAvailability,
                 overrides: Iterable[tuple[date, WeeklyAvailability]] = ()) -> AvailabilityContext:
    """Build and validate one context."""
    return validate_context(AvailabilityContext(base, tuple(sorted(overrides, key=lambda item: item[0]))))


def parse_availability_context(payload: Any) -> AvailabilityContext | None:
    """Parse and validate a direct map or wrapper; ``None`` stays absent."""
    if payload is None:
        return None
    base, overrides = parse_availability_payload(payload)
    return make_context(base, overrides or ())


# ---------------------------------------------------------------------------
# Serialization


def window_payload(window: AvailabilityWindow, *, include_end: bool) -> dict[str, Any]:
    payload: dict[str, Any] = {"start": window.start_time.strftime("%H:%M"),
                               "duration_minutes": window.duration_minutes}
    if include_end:
        payload["end"] = window.end_time.strftime("%H:%M")
    return payload


def week_payload(week: WeeklyAvailability, *, include_end: bool) -> dict[str, list[dict[str, Any]]]:
    return {name: [window_payload(window, include_end=include_end) for window in week.for_weekday(index)]
            for index, name in enumerate(WEEKDAY_NAMES)}


def context_payload(context: AvailabilityContext | None, *, include_end: bool) -> dict[str, Any]:
    """``{"weekly_availability":..., "availability_overrides":...}`` for storage or responses."""
    if context is None:
        context = AvailabilityContext(EMPTY_WEEK)
    return {
        "weekly_availability": week_payload(context.base, include_end=include_end),
        "availability_overrides": {monday.strftime(OVERRIDE_KEY_FORMAT): week_payload(week, include_end=include_end)
                                   for monday, week in context.overrides},
    }


# ---------------------------------------------------------------------------
# Shared profile file


def load_availability_profile(path: Path) -> AvailabilityContext | None:
    """Read either stored window form without rewriting; ``None`` when absent."""
    if not path.exists():
        return None
    try:
        record = json.loads(path.read_text(encoding="utf-8"))
    except (json.JSONDecodeError, UnicodeDecodeError) as error:
        raise ValueError("saved personal availability JSON is invalid") from error
    if not isinstance(record, dict):
        raise ValueError("saved availability JSON must be an object")
    try:
        return parse_availability_context(record)
    except ValueError as error:
        raise ValueError(f"saved personal availability is invalid: {error}") from error


def save_availability_profile(payload: Any, path: Path) -> AvailabilityContext:
    """Validate and atomically write the canonical duration form.

    A payload without ``availability_overrides`` replaces only the base and
    keeps the stored overrides; an explicit ``{}`` clears them.  Nothing is
    written when validation fails.
    """
    if payload is None:
        raise ValueError("availability cannot be None when saving a user profile")
    base, overrides = parse_availability_payload(payload)
    if overrides is None:
        stored = load_availability_profile(path)
        overrides = stored.overrides if stored is not None else ()
    context = make_context(base, overrides)
    document = context_payload(context, include_end=False)
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary_path: Path | None = None
    try:
        with tempfile.NamedTemporaryFile("w", encoding="utf-8", newline="\n",
                                         dir=path.parent, suffix=".tmp", delete=False) as temporary_file:
            temporary_path = Path(temporary_file.name)
            json.dump(document, temporary_file, ensure_ascii=False, indent=2)
            temporary_file.write("\n")
        os.replace(temporary_path, path)
    finally:
        if temporary_path is not None and temporary_path.exists():
            temporary_path.unlink()
    return context
