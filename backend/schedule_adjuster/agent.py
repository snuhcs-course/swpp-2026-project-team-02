"""OpenRouter function calling으로 일정 조정 도구를 선택/실행합니다."""
from __future__ import annotations

import json

from openrouter_client import create_response, decode_call, function_output, parse_reply, usage_record

from .models import AdjustmentRequest
from .settings import (AGENT_MAX_OUTPUT_TOKENS, AGENT_MAX_STEPS, AGENT_REASONING_EFFORT,
                       AGENT_SYSTEM_PROMPT, load_api_key)
from .tools import ScheduleAdjustmentTools, tool_schemas


class AdjustmentAgent:
    """일정 ID 종류에 알맞은 조정 도구를 선택하는 OpenRouter Agent입니다."""

    def __init__(self, api_key: str | None = None):
        """입력 값: 선택적 OpenRouter API 키
        출력 값: 조정 Agent 객체
        기능: 실행 속성을 초기화합니다. 키는 첫 모델 요청 직전에 읽습니다.
        """
        # 비밀 키는 요청 헤더에만 사용하며 로그에 출력하지 않습니다.
        self.api_key = api_key
        self.api_request_count = 0  # OpenRouter에 보낸 요청 횟수입니다.
        self.usage_records: list[dict] = []  # 응답별 반환 모델과 토큰/비용 사용량입니다.

    def _ask_model(self, input_items: list[dict]) -> dict:
        """입력 값: 요청별 모델 대화 기록
        출력 값: OpenRouter 응답 객체
        기능: 키를 확인한 뒤 추론 없이 함수 호출 가능 요청을 한 번 보냅니다.
        """
        self.api_key = self.api_key or load_api_key()
        self.api_request_count += 1
        result = create_response(self.api_key, instructions=AGENT_SYSTEM_PROMPT, input_items=input_items,
                                 tools=tool_schemas(), reasoning_effort=AGENT_REASONING_EFFORT,
                                 max_output_tokens=AGENT_MAX_OUTPUT_TOKENS)
        self.usage_records.append(usage_record(result))
        return result

    def adjust(self, request: AdjustmentRequest) -> tuple[ScheduleAdjustmentTools, str]:
        """입력 값: 대상 일정 ID와 원본 일정
        출력 값: 변경 결과를 가진 도구 상태 객체와 Agent 요약
        기능: inspect 후 일정 종류에 맞는 도구를 실행하는 ReAct 루프를 수행합니다.
        """
        tools = ScheduleAdjustmentTools(request)  # 원본 입력을 가진 도구 실행 상태입니다.
        user_message = json.dumps({"schedule_id": request.schedule_id}, ensure_ascii=False)
        history = [{"role": "user", "content": f"Adjust the schedule for this event. Target: {user_message}. The original CSV is available through the inspect tool; do not infer any missing row."}]
        tool_names = [schema["name"] for schema in tool_schemas()]
        seen_call_ids: set[str] = set()  # 이번 요청에서 받은 함수 호출 ID입니다.
        for _ in range(AGENT_MAX_STEPS):
            output, calls, summary = parse_reply(self._ask_model(history))
            history.extend(output)
            if not calls:
                if tools.outcome is None:
                    raise RuntimeError(tools.last_tool_error or "Agent finished without applying an adjustment tool.")
                return tools, summary
            for item in calls:
                call_id, name, arguments = decode_call(item, tool_names, seen_call_ids)
                observation = tools.call(name, arguments)
                if _is_accepted_adjustment(tools, name, observation):
                    # 검증된 변경 결과가 나오면 추가 모델 요청이나 남은 도구 호출 없이 종료합니다.
                    return tools, tools.outcome.message
                history.append(function_output(call_id, {"result": observation}))
        raise RuntimeError(f"Agent exceeded max steps ({AGENT_MAX_STEPS}); calls: {tools.trace.calls}")


# 변경 도구 이름과 성공 시 기록되는 작업 유형입니다.
MUTATION_ACTIONS = {"reschedule_failed_study": "rescheduled_study",
                    "remove_cancelled_external_schedule": "cancelled_external"}


def _is_accepted_adjustment(tools: ScheduleAdjustmentTools, name: str, observation: object) -> bool:
    """입력 값: 도구 상태, 방금 실행한 도구 이름과 응답
    출력 값: 요청한 대상의 조정이 완료됐는지 여부
    기능: 변경 도구가 성공해 요청 대상과 일정 종류에 맞는 결과를 기록했는지 확인합니다.
    """
    target = next((entry for entry in tools.original_schedule
                   if entry.schedule_id == tools.request.schedule_id), None)
    if name not in MUTATION_ACTIONS or not isinstance(observation, dict) or tools.outcome is None or target is None:
        return False
    target_action = "rescheduled_study" if target.schedule_type == "study" else "cancelled_external"
    return (tools.outcome.schedule_id == target.schedule_id
            and tools.outcome.action == MUTATION_ACTIONS[name] == target_action)

