"""개인 가능 시간 저장과 일정 반영을 검증하는 오프라인 테스트 러너입니다."""
from __future__ import annotations

import json
import tempfile
from datetime import datetime
from pathlib import Path

from license_planner.csv_io import (parse_busy_csv, parse_datetime,
                                    parse_problem_results_csv, render_schedule_csv)
from license_planner.models import PlanRequest
from license_planner.settings import (DEFAULT_CERTIFICATION_ID,
                                      DEFAULT_STUDY_DAYS_PER_WEEK,
                                      DEFAULT_STUDY_WINDOW_START,
                                      DATETIME_FORMAT)
from license_planner.tools import PlannerTools
from license_planner.user_availability import (load_personal_availability,
                                               save_personal_availability)

# 테스트 입력·출력 폴더와 공통 진단 결과 및 기간입니다.
PROJECT_ROOT = Path(__file__).resolve().parent
INPUT_DIR = PROJECT_ROOT / "license_planner_availability_test_inputs"
RESULT_DIR = PROJECT_ROOT / "license_planner_availability_test_results"
CASE_FILE = INPUT_DIR / "license_planner_availability_test_cases.json"
TEST_RESULTS_CSV = "problem_id,topic,possible_score,earned_score\n1,Core Skills,10,10\n"
TEST_SELF_ASSESSMENT = "엑셀에 익숙하고 능숙합니다."
TEST_PREPARATION_START = "2026/10/05/17/00"
TEST_EXAM_DATE = "2026/11/30/23/00"
TEXT_ENCODING = "utf-8-sig"
REQUIRED_TOOL_CALLS = ("analyze_exam_results", "assess_readiness", "build_study_schedule")


def load_cases() -> list[dict]:
    """입력 값: 없음
    출력 값: JSON 테스트 케이스 목록
    기능: 개인 가능 시간 테스트 정의 파일을 읽습니다.
    """
    return json.loads(CASE_FILE.read_text(encoding=TEXT_ENCODING))


def run_case(case: dict) -> dict:
    """입력 값: 개인 가능 시간 테스트 케이스 딕셔너리
    출력 값: 판정, 도구 이력, 생성 CSV 경로를 포함한 trace 딕셔너리
    기능: 임시 JSON 설정으로 시간표 저장/불러오기와 실제 계획 반영을 검증합니다.
    """
    case_id = case["case_id"]
    trace: dict = {"case_id": case_id, "description": case["description"]}
    result_csv_name = f"{case_id}_result.csv"
    if case.get("expected_error"):
        try:
            with tempfile.TemporaryDirectory() as temp_dir:
                save_personal_availability(case["availability"], Path(temp_dir) / "availability.json")
        except ValueError as error:
            trace.update({"expected_error": case["expected_error"], "actual_error": str(error),
                          "passed": case["expected_error"] in str(error)})
        else:
            trace.update({"expected_error": case["expected_error"], "actual_error": None, "passed": False})
        return trace

    with tempfile.TemporaryDirectory() as temp_dir:
        availability_path = Path(temp_dir) / "availability.json"
        if case.get("initial_availability") is not None:
            save_personal_availability(case["initial_availability"], availability_path)
        if case.get("availability") is not None:
            save_personal_availability(case["availability"], availability_path)
        availability = load_personal_availability(availability_path)
        saved_json = (json.loads(availability_path.read_text(encoding="utf-8"))
                      if availability_path.exists() else None)

        request = PlanRequest(
            self_assessment=TEST_SELF_ASSESSMENT,
            problem_results=parse_problem_results_csv(TEST_RESULTS_CSV),
            preparation_start=parse_datetime(TEST_PREPARATION_START),
            exam_date=parse_datetime(TEST_EXAM_DATE),
            busy_periods=parse_busy_csv(case.get("busy_csv")),
            certification_id=DEFAULT_CERTIFICATION_ID,
            study_days_per_week=DEFAULT_STUDY_DAYS_PER_WEEK,
            weekly_availability=availability,
        )
        tools = PlannerTools(request)
        tools.call("analyze_exam_results", {})
        tools.call("assess_readiness", {})
        build_result = tools.call("build_study_schedule", {})
        schedule = tools.schedule or []
        first_start = schedule[0].start_datetime.strftime(DATETIME_FORMAT) if schedule else None
        expected_start = case["expected_start"]
        if expected_start == "default_window_start":
            default_start = datetime.combine(request.preparation_start.date(), DEFAULT_STUDY_WINDOW_START)
            expected_start = max(default_start, request.preparation_start).strftime(DATETIME_FORMAT)
        actual_personal_availability = bool(build_result.get("uses_personal_availability"))
        called_tools = [call["tool"] for call in tools.trace.calls]
        passed = (
            bool(schedule)
            and first_start == expected_start
            and actual_personal_availability == case["expect_personal_availability"]
            and called_tools == list(REQUIRED_TOOL_CALLS)
        )
        trace.update({
            "passed": passed,
            "expected_first_start": expected_start,
            "actual_first_start": first_start,
            "expected_personal_availability": case["expect_personal_availability"],
            "used_personal_availability": actual_personal_availability,
            "saved_availability_json": saved_json,
            "expected_tool_calls": list(REQUIRED_TOOL_CALLS),
            "called_tools": called_tools,
            "tool_calls": tools.trace.calls,
            "study_block_count": len(schedule),
            "result_csv": result_csv_name,
        })
        if schedule:
            (RESULT_DIR / result_csv_name).write_text(
                render_schedule_csv(request.busy_periods, schedule), encoding="utf-8")
    return trace


def main() -> int:
    """입력 값: 없음
    출력 값: 모든 케이스 통과 시 0, 하나라도 실패하면 1
    기능: 모든 오프라인 케이스를 실행하고 결과 CSV와 trace 요약을 저장합니다.
    """
    RESULT_DIR.mkdir(parents=True, exist_ok=True)
    outcomes = []
    for case in load_cases():
        try:
            outcomes.append(run_case(case))
        except Exception as error:
            outcomes.append({"case_id": case.get("case_id", "unknown"), "passed": False,
                             "error": f"{type(error).__name__}: {error}"})
    for outcome in outcomes:
        print(f"{outcome['case_id']}: {'PASS' if outcome['passed'] else 'FAIL'}")
        if "actual_first_start" in outcome:
            print(f"  first_start={outcome['actual_first_start']}; tools={outcome['called_tools']}")
        elif "actual_error" in outcome:
            print(f"  error={outcome['actual_error']}")
        trace_path = RESULT_DIR / f"{outcome['case_id']}_trace.json"
        trace_path.write_text(json.dumps(outcome, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    summary_path = RESULT_DIR / "license_planner_availability_test_summary.json"
    summary_path.write_text(json.dumps(outcomes, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"Summary: {summary_path}")
    return 0 if outcomes and all(outcome["passed"] for outcome in outcomes) else 1


if __name__ == "__main__":
    raise SystemExit(main())
