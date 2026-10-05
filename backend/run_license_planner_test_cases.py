"""Run JSON-input License Planner cases through the local HTTP Planning API."""
from __future__ import annotations

import argparse
import json
import threading
import urllib.error
import urllib.request
from http.server import ThreadingHTTPServer
from pathlib import Path
from typing import Any

from api_server import DEFAULT_API_CONFIG, make_handler
from license_planner.csv_io import parse_datetime
from license_planner.user_availability import parse_weekly_availability

PROJECT_ROOT = Path(__file__).resolve().parent
INPUT_DIR = PROJECT_ROOT / "license_planner_test_inputs"
RESULT_DIR = PROJECT_ROOT / "license_planner_test_results"
CASE_FILE = INPUT_DIR / "license_planner_test_cases.json"
TEXT_ENCODING = "utf-8-sig"
REQUIRED_TOOLS = ("get_certification_profile", "analyze_exam_results",
                  "build_study_schedule")


def load_cases() -> list[dict[str, Any]]:
    """Load self-contained JSON API request fixtures and their expectations."""
    return json.loads(CASE_FILE.read_text(encoding=TEXT_ENCODING))


def post_plan(api_url: str, request_payload: dict[str, Any]) -> tuple[int, dict[str, Any]]:
    """Send one JSON request through POST /api/v1/study-plans."""
    request = urllib.request.Request(
        api_url,
        data=json.dumps(request_payload, ensure_ascii=False).encode("utf-8"),
        headers={"Content-Type": "application/json; charset=utf-8"},
        method="POST",
    )
    try:
        with urllib.request.urlopen(request, timeout=300) as response:
            return response.status, json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as error:
        try:
            payload = json.loads(error.read().decode("utf-8"))
        except (json.JSONDecodeError, UnicodeDecodeError):
            payload = {"error": {"message": str(error)}}
        return error.code, payload


def evaluate_case(api_url: str, case: dict[str, Any]) -> dict[str, Any]:
    """Call the Planning API and validate its JSON schedule response."""
    request_payload = case["request"]
    status, payload = post_plan(api_url, request_payload)
    schedules = payload.get("schedules", []) if isinstance(payload, dict) else []
    study_schedules = [row for row in schedules
                       if str(row.get("schedule_id", "")).startswith("study-")]
    tool_calls = payload.get("tool_calls", []) if isinstance(payload, dict) else []
    called_tools = [call.get("tool") for call in tool_calls]
    availability = parse_weekly_availability(request_payload.get("weekly_availability"))
    availability_respected = all(
        (start := parse_datetime(row["start_date"])).date()
        == (end := parse_datetime(row["end_date"])).date()
        and any(start.time() >= window.start_time and end.time() <= window.end_time
                for window in availability.for_weekday(start.weekday()))
        for row in study_schedules
    )
    busy_schedule_conflict_free = all(
        not (parse_datetime(row["start_date"]) < parse_datetime(busy["end_date"])
             and parse_datetime(busy["start_date"]) < parse_datetime(row["end_date"]))
        for row in study_schedules for busy in request_payload.get("busy_schedules", [])
    )
    first_schedule = study_schedules[0] if study_schedules else None
    actual_first_start = first_schedule.get("start_date") if first_schedule else None
    checks = {
        "http_200": status == 200,
        "schedules_returned": bool(study_schedules),
        "expected_tool_sequence": called_tools == list(REQUIRED_TOOLS),
        "expected_first_topic": (first_schedule or {}).get("topic") == case["expected_first_topic"],
        "expected_first_start": (case.get("expected_first_start") is None
                                  or actual_first_start == case["expected_first_start"]),
        "availability_respected": availability_respected,
        "busy_schedules_conflict_free": busy_schedule_conflict_free,
        "schedule_csv_returned": isinstance(payload.get("schedule_csv"), str)
                                  and bool(payload["schedule_csv"]),
    }
    result = {
        "case_id": case["case_id"],
        "passed": all(checks.values()),
        "http_status": status,
        "checks": checks,
        "expected_first_topic": case["expected_first_topic"],
        "actual_first_topic": (first_schedule or {}).get("topic"),
        "expected_first_start": case.get("expected_first_start"),
        "actual_first_start": actual_first_start,
        "uses_weekly_availability": availability is not None,
        "study_schedule_count": len(study_schedules),
        "called_tools": called_tools,
        "tool_calls": tool_calls,
        "schedules": schedules,
        "schedule_csv": payload.get("schedule_csv"),
        "error": payload.get("error"),
    }
    result_stem = case["case_id"]
    (RESULT_DIR / f"{result_stem}_result.csv").write_text(
        result.get("schedule_csv") or "", encoding="utf-8")
    (RESULT_DIR / f"{result_stem}_tool_trace.json").write_text(
        json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return result


def main() -> int:
    """Start a temporary local server and run selected live API cases."""
    parser = argparse.ArgumentParser(description="Run Gemini Planning API integration cases")
    parser.add_argument("--case-id", help="Run one named case to limit API requests")
    args = parser.parse_args()
    cases = load_cases()
    if args.case_id:
        cases = [case for case in cases if case["case_id"] == args.case_id]
        if not cases:
            parser.error(f"unknown case ID: {args.case_id}")
    RESULT_DIR.mkdir(parents=True, exist_ok=True)

    config = {**DEFAULT_API_CONFIG, "port": 0}
    server = ThreadingHTTPServer(("127.0.0.1", 0), make_handler(config))
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    api_url = f"http://127.0.0.1:{server.server_address[1]}/api/v1/study-plans"
    outcomes = []
    try:
        for case in cases:
            try:
                outcome = evaluate_case(api_url, case)
            except Exception as error:
                outcome = {
                    "case_id": case["case_id"],
                    "passed": False,
                    "error": f"{type(error).__name__}: {error}",
                }
                (RESULT_DIR / f"{case['case_id']}_tool_trace.json").write_text(
                    json.dumps(outcome, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
            outcomes.append(outcome)
            print(f"{outcome['case_id']}: {'PASS' if outcome['passed'] else 'FAIL'}")
            if outcome["passed"]:
                print(f"  first_topic={outcome['actual_first_topic']}; "
                      f"first_start={outcome['actual_first_start']}")
                print(f"  tools={', '.join(outcome['called_tools'])}")
            else:
                print(f"  error={outcome.get('error') or outcome.get('checks')}")
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=5)

    summary_name = (f"{args.case_id}_integration_summary.json" if args.case_id
                    else "license_planner_test_summary.json")
    (RESULT_DIR / summary_name).write_text(
        json.dumps(outcomes, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return 0 if outcomes and all(outcome["passed"] for outcome in outcomes) else 1


if __name__ == "__main__":
    raise SystemExit(main())
