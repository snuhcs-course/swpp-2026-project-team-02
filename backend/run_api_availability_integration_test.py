"""개인 가능 시간 저장부터 HTTP 일정 조정 응답까지 테스트합니다."""
from __future__ import annotations

import csv
import io
import json
import threading
import urllib.error
import urllib.request
from datetime import datetime, time, timedelta
from http.server import ThreadingHTTPServer
from pathlib import Path
from tempfile import TemporaryDirectory
from unittest.mock import patch

import api_service
from api_server import API_PREFIX, make_handler
from license_planner.user_availability import (
    load_personal_availability as load_planner_availability,
    save_personal_availability as save_planner_availability,
)
from schedule_adjuster.availability import (
    load_personal_availability as load_adjuster_availability,
)

# 테스트 입력 및 산출물 경로, 파일 인코딩 설정입니다.
PROJECT_ROOT = Path(__file__).resolve().parent
INPUT_DIR = PROJECT_ROOT / "api_test_inputs"
RESULT_DIR = PROJECT_ROOT / "api_test_results"
AVAILABILITY_FIXTURE = INPUT_DIR / "personal_availability_profile.json"
ADJUSTMENT_FIXTURE = INPUT_DIR / "schedule_adjustment_request.json"
TEXT_ENCODING = "utf-8"


def _request_json(url: str, method: str = "GET", payload: dict | None = None,
                 origin: str | None = None) -> tuple[int, dict, dict[str, str]]:
    """입력 값: 요청 URL, HTTP 메서드, 선택 JSON 본문과 Origin
    출력 값: 상태 코드, JSON 응답 객체, 응답 헤더
    기능: 로컬 API에 실제 HTTP 요청을 보내고 응답을 JSON으로 읽습니다.
    """
    headers = {}
    body = None
    if payload is not None:
        body = json.dumps(payload, ensure_ascii=False).encode(TEXT_ENCODING)
        headers["Content-Type"] = "application/json"
    if origin:
        headers["Origin"] = origin
    request = urllib.request.Request(url, data=body, headers=headers, method=method)
    try:
        with urllib.request.urlopen(request, timeout=60) as response:
            return response.status, json.loads(response.read()), dict(response.headers.items())
    except urllib.error.HTTPError as error:
        return error.code, json.loads(error.read()), dict(error.headers.items())


def _parse_csv(content: str) -> list[dict[str, str]]:
    """입력 값: 응답 CSV 문자열
    출력 값: CSV 일정 행의 딕셔너리 목록
    기능: API CSV 응답을 자동 검증용 행 객체로 변환합니다.
    """
    return list(csv.DictReader(io.StringIO(content)))


def _request_preflight(url: str, origin: str, requested_method: str) -> tuple[int, dict[str, str]]:
    """입력 값: API URL, 브라우저 origin, 예정된 HTTP 메서드
    출력 값: OPTIONS 상태 코드와 응답 헤더
    기능: JSON POST/PUT 전에 브라우저가 보내는 CORS preflight를 검사합니다.
    """
    request = urllib.request.Request(url, headers={
        "Origin": origin,
        "Access-Control-Request-Method": requested_method,
        "Access-Control-Request-Headers": "content-type",
    }, method="OPTIONS")
    with urllib.request.urlopen(request, timeout=10) as response:
        return response.status, dict(response.headers.items())


def run() -> dict:
    """입력 값: 테스트 fixture 두 개와 백엔드 GEMINI_API_KEY 설정
    출력 값: 체크별 결과를 담은 요약 딕셔너리
    기능: 임시 가능 시간 파일을 사용해 HTTP 경로와 Gemini 일정 조정을 검증합니다.
    """
    RESULT_DIR.mkdir(parents=True, exist_ok=True)
    result_csv_path = RESULT_DIR / "api_availability_schedule_result.csv"
    if result_csv_path.exists():
        result_csv_path.unlink()
    availability_payload = json.loads(AVAILABILITY_FIXTURE.read_text(encoding=TEXT_ENCODING))
    adjustment_payload = json.loads(ADJUSTMENT_FIXTURE.read_text(encoding=TEXT_ENCODING))
    checks: dict[str, bool] = {}
    result_body: dict = {}

    with TemporaryDirectory(prefix="api-availability-test-") as temporary_directory:
        profile_path = Path(temporary_directory) / "user_availability.json"

        # API 프로세스에서만 사용 함수들을 임시 경로로 바꿔 개인 설정 파일을 보호합니다.
        def save_test_profile(payload: dict):
            """입력 값: 저장할 사용자 가능 시간 JSON.\n출력 값: 저장된 프로필 데이터.\n기능: 테스트 전용 임시 경로에 가능 시간 프로필을 저장합니다."""
            return save_planner_availability(payload, profile_path)

        def load_test_planner_profile():
            """입력 값: 없음.\n출력 값: 테스트용 가능 시간 프로필.\n기능: 계획 생성기가 읽는 프로필을 임시 경로에서 불러옵니다."""
            return load_planner_availability(profile_path)

        def load_test_adjuster_profile():
            """입력 값: 없음.\n출력 값: 테스트용 가능 시간 프로필.\n기능: 일정 조정기가 읽는 프로필을 임시 경로에서 불러옵니다."""
            return load_adjuster_availability(profile_path)

        server_config = {
            "host": "127.0.0.1",
            "port": 0,
            "allowed_origins": ["http://localhost:5173"],
            "max_request_bytes": 1_048_576,
        }
        handler = make_handler(server_config)
        server = ThreadingHTTPServer((server_config["host"], server_config["port"]), handler)
        worker = threading.Thread(target=server.serve_forever, daemon=True)
        worker.start()
        base_url = f"http://127.0.0.1:{server.server_address[1]}{API_PREFIX}"
        try:
            with (patch.object(api_service, "save_personal_availability", save_test_profile),
                  patch.object(api_service, "load_personal_availability", load_test_planner_profile),
                  patch.object(api_service, "load_adjuster_availability", load_test_adjuster_profile)):
                status, health, health_headers = _request_json(
                    f"{base_url}/health", origin="http://localhost:5173")
                checks["health_route"] = status == 200 and health.get("status") == "ok"
                preflight_status, preflight_headers = _request_preflight(
                    f"{base_url}/schedules/adjust", "http://localhost:5173", "POST")
                checks["cors_origin"] = (
                    health_headers.get("Access-Control-Allow-Origin") == "http://localhost:5173"
                    and preflight_status == 204
                    and preflight_headers.get("Access-Control-Allow-Origin") == "http://localhost:5173"
                    and "POST" in preflight_headers.get("Access-Control-Allow-Methods", ""))

                status, saved, _ = _request_json(
                    f"{base_url}/availability", method="PUT", payload=availability_payload)
                checks["availability_put"] = status == 200
                status, loaded, _ = _request_json(f"{base_url}/availability")
                expected_profile = {
                    day: availability_payload["weekly_availability"].get(day, [])
                    for day in ("monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday")
                }
                checks["availability_get_round_trip"] = (
                    status == 200 and loaded.get("weekly_availability") == expected_profile
                    and saved.get("weekly_availability") == expected_profile)

                invalid_status, invalid_body, _ = _request_json(
                    f"{base_url}/study-plans", method="POST",
                    payload={"busy_schedules": "invalid"})
                checks["invalid_input_422"] = (
                    invalid_status == 422
                    and invalid_body.get("error", {}).get("code") == "INVALID_INPUT")

                adjust_status, result_body, _ = _request_json(
                    f"{base_url}/schedules/adjust", method="POST", payload=adjustment_payload)
                rows = _parse_csv(result_body.get("schedule_csv", "")) if adjust_status == 200 else []
                by_id = {row["schedule_id"]: row for row in rows}
                target = by_id.get(adjustment_payload["schedule_id"])
                expected_tools = ["inspect_schedule", "inspect_topic_schedule", "reschedule_failed_study"]
                actual_tools = [call.get("tool") for call in result_body.get("tool_calls", [])]
                if target:
                    start = datetime.strptime(target["start_date"], "%Y/%m/%d/%H/%M")
                    end = datetime.strptime(target["end_date"], "%Y/%m/%d/%H/%M")
                    availability_valid = (
                        start.weekday() == 2 and start.date() == end.date()
                        and start.time() >= time(20, 0) and end.time() <= time(22, 0)
                        # The fixture starts with a one-hour block; Schedule Adjuster
                        # must preserve its original duration when moving it.
                        and end - start == timedelta(hours=1)
                    )
                else:
                    availability_valid = False
                original_rows = adjustment_payload["schedules"]
                untouched_rows_preserved = all(
                    by_id.get(row["schedule_id"]) == row
                    for row in original_rows if row["schedule_id"] != adjustment_payload["schedule_id"]
                )
                checks["adjustment_http_success"] = (
                    adjust_status == 200 and result_body.get("action") == "rescheduled_study")
                checks["profile_window_respected"] = availability_valid
                checks["other_rows_preserved"] = untouched_rows_preserved
                checks["agent_tools_called_in_order"] = actual_tools == expected_tools
        finally:
            server.shutdown()
            server.server_close()
            worker.join(timeout=5)

    summary = {
        "passed": bool(checks) and all(checks.values()),
        "checks": checks,
        "schedule_adjustment_http_status": adjust_status,
        "schedule_adjustment_error": result_body.get("error"),
        "result_csv": result_csv_path.name if result_body.get("schedule_csv") else None,
        "tool_calls": result_body.get("tool_calls", []),
        "action": result_body.get("action"),
        "moved_schedule": next((row for row in _parse_csv(result_body.get("schedule_csv", ""))
                                 if row["schedule_id"] == adjustment_payload["schedule_id"]), None)
                          if result_body.get("schedule_csv") else None,
    }
    if result_body.get("schedule_csv"):
        result_csv_path.write_text(
            result_body["schedule_csv"], encoding=TEXT_ENCODING)
    (RESULT_DIR / "api_availability_integration_summary.json").write_text(
        json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding=TEXT_ENCODING)
    return summary


def main() -> int:
    """입력 값: fixture 파일과 GEMINI_API_KEY 설정
    출력 값: 모든 확인 통과 시 0, 하나라도 실패하면 1
    기능: 개인 가능 시간 HTTP 통합 테스트를 실행하고 판정 및 산출물을 기록합니다.
    """
    try:
        summary = run()
    except Exception as error:
        RESULT_DIR.mkdir(parents=True, exist_ok=True)
        failure = {"passed": False, "error": f"{type(error).__name__}: {error}"}
        (RESULT_DIR / "api_availability_integration_summary.json").write_text(
            json.dumps(failure, ensure_ascii=False, indent=2) + "\n", encoding=TEXT_ENCODING)
        print(f"FAIL: {failure['error']}")
        print(f"Summary: {RESULT_DIR / 'api_availability_integration_summary.json'}")
        return 1
    for check, passed in summary["checks"].items():
        print(f"{check}: {'PASS' if passed else 'FAIL'}")
    if summary.get("schedule_adjustment_error"):
        print(f"Schedule API error: {summary['schedule_adjustment_error']}")
    print(f"Summary: {RESULT_DIR / 'api_availability_integration_summary.json'}")
    return 0 if summary["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
