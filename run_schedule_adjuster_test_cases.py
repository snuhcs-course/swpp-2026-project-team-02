"""Run live Gemini Agent scenarios and save adjustment CSVs and tool traces."""
from __future__ import annotations

import json
import argparse
from pathlib import Path

from schedule_adjuster.agent import GeminiAdjustmentAgent
from schedule_adjuster.availability import load_personal_availability
from schedule_adjuster.csv_io import parse_schedule_csv, render_schedule_csv
from schedule_adjuster.models import AdjustmentRequest
from schedule_adjuster.schedule_logic import intervals_overlap
from schedule_adjuster.settings import USER_AVAILABILITY_PATH

# Runner 경로와 파일 인코딩 설정입니다.
PROJECT_ROOT = Path(__file__).resolve().parent
INPUT_DIR = PROJECT_ROOT / "schedule_adjuster_test_inputs"
RESULT_DIR = PROJECT_ROOT / "schedule_adjuster_test_results"
CASE_FILE = INPUT_DIR / "schedule_adjuster_test_cases.json"
TEXT_ENCODING = "utf-8-sig"
EXPECTED_INSPECT_TOOL = "inspect_schedule"
EXPECTED_TOPIC_INSPECT_TOOL = "inspect_topic_schedule"


def load_cases() -> list[dict]:
    """입력 값: 없음
    출력 값: JSON에 정의된 테스트 케이스 목록
    기능: Schedule Adjuster의 케이스 설정 파일을 읽습니다.
    """
    return json.loads(CASE_FILE.read_text(encoding=TEXT_ENCODING))


def run_case(agent: GeminiAdjustmentAgent, case: dict) -> dict:
    """입력 값: Gemini Agent 객체와 케이스 설정
    출력 값: 통과 여부, 실제 도구 호출, 결과 파일 정보를 담은 딕셔너리
    기능: 케이스를 실행하고 결과 CSV 및 도구 호출 trace를 저장합니다.
    """
    case_id = case["case_id"]
    request_count_before = agent.api_request_count
    csv_path = INPUT_DIR / case["schedule_csv"]
    original = parse_schedule_csv(csv_path.read_text(encoding=TEXT_ENCODING))
    availability_path = (INPUT_DIR / case["availability_file"] if case.get("availability_file")
                         else USER_AVAILABILITY_PATH)
    weekly_availability = load_personal_availability(availability_path)
    request = AdjustmentRequest(case["schedule_id"], original, weekly_availability)
    tools, summary = agent.adjust(request)
    called_tools = [call["tool"] for call in tools.trace.calls]
    original_by_id = {entry.schedule_id: entry for entry in original}
    adjusted_by_id = {entry.schedule_id: entry for entry in tools.schedule}
    target_before = original_by_id.get(case["schedule_id"])
    target_after = adjusted_by_id.get(case["schedule_id"])
    other_topic_sessions = [entry for entry in original
                            if target_before and target_before.topic
                            and entry.schedule_id != case["schedule_id"]
                            and entry.schedule_type == "study"
                            and entry.topic.casefold() == target_before.topic.casefold()]
    topic_order_preserved = (
        not other_topic_sessions or target_after is None
        or target_after.start_datetime >= max(entry.end_datetime for entry in other_topic_sessions)
    )
    target_has_no_conflict = (target_after is None or all(
        not intervals_overlap(target_after.start_datetime, target_after.end_datetime,
                              entry.start_datetime, entry.end_datetime)
        for entry in tools.schedule if entry.schedule_id != case["schedule_id"]
    ))
    expected_calls = [EXPECTED_INSPECT_TOOL]
    if target_before and target_before.schedule_type == "study" and target_before.topic:
        expected_calls.append(EXPECTED_TOPIC_INSPECT_TOOL)
    expected_calls.append(case["expected_change_tool"])
    other_rows_preserved = all(
        adjusted_by_id.get(schedule_id) == entry
        for schedule_id, entry in original_by_id.items()
        if schedule_id != case["schedule_id"]
    )
    target_adjusted = (
        target_after is None if case["expected_action"] == "cancelled_external"
        else target_before is not None and target_after is not None
        and target_after.start_datetime >= target_before.end_datetime
        and (target_after.end_datetime - target_after.start_datetime)
        == (target_before.end_datetime - target_before.start_datetime)
        and target_after.topic == target_before.topic
        and topic_order_preserved
    )
    availability_respected = True
    if weekly_availability is not None and target_after is not None:
        windows = weekly_availability.for_weekday(target_after.start_datetime.weekday())
        availability_respected = (
            target_after.start_datetime.date() == target_after.end_datetime.date()
            and any(target_after.start_datetime.time() >= window.start_time
                    and target_after.end_datetime.time() <= window.end_time
                    for window in windows)
        )
    availability_profile_loaded = weekly_availability is not None
    result_name = f"{case_id}_result.csv"
    trace_name = f"{case_id}_tool_trace.json"
    passed = (
        tools.outcome is not None
        and tools.outcome.action == case["expected_action"]
        and called_tools == expected_calls
        and agent.api_request_count > request_count_before
        and other_rows_preserved
        and target_adjusted
        and target_has_no_conflict
        and availability_respected
        and (not case.get("availability_file") or availability_profile_loaded)
    )

    result_path = RESULT_DIR / result_name
    trace_path = RESULT_DIR / trace_name
    result_path.write_text(render_schedule_csv(tools.schedule), encoding="utf-8")
    trace = {
        "case_id": case_id,
        "passed": passed,
        "schedule_id": case["schedule_id"],
        "expected_action": case["expected_action"],
        "actual_action": tools.outcome.action if tools.outcome else None,
        "expected_tool_calls": expected_calls,
        "called_tools": called_tools,
        "tool_calls": tools.trace.calls,
        "other_rows_preserved": other_rows_preserved,
        "target_adjusted_as_expected": target_adjusted,
        "target_has_no_conflict": target_has_no_conflict,
        "topic_order_preserved": topic_order_preserved,
        "uses_personal_availability": weekly_availability is not None,
        "availability_profile_loaded": availability_profile_loaded,
        "availability_respected": availability_respected,
        "gemini_api_request_count": agent.api_request_count - request_count_before,
        "result_csv": result_name,
        "agent_summary": summary,
    }
    trace_path.write_text(json.dumps(trace, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return trace


def main() -> int:
    """입력 값: 없음
    출력 값: 모든 케이스 통과 시 0, 하나라도 실패하면 1
    기능: 테스트 케이스를 순차 실행하고 결과와 통합 요약을 기록합니다.
    """
    parser = argparse.ArgumentParser(description="Run Schedule Adjuster Gemini integration scenarios")
    parser.add_argument("--case-id", help="Run only the named case ID")
    args = parser.parse_args()
    RESULT_DIR.mkdir(parents=True, exist_ok=True)
    agent = GeminiAdjustmentAgent()
    outcomes = []
    cases = load_cases()
    if args.case_id:
        cases = [case for case in cases if case["case_id"] == args.case_id]
        if not cases:
            parser.error(f"unknown case ID: {args.case_id}")
    for case in cases:
        request_count_before = agent.api_request_count
        try:
            outcome = run_case(agent, case)
        except Exception as error:
            outcome = {"case_id": case.get("case_id", "unknown"), "passed": False,
                       "error": f"{type(error).__name__}: {error}",
                       "gemini_api_request_count": agent.api_request_count - request_count_before}
            trace_path = RESULT_DIR / f"{outcome['case_id']}_tool_trace.json"
            trace_path.write_text(json.dumps(outcome, ensure_ascii=False, indent=2) + "\n",
                                  encoding="utf-8")
        outcomes.append(outcome)
        print(f"{outcome['case_id']}: {'PASS' if outcome['passed'] else 'FAIL'}")
        print(f"  API requests={outcome.get('gemini_api_request_count', 0)}; tools={outcome.get('called_tools', [])}")

    summary_name = (f"{args.case_id}_summary.json" if args.case_id
                    else "schedule_adjuster_test_summary.json")
    summary_path = RESULT_DIR / summary_name
    summary_path.write_text(json.dumps(outcomes, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"Summary: {summary_path}")
    return 0 if outcomes and all(item["passed"] for item in outcomes) else 1


if __name__ == "__main__":
    raise SystemExit(main())
