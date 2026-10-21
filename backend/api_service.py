"""웹 API payload와 기존 일정 도메인 계층 사이의 변환 서비스입니다."""
from __future__ import annotations

import csv
import io
import os
from datetime import datetime
from pathlib import Path
from typing import Any

from license_planner.agent import GeminiToolAgent
from license_planner.csv_io import parse_busy_csv, parse_datetime, render_schedule_csv
from license_planner.models import PlanRequest
from license_planner.tools import check_initial_capacity
from assessment_service import get_public_questions, parse_assessment_results
from license_planner.user_availability import (
    WEEKDAY_NAMES, load_personal_availability, parse_weekly_availability,
    save_personal_availability,
)
from schedule_adjuster.agent import GeminiAdjustmentAgent
from schedule_adjuster.csv_io import parse_schedule_rows, render_schedule_csv as render_adjusted_csv
from schedule_adjuster.models import AdjustmentRequest, ScheduleEntry
from schedule_adjuster.availability import (
    load_personal_availability as load_adjuster_availability,
    parse_weekly_availability as parse_adjuster_availability,
)
from schedule_adjuster.schedule_logic import move_study_entry, remove_external_entry
from study_plan_store import StudyPlanStore, PlanConflictError, plan_fields


BACKEND_ROOT = Path(__file__).resolve().parent
STUDY_PLAN_OUTPUT_DIR = Path(os.getenv("APP_STUDY_PLAN_DIR",
                                       BACKEND_ROOT / "license_planner" / "output"))


def _save_study_plan_csv(content: str) -> str:
    return StudyPlanStore(STUDY_PLAN_OUTPUT_DIR).create(content)


def read_study_plan_csv(filename: str) -> tuple[str, str]:
    return StudyPlanStore(STUDY_PLAN_OUTPUT_DIR).download(filename)


def list_study_plans(schedule_csv_file: str | None = None) -> dict[str, Any]:
    return {"plans": StudyPlanStore(STUDY_PLAN_OUTPUT_DIR).list_plans(schedule_csv_file)}


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
    check_initial_capacity(request)  # 모델 설정이나 호출 없이 0분 가용 시간을 거절합니다.
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


# AI-assisted with Codex, 2026-10-17; execution verification pending.
def adjust_schedule(payload: dict[str, Any]) -> dict[str, Any]:
    """Adjust a snapshot and optionally update its existing generated file."""
    if not isinstance(payload, dict):
        raise ValueError("request body must be an object")
    entries = parse_schedule_rows(payload.get("schedules"))
    target_id = payload.get("schedule_id")
    if not isinstance(target_id, str) or not target_id:
        raise ValueError("schedule_id must be a non-empty string")
    target = next((entry for entry in entries if entry.schedule_id == target_id), None)
    if target is None:
        raise ValueError("schedule_id must identify an entry in schedules")
    store = StudyPlanStore(STUDY_PLAN_OUTPUT_DIR)
    snapshot = None
    if "schedule_csv_file" in payload:
        snapshot = store.snapshot(payload["schedule_csv_file"])
        if {e.schedule_id: e for e in entries} != {e.schedule_id: e for e in snapshot.entries}:
            raise PlanConflictError("The selected plan has changed. Reload it before adjusting.")
    if "weekly_availability" in payload:
        availability = parse_adjuster_availability(payload["weekly_availability"])
        if availability is None or not any(availability.for_weekday(day) for day in range(7)):
            raise ValueError("weekly_availability must contain at least one study window")
    else:
        availability = load_adjuster_availability()
    # Preflight constraints and guard agent output before any file mutation.
    expected = (move_study_entry(entries, target_id, availability)
                if target.schedule_type == "study" else remove_external_entry(entries, target_id))
    expected_action = "rescheduled_study" if target.schedule_type == "study" else "cancelled_external"
    request = AdjustmentRequest(target_id, entries, availability)
    tools, summary = GeminiAdjustmentAgent().adjust(request)
    if (tools.outcome is None or tools.outcome.schedule_id != target_id
            or tools.outcome.action != expected_action):
        raise RuntimeError("Agent did not apply the requested schedule adjustment")
    try:
        result_entries = parse_schedule_rows([
            {"schedule_id": e.schedule_id,
             "start_date": e.start_datetime.strftime("%Y/%m/%d/%H/%M"),
             "end_date": e.end_datetime.strftime("%Y/%m/%d/%H/%M"), "topic": e.topic}
            for e in tools.schedule
        ])
    except (ValueError, TypeError, AttributeError) as error:
        raise RuntimeError("Agent returned invalid schedule data") from error
    if {e.schedule_id: e for e in result_entries} != {e.schedule_id: e for e in expected}:
        raise RuntimeError("Agent adjustment violated schedule constraints")
    csv_text = render_adjusted_csv(result_entries)
    result = {"schedules": _csv_rows(csv_text), "schedule_csv": csv_text,
              "action": tools.outcome.action, "target_schedule_id": target_id,
              "agent_summary": summary, "tool_calls": tools.trace.calls}
    if snapshot is not None:
        store.update(snapshot, csv_text)
        result.update(plan_fields(snapshot.identifier))
    return result


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
