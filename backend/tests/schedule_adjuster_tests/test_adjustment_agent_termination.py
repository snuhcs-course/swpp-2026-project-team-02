import itertools
import json
import os
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import openrouter_client
from openrouter_client import InferenceConfigurationError, InferenceError
from schedule_adjuster.agent import AdjustmentAgent
from schedule_adjuster.csv_io import parse_schedule_csv
from schedule_adjuster.models import AdjustmentRequest
from schedule_adjuster.settings import AGENT_MAX_STEPS


SAMPLE_CSV = """schedule_id,start_date,end_date,topic
external-10,2026/10/06/18/00,2026/10/06/20/00,
study-11,2026/10/05/19/00,2026/10/05/20/00,Charts
study-12,2026/10/08/18/00,2026/10/08/20/00,Charts
"""


CALL_IDS = itertools.count(1)


def call(name, **args):
    """입력 값: 도구 이름과 인자
    출력 값: Responses API function_call 항목
    기능: 고유한 call_id를 가진 도구 호출 항목을 만듭니다.
    """
    return {"type": "function_call", "call_id": f"call_{next(CALL_IDS)}", "name": name,
            "arguments": json.dumps(args)}


def text(message):
    """입력 값: 모델 응답 문장
    출력 값: Responses API assistant message 항목
    기능: 도구 호출 없이 텍스트만 있는 출력 항목을 만듭니다.
    """
    return {"type": "message", "role": "assistant", "content": [{"type": "output_text", "text": message}]}


def reply(*items):
    """입력 값: 출력 항목 목록
    출력 값: 완료된 Responses API 응답 형식의 딕셔너리
    기능: 스크립트된 모델 응답 하나를 만듭니다.
    """
    return {"status": "completed", "output": list(items)}


class ScriptedAgent(AdjustmentAgent):
    """실제 도구를 실행하고 모델 응답만 미리 정한 순서대로 돌려줍니다."""

    def __init__(self, replies):
        """입력 값: 순서대로 반환할 응답 또는 예외 목록
        출력 값: 네트워크 없이 동작하는 Agent
        기능: 더미 키로 Agent를 만들고 스크립트를 보관합니다.
        """
        super().__init__(api_key="dummy-key")
        self.replies = list(replies)

    def _ask_model(self, input_items):
        """입력 값: 모델 대화 기록
        출력 값: 다음 스크립트 응답
        기능: 요청 횟수를 세고 스크립트 응답을 반환하거나 예외를 발생시킵니다.
        """
        self.api_request_count += 1
        if not self.replies:
            raise AssertionError("the agent requested a reply the script does not provide")
        next_reply = self.replies.pop(0)
        if isinstance(next_reply, Exception):
            raise next_reply
        return next_reply


class AdjustmentAgentTerminationTests(unittest.TestCase):
    def setUp(self):
        """
        입력 값: 없음
        출력 값: 테스트 공통 데이터 준비 상태
        기능: 외부 일정 1개와 같은 주제 공부 일정 2개를 준비합니다.
        """
        self.entries = parse_schedule_csv(SAMPLE_CSV)

    def run_agent(self, schedule_id, replies, entries=None):
        """입력 값: 대상 ID, 스크립트 응답, 선택적 원본 일정
        출력 값: Agent와 adjust 결과
        기능: 실제 조정 도구로 스크립트된 Agent를 실행합니다.
        """
        agent = ScriptedAgent(replies)
        request = AdjustmentRequest(schedule_id, entries if entries is not None else self.entries)
        return agent, agent.adjust(request)

    def test_study_move_returns_outcome_without_follow_up_request(self):
        """
        입력 값: 대상·주제 조회 후 이동 도구를 호출하는 응답과 이후 실패할 응답
        출력 값: 모든 검증 단언 통과 시 테스트 통과
        기능: 이동 성공 직후 추가 모델 요청 없이 검증된 결과로 종료합니다.
        """
        agent, (tools, summary) = self.run_agent("study-11", [
            reply(call("inspect_schedule", schedule_id="study-11")),
            reply(call("inspect_topic_schedule", topic="Charts")),
            reply(call("reschedule_failed_study", schedule_id="study-11")),
            RuntimeError("provider failure after success"),
        ])
        self.assertEqual(agent.api_request_count, 3)
        self.assertEqual(agent.replies[0].args, ("provider failure after success",))
        self.assertEqual(tools.outcome.action, "rescheduled_study")
        self.assertEqual(summary, tools.outcome.message)
        moved = next(entry for entry in tools.schedule if entry.schedule_id == "study-11")
        self.assertGreaterEqual(moved.start_datetime, self.entries[2].end_datetime)

    def test_success_on_final_permitted_response_is_not_a_step_limit_error(self):
        """
        입력 값: 마지막 허용 응답에서 변경 도구가 성공하는 스크립트
        출력 값: 모든 검증 단언 통과 시 테스트 통과
        기능: 반복 제한 마지막 응답의 성공을 오류 대신 결과로 반환합니다.
        """
        replies = [reply(call("inspect_schedule", schedule_id="study-11")),
                   reply(call("inspect_topic_schedule", topic="Charts"))]
        replies += [reply(call("inspect_schedule", schedule_id="study-11")) for _ in range(AGENT_MAX_STEPS - 3)]
        replies.append(reply(call("reschedule_failed_study", schedule_id="study-11")))
        self.assertEqual(len(replies), AGENT_MAX_STEPS)
        agent, (tools, summary) = self.run_agent("study-11", replies)
        self.assertEqual(agent.api_request_count, AGENT_MAX_STEPS)
        self.assertEqual(tools.outcome.schedule_id, "study-11")
        self.assertEqual(summary, tools.outcome.message)

    def test_cancelling_the_only_row_accepts_an_empty_schedule(self):
        """
        입력 값: 외부 일정 하나만 있는 원본과 취소 도구 호출
        출력 값: 모든 검증 단언 통과 시 테스트 통과
        기능: 빈 결과 일정도 검증된 취소 결과로 받아들입니다.
        """
        only_external = tuple(entry for entry in self.entries if entry.schedule_id == "external-10")
        agent, (tools, summary) = self.run_agent("external-10", [
            reply(call("inspect_schedule", schedule_id="external-10")),
            reply(call("remove_cancelled_external_schedule", schedule_id="external-10")),
            RuntimeError("provider failure after success"),
        ], only_external)
        self.assertEqual(agent.api_request_count, 2)
        self.assertEqual(tools.schedule, ())
        self.assertEqual(tools.outcome.action, "cancelled_external")
        self.assertEqual(summary, tools.outcome.message)

    def test_remaining_calls_in_the_same_response_are_not_executed(self):
        """
        입력 값: 성공한 변경 도구 뒤에 다른 도구 호출이 묶인 응답
        출력 값: 모든 검증 단언 통과 시 테스트 통과
        기능: 성공 이후 같은 응답의 나머지 도구 호출을 실행하지 않습니다.
        """
        agent, (tools, _) = self.run_agent("external-10", [
            reply(call("inspect_schedule", schedule_id="external-10"),
                  call("remove_cancelled_external_schedule", schedule_id="external-10"),
                  call("reschedule_failed_study", schedule_id="study-11"),
                  call("unknown_tool"),
                  {"type": "function_call", "call_id": "", "name": "inspect_schedule", "arguments": "["}),
        ])
        self.assertEqual(agent.api_request_count, 1)
        self.assertEqual([entry["tool"] for entry in tools.trace.calls],
                         ["inspect_schedule", "remove_cancelled_external_schedule"])
        self.assertIsNone(tools.last_tool_error)

    def test_text_after_inspection_only_is_not_success(self):
        """
        입력 값: 조회 후 완료를 주장하는 텍스트 응답
        출력 값: 모든 검증 단언 통과 시 테스트 통과
        기능: 변경 도구 성공 없이 텍스트만 있는 응답은 실패합니다.
        """
        with self.assertRaises(RuntimeError):
            self.run_agent("external-10", [
                reply(call("inspect_schedule", schedule_id="external-10")),
                reply(text("The cancelled event was removed.")),
            ])

    def test_inspection_until_the_step_limit_fails(self):
        """
        입력 값: 반복 제한까지 조회만 하는 응답
        출력 값: 모든 검증 단언 통과 시 테스트 통과
        기능: 조회만으로는 성공하지 않고 반복 제한 오류가 발생합니다.
        """
        replies = [reply(call("inspect_schedule", schedule_id="study-11")) for _ in range(AGENT_MAX_STEPS)]
        with self.assertRaisesRegex(RuntimeError, "max steps"):
            self.run_agent("study-11", replies)

    def test_failed_mutation_does_not_end_the_loop(self):
        """
        입력 값: 주제 조회 없이 호출해 실패한 이동 도구와 이후 텍스트 응답
        출력 값: 모든 검증 단언 통과 시 테스트 통과
        기능: 오류를 반환한 변경 도구는 성공으로 보지 않고 모델 응답을 계속 요청합니다.
        """
        agent = ScriptedAgent([
            reply(call("inspect_schedule", schedule_id="study-11"),
                  call("reschedule_failed_study", schedule_id="study-11")),
            reply(text("Done.")),
        ])
        with self.assertRaisesRegex(RuntimeError, "PREREQUISITE"):
            agent.adjust(AdjustmentRequest("study-11", self.entries))
        self.assertEqual(agent.api_request_count, 2)

    def test_mutation_of_the_wrong_kind_is_not_success(self):
        """
        입력 값: 공부 일정 대상에 외부 일정 취소 도구를 호출하는 응답
        출력 값: 모든 검증 단언 통과 시 테스트 통과
        기능: 일정 종류와 맞지 않는 변경 도구 호출은 결과를 기록하지 않고 실패합니다.
        """
        with self.assertRaisesRegex(RuntimeError, "WRONG_SCHEDULE_TYPE"):
            self.run_agent("study-11", [
                reply(call("inspect_schedule", schedule_id="study-11"),
                      call("remove_cancelled_external_schedule", schedule_id="study-11")),
                reply(text("Removed.")),
            ])


class ScriptedProvider:
    """urlopen 대체 객체로, 스크립트된 Responses 응답을 돌려주고 요청 본문을 기록합니다."""

    def __init__(self, replies):
        self.replies = list(replies)
        self.bodies = []

    def __call__(self, request, timeout=None):
        self.bodies.append(json.loads(request.data))
        if not self.replies:
            raise AssertionError("the agent sent a request the script does not provide")
        body = json.dumps(self.replies.pop(0)).encode()

        class Response:
            def __enter__(self):
                return self

            def __exit__(self, *exc):
                return False

            def read(self):
                return body
        return Response()


class AdjustmentProtocolTests(unittest.TestCase):
    def setUp(self):
        """
        입력 값: 없음
        출력 값: 테스트 공통 데이터와 실제 비밀 설정을 대신하는 임시 경로
        기능: 원본 일정을 준비하고 키 파일 경로를 임시 디렉터리로 바꿉니다.
        """
        self.entries = parse_schedule_csv(SAMPLE_CSV)
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.program_env = Path(temp.name) / "program.env"
        self.workspace_env = Path(temp.name) / "workspace.env"
        for patcher in (patch.dict(os.environ),
                        patch("schedule_adjuster.settings.DOTENV_PATH", self.program_env),
                        patch("schedule_adjuster.settings.WORKSPACE_DOTENV_PATH", self.workspace_env)):
            patcher.start()
            self.addCleanup(patcher.stop)
        os.environ.pop("OPENROUTER_API_KEY", None)

    def run_agent(self, schedule_id, replies, api_key="synthetic-openrouter-key", entries=None):
        """
        입력 값: 대상 ID, 스크립트 응답, 선택적 키와 원본 일정
        출력 값: 가짜 제공자, Agent, adjust 결과 또는 예외
        기능: 실제 전송 계층과 도구로 Agent를 실행합니다.
        """
        provider = ScriptedProvider(replies)
        agent = AdjustmentAgent(api_key=api_key)
        request = AdjustmentRequest(schedule_id, entries if entries is not None else self.entries)
        with patch.object(openrouter_client.urllib.request, "urlopen", provider):
            try:
                return provider, agent, agent.adjust(request)
            except Exception as error:
                error.provider = provider
                raise

    def test_requests_use_disabled_reasoning_bounds_and_matching_observations(self):
        """
        입력 값: 조회·주제 조회·이동을 차례로 호출하는 응답
        출력 값: 모든 검증 단언 통과 시 테스트 통과
        기능: 요청마다 Luna 설정과 출력 한도를 보내고 관찰 결과를 같은 call_id로 돌려줍니다.
        """
        inspect = call("inspect_schedule", schedule_id="study-11")
        provider, agent, (tools, summary) = self.run_agent("study-11", [
            reply(inspect), reply(call("inspect_topic_schedule", topic="Charts")),
            reply(call("reschedule_failed_study", schedule_id="study-11")),
        ])
        self.assertEqual((len(provider.bodies), agent.api_request_count), (3, 3))
        for body in provider.bodies:
            self.assertEqual((body["model"], body["reasoning"], body["max_output_tokens"]),
                             ("openai/gpt-6-luna", {"effort": "none"}, 2048))
            self.assertNotIn("include", body)
            self.assertEqual({tool["parameters"]["type"] for tool in body["tools"]}, {"object"})
        second = provider.bodies[1]["input"]
        self.assertEqual(second[1], inspect)
        self.assertEqual(second[2]["call_id"], inspect["call_id"])
        self.assertEqual(json.loads(second[2]["output"])["result"]["schedule_type"], "study")
        self.assertEqual(summary, tools.outcome.message)

    def test_cancellation_on_the_final_permitted_request_is_terminal(self):
        """
        입력 값: 마지막 허용 응답에서 외부 일정을 취소하는 스크립트
        출력 값: 모든 검증 단언 통과 시 테스트 통과
        기능: 마지막 요청의 취소 성공을 결과로 반환하고 추가 요청을 보내지 않습니다.
        """
        replies = [reply(call("inspect_schedule", schedule_id="external-10")) for _ in range(AGENT_MAX_STEPS - 1)]
        replies += [reply(call("remove_cancelled_external_schedule", schedule_id="external-10")),
                    reply(text("unused"))]
        provider, _, (tools, _) = self.run_agent("external-10", replies)
        self.assertEqual((len(provider.bodies), len(provider.replies)), (AGENT_MAX_STEPS, 1))
        self.assertEqual(tools.outcome.action, "cancelled_external")

    def test_invalid_replies_fail_without_mutation(self):
        """
        입력 값: 잘못된 인자·중복 ID·지원하지 않는 도구·거절·미완료 응답
        출력 값: 모든 검증 단언 통과 시 테스트 통과
        기능: 형식이 잘못된 응답은 입력 오류가 아니라 상위 오류이며 일정을 바꾸지 않습니다.
        """
        inspect = call("inspect_schedule", schedule_id="external-10")
        cases = {
            "non-object arguments": [reply({**call("remove_cancelled_external_schedule"), "arguments": "\"external-10\""})],
            "duplicate call_id": [reply(inspect), reply({**call("remove_cancelled_external_schedule",
                                                                schedule_id="external-10"),
                                                         "call_id": inspect["call_id"]})],
            "unsupported tool": [reply(call("edit_csv", schedule_id="external-10"))],
            "refusal": [reply({"type": "message", "content": [{"type": "refusal", "refusal": "no"}]})],
            "incomplete": [{"status": "incomplete", "incomplete_details": {"reason": "max_output_tokens"}}],
        }
        for label, replies in cases.items():
            with self.subTest(label), self.assertRaises(InferenceError) as raised:
                self.run_agent("external-10", replies)
            self.assertNotIsInstance(raised.exception, ValueError)
            self.assertEqual(raised.exception.provider.replies, [])

    def test_requests_stop_at_the_limit(self):
        """
        입력 값: 조회만 반복하는 응답
        출력 값: 모든 검증 단언 통과 시 테스트 통과
        기능: 조정 요청은 다섯 번을 넘지 않고 요약 요청도 보내지 않습니다.
        """
        replies = [reply(call("inspect_schedule", schedule_id="study-11")) for _ in range(AGENT_MAX_STEPS + 1)]
        with self.assertRaisesRegex(RuntimeError, "max steps") as raised:
            self.run_agent("study-11", replies)
        self.assertEqual((len(raised.exception.provider.bodies), AGENT_MAX_STEPS), (5, 5))

    def test_legacy_key_alone_makes_no_provider_request(self):
        """
        입력 값: Gemini 키만 있는 환경 변수와 로컬 설정
        출력 값: 모든 검증 단언 통과 시 테스트 통과
        기능: OpenRouter 키가 없으면 요청 없이 설정 누락 오류가 발생합니다.
        """
        os.environ["GEMINI_API_KEY"] = "legacy-env-key"
        self.workspace_env.write_text("GEMINI_API_KEY=legacy-file-key\n", encoding="utf-8")
        with self.assertRaises(InferenceConfigurationError) as raised:
            self.run_agent("external-10", [], api_key=None)
        self.assertEqual(raised.exception.provider.bodies, [])

    def test_environment_then_program_then_workspace_key(self):
        """
        입력 값: 서로 다른 합성 키를 가진 환경 변수와 두 설정 파일
        출력 값: 모든 검증 단언 통과 시 테스트 통과
        기능: 환경 변수, schedule_adjuster/.env, backend/.env 순서로 키를 고릅니다.
        """
        self.program_env.write_text("OPENROUTER_API_KEY=program-key\n", encoding="utf-8")
        self.workspace_env.write_text("OPENROUTER_API_KEY=workspace-key\n", encoding="utf-8")
        os.environ["OPENROUTER_API_KEY"] = "environment-key"
        expected = ["environment-key", "program-key", "workspace-key"]
        for key in expected:
            with self.subTest(key):
                seen = []

                def urlopen(request, timeout=None):
                    seen.append(request.get_header("Authorization"))
                    raise TimeoutError()

                with patch.object(openrouter_client.urllib.request, "urlopen", urlopen), \
                        self.assertRaises(InferenceError):
                    AdjustmentAgent().adjust(AdjustmentRequest("external-10", self.entries))
                self.assertEqual(seen, [f"Bearer {key}"])
                if key == "environment-key":
                    os.environ.pop("OPENROUTER_API_KEY")
                elif key == "program-key":
                    self.program_env.unlink()


if __name__ == "__main__":
    unittest.main()
