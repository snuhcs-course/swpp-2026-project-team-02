"""Documented, constrained tools exposed to the ReAct agent runtime."""
from __future__ import annotations

from typing import Any

from .catalog import CERTIFICATIONS, get_certification
from .models import PlanRequest, ToolTrace
from .scheduler import generate_schedule, readiness_level, summarize_topic_results
from .settings import DATETIME_FORMAT


class PlannerTools:
    """입력과 실행 상태를 도구에 전달하며 검증된 결과를 반환합니다."""

    def __init__(self, request: PlanRequest, trace: ToolTrace | None = None):
        """입력 값: 사용자 일정 요청, 선택적 tool trace
        출력 값: 요청 상태를 가진 도구 집합 객체
        기능: 도구 호출 상태와 공유 이력을 초기화합니다.
        """
        # 도구가 참조할 사용자 입력, 호출 이력, 수준, 생성 일정입니다.
        self.request = request
        self.trace = trace or ToolTrace()
        self.level: str | None = None  # 평가 도구가 정한 준비 수준입니다.
        self.topic_statistics = ()  # 시험 결과 분석 도구의 토픽별 통계입니다.
        self.results_analyzed = False  # 분석 도구의 선행 실행 여부입니다.
        self.schedule = None  # 일정 생성 도구가 만든 StudyBlock 목록입니다.
        self.last_tool_error: str | None = None  # 마지막으로 호출한 도구의 오류이며 Agent 오류 보고에 사용합니다.

    def get_certification_profile(self, certification_id: str) -> dict[str, Any] | str:
        """입력 값: 자격증 ID
        출력 값: 자격증 정보 딕셔너리 또는 NOT_FOUND 메시지
        기능: 도구 호출자에게 사용 가능한 범위와 점수 기준을 알려줍니다.
        """
        try:
            cert = get_certification(certification_id)
        except ValueError as error:
            return str(error)
        return {"certification_id": cert.certification_id, "name": cert.display_name,
                "topics": list(cert.topics), "score_thresholds": list(cert.score_thresholds),
                "default_study_days_per_topic": cert.default_study_days_per_topic}

    def assess_readiness(self) -> dict[str, Any] | str:
        """입력 값: 없음 (요청 객체의 자기평가와 분석된 문항 통계를 사용)
        출력 값: 준비 수준 딕셔너리 또는 입력 오류 메시지
        기능: 사용자의 수준을 평가하고 이후 도구가 사용할 상태에 저장합니다.
        """
        if not self.results_analyzed:
            return "PREREQUISITE: call analyze_exam_results before assess_readiness."
        try:
            cert = get_certification(self.request.certification_id)
            self.level = readiness_level(self.request.self_assessment, self.topic_statistics, cert)
        except (ValueError, TypeError) as error:
            return f"INVALID_INPUT: {error}. Verify the self-assessment and per-problem result CSV."
        return {"level": self.level, "used_self_assessment": bool(self.request.self_assessment),
                "used_problem_results": bool(self.request.problem_results)}

    def analyze_exam_results(self) -> dict[str, Any] | str:
        """입력 값: 없음 (요청 객체의 문제별 결과를 사용)
        출력 값: 전체 점수와 토픽별 배점/획득 점수/점수율 통계
        기능: 문제 주제 이름을 그대로 사용해 토픽별 취약도를 분석합니다.
        """
        self.topic_statistics = summarize_topic_results(self.request.problem_results)
        self.results_analyzed = True
        possible = sum(item.possible_score for item in self.topic_statistics)
        earned = sum(item.earned_score for item in self.topic_statistics)
        return {"total_possible_score": possible, "total_earned_score": earned,
                "overall_score_percent": earned / possible * 100 if possible else None,
                "topic_statistics": [
                    {"topic": item.topic, "possible_score": item.possible_score,
                     "earned_score": item.earned_score, "score_percent": item.score_percent,
                     "problem_count": item.problem_count}
                    for item in self.topic_statistics]}

    def build_study_schedule(self) -> dict[str, Any] | str:
        """입력 값: 없음 (요청과 평가 도구의 상태를 사용)
        출력 값: 공부 일정 목록 또는 일정 생성 오류 메시지
        기능: 바쁜 일정 및 시험일을 고려한 공부 일정을 만듭니다.
        """
        if not self.results_analyzed or self.level is None:
            return "PREREQUISITE: call analyze_exam_results and assess_readiness before build_study_schedule."
        try:
            cert = get_certification(self.request.certification_id)
            self.schedule = generate_schedule(self.request, cert, self.topic_statistics, self.level)
        except ValueError as error:
            return f"SCHEDULE_ERROR: {error}"
        return {"study_block_count": len(self.schedule),
                "uses_personal_availability": self.request.weekly_availability is not None,
                "schedule": [
                    {"schedule_id": b.schedule_id, "topic": b.topic,
                     "start_date": b.start_datetime.strftime(DATETIME_FORMAT),
                     "end_date": b.end_datetime.strftime(DATETIME_FORMAT)}
                    for b in self.schedule]}

    def call(self, name: str, arguments: dict[str, Any]) -> Any:
        """입력 값: 도구 이름과 JSON 인자
        출력 값: 해당 도구의 반환 값 또는 유도 가능한 오류 메시지
        기능: 허용된 도구만 찾아 실행하고 호출 이력을 기록합니다.
        """
        # 외부 모델이 선택할 수 있는 도구 이름과 실제 함수를 연결합니다.
        registry = {"get_certification_profile": self.get_certification_profile,
                    "analyze_exam_results": self.analyze_exam_results,
                    "assess_readiness": self.assess_readiness,
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


def tool_schemas() -> list[dict[str, Any]]:
    """입력 값: 없음
    출력 값: 모델 함수 호출 형식의 도구 스키마 목록
    기능: 선택 가능한 인자와 허용된 자격증 ID를 모델에 공개합니다.
    """
    return [
        {"name": "get_certification_profile", "description": "Look up the official local plan template for one supported certification.",
         "parameters": {"type": "object", "properties": {"certification_id": {"type": "string", "enum": sorted(CERTIFICATIONS)}}, "required": ["certification_id"]}},
        {"name": "analyze_exam_results", "description": "Aggregate awarded and earned points for every topic from the supplied exam result rows. Topics are data-driven; no fixed topic enum is assumed.",
         "parameters": {"type": "object", "properties": {}, "required": []}},
        {"name": "assess_readiness", "description": "Classify readiness from the self-assessment and the weighted score calculated from problem results. Call analyze_exam_results first.",
         "parameters": {"type": "object", "properties": {}, "required": []}},
        {"name": "build_study_schedule", "description": "Create study sessions using this user's saved weekly availability when present, otherwise the shared default window. Prioritize lower-scoring topics and avoid busy datetime ranges. Call analyze_exam_results and assess_readiness first.",
         "parameters": {"type": "object", "properties": {}, "required": []}},
    ]

