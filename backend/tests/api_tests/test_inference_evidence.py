"""Scenario inference evidence names the model each agent actually requested, or none without a request."""
import copy
import io
import itertools
import json
import os
import tempfile
import threading
import unittest
from http.server import ThreadingHTTPServer
from pathlib import Path
from unittest.mock import patch
from urllib.error import HTTPError
from urllib.request import urlopen as REAL_URLOPEN

import api_service
import openrouter_client
from api_server import DEFAULT_API_CONFIG, make_handler
from openrouter_client import inference_summary
from scenarios import run_license_planner_test_cases as planner_runner
from scenarios import run_schedule_adjuster_test_cases as adjuster_runner

RECORDED = json.loads((Path(__file__).with_name("fixtures") / "recorded_model_tool_calls.json")
                      .read_text(encoding="utf-8"))["topic_errors_then_build_on_last_request"]
OVERRIDE_MODEL = "vendor/override-model"
LATER_MODEL = "vendor/later-model"
RETURNED_MODEL = "provider/routed-model-id"
CALL_IDS = itertools.count(1)
SCHEDULE_CSV = """schedule_id,start_date,end_date,topic
external-10,2026/10/06/18/00,2026/10/06/20/00,
study-11,2026/10/05/19/00,2026/10/05/20/00,Charts
"""


def tool_reply(name, args):
    return {"status": "completed", "model": RETURNED_MODEL,
            "usage": {"input_tokens": 10, "output_tokens": 2, "total_tokens": 12},
            "output": [{"type": "function_call", "call_id": f"call_{next(CALL_IDS)}", "name": name,
                        "arguments": json.dumps(args, ensure_ascii=False)}]}


def rejection(request):
    return HTTPError(request.full_url, 429, "rejected", {}, io.BytesIO(b'{"error": {"message": "quota"}}'))


class ScriptedProvider:
    """Replaces urlopen for OpenRouter only; returns scripted replies or rejections, recording each model sent.

    The planner runner's own requests to the local API pass through to the real urlopen.
    """

    def __init__(self):
        self.replies = []
        self.models = []

    def __call__(self, request, timeout=None):
        if request.full_url != openrouter_client.OPENROUTER_RESPONSES_URL:
            return REAL_URLOPEN(request, timeout=timeout)
        self.models.append(json.loads(request.data)["model"])
        if not self.replies:
            raise AssertionError("unexpected provider request")
        outcome = self.replies.pop(0)
        if outcome is rejection:
            raise rejection(request)
        body = json.dumps(outcome).encode()

        class Response:
            def __enter__(self):
                return self

            def __exit__(self, *exc):
                return False

            def read(self):
                return body
        return Response()


class EvidenceTestCase(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name)
        self.results = self.root / "results"
        self.results.mkdir()
        self.provider = ScriptedProvider()
        for patcher in (patch.dict(os.environ, {"OPENROUTER_API_KEY": "synthetic-openrouter-key",
                                                "OPENROUTER_MODEL": OVERRIDE_MODEL}),
                        patch("license_planner.settings.DOTENV_PATH", self.root / "planner.env"),
                        patch("schedule_adjuster.settings.DOTENV_PATH", self.root / "program.env"),
                        patch("schedule_adjuster.settings.WORKSPACE_DOTENV_PATH", self.root / "workspace.env"),
                        patch.object(openrouter_client.urllib.request, "urlopen", self.provider)):
            patcher.start()
            self.addCleanup(patcher.stop)

    def trace(self, case_id):
        return json.loads((self.results / f"{case_id}_tool_trace.json").read_text(encoding="utf-8"))


class InferenceSummaryTests(unittest.TestCase):
    def test_summary_reports_the_given_selection_only_for_attempted_requests(self):
        usage = [{"model": RETURNED_MODEL, "input_tokens": 1}]
        self.assertEqual(inference_summary(OVERRIDE_MODEL, 2, usage, 1.23456),
                         {"provider": "openrouter", "selected_model": OVERRIDE_MODEL, "model_request_count": 2,
                          "elapsed_seconds": 1.235, "usage": usage})
        self.assertEqual(inference_summary(OVERRIDE_MODEL, 1, [], 0.5)["selected_model"], OVERRIDE_MODEL)
        self.assertIsNone(inference_summary(OVERRIDE_MODEL, 0, [], 0.5)["selected_model"])
        self.assertIsNone(inference_summary(None, 0, [], 0.5)["selected_model"])


class PlannerRunnerEvidenceTests(EvidenceTestCase):
    def setUp(self):
        super().setUp()
        store = self.root / "plans"
        store.mkdir()
        for patcher in (patch.object(planner_runner, "RESULT_DIR", self.results),
                        patch.object(planner_runner, "AGENTS", []),
                        patch.object(api_service, "STUDY_PLAN_OUTPUT_DIR", store),
                        patch.object(api_service, "PlanningAgent", planner_runner.RecordingPlanningAgent)):
            patcher.start()
            self.addCleanup(patcher.stop)
        server = ThreadingHTTPServer(("127.0.0.1", 0), make_handler(DEFAULT_API_CONFIG))
        threading.Thread(target=server.serve_forever, daemon=True).start()
        self.addCleanup(server.server_close)
        self.addCleanup(server.shutdown)
        self.api_url = f"http://127.0.0.1:{server.server_port}/api/v1/study-plans"

    def evaluate(self, case_id, request):
        case = {"case_id": case_id, "request": request, "expected_first_topic": "unused"}
        result = planner_runner.evaluate_case(self.api_url, case)
        self.assertEqual(self.trace(case_id)["inference"], result["inference"])
        return result

    def test_successful_override_records_the_requested_and_returned_models_separately(self):
        self.provider.replies = [tool_reply(call["name"], call["args"]) for call in RECORDED["model_tool_calls"]]
        result = self.evaluate("override_success", copy.deepcopy(RECORDED["request"]))
        self.assertEqual(result["http_status"], 200)
        inference = result["inference"]
        self.assertEqual((inference["selected_model"], inference["model_request_count"]), (OVERRIDE_MODEL, 6))
        self.assertEqual(self.provider.models, [OVERRIDE_MODEL] * 6)
        self.assertEqual([record["model"] for record in inference["usage"]], [RETURNED_MODEL] * 6)

    def test_failed_call_without_usage_still_records_the_requested_model(self):
        self.provider.replies = [rejection]
        result = self.evaluate("override_failure", copy.deepcopy(RECORDED["request"]))
        self.assertEqual(result["http_status"], 502)
        self.assertEqual(result["inference"]["selected_model"], OVERRIDE_MODEL)
        self.assertEqual((result["inference"]["model_request_count"], result["inference"]["usage"]), (1, []))

    def test_cases_without_an_agent_or_a_model_request_record_no_model(self):
        reversed_dates = {**copy.deepcopy(RECORDED["request"]),
                          "preparation_start": "2026/12/01/00/00", "exam_date": "2026/11/01/00/00"}
        no_capacity = {**copy.deepcopy(RECORDED["request"]),
                       "preparation_start": "2026/10/19/17/00", "exam_date": "2026/10/19/18/00"}
        cases = (("invalid_input", reversed_dates, None, 422, 0),
                 ("no_capacity", no_capacity, None, 422, 0),
                 ("missing_key", copy.deepcopy(RECORDED["request"]), "OPENROUTER_API_KEY", 503, 1))
        for case_id, request, removed_variable, status, agent_count in cases:
            with self.subTest(case_id), patch.dict(os.environ):
                if removed_variable:
                    os.environ.pop(removed_variable)
                agents_before = len(planner_runner.AGENTS)
                result = self.evaluate(case_id, request)
                self.assertEqual(result["http_status"], status)
                self.assertEqual(len(planner_runner.AGENTS) - agents_before, agent_count)
                self.assertEqual((result["inference"]["selected_model"], result["inference"]["model_request_count"],
                                  result["inference"]["usage"]), (None, 0, []))
        self.assertEqual(self.provider.models, [])


class AdjusterRunnerEvidenceTests(EvidenceTestCase):
    def setUp(self):
        super().setUp()
        inputs = self.root / "inputs"
        inputs.mkdir()
        (inputs / "plan.csv").write_text(SCHEDULE_CSV, encoding="utf-8")
        for patcher in (patch.object(adjuster_runner, "RESULT_DIR", self.results),
                        patch.object(adjuster_runner, "INPUT_DIR", inputs),
                        patch.object(adjuster_runner, "USER_AVAILABILITY_PATH", self.root / "absent.json"),
                        patch("sys.argv", ["run_schedule_adjuster_test_cases"])):
            patcher.start()
            self.addCleanup(patcher.stop)

    def test_reused_agent_cases_record_the_retained_model_or_none_without_requests(self):
        cancel = {"schedule_id": "external-10", "schedule_csv": "plan.csv", "expected_action": "cancelled_external",
                  "expected_change_tool": "remove_cancelled_external_schedule"}
        cases = [{"case_id": "first_success", **cancel},
                 {"case_id": "missing_input", **cancel, "schedule_csv": "absent.csv"},
                 {"case_id": "later_failure", **cancel}]
        self.provider.replies = [tool_reply("inspect_schedule", {"schedule_id": "external-10"}),
                                 tool_reply("remove_cancelled_external_schedule", {"schedule_id": "external-10"}),
                                 rejection]
        original_run_case = adjuster_runner.run_case

        def changing_configuration_between_cases(agent, case):
            if case["case_id"] != "first_success":
                os.environ["OPENROUTER_MODEL"] = LATER_MODEL
            return original_run_case(agent, case)

        with patch.object(adjuster_runner, "load_cases", return_value=cases), \
                patch.object(adjuster_runner, "run_case", changing_configuration_between_cases), \
                patch("builtins.print"):
            self.assertEqual(adjuster_runner.main(), 1)
        summary = json.loads((self.results / "schedule_adjuster_test_summary.json").read_text(encoding="utf-8"))
        inference = {outcome["case_id"]: outcome["inference"] for outcome in summary}
        self.assertEqual({case_id: (item["selected_model"], item["model_request_count"], len(item["usage"]))
                          for case_id, item in inference.items()},
                         {"first_success": (OVERRIDE_MODEL, 2, 2), "missing_input": (None, 0, 0),
                          "later_failure": (OVERRIDE_MODEL, 1, 0)})
        self.assertEqual([record["model"] for record in inference["first_success"]["usage"]], [RETURNED_MODEL] * 2)
        self.assertEqual(self.provider.models, [OVERRIDE_MODEL] * 3)
        for case_id in inference:
            self.assertEqual(self.trace(case_id)["inference"], inference[case_id])


if __name__ == "__main__":
    unittest.main()
