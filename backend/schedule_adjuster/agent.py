"""Gemini function calling으로 일정 조정 도구를 선택/실행합니다."""
from __future__ import annotations

import json

from openrouter_client import assistant_message, request_completion, tool_arguments
from .models import AdjustmentRequest
from .settings import (AGENT_MAX_STEPS, OPENROUTER_TIMEOUT_SECONDS, AGENT_SYSTEM_PROMPT,
                       load_api_key, load_model)
from .tools import ScheduleAdjustmentTools, tool_schemas


class GeminiAdjustmentAgent:
    """일정 ID 종류에 알맞은 조정 도구를 선택하는 OpenRouter Agent입니다."""

    def __init__(self, api_key: str | None = None):
        """입력 값: 선택적 Gemini API 키
        출력 값: API 인증이 준비된 Agent 객체
        기능: 환경 변수 또는 프로젝트 .env에서 키를 읽고 필수 여부를 검사합니다.
        """
        # 비밀 키는 요청 헤더에만 사용하며 로그에 출력하지 않습니다.
        self.api_key = api_key or load_api_key()
        if not self.api_key:
            raise ValueError("OPENROUTER_API_KEY is missing. Add it to backend/.env or the environment.")
        self.model = load_model()
        self.api_request_count = 0

    def _ask_model(self, contents: list[dict]) -> dict:
        """Request a tool-capable OpenRouter chat completion."""
        self.api_request_count += 1
        return request_completion(api_key=self.api_key, model=self.model, messages=contents,
                                  declarations=tool_schemas(), timeout_seconds=OPENROUTER_TIMEOUT_SECONDS)

    def adjust(self, request: AdjustmentRequest) -> tuple[ScheduleAdjustmentTools, str]:
        """입력 값: 대상 일정 ID와 원본 일정
        출력 값: 변경 결과를 가진 도구 상태 객체와 Agent 요약
        기능: inspect 후 일정 종류에 맞는 도구를 실행하는 ReAct 루프를 수행합니다.
        """
        tools = ScheduleAdjustmentTools(request)  # 원본 입력을 가진 도구 실행 상태입니다.
        user_message = json.dumps({"schedule_id": request.schedule_id}, ensure_ascii=False)
        contents = [
            {"role": "system", "content": AGENT_SYSTEM_PROMPT},
            {"role": "user", "content": f"Adjust the schedule for this event. Target: {user_message}. The original CSV is available through the inspect tool; do not infer any missing row."},
        ]
        for step in range(AGENT_MAX_STEPS):
            try:
                result = self._ask_model(contents)
            except RuntimeError as error:
                completed_tools = [item["tool"] for item in tools.trace.calls]
                raise RuntimeError(
                    f"OpenRouter adjustment failed at step {step + 1}; "
                    f"completed_tools={completed_tools}: {error}"
                ) from error
            candidate = assistant_message(result)
            contents.append(candidate)
            calls = candidate.get("tool_calls") or []
            if not calls:
                summary = candidate.get("content") or ""
                if tools.outcome is None:
                    raise RuntimeError(tools.last_tool_error or "Agent finished without applying an adjustment tool.")
                return tools, summary
            for call in calls:
                name = call.get("function", {}).get("name", "")
                observation = tools.call(name, tool_arguments(call))
                contents.append({"role": "tool", "tool_call_id": call.get("id", name), "name": name,
                                 "content": json.dumps({"result": observation}, ensure_ascii=False)})
        completed_tools = [item["tool"] for item in tools.trace.calls]
        raise RuntimeError(f"Agent exceeded max steps ({AGENT_MAX_STEPS}); completed_tools={completed_tools}")

