"""Run configured License Planner cases and save result CSVs and tool traces."""
from __future__ import annotations

import json
import argparse
from pathlib import Path

from license_planner.agent import GeminiToolAgent
from license_planner.csv_io import (parse_busy_csv, parse_datetime,
                                    parse_problem_results_csv, render_schedule_csv)
from license_planner.models import PlanRequest
from license_planner.settings import DATETIME_FORMAT
from license_planner.user_availability import parse_weekly_availability

# Test case directories and accepted text encoding.
PROJECT_ROOT = Path(__file__).resolve().parent
INPUT_DIR = PROJECT_ROOT / "license_planner_test_inputs"
RESULT_DIR = PROJECT_ROOT / "license_planner_test_results"
CASE_FILE = INPUT_DIR / "license_planner_test_cases.json"
TEXT_ENCODING = "utf-8-sig"
REQUIRED_TOOLS = ("get_certification_profile", "analyze_exam_results",
                  "assess_readiness", "build_study_schedule")


def load_cases() -> list[dict]:
    """입력 값: 없음
    출력 값: 테스트 케이스 설정 목록
    기능: JSON 케이스 설정 파일을 읽어 시나리오 목록을 반환합니다.
    """
    return json.loads(CASE_FILE.read_text(encoding=TEXT_ENCODING))


def run_case(agent: GeminiToolAgent, case: dict) -> dict:
    """입력 값: Gemini Agent와 테스트 케이스 설정
    출력 값: 테스트 판정 및 실행 trace 데이터
    기능: 시험 결과를 분석해 계획을 만들고 결과 CSV와 도구 호출 기록을 저장합니다.
    """
    results_csv = (INPUT_DIR / case["results_csv"]).read_text(encoding=TEXT_ENCODING)
    busy_csv = (INPUT_DIR / case["busy_csv"]).read_text(encoding=TEXT_ENCODING) if case.get("busy_csv") else None
    personal_availability = parse_weekly_availability(case.get("weekly_availability"))
    request = PlanRequest(
        self_assessment=case.get("self_assessment"),
        problem_results=parse_problem_results_csv(results_csv),
        preparation_start=parse_datetime(case["preparation_start"]),
        exam_date=parse_datetime(case["exam_date"]),
        busy_periods=parse_busy_csv(busy_csv),
        weekly_availability=personal_availability,
    )
    api_requests_before = agent.api_request_count
    successful_responses_before = agent.successful_response_count
    planner_tools, summary = agent.run(request)
    api_request_count = agent.api_request_count - api_requests_before
    successful_response_count = agent.successful_response_count - successful_responses_before
    calls = planner_tools.trace.calls
    called_tools = [call["tool"] for call in calls]
    schedule = planner_tools.schedule or []
    first_topic = schedule[0].topic if schedule else None
    availability_respected = all(
        block.start_datetime.date() == block.end_datetime.date()
        and any(block.start_datetime.time() >= window.start_time
            and block.end_datetime.time() <= window.end_time
            for window in personal_availability.for_weekday(block.start_datetime.weekday()))
        for block in schedule
    ) if personal_availability is not None else True
    busy_schedule_conflict_free = all(
        not (block.start_datetime < busy.end_datetime and busy.start_datetime < block.end_datetime)
        for block in schedule for busy in request.busy_periods
    )
    expected_start = case.get("expected_first_start")
    actual_start = (schedule[0].start_datetime.strftime(DATETIME_FORMAT)
                    if schedule else None)
    passed = (
        planner_tools.level == case["expected_level"]
        and called_tools == list(REQUIRED_TOOLS)
        and bool(schedule)
        and first_topic == case["expected_first_topic"]
        and (expected_start is None or actual_start == expected_start)
        and availability_respected
        and busy_schedule_conflict_free
        and api_request_count > 0
        and successful_response_count > 0
        and all(block.start_datetime.strftime("%Y/%m/%d/%H/%M")
                and block.end_datetime > block.start_datetime for block in schedule)
    )

    result_name = f"{case['case_id']}_result.csv"
    trace_name = f"{case['case_id']}_tool_trace.json"
    (RESULT_DIR / result_name).write_text(
        render_schedule_csv(request.busy_periods, schedule), encoding="utf-8")
    trace = {
        "case_id": case["case_id"],
        "passed": passed,
        "expected_level": case["expected_level"],
        "actual_level": planner_tools.level,
        "expected_first_topic": case["expected_first_topic"],
        "actual_first_topic": first_topic,
        "expected_first_start": expected_start,
        "actual_first_start": actual_start,
        "uses_personal_availability": personal_availability is not None,
        "availability_respected": availability_respected,
        "busy_schedule_conflict_free": busy_schedule_conflict_free,
        "gemini_api_request_count": api_request_count,
        "gemini_successful_response_count": successful_response_count,
        "topic_statistics": [
            {"topic": item.topic, "possible_score": item.possible_score,
             "earned_score": item.earned_score, "score_percent": item.score_percent}
            for item in planner_tools.topic_statistics],
        "called_tools": called_tools,
        "tool_calls": calls,
        "study_sessions": [
            {"schedule_id": block.schedule_id, "topic": block.topic,
             "start_datetime": block.start_datetime.strftime("%Y/%m/%d/%H/%M"),
             "end_datetime": block.end_datetime.strftime("%Y/%m/%d/%H/%M")}
            for block in schedule],
        "study_block_count": len(schedule),
        "result_csv": result_name,
        "agent_summary": summary,
    }
    (RESULT_DIR / trace_name).write_text(json.dumps(trace, ensure_ascii=False, indent=2) + "\n",
                                        encoding="utf-8")
    return trace


def main() -> int:
    """입력 값: 없음
    출력 값: 모든 케이스 통과 시 0, 하나 이상 실패 시 1
    기능: 전체 License Planner Agent 통합 테스트를 실행합니다.
    """
    parser = argparse.ArgumentParser(description="Run Gemini License Planner integration cases")
    parser.add_argument("--case-id", help="Run one named case to limit API requests")
    args = parser.parse_args()
    RESULT_DIR.mkdir(parents=True, exist_ok=True)
    agent = GeminiToolAgent()
    outcomes = []
    cases = load_cases()
    if args.case_id:
        cases = [case for case in cases if case["case_id"] == args.case_id]
        if not cases:
            parser.error(f"unknown case ID: {args.case_id}")
    for case in cases:
        api_requests_before = agent.api_request_count
        successful_responses_before = agent.successful_response_count
        try:
            outcome = run_case(agent, case)
        except Exception as error:
            # 실패도 케이스 결과로 기록해 기존 성공 요약이 현재 실패를 가리지 않게 합니다.
            outcome = {
                "case_id": case["case_id"],
                "passed": False,
                "error": f"{type(error).__name__}: {error}",
                "gemini_api_request_count": agent.api_request_count - api_requests_before,
                "gemini_successful_response_count": agent.successful_response_count - successful_responses_before,
            }
            trace_path = RESULT_DIR / f"{case['case_id']}_tool_trace.json"
            trace_path.write_text(json.dumps(outcome, ensure_ascii=False, indent=2) + "\n",
                                  encoding="utf-8")
        outcomes.append(outcome)
        print(f"{outcome['case_id']}: {'PASS' if outcome['passed'] else 'FAIL'}")
        if outcome["passed"]:
            print(f"  level={outcome['actual_level']}; first_topic={outcome['actual_first_topic']}")
            print(f"  tools={', '.join(outcome['called_tools'])}; CSV={outcome['result_csv']}")
        else:
            print(f"  error={outcome.get('error', 'one or more expected checks failed')}")
        print(f"  Gemini API attempts={outcome.get('gemini_api_request_count', 0)}; "
              f"successful responses={outcome.get('gemini_successful_response_count', 0)}")
    summary_name = (f"{args.case_id}_integration_summary.json" if args.case_id
                    else "license_planner_test_summary.json")
    summary_path = RESULT_DIR / summary_name
    summary_path.write_text(json.dumps(outcomes, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return 0 if outcomes and all(outcome["passed"] for outcome in outcomes) else 1


if __name__ == "__main__":
    raise SystemExit(main())
