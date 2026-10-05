"""Assessment and availability-aware study-time allocation."""
from __future__ import annotations

from collections import defaultdict
from datetime import date, datetime, timedelta

from .catalog import Certification
from .models import PlanRequest, ProblemResult, StudyBlock, TopicStatistics
from .settings import FIRST_SCHEDULE_ID, STUDY_SCHEDULE_ID_PREFIX


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


def generate_schedule(request: PlanRequest, cert: Certification,
                      topic_statistics: tuple[TopicStatistics, ...],
                      daily_topic_minutes: list[dict]) -> list[StudyBlock]:
    """Place Agent-proposed topic minutes on chosen dates within user windows.

    The Agent chooses the study dates and flexible durations. This function
    validates date/topic/time constraints and packs the plan into free windows;
    it applies no fixed session length or daily block-count limit.
    """
    if request.preparation_start is None or request.exam_date is None:
        raise ValueError("preparation_start and exam_date are required before generating a dated schedule")
    if request.preparation_start >= request.exam_date:
        raise ValueError("preparation_start must be before exam_date")
    if (request.weekly_availability is None
            or not any(request.weekly_availability.for_weekday(day) for day in range(7))):
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

    free_by_day: dict[date, list[tuple[datetime, datetime]]] = {}
    current_day = request.preparation_start.date()
    final_day = request.exam_date.date()
    while current_day <= final_day:
        day_segments: list[tuple[datetime, datetime]] = []
        for window in request.weekly_availability.for_weekday(current_day.weekday()):
            window_start = datetime.combine(current_day, window.start_time)
            window_end = datetime.combine(current_day, window.end_time)
            if current_day == request.preparation_start.date():
                window_start = max(window_start, request.preparation_start)
            if current_day == request.exam_date.date():
                window_end = min(window_end, request.exam_date)
            if window_start < window_end:
                day_segments.extend(_free_segments(window_start, window_end, request.busy_periods))
        day_segments.sort(key=lambda segment: segment[0])
        free_by_day[current_day] = day_segments
        current_day += timedelta(days=1)

    for plan_date, topic_minutes in planned_by_day.items():
        capacity = sum(int((end - start).total_seconds() // 60)
                       for start, end in free_by_day.get(plan_date, []))
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
        for segment_start, segment_end in free_by_day[day]:
            cursor = segment_start
            segment_minutes = int((segment_end - segment_start).total_seconds() // 60)
            while cursor < segment_end and segment_minutes > 0:
                topic = next((name for name in topic_order if remaining_by_topic.get(name, 0) > 0), None)
                if topic is None:
                    break
                remaining_segment = int((segment_end - cursor).total_seconds() // 60)
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
    return blocks
