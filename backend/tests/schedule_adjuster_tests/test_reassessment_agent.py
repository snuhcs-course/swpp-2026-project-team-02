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
                                     reports={"study-1": {"status": "done", "actual_minutes": 45}})
        self.proposal = {"rationale": "A는 완료했으므로 남은 목표가 있는 B에 더 배정했습니다.",
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
                                replan_from=self.now, now=self.now, first_new_study_id="study-3", reports={})
        topic = next(t for t in context["topics"] if t["topic"] == "A")
        self.assertEqual((topic["actual_study_minutes"], topic["completed_planned_minutes"], topic["unmet_planned_minutes"]), (0, 0, 60))

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


if __name__ == "__main__":
    unittest.main()
