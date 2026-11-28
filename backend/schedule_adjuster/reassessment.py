"""Re-split future study topics without moving or adding any study minutes."""
from __future__ import annotations

import heapq
import re
from collections import defaultdict
from datetime import datetime, timedelta

from license_planner.models import TopicStatistics
from license_planner.topic_budget import calculate_topic_budget
from .models import ScheduleEntry


class ReassessmentPreviewExpired(ValueError):
    """A row covered by the preview has started since the quiz was opened."""


MIN_SESSION_MINUTES = 15


def _session_budgets(durations: list[int], budget: list[tuple[str, int]]) -> list[list[tuple[str, int]]]:
    """Round score targets to usable pieces inside the existing time intervals.

    Keep exact score targets when their pieces already satisfy the minimum.
    Otherwise divide each interval into balanced cells of at least 15 minutes. Reserve
    one cell per topic, then assign cells to the largest target deficit (stable
    budget order on ties). Merge adjacent same-topic cells
    when materializing rows. Existing sub-15-minute intervals stay indivisible;
    no new short interval is introduced. Targets may round, but minutes cannot
    move between intervals and no assessed topic is silently dropped.
    """
    # Keep the exact score targets when they already fit without short pieces.
    exact = []
    topic_index, left = 0, budget[0][1]
    for duration in durations:
        remaining, pieces = duration, []
        while remaining:
            take = min(left, remaining)
            pieces.append((budget[topic_index][0], take))
            remaining -= take
            left -= take
            if left == 0:
                topic_index += 1
                if topic_index < len(budget):
                    left = budget[topic_index][1]
        exact.append(pieces)
    if all(all(minutes >= min(MIN_SESSION_MINUTES, duration) for _, minutes in pieces)
           for duration, pieces in zip(durations, exact)):
        return exact
    cells = []
    counts = []
    for duration in durations:
        count = max(1, duration // MIN_SESSION_MINUTES)
        size, extra = divmod(duration, count)
        cells.extend([size + (index < extra) for index in range(count)])
        counts.append(count)
    if len(cells) < len(budget):
        raise ValueError("NOT_ENOUGH_TIME: existing intervals cannot cover every topic without short sessions")
    total = sum(cells)
    allocated = [1] * len(budget)
    # Compare deficits as integer numerators to avoid float-dependent ties.
    queue = [(total - minutes * len(cells), index) for index, (_, minutes) in enumerate(budget)]
    heapq.heapify(queue)
    for _ in range(len(cells) - len(budget)):
        deficit, index = heapq.heappop(queue)
        allocated[index] += 1
        heapq.heappush(queue, (deficit + total, index))
    labels = [topic for index, (topic, _) in enumerate(budget)
              for _ in range(allocated[index])]
    # Unequal original windows have unequal cell lengths. Give the largest
    # realized allocations to the weakest topics; this also minimizes squared
    # target error among permutations of these allocations.
    realized = defaultdict(int)
    for topic, duration in zip(labels, cells):
        realized[topic] += duration
    rank = {topic: index for index, (topic, _) in enumerate(budget)}
    actual_order = sorted(realized, key=lambda topic: (-realized[topic], rank[topic]))
    renamed = {old: new for old, (new, _) in zip(actual_order, budget)}
    labels = [renamed[topic] for topic in labels]
    offset = 0
    rows = []
    for count in counts:
        pieces = []
        for index in range(offset, offset + count):
            topic, minutes = labels[index], cells[index]
            if pieces and pieces[-1][0] == topic:
                pieces[-1] = (topic, pieces[-1][1] + minutes)
            else:
                pieces.append((topic, minutes))
        rows.append(pieces)
        offset += count
    return rows


def resplit_study(schedule: tuple[ScheduleEntry, ...], keep: set[str],
                  results: list[tuple[str, float, bool]], *, replan_from: datetime,
                  now: datetime, first_new_study_id: str) -> tuple[ScheduleEntry, ...]:
    """Match Android Reassessment.resplit, including stable rounding ties.

    Reported IDs are device-local and supplied by the client. All other protected
    rows are inferred from the authoritative saved schedule and cutoff.
    """
    by_id = {row.schedule_id: row for row in schedule}
    if any(key not in by_id or by_id[key].schedule_type != "study"
           or by_id[key].start_datetime < replan_from for key in keep):
        raise ValueError("keep_schedule_ids must identify future study rows in schedules")
    match = re.fullmatch(r"study-([1-9][0-9]*)", first_new_study_id)
    highest = max((int(row.schedule_id[6:]) for row in schedule if row.schedule_type == "study"), default=0)
    if match is None or int(match[1]) <= highest:
        raise ValueError("first_new_study_id must be numbered above every current study row")
    next_id = int(match[1])
    revisable = sorted((row for row in schedule if row.schedule_type == "study"
                        and row.schedule_id not in keep and row.start_datetime >= replan_from),
                       key=lambda row: (row.start_datetime, row.schedule_id))
    if any(row.start_datetime < now for row in revisable):
        raise ReassessmentPreviewExpired("A study session has started. Preview the plan again.")
    if not revisable:
        raise ValueError("NOTHING_TO_REVISE: no future unreported study sessions")
    totals = defaultdict(lambda: [0.0, 0.0, 0])
    for topic, possible, correct in results:
        totals[topic][0] += possible
        totals[topic][1] += possible if correct else 0
        totals[topic][2] += 1
    statistics = tuple(TopicStatistics(topic, possible, earned, earned / possible * 100, count)
                       for topic, (possible, earned, count) in totals.items())
    minutes = sum(int((row.end_datetime - row.start_datetime).total_seconds() // 60) for row in revisable)
    budget = list(calculate_topic_budget(statistics, minutes).items())
    durations = [int((row.end_datetime - row.start_datetime).total_seconds() // 60) for row in revisable]
    allocation = _session_budgets(durations, budget)
    revised = []
    for row, assigned in zip(revisable, allocation):
        cursor, pieces = row.start_datetime, []
        for topic, take in assigned:
            end = cursor + timedelta(minutes=take)
            pieces.append((topic, cursor, end))
            cursor = end
        if len(pieces) == 1 and pieces[0][0] == row.topic:
            revised.append(row)
        else:
            for topic, start, end in pieces:
                revised.append(ScheduleEntry(f"study-{next_id}", start, end, topic))
                next_id += 1
    removed = {row.schedule_id for row in revisable}
    return tuple(sorted([row for row in schedule if row.schedule_id not in removed] + revised,
                        key=lambda row: (row.start_datetime, row.end_datetime, row.schedule_id)))
