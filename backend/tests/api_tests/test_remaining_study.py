"""Remaining-work re-planning over the real HTTP handler with temporary plan storage."""
import copy
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
from urllib.parse import quote

import api_service
from api_server import DEFAULT_API_CONFIG, make_handler
from schedule_adjuster.csv_io import parse_schedule_rows, render_schedule_csv
from study_availability import parse_availability_context
from study_plan_store import StudyPlanStore

PREPARATION = datetime(2026, 11, 16, 0, 0)
EXAM = datetime(2026, 12, 7, 0, 0)
FROZEN_NOW = datetime(2026, 11, 16, 20, 30)
WEEKLY = {"monday": [{"start": "18:00", "duration_minutes": 120}],
          "tuesday": [{"start": "18:00", "duration_minutes": 60}],
          "wednesday": [{"start": "18:00", "duration_minutes": 60}]}
OVERRIDES = {"2026/11/23": {}}
TOPIC = "함수와 데이터 관리"
ROWS = [
    {"schedule_id": "study-1", "start_date": "2026/11/16/18/00", "end_date": "2026/11/16/19/00", "topic": TOPIC},
    {"schedule_id": "study-2", "start_date": "2026/11/16/19/00", "end_date": "2026/11/16/20/00", "topic": "차트"},
    {"schedule_id": "external-3", "start_date": "2026/11/17/18/00", "end_date": "2026/11/17/18/30", "topic": ""},
    {"schedule_id": "study-4", "start_date": "2026/11/17/18/30", "end_date": "2026/11/17/19/00", "topic": TOPIC},
    {"schedule_id": "study-6", "start_date": "2026/11/18/18/00", "end_date": "2026/11/18/19/00",
     "topic": "데이터베이스"},
]
INSTRUCTIONS = [{"schedule_id": "study-1", "remaining_minutes": 60, "replacement_schedule_id": "study-1"},
                {"schedule_id": "study-4", "remaining_minutes": 10, "replacement_schedule_id": "study-7"}]
EXPECTED = {
    "study-2": ROWS[1], "external-3": ROWS[2], "study-6": ROWS[4],
    # Tuesday's freed half hour takes the 10-minute remainder; the hour waits for the week after the empty week.
    "study-7": {"schedule_id": "study-7", "start_date": "2026/11/17/18/30", "end_date": "2026/11/17/18/40",
                "topic": TOPIC},
    "study-1": {"schedule_id": "study-1", "start_date": "2026/11/30/18/00", "end_date": "2026/11/30/19/00",
                "topic": TOPIC},
}


class ProviderUsed(AssertionError):
    pass


def forbidden(*args, **kwargs):
    raise ProviderUsed("remaining mode must not construct an agent or read the shared profile")


class RemainingStudyApiTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name) / "plans"
        self.profile = Path(self.temp.name) / "profile.json"
        self.profile.write_text(json.dumps({"friday": [{"start": "09:00", "duration_minutes": 60}]}),
                                encoding="utf-8")
        context = parse_availability_context({"weekly_availability": WEEKLY, "availability_overrides": OVERRIDES})
        self.csv = render_schedule_csv(parse_schedule_rows(ROWS))
        self.store = StudyPlanStore(self.root)
        self.plan = self.store.create(self.csv, EXAM, preparation_start=PREPARATION, availability=context)
        self.other = self.store.create(self.csv, EXAM, preparation_start=PREPARATION, availability=context)
        for name in ("AdjustmentAgent", "PlanningAgent", "load_adjuster_availability"):
            self.patch(name, forbidden)
        self.patch("STUDY_PLAN_OUTPUT_DIR", self.root)
        self.patch("current_korea_minute", lambda: FROZEN_NOW)
        self.start_server()
        self.before = self.files()

    def patch(self, name, value):
        patcher = patch.object(api_service, name, value)
        patcher.start()
        self.addCleanup(patcher.stop)

    def start_server(self):
        handler = make_handler(DEFAULT_API_CONFIG)
        handler.log_message = lambda *args: None
        server = ThreadingHTTPServer(("127.0.0.1", 0), handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        self.addCleanup(lambda: (server.shutdown(), server.server_close(), thread.join(timeout=3)))
        self.server = server

    def call(self, path, payload=None):
        data = None if payload is None else json.dumps(payload).encode()
        request = Request(f"http://127.0.0.1:{self.server.server_port}/api/v1{path}", data=data,
                          headers={"Content-Type": "application/json"})
        try:
            response = urlopen(request, timeout=10)
        except HTTPError as error:
            response = error
        with response:
            return response.status, json.loads(response.read().decode())

    def files(self):
        return {path.name: path.read_bytes() for path in self.root.iterdir()} | {"profile": self.profile.read_bytes()}

    def csv_name(self, identifier):
        return identifier.rsplit("/", 1)[1]

    def payload(self, **fields):
        return {"mode": "remaining", "schedule_csv_file": self.plan, "schedules": copy.deepcopy(ROWS),
                "weekly_availability": copy.deepcopy(WEEKLY), "availability_overrides": copy.deepcopy(OVERRIDES),
                "preparation_start": "2026/11/16/00/00", "exam_date": "2026/12/07/00/00",
                "replan_from": "2026/11/16/20/31", "remaining_sessions": copy.deepcopy(INSTRUCTIONS), **fields}

    def assert_rejected(self, payload, status, code):
        response_status, result = self.call("/schedules/adjust", payload)
        self.assertEqual((response_status, result["error"]["code"]), (status, code), result)
        self.assertEqual(self.files(), self.before)
        return result

    def assert_only_selected_csv_changed(self, identifier):
        name = self.csv_name(identifier)
        after = self.files()
        self.assertEqual({k: v for k, v in after.items() if k != name},
                         {k: v for k, v in self.before.items() if k != name})

    def test_complete_batch_is_saved_once_and_read_back_after_restart(self):
        with patch.object(StudyPlanStore, "update", autospec=True, side_effect=StudyPlanStore.update) as update:
            status, result = self.call("/schedules/adjust", self.payload())
        self.assertEqual(status, 200, result)
        self.assertEqual(update.call_count, 1)
        self.assertEqual({row["schedule_id"]: row for row in result["schedules"]}, EXPECTED)
        self.assertEqual((result["action"], result["tool_calls"]), ("replanned_remaining", []))
        self.assertEqual(result["agent_summary"], "남은 공부 2개, 총 70분을 다시 배치했어요.")
        self.assertEqual(result["schedule_csv_file"], self.plan)
        self.assertEqual(result["schedule_csv_url"], f"/api/v1/study-plans/{self.csv_name(self.plan)}")
        self.assertEqual(result["availability_snapshot"]["availability_overrides"]["2026/11/23"]["monday"], [])
        self.assertNotIn("target_schedule_id", result)
        self.assert_only_selected_csv_changed(self.plan)

        # A fresh store and server see the same rows; another valid request continues from them.
        self.store = StudyPlanStore(self.root)
        self.start_server()
        status, listing = self.call("/study-plans?schedule_csv_file=" + quote(self.plan, safe=""))
        self.assertEqual(status, 200)
        self.assertEqual(listing["plans"][0]["schedules"], result["schedules"])
        self.assertEqual(self.store.download(self.csv_name(self.plan))[0], result["schedule_csv"])
        self.before = self.files()
        status, second = self.call("/schedules/adjust", self.payload(
            schedules=result["schedules"],
            remaining_sessions=[{"schedule_id": "study-7", "remaining_minutes": 10,
                                 "replacement_schedule_id": "study-7"}]))
        self.assertEqual(status, 200, second)
        self.assertEqual(next(r for r in second["schedules"] if r["schedule_id"] == "study-7")["start_date"],
                         "2026/11/17/18/30")
        self.assert_only_selected_csv_changed(self.plan)

    def test_automatic_and_manual_requests_keep_their_contracts(self):
        status, result = self.call("/schedules/adjust", {
            "mode": "manual", "schedule_csv_file": self.plan, "schedule_id": "study-6",
            "schedules": copy.deepcopy(ROWS), "new_start_date": "2026/11/24/18/00",
            "confirm_outside_availability": True})
        self.assertEqual(status, 200, result)
        self.assertEqual((result["action"], result["target_schedule_id"]), ("rescheduled_study", "study-6"))
        # Without a mode the request is still automatic, which needs a model and a single target.
        result = self.assert_rejected_after_reset({"schedule_csv_file": self.other, "schedules": copy.deepcopy(ROWS),
                                                   "remaining_sessions": copy.deepcopy(INSTRUCTIONS)})
        self.assertIn("schedule_id", result["error"]["message"])

    def assert_rejected_after_reset(self, payload):
        self.before = self.files()
        return self.assert_rejected(payload, 422, "INVALID_INPUT")

    def test_malformed_mode_specific_fields_are_invalid_input(self):
        variants = {}
        for field in ("schedule_csv_file", "schedules", "weekly_availability", "availability_overrides",
                      "preparation_start", "exam_date", "replan_from", "remaining_sessions"):
            variants[f"missing {field}"] = {k: v for k, v in self.payload().items() if k != field}
            variants[f"null {field}"] = self.payload(**{field: None})
        for field, value in (("schedule_id", "study-1"), ("new_start_date", "2026/11/24/18/00"),
                             ("confirm_outside_availability", False), ("schedule_id", None)):
            variants[f"single-target {field}={value}"] = self.payload(**{field: value})

        def session(**fields):
            return self.payload(remaining_sessions=[{**INSTRUCTIONS[0], **fields}])
        variants |= {
            "empty instructions": self.payload(remaining_sessions=[]),
            "instructions object": self.payload(remaining_sessions={"study-1": 60}),
            "instruction not object": self.payload(remaining_sessions=["study-1"]),
            "extra instruction key": session(status="missed"),
            "missing instruction key": self.payload(remaining_sessions=[{"schedule_id": "study-1",
                                                                         "remaining_minutes": 60}]),
            "boolean minutes": session(remaining_minutes=True),
            "fractional minutes": session(remaining_minutes=59.5),
            "integral float minutes": session(remaining_minutes=60.0),
            "string minutes": session(remaining_minutes="60"),
            "zero minutes": session(remaining_minutes=0),
            "negative minutes": session(remaining_minutes=-10),
            "more minutes than scheduled": session(remaining_minutes=61),
            "numeric source ID": session(schedule_id=1),
            "unknown source": session(schedule_id="study-9", replacement_schedule_id="study-9"),
            "external source": session(schedule_id="external-3", replacement_schedule_id="study-7"),
            "duplicate source": self.payload(remaining_sessions=[
                {**INSTRUCTIONS[1]}, {**INSTRUCTIONS[1], "replacement_schedule_id": "study-8"}]),
            "kept ID with shorter remainder": session(remaining_minutes=20),
            "fresh ID below current": session(remaining_minutes=20, replacement_schedule_id="study-5"),
            "fresh ID equal to another current row": session(remaining_minutes=20, replacement_schedule_id="study-6"),
            "replacement is an external ID": session(remaining_minutes=20, replacement_schedule_id="external-8"),
            "duplicate replacement": self.payload(remaining_sessions=[
                {**INSTRUCTIONS[0], "remaining_minutes": 20, "replacement_schedule_id": "study-7"},
                INSTRUCTIONS[1]]),
            "malformed replan_from": self.payload(replan_from="2026/11/16 20:31"),
            "mismatched exam date": self.payload(exam_date="2026/12/08/00/00"),
            "mismatched preparation start": self.payload(preparation_start="2026/11/15/00/00"),
            "mismatched base week": self.payload(weekly_availability={"monday": [
                {"start": "18:00", "duration_minutes": 60}]}),
            "mismatched override weeks": self.payload(availability_overrides={}),
            "invalid identifier": self.payload(schedule_csv_file="../study_plan_x.csv"),
            "unknown mode": self.payload(mode="remaining_all"),
        }
        for name, payload in variants.items():
            with self.subTest(name):
                self.assert_rejected(payload, 422, "INVALID_INPUT")

    def test_semantically_equal_context_in_another_form_is_accepted(self):
        reordered = {"wednesday": [{"start": "18:00", "end": "19:00"}],
                     "tuesday": [{"start": "18:00", "duration_minutes": 60, "end": "19:00"}],
                     "monday": [{"start": "18:00", "end": "20:00"}]}
        empty_week = {day: [] for day in ("monday", "tuesday", "wednesday", "thursday", "friday",
                                          "saturday", "sunday")}
        status, result = self.call("/schedules/adjust", self.payload(
            weekly_availability=reordered, availability_overrides={"2026/11/23": empty_week}))
        self.assertEqual(status, 200, result)

    def test_plans_without_full_retained_context_need_recreation(self):
        exam_only = self.store.create(self.csv, EXAM)
        csv_only = self.store.create(self.csv)
        self.before = self.files()
        for identifier in (exam_only, csv_only):
            with self.subTest(identifier=identifier):
                result = self.assert_rejected(self.payload(schedule_csv_file=identifier), 422, "INVALID_INPUT")
                self.assertIn("Create a new plan", result["error"]["message"])

    def test_damaged_metadata_is_a_storage_error(self):
        metadata = self.root / (Path(self.csv_name(self.plan)).stem + ".meta.json")
        metadata.write_text("{\"version\": 2}", encoding="utf-8")
        self.before = self.files()
        self.assert_rejected(self.payload(), 500, "STORAGE_ERROR")

    def test_no_whole_fit_rejects_the_batch_after_an_earlier_fit(self):
        # study-1 fits Wednesday 12/2; study-6 has no free window left before the 12/7 exam.
        result = self.assert_rejected(self.payload(
            replan_from="2026/12/02/00/00",
            remaining_sessions=[INSTRUCTIONS[0], {"schedule_id": "study-6", "remaining_minutes": 60,
                                                  "replacement_schedule_id": "study-6"}]),
            422, "INVALID_INPUT")
        self.assertIn("NOT_ENOUGH_TIME", result["error"]["message"])
        self.assertIn("'study-6'", result["error"]["message"])

    def test_stale_rows_missing_plan_and_changes_before_commit(self):
        stale = self.payload()
        stale["schedules"][1]["topic"] = "changed"
        self.assert_rejected(stale, 409, "PLAN_CONFLICT")
        self.assert_rejected(self.payload(
            schedule_csv_file="license_planner/output/study_plan_20990101T000000_deadbeef.csv"), 404, "PLAN_NOT_FOUND")

        original = api_service.place_remaining_sessions
        csv_path = self.root / self.csv_name(self.plan)
        metadata = self.root / (Path(self.csv_name(self.plan)).stem + ".meta.json")
        for changed in (csv_path, metadata):
            with self.subTest(changed=changed.name):
                newer = changed.read_bytes() + b"\n"

                def change_then_place(*args, **kwargs):
                    changed.write_bytes(newer)
                    return original(*args, **kwargs)
                with patch.object(api_service, "place_remaining_sessions", change_then_place):
                    status, result = self.call("/schedules/adjust", self.payload())
                self.assertEqual((status, result["error"]["code"]), (409, "PLAN_CONFLICT"))
                self.assertEqual(changed.read_bytes(), newer)
                changed.write_bytes(self.before[changed.name])
        self.assertEqual(self.files(), self.before)

    def test_failed_atomic_replace_preserves_the_plan(self):
        with patch("study_plan_store.os.replace", side_effect=PermissionError("locked")):
            self.assert_rejected(self.payload(), 500, "STORAGE_ERROR")
        self.assertEqual(list(self.root.glob("*.tmp")), [])


if __name__ == "__main__":
    unittest.main()
