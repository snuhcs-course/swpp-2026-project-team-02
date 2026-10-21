"""HTTP plan-generation outcomes with real tools, a scripted model and a temporary store."""
import copy
import csv
import io
import json
import tempfile
import threading
import unittest
from http.server import ThreadingHTTPServer
from pathlib import Path
from unittest.mock import patch
from urllib.error import HTTPError
from urllib.request import Request, urlopen

import api_service
from api_server import DEFAULT_API_CONFIG, make_handler
from license_planner.agent import GeminiToolAgent

RECORDED = json.loads((Path(__file__).with_name("fixtures") / "recorded_model_tool_calls.json")
                      .read_text(encoding="utf-8"))
SUCCESS_FIELDS = {"schedules", "schedule_csv", "schedule_csv_file", "schedule_csv_url", "agent_summary"}


def model_reply(name, args):
    return {"candidates": [{"content": {"role": "model", "parts": [
        {"functionCall": {"name": name, "args": args}}]}}]}


class PlanGenerationHttpTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.store = Path(self.temp.name)
        self.existing = self.store / "existing-plan.csv"
        self.existing.write_text("schedule_id,start_date,end_date,topic\n", encoding="utf-8")
        self.model_requests = 0
        self.replies = []

        def ask_model(agent, contents, tools):
            self.model_requests += 1
            if not self.replies:
                raise AssertionError("unexpected model request")
            reply = self.replies.pop(0)
            if isinstance(reply, Exception):
                raise reply
            return reply

        def no_profile_access(*args, **kwargs):
            raise AssertionError("the saved availability profile must not be read")

        patches =[patch.object(api_service, "STUDY_PLAN_OUTPUT_DIR", self.store),
                   patch.object(api_service, "load_personal_availability", no_profile_access),
                   patch.object(GeminiToolAgent, "_ask_model", ask_model),
                   patch("license_planner.agent.GEMINI_API_KEY", "test-key")]
        for patcher in patches:
            patcher.start()
            self.addCleanup(patcher.stop)
        self.server = ThreadingHTTPServer(("127.0.0.1", 0), make_handler(DEFAULT_API_CONFIG))
        threading.Thread(target=self.server.serve_forever, daemon=True).start()
        self.addCleanup(self.server.server_close)
        self.addCleanup(self.server.shutdown)
        self.base = f"http://127.0.0.1:{self.server.server_port}/api/v1"

    def post(self, payload):
        request = Request(f"{self.base}/study-plans", data=json.dumps(payload).encode(),
                          headers={"Content-Type": "application/json"}, method="POST")
        try:
            with urlopen(request, timeout=10) as response:
                return response.status, json.loads(response.read())
        except HTTPError as error:
            with error:
                return error.code, json.loads(error.read())

    def saved_files(self):
        return sorted(path.name for path in self.store.iterdir() if path != self.existing)

    def script(self, recorded):
        self.replies = [model_reply(call["name"], call["args"]) for call in recorded["model_tool_calls"]]

    def assert_infeasible(self, status, body, topics_phrase):
        self.assertEqual(status, 422, body)
        self.assertEqual(body["error"]["code"], "INVALID_INPUT")
        message = body["error"]["message"]
        self.assertRegex(message, f"^NOT_ENOUGH_TIME: .*{topics_phrase}")
        self.assertIn("공부 가능 시간을 늘리거나, 준비를 더 일찍 시작하거나, 시험일을 늦춰 주세요", message)
        self.assertFalse(SUCCESS_FIELDS & body.keys())
        self.assertEqual(self.saved_files(), [])
        self.assertEqual(self.existing.read_text(encoding="utf-8"), "schedule_id,start_date,end_date,topic\n")

    def test_zero_capacity_is_explained_without_provider_configuration(self):
        payload = copy.deepcopy(RECORDED["one_minute_with_three_assessed_topics"]["request"])
        payload["preparation_start"], payload["exam_date"] = "2026/10/19/17/00", "2026/10/19/18/00"

        with patch("license_planner.agent.GEMINI_API_KEY", ""):
            status, body = self.post(payload)

        self.assert_infeasible(status, body, "학습 주제 1개에도")
        self.assertEqual(self.model_requests, 0)

    def test_more_mapped_topics_than_minutes_stops_at_analysis(self):
        recorded = RECORDED["one_minute_with_three_assessed_topics"]
        self.script(recorded)

        status, body = self.post(recorded["request"])

        self.assert_infeasible(status, body, "학습 주제 3개에")
        self.assertIn("시간이 1분이라", body["error"]["message"])
        self.assertEqual(self.model_requests, 2)

    def test_recorded_builds_on_the_last_request_are_saved_and_returned(self):
        for key in ("topic_errors_then_build_on_last_request", "capacity_errors_then_build_on_last_request"):
            recorded = RECORDED[key]
            with self.subTest(key):
                for path in self.store.iterdir():
                    if path != self.existing:
                        path.unlink()
                self.model_requests = 0
                self.script(recorded)

                status, body = self.post(recorded["request"])

                self.assertEqual(status, 200, body)
                self.assertEqual(self.model_requests, 6)
                self.assertEqual(self.replies, [])
                self.assertEqual(len(body["tool_calls"]), 6)
                self.assertTrue(body["agent_summary"])
                saved = self.saved_files()
                self.assertEqual(saved, [Path(body["schedule_csv_file"]).name])
                stored = (self.store / saved[0]).read_text(encoding="utf-8")
                self.assertEqual(stored, body["schedule_csv"])
                self.assertEqual(list(csv.DictReader(io.StringIO(stored))), body["schedules"])
                external = {f"external-{row['schedule_id']}" for row in recorded["request"]["busy_schedules"]}
                self.assertEqual({row["schedule_id"] for row in body["schedules"]
                                  if row["schedule_id"].startswith("external-")}, external)

    def test_upstream_and_repeated_failures_are_not_reported_as_infeasible(self):
        request = RECORDED["topic_errors_then_build_on_last_request"]["request"]
        calls = RECORDED["topic_errors_then_build_on_last_request"]["model_tool_calls"]
        repeated_build = model_reply("build_study_schedule", calls[2]["args"])
        scenarios = {
            "provider error": [RuntimeError("Gemini request failed: HTTP 429 RESOURCE_EXHAUSTED: quota")],
            "repeated failing build": [model_reply(calls[1]["name"], calls[1]["args"]), repeated_build,
                                       repeated_build, repeated_build],
        }
        for label, replies in scenarios.items():
            with self.subTest(label):
                self.replies = replies
                status, body = self.post(request)
                self.assertEqual(status, 502, body)
                self.assertEqual(body["error"]["code"], "UPSTREAM_ERROR")
                self.assertNotIn("NOT_ENOUGH_TIME", body["error"]["message"])
                self.assertEqual(self.saved_files(), [])
        self.assertEqual(self.replies, [repeated_build])

        with patch("license_planner.agent.GEMINI_API_KEY", ""):
            status, body = self.post(request)
        self.assertEqual((status, body["error"]["code"]), (503, "UPSTREAM_ERROR"))

    def test_varied_invalid_proposals_for_feasible_input_exhaust_as_upstream_failure(self):
        recorded = RECORDED["topic_errors_then_build_on_last_request"]
        profile, analysis = recorded["model_tool_calls"][:2]
        topics = ["스프레드시트 기본", "함수와 데이터 관리", "차트와 분석"]
        proposals = [
            [{"date": "2026/10/19", "topic": topics[0], "minutes": 60}],
            [{"date": "2026/10/19", "topic": topic, "minutes": 30} for topic in (topics[0], "함수과 데이터 관리", topics[2])],
            [{"date": "2026/10/20", "topic": topic, "minutes": 30} for topic in topics],
            [{"date": "2026/10/19", "topic": topic, "minutes": 50} for topic in topics],
        ]
        sent_tools = []
        self.replies = [model_reply(profile["name"], profile["args"]), model_reply(analysis["name"], analysis["args"]),
                        *(model_reply("build_study_schedule", {"daily_topic_minutes": plan}) for plan in proposals)]

        def recording_ask_model(agent, contents, tools):
            sent_tools.append(tools)
            return self.replies.pop(0)

        with patch.object(GeminiToolAgent, "_ask_model", recording_ask_model):
            status, body = self.post(recorded["request"])

        self.assertEqual(status, 502, body)
        self.assertEqual(body["error"]["code"], "UPSTREAM_ERROR")
        self.assertNotIn("NOT_ENOUGH_TIME", body["error"]["message"])
        self.assertFalse(SUCCESS_FIELDS & body.keys())
        self.assertEqual(self.saved_files(), [])
        self.assertEqual((len(sent_tools), self.replies), (6, []))
        build = next(tool for tool in sent_tools[-1] if tool["name"] == "build_study_schedule")
        item = build["parameters"]["properties"]["daily_topic_minutes"]["items"]["properties"]
        self.assertCountEqual(item["topic"]["enum"], topics)
        self.assertNotIn("2026/10/20", item["date"]["enum"])
        self.assertIn("2026/10/19", item["date"]["enum"])


if __name__ == "__main__":
    unittest.main()
