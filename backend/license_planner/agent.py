"""Small Gemini function-calling ReAct loop, with bounded iterations."""
from __future__ import annotations

import json
import urllib.error
import urllib.request

from .catalog import CERTIFICATIONS
from .models import PlanRequest, ToolTrace
from .scheduler import usable_minutes_by_date
from .settings import DATETIME_FORMAT, GEMINI_API_KEY
from .tools import PlannerTools, check_initial_capacity, tool_schemas

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
    "analyze exam results, then build the schedule in that order. Never invent scores, dates, "
    "topics, or certification details. For each question, map the knowledge it requires to one or "
    "more topics from the certification profile; assign contribution weights summing to 1 per question. "
    "Use the user's locally calculated correctness as supplied. Use the user's free-form self-assessment and topic scores "
    "directly to choose study minutes per topic and date. Do not classify users into fixed readiness "
    "levels or apply level multipliers. Do not apply a fixed session duration or daily session-count "
    "limit. Spread workload as evenly as practical across available dates, giving weaker topics "
    "more time. For example, distribute 120 planned minutes across two equivalent available dates "
    "as about 60 minutes per date, not all 120 on one date. If a tool reports an error, explain it. "
    "When tools succeed, briefly summarize that the application will return the CSV."
)  # Agent role and tool-use constraints.

def _build_summary(result: dict) -> str:
    """입력 값: 성공한 build_study_schedule 결과
    출력 값: 생성 일정의 짧은 요약 문장
    기능: 모델을 다시 호출하지 않고 수용된 일정 수치로 요약을 만듭니다.
    """
    topic_count = len({item["topic"] for item in result["daily_topic_minutes"]})
    return (f"{topic_count}개 과목에 총 {result['total_planned_minutes']}분을 "
            f"학습 일정 {result['study_block_count']}개로 배치했습니다.")


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
        check_initial_capacity(request)
        tools = PlannerTools(request, ToolTrace())  # 상태를 공유하는 로컬 도구 집합입니다.
        weekday_names = ("monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday")
        availability = ({day: [{"start": window.start_time.strftime("%H:%M"),
                                "end": window.end_time.strftime("%H:%M")}
                         for window in request.weekly_availability.for_weekday(index)]
                        for index, day in enumerate(weekday_names)}
                       if request.weekly_availability else None)
        busy_schedules = [{"schedule_id": item.schedule_id,
                           "start_date": item.start_datetime.strftime(DATETIME_FORMAT),
                           "end_date": item.end_datetime.strftime(DATETIME_FORMAT),
                           "topic": item.topic}
                          for item in request.busy_periods]
        user = {"certification_id": request.certification_id,
                "self_assessment": request.self_assessment,
                "problem_result_count": len(request.assessment_items or request.problem_results),
                "assessment_results": [
                    {"problem_id": item.problem_id, "question": item.question,
                     "choices": item.choices,
                     "correct_answer": item.correct_answer, "user_answer": item.user_answer,
                     "is_correct": item.is_correct, "possible_score": item.possible_score}
                    for item in request.assessment_items
                ],
                "preparation_start": request.preparation_start.strftime(DATETIME_FORMAT) if request.preparation_start else None,
                "exam_date": request.exam_date.strftime(DATETIME_FORMAT) if request.exam_date else None,
                "busy_schedules": busy_schedules,
                "weekly_availability": availability}
        if request.preparation_start and request.exam_date and request.weekly_availability:
            # 날짜별 실제 가용 시간(분)이며 모델이 날짜별 용량을 넘지 않도록 돕습니다.
            user["usable_minutes_by_date"] = {
                day.strftime("%Y/%m/%d"): minutes
                for day, minutes in usable_minutes_by_date(request).items() if minutes > 0}
        failed_calls: set[tuple[str, str, str]] = set()  # 마지막 성공 이후 실패한 (도구, 인자, 오류)입니다.
        contents: list[dict] = [{"role": "user", "parts": [{"text": "Prepare this user's plan using tools. Use the supplied question, choices, answers, and local correctness fields for topic classification. Do not alter or recalculate correctness. Input metadata: " + json.dumps(user, ensure_ascii=False)}]}]  # Gemini 대화 기록입니다.
        for _ in range(self.max_steps):
            result = self._ask_model(contents, tool_schemas(request.certification_id))
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
                name, arguments = call["name"], call.get("args", {})
                outcome = tools.call(name, arguments)
                if name == "build_study_schedule" and isinstance(outcome, dict) and tools.schedule:
                    # 검증된 일정이 만들어지면 남은 호출이나 추가 모델 요청 없이 종료합니다.
                    return tools, _build_summary(outcome)
                if isinstance(outcome, str):
                    failure = (name, json.dumps(arguments, sort_keys=True, ensure_ascii=False), outcome)
                    if failure in failed_calls:
                        raise RuntimeError(f"Agent repeated an unchanged failing {name} call: {outcome}")
                    failed_calls.add(failure)
                else:
                    failed_calls.clear()
                responses.append({"functionResponse": {"name": name, "response": {"result": outcome}}})
            contents.append({"role": "user", "parts": responses})
        raise RuntimeError(f"Agent exceeded max_steps={self.max_steps}; tool trace: {tools.trace.calls}")

