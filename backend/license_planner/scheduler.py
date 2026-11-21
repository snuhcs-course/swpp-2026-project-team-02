"""Assessment and availability-aware study-time allocation."""
from __future__ import annotations

from collections import defaultdict
from datetime import date, datetime, time, timedelta

from study_availability import as_context

from .catalog import Certification
from .models import PlanRequest, ProblemResult, StudyBlock, TopicStatistics
from .settings import FIRST_SCHEDULE_ID, STUDY_SCHEDULE_ID_PREFIX

MIN_STUDY_SESSION_MINUTES = 30  # Shortest continuous free time that can hold a study session.


def summarize_topic_results(results: tuple[ProblemResult, ...]) -> tuple[TopicStatistics, ...]:
    """Aggregate earned/possible points by topic, weakest topic first."""
    totals: dict[str, list[float | int]] = defaultdict(lambda: [0.0, 0.0, 0])
    for result in results:
        totals[result.topic][0] += result.possible_score
        totals[result.topic][1] += result.earned_score
        totals[result.topic][2] += result.topic_weight
    stats = [TopicStatistics(topic, values[0], values[1], values[1] / values[0] * 100, values[2])
             for topic, values in totals.items()]
    return tuple(sorted(stats, key=lambda item: (item.score_percent, item.topic.casefold())))


def _overlaps(start: datetime, end: datetime, busy_start: datetime, busy_end: datetime) -> bool:
    """Return whether two half-open datetime intervals overlap."""
    return start < busy_end and busy_start < end


def _free_segments(start: datetime, end: datetime, busy_periods) -> list[tuple[datetime, datetime]]:
    """Subtract busy intervals from one available interval."""
    segments = [(start, end)]
    for busy in sorted(busy_periods, key=lambda item: item.start_datetime):
        next_segments: list[tuple[datetime, datetime]] = []
        for segment_start, segment_end in segments:
            if not _overlaps(segment_start, segment_end, busy.start_datetime, busy.end_datetime):
                next_segments.append((segment_start, segment_end))
                continue
            if segment_start < busy.start_datetime:
                next_segments.append((segment_start, min(segment_end, busy.start_datetime)))
            if busy.end_datetime < segment_end:
                next_segments.append((max(segment_start, busy.end_datetime), segment_end))
        segments = next_segments
    return [(left, right) for left, right in segments if left < right]


def _entered_free_segments_by_day(request: PlanRequest) -> dict[date, list[tuple[datetime, datetime]]]:
    """Free intervals per date from preparation_start through exam_date, busy periods removed.

    Each date uses the shared resolver's absolute windows (including the
    previous date's overnight spill) clipped to that civil date, so a window
    crossing midnight contributes each minute to exactly one date.
    """
    context = as_context(request.weekly_availability)
    free_by_day: dict[date, list[tuple[datetime, datetime]]] = {}
    current_day = request.preparation_start.date()
    final_day = request.exam_date.date()
    while current_day <= final_day:
        day_start = datetime.combine(current_day, time())
        lower = max(day_start, request.preparation_start)
        upper = min(day_start + timedelta(days=1), request.exam_date)
        day_segments: list[tuple[datetime, datetime]] = []
        for window_start, window_end in (context.windows_for(current_day) if context else ()):
            window_start, window_end = max(window_start, lower), min(window_end, upper)
            if window_start < window_end:
                day_segments.extend(_free_segments(window_start, window_end, request.busy_periods))
        day_segments.sort(key=lambda segment: segment[0])
        free_by_day[current_day] = day_segments
        current_day += timedelta(days=1)
    return free_by_day


def _segment_minutes(start: datetime, end: datetime) -> int:
    """Whole minutes usable inside one free segment."""
    return int((end - start).total_seconds() // 60)


def _split_usable_segments(request: PlanRequest) -> tuple[dict[date, list[tuple[datetime, datetime]]], int]:
    """Keep only continuous free time of at least MIN_STUDY_SESSION_MINUTES.

    Touching or overlapping pieces form one segment across window and
    midnight boundaries; only the whole segment's length decides whether it is
    usable.  A kept segment still contributes each minute to its own date.
    Returns the usable pieces per date and the whole minutes left out because
    they lay in shorter segments.
    """
    entered = _entered_free_segments_by_day(request)
    pieces = sorted((start, end, day) for day, segments in entered.items() for start, end in segments)
    chains: list[tuple[datetime, datetime, list[tuple[datetime, datetime, date]]]] = []
    for start, end, day in pieces:
        if chains and start <= chains[-1][1]:
            chain_start, chain_end, members = chains[-1]
            members.append((start, end, day))
            chains[-1] = (chain_start, max(chain_end, end), members)
        else:
            chains.append((start, end, [(start, end, day)]))
    usable: dict[date, list[tuple[datetime, datetime]]] = {day: [] for day in entered}
    dropped = 0
    for chain_start, chain_end, chain in chains:
        if _segment_minutes(chain_start, chain_end) < MIN_STUDY_SESSION_MINUTES:
            dropped += _segment_minutes(chain_start, chain_end)
            continue
        for start, end, day in chain:
            day_pieces = usable[day]
            if day_pieces and start <= day_pieces[-1][1]:
                day_pieces[-1] = (day_pieces[-1][0], max(end, day_pieces[-1][1]))
            else:
                day_pieces.append((start, end))
    return usable, dropped


def _free_segments_by_day(request: PlanRequest) -> dict[date, list[tuple[datetime, datetime]]]:
    """Usable free intervals per date, shared by capacity checks and placement."""
    return _split_usable_segments(request)[0]


def short_segment_minutes(request: PlanRequest) -> int:
    """Whole entered free minutes left empty because their segment is under the session minimum."""
    return _split_usable_segments(request)[1]


def usable_minutes_by_date(request: PlanRequest) -> dict[date, int]:
    """Whole usable free minutes per date, using the same segments as placement."""
    return {day: sum(_segment_minutes(start, end) for start, end in segments)
            for day, segments in _free_segments_by_day(request).items()}


def available_minutes(request: PlanRequest) -> int:
    """Total whole usable free minutes between preparation_start and exam_date."""
    return sum(usable_minutes_by_date(request).values())


def generate_schedule(request: PlanRequest, cert: Certification,
                      topic_statistics: tuple[TopicStatistics, ...],
                      daily_topic_minutes: list[dict], *, preserve_topic_order: bool = False) -> list[StudyBlock]:
    """Place per-date topic minutes within user windows.

    Minutes come from the backend's budgets for diagnosed plans or the Agent's
    legacy proposal when no topic statistics exist. This function validates
    date/topic/time constraints and packs the plan into free segments of at
    least MIN_STUDY_SESSION_MINUTES continuous minutes; shorter free segments
    stay empty. Topic blocks inside a segment may be any positive length, and no
    daily block-count limit applies. Diagnosed plans fill each usable segment.
    Legacy proposals can fill them partially, but every resulting session must
    still meet the minimum.
    """
    if request.preparation_start is None or request.exam_date is None:
        raise ValueError("preparation_start and exam_date are required before generating a dated schedule")
    if request.preparation_start >= request.exam_date:
        raise ValueError("preparation_start must be before exam_date")
    context = as_context(request.weekly_availability)
    if context is None or not context.has_windows():
        raise ValueError(
            "weekly_availability is required. Enter at least one weekday and available time window."
        )
    topic_order = ([item.topic for item in topic_statistics]
                   if topic_statistics else list(cert.topics))
    if not isinstance(daily_topic_minutes, list) or not daily_topic_minutes:
        raise ValueError("daily_topic_minutes must be a non-empty array")
    planned_by_day: dict[date, dict[str, int]] = defaultdict(lambda: defaultdict(int))
    planned_topic_totals: dict[str, int] = defaultdict(int)
    for item in daily_topic_minutes:
        if not isinstance(item, dict):
            raise ValueError("each daily_topic_minutes item must be an object")
        topic, raw_date, minutes = item.get("topic"), item.get("date"), item.get("minutes")
        if not isinstance(topic, str) or topic not in topic_order:
            raise ValueError("daily_topic_minutes contains a topic outside the assessed topics")
        if not isinstance(minutes, int) or isinstance(minutes, bool) or minutes <= 0:
            raise ValueError("daily_topic_minutes minutes must be a positive integer")
        if not isinstance(raw_date, str):
            raise ValueError("daily_topic_minutes date must use YYYY/MM/DD format")
        try:
            plan_date = datetime.strptime(raw_date, "%Y/%m/%d").date()
        except ValueError as error:
            raise ValueError("daily_topic_minutes date must use YYYY/MM/DD format") from error
        if not request.preparation_start.date() <= plan_date <= request.exam_date.date():
            raise ValueError("daily_topic_minutes date must be between preparation_start and exam_date")
        planned_by_day[plan_date][topic] += minutes
        planned_topic_totals[topic] += minutes
    if set(planned_topic_totals) != set(topic_order):
        missing = sorted(set(topic_order) - set(planned_topic_totals))
        raise ValueError(f"daily_topic_minutes must include each assessed topic; missing={missing}")

    free_by_day = _free_segments_by_day(request)
    for plan_date, topic_minutes in planned_by_day.items():
        capacity = sum(_segment_minutes(start, end) for start, end in free_by_day.get(plan_date, []))
        requested = sum(topic_minutes.values())
        if requested > capacity:
            raise ValueError(
                f"NOT_ENOUGH_TIME: {requested} planned minutes exceed {capacity} available minutes on {plan_date}"
            )
    busy_periods = list(request.busy_periods)
    used_ids = {int(period.schedule_id) for period in busy_periods}
    next_id = max(used_ids, default=FIRST_SCHEDULE_ID - 1) + 1
    blocks: list[StudyBlock] = []

    for day in sorted(planned_by_day):
        remaining_by_topic = dict(planned_by_day[day])
        day_order = list(remaining_by_topic) if preserve_topic_order else topic_order
        for segment_start, segment_end in free_by_day[day]:
            cursor = segment_start
            segment_minutes = _segment_minutes(segment_start, segment_end)
            while cursor < segment_end and segment_minutes > 0:
                topic = next((name for name in day_order if remaining_by_topic.get(name, 0) > 0), None)
                if topic is None:
                    break
                remaining_segment = _segment_minutes(cursor, segment_end)
                minutes = min(remaining_segment, segment_minutes, remaining_by_topic[topic])
                if minutes <= 0:
                    break
                while next_id in used_ids:
                    next_id += 1
                end = cursor + timedelta(minutes=minutes)
                blocks.append(StudyBlock(f"{STUDY_SCHEDULE_ID_PREFIX}-{next_id}", cursor, end, topic))
                used_ids.add(next_id)
                next_id += 1
                cursor = end
                remaining_by_topic[topic] -= minutes
                segment_minutes -= minutes
                if segment_minutes <= 0:
                    break
        if any(remaining_by_topic.values()):
            raise ValueError(f"NOT_ENOUGH_TIME: planned minutes do not fit available windows on {day}")
    if not topic_statistics:
        _validate_proposed_sessions(blocks)
    return blocks


def _validate_proposed_sessions(blocks: list[StudyBlock]) -> None:
    session_start = blocks[0].start_datetime
    for index, block in enumerate(blocks):
        end = block.end_datetime
        if index + 1 < len(blocks) and end == blocks[index + 1].start_datetime:
            continue
        if _segment_minutes(session_start, end) < MIN_STUDY_SESSION_MINUTES:
            raise ValueError(
                f"Proposed placement leaves a study session shorter than {MIN_STUDY_SESSION_MINUTES} minutes "
                f"at {session_start:%Y/%m/%d %H:%M}. Revise the date's allocation so each continuous "
                "study session meets the minimum."
            )
        if index + 1 < len(blocks):
            session_start = blocks[index + 1].start_datetime
