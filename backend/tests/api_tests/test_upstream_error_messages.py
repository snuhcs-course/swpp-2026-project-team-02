"""Public HTTP error messages hide upstream diagnostics but keep useful input explanations."""
import contextlib
import copy
import io
import json
import os
import tempfile
import threading
import unittest
from http.server import ThreadingHTTPServer
from pathlib import Path
from unittest.mock import patch
from urllib.error import HTTPError
from urllib.request import Request, urlopen

import api_server
import api_service
import openrouter_client
from api_server import CONFIGURATION_MESSAGE, DEFAULT_API_CONFIG, UPSTREAM_MESSAGES, make_handler
from openrouter_client import InferenceConfigurationError
from schedule_adjuster.agent import AdjustmentAgent
from schedule_adjuster.csv_io import parse_schedule_rows, render_schedule_csv
from schedule_adjuster.settings import AGENT_MAX_STEPS
from study_plan_store import StudyPlanStore

RECORDED = json.loads((Path(__file__).with_name("fixtures") / "recorded_model_tool_calls.json")
                      .read_text(encoding="utf-8"))
GENERATION_REQUEST = RECORDED["topic_errors_then_build_on_last_request"]["request"]
SECRET_TOPIC = "private-topic-argument-7f3a"
SYNTHETIC_KEY = "synthetic-openrouter-key-0d4e"
PROVIDER_BODY = "provider-body-quota-detail-6b2f"
SENTINELS = (SECRET_TOPIC, "Traceback", "/Users/someone/backend/.env", "RESOURCE_EXHAUSTED",
             "tool trace", "calls:", SYNTHETIC_KEY, PROVIDER_BODY, "OPENROUTER_API_KEY", "openrouter")
ROWS = [
    {"schedule_id": "study-1", "start_date": "2026/10/19/18/00",
     "end_date": "2026/10/19/19/00", "topic": SECRET_TOPIC},
    {"schedule_id": "external-2", "start_date": "2026/10/19/19/00",
     "end_date": "2026/10/19/20/00", "topic": ""},
]
WINDOWS = {"monday": [{"start": "18:00", "end": "22:00"}]}
DIAGNOSTIC = (f"Agent exceeded max steps; tool trace: [{{'arguments': {{'topic': '{SECRET_TOPIC}'}}}}] "
              "OpenRouter request failed: HTTP 429 RESOURCE_EXHAUSTED Traceback at /Users/someone/backend/.env")


def inspecting_topic_forever():
    """The production adjustment loop with a provider that only ever inspects the topic."""
    class Agent(AdjustmentAgent):
        def __init__(self):
            super().__init__(api_key="dummy-key")

        def _ask_model(self, input_items):
            self.api_request_count += 1
            name, args = (("inspect_schedule", {"schedule_id": "study-1"}) if self.api_request_count == 1
                          else ("inspect_topic_schedule", {"topic": SECRET_TOPIC}))
            return {"status": "completed", "output": [{"type": "function_call", "name": name,
                                                       "call_id": f"call_{self.api_request_count}",
                                                       "arguments": json.dumps(args)}]}
    return Agent


@contextlib.contextmanager
def openrouter_key(value):
    """Use a synthetic OpenRouter key, or none, with local key files redirected to an empty directory."""
    with tempfile.TemporaryDirectory() as temp, patch.dict(os.environ, {"GEMINI_API_KEY": "legacy-key"}), \
            patch("license_planner.settings.DOTENV_PATH", Path(temp) / "planner.env"), \
            patch("schedule_adjuster.settings.DOTENV_PATH", Path(temp) / "adjuster.env"), \
            patch("schedule_adjuster.settings.WORKSPACE_DOTENV_PATH", Path(temp) / "workspace.env"):
        os.environ.pop("OPENROUTER_API_KEY", None)
        if value is not None:
            os.environ["OPENROUTER_API_KEY"] = value
        yield


class UpstreamErrorMessageTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.store = StudyPlanStore(self.root)
        self.original = render_schedule_csv(parse_schedule_rows(ROWS))
        self.identifier = self.store.create(self.original)
        self.filename = self.identifier.rsplit("/", 1)[1]
        self.adjust_payload = {"schedule_csv_file": self.identifier, "schedule_id": "study-1",
                               "schedules": copy.deepcopy(ROWS), "weekly_availability": WINDOWS}
        patcher = patch.object(api_service, "STUDY_PLAN_OUTPUT_DIR", self.root)
        patcher.start()
        self.addCleanup(patcher.stop)
        self.server = ThreadingHTTPServer(("127.0.0.1", 0), make_handler(DEFAULT_API_CONFIG))
        threading.Thread(target=self.server.serve_forever, daemon=True).start()
        self.addCleanup(self.server.server_close)
        self.addCleanup(self.server.shutdown)
        self.base = f"http://127.0.0.1:{self.server.server_port}/api/v1"

    def post(self, path, payload):
        request = Request(f"{self.base}{path}", data=json.dumps(payload).encode(),
                          headers={"Content-Type": "application/json"}, method="POST")
        try:
            with urlopen(request, timeout=10) as response:
                return response.status, json.loads(response.read())
        except HTTPError as error:
            with error:
                return error.code, json.loads(error.read())

    def assert_store_unchanged(self):
        self.assertEqual([path.name for path in self.root.iterdir()], [self.filename])
        self.assertEqual(self.store.download(self.filename)[0], self.original)

    def assert_public_error(self, response, status, message):
        self.assertEqual(response, (status, {"error": {"code": "UPSTREAM_ERROR", "message": message}}))
        for sentinel in SENTINELS:
            self.assertNotIn(sentinel, json.dumps(response[1], ensure_ascii=False))

    def test_adjustment_step_limit_does_not_expose_the_tool_trace(self):
        with patch.object(api_service, "AdjustmentAgent", inspecting_topic_forever()):
            response = self.post("/schedules/adjust", self.adjust_payload)
        self.assert_public_error(response, 502, UPSTREAM_MESSAGES["/api/v1/schedules/adjust"])
        self.assert_store_unchanged()
        with self.assertRaisesRegex(RuntimeError, f"max steps \\({AGENT_MAX_STEPS}\\).*{SECRET_TOPIC}"):
            inspecting_topic_forever()().adjust(api_service.AdjustmentRequest(
                "study-1", tuple(parse_schedule_rows(ROWS))))

    def test_provider_failures_on_both_routes_do_not_expose_details(self):
        routes = {"/study-plans": GENERATION_REQUEST, "/schedules/adjust": self.adjust_payload}
        for path, payload in routes.items():
            for status in (401, 402, 429, 503):
                attempts = []

                def rejecting_provider(request, timeout=None):
                    attempts.append(request.get_header("Authorization"))
                    raise HTTPError(request.full_url, status, PROVIDER_BODY, {},
                                    io.BytesIO(json.dumps({"error": {"message": PROVIDER_BODY}}).encode()))

                with self.subTest(path=path, status=status), openrouter_key(SYNTHETIC_KEY), \
                        patch.object(openrouter_client.urllib.request, "urlopen", rejecting_provider):
                    response = self.post(path, payload)
                    self.assert_public_error(response, 502, UPSTREAM_MESSAGES["/api/v1" + path])
                    self.assertEqual(attempts, [f"Bearer {SYNTHETIC_KEY}"], "one attempt, no retry")
        self.assert_store_unchanged()

    def test_runtime_and_configuration_failures_on_both_routes(self):
        routes = {"/study-plans": ("create_study_plan", GENERATION_REQUEST),
                  "/schedules/adjust": ("adjust_schedule", self.adjust_payload)}
        failures = [(RuntimeError(DIAGNOSTIC), 502, None),
                    (RuntimeError(f"Invalid API key; {DIAGNOSTIC}"), 502, None),
                    (InferenceConfigurationError(f"OPENROUTER_API_KEY missing; {DIAGNOSTIC}"), 503,
                     CONFIGURATION_MESSAGE)]
        for path, (handler_name, payload) in routes.items():
            for error, status, message in failures:
                with self.subTest(path=path, error=type(error).__name__, status=status), \
                        patch.object(api_server, handler_name, side_effect=error):
                    response = self.post(path, payload)
                    self.assert_public_error(response, status, message or UPSTREAM_MESSAGES["/api/v1" + path])
        self.assert_store_unchanged()

    def test_missing_openrouter_key_uses_the_configuration_message_on_both_routes(self):
        def no_request(request, timeout=None):
            raise AssertionError("no provider request without an OpenRouter key")

        for path, payload in {"/study-plans": GENERATION_REQUEST, "/schedules/adjust": self.adjust_payload}.items():
            with self.subTest(path), openrouter_key(None), \
                    patch.object(openrouter_client.urllib.request, "urlopen", no_request):
                response = self.post(path, payload)
            self.assert_public_error(response, 503, CONFIGURATION_MESSAGE)
        self.assert_store_unchanged()

    def test_input_and_plan_errors_keep_their_explanations(self):
        reversed_dates = {**GENERATION_REQUEST, "preparation_start": "2026/12/01/00/00",
                          "exam_date": "2026/11/01/00/00"}
        no_capacity = {**GENERATION_REQUEST, "preparation_start": "2026/10/19/17/00",
                       "exam_date": "2026/10/19/18/00"}
        reversed_window = {**self.adjust_payload,
                           "weekly_availability": {"monday": [{"start": "20:00", "end": "18:00"}]}}
        stale = copy.deepcopy(self.adjust_payload)
        stale["schedules"][1]["topic"] = "Changed"
        with openrouter_key(None):
            status, body = self.post("/study-plans", reversed_dates)
            self.assertEqual((status, body["error"]),
                             (422, {"code": "INVALID_INPUT", "message": "preparation_start must be before exam_date"}))
            status, body = self.post("/study-plans", no_capacity)
            self.assertEqual((status, body["error"]["code"]), (422, "INVALID_INPUT"))
            self.assertTrue(body["error"]["message"].startswith("NOT_ENOUGH_TIME: "))
        status, body = self.post("/schedules/adjust", reversed_window)
        self.assertEqual((status, body["error"]["code"]), (422, "INVALID_INPUT"))
        self.assertIn("monday", body["error"]["message"])
        status, body = self.post("/schedules/adjust", stale)
        self.assertEqual((status, body["error"]),
                         (409, {"code": "PLAN_CONFLICT",
                                "message": "The selected plan has changed. Reload it before adjusting."}))
        (self.root / self.filename).unlink()
        status, body = self.post("/schedules/adjust", self.adjust_payload)
        self.assertEqual((status, body["error"]["code"]), (404, "PLAN_NOT_FOUND"))
        self.assertTrue(body["error"]["message"])


if __name__ == "__main__":
    unittest.main()
