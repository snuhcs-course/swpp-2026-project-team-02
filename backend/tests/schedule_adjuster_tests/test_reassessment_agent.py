import copy
from datetime import datetime, timedelta
import json
import unittest
from unittest.mock import patch

from openrouter_client import InferenceError
from schedule_adjuster.models import ScheduleEntry
from schedule_adjuster.reassessment import ReassessmentPreviewExpired
from schedule_adjuster.reassessment_agent import ReassessmentAgent, build_context, validate_proposal, wire
from schedule_adjuster import reassessment_preview


class AgentReassessmentTests(unittest.TestCase):
    def setUp(self):
        self.now = datetime(2026, 12, 2, 12)
        self.rows = (ScheduleEntry("study-1", self.now-timedelta(days=1), self.now-timedelta(days=1)+timedelta(minutes=60), "A"),
                     ScheduleEntry("external-1", self.now+timedelta(hours=1), self.now+timedelta(hours=2), "event"),
                     ScheduleEntry("study-2", self.now+timedelta(days=1), self.now+timedelta(days=1, minutes=60), "B"))
        self.context = build_context(self.rows, set(), [("A", 1, False), ("B", 1, False)],
                                     replan_from=self.now, now=self.now, first_new_study_id="study-3",
                                     reports={"study-1": {"status": "done", "actual_minutes": 45}}, passing_score=60)
        self.proposal = {"rationale": "A는 완료했으므로 남은 목표가 있는 B에 더 배정했습니다.",
                         "weak_topic_allocations": [{"topic": "A", "minutes": 15, "review_minutes": 15, "reason": "학습 기록과 새 저점수로 복습 필요"}, {"topic": "B", "minutes": 45, "review_minutes": 15, "reason": "원본 잔여 학습 보완"}],
                         "allocations": [{"schedule_id": "study-2", "pieces": [{"topic": "A", "minutes": 15}, {"topic": "B", "minutes": 45}]}]}

    def reply(self, proposal, number=1):
        return {"status": "completed", "output": [{"type": "function_call", "call_id": f"call-{number}",
                "name": "propose_reassessment", "arguments": json.dumps(proposal)}]}

    def run_agent(self, replies):
        with patch("schedule_adjuster.reassessment_agent.load_api_key", return_value="synthetic"), \
             patch("schedule_adjuster.reassessment_agent.load_model", return_value="test-model"), \
             patch("schedule_adjuster.reassessment_agent.create_response", side_effect=replies) as transport:
            result = ReassessmentAgent().run(self.context)
            return result, transport

    def test_actual_progress_and_unmet_work_are_sent_to_the_model(self):
        (rows, rationale, trace), transport = self.run_agent([self.reply(self.proposal)])
        sent = json.loads(transport.call_args.kwargs["input_items"][0]["content"])
        by_topic = {t["topic"]: t for t in sent["topics"]}
        self.assertEqual((by_topic["A"]["planned_minutes"], by_topic["A"]["actual_study_minutes"],
                          by_topic["A"]["completed_planned_minutes"], by_topic["A"]["unmet_planned_minutes"]), (60, 45, 60, 0))
        self.assertEqual(by_topic["B"]["unmet_planned_minutes"], 60)
        self.assertEqual([(r.topic, int((r.end_datetime-r.start_datetime).total_seconds()/60)) for r in rows if r.schedule_id.startswith("study-") and r.start_datetime >= self.now], [("A", 15), ("B", 45)])
        self.assertEqual(rationale, self.proposal["rationale"])
        self.assertEqual(len(trace), 1)
        self.assertEqual(transport.call_count, 1)
        self.assertIn(self.rows[0], rows)
        self.assertIn(self.rows[1], rows)

    def test_unreported_past_minutes_are_not_inferred_as_completed(self):
        context = build_context(self.rows, set(), [("A", 1, False), ("B", 1, False)],
                                replan_from=self.now, now=self.now, first_new_study_id="study-3", reports={}, passing_score=60)
        topic = next(t for t in context["topics"] if t["topic"] == "A")
        self.assertEqual((topic["actual_study_minutes"], topic["completed_planned_minutes"], topic["unmet_planned_minutes"]), (None, 0, 60))

    def test_short_pieces_are_rejected_and_model_can_correct(self):
        bad = copy.deepcopy(self.proposal)
        bad["allocations"][0]["pieces"] = [{"topic": "A", "minutes": 5}, {"topic": "B", "minutes": 55}]
        (_, _, _), transport = self.run_agent([self.reply(bad), self.reply(self.proposal, 2)])
        self.assertEqual(transport.call_count, 2)

    def test_invalid_proposals_cannot_change_minutes_ids_or_topics(self):
        for kind in ("missing", "duplicate", "protected", "minutes", "unknown", "bool", "short"):
            proposal = copy.deepcopy(self.proposal)
            if kind == "missing": proposal["allocations"] = []
            elif kind == "duplicate": proposal["allocations"] *= 2
            elif kind == "protected": proposal["allocations"][0]["schedule_id"] = "study-1"
            elif kind == "minutes": proposal["allocations"][0]["pieces"][0]["minutes"] = 16
            elif kind == "unknown": proposal["allocations"][0]["pieces"][0]["topic"] = "invented"
            elif kind == "bool": proposal["allocations"][0]["pieces"][0]["minutes"] = True
            else: proposal["allocations"][0]["pieces"] = [{"topic": "A", "minutes": 1}, {"topic": "B", "minutes": 59}]
            with self.subTest(kind=kind), self.assertRaises(ValueError):
                validate_proposal(self.context, proposal)

    def test_provider_failure_and_exhaustion_have_no_deterministic_fallback(self):
        with self.assertRaises(InferenceError):
            self.run_agent([InferenceError("unavailable")])
        bad = dict(self.proposal, allocations=[])
        with self.assertRaisesRegex(RuntimeError, "exhausted"):
            self.run_agent([self.reply(bad, i) for i in range(4)])

    def test_a_completed_but_newly_weak_topic_cannot_be_silently_omitted(self):
        proposal = copy.deepcopy(self.proposal)
        proposal["allocations"][0]["pieces"] = [{"topic": "B", "minutes": 60}]
        with self.assertRaisesRegex(ValueError, "missing weak topics"):
            validate_proposal(self.context, proposal)

    def test_signed_preview_binds_rows_progress_and_expiry(self):
        rows = [wire(r) for r in validate_proposal(self.context, self.proposal)]
        token = reassessment_preview.issue(self.context, rows)
        reassessment_preview.verify(token, self.context, rows)
        for context, candidate, signature in ((dict(self.context, study_reports={}), rows, token),
                                               (self.context, rows[:-1], token), (self.context, rows, token+"x")):
            with self.assertRaises(ReassessmentPreviewExpired):
                reassessment_preview.verify(signature, context, candidate)
        with patch("schedule_adjuster.reassessment_preview.time.time", return_value=999999999999), self.assertRaises(ReassessmentPreviewExpired):
            reassessment_preview.verify(token, self.context, rows)

    def review_context(self, remaining=50, actual=None, score=100, passing_score=60):
        rows = []
        if remaining < 100:
            rows.append(ScheduleEntry("study-1", self.now-timedelta(days=2), self.now-timedelta(days=2)+timedelta(minutes=100-remaining), "A"))
        if remaining:
            rows.append(ScheduleEntry("study-2", self.now+timedelta(days=1), self.now+timedelta(days=1, minutes=remaining), "A"))
        rows.extend((ScheduleEntry("study-3", self.now-timedelta(days=1), self.now-timedelta(days=1)+timedelta(minutes=60), "C"),
                     ScheduleEntry("study-4", self.now+timedelta(days=2), self.now+timedelta(days=2, minutes=120), "C")))
        reports = {"study-3": {"status": "done", "actual_minutes": 30}}
        if actual is not None:
            reports["study-1"] = {"status": "partly" if actual else "missed", "actual_minutes": actual}
        results = [("A", score, True)] if score else []
        if score < 100:
            results.append(("A", 100-score, False))
        return build_context(tuple(rows), set(), results + [("C", 1, True)], replan_from=self.now,
                             now=self.now, first_new_study_id="study-5", reports=reports, passing_score=passing_score)

    def review_proposal(self, context, amount):
        intervals = context["revisable_intervals"]
        return {"rationale": "토픽별 문제 풀이와 오답 확인 분량으로 복습 시간을 선택했습니다.",
                "review_sessions": [{"topic": "A", "minutes": amount, "reason": "개념 확인과 대표 문제 복습에 필요한 분량"}],
                "allocations": [{"schedule_id": r["schedule_id"], "pieces":
                    ([{"topic": "A", "minutes": amount}, {"topic": "C", "minutes": r["minutes"]-amount}]
                     if r["schedule_id"] == "study-4" else [{"topic": "C", "minutes": r["minutes"]}])} for r in intervals]}

    def test_95_percent_boundary_uses_original_total_not_unmet_work(self):
        for remaining, expected in ((94, False), (95, True), (96, True)):
            topic = self.review_context(remaining)["topics"][0]
            self.assertEqual("at_least_95_percent_remaining" in topic["review_only_reasons"], expected)

    def test_actual_10_minute_boundary_requires_a_report(self):
        for actual, expected in ((None, False), (0, True), (10, True), (11, False)):
            topic = self.review_context(actual=actual)["topics"][0]
            self.assertEqual("reported_actual_at_most_10_minutes" in topic["review_only_reasons"], expected)
            self.assertEqual(topic["has_study_reports"], actual is not None)

    def test_agent_can_choose_different_review_durations_not_a_fixed_15_minutes(self):
        self.context = self.review_context(actual=10)
        for amount in (20, 35):
            (rows, _, trace), _ = self.run_agent([self.reply(self.review_proposal(self.context, amount))])
            self.assertEqual(trace[0]["arguments"]["review_sessions"][0]["minutes"], amount)
            self.assertEqual(sum(int((r.end_datetime-r.start_datetime).total_seconds()/60) for r in rows if r.topic == "A" and r.start_datetime > self.now), amount)

    def test_review_duration_must_match_one_continuous_reduced_session(self):
        context = self.review_context(actual=10)
        for kind in ("missing", "duration", "reason", "duplicate", "not_reduced", "disconnected"):
            proposal = self.review_proposal(context, 30)
            if kind == "missing": proposal["review_sessions"] = []
            elif kind == "duration": proposal["review_sessions"][0]["minutes"] = 20
            elif kind == "reason": proposal["review_sessions"][0]["reason"] = ""
            elif kind == "duplicate": proposal["review_sessions"] *= 2
            elif kind == "not_reduced": proposal = self.review_proposal(context, 50)
            else:
                proposal["allocations"][1]["pieces"] = [{"topic": "A", "minutes": 15}, {"topic": "C", "minutes": 90}, {"topic": "A", "minutes": 15}]
            with self.subTest(kind=kind), self.assertRaises(ValueError):
                validate_proposal(context, proposal)

    def test_review_reductions_do_not_silently_remove_time(self):
        row = ScheduleEntry("study-1", self.now+timedelta(days=1), self.now+timedelta(days=1, minutes=60), "A")
        with self.assertRaisesRegex(ValueError, "no eligible topic"):
            build_context((row,), set(), [("A", 1, True)], replan_from=self.now, now=self.now,
                          first_new_study_id="study-2", reports={}, passing_score=60)

    def test_explicit_low_study_with_zero_remainder_gets_one_agent_chosen_review(self):
        context = self.review_context(remaining=0, actual=10)
        proposal = self.review_proposal(context, 35)
        self.assertTrue(validate_proposal(context, proposal))
        unknown = self.review_context(remaining=0)
        self.assertEqual(unknown["topics"][0]["review_only_reasons"], [])

    def test_review_under_15_minutes_is_clamped_to_15_even_for_a_short_remainder(self):
        context = self.review_context(remaining=10, actual=10)
        proposal = self.review_proposal(context, 15)
        proposal["review_sessions"][0]["minutes"] = 10
        self.assertTrue(validate_proposal(context, proposal))

    def test_only_high_scores_trigger_either_review_rule_including_boundaries(self):
        for passing, score, expected in ((60, 69, False), (60, 70, True), (80, 89, False), (80, 90, True), (95, 99, False), (95, 100, True)):
            for remaining, actual in ((95, None), (50, 10)):
                context = self.review_context(remaining, actual, score, passing)
                self.assertEqual(bool(context["topics"][0]["review_only_reasons"]), expected, (passing, score, remaining, actual))
                self.assertEqual(context["high_score_threshold_percent"], min(passing+10, 100))

    def test_missing_or_invalid_passing_score_never_silently_defaults(self):
        for value in (None, True, -1, 101, float("nan")):
            with self.subTest(value=value), self.assertRaisesRegex(ValueError, "passing_score"):
                self.review_context(passing_score=value)

    def test_high_score_without_either_condition_can_reduce_to_zero_below_100(self):
        for passing, score in ((60, 70), (60, 80), (60, 99), (60, 100), (80, 90)):
            for actual in (None, 11):
                context = self.review_context(remaining=50, actual=actual, score=score, passing_score=passing)
                self.assertNotIn("A", context["required_topic_names"])
                proposal = {"rationale": "고득점이며 두 복습 조건에 해당하지 않아 미래 배정을 모두 다른 학습에 사용", "allocations": [
                    {"schedule_id": r["schedule_id"], "pieces": [{"topic": "C", "minutes": r["minutes"]}]} for r in context["revisable_intervals"]]}
                rows = validate_proposal(context, proposal)
                self.assertFalse(any(r.topic == "A" and r.start_datetime > self.now for r in rows))
                self.assertEqual(sum(int((r.end_datetime-r.start_datetime).total_seconds()/60) for r in rows if r.start_datetime > self.now), 170)

    def test_rule_three_has_no_partial_reduction_floor_and_keeps_model_choice(self):
        self.context = self.review_context(remaining=50, actual=11, score=80)
        for amount in (15, 20, 35, 50):
            proposal = self.review_proposal(self.context, amount)
            del proposal["review_sessions"]
            (rows, _, _), _ = self.run_agent([self.reply(proposal)])
            self.assertEqual(sum(int((r.end_datetime-r.start_datetime).total_seconds()/60) for r in rows if r.topic == "A" and r.start_datetime > self.now), amount)

    def test_rule_three_cannot_omit_low_scores_or_bypass_either_review_condition(self):
        for score, remaining, actual in ((69, 50, 11), (80, 95, 11), (80, 50, 10)):
            context = self.review_context(remaining=remaining, actual=actual, score=score)
            proposal = {"rationale": "모두 감축하는 잘못된 제안", "allocations": [
                {"schedule_id": r["schedule_id"], "pieces": [{"topic": "C", "minutes": r["minutes"]}]} for r in context["revisable_intervals"]]}
            with self.subTest(score=score, remaining=remaining, actual=actual), self.assertRaises(ValueError):
                validate_proposal(context, proposal)

    def test_zero_allowed_topics_do_not_consume_preflight_capacity(self):
        rows = (ScheduleEntry("study-1", self.now-timedelta(days=1), self.now-timedelta(days=1)+timedelta(minutes=95), "A"),
                ScheduleEntry("study-2", self.now+timedelta(days=1), self.now+timedelta(days=1, minutes=5), "A"))
        context = build_context(rows, set(), [(t, 1, True) for t in ("A", "B", "C", "D", "E", "F", "G", "H")],
                                replan_from=self.now, now=self.now, first_new_study_id="study-3", passing_score=60,
                                reports={"study-1": {"status": "partly", "actual_minutes": 11}})
        self.assertEqual(context["required_topic_names"], [])
        proposal = {"rationale": "다른 고득점 토픽에는 0분을 허용하고 기존 짧은 구간만 유지", "allocations": [
            {"schedule_id": "study-2", "pieces": [{"topic": "A", "minutes": 5}]}]}
        self.assertEqual(validate_proposal(context, proposal), rows)


if __name__ == "__main__":
    unittest.main()
