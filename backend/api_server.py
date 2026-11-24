"""표준 라이브러리만으로 실행하는 로컬/배포 HTTP API 서버입니다."""
from __future__ import annotations

import ipaddress
import json
import logging
import os
import re
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any
from urllib.parse import parse_qs, urlparse

from api_admission import AdmissionLimiter
from api_service import (adjust_schedule, create_study_plan, delete_study_plan, owner_plan_store, read_availability,
                         read_study_plan_csv, write_availability, list_study_plans)
from assessment_service import (AssessmentConfigurationError, get_public_certifications,
                                get_public_questions, get_reassessment_questions)
from schedule_adjuster.reassessment import ReassessmentPreviewExpired
from openrouter_client import InferenceConfigurationError, sanitize_diagnostic
from schedule_adjuster.schedule_logic import ManualMoveError
from study_plan_store import PlanNotFoundError, PlanConflictError, PlanStorageError, StudyPlanStore

# API 서버 기본 설정은 JSON 파일에 두며 환경 변수로도 덮어쓸 수 있습니다.
PROJECT_ROOT = Path(__file__).resolve().parent
API_CONFIG_PATH = PROJECT_ROOT / "config" / "api_server.json"
DEFAULT_API_CONFIG = {
    "host": "127.0.0.1", "port": 8000,
    "allowed_origins": ["http://localhost:5173", "http://127.0.0.1:5173"],
    "max_request_bytes": 1_048_576,
    # 공개 배포는 루프백 Nginx의 X-Real-IP만 믿고, 공용 가능 시간 프로필을 끕니다.
    "trust_local_proxy": False,
    "shared_availability_enabled": True,
}
FLAG_ENVIRONMENT = {"trust_local_proxy": "APP_TRUST_LOCAL_PROXY",
                    "shared_availability_enabled": "APP_SHARED_AVAILABILITY_ENABLED"}
API_PREFIX = "/api/v1"
LOGGER = logging.getLogger(__name__)

# 상위 서비스 오류는 내부 도구 인자·trace·경로가 노출되지 않도록 고정 문구로만 응답합니다.
UPSTREAM_MESSAGES = {
    f"{API_PREFIX}/study-plans": "Study plan generation failed. Try again later.",
    f"{API_PREFIX}/schedules/adjust": "Schedule adjustment failed. Try again later.",
}
DEFAULT_UPSTREAM_MESSAGE = "The upstream service failed. Try again later."
CONFIGURATION_MESSAGE = "The planning service is not configured on the server."
ASSESSMENT_CONFIGURATION_MESSAGE = "The assessment questions are not configured correctly on the server."

# 계획 경로는 설치마다 만든 기기 토큰(Bearer, 소문자 16진수 64자)을 요구합니다.
BEARER_CREDENTIAL = re.compile(r"(?i:bearer) ([0-9a-f]{64})")
DEVICE_TOKEN_CHALLENGE = 'Bearer realm="license-planner"'
INVALID_DEVICE_TOKEN_MESSAGE = "A valid device token is required."
RATE_LIMITED_MESSAGE = "Too many requests. Try again later."
CLIENT_ADDRESS_MESSAGE = "The service cannot identify the client address. Try again later."
ROUTE_NOT_FOUND = {"error": {"code": "NOT_FOUND", "message": "API route not found"}}
PROTECTED_POST_PATHS = frozenset({f"{API_PREFIX}/study-plans", f"{API_PREFIX}/schedules/adjust"})


def is_protected_get(path: str) -> bool:
    """입력 값: 요청 경로
    출력 값: 기기 토큰이 필요한 계획 조회·다운로드 경로인지 여부
    """
    return path == f"{API_PREFIX}/study-plans" or path.startswith(f"{API_PREFIX}/study-plans/")


def device_token(headers: Any) -> str | None:
    """입력 값: HTTP 요청 헤더
    출력 값: 형식이 맞는 단일 Bearer 기기 토큰, 없거나 잘못되면 None
    기능: 빈 값, 중복 헤더, 여러 자격 증명이나 형식이 다른 토큰을 거절합니다.
    """
    values = headers.get_all("Authorization") or []
    if len(values) != 1:
        return None
    match = BEARER_CREDENTIAL.fullmatch(values[0])
    return match.group(1) if match else None


def proxied_client_ip(peer: str, headers: Any, trust_local_proxy: bool) -> str | None:
    """입력 값: 소켓 상대 주소, 요청 헤더, 루프백 프록시 신뢰 여부
    출력 값: 사용량 제한에 쓸 클라이언트 IP, 신뢰한 프록시가 올바른 단일 X-Real-IP를 주지 않으면 None
    기능: 로컬 모드와 루프백이 아닌 상대는 소켓 주소를 쓰고, 클라이언트가 보낸 전달 헤더는 믿지 않습니다.
    """
    address = ipaddress.ip_address(peer)
    mapped = getattr(address, "ipv4_mapped", None)
    if not trust_local_proxy or not (mapped or address).is_loopback:
        return str(address)
    values = headers.get_all("X-Real-IP") or []
    if len(values) != 1:
        return None
    try:
        return str(ipaddress.ip_address(values[0]))
    except ValueError:
        return None


def _flag(name: str, value: Any) -> bool:
    """환경 변수 값은 정확히 0 또는 1, 설정 파일 값은 JSON true/false만 받습니다."""
    if isinstance(value, bool):
        return value
    if value in ("0", "1"):
        return value == "1"
    raise ValueError(f"{name} must be 0 or 1")


def _is_missing_configuration(error: Exception) -> bool:
    """입력 값: 서비스 예외
    출력 값: 모델 API 키 설정 누락 오류 여부
    기능: 예외 메시지가 아니라 전용 예외 종류로 서버 설정 누락을 판별합니다.
    """
    return isinstance(error, InferenceConfigurationError)


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
    for key, variable in FLAG_ENVIRONMENT.items():
        if variable in os.environ:
            config[key] = _flag(variable, os.environ[variable])
        elif not isinstance(config[key], bool):
            raise ValueError(f"{key} must be true or false")
    return config


def make_handler(config: dict[str, Any], admission: AdmissionLimiter | None = None
                 ) -> type[BaseHTTPRequestHandler]:
    """입력 값: 검증된 서버 설정, 선택적인 사용량 제한 상태(테스트용 한도·시계)
    출력 값: 해당 설정으로 동작하는 HTTP 요청 처리기 클래스
    기능: 상태 확인, 가능 시간, 계획 생성, 일정 조정 경로를 처리합니다.
    처리기마다 새 메모리 제한 상태를 쓰므로 서버를 다시 시작하면 사용량이 초기화됩니다.
    """
    limiter = admission if admission is not None else AdmissionLimiter()
    trust_local_proxy = config.get("trust_local_proxy", DEFAULT_API_CONFIG["trust_local_proxy"])
    shared_availability = config.get("shared_availability_enabled",
                                     DEFAULT_API_CONFIG["shared_availability_enabled"])

    class ApiHandler(BaseHTTPRequestHandler):
        """JSON 요청을 서비스 함수에 전달하는 HTTP 핸들러입니다."""

        def log_message(self, format: str, *args: Any) -> None:
            """입력 값: 기본 HTTP 로그 형식과 인자
            출력 값: 없음
            기능: 요청 로그에서 민감한 요청 본문을 출력하지 않습니다.
            """
            super().log_message(format, *args)

        _protected = False

        def _send_protection_headers(self) -> None:
            """기기 토큰 경로의 성공·실패 응답이 캐시되지 않게 합니다."""
            if self._protected:
                self.send_header("Cache-Control", "no-store")

        def _send_json(self, status: int, payload: dict[str, Any],
                       extra_headers: dict[str, str] | None = None) -> None:
            """입력 값: HTTP 상태와 JSON 객체, 추가 응답 헤더
            출력 값: 없음
            기능: CORS 헤더를 포함한 JSON HTTP 응답을 기록합니다.
            """
            body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            for name, value in (extra_headers or {}).items():
                self.send_header(name, value)
            self._send_protection_headers()
            if status == 401:
                self.send_header("WWW-Authenticate", DEVICE_TOKEN_CHALLENGE)
            origin = self.headers.get("Origin")
            if origin in config["allowed_origins"]:
                self.send_header("Access-Control-Allow-Origin", origin)
                self.send_header("Vary", "Origin")
            self.end_headers()
            self.wfile.write(body)

        def _send_csv(self, filename: str, content: str) -> None:
            """Return one generated schedule as a downloadable UTF-8 CSV."""
            body = content.encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "text/csv; charset=utf-8")
            self.send_header("Content-Disposition", f'attachment; filename="{filename}"')
            self.send_header("Content-Length", str(len(body)))
            self._send_protection_headers()
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

        def _reject_unread(self, status: int, payload: dict[str, Any],
                           extra_headers: dict[str, str] | None = None) -> None:
            """입력 값: 거절 상태, 오류 JSON, 추가 헤더
            출력 값: 없음
            기능: 본문을 해석하지 않고 크기 제한 안의 선언된 바이트만 버린 뒤 응답하고 연결을 닫습니다.
            읽지 않은 본문이 남은 채 연결을 닫으면 클라이언트가 응답 대신 연결 재설정을 받을 수 있습니다.
            """
            try:
                length = int(self.headers.get("Content-Length", "0"))
            except ValueError:
                length = 0
            if 0 < length <= config["max_request_bytes"]:
                self.rfile.read(length)
            self.close_connection = True
            self._send_json(status, payload, extra_headers)

        def _owner_store(self) -> StudyPlanStore | None:
            """입력 값: 계획 경로 요청의 Authorization 헤더
            출력 값: 토큰 소유자의 계획 저장소, 토큰이 없거나 잘못되면 None
            기능: 본문 읽기나 저장소 접근 전에 토큰을 확인하고 실패 시 401을 보냅니다.
            """
            self._protected = True
            token = device_token(self.headers)
            if token is None:
                self._reject_unread(401, {"error": {"code": "INVALID_DEVICE_TOKEN",
                                                    "message": INVALID_DEVICE_TOKEN_MESSAGE}})
                return None
            return owner_plan_store(token)

        def _admitted(self, path: str, store: StudyPlanStore) -> bool:
            """입력 값: 예산 POST 경로와 토큰 소유자 저장소
            출력 값: 본문 읽기 전에 요청을 받아들였는지 여부
            기능: 클라이언트 IP를 정하고 소유자·IP·서비스 칸을 한 번에 예약하며, 실패 시 503 또는 429를 보냅니다.
            """
            client_ip = proxied_client_ip(self.client_address[0], self.headers, trust_local_proxy)
            if client_ip is None:
                LOGGER.warning("Rejected a proxied request on %s without one valid X-Real-IP", path)
                self._reject_unread(503, {"error": {"code": "SERVICE_UNAVAILABLE", "message": CLIENT_ADDRESS_MESSAGE}})
                return False
            retry_after = limiter.admit(path, store.directory.name, client_ip)
            if retry_after is not None:
                self._reject_unread(429, {"error": {"code": "RATE_LIMITED", "message": RATE_LIMITED_MESSAGE}},
                                    {"Retry-After": str(retry_after)})
                return False
            return True

        def do_OPTIONS(self) -> None:
            """입력 값: 브라우저 CORS preflight 요청
            출력 값: HTTP 204 응답
            기능: 허용된 프론트엔드 origin에 JSON API 사전 요청을 승인합니다.
            """
            self.send_response(204)
            origin = self.headers.get("Origin")
            if origin in config["allowed_origins"]:
                self.send_header("Access-Control-Allow-Origin", origin)
                self.send_header("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS")
                self.send_header("Access-Control-Allow-Headers", "Content-Type, Authorization")
                self.send_header("Access-Control-Max-Age", "600")
            self.end_headers()

        def do_GET(self) -> None:
            """입력 값: HTTP GET 경로
            출력 값: 상태 확인 또는 사용자 가능 시간 JSON
            기능: 읽기 전용 API 경로를 처리합니다.
            """
            url = urlparse(self.path)
            path = url.path
            self._protected = False
            try:
                store = None
                if is_protected_get(path):
                    store = self._owner_store()
                    if store is None:
                        return
                if path == f"{API_PREFIX}/health":
                    self._send_json(200, {"status": "ok"})
                elif path == f"{API_PREFIX}/availability":
                    if not shared_availability:
                        self._send_json(404, ROUTE_NOT_FOUND)
                        return
                    self._send_json(200, read_availability())
                elif path == f"{API_PREFIX}/certifications":
                    self._send_json(200, get_public_certifications())
                elif path.startswith(f"{API_PREFIX}/certifications/") and path.endswith("/reassessment-questions"):
                    certification_id = path[len(f"{API_PREFIX}/certifications/"):-len("/reassessment-questions")]
                    if not certification_id or "/" in certification_id:
                        self._send_json(404, ROUTE_NOT_FOUND)
                        return
                    self._send_json(200, get_reassessment_questions(certification_id))
                elif path.startswith(f"{API_PREFIX}/certifications/") and path.endswith("/questions"):
                    certification_id = path[len(f"{API_PREFIX}/certifications/"):-len("/questions")].strip("/")
                    if not certification_id or "/" in certification_id:
                        self._send_json(404, {"error": {"code": "NOT_FOUND", "message": "API route not found"}})
                        return
                    self._send_json(200, get_public_questions(certification_id))
                elif path == f"{API_PREFIX}/study-plans":
                    selected = parse_qs(url.query, keep_blank_values=True).get("schedule_csv_file")
                    try:
                        if selected is not None and len(selected) != 1:
                            raise ValueError("schedule_csv_file must be given at most once")
                        result = list_study_plans(selected[0] if selected else None, store)
                    except ValueError as error:
                        self._send_json(422, {"error": {"code": "INVALID_INPUT", "message": str(error)}})
                        return
                    self._send_json(200, result)
                elif path.startswith(f"{API_PREFIX}/study-plans/"):
                    filename = path[len(f"{API_PREFIX}/study-plans/"):]
                    content, safe_filename = read_study_plan_csv(filename, store)
                    self._send_csv(safe_filename, content)
                else:
                    self._send_json(404, {"error": {"code": "NOT_FOUND", "message": "API route not found"}})
            except AssessmentConfigurationError as error:
                self._send_assessment_configuration_error(path, error)
            except PlanNotFoundError as error:
                self._send_json(404, {"error": {"code": "PLAN_NOT_FOUND", "message": str(error)}})
            except PlanStorageError as error:
                self._send_json(500, {"error": {"code": "STORAGE_ERROR", "message": str(error)}})
            except KeyError as error:
                self._send_json(404, {"error": {"code": "NOT_FOUND", "message": str(error)}})
            except FileNotFoundError:
                self._send_json(404, {"error": {"code": "NOT_FOUND", "message": "Study plan CSV not found"}})
            except ValueError as error:
                self._send_json(500, {"error": {"code": "INTERNAL_ERROR", "message": str(error)}})
            except Exception:
                LOGGER.exception("Unexpected API failure on %s", path)
                self._send_json(500, {"error": {"code": "INTERNAL_ERROR", "message": "Unexpected server error"}})

        def _send_assessment_configuration_error(self, path: str, error: AssessmentConfigurationError) -> None:
            """Log the internal diagnostic and return a fixed message without paths or traces."""
            LOGGER.error("Assessment configuration failure on %s: %s", path, error)
            self._send_json(500, {"error": {"code": "INTERNAL_ERROR", "message": ASSESSMENT_CONFIGURATION_MESSAGE}})

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

        def do_DELETE(self) -> None:
            self._dispatch("DELETE")

        def _dispatch(self, method: str) -> None:
            """입력 값: HTTP 메서드
            출력 값: 해당 API 경로의 JSON 응답
            기능: 요청 경로를 서비스에 연결하고 입력·API 오류를 HTTP 상태로 변환합니다.
            """
            path = urlparse(self.path).path
            self._protected = False
            try:
                store = None
                if method == "DELETE":
                    prefix = f"{API_PREFIX}/study-plans/"
                    filename = path.removeprefix(prefix)
                    if filename == path or not filename or "/" in filename:
                        self._reject_unread(404, ROUTE_NOT_FOUND)
                        return
                    store = self._owner_store()
                    if store is None:
                        return
                    try:
                        delete_study_plan(filename, store)
                    except FileNotFoundError:
                        self._send_json(404, {"error": {"code": "NOT_FOUND", "message": "Study plan CSV not found"}})
                        return
                    self.send_response(204)
                    self._send_protection_headers()
                    origin = self.headers.get("Origin")
                    if origin in config["allowed_origins"]:
                        self.send_header("Access-Control-Allow-Origin", origin)
                        self.send_header("Vary", "Origin")
                    self.end_headers()
                    return
                if method == "POST" and path in PROTECTED_POST_PATHS:
                    store = self._owner_store()
                    if store is None or not self._admitted(path, store):
                        return
                elif method == "PUT" and path == f"{API_PREFIX}/availability" and not shared_availability:
                    # 공용 프로필을 끈 배포는 본문을 읽기 전에 경로 자체가 없는 것처럼 응답합니다.
                    self._reject_unread(404, ROUTE_NOT_FOUND)
                    return
                payload = self._read_json()
                if method == "POST" and path == f"{API_PREFIX}/study-plans":
                    result = create_study_plan(payload, store, shared_availability=shared_availability)
                elif method == "POST" and path == f"{API_PREFIX}/schedules/adjust":
                    result = adjust_schedule(payload, store, shared_availability=shared_availability)
                elif method == "PUT" and path == f"{API_PREFIX}/availability":
                    result = write_availability(payload)
                else:
                    self._send_json(404, {"error": {"code": "NOT_FOUND", "message": "API route not found"}})
                    return
                self._send_json(200, result)
            except AssessmentConfigurationError as error:
                self._send_assessment_configuration_error(path, error)
            except PlanNotFoundError as error:
                self._send_json(404, {"error": {"code": "PLAN_NOT_FOUND", "message": str(error)}})
            except PlanConflictError as error:
                self._send_json(409, {"error": {"code": "PLAN_CONFLICT", "message": str(error)}})
            except PlanStorageError as error:
                self._send_json(500, {"error": {"code": "STORAGE_ERROR", "message": str(error)}})
            except ManualMoveError as error:
                self._send_json(422, {"error": {"code": error.code, "message": str(error)}})
            except ReassessmentPreviewExpired as error:
                self._send_json(422, {"error": {"code": "REASSESSMENT_PREVIEW_EXPIRED", "message": str(error)}})
            except ValueError as error:
                self._send_json(422, {"error": {"code": "INVALID_INPUT", "message": str(error)}})
            except RuntimeError as error:
                detail = sanitize_diagnostic(str(error))
                LOGGER.warning("API upstream failure on %s: %s: %s", path, type(error).__name__, detail)
                if _is_missing_configuration(error):
                    self._send_json(503, {"error": {"code": "UPSTREAM_ERROR", "message": CONFIGURATION_MESSAGE}})
                else:
                    message = UPSTREAM_MESSAGES.get(path, DEFAULT_UPSTREAM_MESSAGE)
                    self._send_json(502, {"error": {"code": "UPSTREAM_ERROR", "message": message}})
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
