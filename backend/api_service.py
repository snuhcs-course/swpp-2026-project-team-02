"""웹 API payload와 기존 일정 도메인 계층 사이의 변환 서비스입니다."""
from __future__ import annotations

import csv
import io
import re
from datetime import datetime
from pathlib import Path
from uuid import uuid4
from typing import Any

from license_planner.agent import GeminiToolAgent
from license_planner.csv_io import parse_busy_csv, parse_datetime, render_schedule_csv
from license_planner.models import PlanRequest
from assessment_service import get_public_questions, parse_assessment_results
from license_planner.user_availability import (
    WEEKDAY_NAMES, load_personal_availability, parse_weekly_availability,
    save_personal_availability,
)
from schedule_adjuster.agent import GeminiAdjustmentAgent
from schedule_adjuster.csv_io import render_schedule_csv as render_adjusted_csv
from schedule_adjuster.models import AdjustmentRequest, ScheduleEntry
from schedule_adjuster.availability import load_personal_availability as load_adjuster_availability


BACKEND_ROOT = Path(__file__).resolve().parent
STUDY_PLAN_OUTPUT_DIR = BACKEND_ROOT / "license_planner" / "output"


def _save_study_plan_csv(content: str) -> str:
    """입력 값: 생성된 일정 CSV 문자열
    출력 값: backend 폴더 기준 재사용 가능한 CSV 상대 경로
    기능: 각 Planning 요청 결과를 고유한 파일로 저장합니다.
    """
    STUDY_PLAN_OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
    timestamp = datetime.now().astimezone().strftime("%Y%m%dT%H%M%S%f%z")
    output_path = STUDY_PLAN_OUTPUT_DIR / f"study_plan_{timestamp}_{uuid4().hex[:8]}.csv"
    with output_path.open("x", encoding="utf-8", newline="") as output_file:
        output_file.write(content)
    return output_path.relative_to(BACKEND_ROOT).as_posix()


def read_study_plan_csv(filename: str) -> tuple[str, str]:
    """Read a generated plan by its server-issued basename, without path traversal."""
    if (not isinstance(filename, str) or "/" in filename or "\\" in filename
            or not re.fullmatch(r"study_plan_[0-9A-Fa-fT+_-]+\.csv", filename)):
        raise FileNotFoundError("Study plan CSV not found")
    output_path = (STUDY_PLAN_OUTPUT_DIR / filename).resolve()
    if output_path.parent != STUDY_PLAN_OUTPUT_DIR.resolve() or not output_path.is_file():
        raise FileNotFoundError("Study plan CSV not found")
    return output_path.read_text(encoding="utf-8"), filename


def _datetime(value: Any, field: str) -> datetime:
    """입력 값: API 날짜 문자열과 필드명
    출력 값: 검증된 datetime 객체
    기능: 일정 날짜를 내부 날짜 객체로 변환하고 오류에 필드명을 덧붙입니다.
    """
    if not isinstance(value, str):
        raise ValueError(f"{field} must use YYYY/MM/DD/HH/MM format")
    try:
        return parse_datetime(value)
    except ValueError as error:
        raise ValueError(f"{field}: {error}") from error


def _busy_csv(rows: list[dict[str, Any]]) -> str:
    """입력 값: 외부 일정 객체 목록
    출력 값: 기존 License Planner CSV 파서가 읽을 수 있는 문자열
    기능: API 일정 배열을 일정 CSV 형식으로 변환합니다.
    """
    output = io.StringIO(newline="")
    writer = csv.writer(output, lineterminator="\n")
    writer.writerow(("schedule_id", "start_date", "end_date", "topic"))
    for row in rows:
        if not isinstance(row, dict):
            raise ValueError("each busy_schedules item must be an object")
        writer.writerow((row.get("schedule_id", ""), row.get("start_date", ""),
                         row.get("end_date", ""), row.get("topic", "")))
    return output.getvalue()


def build_plan_request(payload: dict[str, Any], *, require_assessment: bool = False) -> PlanRequest:
    """입력 값: 학습 계획 API 요청 JSON 객체
    출력 값: 검증된 PlanRequest
    기능: JSON 요청 필드를 내부 계획 요청 객체로 변환합니다.
    """
    if not isinstance(payload, dict):
        raise ValueError("request body must be a JSON object")
    scored_fields = {"problem_results", "problem_results_csv"} & payload.keys()
    assessment_fields = {"assessment_answers", "assessment_results"} & payload.keys()
    if "assessment_answers" in payload:
        raise ValueError("assessment_answers is no longer supported; submit the locally-scored assessment_results JSON")
    if len(assessment_fields) > 1:
        raise ValueError("provide assessment_results only")
    if scored_fields:
        raise ValueError("CSV/scored problem inputs are no longer supported; submit assessment_results JSON")
    assessment_items = ()
    if "assessment_results" in payload:
        assessment_items = parse_assessment_results(
            str(payload.get("certification_id", "computer_specialist_level_2")),
            payload["assessment_results"],
        )
        problem_results = ()
    else:
        if require_assessment:
            raise ValueError("assessment_results is required")
        problem_results = ()
    busy = payload.get("busy_schedules") or []
    if not isinstance(busy, list):
        raise ValueError("busy_schedules must be an array or null")
    raw_availability = payload.get("weekly_availability")
    availability = (parse_weekly_availability(raw_availability) if raw_availability is not None
                    else load_personal_availability())
    if availability is None or not any(availability.for_weekday(day) for day in range(7)):
        raise ValueError(
            "weekly_availability is required. Enter at least one weekday and available time window."
        )
    if payload.get("preparation_start") is None:
        raise ValueError("preparation_start is required")
    if payload.get("exam_date") is None:
        raise ValueError("exam_date is required")
    preparation_start = _datetime(payload["preparation_start"], "preparation_start")
    exam_date = _datetime(payload["exam_date"], "exam_date")
    if preparation_start >= exam_date:
        raise ValueError("preparation_start must be before exam_date")
    return PlanRequest(
        self_assessment=payload.get("self_assessment"),
        problem_results=problem_results,
        preparation_start=preparation_start,
        exam_date=exam_date,
        assessment_items=assessment_items,
        busy_periods=parse_busy_csv(_busy_csv(busy)),
        certification_id=payload.get("certification_id", "computer_specialist_level_2"),
        study_days_per_week=payload.get("study_days_per_week", 5),
        weekly_availability=availability,
    )


def create_study_plan(payload: dict[str, Any]) -> dict[str, Any]:
    """입력 값: 학습 계획 API 요청 JSON 객체
    출력 값: 생성 CSV와 일정 행을 포함한 JSON 객체
    기능: JSON 요청을 검증하고 Gemini Agent로 계획을 만듭니다.
    """
    request = build_plan_request(payload, require_assessment=True)
    tools, summary = GeminiToolAgent().run(request)
    if tools.schedule is None:
        raise RuntimeError("Agent did not generate a schedule")
    csv_text = render_schedule_csv(request.busy_periods, tools.schedule)
    csv_file = _save_study_plan_csv(csv_text)
    return {"schedules": _csv_rows(csv_text), "schedule_csv": csv_text,
            "schedule_csv_file": csv_file,
            "schedule_csv_url": f"/api/v1/study-plans/{Path(csv_file).name}",
            "agent_summary": summary, "tool_calls": tools.trace.calls}


def _csv_rows(content: str) -> list[dict[str, str]]:
    """입력 값: 일정 CSV 문자열
    출력 값: 일정 행 객체 목록
    기능: 헤더 포함 CSV를 웹 응답용 객체 목록으로 바꿉니다.
    """
    return list(csv.DictReader(io.StringIO(content)))


def adjust_schedule(payload: dict[str, Any]) -> dict[str, Any]:
    """입력 값: 대상 일정 ID와 전체 일정이 담긴 API 요청 객체
    출력 값: 조정된 CSV와 일정 행을 포함한 JSON 객체
    기능: 일정을 검증하고 기존 Schedule Adjuster Agent를 실행합니다.
    """
    if not isinstance(payload, dict) or not isinstance(payload.get("schedules"), list):
        raise ValueError("schedules must be an array")
    entries: list[ScheduleEntry] = []
    for row in payload["schedules"]:
        if not isinstance(row, dict):
            raise ValueError("each schedules item must be an object")
        start, end = _datetime(row.get("start_date"), "start_date"), _datetime(row.get("end_date"), "end_date")
        if start >= end:
            raise ValueError("schedule start_date must be before end_date")
        entries.append(ScheduleEntry(str(row.get("schedule_id", "")), start, end, str(row.get("topic", ""))))
    availability = load_adjuster_availability()
    request = AdjustmentRequest(str(payload.get("schedule_id", "")), tuple(entries), availability)
    tools, summary = GeminiAdjustmentAgent().adjust(request)
    if tools.outcome is None:
        raise RuntimeError("Agent did not apply a schedule adjustment")
    csv_text = render_adjusted_csv(tools.schedule)
    return {"schedules": _csv_rows(csv_text), "schedule_csv": csv_text,
            "action": tools.outcome.action, "target_schedule_id": tools.outcome.schedule_id,
            "agent_summary": summary, "tool_calls": tools.trace.calls}


def read_availability() -> dict[str, Any]:
    """입력 값: 없음
    출력 값: 주간 가능 시간 JSON 객체
    기능: 저장된 개인 가능 시간을 요일별 JSON으로 반환합니다.
    """
    availability = load_personal_availability()
    if availability is None:
        return {day: [] for day in WEEKDAY_NAMES}
    return {day: [{"start": window.start_time.strftime("%H:%M"),
                   "end": window.end_time.strftime("%H:%M")}
                  for window in availability.for_weekday(index)]
            for index, day in enumerate(WEEKDAY_NAMES)}


def write_availability(payload: dict[str, Any]) -> dict[str, Any]:
    """입력 값: 요일별 주간 가능 시간 JSON 객체
    출력 값: 저장된 가능 시간 JSON 객체
    기능: 가능 시간을 검증·저장하고 정규화된 값을 반환합니다.
    """
    saved = save_personal_availability(payload)
    return {day: [{"start": window.start_time.strftime("%H:%M"),
                   "end": window.end_time.strftime("%H:%M")}
                  for window in saved.for_weekday(index)]
            for index, day in enumerate(WEEKDAY_NAMES)}
