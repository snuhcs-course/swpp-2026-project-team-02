"""The shared availability profile is unavailable on a public server and unchanged in local mode."""
from __future__ import annotations

import copy
import http.client
import json
import os
import tempfile
import threading
import unittest
from datetime import datetime
from http.server import ThreadingHTTPServer
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

import api_service
import license_planner.user_availability as planner_availability
import schedule_adjuster.settings as adjuster_settings
from api_server import DEFAULT_API_CONFIG, load_api_config, make_handler
from license_planner.models import StudyBlock
from schedule_adjuster.csv_io import parse_schedule_rows, render_schedule_csv
from study_availability import parse_availability_context
from test_support.device_tokens import bearer, owner_store

PUBLIC_CONFIG = {**DEFAULT_API_CONFIG, "shared_availability_enabled": False}
ROUTE_NOT_FOUND = {"error": {"code": "NOT_FOUND", "message": "API route not found"}}
PROFILE_WEEK = {"tuesday": [{"start": "18:00", "duration_minutes": 120}]}
REQUEST_WEEK = {"monday": [{"start": "18:00", "duration_minutes": 120}]}
FROZEN_NOW = datetime(2026, 10, 18, 0, 0)
ROWS = [{"schedule_id": "study-1", "start_date": "2026/10/19/18/00", "end_date": "2026/10/19/19/00",
         "topic": "스프레드시트 기본"}]
GENERATED = (StudyBlock("study-1", datetime(2026, 10, 19, 18, 0), datetime(2026, 10, 19, 19, 0), "스프레드시트 기본"),)


def generation_request() -> dict:
    fixtures = Path(__file__).with_name("fixtures") / "recorded_model_tool_calls.json"
    request = json.loads(fixtures.read_text(encoding="utf-8"))["topic_errors_then_build_on_last_request"]["request"]
    request = copy.deepcopy(request)
    request.pop("weekly_availability", None)
    request.pop("availability_overrides", None)
    return request


class RecordingPlanningAgent:
    contexts: list = []

    def run(self, request):
        RecordingPlanningAgent.contexts.append(request.weekly_availability)
        return SimpleNamespace(schedule=GENERATED, trace=SimpleNamespace(calls=[])), "fixed plan"


def forbidden_agent(*args, **kwargs):
    raise AssertionError("these requests must not reach the model")


class PublicProfileGatingTests(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.plan_root = Path(temp.name) / "plans"
        self.profile = Path(temp.name) / "user_availability.json"
        self.profile.write_text(json.dumps({"weekly_availability": PROFILE_WEEK}), encoding="utf-8")
        self.profile_bytes = self.profile.read_bytes()
        self.profile_reads = []
        RecordingPlanningAgent.contexts = []
        real_planner_loader = api_service.load_personal_availability
        real_adjuster_loader = api_service.load_adjuster_availability

        def counted(label, loader):
            def load(*args, **kwargs):
                self.profile_reads.append(label)
                return loader(*args, **kwargs)
            return load

        def forbidden_save(*args, **kwargs):
            raise AssertionError("the shared profile must not be written")

        patches = [patch.object(planner_availability, "USER_AVAILABILITY_PATH", self.profile),
                   patch.object(adjuster_settings, "USER_AVAILABILITY_PATH", self.profile),
                   patch.object(api_service, "STUDY_PLAN_OUTPUT_DIR", self.plan_root),
                   patch.object(api_service, "current_korea_minute", lambda: FROZEN_NOW),
                   patch.object(api_service, "PlanningAgent", RecordingPlanningAgent),
                   patch.object(api_service, "AdjustmentAgent", forbidden_agent),
                   patch.object(api_service, "load_personal_availability", counted("planner", real_planner_loader)),
                   patch.object(api_service, "load_adjuster_availability", counted("adjuster", real_adjuster_loader))]
        for patcher in patches:
            patcher.start()
            self.addCleanup(patcher.stop)
        self.store = owner_store(self.plan_root)

    def start_server(self, config):
        server = ThreadingHTTPServer(("127.0.0.1", 0), make_handler(config))
        server.RequestHandlerClass.log_message = lambda *args: None
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        self.addCleanup(lambda: (server.shutdown(), server.server_close(), thread.join(timeout=3)))
        self.port = server.server_port

    def send(self, method, path, payload=None, *, token=True, body=None):
        connection = http.client.HTTPConnection("127.0.0.1", self.port, timeout=10)
        try:
            if payload is not None:
                body = json.dumps(payload).encode("utf-8")
            headers = bearer() if token else {}
            if body is not None:
                headers = {**headers, "Content-Type": "application/json", "Content-Length": str(len(body))}
            connection.request(method, "/api/v1" + path, body=body, headers=headers)
            raw = connection.getresponse()
            return raw.status, json.loads(raw.read())
        finally:
            connection.close()

    def assert_profile_untouched(self):
        self.assertEqual(self.profile.read_bytes(), self.profile_bytes)
        self.assertEqual(self.profile_reads, [])

    def test_public_profile_routes_are_not_found_before_profile_access(self):
        self.start_server(PUBLIC_CONFIG)
        with patch.object(api_service, "save_personal_availability",
                          side_effect=AssertionError("the shared profile must not be written")):
            for token in (True, False):
                with self.subTest(token=token):
                    self.assertEqual(self.send("GET", "/availability", token=token), (404, ROUTE_NOT_FOUND))
                    self.assertEqual(self.send("PUT", "/availability", {"monday": [{"start": "09:00",
                                                                                     "duration_minutes": 60}]},
                                               token=token), (404, ROUTE_NOT_FOUND))
                    self.assertEqual(self.send("PUT", "/availability", token=token, body=b"{not json"),
                                     (404, ROUTE_NOT_FOUND))
        self.assert_profile_untouched()

    def test_local_profile_routes_keep_their_contract(self):
        self.start_server(DEFAULT_API_CONFIG)
        status, body = self.send("GET", "/availability", token=False)
        self.assertEqual((status, body["weekly_availability"]["tuesday"]),
                         (200, [{"start": "18:00", "duration_minutes": 120, "end": "20:00"}]))
        status, body = self.send("PUT", "/availability", {"weekly_availability": REQUEST_WEEK}, token=False)
        self.assertEqual((status, body["weekly_availability"]["monday"][0]["end"]), (200, "20:00"))
        self.assertEqual(json.loads(self.profile.read_text(encoding="utf-8"))["weekly_availability"]["monday"],
                         REQUEST_WEEK["monday"])

    def test_public_generation_uses_only_request_availability(self):
        self.start_server(PUBLIC_CONFIG)
        status, body = self.send("POST", "/study-plans", generation_request())
        self.assertEqual(status, 422, body)
        self.assertEqual(body["error"]["code"], "INVALID_INPUT")
        self.assertIn("weekly_availability is required", body["error"]["message"])
        self.assertEqual(RecordingPlanningAgent.contexts, [])

        status, body = self.send("POST", "/study-plans", {**generation_request(), "weekly_availability": REQUEST_WEEK})
        self.assertEqual(status, 200, body)
        self.assertEqual(RecordingPlanningAgent.contexts, [parse_availability_context(
            {"weekly_availability": REQUEST_WEEK, "availability_overrides": {}})])
        self.assertEqual(body["availability_snapshot"]["weekly_availability"]["monday"][0]["duration_minutes"], 120)
        self.assertEqual(body["availability_snapshot"]["weekly_availability"]["tuesday"], [])
        self.assert_profile_untouched()

    def test_local_generation_still_falls_back_to_the_profile(self):
        self.start_server(DEFAULT_API_CONFIG)
        status, body = self.send("POST", "/study-plans", generation_request())
        self.assertEqual(status, 200, body)
        self.assertEqual(self.profile_reads, ["planner"])
        self.assertEqual(body["availability_snapshot"]["weekly_availability"]["tuesday"][0]["duration_minutes"], 120)

    def manual_move(self, identifier, new_start, **fields):
        return self.send("POST", "/schedules/adjust", {
            "mode": "manual", "schedule_csv_file": identifier, "schedule_id": "study-1",
            "schedules": copy.deepcopy(ROWS), "new_start_date": new_start, **fields})

    def test_public_adjustment_uses_retained_context_and_never_the_profile(self):
        csv_text = render_schedule_csv(parse_schedule_rows(ROWS))
        retained = self.store.create(csv_text, datetime(2026, 11, 30), preparation_start=datetime(2026, 10, 18),
                                     availability=parse_availability_context({"weekly_availability": REQUEST_WEEK}))
        exam_only = self.store.create(csv_text, datetime(2026, 11, 30))
        self.start_server(PUBLIC_CONFIG)

        # The retained Monday window applies; the profile's Tuesday window is not a study window.
        status, body = self.manual_move(retained, "2026/10/27/18/00")
        self.assertEqual((status, body["error"]["code"]), (422, "OUTSIDE_STUDY_WINDOWS"))
        status, body = self.manual_move(retained, "2026/10/26/18/00")
        self.assertEqual(status, 200, body)
        # A plan with no retained context has no study window; the move needs explicit confirmation.
        status, body = self.manual_move(exam_only, "2026/10/27/18/00", preparation_start="2026/10/18/00/00")
        self.assertEqual((status, body["error"]["code"]), (422, "OUTSIDE_STUDY_WINDOWS"))
        self.assert_profile_untouched()

    def test_local_adjustment_still_falls_back_to_the_profile(self):
        exam_only = self.store.create(render_schedule_csv(parse_schedule_rows(ROWS)), datetime(2026, 11, 30))
        self.start_server(DEFAULT_API_CONFIG)
        status, body = self.manual_move(exam_only, "2026/10/27/18/00", preparation_start="2026/10/18/00/00")
        self.assertEqual(status, 200, body)
        self.assertEqual(self.profile_reads, ["adjuster"])


class FlagConfigurationTests(unittest.TestCase):
    FLAGS = ("APP_TRUST_LOCAL_PROXY", "APP_SHARED_AVAILABILITY_ENABLED")

    def config(self, environment):
        with patch.dict(os.environ), patch("api_server.API_CONFIG_PATH", Path("/nonexistent")):
            for name in self.FLAGS:
                os.environ.pop(name, None)
            os.environ.update(environment)
            return load_api_config()

    def test_flags_default_to_local_behavior_and_accept_only_zero_or_one(self):
        defaults = self.config({})
        self.assertEqual((defaults["trust_local_proxy"], defaults["shared_availability_enabled"]), (False, True))
        public = self.config({"APP_TRUST_LOCAL_PROXY": "1", "APP_SHARED_AVAILABILITY_ENABLED": "0"})
        self.assertEqual((public["trust_local_proxy"], public["shared_availability_enabled"]), (True, False))
        for name in self.FLAGS:
            for value in ("", "true", "yes", " 1", "2"):
                with self.subTest(name=name, value=value), self.assertRaises(ValueError):
                    self.config({name: value})


if __name__ == "__main__":
    unittest.main()
