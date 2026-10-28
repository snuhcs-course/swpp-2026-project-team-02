"""OpenRouter transport request shape, credentials and safe failures at the HTTP boundary."""
import http.client
import io
import json
import os
import tempfile
import unittest
import urllib.error
from pathlib import Path
from unittest.mock import patch

import openrouter_client
from openrouter_client import (InferenceConfigurationError, InferenceError, create_response,
                               decode_call, load_api_key, parse_reply, usage_record)

SYNTHETIC_KEY = "synthetic-openrouter-key-5c1d"
PROVIDER_SECRET = "provider-body-secret-91ab"
DECLARATION = {"name": "inspect_schedule", "description": "Look up one row.",
               "parameters": {"type": "object", "properties": {"schedule_id": {"type": "string"}},
                              "required": ["schedule_id"]}}


class FakeResponse:
    def __init__(self, body):
        self.body = body

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        return False

    def read(self):
        return self.body


class RecordingUrlopen:
    """Stands in for urllib.request.urlopen and records every attempted HTTP request."""

    def __init__(self, outcome):
        self.outcome = outcome
        self.requests = []

    def __call__(self, request, timeout=None):
        self.requests.append((request, timeout))
        if isinstance(self.outcome, BaseException):
            raise self.outcome
        body = self.outcome if isinstance(self.outcome, bytes) else json.dumps(self.outcome).encode()
        return FakeResponse(body)


def completed(*items, **extra):
    return {"status": "completed", "output": list(items), **extra}


def send(outcome, reasoning_effort="low", max_output_tokens=16384):
    fake = RecordingUrlopen(outcome)
    with patch.object(openrouter_client.urllib.request, "urlopen", fake):
        try:
            return fake, create_response(SYNTHETIC_KEY, instructions="Use tools.",
                                         input_items=[{"role": "user", "content": "hi"}],
                                         tools=[DECLARATION], reasoning_effort=reasoning_effort,
                                         max_output_tokens=max_output_tokens)
        except InferenceError as error:
            return fake, error


class RequestShapeTests(unittest.TestCase):
    def test_request_targets_luna_responses_with_bearer_auth_and_bounds(self):
        fake, result = send(completed())
        self.assertEqual(result, completed())
        self.assertEqual(len(fake.requests), 1)
        request, timeout = fake.requests[0]
        self.assertEqual((request.full_url, request.get_method(), timeout),
                         ("https://openrouter.ai/api/v1/responses", "POST", 45))
        self.assertEqual(request.get_header("Authorization"), f"Bearer {SYNTHETIC_KEY}")
        self.assertEqual(request.get_header("Content-type"), "application/json")
        body = json.loads(request.data)
        self.assertEqual(body["model"], "openai/gpt-6-luna")
        self.assertEqual(body["reasoning"], {"effort": "low"})
        self.assertEqual(body["max_output_tokens"], 16384)
        self.assertEqual(body["include"], ["reasoning.encrypted_content"])
        self.assertEqual((body["store"], body["stream"], body["tool_choice"], body["parallel_tool_calls"]),
                         (False, False, "auto", False))
        self.assertEqual(body["instructions"], "Use tools.")
        self.assertEqual(body["input"], [{"role": "user", "content": "hi"}])
        self.assertEqual(body["tools"], [{"type": "function", **DECLARATION, "strict": False}])
        self.assertNotIn("temperature", body)
        self.assertNotIn("previous_response_id", body)

    def test_disabled_reasoning_does_not_request_reasoning_items(self):
        fake, _ = send(completed(), reasoning_effort="none", max_output_tokens=2048)
        body = json.loads(fake.requests[0][0].data)
        self.assertEqual((body["reasoning"], body["max_output_tokens"]), ({"effort": "none"}, 2048))
        self.assertNotIn("include", body)


class FailureTests(unittest.TestCase):
    def assert_safe_failure(self, outcome, expected):
        fake, error = send(outcome)
        self.assertIsInstance(error, InferenceError)
        self.assertNotIsInstance(error, InferenceConfigurationError)
        self.assertIn(expected, str(error))
        for secret in (SYNTHETIC_KEY, PROVIDER_SECRET):
            self.assertNotIn(secret, str(error))
        self.assertIsNone(error.__cause__)
        self.assertEqual(len(fake.requests), 1, "exactly one HTTP attempt, no retry")
        body = json.loads(fake.requests[0][0].data)
        self.assertEqual(body["model"], "openai/gpt-6-luna", "no alternate model")

    def test_provider_http_failures_are_categorized_without_the_body(self):
        cases = {401: "authentication was rejected", 403: "authentication was rejected",
                 402: "spending limit", 429: "rate limited", 500: "provider unavailable",
                 503: "provider unavailable", 400: "request rejected"}
        for status, expected in cases.items():
            with self.subTest(status=status):
                body = io.BytesIO(json.dumps({"error": {"message": PROVIDER_SECRET}}).encode())
                error = urllib.error.HTTPError("https://openrouter.ai/api/v1/responses", status,
                                               PROVIDER_SECRET, {}, body)
                self.assert_safe_failure(error, expected)

    def test_network_failures_and_timeouts(self):
        for outcome in (TimeoutError(PROVIDER_SECRET), urllib.error.URLError(PROVIDER_SECRET),
                        ConnectionResetError(PROVIDER_SECRET), http.client.IncompleteRead(b"")):
            with self.subTest(type(outcome).__name__):
                self.assert_safe_failure(outcome, "network error")

    def test_non_json_and_non_object_bodies(self):
        self.assert_safe_failure(f"<html>{PROVIDER_SECRET}</html>".encode(), "not JSON")
        self.assert_safe_failure(b"\xff\xfe", "not JSON")
        self.assert_safe_failure([PROVIDER_SECRET], "not a JSON object")


class ReplyParsingTests(unittest.TestCase):
    def test_output_items_calls_and_text_are_returned_in_order(self):
        reasoning = {"type": "reasoning", "id": "rs_1", "encrypted_content": "opaque", "summary": []}
        call = {"type": "function_call", "id": "fc_1", "call_id": "call_a", "name": "inspect_schedule",
                "arguments": "{\"schedule_id\": \"study-1\"}"}
        message = {"type": "message", "role": "assistant",
                   "content": [{"type": "output_text", "text": "Checking."}]}
        output, calls, text = parse_reply(completed(reasoning, message, call))
        self.assertEqual(output, [reasoning, message, call])
        self.assertEqual(calls, [call])
        self.assertEqual(text, "Checking.")

    def test_error_refusal_incomplete_and_malformed_replies_fail(self):
        cases = {
            "error": ({"status": "failed", "error": {"message": PROVIDER_SECRET}, "output": []}, "error response"),
            "refusal": (completed({"type": "message", "content": [{"type": "refusal", "refusal": PROVIDER_SECRET}]}),
                        "refused"),
            "incomplete": ({"status": "incomplete", "incomplete_details": {"reason": "max_output_tokens"},
                            "output": []}, "incomplete (max_output_tokens)"),
            "unsafe incomplete reason": ({"status": "incomplete", "incomplete_details": {"reason": PROVIDER_SECRET},
                                         "output": []}, "incomplete (unknown)"),
            "in progress": ({"status": "in_progress", "output": []}, "did not complete"),
            "missing output": ({"status": "completed"}, "malformed"),
            "untyped item": (completed({"call_id": "x"}), "malformed"),
            "message content": (completed({"type": "message", "content": PROVIDER_SECRET}), "malformed"),
        }
        for label, (response, expected) in cases.items():
            with self.subTest(label), self.assertRaises(InferenceError) as raised:
                parse_reply(response)
            self.assertIn(expected, str(raised.exception))
            self.assertNotIn(PROVIDER_SECRET, str(raised.exception))

    def test_calls_are_validated_when_reached(self):
        seen = set()
        call = {"type": "function_call", "call_id": "call_a", "name": "inspect_schedule",
                "arguments": "{\"schedule_id\": \"study-1\"}"}
        self.assertEqual(decode_call(call, ["inspect_schedule"], seen),
                         ("call_a", "inspect_schedule", {"schedule_id": "study-1"}))
        invalid = {
            "duplicate call_id": ({**call}, "repeated"),
            "missing call_id": ({**call, "call_id": None}, "without a call_id"),
            "empty call_id": ({**call, "call_id": ""}, "without a call_id"),
            "item id is not a call_id": ({"type": "function_call", "id": "fc_9", "name": "inspect_schedule",
                                          "arguments": "{}"}, "without a call_id"),
            "unsupported tool": ({**call, "call_id": "call_b", "name": "delete_everything"}, "unsupported tool"),
            "invalid JSON": ({**call, "call_id": "call_c", "arguments": "{\"schedule_id\""}, "not a JSON object"),
            "array arguments": ({**call, "call_id": "call_d", "arguments": "[1]"}, "not a JSON object"),
            "decoded object": ({**call, "call_id": "call_e", "arguments": {"schedule_id": "study-1"}},
                               "not a JSON object"),
        }
        for label, (item, expected) in invalid.items():
            with self.subTest(label), self.assertRaisesRegex(InferenceError, expected):
                decode_call(item, ["inspect_schedule"], seen)

    def test_usage_keeps_allowlisted_numbers_and_marks_absent_values_unknown(self):
        record = usage_record({"model": "openai/gpt-6-luna", "usage": {
            "input_tokens": 120, "output_tokens": 30, "total_tokens": 150, "cost": 0.000027,
            "output_tokens_details": {"reasoning_tokens": 12}, "user_id": PROVIDER_SECRET}})
        self.assertEqual(record, {"input_tokens": 120, "output_tokens": 30, "total_tokens": 150,
                                  "cost": 0.000027, "reasoning_tokens": 12, "model": "openai/gpt-6-luna"})
        self.assertEqual(usage_record({}), {"input_tokens": None, "output_tokens": None, "total_tokens": None,
                                            "cost": None, "reasoning_tokens": None, "model": None})


class CredentialTests(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name)
        environment = patch.dict(os.environ)
        environment.start()
        self.addCleanup(environment.stop)
        os.environ.pop("OPENROUTER_API_KEY", None)
        os.environ.pop("GEMINI_API_KEY", None)

    def env_file(self, name, text):
        path = self.root / name
        path.write_text(text, encoding="utf-8")
        return path

    def test_environment_takes_precedence_over_local_files(self):
        local = self.env_file(".env", "OPENROUTER_API_KEY=file-key\n")
        os.environ["OPENROUTER_API_KEY"] = SYNTHETIC_KEY
        self.assertEqual(load_api_key([local]), SYNTHETIC_KEY)

    def test_local_files_are_read_in_order_for_the_openrouter_name_only(self):
        first = self.env_file("first.env", "# comment\nOTHER=x\nOPENROUTER_API_KEY=\n")
        second = self.env_file("second.env", "OPENROUTER_API_KEY = \"second-key\"\n")
        third = self.env_file("third.env", "OPENROUTER_API_KEY=third-key\n")
        self.assertEqual(load_api_key([self.root / "absent.env", first, second, third]), "second-key")

    def test_legacy_key_alone_is_missing_configuration(self):
        os.environ["GEMINI_API_KEY"] = "legacy-env-key"
        legacy = self.env_file(".env", "GEMINI_API_KEY=legacy-file-key\n")
        with self.assertRaises(InferenceConfigurationError) as raised:
            load_api_key([legacy])
        self.assertIsInstance(raised.exception, RuntimeError)
        self.assertEqual(str(raised.exception), "OPENROUTER_API_KEY is not configured on the server.")


if __name__ == "__main__":
    unittest.main()
