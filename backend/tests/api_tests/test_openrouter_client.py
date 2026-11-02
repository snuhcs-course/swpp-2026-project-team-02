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
from license_planner import settings as planner_settings
from openrouter_client import (InferenceConfigurationError, InferenceError, create_response,
                               decode_call, load_api_key, load_model, parse_reply, usage_record)
from schedule_adjuster import settings as adjuster_settings

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


class TrackingBody(io.BytesIO):
    """HTTP error body that records requested read sizes and closure, or fails while being read."""

    def __init__(self, data=b"", failure=None):
        super().__init__(data)
        self.failure = failure
        self.read_sizes = []

    def read(self, size=-1):
        self.read_sizes.append(size)
        if self.failure is not None:
            raise self.failure
        return super().read(size)


def provider_error_body(error_value):
    return json.dumps({"error": error_value}).encode()


class ProviderDiagnosticTests(unittest.TestCase):
    def reject(self, status, data=b"", failure=None):
        """Send one request the provider rejects; return the error body, attempts, failure and log lines."""
        body = TrackingBody(data, failure)
        rejection = urllib.error.HTTPError("https://openrouter.ai/api/v1/responses", status, "rejected", {}, body)
        with self.assertLogs("openrouter_client", "WARNING") as logs:
            fake, error = send(rejection)
        self.assertIsInstance(error, InferenceError)
        self.assertIsNone(error.__cause__)
        self.assertEqual(len(fake.requests), 1, "exactly one HTTP attempt, no retry")
        self.assertEqual(json.loads(fake.requests[0][0].data)["model"], "openai/gpt-6-luna", "no alternate model")
        self.assertTrue(body.closed, "the HTTP error response is closed")
        self.assertEqual(body.read_sizes, [openrouter_client.DIAGNOSTIC_READ_LIMIT_BYTES])
        self.assertEqual(len(logs.records), 1)
        return error, logs.records[0].getMessage()

    def detail(self, line):
        prefix, _, rest = line.partition("(")
        self.assertTrue(prefix.startswith("OpenRouter Responses request rejected: HTTP "))
        self.assertTrue(rest.endswith(")"))
        return rest[:-1]

    def test_credentials_and_line_breaks_are_removed_from_a_bounded_log_line(self):
        openrouter_key = "sk-or-v1-" + "5ea1" * 16
        bearer = "abc.def-123"
        boundary_token = "tok3nAtTheCut.zz-9"
        message = (f"Key {openrouter_key} rejected\r\nAuthorization: bEaReR {bearer}\nretry later\r"
                   + "x" * 353 + f" BEARER {boundary_token}" + "y" * 200)
        self.assertLess(message.index(boundary_token), 500, "the raw token straddles the length limit")
        self.assertGreater(message.index(boundary_token) + len(boundary_token), 500)
        error, line = self.reject(401, provider_error_body({"message": message}))
        self.assertIn("authentication was rejected (HTTP 401)", str(error))
        for secret in (openrouter_key, "5ea15ea1", bearer, "tok3n"):
            self.assertNotIn(secret, line)
        self.assertNotIn("\n", line)
        self.assertNotIn("\r", line)
        detail = self.detail(line)
        self.assertLessEqual(len(detail), 500)
        self.assertTrue(detail.startswith("Key [redacted] rejected Authorization: [redacted] retry later"))

    def test_useful_nonsecret_provider_message_is_logged_with_status(self):
        error, line = self.reject(429, provider_error_body({"message": "Monthly key quota exceeded", "code": 429}))
        self.assertIn("rate limited (HTTP 429)", str(error))
        self.assertIn("HTTP 429", line)
        self.assertEqual(self.detail(line), "Monthly key quota exceeded")

    def test_malformed_diagnostic_bodies_use_a_fallback_and_keep_the_rejection(self):
        bodies = {
            "null error": provider_error_body(None),
            "string error": provider_error_body(PROVIDER_SECRET),
            "list error": provider_error_body([{"message": PROVIDER_SECRET}]),
            "number error": provider_error_body(429),
            "list root": json.dumps([{"error": {"message": PROVIDER_SECRET}}]).encode(),
            "string root": json.dumps(PROVIDER_SECRET).encode(),
            "null root": b"null",
            "number message": provider_error_body({"message": 7}),
            "list message": provider_error_body({"message": [PROVIDER_SECRET]}),
            "object message": provider_error_body({"message": {"text": PROVIDER_SECRET}}),
            "invalid JSON": f"{{\"error\": {{\"message\": \"{PROVIDER_SECRET}\"".encode(),
            "invalid UTF-8": b"\xff\xfe" + PROVIDER_SECRET.encode(),
            "deeply nested": b"[" * 50_000,
            "empty": b"",
        }
        for status, category in ((401, "authentication was rejected"), (429, "rate limited")):
            for label, data in bodies.items():
                with self.subTest(status=status, body=label):
                    error, line = self.reject(status, data)
                    self.assertIn(category, str(error))
                    self.assertEqual(self.detail(line), "no provider message")
                    self.assertNotIn(PROVIDER_SECRET, line + str(error))

    def test_body_reaching_the_read_cap_is_not_parsed(self):
        limit = openrouter_client.DIAGNOSTIC_READ_LIMIT_BYTES
        prefix = provider_error_body({"message": PROVIDER_SECRET})[:-2]
        for size in (limit, limit + 1, 4 * limit):
            with self.subTest(size=size):
                data = prefix + b" " * (size - len(prefix) - 2) + b"}}"
                self.assertEqual(len(data), size)
                error, line = self.reject(503, data)
                self.assertIn("provider unavailable (HTTP 503)", str(error))
                self.assertEqual(self.detail(line), "provider message too large")
                self.assertNotIn(PROVIDER_SECRET, line)

    def test_interrupted_error_body_reads_keep_the_original_rejection(self):
        failures = (http.client.IncompleteRead(PROVIDER_SECRET.encode()), ConnectionResetError(PROVIDER_SECRET),
                    TimeoutError(PROVIDER_SECRET), http.client.HTTPException(PROVIDER_SECRET))
        for failure in failures:
            with self.subTest(type(failure).__name__):
                error, line = self.reject(429, failure=failure)
                self.assertEqual(str(error), "OpenRouter request failed: rate limited (HTTP 429)")
                self.assertEqual(self.detail(line), "provider message unreadable")
                self.assertNotIn(PROVIDER_SECRET, line)


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

    def test_model_precedence_is_environment_then_first_file_then_default(self):
        os.environ.pop("OPENROUTER_MODEL", None)
        first = self.env_file("first.env", "OPENROUTER_MODEL=\nOPENROUTER_API_KEY=first-key\n")
        second = self.env_file("second.env", "OPENROUTER_MODEL = 'vendor/second-model'\n")
        third = self.env_file("third.env", "OPENROUTER_MODEL=vendor/third-model\n")
        self.assertEqual(load_model([self.root / "absent.env", first]), "openai/gpt-6-luna")
        self.assertEqual(load_model([first, second, third]), "vendor/second-model")
        os.environ["OPENROUTER_MODEL"] = "   "
        self.assertEqual(load_model([first, second, third]), "vendor/second-model", "blank process value")
        os.environ["OPENROUTER_MODEL"] = " vendor/process-model "
        self.assertEqual(load_model([first, second, third]), "vendor/process-model")

    def test_copied_example_configuration_keeps_the_default_model(self):
        os.environ.pop("OPENROUTER_MODEL", None)
        example = Path(openrouter_client.__file__).with_name(".env.example").read_text(encoding="utf-8")
        lines = [f"OPENROUTER_API_KEY={SYNTHETIC_KEY}" if line.startswith("OPENROUTER_API_KEY=") else line
                 for line in example.splitlines()]
        self.assertIn(f"OPENROUTER_API_KEY={SYNTHETIC_KEY}", lines)
        copied = self.env_file(".env", "\n".join(lines) + "\n")
        self.assertEqual((load_api_key([copied]), load_model([copied])), (SYNTHETIC_KEY, "openai/gpt-6-luna"))
        with patch("license_planner.settings.DOTENV_PATH", copied), \
                patch("schedule_adjuster.settings.DOTENV_PATH", self.root / "absent.env"), \
                patch("schedule_adjuster.settings.WORKSPACE_DOTENV_PATH", copied):
            self.assertEqual((planner_settings.load_model(), adjuster_settings.load_model()),
                             ("openai/gpt-6-luna", "openai/gpt-6-luna"))
            enabled = self.env_file("enabled.env", "OPENROUTER_MODEL=vendor/opted-in\n")
            with patch("schedule_adjuster.settings.DOTENV_PATH", enabled):
                self.assertEqual(adjuster_settings.load_model(), "vendor/opted-in",
                                 "the program file precedes the workspace file")

    def test_legacy_key_alone_is_missing_configuration(self):
        os.environ["GEMINI_API_KEY"] = "legacy-env-key"
        legacy = self.env_file(".env", "GEMINI_API_KEY=legacy-file-key\n")
        with self.assertRaises(InferenceConfigurationError) as raised:
            load_api_key([legacy])
        self.assertIsInstance(raised.exception, RuntimeError)
        self.assertEqual(str(raised.exception), "OPENROUTER_API_KEY is not configured on the server.")


if __name__ == "__main__":
    unittest.main()
