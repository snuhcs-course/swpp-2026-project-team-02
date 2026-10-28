"""Planning agent tool protocol through the real transport with a scripted provider."""
import copy
import json
import os
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import openrouter_client
from api_service import build_plan_request
from license_planner.agent import AGENT_MAX_STEPS, PlanningAgent
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


class PlanningProtocolTests(unittest.TestCase):
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

    def test_zero_capacity_is_rejected_before_credentials_or_requests(self):
        payload = {**copy.deepcopy(PAYLOAD), "preparation_start": "2026/10/05/17/00", "exam_date": "2026/10/05/18/00"}
        provider = ScriptedProvider([])
        with patch.object(openrouter_client.urllib.request, "urlopen", provider), \
                patch("license_planner.agent.load_api_key", side_effect=AssertionError("key read")), \
                self.assertRaisesRegex(ValueError, "^NOT_ENOUGH_TIME: "):
            PlanningAgent().run(build_plan_request(payload, require_assessment=True))
        self.assertEqual(provider.bodies, [])


if __name__ == "__main__":
    unittest.main()
