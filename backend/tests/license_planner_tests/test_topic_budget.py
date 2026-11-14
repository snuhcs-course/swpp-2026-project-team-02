"""P10 allocation invariants, adversarial proposals and real placement checks."""
import copy
from collections import defaultdict
from datetime import datetime
import random
import unittest

from license_planner.models import PlanRequest, ProblemResult, TopicStatistics
from license_planner.tools import PlannerTools
from license_planner.topic_budget import calculate_topic_budget, rebalance_topic_minutes, validate_topic_budget
from license_planner.user_availability import parse_weekly_availability


def statistics(*scores):
    return tuple(TopicStatistics(f"topic-{i}", 100, score, score, 1) for i, score in enumerate(scores))


class TopicBudgetTests(unittest.TestCase):
    def test_linear_weights_have_explicit_reproducible_example(self):
        self.assertEqual(calculate_topic_budget(statistics(0, 50, 100), 183),
                         {"topic-0": 81, "topic-1": 61, "topic-2": 41})

    def test_equal_scores_stay_balanced_and_input_order_does_not_break_ties(self):
        for score in (0, 50, 100):
            stats = statistics(*([score] * 8))
            budget = calculate_topic_budget(stats, 101)
            self.assertEqual(sum(budget.values()), 101)
            self.assertLessEqual(max(budget.values()) - min(budget.values()), 1)
            self.assertEqual(calculate_topic_budget(tuple(reversed(stats)), 101), budget)

    def test_minimum_capacity_is_allowed_without_claiming_strict_order(self):
        self.assertEqual(list(calculate_topic_budget(statistics(0, 50, 100), 3).values()), [1, 1, 1])
        for total in (0, 2, True, 3.5):
            with self.subTest(total=total), self.assertRaises(ValueError):
                calculate_topic_budget(statistics(0, 50, 100), total)

    def test_all_scores_and_budgets_preserve_order_coverage_and_total(self):
        rng = random.Random(10)
        for _ in range(150):
            scores = [rng.choice([0, 25, 50, 75, 100]) for _ in range(8)]
            total = rng.randint(8, 5000)
            budget = calculate_topic_budget(statistics(*scores), total)
            self.assertEqual(sum(budget.values()), total)
            self.assertTrue(all(value >= 1 for value in budget.values()))
            for i, a in enumerate(scores):
                for j, b in enumerate(scores):
                    if a < b:
                        self.assertGreaterEqual(budget[f"topic-{i}"], budget[f"topic-{j}"])

    def test_invalid_scores_and_duplicate_statistics_are_rejected(self):
        for scores in ((float("nan"),), (float("inf"),), (-1,), (101,)):
            with self.subTest(scores=scores), self.assertRaises(ValueError):
                calculate_topic_budget(statistics(*scores), 100)
        with self.assertRaises(ValueError):
            calculate_topic_budget((statistics(0)[0], statistics(100)[0]), 100)

    def test_validator_detects_order_inversion_and_lost_minutes(self):
        stats = statistics(0, 50, 100)
        for invalid in ({"topic-0": 10, "topic-1": 20, "topic-2": 30},
                        {"topic-0": 30, "topic-1": 20, "topic-2": 9},
                        {"topic-0": 60}):
            with self.subTest(invalid=invalid), self.assertRaises(ValueError):
                validate_topic_budget(stats, invalid, 60)

    def test_rebalancing_preserves_each_day_and_exact_global_budgets(self):
        proposal = [{"date": f"2026/11/{9 + i:02d}", "topic": "topic-2", "minutes": amount}
                    for i, amount in enumerate((1, 7, 3, 101, 71))]
        before = copy.deepcopy(proposal)
        rows, budget = rebalance_topic_minutes(statistics(0, 50, 100), proposal)
        daily, topics = defaultdict(int), defaultdict(int)
        for row in rows:
            daily[row["date"]] += row["minutes"]
            topics[row["topic"]] += row["minutes"]
        self.assertEqual(daily, {row["date"]: row["minutes"] for row in proposal})
        self.assertEqual(topics, budget)
        self.assertEqual(proposal, before)
        self.assertEqual(rebalance_topic_minutes(statistics(0, 50, 100), proposal), (rows, budget))


class TopicBudgetPlacementTests(unittest.TestCase):
    def make_tools(self, availability=None):
        request = PlanRequest(
            self_assessment=None,
            problem_results=(ProblemResult(1, "weak", 1, 0), ProblemResult(2, "middle", 2, 1), ProblemResult(3, "strong", 1, 1)),
            preparation_start=datetime(2026, 11, 9), exam_date=datetime(2026, 11, 11),
            weekly_availability=parse_weekly_availability(availability or {
                "monday": [{"start": "19:00", "end": "21:00"}],
                "tuesday": [{"start": "19:00", "end": "21:00"}]}))
        tools = PlannerTools(request)
        tools.analyze_exam_results()
        return tools

    def proposal(self):
        return [{"date": date, "topic": topic, "minutes": minutes}
                for date in ("2026/11/09", "2026/11/10")
                for topic, minutes in (("weak", 1), ("middle", 1), ("strong", 88))]

    def test_adversarial_model_allocation_is_corrected_before_return(self):
        tools = self.make_tools()
        result = tools.build_study_schedule(self.proposal())
        self.assertIsInstance(result, dict)
        self.assertEqual(result["topic_budget"], {"weak": 80, "middle": 60, "strong": 40})
        self.assertEqual(result["total_planned_minutes"], 180)
        totals, days = defaultdict(int), defaultdict(int)
        for block in tools.schedule:
            minutes = int((block.end_datetime - block.start_datetime).total_seconds() / 60)
            totals[block.topic] += minutes
            days[block.start_datetime.strftime("%Y/%m/%d")] += minutes
        self.assertEqual(totals, result["topic_budget"])
        self.assertEqual(days, {"2026/11/09": 90, "2026/11/10": 90})

    def test_rebalancing_does_not_hide_invalid_capacity_dates_or_missing_topics(self):
        for change in ("capacity", "date", "missing"):
            tools = self.make_tools()
            proposal = self.proposal()
            if change == "capacity":
                proposal[2]["minutes"] = 500
            elif change == "date":
                proposal[0]["date"] = "2026/11/08"
            else:
                proposal = [row for row in proposal if row["topic"] != "weak"]
            with self.subTest(change=change):
                self.assertIsInstance(tools.build_study_schedule(proposal), str)
                self.assertIsNone(tools.schedule)

    def test_overnight_and_empty_week_override_are_respected(self):
        tools = self.make_tools({"weekly_availability": {"monday": [{"start": "23:00", "duration_minutes": 120}]},
                                 "availability_overrides": {"2026/11/16": {}}})
        proposal = [{"date": day, "topic": topic, "minutes": 20}
                    for day in ("2026/11/09", "2026/11/10") for topic in ("weak", "middle", "strong")]
        result = tools.build_study_schedule(proposal)
        self.assertIsInstance(result, dict)
        self.assertEqual(result["total_planned_minutes"], 120)
        self.assertTrue(all(datetime(2026, 11, 9, 23) <= row.start_datetime < row.end_datetime <= datetime(2026, 11, 10, 1)
                            for row in tools.schedule))


if __name__ == "__main__":
    unittest.main()
