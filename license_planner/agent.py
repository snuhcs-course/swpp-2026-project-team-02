"""Small Gemini function-calling ReAct loop, with bounded iterations."""
from __future__ import annotations

import json
import urllib.error
import urllib.request

from .catalog import CERTIFICATIONS
from .models import PlanRequest, ToolTrace
from .settings import DATETIME_FORMAT, GEMINI_API_KEY
from .tools import PlannerTools, tool_schemas

# Agent/API settings: move these values here when changing Gemini or its behavior.
GEMINI_MODEL = "gemini-3.5-flash-lite"  # Gemini model ID used for tool selection.
GEMINI_API_BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models"  # Gemini REST API root.
GEMINI_API_ACTION = "generateContent"  # Function-calling API method.
GEMINI_REQUEST_TIMEOUT_SECONDS = 45  # Maximum wait for one Gemini API response.
AGENT_MAX_STEPS = 6  # Maximum reason/action/observation cycles per user request.
GEMINI_FUNCTION_CALL_MODE = "AUTO"  # Allows Gemini to choose whether to call a tool.
HTTP_CONTENT_TYPE = "application/json"  # Request body format.
HTTP_POST_METHOD = "POST"  # HTTP method for Gemini requests.
AGENT_SYSTEM_PROMPT = (
    "You create study schedules through the provided tools. Inspect the certification profile, "
    "analyze exam results, assess readiness, then build the schedule in that order. Never invent "
    "scores, dates, topics, certification details, or schedule rows. If a tool reports an error, explain it clearly. "
    "When tools succeed, briefly summarize that the application will return the CSV."
)  # Agent role and tool-use constraints.

class GeminiToolAgent:
    """Gemini 함수 호출을 이용해 도구를 실행하는 ReAct Agent입니다."""

    # 인스턴스 속성: 접속 키, 모델 ID, 최대 도구 호출 횟수입니다.
    def __init__(self, api_key: str | None = None, model: str = GEMINI_MODEL, max_steps: int = AGENT_MAX_STEPS):
        """입력 값: 선택적 API 키, 모델 ID, 최대 반복 횟수
        출력 값: 설정된 Agent 객체
        기능: .env 또는 환경 변수의 API 키를 검증하고 실행 속성을 초기화합니다.
        """
        self.api_key = api_key or GEMINI_API_KEY
        self.model = model
        self.max_steps = max_steps
        self.api_request_count = 0  # Gemini API에 전송을 시도한 요청 수입니다.
        self.successful_response_count = 0  # Gemini에서 정상 JSON 응답을 받은 요청 수입니다.
        if not self.api_key:
            raise ValueError("GEMINI_API_KEY is required for online agent mode")

    def _ask_model(self, contents: list[dict], tools: list[dict]) -> dict:
        """입력 값: 대화 내용과 도구 스키마
        출력 값: Gemini 응답 객체
        기능: 설정한 Gemini API에 함수 호출 가능 요청을 전송합니다.
        """
        url = f"{GEMINI_API_BASE_URL}/{self.model}:{GEMINI_API_ACTION}"  # 요청 대상 주소입니다.
        body = {"contents": contents, "tools": [{"function_declarations": tools}],
                "tool_config": {"function_calling_config": {"mode": GEMINI_FUNCTION_CALL_MODE}},
                "system_instruction": {"parts": [{"text": AGENT_SYSTEM_PROMPT}]}}  # 함수 호출 모드와 시스템 지시입니다.
        request = urllib.request.Request(url, data=json.dumps(body).encode(), headers={
            "Content-Type": HTTP_CONTENT_TYPE,
            "x-goog-api-key": self.api_key,
        }, method=HTTP_POST_METHOD)  # HTTP 요청 객체와 인증 헤더입니다.
        self.api_request_count += 1
        try:
            with urllib.request.urlopen(request, timeout=GEMINI_REQUEST_TIMEOUT_SECONDS) as response:
                result = json.loads(response.read())
                self.successful_response_count += 1
                return result
        except urllib.error.HTTPError as error:
            # HTTP 오류 본문에 Gemini가 반환한 쿼터/검증 설명이 들어 있습니다.
            response_text = error.read().decode("utf-8", errors="replace")
            try:
                error_record = json.loads(response_text).get("error", {})
            except (json.JSONDecodeError, AttributeError):
                error_record = {}
            api_status = error_record.get("status", "")
            api_message = error_record.get("message", "")
            detail = f"HTTP {error.code} {api_status}: {api_message}".strip()
            raise RuntimeError(f"Gemini request failed: {detail}") from error
        except (urllib.error.URLError, TimeoutError) as error:
            raise RuntimeError(f"Gemini request failed: {error}") from error

    def run(self, request: PlanRequest) -> tuple[PlannerTools, str]:
        """입력 값: 일정 생성 요청
        출력 값: 도구 실행 결과 객체와 Agent 요약 문자열
        기능: ReAct 도구 호출 루프를 실행하고 생성된 일정을 확인합니다.
        """
        cert_ids = sorted(CERTIFICATIONS)  # 현재 사용 가능한 자격증 ID 목록입니다.
        if request.certification_id not in cert_ids:
            raise ValueError(f"Unsupported certification {request.certification_id!r}; choose from {cert_ids}")
        tools = PlannerTools(request, ToolTrace())  # 상태를 공유하는 로컬 도구 집합입니다.
        user = {"certification_id": request.certification_id,
                "self_assessment": request.self_assessment,
                "problem_result_count": len(request.problem_results),
                "preparation_start": request.preparation_start.strftime(DATETIME_FORMAT) if request.preparation_start else None,
                "exam_date": request.exam_date.strftime(DATETIME_FORMAT) if request.exam_date else None,
                "busy_period_count": len(request.busy_periods),
                "has_personal_availability": request.weekly_availability is not None}
        contents: list[dict] = [{"role": "user", "parts": [{"text": "Prepare this user's plan using tools. Problem results and busy periods are available to the local tools; do not infer or invent their contents. Input metadata: " + json.dumps(user, ensure_ascii=False)}]}]  # Gemini 대화 기록입니다.
        for _ in range(self.max_steps):
            result = self._ask_model(contents, tool_schemas())
            candidate = (result.get("candidates") or [{}])[0].get("content", {})
            contents.append(candidate)
            calls = [part["functionCall"] for part in candidate.get("parts", []) if "functionCall" in part]
            if not calls:
                answer = " ".join(part.get("text", "") for part in candidate.get("parts", []))
                if tools.schedule is None:
                    raise RuntimeError(tools.last_tool_error or "Agent finished without calling build_study_schedule")
                return tools, answer
            responses = []
            for call in calls:
                outcome = tools.call(call["name"], call.get("args", {}))
                responses.append({"functionResponse": {"name": call["name"], "response": {"result": outcome}}})
            contents.append({"role": "user", "parts": responses})
        raise RuntimeError(f"Agent exceeded max_steps={self.max_steps}; tool trace: {tools.trace.calls}")

