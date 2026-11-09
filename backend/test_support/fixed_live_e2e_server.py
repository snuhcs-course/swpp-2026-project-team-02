#!/usr/bin/env python3
"""Serve the production HTTP API with fixed model replies instead of OpenRouter.

This is an E2E fixture for the Android live flavor.  It uses the real API
handler, request validation, scheduler, adjustment agent loop and tools,
stored exam deadlines, and StudyPlanStore, but replaces only the outbound
OpenRouter exchange: planning replays a checked-in tool-call trace, and
adjustment replies are chosen deterministically from the conversation the
agent sends.  The default trace is a synthetic one for a complete onboarding
selection; the recorded five-question traces remain selectable by case ID.
Any attempt to read an API key or send a provider request fails.
It must never be deployed or configured with a production API key.
"""

from __future__ import annotations

import argparse
import contextlib
import copy
import itertools
import json
import re
import sys
import tempfile
import threading
from dataclasses import dataclass
from http.server import ThreadingHTTPServer
from pathlib import Path
from typing import Iterator
from unittest.mock import patch


BACKEND_ROOT = Path(__file__).resolve().parents[1]
if str(BACKEND_ROOT) not in sys.path:
    sys.path.insert(0, str(BACKEND_ROOT))

import api_service
import license_planner.agent
import schedule_adjuster.agent
from api_server import API_PREFIX, DEFAULT_API_CONFIG, make_handler
from license_planner.agent import PlanningAgent
from schedule_adjuster.agent import AdjustmentAgent


FIXTURE_DIR = BACKEND_ROOT / "tests" / "api_tests" / "fixtures"
FIXTURE_PATH = FIXTURE_DIR / "recorded_model_tool_calls.json"
SCRIPTED_FIXTURE_PATH = FIXTURE_DIR / "scripted_selected_assessment_tool_calls.json"
LEGACY_CASE_ID = "topic_errors_then_build_on_last_request"
DEFAULT_CASE_ID = "selected_onboarding_reviewed_topics"


def load_cases() -> dict[str, dict]:
    """Load the recorded and synthetic model responses used by the fixture server."""
    cases: dict[str, dict] = {}
    for path in (FIXTURE_PATH, SCRIPTED_FIXTURE_PATH):
        loaded = json.loads(path.read_text(encoding="utf-8"))
        if not isinstance(loaded, dict):
            raise ValueError(f"model fixture {path.name} must contain an object")
        if cases.keys() & loaded.keys():
            raise ValueError(f"model fixture {path.name} repeats a case ID")
        cases.update(loaded)
    return cases


def recorded_agent(case: dict) -> type[PlanningAgent]:
    """Create a PlanningAgent class that returns one fixture tool call per request."""
    calls = case.get("model_tool_calls")
    if not isinstance(calls, list) or not calls:
        raise ValueError("fixture case must contain non-empty model_tool_calls")
    call_ids = itertools.count(1)

    class RecordedPlanningAgent(PlanningAgent):
        def __init__(self, *args, **kwargs):
            super().__init__(*args, **kwargs)
            self._replies = copy.deepcopy(calls)

        def _ask_model(self, input_items, tools):
            if not self._replies:
                raise RuntimeError("fixed E2E fixture exhausted its model tool calls")
            reply = self._replies.pop(0)
            name, arguments = reply.get("name"), reply.get("args")
            if not isinstance(name, str) or not isinstance(arguments, dict):
                raise RuntimeError("fixed E2E fixture contains an invalid model tool call")
            self.model = "fixed-e2e-fixture"
            self.api_request_count += 1
            self.successful_response_count += 1
            return {
                "status": "completed",
                "output": [{
                    "type": "function_call",
                    "call_id": f"fixed-call-{next(call_ids)}",
                    "name": name,
                    "arguments": json.dumps(arguments, ensure_ascii=False),
                }],
            }

    return RecordedPlanningAgent


def _function_call(call_id: str, name: str, arguments: dict) -> dict:
    return {"status": "completed", "output": [{
        "type": "function_call", "call_id": call_id, "name": name,
        "arguments": json.dumps(arguments, ensure_ascii=False)}]}


class ScriptedAdjustmentAgent(AdjustmentAgent):
    """Run the real adjustment loop; only the model's next tool call is fixed.

    Like a well-behaved model, it inspects the target named in the user
    message, inspects that study session's topic, then asks for the matching
    mutation tool.  A tool error ends the turn with a plain text reply.
    """

    def __init__(self, *args, **kwargs):
        super().__init__(*args, **kwargs)
        self._call_ids = itertools.count(1)

    def _ask_model(self, input_items):
        self.model = "fixed-e2e-fixture"
        self.api_request_count += 1
        call_id = f"fixed-adjust-{next(self._call_ids)}"
        outputs = [item for item in input_items if item.get("type") == "function_call_output"]
        if not outputs:
            match = re.search(r'"schedule_id": "([^"]+)"', str(input_items[0].get("content", "")))
            if match is None:
                raise RuntimeError("fixed E2E fixture could not find the adjustment target")
            return _function_call(call_id, "inspect_schedule", {"schedule_id": match.group(1)})
        last = outputs[-1]
        name = next(item["name"] for item in input_items
                    if item.get("type") == "function_call" and item.get("call_id") == last["call_id"])
        result = json.loads(last["output"])["result"]
        if not isinstance(result, dict):
            return {"status": "completed", "output": [{
                "type": "message", "role": "assistant",
                "content": [{"type": "output_text", "text": "The adjustment could not be applied."}]}]}
        if name == "inspect_schedule" and result["schedule_type"] == "external":
            return _function_call(call_id, "remove_cancelled_external_schedule",
                                  {"schedule_id": result["schedule_id"]})
        if name == "inspect_schedule" and result["topic"]:
            return _function_call(call_id, "inspect_topic_schedule", {"topic": result["topic"]})
        target = result["schedule_id"] if name == "inspect_schedule" else result["failed_session_id"]
        return _function_call(call_id, "reschedule_failed_study", {"schedule_id": target})


def _forbidden_provider_access(*args, **kwargs):
    raise RuntimeError("the fixed E2E fixture must not read provider credentials or call OpenRouter")


@dataclass(frozen=True)
class FixedLiveE2EServer:
    """The running production handler and its isolated plan-store directory."""

    api_base_url: str
    output_dir: Path


@contextlib.contextmanager
def running_server(host: str = "127.0.0.1", port: int = 0,
                   case_id: str = DEFAULT_CASE_ID) -> Iterator[FixedLiveE2EServer]:
    """Start the real HTTP handler with one planning trace and isolated storage."""
    cases = load_cases()
    try:
        case = cases[case_id]
    except KeyError as error:
        raise ValueError(f"unknown fixed E2E case {case_id!r}; choose from {sorted(cases)}") from error

    with tempfile.TemporaryDirectory(prefix="licenseplanner-fixed-e2e-") as directory:
        output_dir = Path(directory)
        config = {**DEFAULT_API_CONFIG, "host": host, "port": port}
        with patch.object(api_service, "STUDY_PLAN_OUTPUT_DIR", output_dir), \
                patch.object(api_service, "PlanningAgent", recorded_agent(case)), \
                patch.object(api_service, "AdjustmentAgent", ScriptedAdjustmentAgent), \
                patch.multiple(license_planner.agent, create_response=_forbidden_provider_access,
                               load_api_key=_forbidden_provider_access, load_model=_forbidden_provider_access), \
                patch.multiple(schedule_adjuster.agent, create_response=_forbidden_provider_access,
                               load_api_key=_forbidden_provider_access, load_model=_forbidden_provider_access):
            server = ThreadingHTTPServer((host, port), make_handler(config))
            worker = threading.Thread(target=server.serve_forever, daemon=True)
            worker.start()
            bound_host, bound_port = server.server_address[:2]
            try:
                yield FixedLiveE2EServer(f"http://{bound_host}:{bound_port}{API_PREFIX}", output_dir)
            finally:
                server.shutdown()
                server.server_close()
                worker.join(timeout=5)


def main() -> int:
    """Bind the fixed-response production API until interrupted by the caller."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--host", default="0.0.0.0", help="address to bind (default: 0.0.0.0)")
    parser.add_argument("--port", type=int, default=8000, help="TCP port to bind (default: 8000)")
    parser.add_argument("--case-id", default=DEFAULT_CASE_ID,
                        help=f"planning case to replay (default: {DEFAULT_CASE_ID})")
    args = parser.parse_args()
    if not 1 <= args.port <= 65535:
        parser.error("--port must be between 1 and 65535")

    try:
        with running_server(args.host, args.port, args.case_id) as fixture:
            print(f"Fixed live E2E API listening at {fixture.api_base_url}", flush=True)
            print(f"Plans are stored only in {fixture.output_dir}", flush=True)
            print("OpenRouter is disabled; press Ctrl+C to stop.", flush=True)
            threading.Event().wait()
    except KeyboardInterrupt:
        return 0
    except (OSError, ValueError) as error:
        print(f"fixed live E2E server failed: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
