import unittest

from schedule_adjuster.agent import GeminiAdjustmentAgent
from schedule_adjuster.csv_io import parse_schedule_csv
from schedule_adjuster.models import AdjustmentRequest
from schedule_adjuster.settings import AGENT_MAX_STEPS


SAMPLE_CSV = """schedule_id,start_date,end_date,topic
external-10,2026/10/06/18/00,2026/10/06/20/00,
study-11,2026/10/05/19/00,2026/10/05/20/00,Charts
study-12,2026/10/08/18/00,2026/10/08/20/00,Charts
"""


def call(name, **args):
    """입력 값: 도구 이름과 인자
    출력 값: Gemini functionCall 파트
    기능: 모델 응답에 들어갈 도구 호출 파트를 만듭니다.
    """
    return {"functionCall": {"name": name, "args": args}}


def reply(*parts):
    """입력 값: 응답 파트 목록
    출력 값: Gemini generateContent 응답 형식의 딕셔너리
    기능: 스크립트된 모델 응답 하나를 만듭니다.
    """
    return {"candidates": [{"content": {"role": "model", "parts": list(parts)}}]}


class ScriptedAgent(GeminiAdjustmentAgent):
    """실제 도구를 실행하고 모델 응답만 미리 정한 순서대로 돌려줍니다."""

    def __init__(self, replies):
        """입력 값: 순서대로 반환할 응답 또는 예외 목록
        출력 값: 네트워크 없이 동작하는 Agent
        기능: 더미 키로 Agent를 만들고 스크립트를 보관합니다.
        """
        super().__init__(api_key="dummy-key")
        self.replies = list(replies)

    def _ask_model(self, contents):
        """입력 값: Gemini 대화 이력
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
        replies += [reply(call("inspect_schedule", schedule_id="study-11"))] * (AGENT_MAX_STEPS - 3)
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
                  call("unknown_tool")),
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
                reply({"text": "The cancelled event was removed."}),
            ])

    def test_inspection_until_the_step_limit_fails(self):
        """
        입력 값: 반복 제한까지 조회만 하는 응답
        출력 값: 모든 검증 단언 통과 시 테스트 통과
        기능: 조회만으로는 성공하지 않고 반복 제한 오류가 발생합니다.
        """
        replies = [reply(call("inspect_schedule", schedule_id="study-11"))] * AGENT_MAX_STEPS
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
            reply({"text": "Done."}),
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
                reply({"text": "Removed."}),
            ])


if __name__ == "__main__":
    unittest.main()
