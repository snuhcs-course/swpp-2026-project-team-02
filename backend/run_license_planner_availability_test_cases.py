"""Run offline checks for saved availability and schedule placement."""
from __future__ import annotations

import json
import tempfile
from datetime import datetime
from pathlib import Path

from license_planner.catalog import get_certification
from license_planner.csv_io import parse_busy_csv, parse_datetime, render_schedule_csv
from license_planner.models import PlanRequest, ProblemResult
from license_planner.scheduler import generate_schedule, summarize_topic_results
from license_planner.settings import DATETIME_FORMAT
from license_planner.user_availability import (
    load_personal_availability,
    save_personal_availability,
)

PROJECT_ROOT = Path(__file__).resolve().parent
INPUT_DIR = PROJECT_ROOT / "license_planner_availability_test_inputs"
RESULT_DIR = PROJECT_ROOT / "license_planner_availability_test_results"
CASE_FILE = INPUT_DIR / "license_planner_availability_test_cases.json"
TEXT_ENCODING = "utf-8-sig"
CERTIFICATION_ID = "computer_specialist_level_2"
TEST_PREPARATION_START = "2026/10/05/17/00"
TEST_EXAM_DATE = "2026/11/30/23/00"
TEST_TOPIC = "Core Skills"
TEST_TOPIC_RESULTS = summarize_topic_results((ProblemResult(1, TEST_TOPIC, 10, 10),))


def load_cases() -> list[dict]:
    """Load local availability scenarios from their JSON fixture."""
    return json.loads(CASE_FILE.read_text(encoding=TEXT_ENCODING))


def _request(availability, busy_csv: str | None = None) -> PlanRequest:
    return PlanRequest(
        self_assessment="Test user's self assessment",
        problem_results=(ProblemResult(1, TEST_TOPIC, 10, 10),),
        preparation_start=parse_datetime(TEST_PREPARATION_START),
        exam_date=parse_datetime(TEST_EXAM_DATE),
        busy_periods=parse_busy_csv(busy_csv),
        certification_id=CERTIFICATION_ID,
        weekly_availability=availability,
    )


def run_case(case: dict) -> dict:
    """Save/reload a profile and validate the deterministic scheduler against it."""
    case_id = case["case_id"]
    trace: dict = {"case_id": case_id, "description": case["description"]}

    if case.get("expected_error"):
        try:
            with tempfile.TemporaryDirectory() as temp_dir:
                save_personal_availability(case["availability"], Path(temp_dir) / "availability.json")
        except ValueError as error:
            trace.update({
                "expected_error": case["expected_error"],
                "actual_error": str(error),
                "passed": case["expected_error"] in str(error),
            })
        else:
            trace.update({"expected_error": case["expected_error"], "actual_error": None,
                          "passed": False})
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
        request = _request(availability, case.get("busy_csv"))

        if case.get("expected_plan_error"):
            try:
                generate_schedule(request, get_certification(CERTIFICATION_ID), TEST_TOPIC_RESULTS,
                                  [{"date": "2026/10/05", "topic": TEST_TOPIC, "minutes": 60}])
            except ValueError as error:
                actual_error = str(error)
            else:
                actual_error = None
            trace.update({
                "expected_plan_error": case["expected_plan_error"],
                "actual_plan_error": actual_error,
                "passed": actual_error is not None and case["expected_plan_error"] in actual_error,
                "saved_availability_json": saved_json,
                "scheduler": "generate_schedule",
            })
            return trace

        expected_start = parse_datetime(case["expected_start"])
        daily_topic_minutes = [{
            "date": expected_start.strftime("%Y/%m/%d"),
            "topic": TEST_TOPIC,
            "minutes": 60,
        }]
        blocks = generate_schedule(request, get_certification(CERTIFICATION_ID),
                                   TEST_TOPIC_RESULTS, daily_topic_minutes)
        first_start = blocks[0].start_datetime if blocks else None
        called_availability = availability is not None
        actual_start = first_start.strftime(DATETIME_FORMAT) if first_start else None
        passed = (bool(blocks) and first_start == expected_start
                  and called_availability == case["expect_personal_availability"])
        trace.update({
            "passed": passed,
            "expected_first_start": case["expected_start"],
            "actual_first_start": actual_start,
            "expected_personal_availability": case["expect_personal_availability"],
            "used_personal_availability": called_availability,
            "saved_availability_json": saved_json,
            "scheduler": "generate_schedule",
            "study_block_count": len(blocks),
            "result_csv": f"{case_id}_result.csv",
        })
        if blocks:
            (RESULT_DIR / f"{case_id}_result.csv").write_text(
                render_schedule_csv(request.busy_periods, blocks), encoding="utf-8")
    return trace


def main() -> int:
    """Run each offline case and write traces and a summary JSON document."""
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
            print(f"  first_start={outcome['actual_first_start']}")
        elif "actual_plan_error" in outcome:
            print(f"  error={outcome['actual_plan_error']}")
        trace_path = RESULT_DIR / f"{outcome['case_id']}_trace.json"
        trace_path.write_text(json.dumps(outcome, ensure_ascii=False, indent=2) + "\n",
                              encoding="utf-8")

    summary_path = RESULT_DIR / "license_planner_availability_test_summary.json"
    summary_path.write_text(json.dumps(outcomes, ensure_ascii=False, indent=2) + "\n",
                            encoding="utf-8")
    print(f"Summary: {summary_path}")
    return 0 if outcomes and all(outcome["passed"] for outcome in outcomes) else 1


if __name__ == "__main__":
    raise SystemExit(main())
