"""Re-split future study topics without moving or adding any study minutes."""
from __future__ import annotations

import re
from collections import defaultdict
from datetime import datetime, timedelta

from license_planner.models import TopicStatistics
from license_planner.topic_budget import calculate_topic_budget
from .models import ScheduleEntry


class ReassessmentPreviewExpired(ValueError):
    """A row covered by the preview has started since the quiz was opened."""


def resplit_study(schedule: tuple[ScheduleEntry, ...], keep: set[str],
                  results: list[tuple[str, float, bool]], *, replan_from: datetime,
                  now: datetime, first_new_study_id: str) -> tuple[ScheduleEntry, ...]:
    """Match Android Reassessment.resplit, including stable largest-remainder ties.

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
    topic_index, left = 0, budget[0][1]
    revised = []
    for row in revisable:
        cursor, pieces = row.start_datetime, []
        while cursor < row.end_datetime:
            take = min(left, int((row.end_datetime - cursor).total_seconds() // 60))
            end = cursor + timedelta(minutes=take)
            pieces.append((budget[topic_index][0], cursor, end))
            cursor = end
            left -= take
            if left == 0:
                topic_index += 1
                if topic_index < len(budget):
                    left = budget[topic_index][1]
        if len(pieces) == 1 and pieces[0][0] == row.topic:
            revised.append(row)
        else:
            for topic, start, end in pieces:
                revised.append(ScheduleEntry(f"study-{next_id}", start, end, topic))
                next_id += 1
    removed = {row.schedule_id for row in revisable}
    return tuple(sorted([row for row in schedule if row.schedule_id not in removed] + revised,
                        key=lambda row: (row.start_datetime, row.end_datetime, row.schedule_id)))
