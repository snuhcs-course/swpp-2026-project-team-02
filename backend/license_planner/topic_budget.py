"""P10: deterministic score-based budgets inside the model's chosen workload.

No dates, availability or readiness levels are invented here. The model still
chooses total study minutes and daily workloads; the backend redistributes
their topic labels using reviewed/validated diagnostic statistics.
"""
from __future__ import annotations

from collections import defaultdict
from bisect import bisect_right
from fractions import Fraction
from functools import lru_cache
from itertools import accumulate
import math

from .models import TopicStatistics

POLICY_VERSION = "diagnostic-linear-v1"
PLACEMENT_POLICY_VERSION = "contiguous-topic-blocks-v2"
SHORT_BLOCK_TARGET = 15  # Soft cost only, never a minimum allowed duration.
EXACT_ORDER_LIMIT = 10  # Bound exponential work for arbitrary internal catalogs.
REPAIR_STEP_LIMIT = 32


def _smooth_boundaries(rows, topics):
    """Bounded 2x2 minute exchanges; preserve both daily totals and topic budgets.

    Only accept a strict improvement in (short chunks, chunk count). No minimum
    length is imposed: unavoidable small budgets/windows remain valid.
    """
    days = {}
    for row in rows:
        days.setdefault(row["date"], {})[row["topic"]] = row["minutes"]
    dates = list(days)
    rank = {topic: i for i, topic in enumerate(topics)}

    def cost(values):
        return sum(0 < x < SHORT_BLOCK_TARGET for x in values), sum(x > 0 for x in values)

    for _ in range(REPAIR_STEP_LIMIT):
        short, blocks = cost([m for day in days.values() for m in day.values()])
        best = None
        for ai, a in enumerate(dates):
            for topic, small in list(days[a].items()):
                if not 0 < small < SHORT_BLOCK_TARGET:
                    continue
                for other, donor_a in list(days[a].items()):
                    if other == topic or donor_a <= 0:
                        continue
                    for bi, b in enumerate(dates):
                        donor_b = days[b].get(topic, 0)
                        if a == b or donor_b <= 0:
                            continue
                        receiver = days[b].get(other, 0)
                        before = cost((small, donor_a, donor_b, receiver))
                        deltas = {1, SHORT_BLOCK_TARGET, SHORT_BLOCK_TARGET-small,
                                  SHORT_BLOCK_TARGET-receiver, donor_a, donor_b,
                                  donor_a-SHORT_BLOCK_TARGET, donor_b-SHORT_BLOCK_TARGET}
                        for delta in sorted(deltas):
                            if not 0 < delta <= min(donor_a, donor_b):
                                continue
                            after = cost((small+delta, donor_a-delta, donor_b-delta, receiver+delta))
                            candidate = (short+after[0]-before[0], blocks+after[1]-before[1],
                                         delta, ai, rank[topic], bi, rank[other])
                            if candidate[:2] < (short, blocks) and (best is None or candidate < best):
                                best = candidate
        if best is None:
            break
        _, _, delta, ai, ti, bi, ui = best
        a, b, topic, other = dates[ai], dates[bi], topics[ti], topics[ui]
        days[a][topic] += delta
        days[a][other] -= delta
        days[b][topic] -= delta
        days[b][other] = days[b].get(other, 0) + delta
    return [{"date": day, "topic": topic, "minutes": minutes}
            for day, values in days.items() for topic, minutes in values.items() if minutes]


def _scores(statistics: tuple[TopicStatistics, ...]) -> dict[str, Fraction]:
    scores: dict[str, Fraction] = {}
    for item in statistics:
        if item.topic in scores:
            raise ValueError("duplicate topic statistics")
        if not math.isfinite(item.score_percent) or not 0 <= item.score_percent <= 100:
            raise ValueError("topic score must be finite and between 0 and 100")
        scores[item.topic] = Fraction(str(item.score_percent))
    if not scores:
        raise ValueError("diagnostic topic statistics are required")
    return scores


def _apportion(total: int, weights: dict[str, Fraction], order: dict[str, int]) -> dict[str, int]:
    """Largest remainder, with an explicit stable tie order and exact sums."""
    denominator = sum(weights.values())
    if denominator <= 0:
        if total == 0:
            return {topic: 0 for topic in weights}
        raise ValueError("positive allocation weights are required")
    quotas = {topic: total * weight / denominator for topic, weight in weights.items()}
    minutes = {topic: int(quota) for topic, quota in quotas.items()}
    left = total - sum(minutes.values())
    priority = sorted(weights, key=lambda topic: (-(quotas[topic] - minutes[topic]), order[topic]))
    for topic in priority[:left]:
        minutes[topic] += 1
    return minutes


def calculate_topic_budget(statistics: tuple[TopicStatistics, ...], total_minutes: int) -> dict[str, int]:
    """Reserve one minute per topic; split the rest by 2 - score/100.

    A 0% topic has weight 2, a 100% topic weight 1. Equal-score topics differ
    by at most one minute after integer rounding. Lower scores never receive
    fewer minutes; small budgets can produce unavoidable integer ties.
    """
    scores = _scores(statistics)
    if isinstance(total_minutes, bool) or not isinstance(total_minutes, int) or total_minutes < len(scores):
        raise ValueError("NOT_ENOUGH_TIME: total minutes must cover every assessed topic")
    topics = sorted(scores, key=lambda topic: (scores[topic], topic.casefold(), topic))
    order = {topic: index for index, topic in enumerate(topics)}
    weights = {topic: Fraction(2) - scores[topic] / 100 for topic in topics}
    allocated = _apportion(total_minutes - len(topics), weights, order)
    budget = {topic: allocated[topic] + 1 for topic in topics}
    validate_topic_budget(statistics, budget, total_minutes)
    return budget


def validate_topic_budget(statistics: tuple[TopicStatistics, ...], budget: dict[str, int], total_minutes: int) -> None:
    """Check conservation, coverage, positivity and complete weak-topic order."""
    scores = _scores(statistics)
    if set(budget) != set(scores) or any(isinstance(m, bool) or not isinstance(m, int) or m < 1 for m in budget.values()):
        raise ValueError("topic budget must cover every assessed topic with positive integer minutes")
    if sum(budget.values()) != total_minutes:
        raise ValueError("topic budget must preserve total study minutes")
    for weaker, score in scores.items():
        if any(score < other and budget[weaker] < budget[stronger] for stronger, other in scores.items()):
            raise ValueError("topic budget violates weak-topic order")


def rebalance_topic_minutes(statistics: tuple[TopicStatistics, ...], daily_plan: list[dict]) -> tuple[list[dict], dict[str, int]]:
    """Preserve every proposed date's total while meeting exact global budgets.

    The caller validates the original plan before rebalancing, and runs the
    result through normal availability/busy-calendar placement afterwards.
    Pack each topic's budget contiguously across the selected study dates.
    A bounded subset dynamic program chooses the topic order with the fewest
    short chunks, then fewest chunks, then weakest-first deterministic ties.
    This optimizes the contiguous-topic family, then bounded pairwise exchanges
    reduce short tails while preserving both daily and topic totals. Neither
    stage claims a globally optimal solution over all possible schedules.
    Physical busy-window cuts are still handled by the normal scheduler.
    """
    daily_totals: dict[str, int] = defaultdict(int)
    for item in daily_plan:
        daily_totals[item["date"]] += item["minutes"]
    total = sum(daily_totals.values())
    budget = calculate_topic_budget(statistics, total)
    topics = list(budget)
    amounts = list(budget.values())
    days = sorted(daily_totals)
    ends = list(accumulate(daily_totals[day] for day in days))

    def chunk_cost(start, amount):
        end = start + amount
        index = bisect_right(ends, start)
        short = blocks = 0
        while start < end:
            right = min(end, ends[index])
            short += right - start < SHORT_BLOCK_TARGET
            blocks += 1
            start = right
            index += 1
        return short, blocks

    if len(topics) <= EXACT_ORDER_LIMIT:
        positions = [0] * (1 << len(topics))
        for mask in range(1, len(positions)):
            bit = mask & -mask
            positions[mask] = positions[mask ^ bit] + amounts[bit.bit_length() - 1]

        @lru_cache(None)
        def best(mask):
            if mask == len(positions) - 1:
                return 0, 0, ()
            choices = []
            for i, amount in enumerate(amounts):
                if not mask & (1 << i):
                    short, blocks = chunk_cost(positions[mask], amount)
                    later_short, later_blocks, later_order = best(mask | (1 << i))
                    choices.append((short + later_short, blocks + later_blocks, (i,) + later_order))
            return min(choices)

        order = best(0)[2]
    else:
        order = tuple(range(len(topics)))
    normalized = []
    cursor = day_index = 0
    for i in order:
        left = amounts[i]
        while left:
            minutes = min(left, ends[day_index] - cursor)
            normalized.append({"date": days[day_index], "topic": topics[i], "minutes": minutes})
            left -= minutes
            cursor += minutes
            if cursor == ends[day_index]:
                day_index += 1
    return _smooth_boundaries(normalized, topics), budget
