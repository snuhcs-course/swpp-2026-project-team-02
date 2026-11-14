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


def _free_segments_by_day(request: PlanRequest) -> dict[date, list[tuple[datetime, datetime]]]:
    """Free intervals per date from preparation_start through exam_date, busy periods removed."""
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
    return free_by_day


def _segment_minutes(start: datetime, end: datetime) -> int:
    """Whole minutes usable inside one free segment."""
    return int((end - start).total_seconds() // 60)


def usable_minutes_by_date(request: PlanRequest) -> dict[date, int]:
    """Whole free minutes per date, using the same segments as placement."""
    return {day: sum(_segment_minutes(start, end) for start, end in segments)
            for day, segments in _free_segments_by_day(request).items()}


def available_minutes(request: PlanRequest) -> int:
    """Total whole free minutes between preparation_start and exam_date."""
    return sum(usable_minutes_by_date(request).values())


def repeat_plan_for_review(
    request: PlanRequest,
    daily_topic_minutes: list[dict],
    topic_order: list[str],
) -> tuple[list[dict], int]:
    """Repeat the accepted topic-minute mix in complete rounds that fit total capacity.

    Additional minutes are packed into dates with remaining capacity, preferring dates
    already assigned to the same topic and then nearby dates. Any capacity smaller than
    another complete round remains spare time.
    """
    capacities = usable_minutes_by_date(request)
    allocation: dict[date, dict[str, int]] = defaultdict(lambda: defaultdict(int))
    base_topic_minutes: dict[str, int] = defaultdict(int)
    base_minutes = 0
    for item in daily_topic_minutes:
        plan_date = datetime.strptime(item["date"], "%Y/%m/%d").date()
        topic, minutes = item["topic"], item["minutes"]
        allocation[plan_date][topic] += minutes
        base_topic_minutes[topic] += minutes
        base_minutes += minutes

    total_available = sum(capacities.values())
    if base_minutes <= 0 or total_available < base_minutes:
        return list(daily_topic_minutes), 0

    total_rounds = total_available // base_minutes
    review_rounds = total_rounds - 1
    if review_rounds <= 0:
        return list(daily_topic_minutes), 0

    remaining = {
        plan_date: capacity - sum(allocation.get(plan_date, {}).values())
        for plan_date, capacity in capacities.items()
    }
    completed_rounds = 0
    for _ in range(review_rounds):
        round_additions: list[tuple[date, str, int]] = []
        round_remaining = remaining.copy()
        round_topic_dates = {
            topic: {day for day, by_topic in allocation.items() if by_topic.get(topic, 0) > 0}
            for topic in topic_order
        }
        complete = True
        for topic in topic_order:
            needed = base_topic_minutes.get(topic, 0)
            if needed <= 0:
                continue
            while needed > 0:
                candidates = [day for day, capacity in round_remaining.items() if capacity > 0]
                if not candidates:
                    complete = False
                    break
                existing_dates = round_topic_dates.get(topic, set())
                candidates.sort(key=lambda day: (
                    0 if day in existing_dates else 1,
                    min((abs((day - existing).days) for existing in existing_dates), default=0),
                    day,
                ))
                day = candidates[0]
                amount = min(needed, round_remaining[day])
                round_additions.append((day, topic, amount))
                round_remaining[day] -= amount
                needed -= amount
                round_topic_dates.setdefault(topic, set()).add(day)
            if not complete:
                break
        if not complete:
            break
        for day, topic, amount in round_additions:
            allocation[day][topic] += amount
        remaining = round_remaining
        completed_rounds += 1

    if completed_rounds == 0:
        return list(daily_topic_minutes), 0

    topic_rank = {topic: index for index, topic in enumerate(topic_order)}
    expanded = [
        {"date": day.strftime("%Y/%m/%d"), "topic": topic, "minutes": minutes}
        for day in sorted(allocation)
        for topic, minutes in sorted(
            allocation[day].items(),
            key=lambda pair: (topic_rank.get(pair[0], len(topic_rank)), pair[0]),
        )
        if minutes > 0
    ]
    return expanded, completed_rounds * base_minutes


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
        for segment_start, segment_end in free_by_day[day]:
            cursor = segment_start
            segment_minutes = _segment_minutes(segment_start, segment_end)
            while cursor < segment_end and segment_minutes > 0:
                topic = next((name for name in topic_order if remaining_by_topic.get(name, 0) > 0), None)
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
    return blocks
