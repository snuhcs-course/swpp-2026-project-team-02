"""Reassessment FE contract over real HTTP and isolated per-device storage."""
import copy
import csv
import io
import json
import tempfile
import threading
import unittest
from datetime import datetime
from http.server import ThreadingHTTPServer
from pathlib import Path
from unittest.mock import patch
from urllib.error import HTTPError
from urllib.request import Request, urlopen

import api_service
from api_server import DEFAULT_API_CONFIG, make_handler
from assessment_service import get_public_questions, get_reassessment_questions
from schedule_adjuster.csv_io import parse_schedule_rows, render_schedule_csv
from study_availability import parse_availability_context
from study_plan_store import PlanConflictError
from test_support.admission import roomy_admission
from test_support.device_tokens import bearer, owner_store
from test_support.question_banks import level_2_bank, temporary_question_bank

CERT = "computer_specialist_level_2"
QUESTIONS_PATH = f"/certifications/{CERT}/reassessment-questions"
ROWS = [
    {"schedule_id": "study-1", "start_date": "2026/12/01/19/00", "end_date": "2026/12/01/20/00", "topic": "매크로 활용"},
    {"schedule_id": "study-2", "start_date": "2026/12/02/11/50", "end_date": "2026/12/02/12/30", "topic": "매크로 활용"},
    {"schedule_id": "external-1", "start_date": "2026/12/02/18/00", "end_date": "2026/12/02/19/00", "topic": "일정"},
    {"schedule_id": "study-3", "start_date": "2026/12/02/19/00", "end_date": "2026/12/02/20/00", "topic": "매크로 활용"},
    {"schedule_id": "study-4", "start_date": "2026/12/03/19/00", "end_date": "2026/12/03/21/00", "topic": "매크로 활용"},
    {"schedule_id": "study-5", "start_date": "2026/12/04/19/00", "end_date": "2026/12/04/21/00", "topic": "매크로 활용"},
]
WEEKLY = {day: [{"start": "19:00", "duration_minutes": 120}]
          for day in ("monday", "tuesday", "wednesday", "thursday", "friday")}
NOW = datetime(2026, 12, 2, 12)


class ReassessmentApiTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.store = owner_store(self.root)
        self.context = parse_availability_context({"weekly_availability": WEEKLY})
        self.csv = render_schedule_csv(parse_schedule_rows(ROWS))
        self.plan = self.store.create(self.csv, datetime(2026, 12, 10),
                                      preparation_start=datetime(2026, 12, 1), availability=self.context)
        for name, value in (("STUDY_PLAN_OUTPUT_DIR", self.root), ("current_korea_minute", lambda: NOW),
                            ("PlanningAgent", self.forbidden), ("AdjustmentAgent", self.forbidden),
                            ("load_adjuster_availability", self.forbidden), ("load_personal_availability", self.forbidden)):
            patcher = patch.object(api_service, name, value)
            patcher.start()
            self.addCleanup(patcher.stop)
        handler = make_handler(DEFAULT_API_CONFIG, roomy_admission())
        handler.log_message = lambda *args: None
        self.server = ThreadingHTTPServer(("127.0.0.1", 0), handler)
        thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        thread.start()
        self.addCleanup(lambda: (self.server.shutdown(), self.server.server_close(), thread.join(timeout=3)))
        self.questions = get_reassessment_questions(CERT)["questions"]

    @staticmethod
    def forbidden(*args, **kwargs):
        raise AssertionError("reassessment must not call a provider or shared availability profile")

    def call(self, path, payload=None, headers=None):
        data = json.dumps(payload).encode() if payload is not None else None
        request = Request(f"http://127.0.0.1:{self.server.server_port}/api/v1{path}", data=data,
                          headers={"Content-Type": "application/json", **(bearer() if headers is None else headers)})
        try:
            response = urlopen(request, timeout=10)
        except HTTPError as error:
            response = error
        with response:
            return response.status, json.loads(response.read().decode())

    def payload(self, **changes):
        results = [{"problem_id": q["id"], "question": q["prompt"],
                    "choices": q["choices"] | {"UNKNOWN": "모르겠음"}, "correct_answer": q["correct_answer"],
                    "possible_score": q["possible_score"], "user_answer": "UNKNOWN", "is_correct": False}
                   for q in self.questions]
        return {"mode": "reassessment", "commit": False, "schedule_csv_file": self.plan,
                "schedules": copy.deepcopy(ROWS), "weekly_availability": copy.deepcopy(WEEKLY),
                "availability_overrides": {}, "preparation_start": "2026/12/01/00/00",
                "exam_date": "2026/12/10/00/00", "replan_from": "2026/12/02/12/00",
                "keep_schedule_ids": ["study-3"], "first_new_study_id": "study-6",
                "assessment_results": {"certification_id": CERT, "results": results}, **changes}

    def files(self):
        return {str(path.relative_to(self.root)): path.read_bytes() for path in self.root.rglob("*") if path.is_file()}

    def assert_invalid(self, payload, code="INVALID_INPUT", status=422):
        before = self.files()
        actual, body = self.call("/schedules/adjust", payload)
        self.assertEqual((actual, body.get("error", {}).get("code")), (status, code), body)
        self.assertEqual(self.files(), before)

    def test_questions_are_separate_complete_and_still_drafts(self):
        status, body = self.call(QUESTIONS_PATH, headers={})
        self.assertEqual(status, 200)
        self.assertEqual(body["certification_id"], CERT)
        self.assertEqual(len(body["questions"]), 18)
        onboarding = get_public_questions(CERT)["questions"]
        self.assertFalse({q["id"] for q in onboarding} & {q["id"] for q in body["questions"]})
        for q in body["questions"]:
            self.assertTrue(q["topic"] and q["explanation"])
            self.assertEqual(q["review_status"], "draft")
            self.assertIn(q["correct_answer"], q["choices"])

    def test_preview_is_read_only_and_commit_matches_preview(self):
        before = self.files()
        payload = self.payload()
        status, preview = self.call("/schedules/adjust", payload)
        self.assertEqual(status, 200, preview)
        self.assertEqual(preview["action"], "reassessment_preview")
        self.assertEqual(self.files(), before)
        self.assertEqual(preview["schedule_csv_file"], self.plan)
        self.assertEqual(parse_schedule_rows(preview["schedules"]),
                         parse_schedule_rows(list(csv.DictReader(io.StringIO(preview["schedule_csv"])))))
        for protected in ROWS[:4]:
            self.assertIn(protected, preview["schedules"])
        changed = [row for row in preview["schedules"] if row["schedule_id"] not in {r["schedule_id"] for r in ROWS}]
        self.assertTrue(changed)
        self.assertEqual(sum(int((row.end_datetime-row.start_datetime).total_seconds()/60)
                             for row in parse_schedule_rows(changed)), 240)
        status, committed = self.call("/schedules/adjust", payload | {"commit": True})
        self.assertEqual(status, 200, committed)
        self.assertEqual(committed["action"], "reassessed")
        self.assertEqual(committed["schedules"], preview["schedules"])
        self.assertEqual(committed["availability_snapshot"], preview["availability_snapshot"])
        self.assertEqual({r.schedule_id: r for r in self.store.snapshot(self.plan).entries},
                         {r.schedule_id: r for r in parse_schedule_rows(preview["schedules"])})
        after = self.files()
        self.assertEqual([key for key in before if before[key] != after[key]],
                         [next(key for key in before if key.endswith(".csv"))])
        self.assert_invalid(payload | {"commit": True}, "PLAN_CONFLICT", 409)

    def test_correct_answers_and_reversed_result_order(self):
        payload = self.payload()
        for item in payload["assessment_results"]["results"]:
            item["user_answer"], item["is_correct"] = item["correct_answer"], True
        status, first = self.call("/schedules/adjust", payload)
        self.assertEqual(status, 200, first)
        payload["assessment_results"]["results"].reverse()
        self.assertEqual(self.call("/schedules/adjust", payload)[1]["schedules"], first["schedules"])

    def test_started_preview_cannot_be_committed(self):
        self.assertEqual(self.call("/schedules/adjust", self.payload())[0], 200)
        with patch.object(api_service, "current_korea_minute", return_value=datetime(2026, 12, 3, 19, 1)):
            self.assert_invalid(self.payload(commit=True), "REASSESSMENT_PREVIEW_EXPIRED")

    def test_clock_is_rechecked_before_write(self):
        with patch.object(api_service, "current_korea_minute", side_effect=[NOW, datetime(2026, 12, 3, 19, 1)]):
            self.assert_invalid(self.payload(commit=True), "REASSESSMENT_PREVIEW_EXPIRED")

    def test_stale_client_rows(self):
        payload = self.payload()
        payload["schedules"][0]["topic"] = "changed"
        self.assert_invalid(payload, "PLAN_CONFLICT", 409)

    def test_optimistic_storage_conflict(self):
        with patch.object(type(self.store), "update", side_effect=PlanConflictError("Concurrent modification")):
            self.assert_invalid(self.payload(commit=True), "PLAN_CONFLICT", 409)

    def test_owner_isolation_and_authentication(self):
        status, body = self.call("/schedules/adjust", self.payload(commit=True), headers=bearer("b2" * 32))
        self.assertEqual((status, body["error"]["code"]), (404, "PLAN_NOT_FOUND"))
        self.assertEqual(self.call("/schedules/adjust", self.payload(), headers={})[0], 401)

    def test_metadata_cannot_be_replaced(self):
        for field, value in (("exam_date", "2026/12/11/00/00"), ("preparation_start", "2026/11/30/00/00"),
                             ("weekly_availability", {"monday": [{"start": "09:00", "duration_minutes": 60}]})):
            with self.subTest(field=field):
                self.assert_invalid(self.payload(**{field: value}))

    def test_valid_quiz_for_a_different_certification_cannot_revise_plan(self):
        different_plan = self.store.create(self.csv, datetime(2026, 12, 10),
                                           preparation_start=datetime(2026, 12, 1), availability=self.context,
                                           certification_id="computer_specialist_level_1")
        for commit in (False, True):
            with self.subTest(commit=commit):
                self.assert_invalid(self.payload(schedule_csv_file=different_plan, commit=commit))

    def test_missing_question_field_is_a_configuration_error(self):
        with patch("assessment_service.check_reviewed_record", side_effect=KeyError("missing question field")):
            with self.assertLogs("api_server", level="ERROR"):
                self.assert_invalid(self.payload(), "INTERNAL_ERROR", 500)
                status, body = self.call(QUESTIONS_PATH)
        self.assertEqual((status, body["error"]["code"]), (500, "INTERNAL_ERROR"))
        self.assertNotIn("missing question field", body["error"]["message"])

    def test_legacy_plan_without_context_is_rejected(self):
        legacy = self.store.create(self.csv, datetime(2026, 12, 10))
        self.assert_invalid(self.payload(schedule_csv_file=legacy))

    def test_no_unreported_future_rows(self):
        self.assert_invalid(self.payload(keep_schedule_ids=["study-3", "study-4", "study-5"]))

    def test_insufficient_minutes_preserve_plan(self):
        rows = [{**ROWS[4], "end_date": "2026/12/03/19/01"}]
        plan = self.store.create(render_schedule_csv(parse_schedule_rows(rows)), datetime(2026, 12, 10),
                                 preparation_start=datetime(2026, 12, 1), availability=self.context)
        payload = self.payload(schedule_csv_file=plan, schedules=rows, keep_schedule_ids=[])
        self.assert_invalid(payload)
        status, body = self.call("/schedules/adjust", payload)
        self.assertEqual(status, 422)
        self.assertTrue(body["error"]["message"].startswith("NOT_ENOUGH_TIME"), body)

    def test_required_and_typed_fields(self):
        for field in self.payload():
            if field == "mode":
                continue
            payload = self.payload()
            del payload[field]
            with self.subTest(missing=field):
                self.assert_invalid(payload)
        for changes in ({"commit": 1}, {"keep_schedule_ids": ["study-3", "study-3"]},
                        {"keep_schedule_ids": ["external-1"]}, {"keep_schedule_ids": ["study-1"]},
                        {"keep_schedule_ids": ["study-999"]}, {"first_new_study_id": "study-1"},
                        {"first_new_study_id": 6}, {"schedule_id": "study-4"},
                        {"replan_from": "bad"}, {"replan_from": "2026/12/11/00/00"}):
            with self.subTest(changes=changes):
                self.assert_invalid(self.payload(**changes))

    def test_incomplete_duplicate_or_tampered_answers_are_rejected(self):
        for kind in ("missing", "duplicate", "key", "prompt", "score", "correctness", "topic", "unknown"):
            payload = self.payload()
            results = payload["assessment_results"]["results"]
            if kind == "missing": results.pop()
            elif kind == "duplicate": results.append(copy.deepcopy(results[0]))
            elif kind == "key": results[0]["correct_answer"] = next(k for k in results[0]["choices"] if k != results[0]["correct_answer"])
            elif kind == "prompt": results[0]["question"] += "tampered"
            elif kind == "score": results[0]["possible_score"] = 2
            elif kind == "correctness": results[0]["is_correct"] = True
            elif kind == "topic": results[0]["problem_id"] = 1
            elif kind == "unknown": payload["assessment_results"]["certification_id"] = "unsupported"
            with self.subTest(kind=kind):
                self.assert_invalid(payload)

    def test_unknown_question_route(self):
        self.assertEqual(self.call("/certifications/unsupported/reassessment-questions")[0], 404)
        self.assertEqual(self.call("/certifications/a/b/reassessment-questions")[0], 404)

    def test_bad_selection_or_content_fails_closed(self):
        for kind in ("missing", "duplicate", "overlap", "unknown", "topic", "explanation", "coverage"):
            document = level_2_bank()
            if kind == "missing": document.pop("reassessment_question_ids")
            elif kind == "duplicate": document["reassessment_question_ids"].append(19)
            elif kind == "overlap": document["reassessment_question_ids"][0] = 1
            elif kind == "unknown": document["reassessment_question_ids"][0] = 999
            elif kind == "topic": document["questions"][18]["topic"] = "unknown"
            elif kind == "explanation": document["questions"][18]["explanation"] = ""
            elif kind == "coverage": document["reassessment_question_ids"] = [19]
            with self.subTest(kind=kind), temporary_question_bank(document):
                with self.assertLogs("api_server", level="ERROR"):
                    status, body = self.call(QUESTIONS_PATH)
                self.assertEqual(status, 500, body)
                self.assertEqual(body["error"]["code"], "INTERNAL_ERROR")


if __name__ == "__main__":
    unittest.main()
