import copy
from datetime import datetime, timedelta
import json
import unittest
from unittest.mock import patch

from schedule_adjuster.models import ScheduleEntry
from schedule_adjuster.reassessment_agent import build_context, materialize_budgets, validate_proposal, ReassessmentAgent
from schedule_adjuster.reassessment_placement import PlacementSearchLimit


class WeakAdditionalReviewTests(unittest.TestCase):
    def context(self, zero_original=False):
        now = datetime(2026, 12, 2, 12)
        rows, reports = [], {}
        for i, topic in enumerate(("A", "B", "C")):
            start = now + timedelta(days=i+1) if not zero_original else now-timedelta(days=i+1)
            rows.append(ScheduleEntry(f"study-{i+1}", start, start+timedelta(minutes=120 if not zero_original else 60), topic))
            if zero_original:
                reports[f"study-{i+1}"] = {"status": "partly", "actual_minutes": 20}
        for i in range(2 if zero_original else 1):
            start = now+timedelta(days=4+i)
            rows.append(ScheduleEntry(f"study-{4+i}", start, start+timedelta(minutes=120), "H"))
        return build_context(tuple(rows), set(), [(t, 1, t == "H") for t in ("A", "B", "C", "H")],
                             replan_from=now, now=now, first_new_study_id="study-9", reports=reports, passing_score=60)

    def proposal(self, amounts=(150, 150, 150, 30), reviews=(30, 30, 30)):
        return {"topic_minutes": dict(zip(("A", "B", "C", "H"), amounts)), "rationale": "진단·기록·원본 잔여량을 고려해 추가 학습을 분산",
                "review_sessions": [{"topic": "H", "minutes": amounts[3], "reason": "고득점이며 원본 대부분이 남아 1회 복습"}],
                "weak_topic_allocations": [{"topic": t, "minutes": n, "review_minutes": r, "reason": "저득점과 원본 잔여 분량 및 학습 보고를 고려한 교정 학습"}
                                           for t, n, r in zip(("A", "B", "C"), amounts, reviews)]}

    def checked(self, context, proposal):
        allocation = materialize_budgets(context, proposal)
        info = {}
        rows = validate_proposal(context, allocation, validation_info=info)
        return rows, info["weak_additions"]

    def test_every_weak_topic_gets_at_least_its_chosen_full_review(self):
        _, decision = self.checked(self.context(), self.proposal())
        self.assertEqual(decision["minimums"], {"A": 30, "B": 30, "C": 30})
        self.assertFalse(decision["fallback"])

    def test_concentration_or_undersized_gain_is_rejected_when_full_reviews_fit(self):
        for amounts in ((210, 120, 120, 30), (135, 165, 150, 30)):
            with self.subTest(amounts=amounts), self.assertRaisesRegex(ValueError, "spread weak-topic additions"):
                self.checked(self.context(), self.proposal(amounts))

    def test_global_time_shortage_proves_15_minute_fallback(self):
        _, decision = self.checked(self.context(), self.proposal(reviews=(45, 45, 45)))
        self.assertTrue(decision["fallback"])
        self.assertEqual(set(decision["minimums"].values()), {15})

    def test_review_longer_than_any_continuous_window_proves_fallback(self):
        _, decision = self.checked(self.context(zero_original=True), self.proposal((90, 60, 60, 30), (150, 15, 15)))
        self.assertTrue(decision["fallback"])

    def test_even_15_minutes_cannot_be_invented(self):
        with self.assertRaisesRegex(ValueError, "NOT_ENOUGH_TIME"):
            self.checked(self.context(), self.proposal((130, 125, 125, 100)))

    def test_search_limit_does_not_authorize_fallback(self):
        with patch("schedule_adjuster.reassessment_agent.place", side_effect=PlacementSearchLimit("unknown")) as search:
            with self.assertRaises(PlacementSearchLimit):
                self.checked(self.context(), self.proposal())
            self.assertEqual(search.call_count, 1)

    def test_bad_interval_arithmetic_is_a_model_error_not_a_fallback(self):
        context = self.context()
        allocation = materialize_budgets(context, self.proposal())
        bad = copy.deepcopy(allocation)
        bad["allocations"][0]["pieces"][0]["minutes"] += 1
        with patch("schedule_adjuster.reassessment_agent.effective_weak_minimums") as physical:
            with self.assertRaisesRegex(ValueError, "preserve every interval"):
                validate_proposal(context, bad)
            physical.assert_not_called()

    def test_budget_tool_keeps_the_model_amounts_and_records_fallback_decision(self):
        proposal = self.proposal()
        reply = {"status": "completed", "output": [{"type": "function_call", "call_id": "budgets-1", "name": "propose_topic_budgets", "arguments": json.dumps(proposal)}]}
        with patch("schedule_adjuster.reassessment_agent.load_api_key", return_value="synthetic"), \
             patch("schedule_adjuster.reassessment_agent.load_model", return_value="test-model"), \
             patch("schedule_adjuster.reassessment_agent.create_response", return_value=reply):
            rows, _, trace = ReassessmentAgent().run(self.context())
        self.assertEqual(trace[0]["arguments"], proposal)
        self.assertFalse(trace[0]["validation"]["weak_additions"]["fallback"])
        self.assertEqual(sum(int((r.end_datetime-r.start_datetime).total_seconds()/60) for r in rows), 480)
