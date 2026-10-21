"""Gemini function calling으로 일정 조정 도구를 선택/실행합니다."""
from __future__ import annotations

import json
import urllib.error
import urllib.request

from .models import AdjustmentRequest
from .settings import (AGENT_MAX_STEPS, FUNCTION_CALL_MODE, GEMINI_API_ACTION,
                       GEMINI_API_URL, GEMINI_MODEL, GEMINI_TIMEOUT_SECONDS, AGENT_SYSTEM_PROMPT,
                       load_api_key)
from .tools import ScheduleAdjustmentTools, tool_schemas


class GeminiAdjustmentAgent:
    """일정 ID 종류에 알맞은 조정 도구를 선택하는 Gemini Agent입니다."""

    def __init__(self, api_key: str | None = None):
        """입력 값: 선택적 Gemini API 키
        출력 값: API 인증이 준비된 Agent 객체
        기능: 환경 변수 또는 프로젝트 .env에서 키를 읽고 필수 여부를 검사합니다.
        """
        # 비밀 키는 요청 헤더에만 사용하며 로그에 출력하지 않습니다.
        self.api_key = api_key or load_api_key()
        if not self.api_key:
            raise ValueError("GEMINI_API_KEY is missing. Add it to the root .env or environment.")
        self.api_request_count = 0  # Gemini API에 보낸 요청 횟수입니다.

    def _ask_model(self, contents: list[dict]) -> dict:
        """입력 값: Gemini 대화 이력
        출력 값: Gemini 응답 JSON 객체
        기능: function calling을 활성화해 지정 모델에 요청합니다.
        """
        url = f"{GEMINI_API_URL}/{GEMINI_MODEL}:{GEMINI_API_ACTION}"
        body = {
            "contents": contents,
            "tools": [{"function_declarations": tool_schemas()}],
            "tool_config": {"function_calling_config": {"mode": FUNCTION_CALL_MODE}},
            "system_instruction": {"parts": [{"text": AGENT_SYSTEM_PROMPT}]},
        }
        request = urllib.request.Request(
            url,
            data=json.dumps(body).encode("utf-8"),
            headers={"Content-Type": "application/json", "x-goog-api-key": self.api_key},
            method="POST",
        )
        self.api_request_count += 1
        try:
            with urllib.request.urlopen(request, timeout=GEMINI_TIMEOUT_SECONDS) as response:
                return json.loads(response.read())
        except urllib.error.HTTPError as error:
            response_text = error.read().decode("utf-8", errors="replace")
            try:
                error_record = json.loads(response_text).get("error", {})
            except (json.JSONDecodeError, AttributeError):
                error_record = {}
            api_status = error_record.get("status", "")
            api_message = error_record.get("message", "")
            detail = f"HTTP {error.code} {api_status}: {api_message}".strip()
            raise RuntimeError(f"Gemini API request failed: {detail}") from error
        except (urllib.error.URLError, TimeoutError) as error:
            raise RuntimeError(f"Gemini API request failed: {error}") from error

    def adjust(self, request: AdjustmentRequest) -> tuple[ScheduleAdjustmentTools, str]:
        """입력 값: 대상 일정 ID와 원본 일정
        출력 값: 변경 결과를 가진 도구 상태 객체와 Agent 요약
        기능: inspect 후 일정 종류에 맞는 도구를 실행하는 ReAct 루프를 수행합니다.
        """
        tools = ScheduleAdjustmentTools(request)  # 원본 입력을 가진 도구 실행 상태입니다.
        user_message = json.dumps({"schedule_id": request.schedule_id}, ensure_ascii=False)
        contents = [{"role": "user", "parts": [{"text": f"Adjust the schedule for this event. Target: {user_message}. The original CSV is available through the inspect tool; do not infer any missing row."}]}]
        for _ in range(AGENT_MAX_STEPS):
            result = self._ask_model(contents)
            candidate = (result.get("candidates") or [{}])[0].get("content", {})
            contents.append(candidate)
            calls = [part["functionCall"] for part in candidate.get("parts", []) if "functionCall" in part]
            if not calls:
                summary = " ".join(part.get("text", "") for part in candidate.get("parts", []))
                if tools.outcome is None:
                    raise RuntimeError(tools.last_tool_error or "Agent finished without applying an adjustment tool.")
                return tools, summary
            responses = []
            for call in calls:
                observation = tools.call(call["name"], call.get("args", {}))
                if _is_accepted_adjustment(tools, call["name"], observation):
                    # 검증된 변경 결과가 나오면 추가 모델 요청이나 남은 도구 호출 없이 종료합니다.
                    return tools, tools.outcome.message
                responses.append({"functionResponse": {"name": call["name"], "response": {"result": observation}}})
            contents.append({"role": "user", "parts": responses})
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

