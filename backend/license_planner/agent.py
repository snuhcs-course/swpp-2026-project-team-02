"""Small Gemini function-calling ReAct loop, with bounded iterations."""
from __future__ import annotations

import json

from .catalog import CERTIFICATIONS
from .models import PlanRequest, ToolTrace
from openrouter_client import assistant_message, request_completion, tool_arguments
from .settings import DATETIME_FORMAT, OPENROUTER_API_KEY, OPENROUTER_MODEL
from .tools import PlannerTools, tool_schemas

# Agent/API settings: move these values here when changing Gemini or its behavior.
OPENROUTER_REQUEST_TIMEOUT_SECONDS = 45  # Maximum wait for one model API response.
AGENT_MAX_STEPS = 6  # Maximum reason/action/observation cycles per user request.
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

class GeminiToolAgent:
    """OpenRouter function calling으로 도구를 실행하는 ReAct Agent입니다.

    The legacy class name is retained so existing service-level test seams remain stable.
    """

    # 인스턴스 속성: 접속 키, 모델 ID, 최대 도구 호출 횟수입니다.
    def __init__(self, api_key: str | None = None, model: str = OPENROUTER_MODEL, max_steps: int = AGENT_MAX_STEPS):
        """입력 값: 선택적 API 키, 모델 ID, 최대 반복 횟수
        출력 값: 설정된 Agent 객체
        기능: .env 또는 환경 변수의 API 키를 검증하고 실행 속성을 초기화합니다.
        """
        self.api_key = api_key or OPENROUTER_API_KEY
        self.model = model
        self.max_steps = max_steps
        self.api_request_count = 0
        self.successful_response_count = 0
        if not self.api_key:
            raise ValueError("OPENROUTER_API_KEY is required for online agent mode")

    def _ask_model(self, contents: list[dict], tools: list[dict]) -> dict:
        """Send one OpenRouter Chat Completions request."""
        self.api_request_count += 1
        result = request_completion(api_key=self.api_key, model=self.model, messages=contents,
                                    declarations=tools, timeout_seconds=OPENROUTER_REQUEST_TIMEOUT_SECONDS)
        self.successful_response_count += 1
        return result

    def run(self, request: PlanRequest) -> tuple[PlannerTools, str]:
        """입력 값: 일정 생성 요청
        출력 값: 도구 실행 결과 객체와 Agent 요약 문자열
        기능: ReAct 도구 호출 루프를 실행하고 생성된 일정을 확인합니다.
        """
        cert_ids = sorted(CERTIFICATIONS)  # 현재 사용 가능한 자격증 ID 목록입니다.
        if request.certification_id not in cert_ids:
            raise ValueError(f"Unsupported certification {request.certification_id!r}; choose from {cert_ids}")
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
        contents: list[dict] = [
            {"role": "system", "content": AGENT_SYSTEM_PROMPT},
            {"role": "user", "content": "Prepare this user's plan using tools. Use the supplied question, choices, answers, and local correctness fields for topic classification. Do not alter or recalculate correctness. Input metadata: " + json.dumps(user, ensure_ascii=False)},
        ]
        for step in range(self.max_steps):
            try:
                result = self._ask_model(contents, tool_schemas(request.certification_id))
            except RuntimeError as error:
                completed_tools = [item["tool"] for item in tools.trace.calls]
                raise RuntimeError(
                    f"OpenRouter planner failed at step {step + 1}; "
                    f"completed_tools={completed_tools}: {error}"
                ) from error
            candidate = assistant_message(result)
            contents.append(candidate)
            calls = candidate.get("tool_calls") or []
            if not calls:
                answer = candidate.get("content") or ""
                if tools.schedule is None:
                    raise RuntimeError(tools.last_tool_error or "Agent finished without calling build_study_schedule")
                return tools, answer
            for call in calls:
                name = call.get("function", {}).get("name", "")
                outcome = tools.call(name, tool_arguments(call))
                contents.append({"role": "tool", "tool_call_id": call.get("id", name),
                                 "name": name, "content": json.dumps({"result": outcome}, ensure_ascii=False)})
        completed_tools = [item["tool"] for item in tools.trace.calls]
        raise RuntimeError(f"Agent exceeded max_steps={self.max_steps}; completed_tools={completed_tools}")

