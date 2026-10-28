"""Small OpenRouter function-calling ReAct loop, with bounded iterations."""
from __future__ import annotations

import json

from openrouter_client import create_response, decode_call, function_output, parse_reply, usage_record

from .catalog import CERTIFICATIONS
from .models import PlanRequest, ToolTrace
from .settings import DATETIME_FORMAT, load_api_key
from .tools import PlannerTools, check_initial_capacity, tool_schemas, usable_date_minutes

# Agent settings: the shared transport in openrouter_client.py selects the model and timeout.
AGENT_MAX_STEPS = 6  # Maximum reason/action/observation cycles per user request.
AGENT_REASONING_EFFORT = "low"  # Reasoning effort for topic mapping and minute allocation.
AGENT_MAX_OUTPUT_TOKENS = 16384  # Output bound per model request, including reasoning tokens.
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


class PlanningAgent:
    """OpenRouter 함수 호출을 이용해 도구를 실행하는 ReAct Agent입니다."""

    # 인스턴스 속성: 접속 키, 최대 도구 호출 횟수, 검증 기록용 요청 수와 사용량입니다.
    def __init__(self, api_key: str | None = None, max_steps: int = AGENT_MAX_STEPS):
        """입력 값: 선택적 API 키, 최대 반복 횟수
        출력 값: 설정된 Agent 객체
        기능: 실행 속성을 초기화합니다. 키는 첫 모델 요청 직전에 읽습니다.
        """
        self.api_key = api_key
        self.max_steps = max_steps
        self.api_request_count = 0  # OpenRouter에 전송을 시도한 요청 수입니다.
        self.successful_response_count = 0  # OpenRouter에서 정상 JSON 응답을 받은 요청 수입니다.
        self.usage_records: list[dict] = []  # 응답별 반환 모델과 토큰/비용 사용량입니다.

    def _ask_model(self, input_items: list[dict], tools: list[dict]) -> dict:
        """입력 값: 요청별 대화 기록과 도구 선언
        출력 값: OpenRouter 응답 객체
        기능: 키를 확인한 뒤 낮은 추론 강도로 함수 호출 가능 요청을 한 번 보냅니다.
        """
        self.api_key = self.api_key or load_api_key()
        self.api_request_count += 1
        result = create_response(self.api_key, instructions=AGENT_SYSTEM_PROMPT, input_items=input_items,
                                 tools=tools, reasoning_effort=AGENT_REASONING_EFFORT,
                                 max_output_tokens=AGENT_MAX_OUTPUT_TOKENS)
        self.successful_response_count += 1
        self.usage_records.append(usage_record(result))
        return result

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
        date_minutes = usable_date_minutes(request)  # 날짜별 실제 가용 시간(분)이며 모델이 날짜별 용량을 넘지 않도록 돕습니다.
        if date_minutes is not None:
            user["usable_minutes_by_date"] = date_minutes
        failed_calls: set[tuple[str, str, str]] = set()  # 마지막 성공 이후 실패한 (도구, 인자, 오류)입니다.
        seen_call_ids: set[str] = set()  # 이번 요청에서 받은 함수 호출 ID입니다.
        history: list[dict] = [{"role": "user", "content": "Prepare this user's plan using tools. Use the supplied question, choices, answers, and local correctness fields for topic classification. Do not alter or recalculate correctness. Input metadata: " + json.dumps(user, ensure_ascii=False)}]  # 요청 안에서만 쓰는 모델 대화 기록입니다.
        for _ in range(self.max_steps):
            schemas = tool_schemas(request.certification_id, tools.build_topics(), list(date_minutes or ()))
            output, calls, answer = parse_reply(self._ask_model(history, schemas))
            history.extend(output)
            if not calls:
                if tools.schedule is None:
                    raise RuntimeError(tools.last_tool_error or "Agent finished without calling build_study_schedule")
                return tools, answer
            for item in calls:
                call_id, name, arguments = decode_call(item, (schema["name"] for schema in schemas), seen_call_ids)
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
                response = {"result": outcome}
                if name == "analyze_exam_results" and isinstance(outcome, dict):
                    # 다음 일정 제안이 분석된 필수 토픽과 날짜별 용량을 그대로 쓰도록 알려줍니다.
                    response["build_requirements"] = {"required_topics": tools.build_topics()}
                    if date_minutes is not None:
                        response["build_requirements"]["usable_minutes_by_date"] = date_minutes
                history.append(function_output(call_id, response))
        raise RuntimeError(f"Agent exceeded max_steps={self.max_steps}; tool trace: {tools.trace.calls}")

