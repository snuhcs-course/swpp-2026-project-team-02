"""HTTP plan-generation outcomes with real tools, a scripted model and a temporary store."""
import contextlib
import copy
import csv
import io
import itertools
import json
import os
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
from assessment_service import _onboarding_selection
from license_planner.agent import PlanningAgent

RECORDED = json.loads((Path(__file__).with_name("fixtures") / "recorded_model_tool_calls.json")
                      .read_text(encoding="utf-8"))
SUCCESS_FIELDS = {"schedules", "schedule_csv", "schedule_csv_file", "schedule_csv_url", "agent_summary"}
CALL_IDS = itertools.count(1)
REAL_ASK_MODEL = PlanningAgent._ask_model
WIRE_DATETIME_FORMAT = "%Y/%m/%d/%H/%M"


def model_reply(name, args):
    return {"status": "completed", "output": [{"type": "function_call", "call_id": f"call_{next(CALL_IDS)}",
                                               "name": name, "arguments": json.dumps(args, ensure_ascii=False)}]}


@contextlib.contextmanager
def missing_openrouter_key():
    """Run the real model seam with no OpenRouter key in the environment or local files; a legacy key stays set."""
    with tempfile.TemporaryDirectory() as temp, patch.dict(os.environ, {"GEMINI_API_KEY": "legacy-key"}), \
            patch("license_planner.settings.DOTENV_PATH", Path(temp) / ".env"), \
            patch.object(PlanningAgent, "_ask_model", REAL_ASK_MODEL):
        os.environ.pop("OPENROUTER_API_KEY", None)
        yield


class PlanGenerationHttpTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.store = Path(self.temp.name)
        self.existing = self.store / "existing-plan.csv"
        self.existing.write_text("schedule_id,start_date,end_date,topic\n", encoding="utf-8")
        self.model_requests = 0
        self.replies = []

        def ask_model(agent, input_items, tools):
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
                   patch.object(PlanningAgent, "_ask_model", ask_model)]
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

    def get(self, path):
        with urlopen(f"{self.base}{path}", timeout=10) as response:
            return response.status, json.loads(response.read())

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

        with missing_openrouter_key():
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
                csv_name = Path(body["schedule_csv_file"]).name
                metadata_name = Path(csv_name).stem + ".meta.json"
                self.assertEqual(self.saved_files(), sorted([csv_name, metadata_name]))
                self.assertEqual(json.loads((self.store / metadata_name).read_text(encoding="utf-8")),
                                 {"exam_date": recorded["request"]["exam_date"]})
                stored = (self.store / csv_name).read_text(encoding="utf-8")
                self.assertEqual(stored, body["schedule_csv"])
                self.assertEqual(list(csv.DictReader(io.StringIO(stored))), body["schedules"])
                external = {f"external-{row['schedule_id']}" for row in recorded["request"]["busy_schedules"]}
                self.assertEqual({row["schedule_id"] for row in body["schedules"]
                                  if row["schedule_id"].startswith("external-")}, external)

    def test_busy_schedule_is_excluded_from_study_blocks_over_http(self):
        """The JSON busy_schedules contract reaches the scheduler, not just the model prompt."""
        recorded = RECORDED["topic_errors_then_build_on_last_request"]
        payload = copy.deepcopy(recorded["request"])
        payload.update({
            "preparation_start": "2026/10/19/18/00",
            "exam_date": "2026/10/19/20/00",
            "weekly_availability": {"monday": [{"start": "18:00", "end": "20:00"}]},
            "busy_schedules": [{
                "schedule_id": "55",
                "start_date": "2026/10/19/18/00",
                "end_date": "2026/10/19/19/00",
                "topic": "고정 외부 일정",
            }],
        })
        profile, analysis = recorded["model_tool_calls"][:2]
        topics = ["스프레드시트 기본", "함수와 데이터 관리", "차트와 분석"]
        self.replies = [
            model_reply(profile["name"], profile["args"]),
            model_reply(analysis["name"], analysis["args"]),
            model_reply("build_study_schedule", {"daily_topic_minutes": [
                {"date": "2026/10/19", "topic": topic, "minutes": 20} for topic in topics
            ]}),
        ]

        sent_inputs = []
        scripted = PlanningAgent._ask_model

        def recording_ask_model(agent, input_items, tools):
            sent_inputs.append(json.dumps(input_items, ensure_ascii=False))
            return scripted(agent, input_items, tools)

        with patch.object(PlanningAgent, "_ask_model", recording_ask_model):
            status, body = self.post(payload)

        self.assertEqual(status, 200, body)
        self.assertEqual(self.model_requests, 3)
        busy = payload["busy_schedules"][0]
        for sent in sent_inputs:
            self.assertNotIn("busy_schedules", sent)
            self.assertNotIn(busy["topic"], sent)
            self.assertNotIn(busy["end_date"], sent)
        external = {"schedule_id": "external-55", "start_date": busy["start_date"],
                    "end_date": busy["end_date"], "topic": busy["topic"]}
        self.assertIn(external, body["schedules"])
        self.assertEqual(list(csv.DictReader(io.StringIO(body["schedule_csv"]))), body["schedules"])
        self.assertEqual((self.store / Path(body["schedule_csv_file"]).name).read_text(encoding="utf-8"),
                         body["schedule_csv"])
        busy_start = datetime.strptime(payload["busy_schedules"][0]["start_date"], WIRE_DATETIME_FORMAT)
        busy_end = datetime.strptime(payload["busy_schedules"][0]["end_date"], WIRE_DATETIME_FORMAT)
        study_rows = [row for row in body["schedules"] if row["schedule_id"].startswith("study-")]
        self.assertEqual(len(study_rows), 3)
        self.assertEqual(study_rows[0]["start_date"], "2026/10/19/19/00")
        for row in study_rows:
            start = datetime.strptime(row["start_date"], WIRE_DATETIME_FORMAT)
            end = datetime.strptime(row["end_date"], WIRE_DATETIME_FORMAT)
            self.assertFalse(start < busy_end and busy_start < end, row)

    def test_fully_busy_window_stops_before_model_call_over_http(self):
        """Calendar conflicts that remove all capacity retain the existing input-error recovery path."""
        payload = copy.deepcopy(RECORDED["one_minute_with_three_assessed_topics"]["request"])
        payload.update({
            "preparation_start": "2026/10/19/18/00",
            "exam_date": "2026/10/19/19/00",
            "weekly_availability": {"monday": [{"start": "18:00", "end": "19:00"}]},
            "busy_schedules": [{
                "schedule_id": "56",
                "start_date": "2026/10/19/18/00",
                "end_date": "2026/10/19/19/00",
                "topic": "고정 외부 일정",
            }],
        })

        status, body = self.post(payload)

        self.assertEqual(status, 422, body)
        self.assertEqual(body["error"]["code"], "INVALID_INPUT")
        self.assertIn("NOT_ENOUGH_TIME", body["error"]["message"])
        self.assertEqual(self.model_requests, 0)
        self.assertEqual(self.saved_files(), [])

    def test_unsaved_exam_metadata_returns_storage_error_without_a_plan(self):
        recorded = RECORDED["topic_errors_then_build_on_last_request"]
        self.script(recorded)
        real_replace = os.replace

        def fail_metadata(source, target):
            if str(target).endswith(".meta.json"):
                raise PermissionError("locked")
            return real_replace(source, target)

        with patch("study_plan_store.os.replace", fail_metadata):
            status, body = self.post(recorded["request"])

        self.assertEqual((status, body["error"]["code"]), (500, "STORAGE_ERROR"))
        self.assertFalse(SUCCESS_FIELDS & body.keys())
        self.assertEqual(self.saved_files(), [])

    def test_upstream_and_repeated_failures_are_not_reported_as_infeasible(self):
        request = RECORDED["topic_errors_then_build_on_last_request"]["request"]
        calls = RECORDED["topic_errors_then_build_on_last_request"]["model_tool_calls"]
        repeated_builds = [model_reply("build_study_schedule", calls[2]["args"]) for _ in range(3)]
        scenarios = {
            "provider error": [RuntimeError("OpenRouter request failed: rate limited (HTTP 429)")],
            "repeated failing build": [model_reply(calls[1]["name"], calls[1]["args"]), *repeated_builds],
        }
        for label, replies in scenarios.items():
            with self.subTest(label):
                self.replies = replies
                status, body = self.post(request)
                self.assertEqual(status, 502, body)
                self.assertEqual(body["error"]["code"], "UPSTREAM_ERROR")
                self.assertNotIn("NOT_ENOUGH_TIME", body["error"]["message"])
                self.assertEqual(self.saved_files(), [])
        self.assertEqual(self.replies, repeated_builds[2:])

        with missing_openrouter_key():
            status, body = self.post(request)
        self.assertEqual((status, body["error"]["code"]), (503, "UPSTREAM_ERROR"))
        self.assertEqual(self.saved_files(), [])

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

        def recording_ask_model(agent, input_items, tools):
            sent_tools.append(tools)
            return self.replies.pop(0)

        with patch.object(PlanningAgent, "_ask_model", recording_ask_model):
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

    def test_complete_onboarding_selection_plans_every_reviewed_topic_over_http(self):
        status, bank = self.get("/certifications/computer_specialist_level_2/questions")
        self.assertEqual(status, 200, bank)
        computer_general = {"컴퓨터 시스템 활용", "인터넷 자료 활용", "컴퓨터 시스템 보호"}
        reviewed = {question["id"]: question["topic"]
                    for question in _onboarding_selection("computer_specialist_level_2")[1]}
        payload = copy.deepcopy(RECORDED["topic_errors_then_build_on_last_request"]["request"])
        payload["assessment_results"]["results"] = [
            {"problem_id": question["id"], "question": question["prompt"], "choices": question["choices"],
             "correct_answer": question["correct_answer"],
             "user_answer": question["correct_answer"] if reviewed[question["id"]] not in computer_general
             else next(key for key in question["choices"] if key != question["correct_answer"]),
             "is_correct": reviewed[question["id"]] not in computer_general,
             "possible_score": question["possible_score"]}
            for question in bank["questions"]]
        topics = list(dict.fromkeys(reviewed.values()))
        days = ("2026/10/19", "2026/10/21", "2026/10/23")
        self.replies = [model_reply("analyze_exam_results", {}),
                        model_reply("build_study_schedule", {"daily_topic_minutes": [
                            {"date": days[index % len(days)], "topic": topic, "minutes": 30}
                            for index, topic in enumerate(topics)]})]

        status, body = self.post(payload)

        self.assertEqual(status, 200, body)
        self.assertEqual(len(bank["questions"]), 18)
        self.assertEqual(len(topics), 8)
        self.assertEqual({row["topic"] for row in body["schedules"]}, set(topics))
        self.assertEqual([call["tool"] for call in body["tool_calls"]],
                         ["analyze_exam_results", "build_study_schedule"])
        self.assertEqual(self.replies, [])
        self.assertEqual(len(self.saved_files()), 2)


if __name__ == "__main__":
    unittest.main()
