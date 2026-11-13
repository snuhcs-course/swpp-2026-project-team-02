#!/usr/bin/env python3
"""Serve the production HTTP API with fixed model replies instead of OpenRouter.

This is an E2E fixture for the Android live flavor.  It uses the real API
handler, request validation, scheduler, adjustment agent loop and tools,
stored exam deadlines, and StudyPlanStore, but replaces only the outbound
OpenRouter exchange: planning replays a checked-in tool-call trace, and
adjustment replies are chosen deterministically from the conversation the
agent sends.  The default trace is a synthetic one for a complete onboarding
selection; the recorded five-question traces remain selectable by case ID.
``--planning scripted`` instead derives the planning tool calls from each
request's own dates, availability and required topics, so plans can be made for
any period.  The availability profile and plans live in a private storage
directory (temporary unless ``--storage-dir`` is given), never in
``backend/config`` or the real plan output directory.  ``--now`` freezes the
clock used for manual-move validation, ``--request-log`` appends one JSON line
per request, and ``--test-control`` names an optional JSON file of failure
switches that exist only in this fixture.
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
import signal
import sys
import tempfile
import threading
import time
from dataclasses import dataclass
from datetime import datetime
from http.server import ThreadingHTTPServer
from pathlib import Path
from typing import Iterator
from unittest.mock import patch


BACKEND_ROOT = Path(__file__).resolve().parents[1]
if str(BACKEND_ROOT) not in sys.path:
    sys.path.insert(0, str(BACKEND_ROOT))

import api_service
import license_planner.agent
import license_planner.user_availability
import schedule_adjuster.agent
import schedule_adjuster.settings
from api_server import API_PREFIX, DEFAULT_API_CONFIG, make_handler
from license_planner.agent import PlanningAgent
from schedule_adjuster.agent import AdjustmentAgent


FIXTURE_DIR = BACKEND_ROOT / "tests" / "api_tests" / "fixtures"
FIXTURE_PATH = FIXTURE_DIR / "recorded_model_tool_calls.json"
SCRIPTED_FIXTURE_PATH = FIXTURE_DIR / "scripted_selected_assessment_tool_calls.json"
LEGACY_CASE_ID = "topic_errors_then_build_on_last_request"
DEFAULT_CASE_ID = "selected_onboarding_reviewed_topics"
PLANNING_MODES = ("recorded", "scripted")
WIRE_FORMAT = "%Y/%m/%d/%H/%M"
PROFILE_FILE_NAME = "user_availability.json"
PLAN_DIR_NAME = "plans"


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


class ScriptedPlanningAgent(PlanningAgent):
    """Run the real planning loop; the model's tool calls come from the request itself.

    It asks for ``analyze_exam_results`` without classifications, then builds
    one 60-minute session per date that has at least 60 usable minutes, cycling
    through the required topics.  Requests whose questions need classification
    (custom questions) are outside what it can answer.
    """

    def __init__(self, *args, **kwargs):
        super().__init__(*args, **kwargs)
        self._call_ids = itertools.count(1)

    def _ask_model(self, input_items, tools):
        self.model = "fixed-e2e-fixture"
        self.api_request_count += 1
        self.successful_response_count += 1
        call_id = f"fixed-plan-{next(self._call_ids)}"
        outputs = [item for item in input_items if item.get("type") == "function_call_output"]
        if not outputs:
            return _function_call(call_id, "analyze_exam_results", {})
        last = json.loads(outputs[-1]["output"])
        requirements = last.get("build_requirements") if isinstance(last, dict) else None
        if not isinstance(last.get("result"), dict) or not requirements \
                or "usable_minutes_by_date" not in requirements:
            raise RuntimeError(f"scripted planning fixture cannot continue after: {last}")
        topics = requirements["required_topics"]
        dates = sorted(day for day, minutes in requirements["usable_minutes_by_date"].items() if minutes >= 60)
        if not topics or len(dates) < len(topics):
            raise RuntimeError("scripted planning fixture needs one date with 60 usable minutes per topic")
        sessions = [{"date": day, "topic": topics[index % len(topics)], "minutes": 60}
                    for index, day in enumerate(dates)]
        return _function_call(call_id, "build_study_schedule", {"daily_topic_minutes": sessions})


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
    """The running production handler and its isolated storage."""

    api_base_url: str
    output_dir: Path
    profile_path: Path
    storage_dir: Path


class _TestControl:
    """Failure switches read from an optional JSON file; they are test scaffolding, not server behavior.

    ``adjust_delay_seconds``: sleep before handling each ``POST /schedules/adjust``.
    ``drop_next_adjust_response``: handle the next adjustment (the plan is saved)
    but close the connection without writing a response; consumed once.
    ``inject_busy_schedules``: rows appended to the ``busy_schedules`` of the
    next ``POST /study-plans`` before the handler reads it; consumed once.
    """

    def __init__(self, path: Path | None, lock: threading.Lock):
        self.path, self.lock = path, lock

    def read(self) -> dict:
        if self.path is None:
            return {}
        with self.lock:
            try:
                loaded = json.loads(self.path.read_text(encoding="utf-8"))
            except (OSError, ValueError):
                return {}
        return loaded if isinstance(loaded, dict) else {}

    def consume(self, key: str):
        """Return one switch and remove it from the file."""
        if self.path is None:
            return None
        with self.lock:
            try:
                loaded = json.loads(self.path.read_text(encoding="utf-8"))
            except (OSError, ValueError):
                return None
            if not isinstance(loaded, dict) or key not in loaded:
                return None
            value = loaded.pop(key)
            self.path.write_text(json.dumps(loaded, ensure_ascii=False), encoding="utf-8")
            return value


def _recording_handler(base: type, log_path: Path | None, control: _TestControl,
                       lock: threading.Lock) -> type:
    """Wrap the production handler with a JSON-lines request log and the test switches."""

    def write_log(entry: dict) -> None:
        if log_path is None:
            return
        entry = {"time": datetime.now().isoformat(timespec="seconds"), **entry}
        with lock, log_path.open("a", encoding="utf-8") as handle:
            handle.write(json.dumps(entry, ensure_ascii=False) + "\n")

    class RecordingHandler(base):
        _drop_response = False
        _log_events: list = []

        def _read_json(self):
            try:
                payload = super()._read_json()
            except ValueError:
                self._log_body = "<unreadable request body>"
                raise
            self._log_body = copy.deepcopy(payload)
            if self.command == "POST" and self.path.endswith("/study-plans"):
                rows = control.consume("inject_busy_schedules")
                if isinstance(rows, list) and rows:
                    payload["busy_schedules"] = list(payload.get("busy_schedules") or []) + rows
                    self._log_events.append({"test_control": "inject_busy_schedules", "rows": rows})
            return payload

        def _send_json(self, status, payload):
            self._log_status, self._log_response = status, payload
            if self._drop_response:
                self.close_connection = True
                return
            super()._send_json(status, payload)

        def _send_csv(self, filename, content):
            self._log_status, self._log_response = 200, {"csv_file": filename}
            super()._send_csv(filename, content)

        def _handle_logged(self, method: str) -> None:
            self._log_status = self._log_response = self._log_body = None
            self._log_events, self._drop_response = [], False
            if method == "POST" and self.path.endswith("/schedules/adjust"):
                delay = control.read().get("adjust_delay_seconds")
                if isinstance(delay, (int, float)) and delay > 0:
                    self._log_events.append({"test_control": "adjust_delay_seconds", "seconds": delay})
                    time.sleep(float(delay))
                if control.consume("drop_next_adjust_response"):
                    self._drop_response = True
                    self._log_events.append({"test_control": "drop_next_adjust_response"})
            try:
                getattr(base, f"do_{method}")(self)
            finally:
                entry = {"method": method, "path": self.path, "status": self._log_status,
                         "body": self._log_body, "response": self._log_response}
                if self._log_events:
                    entry["test_control"] = self._log_events
                if self._drop_response:
                    entry["response_dropped"] = True
                write_log(entry)

        def do_GET(self):
            self._handle_logged("GET")

        def do_POST(self):
            self._handle_logged("POST")

        def do_PUT(self):
            self._handle_logged("PUT")

    return RecordingHandler


def parse_wire_minute(value: str) -> datetime:
    """Parse a ``YYYY/MM/DD/HH/MM`` timestamp for the frozen manual-move clock."""
    try:
        return datetime.strptime(value, WIRE_FORMAT)
    except ValueError as error:
        raise ValueError(f"--now must use YYYY/MM/DD/HH/MM, got {value!r}") from error


@contextlib.contextmanager
def _storage_root(storage_dir: Path | None) -> Iterator[Path]:
    if storage_dir is not None:
        storage_dir = Path(storage_dir).resolve()
        storage_dir.mkdir(parents=True, exist_ok=True)
        yield storage_dir
        return
    with tempfile.TemporaryDirectory(prefix="licenseplanner-fixed-e2e-") as directory:
        yield Path(directory)


@contextlib.contextmanager
def running_server(host: str = "127.0.0.1", port: int = 0,
                   case_id: str = DEFAULT_CASE_ID, *, planning: str = "recorded",
                   storage_dir: Path | None = None, profile_seed: Path | None = None,
                   now: datetime | None = None, request_log: Path | None = None,
                   test_control: Path | None = None) -> Iterator[FixedLiveE2EServer]:
    """Start the real HTTP handler with fixed model replies and isolated storage.

    ``storage_dir`` keeps plans (``plans/``) and the availability profile
    (``user_availability.json``) across restarts; without it both live in a
    temporary directory removed on exit.  ``profile_seed`` is copied to the
    profile only when the profile does not exist yet.  ``now`` replaces the
    clock that manual moves compare against.
    """
    if planning not in PLANNING_MODES:
        raise ValueError(f"unknown planning mode {planning!r}; choose from {list(PLANNING_MODES)}")
    if planning == "recorded":
        cases = load_cases()
        try:
            planning_agent = recorded_agent(cases[case_id])
        except KeyError as error:
            raise ValueError(f"unknown fixed E2E case {case_id!r}; choose from {sorted(cases)}") from error
    else:
        planning_agent = ScriptedPlanningAgent
    seed_bytes = None
    if profile_seed is not None:
        seed_bytes = Path(profile_seed).read_bytes()
        json.loads(seed_bytes)

    with _storage_root(storage_dir) as root:
        output_dir, profile_path = root / PLAN_DIR_NAME, root / PROFILE_FILE_NAME
        output_dir.mkdir(exist_ok=True)
        if seed_bytes is not None and not profile_path.exists():
            profile_path.write_bytes(seed_bytes)
        lock = threading.Lock()
        log_path = Path(request_log).resolve() if request_log is not None else None
        control = _TestControl(Path(test_control).resolve() if test_control is not None else None, lock)
        config = {**DEFAULT_API_CONFIG, "host": host, "port": port}
        with contextlib.ExitStack() as patches:
            patches.enter_context(patch.object(api_service, "STUDY_PLAN_OUTPUT_DIR", output_dir))
            patches.enter_context(patch.object(api_service, "PlanningAgent", planning_agent))
            patches.enter_context(patch.object(api_service, "AdjustmentAgent", ScriptedAdjustmentAgent))
            patches.enter_context(patch.object(
                license_planner.user_availability, "USER_AVAILABILITY_PATH", profile_path))
            patches.enter_context(patch.object(schedule_adjuster.settings, "USER_AVAILABILITY_PATH", profile_path))
            for module in (license_planner.agent, schedule_adjuster.agent):
                patches.enter_context(patch.multiple(
                    module, create_response=_forbidden_provider_access,
                    load_api_key=_forbidden_provider_access, load_model=_forbidden_provider_access))
            if now is not None:
                frozen = now.replace(second=0, microsecond=0, tzinfo=None)
                patches.enter_context(patch.object(api_service, "current_korea_minute", lambda: frozen))
            handler = _recording_handler(make_handler(config), log_path, control, lock)
            server = ThreadingHTTPServer((host, port), handler)
            worker = threading.Thread(target=server.serve_forever, daemon=True)
            worker.start()
            bound_host, bound_port = server.server_address[:2]
            try:
                yield FixedLiveE2EServer(f"http://{bound_host}:{bound_port}{API_PREFIX}",
                                         output_dir, profile_path, root)
            finally:
                server.shutdown()
                server.server_close()
                worker.join(timeout=5)


def _stop_on_signal(signum, frame):
    raise KeyboardInterrupt


def main() -> int:
    """Bind the fixed-response production API until interrupted by the caller."""
    # Background shells start children with SIGINT ignored; stop cleanly on SIGINT or SIGTERM.
    signal.signal(signal.SIGINT, _stop_on_signal)
    signal.signal(signal.SIGTERM, _stop_on_signal)
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--host", default="0.0.0.0", help="address to bind (default: 0.0.0.0)")
    parser.add_argument("--port", type=int, default=8000, help="TCP port to bind (default: 8000)")
    parser.add_argument("--case-id", default=DEFAULT_CASE_ID,
                        help=f"planning case to replay in recorded mode (default: {DEFAULT_CASE_ID})")
    parser.add_argument("--planning", choices=PLANNING_MODES, default="recorded",
                        help="recorded: replay --case-id; scripted: derive tool calls from each request "
                             "(default: recorded)")
    parser.add_argument("--storage-dir", type=Path,
                        help="keep plans and the availability profile here across restarts "
                             "(default: a temporary directory removed on exit)")
    parser.add_argument("--profile-seed", type=Path,
                        help="availability JSON copied to the isolated profile when it does not exist yet")
    parser.add_argument("--now", type=parse_wire_minute,
                        help="frozen YYYY/MM/DD/HH/MM clock for manual-move validation (default: real time)")
    parser.add_argument("--request-log", type=Path,
                        help="append one JSON line per request (method, path, status, body, response)")
    parser.add_argument("--test-control", type=Path,
                        help="JSON file of test-only failure switches: adjust_delay_seconds, "
                             "drop_next_adjust_response, inject_busy_schedules (default: none)")
    args = parser.parse_args()
    if not 1 <= args.port <= 65535:
        parser.error("--port must be between 1 and 65535")

    try:
        with running_server(args.host, args.port, args.case_id, planning=args.planning,
                            storage_dir=args.storage_dir, profile_seed=args.profile_seed, now=args.now,
                            request_log=args.request_log, test_control=args.test_control) as fixture:
            print(f"Fixed live E2E API listening at {fixture.api_base_url}", flush=True)
            print(f"Planning: {args.planning}" + (f" ({args.case_id})" if args.planning == "recorded" else ""),
                  flush=True)
            print(f"Plans are stored only in {fixture.output_dir}", flush=True)
            print(f"Availability profile: {fixture.profile_path}", flush=True)
            if args.now is not None:
                print(f"Manual-move clock frozen at {args.now:%Y/%m/%d/%H/%M}", flush=True)
            if args.request_log is not None:
                print(f"Request log: {args.request_log.resolve()}", flush=True)
            if args.test_control is not None:
                print(f"Test control file: {args.test_control.resolve()}", flush=True)
            print("OpenRouter is disabled; press Ctrl+C to stop.", flush=True)
            threading.Event().wait()
    except KeyboardInterrupt:
        return 0
    except (OSError, ValueError) as error:
        print(f"fixed live E2E server failed: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
