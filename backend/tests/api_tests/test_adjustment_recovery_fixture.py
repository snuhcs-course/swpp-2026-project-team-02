"""Request-shape and deadline guards of the Android recovery simulation server."""
from __future__ import annotations

import copy
import importlib.util
import json
import tempfile
import threading
import unittest
from datetime import datetime
from http.server import ThreadingHTTPServer
from pathlib import Path
from urllib.error import HTTPError
from urllib.request import Request, urlopen

SERVER_PATH = Path(__file__).resolve().parents[3] / "frontend" / "test_support" / "adjustment_server.py"
_spec = importlib.util.spec_from_file_location("android_adjustment_server", SERVER_PATH)
fake = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(fake)

WINDOWS = {"monday": [{"start": "18:00", "end": "20:00"}]}
MISMATCH = "exam_date: must match the selected plan's stored exam date"


class RecoveryFixtureTests(unittest.TestCase):
    def start(self, scenario="normal"):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.state = fake.State(scenario, Path(temp.name))
        handler = type("Handler", (fake.Handler,), {"state": self.state})
        server = ThreadingHTTPServer(("127.0.0.1", 0), handler)
        server.daemon_threads = True
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        self.addCleanup(thread.join, 3)
        self.addCleanup(server.server_close)
        self.addCleanup(server.shutdown)
        self.base = f"http://127.0.0.1:{server.server_port}/api/v1"

    def post(self, path, payload):
        request = Request(self.base + path, data=json.dumps(payload).encode(),
                          headers={"Content-Type": "application/json"}, method="POST")
        try:
            with urlopen(request, timeout=5) as response:
                return response.status, json.loads(response.read())
        except HTTPError as error:
            with error:
                return error.code, json.loads(error.read())

    def generate(self, exam_date):
        status, plan = self.post("/study-plans", {"exam_date": exam_date})
        self.assertEqual(status, 200, plan)
        return plan

    def request(self, plan, schedule_id="study-1", **overrides):
        payload = {"schedule_csv_file": plan["schedule_csv_file"], "schedule_id": schedule_id,
                   "schedules": copy.deepcopy(plan["schedules"]), "weekly_availability": WINDOWS,
                   "exam_date": overrides.pop("exam_date", "2026/11/16/00/00")}
        payload.update(overrides)
        return payload

    def stored(self):
        return copy.deepcopy(self.state.snapshot())

    def assert_rejected(self, payload, message):
        before = self.stored()
        status, body = self.post("/schedules/adjust", payload)
        self.assertEqual((status, body["error"]["code"]), (422, "INVALID_INPUT"), body)
        self.assertEqual(body["error"]["message"], message)
        self.assertEqual(self.stored(), before)

    def test_moves_within_the_generated_exam_date(self):
        self.start()
        plan = self.generate("2026/11/16/00/00")
        status, body = self.post("/schedules/adjust", self.request(plan))
        self.assertEqual(status, 200, body)
        target = next(r for r in body["schedules"] if r["schedule_id"] == "study-1")
        self.assertEqual((target["start_date"], target["end_date"]), ("2026/10/26/18/00", "2026/10/26/19/00"))
        self.assertEqual(dict(self.stored())[fake.FILE_A], body["schedules"])

    def test_current_app_fields_and_exam_date_are_required(self):
        self.start()
        plan = self.generate("2026/11/16/00/00")
        fields = "request must contain exactly the current app's five adjustment fields"
        missing = self.request(plan)
        del missing["exam_date"]
        self.assert_rejected(missing, fields)
        self.assert_rejected(self.request(plan, extra=1), fields)
        for value in (None, "2026-11-16", "2026/11/16", 20261116):
            with self.subTest(value=value):
                self.assert_rejected(self.request(plan, exam_date=value),
                                     "exam_date must use YYYY/MM/DD/HH/MM format")
        other = self.generate("2026/12/14/00/00")  # B, with its own date
        for value in ("2026/11/15/00/00", "2026/11/17/00/00", "2026/12/14/00/00"):
            with self.subTest(value=value):
                self.assert_rejected(self.request(plan, exam_date=value), MISMATCH)
        self.assertNotEqual(plan["schedule_csv_file"], other["schedule_csv_file"])

    def test_simulated_move_past_the_exam_saves_nothing(self):
        self.start()
        plan = self.generate("2026/10/26/18/30")
        self.assert_rejected(self.request(plan, exam_date="2026/10/26/18/30"),
                             "NOT_ENOUGH_TIME: no available study slot remains before the exam date.")
        status, _ = self.post("/schedules/adjust", self.request(plan, exam_date="2026/10/26/18/30",
                                                                schedule_id="study-2"))
        self.assertEqual(status, 422)
        events = [json.loads(line).get("event") for line in
                  self.state.log_path.read_text(encoding="utf-8").splitlines()]
        self.assertNotIn("saved-adjustment", events)

    def test_legacy_plan_scenario_asks_for_a_new_plan(self):
        self.start("legacy-plan")
        plan = self.generate("2026/11/16/00/00")
        self.assert_rejected(self.request(plan), fake.NO_STORED_EXAM_DATE)
        self.assertEqual(fake.NO_STORED_EXAM_DATE,
                         "This saved plan has no stored exam date. Create a new plan before adjusting study sessions.")

    def assert_recreation_required(self, payload):
        before = self.stored()
        status, body = self.post("/schedules/adjust", payload)
        self.assertEqual((status, body), (422, {"error": {"code": "INVALID_INPUT",
                                                          "message": fake.NO_STORED_EXAM_DATE}}))
        self.assertEqual(self.stored(), before)

    def test_exam_dates_exist_only_for_plans_generated_by_this_fixture_state(self):
        self.start()
        seeded_a = {"schedule_csv_file": fake.FILE_A, "schedules": dict(self.stored())[fake.FILE_A]}
        self.assertEqual(self.state.exam_dates, {})
        self.assert_recreation_required(self.request(seeded_a))

        plan = self.generate("2026/11/16/00/00")
        self.assertEqual(plan["schedule_csv_file"], fake.FILE_A)
        self.assertEqual(self.state.exam_dates, {fake.FILE_A: datetime(2026, 11, 16, 0, 0)})
        status, body = self.post("/schedules/adjust", self.request(plan))
        self.assertEqual(status, 200, body)
        self.assertEqual(dict(self.stored())[fake.FILE_A], body["schedules"])

        self.start()  # a restarted fixture: rows reseeded, no recorded exam dates
        self.assertEqual(self.state.exam_dates, {})
        self.assertEqual(dict(self.stored())[fake.FILE_A], seeded_a["schedules"])
        self.assert_recreation_required(self.request(seeded_a))

    def test_unknown_plan_and_stale_rows_keep_their_errors(self):
        self.start()
        plan = self.generate("2026/11/16/00/00")
        status, body = self.post("/schedules/adjust", self.request(plan, schedule_csv_file=fake.FILE_A_SIMILAR))
        self.assertEqual((status, body["error"]["code"]), (404, "PLAN_NOT_FOUND"))
        stale = self.request(plan)
        stale["schedules"][0]["topic"] = "changed"
        status, body = self.post("/schedules/adjust", stale)
        self.assertEqual((status, body["error"]["code"]), (409, "PLAN_CONFLICT"))

    def test_generation_requires_a_valid_exam_date(self):
        self.start()
        status, body = self.post("/study-plans", {})
        self.assertEqual((status, body["error"]["code"]), (422, "INVALID_INPUT"))
        self.assertEqual(self.state.counts["generate"], 0)


if __name__ == "__main__":
    unittest.main()
