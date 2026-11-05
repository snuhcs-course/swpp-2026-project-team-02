#!/usr/bin/env python3
"""Serve the production HTTP API with recorded PlanningAgent tool calls.

This is an E2E fixture for the Android live flavor.  It uses the real API
handler, request validation, scheduler, and StudyPlanStore, but replaces only
the outbound OpenRouter exchange with a checked-in recorded tool-call trace.
It must never be deployed or configured with a production API key.
"""

from __future__ import annotations

import argparse
import contextlib
import copy
import itertools
import json
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
from api_server import API_PREFIX, DEFAULT_API_CONFIG, make_handler
from license_planner.agent import PlanningAgent


FIXTURE_PATH = BACKEND_ROOT / "tests" / "api_tests" / "fixtures" / "recorded_model_tool_calls.json"
DEFAULT_CASE_ID = "topic_errors_then_build_on_last_request"


def load_cases() -> dict[str, dict]:
    """Load only the recorded model responses used by the fixture server."""
    cases = json.loads(FIXTURE_PATH.read_text(encoding="utf-8"))
    if not isinstance(cases, dict):
        raise ValueError("recorded model fixture must contain an object")
    return cases


def recorded_agent(case: dict) -> type[PlanningAgent]:
    """Create a PlanningAgent class that returns one recorded tool call per request."""
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
                raise RuntimeError("fixed E2E fixture exhausted recorded model tool calls")
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


@dataclass(frozen=True)
class FixedLiveE2EServer:
    """The running production handler and its isolated plan-store directory."""

    api_base_url: str
    output_dir: Path


@contextlib.contextmanager
def running_server(host: str = "127.0.0.1", port: int = 0,
                   case_id: str = DEFAULT_CASE_ID) -> Iterator[FixedLiveE2EServer]:
    """Start the real HTTP handler with one recorded planning trace and isolated storage."""
    cases = load_cases()
    try:
        case = cases[case_id]
    except KeyError as error:
        raise ValueError(f"unknown fixed E2E case {case_id!r}; choose from {sorted(cases)}") from error

    with tempfile.TemporaryDirectory(prefix="licenseplanner-fixed-e2e-") as directory:
        output_dir = Path(directory)
        config = {**DEFAULT_API_CONFIG, "host": host, "port": port}
        with patch.object(api_service, "STUDY_PLAN_OUTPUT_DIR", output_dir), \
                patch.object(api_service, "PlanningAgent", recorded_agent(case)):
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
    parser.add_argument("--case-id", default=DEFAULT_CASE_ID, help="recorded planning case to replay")
    args = parser.parse_args()
    if not 1 <= args.port <= 65535:
        parser.error("--port must be between 1 and 65535")

    try:
        with running_server(args.host, args.port, args.case_id) as fixture:
            print(f"Fixed live E2E API listening at {fixture.api_base_url}", flush=True)
            print("OpenRouter is disabled; press Ctrl+C to stop.", flush=True)
            threading.Event().wait()
    except KeyboardInterrupt:
        return 0
    except (OSError, ValueError) as error:
        print(f"fixed live E2E server failed: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
