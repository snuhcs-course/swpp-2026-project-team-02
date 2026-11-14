"""P10: deterministic score-based budgets inside the model's chosen workload.

No dates, availability or readiness levels are invented here. The model still
chooses total study minutes and daily workloads; the backend redistributes
their topic labels using reviewed/validated diagnostic statistics.
"""
from __future__ import annotations

from collections import defaultdict
from fractions import Fraction
import math

from .models import TopicStatistics

POLICY_VERSION = "diagnostic-linear-v1"


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
    Dates receive proportional shares of remaining budgets; integer leftovers
    follow the same deterministic order. No topic receives a negative remainder.
    """
    daily_totals: dict[str, int] = defaultdict(int)
    for item in daily_plan:
        daily_totals[item["date"]] += item["minutes"]
    total = sum(daily_totals.values())
    budget = calculate_topic_budget(statistics, total)
    remaining = dict(budget)
    order = {topic: index for index, topic in enumerate(budget)}
    normalized = []
    for day in sorted(daily_totals):
        weights = {topic: Fraction(minutes) for topic, minutes in remaining.items() if minutes > 0}
        allocated = _apportion(daily_totals[day], weights, order)
        for topic in sorted(allocated, key=order.get):
            minutes = allocated[topic]
            if minutes:
                normalized.append({"date": day, "topic": topic, "minutes": minutes})
                remaining[topic] -= minutes
    if any(remaining.values()):
        raise ValueError("daily allocation did not consume the topic budget")
    return normalized, budget
