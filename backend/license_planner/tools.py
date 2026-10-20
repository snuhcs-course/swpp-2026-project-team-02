"""Documented, constrained tools exposed to the ReAct agent runtime."""
from __future__ import annotations

from typing import Any

from .catalog import CERTIFICATIONS, get_certification
from .models import InsufficientCapacityError, PlanRequest, ProblemResult, ToolTrace
from .scheduler import available_minutes, generate_schedule, summarize_topic_results
from .settings import DATETIME_FORMAT


def check_topic_capacity(request: PlanRequest, topic_count: int, *, exact: bool) -> None:
    """입력 값: 일정 요청, 필수 토픽 수, 토픽 수가 확정 값인지 여부
    출력 값: 없음
    기능: 토픽마다 최소 1분을 줄 수 없으면 NOT_ENOUGH_TIME 오류를 발생시킵니다.
    """
    if (request.preparation_start is None or request.exam_date is None
            or request.weekly_availability is None or request.preparation_start >= request.exam_date):
        return  # 날짜·가능 시간 검증은 일정 생성 단계의 기존 오류가 담당합니다.
    usable = available_minutes(request)
    if usable >= topic_count:
        return
    topics = (f"{topic_count} assessed topic{'s' if topic_count != 1 else ''}" if exact
              else "even 1 assessed topic")
    raise InsufficientCapacityError(
        f"NOT_ENOUGH_TIME: {usable} usable minute{'s' if usable != 1 else ''} between "
        f"preparation_start and exam_date cannot cover {topics} (at least 1 minute each). "
        "Add study windows, start preparation earlier, or choose a later exam date."
    )


def check_initial_capacity(request: PlanRequest) -> None:
    """입력 값: 일정 요청
    출력 값: 없음
    기능: 모델 호출 전에 알 수 있는 필수 토픽 수로 최소 가용 시간을 확인합니다.
    """
    if request.assessment_items:
        # 문항-토픽 분류는 모델이 정하므로 분류 전에는 최소 1개 토픽만 확정됩니다.
        check_topic_capacity(request, 1, exact=False)
    elif request.problem_results:
        check_topic_capacity(request, len({item.topic for item in request.problem_results}), exact=True)
    else:
        check_topic_capacity(request, len(get_certification(request.certification_id).topics), exact=True)


class PlannerTools:
    """입력과 실행 상태를 도구에 전달하며 검증된 결과를 반환합니다."""

    def __init__(self, request: PlanRequest, trace: ToolTrace | None = None):
        """입력 값: 사용자 일정 요청, 선택적 tool trace
        출력 값: 요청 상태를 가진 도구 집합 객체
        기능: 도구 호출 상태와 공유 이력을 초기화합니다.
        """
        # 도구가 참조할 사용자 입력, 호출 이력, 점수 통계, 생성 일정입니다.
        self.request = request
        self.trace = trace or ToolTrace()
        self.topic_statistics = ()  # 시험 결과 분석 도구의 토픽별 통계입니다.
        self.results_analyzed = False  # 분석 도구의 선행 실행 여부입니다.
        self.schedule = None  # 일정 생성 도구가 만든 StudyBlock 목록입니다.
        self.last_tool_error: str | None = None  # 마지막으로 호출한 도구의 오류이며 Agent 오류 보고에 사용합니다.

    def get_certification_profile(self, certification_id: str) -> dict[str, Any] | str:
        """입력 값: 자격증 ID
        출력 값: 자격증 정보 딕셔너리 또는 NOT_FOUND 메시지
        기능: 도구 호출자에게 자격증 이름과 기본 학습 토픽을 알려줍니다.
        """
        try:
            cert = get_certification(certification_id)
        except ValueError as error:
            return str(error)
        return {"certification_id": cert.certification_id, "name": cert.display_name,
                "topics": list(cert.topics)}

    def analyze_exam_results(self, problem_topics: list[dict[str, Any]] | None = None) -> dict[str, Any] | str:
        """입력 값: 없음 (요청 객체의 문제별 결과를 사용)
        출력 값: 전체 점수와 토픽별 배점/획득 점수/점수율 통계
        기능: 문제 주제 이름을 그대로 사용해 토픽별 취약도를 분석합니다.
        """
        results = self.request.problem_results
        if self.request.assessment_items:
            if not isinstance(problem_topics, list):
                return "INVALID_TOOL_CALL: problem_topics must classify every assessment question."
            try:
                cert = get_certification(self.request.certification_id)
                assignments: dict[int, list[tuple[str, float]]] = {}
                for assignment in problem_topics:
                    if not isinstance(assignment, dict):
                        raise ValueError("each problem_topics item must be an object")
                    problem_id, topics = assignment.get("problem_id"), assignment.get("topics")
                    if not isinstance(problem_id, int) or isinstance(problem_id, bool):
                        raise ValueError("problem_id must be an integer")
                    if problem_id in assignments:
                        raise ValueError(f"duplicate topic mapping for problem_id: {problem_id}")
                    if not isinstance(topics, list) or not topics:
                        raise ValueError(f"topics must be a non-empty array for problem_id: {problem_id}")
                    mapped: list[tuple[str, float]] = []
                    for item in topics:
                        if not isinstance(item, dict):
                            raise ValueError("each topic mapping must be an object")
                        topic, weight = item.get("topic"), item.get("weight")
                        if not isinstance(topic, str) or topic not in cert.topics:
                            raise ValueError(f"topic must be one of the certification topics: {cert.topics}")
                        if topic in {name for name, _ in mapped}:
                            raise ValueError(f"duplicate topic {topic!r} for problem_id: {problem_id}")
                        if not isinstance(weight, (int, float)) or isinstance(weight, bool) or not 0 < weight <= 1:
                            raise ValueError("topic weight must be greater than 0 and at most 1")
                        mapped.append((topic, float(weight)))
                    if abs(sum(weight for _, weight in mapped) - 1.0) > 0.001:
                        raise ValueError(f"topic weights must sum to 1 for problem_id: {problem_id}")
                    assignments[problem_id] = mapped
                expected = {item.problem_id for item in self.request.assessment_items}
                if set(assignments) != expected:
                    raise ValueError("problem_topics must map every assessment problem exactly once")
                results = tuple(
                    ProblemResult(item.problem_id, topic, item.possible_score * weight,
                                  item.possible_score * weight if item.is_correct else 0.0, weight)
                    for item in self.request.assessment_items
                    for topic, weight in assignments[item.problem_id]
                )
            except ValueError as error:
                return f"INVALID_TOOL_CALL: {error}"
        elif problem_topics is not None:
            return "INVALID_TOOL_CALL: problem_topics is only used with assessment_results."
        self.topic_statistics = summarize_topic_results(results)
        self.results_analyzed = True
        self._check_required_capacity()
        possible = sum(item.possible_score for item in self.topic_statistics)
        earned = sum(item.earned_score for item in self.topic_statistics)
        return {"total_possible_score": possible, "total_earned_score": earned,
                "overall_score_percent": earned / possible * 100 if possible else None,
                "topic_statistics": [
                    {"topic": item.topic, "possible_score": item.possible_score,
                     "earned_score": item.earned_score, "score_percent": item.score_percent,
                     "problem_count": item.problem_count}
                    for item in self.topic_statistics]}

    def build_study_schedule(self, daily_topic_minutes: list[dict[str, Any]]) -> dict[str, Any] | str:
        """입력 값: Agent가 정한 날짜·주제별 학습 시간(분)
        출력 값: 공부 일정 목록 또는 일정 생성 오류 메시지
        기능: 제안 학습 시간을 검증하고 가용 시간 안에 배치합니다.
        """
        if not self.results_analyzed:
            return "PREREQUISITE: call analyze_exam_results before build_study_schedule."
        self._check_required_capacity()
        try:
            cert = get_certification(self.request.certification_id)
            if not isinstance(daily_topic_minutes, list) or not daily_topic_minutes:
                raise ValueError("daily_topic_minutes must be a non-empty array")
            normalized_plan: list[dict[str, Any]] = []
            minutes_by_topic: dict[str, int] = {}
            for item in daily_topic_minutes:
                if not isinstance(item, dict):
                    raise ValueError("each daily_topic_minutes item must be an object")
                topic, minutes, plan_date = item.get("topic"), item.get("minutes"), item.get("date")
                if not isinstance(topic, str) or not topic.strip():
                    raise ValueError("daily_topic_minutes topic must be a non-empty string")
                if not isinstance(minutes, int) or isinstance(minutes, bool) or minutes <= 0:
                    raise ValueError("daily_topic_minutes minutes must be a positive integer")
                if not isinstance(plan_date, str):
                    raise ValueError("daily_topic_minutes date must use YYYY/MM/DD format")
                normalized_plan.append({"date": plan_date, "topic": topic, "minutes": minutes})
                minutes_by_topic[topic] = minutes_by_topic.get(topic, 0) + minutes
            topics = ({item.topic for item in self.topic_statistics}
                      if self.topic_statistics else set(cert.topics))
            if set(minutes_by_topic) != topics:
                missing, extra = sorted(topics - set(minutes_by_topic)), sorted(set(minutes_by_topic) - topics)
                raise ValueError(f"daily_topic_minutes must cover assessed topics; missing={missing}, extra={extra}")
            self.schedule = generate_schedule(self.request, cert, self.topic_statistics,
                                              normalized_plan)
        except ValueError as error:
            return f"SCHEDULE_ERROR: {error}"
        return {"study_block_count": len(self.schedule),
                "total_planned_minutes": sum(minutes_by_topic.values()),
                "daily_topic_minutes": normalized_plan,
                "uses_personal_availability": self.request.weekly_availability is not None,
                "schedule": [
                    {"schedule_id": b.schedule_id, "topic": b.topic,
                     "start_date": b.start_datetime.strftime(DATETIME_FORMAT),
                     "end_date": b.end_datetime.strftime(DATETIME_FORMAT)}
                    for b in self.schedule]}

    def _check_required_capacity(self) -> None:
        """입력 값: 없음 (분석된 토픽 통계를 사용)
        출력 값: 없음
        기능: 분석으로 확정된 필수 토픽 수보다 가용 시간이 적으면 재시도 없이 중단합니다.
        """
        topic_count = (len(self.topic_statistics) if self.topic_statistics
                       else len(get_certification(self.request.certification_id).topics))
        check_topic_capacity(self.request, topic_count, exact=True)

    def call(self, name: str, arguments: dict[str, Any]) -> Any:
        """입력 값: 도구 이름과 JSON 인자
        출력 값: 해당 도구의 반환 값 또는 유도 가능한 오류 메시지
        기능: 허용된 도구만 찾아 실행하고 호출 이력을 기록합니다.
        """
        # 외부 모델이 선택할 수 있는 도구 이름과 실제 함수를 연결합니다.
        registry = {"get_certification_profile": self.get_certification_profile,
                    "analyze_exam_results": self.analyze_exam_results,
                    "build_study_schedule": self.build_study_schedule}
        if name not in registry:
            error = f"NOT_FOUND: unknown tool {name!r}. Available: {', '.join(registry)}."
            self.last_tool_error = error
            return error
        self.trace.calls.append({"tool": name, "arguments": arguments})
        try:
            result = registry[name](**arguments)
        except TypeError as error:
            result = f"INVALID_TOOL_CALL: {error}. Review the tool schema and retry."
        self.last_tool_error = result if isinstance(result, str) else None
        return result


def tool_schemas(certification_id: str | None = None) -> list[dict[str, Any]]:
    """입력 값: 없음
    출력 값: 모델 함수 호출 형식의 도구 스키마 목록
    기능: 선택 가능한 인자와 허용된 자격증 ID를 모델에 공개합니다.
    """
    selected = CERTIFICATIONS.get(certification_id) if certification_id else None
    known_topics = (list(selected.topics) if selected else
                    sorted({topic for certification in CERTIFICATIONS.values()
                            for topic in certification.topics}))
    return [
        {"name": "get_certification_profile", "description": "Look up the official local plan template for one supported certification.",
         "parameters": {"type": "object", "properties": {"certification_id": {"type": "string", "enum": sorted(CERTIFICATIONS)}}, "required": ["certification_id"]}},
        {"name": "analyze_exam_results", "description": "For assessment_results, examine each question, correct answer, user answer, and locally calculated correctness. Map every question to one or more of this certification's fixed topics and assign contribution weights that sum to 1 per question (for example 0.7 and 0.3 when two topics are needed). Do not infer an answer or correctness. For legacy rows, omit problem_topics. Then aggregate weighted earned/possible points per topic.",
         "parameters": {"type": "object", "properties": {"problem_topics": {"type": "array", "items": {"type": "object", "properties": {"problem_id": {"type": "integer"}, "topics": {"type": "array", "items": {"type": "object", "properties": {"topic": {"type": "string", "enum": known_topics}, "weight": {"type": "number"}}, "required": ["topic", "weight"]}}}, "required": ["problem_id", "topics"]}}}, "required": []}},
        {"name": "build_study_schedule", "description": "Choose dates and total study minutes for every assessed topic using exam date, preparation start, the user's free-form self-assessment, per-topic score statistics, weekly availability, and busy schedules. Do not classify users into fixed readiness levels or apply level multipliers. Assign time directly from these signals, giving weaker topics more time. Balance workload across equivalent available dates; for example, put about 60 minutes on each of two equivalent dates for a 120-minute workload, rather than all 120 on one date. Do not apply a fixed session length or daily session-count cap. Return date as YYYY/MM/DD and positive integer minutes. The application validates the plan and places it within the user's available time. Call analyze_exam_results first.",
         "parameters": {"type": "object", "properties": {"daily_topic_minutes": {"type": "array", "items": {"type": "object", "properties": {"date": {"type": "string"}, "topic": {"type": "string"}, "minutes": {"type": "integer"}}, "required": ["date", "topic", "minutes"]}}}, "required": ["daily_topic_minutes"]}},
    ]

