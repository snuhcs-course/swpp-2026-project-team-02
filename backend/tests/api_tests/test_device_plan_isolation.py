"""Device-token protection and per-owner plan storage over the real HTTP handler."""
from __future__ import annotations

import contextlib
import copy
import csv
import http.client
import io
import json
import logging
import os
import tempfile
import threading
import unittest
from datetime import datetime
from http.server import ThreadingHTTPServer
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch
from urllib.parse import quote

import api_service
import study_plan_store
from api_server import DEFAULT_API_CONFIG, make_handler
from license_planner.models import StudyBlock
from schedule_adjuster.csv_io import parse_schedule_rows, render_schedule_csv
from schedule_adjuster.tools import ScheduleAdjustmentTools
from study_plan_store import StudyPlanStore, owner_digest

TOKEN_A = "a1" * 32
TOKEN_B = "b2" * 32
TOKEN_FRESH = "c3" * 32
TOKEN_LINKED = "d4" * 32
ORIGIN = DEFAULT_API_CONFIG["allowed_origins"][0]
ABSENT_ID = "license_planner/output/study_plan_20990101T000000_deadbeef.csv"
ABSENT_NAME = ABSENT_ID.rsplit("/", 1)[1]
INVALID_TOKEN_BODY = {"error": {"code": "INVALID_DEVICE_TOKEN", "message": "A valid device token is required."}}
WEEKLY = {"monday": [{"start": "18:00", "end": "20:00"}], "wednesday": [{"start": "18:00", "end": "20:00"}],
          "friday": [{"start": "18:00", "end": "20:00"}]}
FROZEN_NOW = datetime(2026, 10, 18, 0, 0)
GENERATED_ROWS = (
    StudyBlock("study-1", datetime(2026, 10, 19, 18, 0), datetime(2026, 10, 19, 19, 0), "스프레드시트 기본"),
    StudyBlock("study-2", datetime(2026, 10, 21, 18, 0), datetime(2026, 10, 21, 19, 0), "함수와 데이터 관리"),
)


def generation_request() -> dict:
    fixtures = Path(__file__).with_name("fixtures") / "recorded_model_tool_calls.json"
    request = json.loads(fixtures.read_text(encoding="utf-8"))["topic_errors_then_build_on_last_request"]["request"]
    return {**copy.deepcopy(request), "weekly_availability": WEEKLY, "busy_schedules": [
        {"schedule_id": "7", "start_date": "2026/10/23/18/00", "end_date": "2026/10/23/19/00", "topic": "외부 일정"}]}


class StubPlanningAgent:
    """Returns a fixed schedule and records each planning request it is given."""
    requests: list = []

    def run(self, request):
        StubPlanningAgent.requests.append(request)
        return SimpleNamespace(schedule=GENERATED_ROWS, trace=SimpleNamespace(calls=[])), "fixed plan"


class ForbiddenAdjustmentAgent:
    """Counts constructions; every isolation rejection must happen before one exists."""
    constructed = 0

    def __init__(self, *args, **kwargs):
        ForbiddenAdjustmentAgent.constructed += 1
        raise AssertionError("a rejected adjustment must not reach the model")


class CancellingAdjustmentAgent:
    """Replaces only model selection: cancels the requested external row with the production tool."""

    def adjust(self, request):
        tools = ScheduleAdjustmentTools(request)
        tools.call("inspect_schedule", {"schedule_id": request.schedule_id})
        tools.call("remove_cancelled_external_schedule", {"schedule_id": request.schedule_id})
        return tools, "cancelled"


class Response(SimpleNamespace):
    def json(self):
        return json.loads(self.body)


class DevicePlanIsolationTests(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.plan_root = Path(temp.name) / "plans"
        self.plan_root.mkdir()
        StubPlanningAgent.requests = []
        ForbiddenAdjustmentAgent.constructed = 0

        def no_profile(*args, **kwargs):
            raise AssertionError("plan routes in these tests supply their own availability")

        for name, value in (("STUDY_PLAN_OUTPUT_DIR", self.plan_root), ("PlanningAgent", StubPlanningAgent),
                            ("AdjustmentAgent", ForbiddenAdjustmentAgent),
                            ("current_korea_minute", lambda: FROZEN_NOW),
                            ("load_personal_availability", no_profile),
                            ("load_adjuster_availability", no_profile)):
            patcher = patch.object(api_service, name, value)
            patcher.start()
            self.addCleanup(patcher.stop)
        self.responses: list[Response] = []
        self.start_server()

    def start_server(self):
        server = ThreadingHTTPServer(("127.0.0.1", 0), make_handler(DEFAULT_API_CONFIG))
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        self.addCleanup(lambda: (server.shutdown(), server.server_close(), thread.join(timeout=3)))
        self.port = server.server_port

    def request(self, method, path, token=None, payload=None, *, headers=(), body=None) -> Response:
        connection = http.client.HTTPConnection("127.0.0.1", self.port, timeout=10)
        try:
            if payload is not None:
                body = json.dumps(payload).encode("utf-8")
            connection.putrequest(method, "/api/v1" + path)
            sent = list(headers)
            if token is not None:
                sent.append(("Authorization", f"Bearer {token}"))
            if body is not None:
                sent += [("Content-Type", "application/json"), ("Content-Length", str(len(body)))]
            for name, value in sent:
                connection.putheader(name, value)
            connection.endheaders(body)
            raw = connection.getresponse()
            response = Response(status=raw.status, headers=raw.getheaders(), body=raw.read())
        finally:
            connection.close()
        self.responses.append(response)
        return response

    @staticmethod
    def header(response, name):
        values = [value for key, value in response.headers if key.lower() == name.lower()]
        return values[0] if len(values) == 1 else values or None

    def generate(self, token):
        response = self.request("POST", "/study-plans", token, generation_request())
        self.assertEqual(response.status, 200, response.body)
        return response.json()

    def owner_dir(self, token):
        return self.plan_root / owner_digest(token)

    def files(self):
        return {str(path.relative_to(self.plan_root)): path.read_bytes()
                for path in sorted(self.plan_root.rglob("*")) if path.is_file()}

    @staticmethod
    def filtered(identifier):
        return "/study-plans?schedule_csv_file=" + quote(identifier, safe="")

    def adjustment_payloads(self, plan, identifier):
        rows = copy.deepcopy(plan["schedules"])
        base = {"schedule_csv_file": identifier, "schedules": rows}
        return {
            "automatic study": {**base, "schedule_id": "study-1", "weekly_availability": WEEKLY},
            "external cancellation": {**base, "schedule_id": "external-7"},
            "manual": {**base, "mode": "manual", "schedule_id": "study-2", "new_start_date": "2026/10/26/18/00"},
            "remaining": {**base, "mode": "remaining", "weekly_availability": WEEKLY, "availability_overrides": {},
                          "preparation_start": plan_request_value("preparation_start"),
                          "exam_date": plan_request_value("exam_date"), "replan_from": "2026/10/19/19/00",
                          "remaining_sessions": [{"schedule_id": "study-1", "remaining_minutes": 30,
                                                  "replacement_schedule_id": "study-9"}]},
        }

    def assert_no_store(self, response):
        self.assertEqual(self.header(response, "Cache-Control"), "no-store", response.headers)

    def assert_invalid_token(self, response):
        self.assertEqual((response.status, response.json()), (401, INVALID_TOKEN_BODY))
        self.assertEqual(self.header(response, "WWW-Authenticate"), 'Bearer realm="license-planner"')
        self.assert_no_store(response)

    # Owner isolation

    def test_delete_requires_ownership_and_cannot_be_reversed_by_adjustment(self):
        plan = self.generate(TOKEN_A)
        identifier = plan["schedule_csv_file"]
        path = plan["schedule_csv_url"].removeprefix("/api/v1")
        files = self.files()
        self.assertEqual(len(files), 2)

        self.assert_invalid_token(self.request("DELETE", path))
        self.assert_invalid_token(self.request("DELETE", path, "invalid"))
        foreign = self.request("DELETE", path, TOKEN_B)
        self.assertEqual((foreign.status, foreign.json()["error"]["code"]), (404, "PLAN_NOT_FOUND"))
        self.assertEqual(self.files(), files)

        deleted = self.request("DELETE", path, TOKEN_A, headers=[("Origin", ORIGIN)])
        self.assertEqual((deleted.status, deleted.body), (204, b""))
        self.assert_no_store(deleted)
        self.assertEqual(self.header(deleted, "Access-Control-Allow-Origin"), ORIGIN)
        self.assertEqual(self.files(), {})
        self.assertEqual(self.request("GET", "/study-plans", TOKEN_A).json(), {"plans": []})

        repeated = self.request("DELETE", path, TOKEN_A)
        self.assertEqual((repeated.status, repeated.body), (foreign.status, foreign.body))
        for payload in self.adjustment_payloads(plan, identifier).values():
            response = self.request("POST", "/schedules/adjust", TOKEN_A, payload)
            self.assertEqual((response.status, response.body), (foreign.status, foreign.body))
        self.assertEqual(ForbiddenAdjustmentAgent.constructed, 0)
        self.assertEqual(self.files(), {})

    def test_delete_route_and_preflight(self):
        path = "/study-plans/" + ABSENT_NAME
        preflight = self.request("OPTIONS", path, headers=[("Origin", ORIGIN),
                                                           ("Access-Control-Request-Method", "DELETE")])
        self.assertEqual(preflight.status, 204)
        self.assertIn("DELETE", self.header(preflight, "Access-Control-Allow-Methods").split(", "))
        for path in ("/health", "/study-plans", "/study-plans/", "/study-plans/nested/other.csv", "/schedules/adjust"):
            with self.subTest(path=path):
                response = self.request("DELETE", path)
                self.assertEqual((response.status, response.json()["error"]["code"]), (404, "NOT_FOUND"))
        malformed = "/study-plans/other.csv"
        get = self.request("GET", malformed, TOKEN_A)
        delete = self.request("DELETE", malformed, TOKEN_A)
        self.assertEqual((delete.status, delete.body), (get.status, get.body))

    def test_each_owner_lists_reads_and_downloads_only_its_own_plans(self):
        plan_a, plan_b = self.generate(TOKEN_A), self.generate(TOKEN_B)
        self.assertNotEqual(plan_a["schedule_csv_file"], plan_b["schedule_csv_file"])
        self.assertTrue(plan_a["schedule_csv_file"].startswith("license_planner/output/study_plan_"))
        self.assertEqual(plan_a["schedule_csv_url"],
                         "/api/v1/study-plans/" + plan_a["schedule_csv_file"].rsplit("/", 1)[1])
        for token, plan in ((TOKEN_A, plan_a), (TOKEN_B, plan_b)):
            listing = self.request("GET", "/study-plans", token)
            self.assertEqual(listing.status, 200)
            self.assertEqual([item["schedule_csv_file"] for item in listing.json()["plans"]],
                             [plan["schedule_csv_file"]])
            self.assert_no_store(listing)
        self.assertEqual(self.request("GET", "/study-plans", TOKEN_FRESH).json(), {"plans": []})
        self.assertEqual(sorted(path.name for path in self.plan_root.iterdir()),
                         sorted([owner_digest(TOKEN_A), owner_digest(TOKEN_B)]))

        # A's exact readback and UTF-8 download.
        readback = self.request("GET", self.filtered(plan_a["schedule_csv_file"]), TOKEN_A)
        self.assertEqual(readback.status, 200)
        [item] = readback.json()["plans"]
        self.assertEqual((item["schedule_csv_file"], item["schedules"]),
                         (plan_a["schedule_csv_file"], plan_a["schedules"]))
        self.assertEqual(item["availability_snapshot"], plan_a["availability_snapshot"])
        download = self.request("GET", plan_a["schedule_csv_url"].removeprefix("/api/v1"), TOKEN_A)
        self.assertEqual(download.status, 200)
        self.assertEqual(self.header(download, "Content-Type"), "text/csv; charset=utf-8")
        self.assertEqual(self.header(download, "Content-Disposition"),
                         f'attachment; filename="{plan_a["schedule_csv_file"].rsplit("/", 1)[1]}"')
        self.assertEqual(download.body.decode("utf-8"), plan_a["schedule_csv"])
        self.assertIn("스프레드시트 기본", download.body.decode("utf-8"))
        self.assert_no_store(download)

    def test_another_owner_gets_the_absent_plan_outcome_without_touching_the_plan(self):
        plan_a = self.generate(TOKEN_A)
        identifier = plan_a["schedule_csv_file"]
        before = self.files()

        foreign = self.request("GET", self.filtered(identifier), TOKEN_B)
        absent = self.request("GET", self.filtered(ABSENT_ID), TOKEN_B)
        self.assertEqual((foreign.status, foreign.json()), (200, {"plans": []}))
        self.assertEqual((absent.status, absent.body), (foreign.status, foreign.body))

        foreign = self.request("GET", plan_a["schedule_csv_url"].removeprefix("/api/v1"), TOKEN_B)
        absent = self.request("GET", "/study-plans/" + ABSENT_NAME, TOKEN_B)
        self.assertEqual((foreign.status, foreign.json()["error"]["code"]), (404, "PLAN_NOT_FOUND"))
        self.assertEqual((absent.status, absent.body), (foreign.status, foreign.body))
        self.assert_no_store(foreign)

        for mode, payload in self.adjustment_payloads(plan_a, identifier).items():
            with self.subTest(mode):
                foreign = self.request("POST", "/schedules/adjust", TOKEN_B, payload)
                absent = self.request("POST", "/schedules/adjust", TOKEN_B, {**payload, "schedule_csv_file": ABSENT_ID})
                self.assertEqual((foreign.status, foreign.json()["error"]["code"]), (404, "PLAN_NOT_FOUND"))
                self.assertEqual((absent.status, absent.body), (foreign.status, foreign.body))
                self.assert_no_store(foreign)
        self.assertEqual(self.files(), before)
        self.assertFalse(self.owner_dir(TOKEN_B).exists())
        self.assertEqual(ForbiddenAdjustmentAgent.constructed, 0)
        self.assertEqual(len(StubPlanningAgent.requests), 1)

    def test_owner_moves_its_plan_and_reads_back_the_same_identity(self):
        plan_a = self.generate(TOKEN_A)
        plan_b = self.generate(TOKEN_B)
        b_files = {name: data for name, data in self.files().items() if name.startswith(owner_digest(TOKEN_B))}
        metadata = next(self.owner_dir(TOKEN_A).glob("*.meta.json"))
        metadata_bytes = metadata.read_bytes()

        moved = self.request("POST", "/schedules/adjust", TOKEN_A,
                             self.adjustment_payloads(plan_a, plan_a["schedule_csv_file"])["manual"])
        self.assertEqual(moved.status, 200, moved.body)
        result = moved.json()
        self.assertEqual((result["schedule_csv_file"], result["schedule_csv_url"]),
                         (plan_a["schedule_csv_file"], plan_a["schedule_csv_url"]))
        self.assertEqual(next(row for row in result["schedules"] if row["schedule_id"] == "study-2")["start_date"],
                         "2026/10/26/18/00")
        self.assert_no_store(moved)
        download = self.request("GET", plan_a["schedule_csv_url"].removeprefix("/api/v1"), TOKEN_A)
        self.assertEqual(download.body.decode("utf-8"), result["schedule_csv"])
        [item] = self.request("GET", self.filtered(plan_a["schedule_csv_file"]), TOKEN_A).json()["plans"]
        self.assertEqual(item["schedules"], result["schedules"])
        self.assertEqual(metadata.read_bytes(), metadata_bytes)
        self.assertEqual({name: data for name, data in self.files().items()
                          if name.startswith(owner_digest(TOKEN_B))}, b_files)
        self.assertEqual([p["schedule_csv_file"] for p in self.request("GET", "/study-plans", TOKEN_B).json()["plans"]],
                         [plan_b["schedule_csv_file"]])

    def test_stateless_automatic_adjustment_needs_a_token_and_saves_nothing(self):
        rows = [{"schedule_id": "study-1", "start_date": "2026/10/19/18/00", "end_date": "2026/10/19/19/00",
                 "topic": "스프레드시트 기본"},
                {"schedule_id": "external-7", "start_date": "2026/10/23/18/00", "end_date": "2026/10/23/19/00",
                 "topic": "외부 일정"}]
        payload = {"schedule_id": "external-7", "schedules": rows, "weekly_availability": WEEKLY}
        self.assert_invalid_token(self.request("POST", "/schedules/adjust", None, payload))
        with patch.object(api_service, "AdjustmentAgent", CancellingAdjustmentAgent):
            response = self.request("POST", "/schedules/adjust", TOKEN_A, payload)
        self.assertEqual(response.status, 200, response.body)
        self.assertEqual([row["schedule_id"] for row in response.json()["schedules"]], ["study-1"])
        self.assertNotIn("schedule_csv_file", response.json())
        self.assert_no_store(response)
        self.assertEqual(list(self.plan_root.iterdir()), [])

    # Credential validation

    def test_every_protected_route_rejects_missing_and_malformed_credentials_first(self):
        plan_a = self.generate(TOKEN_A)
        before = self.files()
        routes = {
            "list": ("GET", "/study-plans", None),
            "filtered readback": ("GET", self.filtered(plan_a["schedule_csv_file"]), None),
            "invalid filter": ("GET", "/study-plans?schedule_csv_file=", None),
            "download": ("GET", plan_a["schedule_csv_url"].removeprefix("/api/v1"), None),
            "generation": ("POST", "/study-plans", json.dumps(generation_request()).encode()),
            "generation with invalid body": ("POST", "/study-plans", b"not json"),
            "adjustment": ("POST", "/schedules/adjust", json.dumps(
                self.adjustment_payloads(plan_a, plan_a["schedule_csv_file"])["manual"]).encode()),
            "adjustment with invalid body": ("POST", "/schedules/adjust", b"[]"),
        }
        credentials = {
            "missing": [],
            "empty": [("Authorization", "")],
            "scheme only": [("Authorization", "Bearer")],
            "other scheme": [("Authorization", f"Basic {TOKEN_A}")],
            "uppercase hex": [("Authorization", f"Bearer {TOKEN_A.upper()}")],
            "short": [("Authorization", f"Bearer {TOKEN_A[:-1]}")],
            "long": [("Authorization", f"Bearer {TOKEN_A}0")],
            "non-hex": [("Authorization", "Bearer " + "g" * 64)],
            "two tokens": [("Authorization", f"Bearer {TOKEN_A} {TOKEN_B}")],
            "combined": [("Authorization", f"Bearer {TOKEN_A}, Bearer {TOKEN_B}")],
            "trailing space": [("Authorization", f"Bearer {TOKEN_A} ")],
            "double space": [("Authorization", f"Bearer  {TOKEN_A}")],
            "duplicate fields": [("Authorization", f"Bearer {TOKEN_A}"), ("Authorization", f"Bearer {TOKEN_A}")],
        }
        for route, (method, path, body) in routes.items():
            for label, headers in credentials.items():
                with self.subTest(route=route, credential=label):
                    self.assert_invalid_token(self.request(method, path, headers=headers, body=body))
        self.assertEqual(self.files(), before)
        self.assertEqual((len(StubPlanningAgent.requests), ForbiddenAdjustmentAgent.constructed), (1, 0))

    def test_bearer_scheme_is_case_insensitive(self):
        plan_a = self.generate(TOKEN_A)
        response = self.request("GET", "/study-plans", headers=[("Authorization", f"bEaReR {TOKEN_A}")])
        self.assertEqual([item["schedule_csv_file"] for item in response.json()["plans"]],
                         [plan_a["schedule_csv_file"]])

    def test_public_metadata_and_preflight_need_no_token(self):
        for path in ("/health", "/certifications", "/certifications/computer_specialist_level_2/questions"):
            with self.subTest(path):
                self.assertEqual(self.request("GET", path).status, 200)
        preflight = self.request("OPTIONS", "/study-plans", headers=[
            ("Origin", ORIGIN), ("Access-Control-Request-Method", "POST"),
            ("Access-Control-Request-Headers", "authorization, content-type")])
        self.assertEqual(preflight.status, 204)
        self.assertEqual(self.header(preflight, "Access-Control-Allow-Origin"), ORIGIN)
        allowed = {name.strip().lower() for name in self.header(preflight, "Access-Control-Allow-Headers").split(",")}
        self.assertTrue({"authorization", "content-type"} <= allowed)
        self.assertEqual(list(self.plan_root.iterdir()), [])

    # Storage layout

    def test_symlinked_owner_directory_is_rejected_without_using_its_target(self):
        plan_a = self.generate(TOKEN_A)
        self.owner_dir(TOKEN_LINKED).symlink_to(self.owner_dir(TOKEN_A), target_is_directory=True)
        before = self.files()
        requests = {
            "list": ("GET", "/study-plans", None),
            "filtered readback": ("GET", self.filtered(plan_a["schedule_csv_file"]), None),
            "download": ("GET", plan_a["schedule_csv_url"].removeprefix("/api/v1"), None),
            "generation": ("POST", "/study-plans", generation_request()),
            "adjustment": ("POST", "/schedules/adjust",
                           self.adjustment_payloads(plan_a, plan_a["schedule_csv_file"])["manual"]),
        }
        for label, (method, path, payload) in requests.items():
            with self.subTest(label):
                response = self.request(method, path, TOKEN_LINKED, payload)
                self.assertEqual((response.status, response.json()["error"]["code"]), (500, "STORAGE_ERROR"))
        self.assertEqual(self.files(), before)
        self.assertEqual(len(StubPlanningAgent.requests), 1)

    def test_plans_at_the_old_shared_root_are_unreachable(self):
        legacy_csv = render_schedule_csv(parse_schedule_rows([
            {"schedule_id": "study-1", "start_date": "2026/10/19/18/00", "end_date": "2026/10/19/19/00",
             "topic": "스프레드시트 기본"}]))
        legacy = StudyPlanStore(self.plan_root).create(legacy_csv, datetime(2026, 11, 2, 18, 0))
        legacy_name = legacy.rsplit("/", 1)[1]
        self.generate(TOKEN_A)
        before = {name: data for name, data in self.files().items() if "/" not in name}
        self.assertEqual(len(before), 2)

        listing = self.request("GET", "/study-plans", TOKEN_A).json()["plans"]
        self.assertNotIn(legacy, [item["schedule_csv_file"] for item in listing])
        self.assertEqual(self.request("GET", self.filtered(legacy), TOKEN_A).json(), {"plans": []})
        self.assertEqual(self.request("GET", "/study-plans/" + legacy_name, TOKEN_A).json()["error"]["code"],
                         "PLAN_NOT_FOUND")
        adjust = self.request("POST", "/schedules/adjust", TOKEN_A, {
            "mode": "manual", "schedule_csv_file": legacy, "schedule_id": "study-1",
            "schedules": parse_rows(legacy_csv), "new_start_date": "2026/10/21/18/00"})
        self.assertEqual((adjust.status, adjust.json()["error"]["code"]), (404, "PLAN_NOT_FOUND"))
        self.assertEqual({name: data for name, data in self.files().items() if "/" not in name}, before)

    def test_retention_is_counted_per_owner_and_ignores_orphan_sidecars(self):
        csv_text = render_schedule_csv(parse_schedule_rows([
            {"schedule_id": "study-1", "start_date": "2026/10/19/18/00", "end_date": "2026/10/19/19/00",
             "topic": "스프레드시트 기본"}]))
        exam = datetime(2026, 11, 2, 18, 0)
        store_a = StudyPlanStore.for_owner(self.plan_root, TOKEN_A)
        store_b = StudyPlanStore.for_owner(self.plan_root, TOKEN_B)
        identifiers = [store_a.create(csv_text, exam) for _ in range(study_plan_store.MAX_STUDY_PLAN_CSV_FILES)]
        for index, identifier in enumerate(identifiers):
            os.utime(store_a.directory / identifier.rsplit("/", 1)[1], ns=(10 ** 18 + index, 10 ** 18 + index))
        b_plans = [store_b.create(csv_text, exam) for _ in range(2)]
        orphan = store_a.directory / "study_plan_20000101T000000_00000000.meta.json"
        orphan.write_text('{"exam_date":"2026/11/02/18/00"}', encoding="utf-8")
        legacy = StudyPlanStore(self.plan_root).create(csv_text, exam)
        b_before = {name: data for name, data in self.files().items() if name.startswith(owner_digest(TOKEN_B))}

        new_plan = self.generate(TOKEN_A)

        oldest = store_a.directory / identifiers[0].rsplit("/", 1)[1]
        self.assertFalse(oldest.exists())
        self.assertFalse(oldest.with_name(oldest.stem + ".meta.json").exists())
        listed = [item["schedule_csv_file"] for item in self.request("GET", "/study-plans", TOKEN_A).json()["plans"]]
        self.assertEqual(len(listed), study_plan_store.MAX_STUDY_PLAN_CSV_FILES)
        self.assertEqual(set(listed), set(identifiers[1:]) | {new_plan["schedule_csv_file"]})
        self.assertTrue(orphan.exists())
        self.assertEqual({name: data for name, data in self.files().items()
                          if name.startswith(owner_digest(TOKEN_B))}, b_before)
        self.assertEqual(len(b_plans), 2)
        self.assertTrue((self.plan_root / legacy.rsplit("/", 1)[1]).exists())

    def test_ownership_survives_a_server_restart(self):
        plan_a = self.generate(TOKEN_A)
        self.start_server()
        [item] = self.request("GET", self.filtered(plan_a["schedule_csv_file"]), TOKEN_A).json()["plans"]
        self.assertEqual((item["schedule_csv_file"], item["schedules"], item["availability_snapshot"]),
                         (plan_a["schedule_csv_file"], plan_a["schedules"], plan_a["availability_snapshot"]))
        self.assertEqual(self.request("GET", self.filtered(plan_a["schedule_csv_file"]), TOKEN_FRESH).json(),
                         {"plans": []})
        self.assertEqual(self.request("GET", plan_a["schedule_csv_url"].removeprefix("/api/v1"),
                                      TOKEN_FRESH).status, 404)

    # Credential containment

    def test_raw_token_stays_out_of_storage_logs_responses_and_planning(self):
        captured_logs = io.StringIO()
        log_handler = logging.StreamHandler(captured_logs)
        root_logger = logging.getLogger()
        root_logger.addHandler(log_handler)
        self.addCleanup(root_logger.removeHandler, log_handler)
        stderr = io.StringIO()
        with contextlib.redirect_stderr(stderr):
            plan_a = self.generate(TOKEN_A)
            self.request("GET", "/study-plans", TOKEN_A)
            self.request("GET", self.filtered(plan_a["schedule_csv_file"]), TOKEN_A)
            self.request("GET", plan_a["schedule_csv_url"].removeprefix("/api/v1"), TOKEN_A)
            self.request("POST", "/schedules/adjust", TOKEN_A,
                         self.adjustment_payloads(plan_a, plan_a["schedule_csv_file"])["manual"])
            self.request("POST", "/schedules/adjust", TOKEN_A, {"schedule_id": "study-1", "schedules": "bad"})
            self.request("GET", "/study-plans/" + ABSENT_NAME, TOKEN_A)
            self.request("POST", "/schedules/adjust", TOKEN_B,
                         self.adjustment_payloads(plan_a, plan_a["schedule_csv_file"])["manual"])
            self.request("GET", "/study-plans", headers=[("Authorization", f"Bearer {TOKEN_A.upper()}")])
        self.assertIn("/api/v1/study-plans", stderr.getvalue())

        secrets = {TOKEN_A, TOKEN_A.upper(), TOKEN_B}
        digests = {owner_digest(TOKEN_A), owner_digest(TOKEN_B)}
        stored = self.files()
        for secret in secrets:
            for name, data in stored.items():
                self.assertNotIn(secret, name)
                self.assertNotIn(secret.encode(), data)
            self.assertNotIn(secret, stderr.getvalue())
            self.assertNotIn(secret, captured_logs.getvalue())
            self.assertNotIn(secret, repr(StubPlanningAgent.requests))
        for response in self.responses:
            for value in [response.body.decode("utf-8")] + [f"{key}: {value}" for key, value in response.headers]:
                for secret in secrets | digests:
                    self.assertNotIn(secret, value)
            self.assert_no_store(response)
        self.assertEqual({response.status for response in self.responses}, {200, 401, 404, 422})


def plan_request_value(field):
    return generation_request()[field]


def parse_rows(csv_text):
    return list(csv.DictReader(io.StringIO(csv_text)))


if __name__ == "__main__":
    unittest.main()
