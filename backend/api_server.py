"""표준 라이브러리만으로 실행하는 로컬/배포 HTTP API 서버입니다."""
from __future__ import annotations

import json
import os
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any
from urllib.parse import urlparse

from api_service import adjust_schedule, create_study_plan, read_availability, write_availability

# API 서버 기본 설정은 JSON 파일에 두며 환경 변수로도 덮어쓸 수 있습니다.
PROJECT_ROOT = Path(__file__).resolve().parent
API_CONFIG_PATH = PROJECT_ROOT / "config" / "api_server.json"
DEFAULT_API_CONFIG = {
    "host": "127.0.0.1", "port": 8000,
    "allowed_origins": ["http://localhost:5173", "http://127.0.0.1:5173"],
    "max_request_bytes": 1_048_576,
}
API_PREFIX = "/api/v1"


def load_api_config() -> dict[str, Any]:
    """입력 값: 없음
    출력 값: 검증된 HTTP 서버 설정
    기능: JSON 설정 파일을 읽고 환경 변수 설정을 우선 적용합니다.
    """
    config = {**DEFAULT_API_CONFIG}
    if API_CONFIG_PATH.exists():
        supplied = json.loads(API_CONFIG_PATH.read_text(encoding="utf-8"))
        if not isinstance(supplied, dict):
            raise ValueError("config/api_server.json must contain a JSON object")
        unknown = set(supplied) - set(DEFAULT_API_CONFIG)
        if unknown:
            raise ValueError(f"unknown API config keys: {', '.join(sorted(unknown))}")
        config.update(supplied)
    config["host"] = os.getenv("APP_API_HOST", config["host"])
    config["port"] = int(os.getenv("APP_API_PORT", config["port"]))
    if not 1 <= config["port"] <= 65535:
        raise ValueError("API port must be between 1 and 65535")
    if not isinstance(config["allowed_origins"], list) or not all(
            isinstance(origin, str) for origin in config["allowed_origins"]):
        raise ValueError("allowed_origins must be a list of strings")
    if not isinstance(config["max_request_bytes"], int) or config["max_request_bytes"] <= 0:
        raise ValueError("max_request_bytes must be a positive integer")
    return config


def make_handler(config: dict[str, Any]) -> type[BaseHTTPRequestHandler]:
    """입력 값: 검증된 서버 설정
    출력 값: 해당 설정으로 동작하는 HTTP 요청 처리기 클래스
    기능: 상태 확인, 가능 시간, 계획 생성, 일정 조정 경로를 처리합니다.
    """
    class ApiHandler(BaseHTTPRequestHandler):
        """JSON 요청을 서비스 함수에 전달하는 HTTP 핸들러입니다."""

        def log_message(self, format: str, *args: Any) -> None:
            """입력 값: 기본 HTTP 로그 형식과 인자
            출력 값: 없음
            기능: 요청 로그에서 민감한 요청 본문을 출력하지 않습니다.
            """
            super().log_message(format, *args)

        def _send_json(self, status: int, payload: dict[str, Any]) -> None:
            """입력 값: HTTP 상태와 JSON 객체
            출력 값: 없음
            기능: CORS 헤더를 포함한 JSON HTTP 응답을 기록합니다.
            """
            body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            origin = self.headers.get("Origin")
            if origin in config["allowed_origins"]:
                self.send_header("Access-Control-Allow-Origin", origin)
                self.send_header("Vary", "Origin")
            self.end_headers()
            self.wfile.write(body)

        def _read_json(self) -> dict[str, Any]:
            """입력 값: HTTP 요청 본문
            출력 값: JSON 객체
            기능: 크기 제한과 Content-Length 및 JSON 객체 형식을 검증합니다.
            """
            try:
                length = int(self.headers.get("Content-Length", "0"))
            except ValueError as error:
                raise ValueError("Content-Length must be an integer") from error
            if length <= 0 or length > config["max_request_bytes"]:
                raise ValueError("request body is empty or exceeds configured size limit")
            try:
                payload = json.loads(self.rfile.read(length))
            except (json.JSONDecodeError, UnicodeDecodeError) as error:
                raise ValueError("request body must contain valid UTF-8 JSON") from error
            if not isinstance(payload, dict):
                raise ValueError("request body must be a JSON object")
            return payload

        def do_OPTIONS(self) -> None:
            """입력 값: 브라우저 CORS preflight 요청
            출력 값: HTTP 204 응답
            기능: 허용된 프론트엔드 origin에 JSON API 사전 요청을 승인합니다.
            """
            self.send_response(204)
            origin = self.headers.get("Origin")
            if origin in config["allowed_origins"]:
                self.send_header("Access-Control-Allow-Origin", origin)
                self.send_header("Access-Control-Allow-Methods", "GET, POST, PUT, OPTIONS")
                self.send_header("Access-Control-Allow-Headers", "Content-Type")
                self.send_header("Access-Control-Max-Age", "600")
            self.end_headers()

        def do_GET(self) -> None:
            """입력 값: HTTP GET 경로
            출력 값: 상태 확인 또는 사용자 가능 시간 JSON
            기능: 읽기 전용 API 경로를 처리합니다.
            """
            path = urlparse(self.path).path
            if path == f"{API_PREFIX}/health":
                self._send_json(200, {"status": "ok"})
            elif path == f"{API_PREFIX}/availability":
                self._send_json(200, {"weekly_availability": read_availability()})
            else:
                self._send_json(404, {"error": {"code": "NOT_FOUND", "message": "API route not found"}})

        def do_POST(self) -> None:
            """입력 값: HTTP POST 경로 및 JSON 요청 본문
            출력 값: 일정 생성 또는 조정 결과 JSON
            기능: 변경 작업 API를 실행하고 오류를 구조화된 JSON으로 반환합니다.
            """
            self._dispatch("POST")

        def do_PUT(self) -> None:
            """입력 값: HTTP PUT 경로 및 JSON 요청 본문
            출력 값: 저장된 설정 JSON
            기능: 가능 시간 변경 API를 실행합니다.
            """
            self._dispatch("PUT")

        def _dispatch(self, method: str) -> None:
            """입력 값: HTTP 메서드
            출력 값: 해당 API 경로의 JSON 응답
            기능: 요청 경로를 서비스에 연결하고 입력·API 오류를 HTTP 상태로 변환합니다.
            """
            path = urlparse(self.path).path
            try:
                payload = self._read_json()
                if method == "POST" and path == f"{API_PREFIX}/study-plans":
                    result = create_study_plan(payload)
                elif method == "POST" and path == f"{API_PREFIX}/schedules/adjust":
                    result = adjust_schedule(payload)
                elif method == "PUT" and path == f"{API_PREFIX}/availability":
                    result = {"weekly_availability": write_availability(payload)}
                else:
                    self._send_json(404, {"error": {"code": "NOT_FOUND", "message": "API route not found"}})
                    return
                self._send_json(200, result)
            except ValueError as error:
                message = str(error)
                if "GEMINI_API_KEY" in message or "API key" in message:
                    self._send_json(503, {"error": {"code": "UPSTREAM_ERROR", "message": message}})
                else:
                    self._send_json(422, {"error": {"code": "INVALID_INPUT", "message": message}})
            except RuntimeError as error:
                message = str(error)
                code = 503 if "GEMINI_API_KEY" in message or "API key" in message else 502
                self._send_json(code, {"error": {"code": "UPSTREAM_ERROR", "message": message}})
            except Exception:
                self._send_json(500, {"error": {"code": "INTERNAL_ERROR", "message": "Unexpected server error"}})

    return ApiHandler


def main() -> int:
    """입력 값: config/api_server.json과 APP_API_* 환경 변수
    출력 값: 서버 종료 시 프로세스 코드 0
    기능: 설정한 주소와 포트에서 HTTP API 서버를 실행합니다.
    """
    config = load_api_config()
    server = ThreadingHTTPServer((config["host"], config["port"]), make_handler(config))
    print(f"API listening at http://{config['host']}:{config['port']}{API_PREFIX}")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
