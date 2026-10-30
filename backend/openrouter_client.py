"""Small OpenRouter Chat Completions client shared by the two agents."""
from __future__ import annotations

import json
import urllib.error
import urllib.request
from typing import Any

OPENROUTER_CHAT_URL = "https://openrouter.ai/api/v1/chat/completions"


def openai_tools(declarations: list[dict[str, Any]]) -> list[dict[str, Any]]:
    """Convert the existing function declarations into OpenAI-compatible tools."""
    def normalize(value: Any) -> Any:
        if isinstance(value, dict):
            return {key: (item.lower() if key == "type" and isinstance(item, str) else normalize(item))
                    for key, item in value.items()}
        if isinstance(value, list):
            return [normalize(item) for item in value]
        return value

    return [{"type": "function", "function": normalize(declaration)} for declaration in declarations]


def request_completion(*, api_key: str, model: str, messages: list[dict[str, Any]],
                       declarations: list[dict[str, Any]], timeout_seconds: int) -> dict[str, Any]:
    """Send one non-streaming tool-capable completion request without logging the key."""
    body = {
        "model": model,
        "messages": messages,
        "tools": openai_tools(declarations),
        "tool_choice": "auto",
    }
    request = urllib.request.Request(
        OPENROUTER_CHAT_URL,
        data=json.dumps(body).encode("utf-8"),
        headers={
            "Content-Type": "application/json",
            "Authorization": f"Bearer {api_key}",
            "X-OpenRouter-Title": "SWPP Team 2 License Planner",
        },
        method="POST",
    )
    try:
        with urllib.request.urlopen(request, timeout=timeout_seconds) as response:
            return json.loads(response.read())
    except urllib.error.HTTPError as error:
        text = error.read().decode("utf-8", errors="replace")
        try:
            message = json.loads(text).get("error", {}).get("message", text)
        except (json.JSONDecodeError, AttributeError):
            message = text
        raise RuntimeError(f"OpenRouter request failed: HTTP {error.code}: {message}") from error
    except (urllib.error.URLError, TimeoutError) as error:
        raise RuntimeError(f"OpenRouter request failed: {error}") from error


def assistant_message(result: dict[str, Any]) -> dict[str, Any]:
    """Extract the sole assistant message and turn malformed upstream replies into retryable errors."""
    choice = (result.get("choices") or [{}])[0]
    if choice.get("error"):
        raise RuntimeError(f"OpenRouter request failed: {choice['error'].get('message', choice['error'])}")
    message = choice.get("message")
    if not isinstance(message, dict):
        raise RuntimeError("OpenRouter response did not contain an assistant message")
    return message


def tool_arguments(call: dict[str, Any]) -> dict[str, Any]:
    raw = call.get("function", {}).get("arguments", "{}")
    try:
        parsed = json.loads(raw) if isinstance(raw, str) else raw
    except json.JSONDecodeError as error:
        raise RuntimeError("OpenRouter returned invalid tool arguments") from error
    if not isinstance(parsed, dict):
        raise RuntimeError("OpenRouter tool arguments must be a JSON object")
    return parsed
