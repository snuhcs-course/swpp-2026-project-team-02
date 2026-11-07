"""Planning agent tool protocol through the real transport with a scripted provider."""
import copy
import json
import os
import tempfile
import unittest
from dataclasses import replace
from datetime import datetime, timedelta
from pathlib import Path
from unittest.mock import patch

import openrouter_client
from api_service import build_plan_request
from license_planner.agent import AGENT_MAX_STEPS, PlanningAgent
from license_planner.settings import load_model as planner_load_model
from license_planner.tools import PlannerTools
from openrouter_client import InferenceConfigurationError, InferenceError

CHARTS = "차트와 분석"
PAYLOAD = {
    "certification_id": "computer_specialist_level_2",
    "self_assessment": "차트가 어렵습니다.",
    "preparation_start": "2026/10/05/18/00",
    "exam_date": "2026/10/20/18/00",
    "weekly_availability": {"monday": [{"start": "18:00", "end": "21:00"}]},
    "assessment_results": {"certification_id": "computer_specialist_level_2", "results": [{
        "problem_id": 1, "question": "표의 데이터로 가장 적절한 차트를 고르세요.",
        "choices": {"A": "막대형", "B": "원형"}, "correct_answer": "A", "user_answer": "B",
        "is_correct": False, "possible_score": 1}]},
}
PROFILE = ("get_certification_profile", {"certification_id": "computer_specialist_level_2"})
ANALYSIS = ("analyze_exam_results", {"problem_topics": [{"problem_id": 1, "topics": [{"topic": CHARTS, "weight": 1}]}]})
BUILD = ("build_study_schedule", {"daily_topic_minutes": [{"date": "2026/10/05", "topic": CHARTS, "minutes": 60}]})


def call(call_id, name, args):
    return {"type": "function_call", "id": f"fc_{call_id}", "call_id": call_id, "name": name,
            "arguments": json.dumps(args, ensure_ascii=False)}


def reply(*items):
    return {"status": "completed", "model": "openai/gpt-6-luna", "output": list(items),
            "usage": {"input_tokens": 10, "output_tokens": 5, "total_tokens": 15}}


class ScriptedProvider:
    """Replaces urlopen; returns scripted Responses replies and records each request body."""

    def __init__(self, replies):
        self.replies = list(replies)
        self.bodies = []

    def __call__(self, request, timeout=None):
        self.bodies.append(json.loads(request.data))
        if not self.replies:
            raise AssertionError("the agent sent a request the script does not provide")
        outcome = self.replies.pop(0)
        if isinstance(outcome, BaseException):
            raise outcome
        class Response:
            def __enter__(self):
                return self

            def __exit__(self, *exc):
                return False

            def read(self):
                return json.dumps(outcome).encode()
        return Response()


def isolate_model_configuration(test):
    """Point the planner's local configuration at an empty temporary file and clear the process model."""
    temp = tempfile.TemporaryDirectory()
    test.addCleanup(temp.cleanup)
    for patcher in (patch.dict(os.environ), patch("license_planner.settings.DOTENV_PATH", Path(temp.name) / ".env")):
        patcher.start()
        test.addCleanup(patcher.stop)
    os.environ.pop("OPENROUTER_MODEL", None)


class PlanningProtocolTests(unittest.TestCase):
    def setUp(self):
        isolate_model_configuration(self)

    def run_agent(self, replies, payload=PAYLOAD, api_key="synthetic-openrouter-key"):
        provider = ScriptedProvider(replies)
        agent = PlanningAgent(api_key=api_key)
        request = build_plan_request(copy.deepcopy(payload), require_assessment=True)
        with patch.object(openrouter_client.urllib.request, "urlopen", provider):
            try:
                return provider, agent, agent.run(request)
            except Exception as error:
                error.provider, error.agent = provider, agent
                raise

    def test_continuation_carries_reasoning_items_and_matching_observations(self):
        reasoning = {"type": "reasoning", "id": "rs_1", "summary": [], "encrypted_content": "opaque-continuation"}
        provider, agent, (tools, summary) = self.run_agent([
            reply(reasoning, call("call_profile", *PROFILE)),
            reply(call("call_analysis", *ANALYSIS)),
            reply(call("call_build", *BUILD)),
            RuntimeError("provider failure after success"),
        ])
        self.assertEqual(len(provider.bodies), 3)
        self.assertEqual(provider.replies[0].args, ("provider failure after success",))
        self.assertEqual(agent.api_request_count, 3)
        self.assertEqual([record["model"] for record in agent.usage_records], ["openai/gpt-6-luna"] * 3)
        second = provider.bodies[1]["input"]
        self.assertEqual(second[1:3], [reasoning, call("call_profile", *PROFILE)])
        self.assertEqual(second[3]["type"], "function_call_output")
        self.assertEqual(second[3]["call_id"], "call_profile")
        self.assertEqual(json.loads(second[3]["output"])["result"]["certification_id"],
                         "computer_specialist_level_2")
        guidance = json.loads(provider.bodies[2]["input"][-1]["output"])
        self.assertEqual(provider.bodies[2]["input"][-1]["call_id"], "call_analysis")
        self.assertEqual(guidance["build_requirements"]["required_topics"], [CHARTS])
        for body in provider.bodies:
            self.assertEqual((body["model"], body["reasoning"], body["max_output_tokens"]),
                             ("openai/gpt-6-luna", {"effort": "low"}, 16384))
            build, = [tool for tool in body["tools"] if tool["name"] == "build_study_schedule"]
            self.assertEqual(build["type"], "function")
        build_dates = provider.bodies[2]["tools"][2]["parameters"]["properties"]["daily_topic_minutes"]["items"]["properties"]
        self.assertEqual(build_dates["topic"]["enum"], [CHARTS])
        self.assertEqual(len(tools.schedule), 1)
        self.assertIn("60분", summary)

    def test_multiple_calls_in_one_response_run_in_order(self):
        provider, _, (tools, _) = self.run_agent([
            reply(call("call_1", *PROFILE), call("call_2", *ANALYSIS)),
            reply(call("call_3", *BUILD)),
        ])
        self.assertEqual([item["tool"] for item in tools.trace.calls],
                         ["get_certification_profile", "analyze_exam_results", "build_study_schedule"])
        outputs = [item["call_id"] for item in provider.bodies[1]["input"] if item.get("type") == "function_call_output"]
        self.assertEqual(outputs, ["call_1", "call_2"])

    def test_accepted_build_ignores_later_calls_in_the_same_response(self):
        provider, _, (tools, _) = self.run_agent([
            reply(call("call_1", *ANALYSIS), call("call_2", *BUILD),
                  call("call_3", "delete_everything", {}),
                  {"type": "function_call", "call_id": "call_4", "name": "build_study_schedule", "arguments": "["}),
        ])
        self.assertEqual(len(provider.bodies), 1)
        self.assertEqual([item["tool"] for item in tools.trace.calls], ["analyze_exam_results", "build_study_schedule"])

    def test_rejected_proposal_is_returned_to_the_model_for_correction(self):
        wrong = ("build_study_schedule", {"daily_topic_minutes": [{"date": "2026/10/05", "topic": "함수", "minutes": 60}]})
        provider, _, (tools, _) = self.run_agent([
            reply(call("call_1", *ANALYSIS)), reply(call("call_2", *wrong)), reply(call("call_3", *BUILD)),
        ])
        rejected = provider.bodies[2]["input"][-1]
        self.assertEqual(rejected["call_id"], "call_2")
        self.assertTrue(json.loads(rejected["output"])["result"].startswith("SCHEDULE_ERROR"))
        self.assertEqual(len(tools.schedule), 1)

    def test_invalid_replies_fail_as_upstream_errors_without_running_the_call(self):
        cases = {
            "invalid JSON arguments": [reply({**call("c1", *ANALYSIS), "arguments": "{\"problem_topics\": "})],
            "non-object arguments": [reply({**call("c1", *ANALYSIS), "arguments": "[]"})],
            "unsupported tool": [reply(call("c1", "write_csv", {}))],
            "missing call_id": [reply({**call("c1", *ANALYSIS), "call_id": ""})],
            "duplicate call_id": [reply(call("c1", *PROFILE)), reply(call("c1", *ANALYSIS))],
            "refusal": [reply({"type": "message", "role": "assistant", "content": [{"type": "refusal", "refusal": "no"}]})],
            "incomplete": [{"status": "incomplete", "incomplete_details": {"reason": "max_output_tokens"}, "output": []}],
        }
        for label, replies in cases.items():
            executed = []

            def spy(tools, name, arguments):
                executed.append(name)
                return original(tools, name, arguments)

            original = PlannerTools.call
            with self.subTest(label), patch.object(PlannerTools, "call", spy), \
                    self.assertRaises(InferenceError) as raised:
                self.run_agent(replies)
            self.assertNotIsInstance(raised.exception, ValueError)
            self.assertEqual(raised.exception.provider.replies, [])
            self.assertEqual(executed, ["get_certification_profile"] if label == "duplicate call_id" else [])

    def test_text_without_an_accepted_build_is_not_success(self):
        with self.assertRaises(RuntimeError) as raised:
            self.run_agent([reply(call("c1", *ANALYSIS)),
                            reply({"type": "message", "role": "assistant",
                                   "content": [{"type": "output_text", "text": "일정을 만들었습니다."}]})])
        self.assertIn("without calling build_study_schedule", str(raised.exception))

    def test_requests_stop_at_the_limit_without_a_summary_request(self):
        replies = [reply(call(f"c{index}", *PROFILE)) for index in range(AGENT_MAX_STEPS + 1)]
        with self.assertRaisesRegex(RuntimeError, f"max_steps={AGENT_MAX_STEPS}") as raised:
            self.run_agent(replies)
        provider = raised.exception.provider
        self.assertEqual(len(provider.bodies), AGENT_MAX_STEPS)
        self.assertEqual(len(provider.replies), 1)
        self.assertTrue(all(body["max_output_tokens"] == 16384 for body in provider.bodies))


TOPICS = ["스프레드시트 기본", "함수와 데이터 관리", CHARTS]
BUSY_TOPIC = "합성 비공개 회의"


def three_topic_payload(wednesday_end):
    """Monday 18:00-20:00 plus Wednesday 18:00-<end> in one preparation week."""
    results = [{"problem_id": index + 1, "question": f"합성 문항 {index + 1}", "choices": {"A": "가", "B": "나"},
                "correct_answer": "A", "user_answer": "B", "is_correct": False, "possible_score": 1}
               for index in range(3)]
    return {**PAYLOAD, "preparation_start": "2026/10/05/00/00", "exam_date": "2026/10/08/00/00",
            "weekly_availability": {"monday": [{"start": "18:00", "end": "20:00"}],
                                    "wednesday": [{"start": "18:00", "end": wednesday_end}]},
            "assessment_results": {"certification_id": "computer_specialist_level_2", "results": results}}


THREE_TOPIC_ANALYSIS = ("analyze_exam_results", {"problem_topics": [
    {"problem_id": index + 1, "topics": [{"topic": topic, "weight": 1}]} for index, topic in enumerate(TOPICS)]})
FOUR_SESSION_BUILD = ("build_study_schedule", {"daily_topic_minutes": [
    {"date": "2026/10/05", "topic": TOPICS[0], "minutes": 60},
    {"date": "2026/10/05", "topic": TOPICS[1], "minutes": 30},
    {"date": "2026/10/05", "topic": TOPICS[2], "minutes": 30},
    {"date": "2026/10/07", "topic": TOPICS[1], "minutes": 60},
]})


def busy_calendar(rows_per_day):
    """Daily 18:00-18:30 conflicts for 92 days, each day's union split into the given (start, end) minutes."""
    rows, day = [], datetime(2026, 10, 5)
    for offset in range(92):
        date = day + timedelta(days=offset)
        for start, end in rows_per_day:
            rows.append({"schedule_id": str(870000 + len(rows)),
                         "start_date": (date + timedelta(hours=18, minutes=start)).strftime("%Y/%m/%d/%H/%M"),
                         "end_date": (date + timedelta(hours=18, minutes=end)).strftime("%Y/%m/%d/%H/%M"),
                         "topic": BUSY_TOPIC})
    return rows


def dense_payload(busy):
    return {**PAYLOAD, "preparation_start": "2026/10/05/00/00", "exam_date": "2027/01/05/00/00",
            "weekly_availability": {"monday": [{"start": "18:00", "end": "21:00"}],
                                    "wednesday": [{"start": "18:00", "end": "21:00"}]},
            "busy_schedules": busy}


class PlanningSummaryAndModelInputTests(unittest.TestCase):
    setUp = PlanningProtocolTests.setUp
    run_agent = PlanningProtocolTests.run_agent

    def test_summary_sentences_are_separated_and_need_no_model_request(self):
        expected = {
            "20:00": "3개 학습 주제에 학습 일정 4개, 총 180분을 배치했습니다. 준비 기간의 공부 가능 시간은 총 240분입니다. "
                     "공부 가능 시간의 일부는 여유 시간으로 남겼습니다.",
            "19:00": "3개 학습 주제에 학습 일정 4개, 총 180분을 배치했습니다. 준비 기간의 공부 가능 시간은 총 180분입니다.",
        }
        for wednesday_end, text in expected.items():
            with self.subTest(wednesday_end):
                provider, agent, (tools, summary) = self.run_agent([
                    reply(call("c1", *THREE_TOPIC_ANALYSIS)), reply(call("c2", *FOUR_SESSION_BUILD)),
                    RuntimeError("provider failure after success"),
                ], payload=three_topic_payload(wednesday_end))
                self.assertEqual(summary, text)
                self.assertEqual((len(provider.bodies), agent.api_request_count, len(provider.replies)), (2, 2, 1))
                self.assertEqual(len(tools.schedule), 4)

    def run_dense(self, busy):
        build = ("build_study_schedule", {"daily_topic_minutes": [
            {"date": "2026/10/05", "topic": CHARTS, "minutes": 60}]})
        provider, _, (tools, _) = self.run_agent([
            reply(call("c1", *PROFILE)), reply(call("c2", *ANALYSIS)), reply(call("c3", *build)),
        ], payload=dense_payload(busy))
        return provider, tools

    def assert_no_busy_occurrences(self, bodies, busy):
        self.assertTrue(bodies)
        for step, body in enumerate(bodies):
            sent = json.dumps(body["input"], ensure_ascii=False)
            with self.subTest(step=step):
                self.assertNotIn("busy_schedules", sent)
                self.assertNotIn(BUSY_TOPIC, sent)
                for row in busy:
                    for value in (row["schedule_id"], row["start_date"], row["end_date"]):
                        self.assertNotIn(value, sent)

    def test_model_receives_date_capacities_instead_of_calendar_occurrences(self):
        sparse = busy_calendar([(0, 30)])
        dense = busy_calendar([(0, 20), (10, 30), (15, 25)])
        self.assertGreaterEqual(len(sparse), 90)
        sparse_provider, sparse_tools = self.run_dense(sparse)
        dense_provider, dense_tools = self.run_dense(dense)

        self.assertEqual(len(sparse_provider.bodies), 3)
        self.assertEqual(sparse_provider.bodies, dense_provider.bodies)
        self.assert_no_busy_occurrences(sparse_provider.bodies, sparse)
        self.assert_no_busy_occurrences(dense_provider.bodies, dense)
        metadata = json.loads(sparse_provider.bodies[0]["input"][0]["content"].split("Input metadata: ", 1)[1])
        self.assertEqual(metadata["usable_minutes_by_date"]["2026/10/05"], 150)
        self.assertEqual(metadata["usable_minutes_by_date"]["2026/10/07"], 150)
        self.assertEqual(len(metadata["usable_minutes_by_date"]), 27)
        guidance = json.loads(sparse_provider.bodies[2]["input"][-1]["output"])
        self.assertEqual(guidance["build_requirements"]["usable_minutes_by_date"], metadata["usable_minutes_by_date"])

        for busy, tools in ((sparse, sparse_tools), (dense, dense_tools)):
            intervals = [(datetime.strptime(r["start_date"], "%Y/%m/%d/%H/%M"),
                          datetime.strptime(r["end_date"], "%Y/%m/%d/%H/%M")) for r in busy]
            [block] = tools.schedule
            self.assertEqual((block.start_datetime, block.end_datetime),
                             (datetime(2026, 10, 5, 18, 30), datetime(2026, 10, 5, 19, 30)))
            self.assertFalse(any(block.start_datetime < end and start < block.end_datetime
                                 for start, end in intervals))

    def test_request_without_date_capacities_still_omits_calendar_occurrences(self):
        busy = busy_calendar([(0, 30)])
        request = replace(build_plan_request(dense_payload(busy), require_assessment=True), weekly_availability=None)
        provider = ScriptedProvider([reply({"type": "message", "role": "assistant",
                                            "content": [{"type": "output_text", "text": "stop"}]})])
        with patch.object(openrouter_client.urllib.request, "urlopen", provider), \
                self.assertRaises(RuntimeError):
            PlanningAgent(api_key="synthetic-openrouter-key").run(request)
        self.assertEqual(len(provider.bodies), 1)
        self.assertNotIn("usable_minutes_by_date", json.dumps(provider.bodies[0]["input"], ensure_ascii=False))
        self.assert_no_busy_occurrences(provider.bodies, busy)


class PlanningModelLifecycleTests(unittest.TestCase):
    def setUp(self):
        isolate_model_configuration(self)
        os.environ["OPENROUTER_MODEL"] = "vendor/model-a"

    def test_one_agent_keeps_its_first_model_after_the_configuration_changes(self):
        replies = [reply(call("call_profile", *PROFILE)), reply(call("call_analysis", *ANALYSIS)),
                   reply(call("call_build", *BUILD))]
        provider = ScriptedProvider(replies)

        def changing_configuration(request, timeout=None):
            os.environ["OPENROUTER_MODEL"] = "vendor/model-b"
            return provider(request, timeout)

        agent = PlanningAgent(api_key="synthetic-openrouter-key")
        self.assertIsNone(agent.model)
        loads = []

        def counting_load_model():
            loads.append(os.environ["OPENROUTER_MODEL"])
            return planner_load_model()

        with patch.object(openrouter_client.urllib.request, "urlopen", changing_configuration), \
                patch("license_planner.agent.load_model", counting_load_model):
            tools, _ = agent.run(build_plan_request(copy.deepcopy(PAYLOAD), require_assessment=True))
        self.assertEqual([body["model"] for body in provider.bodies], ["vendor/model-a"] * 3)
        self.assertEqual((loads, agent.model, agent.api_request_count), (["vendor/model-a"], "vendor/model-a", 3))
        self.assertEqual(len(agent.usage_records), 3)
        self.assertEqual(len(tools.schedule), 1)
        for body in provider.bodies:
            self.assertEqual((body["reasoning"], body["max_output_tokens"]), ({"effort": "low"}, 16384))

        fresh = ScriptedProvider([RuntimeError("stop after the first request")])
        new_agent = PlanningAgent(api_key="synthetic-openrouter-key")
        with patch.object(openrouter_client.urllib.request, "urlopen", fresh), \
                self.assertRaisesRegex(RuntimeError, "stop after the first request"):
            new_agent.run(build_plan_request(copy.deepcopy(PAYLOAD), require_assessment=True))
        self.assertEqual(([body["model"] for body in fresh.bodies], new_agent.model), (["vendor/model-b"], "vendor/model-b"))

    def test_selection_survives_the_step_limit_without_reloading(self):
        loads = []

        def counting_load_model():
            loads.append(1)
            return "vendor/model-a"

        provider = ScriptedProvider([reply(call(f"c{index}", *PROFILE)) for index in range(AGENT_MAX_STEPS)])
        agent = PlanningAgent(api_key="synthetic-openrouter-key")
        with patch.object(openrouter_client.urllib.request, "urlopen", provider), \
                patch("license_planner.agent.load_model", counting_load_model), \
                self.assertRaisesRegex(RuntimeError, f"max_steps={AGENT_MAX_STEPS}"):
            agent.run(build_plan_request(copy.deepcopy(PAYLOAD), require_assessment=True))
        self.assertEqual((len(provider.bodies), len(loads)), (AGENT_MAX_STEPS, 1))
        self.assertEqual({body["model"] for body in provider.bodies}, {"vendor/model-a"})


class PlanningCredentialTests(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.dotenv = Path(temp.name) / ".env"
        self.dotenv.write_text("GEMINI_API_KEY=legacy-file-key\n", encoding="utf-8")
        for patcher in (patch.dict(os.environ), patch("license_planner.settings.DOTENV_PATH", self.dotenv)):
            patcher.start()
            self.addCleanup(patcher.stop)
        os.environ.pop("OPENROUTER_API_KEY", None)
        os.environ["GEMINI_API_KEY"] = "legacy-env-key"

    def test_legacy_key_alone_makes_no_provider_request(self):
        provider = ScriptedProvider([])
        agent = PlanningAgent()
        request = build_plan_request(copy.deepcopy(PAYLOAD), require_assessment=True)
        with patch.object(openrouter_client.urllib.request, "urlopen", provider), \
                self.assertRaises(InferenceConfigurationError):
            agent.run(request)
        self.assertEqual((provider.bodies, agent.api_request_count), ([], 0))

    def test_local_file_key_is_used_when_the_environment_has_none(self):
        self.dotenv.write_text("OPENROUTER_API_KEY=synthetic-file-key\n", encoding="utf-8")
        captured = []

        def urlopen(request, timeout=None):
            captured.append(request.get_header("Authorization"))
            raise TimeoutError()

        with patch.object(openrouter_client.urllib.request, "urlopen", urlopen), \
                self.assertRaisesRegex(InferenceError, "network error"):
            PlanningAgent().run(build_plan_request(copy.deepcopy(PAYLOAD), require_assessment=True))
        self.assertEqual(captured, ["Bearer synthetic-file-key"])

    def test_zero_capacity_is_rejected_before_credentials_model_or_requests(self):
        payload = {**copy.deepcopy(PAYLOAD), "preparation_start": "2026/10/05/17/00", "exam_date": "2026/10/05/18/00"}
        provider = ScriptedProvider([])
        with patch.object(openrouter_client.urllib.request, "urlopen", provider), \
                patch("license_planner.agent.load_api_key", side_effect=AssertionError("key read")), \
                patch("license_planner.agent.load_model", side_effect=AssertionError("model read")), \
                self.assertRaisesRegex(ValueError, "^NOT_ENOUGH_TIME: "):
            agent = PlanningAgent()
            agent.run(build_plan_request(payload, require_assessment=True))
        self.assertEqual((provider.bodies, agent.model, agent.api_request_count), ([], None, 0))


if __name__ == "__main__":
    unittest.main()
