"""OpenRouter Responses API를 표준 라이브러리로 호출하는 두 Agent 공용 전송 계층입니다."""
from __future__ import annotations

import http.client
import json
import logging
import os
import re
import urllib.error
import urllib.request
from pathlib import Path
from typing import Any, Iterable

# OpenRouter 접속 설정입니다. 모델과 요청 한도는 프론트엔드가 아니라 백엔드 코드에서만 바꿉니다.
OPENROUTER_RESPONSES_URL = "https://openrouter.ai/api/v1/responses"  # 비스트리밍 Responses API 주소입니다.
OPENROUTER_MODEL = "openai/gpt-6-luna"  # 환경 변수가 없을 때의 호환 기본 모델 ID입니다.
OPENROUTER_API_KEY_NAME = "OPENROUTER_API_KEY"  # 백엔드 전용 비밀 키 이름입니다.
OPENROUTER_MODEL_NAME = "OPENROUTER_MODEL"  # 서버에서만 바꾸는 모델 선택 환경 변수입니다.
REQUEST_TIMEOUT_SECONDS = 45  # 모델 요청 하나의 최대 대기 시간(초)입니다.
# 사용량 기록에 남길 수 있는 숫자 필드입니다. 그 밖의 응답 필드는 기록하지 않습니다.
USAGE_FIELDS = ("input_tokens", "output_tokens", "total_tokens", "cost")
INCOMPLETE_REASON_PATTERN = re.compile(r"[a-z_]{1,40}")  # 공개해도 되는 미완료 사유 형식입니다.
SECRET_PATTERN = re.compile(r"(?:sk-or-v1-|Bearer\\s+)[A-Za-z0-9._-]+", re.IGNORECASE)
LOGGER = logging.getLogger(__name__)


class InferenceConfigurationError(RuntimeError):
    """OpenRouter 키가 백엔드에 설정되지 않아 모델 요청을 보낼 수 없을 때 발생합니다."""


class InferenceError(RuntimeError):
    """제공자 응답 본문이나 인증 정보 없이 안전한 문구만 담는 모델 요청 실패입니다."""


def load_api_key(dotenv_paths: Iterable[Path]) -> str:
    """입력 값: 순서대로 확인할 로컬 비밀 설정 파일 경로
    출력 값: OpenRouter API 키
    기능: OS 환경 변수를 먼저 읽고, 없으면 파일에서 OPENROUTER_API_KEY만 읽습니다.
    """
    value = os.environ.get(OPENROUTER_API_KEY_NAME, "").strip()
    if value:
        return value
    value = _dotenv_value(OPENROUTER_API_KEY_NAME, dotenv_paths)
    if value:
        return value
    raise InferenceConfigurationError(f"{OPENROUTER_API_KEY_NAME} is not configured on the server.")


def load_model(dotenv_paths: Iterable[Path]) -> str:
    """Return the server-selected OpenRouter model without exposing any credential."""
    value = os.environ.get(OPENROUTER_MODEL_NAME, "").strip()
    return value or _dotenv_value(OPENROUTER_MODEL_NAME, dotenv_paths) or OPENROUTER_MODEL


def _dotenv_value(name: str, dotenv_paths: Iterable[Path]) -> str:
    for path in dotenv_paths:
        if not path.is_file():
            continue
        for raw_line in path.read_text(encoding="utf-8").splitlines():
            key, separator, raw_value = raw_line.strip().partition("=")
            if separator and key.strip() == name:
                value = raw_value.strip().strip("\"'")
                if value:
                    return value
    return ""


def function_tools(declarations: list[dict[str, Any]]) -> list[dict[str, Any]]:
    """입력 값: name/description/parameters 형식의 도구 선언 목록
    출력 값: Responses API 함수 도구 목록
    기능: 기존 선택 인자를 유지하도록 strict 검사를 끈 함수 도구로 감쌉니다.
    """
    return [{"type": "function", "name": item["name"], "description": item["description"],
             "parameters": item["parameters"], "strict": False}
            for item in declarations]


def create_response(api_key: str, *, instructions: str, input_items: list[dict[str, Any]],
                    tools: list[dict[str, Any]], reasoning_effort: str,
                    max_output_tokens: int, model: str = OPENROUTER_MODEL) -> dict[str, Any]:
    """입력 값: API 키, 시스템 지시, 요청별 대화 기록, 도구 선언, 추론 강도, 출력 토큰 한도
    출력 값: OpenRouter 응답 JSON 객체
    기능: 재시도나 다른 모델 전환 없이 Responses API에 요청 한 번을 보냅니다.
    """
    body = {"model": model, "instructions": instructions, "input": input_items,
            "tools": function_tools(tools), "tool_choice": "auto", "parallel_tool_calls": False,
            "reasoning": {"effort": reasoning_effort}, "max_output_tokens": max_output_tokens,
            "store": False, "stream": False}
    if reasoning_effort != "none":
        # 서버에 대화를 저장하지 않으므로 다음 요청에 되돌려 줄 추론 항목을 받습니다.
        body["include"] = ["reasoning.encrypted_content"]
    request = urllib.request.Request(OPENROUTER_RESPONSES_URL, data=json.dumps(body).encode("utf-8"), headers={
        "Authorization": f"Bearer {api_key}",
        "Content-Type": "application/json",
    }, method="POST")
    try:
        with urllib.request.urlopen(request, timeout=REQUEST_TIMEOUT_SECONDS) as response:
            raw = response.read()
    except urllib.error.HTTPError as error:
        detail = _safe_provider_detail(error)
        LOGGER.warning("OpenRouter Responses request rejected: HTTP %s (%s)", error.code, detail)
        error.close()
        raise InferenceError(f"OpenRouter request failed: {_http_failure(error.code)}") from None
    except (OSError, http.client.HTTPException) as error:
        raise InferenceError(f"OpenRouter request failed: network error ({type(error).__name__})") from None
    try:
        result = json.loads(raw)
    except (json.JSONDecodeError, UnicodeDecodeError):
        raise InferenceError("OpenRouter returned a response that is not JSON") from None
    if not isinstance(result, dict):
        raise InferenceError("OpenRouter returned a response that is not a JSON object")
    return result


def _http_failure(status: int) -> str:
    """입력 값: HTTP 상태 코드
    출력 값: 본문 없이 원인 범주만 담은 문구
    기능: 인증·크레딧·속도 제한·제공자 오류를 구분해 설명합니다.
    """
    if status in (401, 403):
        return f"authentication was rejected (HTTP {status})"
    if status == 402:
        return "credits or the key spending limit are exhausted (HTTP 402)"
    if status == 429:
        return "rate limited (HTTP 429)"
    if status >= 500:
        return f"provider unavailable (HTTP {status})"
    return f"request rejected (HTTP {status})"


def _safe_provider_detail(error: urllib.error.HTTPError) -> str:
    """Return a bounded provider message for server logs without credentials or raw payloads."""
    try:
        payload = json.loads(error.read().decode("utf-8", errors="replace"))
        message = payload.get("error", {}).get("message", "") if isinstance(payload, dict) else ""
        if not isinstance(message, str):
            message = ""
    except (json.JSONDecodeError, OSError, UnicodeDecodeError):
        message = ""
    message = SECRET_PATTERN.sub("[redacted]", message).replace("\n", " ").strip()
    return message[:500] or "no provider message"


def parse_reply(response: dict[str, Any]) -> tuple[list[dict[str, Any]], list[dict[str, Any]], str]:
    """입력 값: Responses API 응답 객체
    출력 값: 다음 요청에 되돌려 줄 출력 항목, 순서대로 된 함수 호출 항목, 응답 텍스트
    기능: 완료된 정상 응답만 받아들이고 거절·미완료·잘못된 형식은 상위 오류로 처리합니다.
    """
    if response.get("error"):
        raise InferenceError("OpenRouter returned an error response")
    status = response.get("status")
    if status == "incomplete":
        reason = (response.get("incomplete_details") or {}).get("reason")
        reason = reason if isinstance(reason, str) and INCOMPLETE_REASON_PATTERN.fullmatch(reason) else "unknown"
        raise InferenceError(f"OpenRouter response was incomplete ({reason})")
    if status != "completed":
        LOGGER.warning("OpenRouter Responses response shape mismatch: keys=%s status=%r output_type=%s choices_type=%s",
                       sorted(str(key) for key in response), status, type(response.get("output")).__name__,
                       type(response.get("choices")).__name__)
        raise InferenceError("OpenRouter response did not complete")
    output = response.get("output")
    if not isinstance(output, list) or not all(
            isinstance(item, dict) and isinstance(item.get("type"), str) for item in output):
        raise InferenceError("OpenRouter response output is malformed")
    calls, texts = [], []
    for item in output:
        if item["type"] == "function_call":
            calls.append(item)
        elif item["type"] == "message":
            content = item.get("content")
            if not isinstance(content, list):
                raise InferenceError("OpenRouter response message is malformed")
            for part in content:
                if not isinstance(part, dict):
                    raise InferenceError("OpenRouter response message is malformed")
                if part.get("type") == "refusal":
                    raise InferenceError("The model refused the request")
                if part.get("type") == "output_text" and isinstance(part.get("text"), str):
                    texts.append(part["text"])
    return output, calls, " ".join(texts)


def decode_call(item: dict[str, Any], allowed_names: Iterable[str],
                seen_call_ids: set[str]) -> tuple[str, str, dict[str, Any]]:
    """입력 값: 함수 호출 항목, 허용 도구 이름, 이번 요청에서 이미 받은 call_id 집합
    출력 값: call_id, 도구 이름, JSON 객체 인자
    기능: 도구를 실행하기 직전에 호출 식별자·이름·인자를 검증합니다.
    """
    call_id = item.get("call_id")
    if not isinstance(call_id, str) or not call_id:
        raise InferenceError("The model returned a tool call without a call_id")
    if call_id in seen_call_ids:
        raise InferenceError("The model repeated a tool call_id")
    seen_call_ids.add(call_id)
    name = item.get("name")
    if not isinstance(name, str) or name not in set(allowed_names):
        raise InferenceError("The model called an unsupported tool")
    raw_arguments = item.get("arguments")
    try:
        arguments = json.loads(raw_arguments) if isinstance(raw_arguments, str) else None
    except json.JSONDecodeError:
        arguments = None
    if not isinstance(arguments, dict):
        raise InferenceError(f"The model sent {name} arguments that are not a JSON object")
    return call_id, name, arguments


def function_output(call_id: str, observation: Any) -> dict[str, Any]:
    """입력 값: 원래 함수 호출의 call_id와 로컬 도구 결과
    출력 값: Responses API function_call_output 항목
    기능: 도구 결과를 JSON 문자열로 바꿔 같은 호출에 연결합니다.
    """
    return {"type": "function_call_output", "call_id": call_id,
            "output": json.dumps(observation, ensure_ascii=False)}


def usage_record(response: dict[str, Any]) -> dict[str, Any]:
    """입력 값: Responses API 응답 객체
    출력 값: 반환 모델과 허용된 숫자 사용량 필드
    기능: 검증 기록용 사용량만 골라내며, 없는 값은 0이 아닌 None으로 둡니다.
    """
    usage = response.get("usage") if isinstance(response.get("usage"), dict) else {}
    record = {field: (usage.get(field) if isinstance(usage.get(field), (int, float))
                      and not isinstance(usage.get(field), bool) else None)
              for field in USAGE_FIELDS}
    details = usage.get("output_tokens_details")
    reasoning = details.get("reasoning_tokens") if isinstance(details, dict) else None
    record["reasoning_tokens"] = reasoning if isinstance(reasoning, int) and not isinstance(reasoning, bool) else None
    model = response.get("model")
    record["model"] = model if isinstance(model, str) and len(model) <= 100 else None
    return record


def inference_summary(request_count: int, usage_records: list[dict[str, Any]],
                      elapsed_seconds: float) -> dict[str, Any]:
    """입력 값: 시도한 모델 요청 수, 응답별 사용량 기록, 경과 시간(초)
    출력 값: 시나리오 결과 파일에만 쓰는 추론 실행 정보
    기능: 선택 모델과 요청 수·시간·사용량을 묶습니다. 공개 API 응답에는 넣지 않습니다.
    """
    return {"provider": "openrouter", "selected_model": OPENROUTER_MODEL,
            "model_request_count": request_count, "elapsed_seconds": round(elapsed_seconds, 3),
            "usage": usage_records}
