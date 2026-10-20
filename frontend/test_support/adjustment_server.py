#!/usr/bin/env python3
"""Controlled /api/v1 server for checking the live Android build's adjustment error recovery.

Test support only: it is not the backend, calls no AI service, reads no backend settings or
.env file, and keeps every plan in memory. Each start begins from the same fresh state, so
restarting the process is the reset.

Seeded plans (all with recognized generated-plan identities):
  A  returned by the 1st, 3rd, ... POST /study-plans; long quoted Korean topic and external rows
  B  returned by the 2nd, 4th, ... POST /study-plans; the same study IDs as A
  C  stored only; never returned by generation, so the app must never show it

Routes: GET certifications/computer_specialist_level_2/questions (the unit-test fixture),
POST study-plans, POST schedules/adjust, GET study-plans. Unscripted adjustments behave like
the backend's stored-plan update: 404 PLAN_NOT_FOUND for an unknown file, 409 PLAN_CONFLICT
when the sent rows differ from the stored rows, otherwise the target study row moves 7 days
later and the plan is saved. Scenarios script the first calls (see SCENARIOS).

Every request, its status and scripted events are appended to <output-dir>/requests.jsonl.

Usage (from frontend/):
  python3 -I test_support/adjustment_server.py --scenario conflict --port 8765 --output-dir "$(mktemp -d)"
"""
import argparse
import csv
import io
import json
import sys
import threading
import time
from datetime import datetime, timedelta
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

API = "/api/v1"
QUESTIONS_PATH = f"{API}/certifications/computer_specialist_level_2/questions"
QUESTIONS_FILE = Path(__file__).resolve().parents[1] / "app/src/test/resources/questions-response.json"
WIRE = "%Y/%m/%d/%H/%M"
HOLD_SECONDS = 125  # longer than the app's 120 s call deadline

FILE_A = "license_planner/output/study_plan_20261017T120000000000+0900_aaaaaaaa.csv"
FILE_B = "license_planner/output/study_plan_20261017T120500000000+0900_bbbbbbbb.csv"
FILE_C = "license_planner/output/study_plan_20261016T090000000000+0900_cccccccc.csv"
FILE_A_SIMILAR = "license_planner/output/study_plan_20261017T120000000000+0900_aaaaaaa0.csv"

SCENARIOS = {
    "normal": "No scripted failures; adjustments update the stored plan.",
    "plan-not-found": "A is pruned just before its first adjustment: 404 PLAN_NOT_FOUND; later lists omit A.",
    "route-not-found": "Old server: adjustment and the plan list answer 404 NOT_FOUND (route).",
    "conflict": "First adjustment: another client changes stored A, then 409 PLAN_CONFLICT. "
                "First list read: 500 STORAGE_ERROR. Later reads list the changed A.",
    "unknown-conflict": "First adjustment: 409 with an unknown code; second: 409 with a non-JSON body. Store unchanged.",
    "timeout-after-save": "First adjustment saves A, then holds the response past the client deadline.",
    "timeout-without-save": "First adjustment holds without saving, then closes without a response.",
    "invalid-readback": "First adjustment: 409 PLAN_CONFLICT (store unchanged). List reads 1-4: malformed JSON, "
                        "no plans property, A with duplicate IDs, A listed twice; then valid.",
    "readback-missing": "First adjustment: 409 PLAN_CONFLICT and A disappears; lists hold B, C and a similarly named plan.",
}


def rows(*items):
    return [dict(zip(("schedule_id", "start_date", "end_date", "topic"), item)) for item in items]


def seed():
    """Fresh stored plans, newest first."""
    a = rows(
        ("study-1", "2026/10/19/18/00", "2026/10/19/19/00",
         "스프레드시트 기본: \"표시 형식\", 조건부 서식, 셀 스타일을 한 번에 익히는 아주 긴 주제 이름"),
        ("external-1", "2026/10/20/18/00", "2026/10/20/19/00", ""),
        ("study-2", "2026/10/21/18/00", "2026/10/21/19/00", "함수와 데이터 관리"),
        ("study-3", "2026/10/26/18/00", "2026/10/26/19/00", "차트와 인쇄"),
        ("external-2", "2026/10/27/19/00", "2026/10/27/20/00", "동아리 모임"),
    )
    b = rows(
        ("study-1", "2026/10/20/09/00", "2026/10/20/10/00", "B: 셀 참조"),
        ("study-2", "2026/10/22/09/00", "2026/10/22/10/00", "B: 함수"),
        ("study-3", "2026/10/27/09/00", "2026/10/27/10/00", "B: 차트"),
    )
    c = rows(("study-1", "2026/10/19/07/00", "2026/10/19/08/00", "C: 가져오면 안 되는 계획"))
    return {FILE_B: b, FILE_A: a, FILE_C: c}


def csv_text(schedule):
    out = io.StringIO()
    writer = csv.writer(out, lineterminator="\n")
    writer.writerow(["schedule_id", "start_date", "end_date", "topic"])
    for row in schedule:
        writer.writerow([row["schedule_id"], row["start_date"], row["end_date"], row["topic"]])
    return out.getvalue()


def shift(value, days):
    return (datetime.strptime(value, WIRE) + timedelta(days=days)).strftime(WIRE)


def row_key(schedule):
    return sorted((r.get("schedule_id"), r.get("start_date"), r.get("end_date"), r.get("topic", "")) for r in schedule)


def url_for(file):
    return f"{API}/study-plans/{file.rsplit('/', 1)[-1]}"


class State:
    def __init__(self, scenario, output_dir):
        self.scenario = scenario
        self.lock = threading.Lock()
        self.plans = seed()  # insertion order = newest first
        self.counts = {"generate": 0, "adjust": 0, "list": 0}
        self.sequence = 0
        self.log_path = output_dir / "requests.jsonl"

    def count(self, name):
        with self.lock:
            self.counts[name] += 1
            return self.counts[name]

    def log(self, entry):
        with self.lock:
            self.sequence += 1
            entry = {"seq": self.sequence, "at": time.strftime("%H:%M:%S"), **entry}
            with self.log_path.open("a", encoding="utf-8") as log:
                log.write(json.dumps(entry, ensure_ascii=False) + "\n")
        sys.stderr.write(json.dumps(entry, ensure_ascii=False)[:300] + "\n")

    def save(self, file, schedule):
        with self.lock:
            self.plans.pop(file, None)
            self.plans = {file: schedule, **self.plans}

    def snapshot(self):
        with self.lock:
            return [(file, [dict(r) for r in schedule]) for file, schedule in self.plans.items()]


def plan_list(entries):
    return {"plans": [{"schedule_csv_file": file, "schedule_csv_url": url_for(file), "schedules": schedule}
                      for file, schedule in entries]}


class Handler(BaseHTTPRequestHandler):
    state: State = None

    # --- responses ---

    def _send_raw(self, status, data, content_type="application/json; charset=utf-8"):
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)
        self.state.log({"type": "response", "method": self.command, "path": self.path, "status": status})

    def _send(self, status, body):
        self._send_raw(status, json.dumps(body, ensure_ascii=False).encode("utf-8"))

    def _error(self, status, code, message):
        self._send(status, {"error": {"code": code, "message": message}})

    def _event(self, name, **details):
        self.state.log({"type": "event", "event": name, **details})

    def _hold(self):
        self._event("holding", seconds=HOLD_SECONDS)
        time.sleep(HOLD_SECONDS)

    # --- routes ---

    def do_GET(self):
        self.state.log({"type": "request", "method": "GET", "path": self.path})
        if self.path == QUESTIONS_PATH:
            self._send_raw(200, QUESTIONS_FILE.read_bytes())
        elif self.path == f"{API}/study-plans":
            self._list()
        else:
            self._error(404, "NOT_FOUND", "API route not found")

    def do_POST(self):
        length = int(self.headers.get("Content-Length") or 0)
        raw = self.rfile.read(length)
        try:
            payload = json.loads(raw or b"{}")
        except json.JSONDecodeError:
            payload = None
        self.state.log({"type": "request", "method": "POST", "path": self.path, "body": payload})
        if not isinstance(payload, dict):
            self._error(422, "INVALID_INPUT", "Request body must be a JSON object")
        elif self.path == f"{API}/study-plans":
            self._generate()
        elif self.path == f"{API}/schedules/adjust":
            self._adjust(payload)
        else:
            self._error(404, "NOT_FOUND", "API route not found")

    def _generate(self):
        n = self.state.count("generate")
        file = FILE_A if n % 2 == 1 else FILE_B
        schedule = dict(self.state.snapshot()).get(file)
        if schedule is None:
            self._error(500, "STORAGE_ERROR", "seeded plan is no longer stored")
            return
        self._send(200, {
            "schedules": schedule, "schedule_csv": csv_text(schedule),
            "agent_summary": "통제 테스트 서버가 만든 고정 계획이에요.", "tool_calls": [{"name": "hidden"}],
            "schedule_csv_file": file, "schedule_csv_url": url_for(file),
        })

    def _list(self):
        scenario = self.state.scenario
        if scenario == "route-not-found":
            self._error(404, "NOT_FOUND", "API route not found")
            return
        n = self.state.count("list")
        entries = self.state.snapshot()
        if scenario == "conflict" and n == 1:
            self._error(500, "STORAGE_ERROR", "Stored plan could not be read")
        elif scenario == "invalid-readback" and n <= 4:
            a = dict(entries)[FILE_A]
            bodies = {
                1: b'{"plans": [{"schedule_csv_file": "',
                2: json.dumps({"items": plan_list(entries)["plans"]}).encode("utf-8"),
                3: json.dumps(plan_list([(FILE_A, a + [dict(a[0])])])).encode("utf-8"),
                4: json.dumps(plan_list([(FILE_A, a), (FILE_A, a)])).encode("utf-8"),
            }
            self._event("invalid-readback", kind={1: "malformed", 2: "no-plans", 3: "duplicate-ids", 4: "duplicate-identity"}[n])
            self._send_raw(200, bodies[n])
        elif scenario == "readback-missing":
            similar = rows(("study-1", "2026/10/19/18/00", "2026/10/19/19/00", "비슷한 이름의 다른 계획"))
            self._send(200, plan_list(entries + [(FILE_A_SIMILAR, similar)]))
        else:
            self._send(200, plan_list(entries))

    def _adjust(self, payload):
        scenario = self.state.scenario
        if scenario == "route-not-found":
            self._error(404, "NOT_FOUND", "API route not found")
            return
        n = self.state.count("adjust")
        file = payload.get("schedule_csv_file")
        if n == 1 and scenario == "plan-not-found":
            with self.state.lock:
                self.state.plans.pop(file, None)
            self._event("pruned", file=file)
        elif n == 1 and scenario == "conflict":
            stored = dict(self.state.snapshot()).get(file)
            if stored is not None:
                changed = [dict(r, start_date=shift(r["start_date"], 1), end_date=shift(r["end_date"], 1))
                           if r["schedule_id"] == "study-2" else r for r in stored]
                self.state.save(file, changed)
                self._event("changed-elsewhere", file=file, schedule_id="study-2")
        elif n == 1 and scenario == "invalid-readback":
            self._error(409, "PLAN_CONFLICT", f"{file} changed after it was read")
            return
        elif n == 1 and scenario == "readback-missing":
            with self.state.lock:
                self.state.plans.pop(file, None)
            self._event("removed", file=file)
            self._error(409, "PLAN_CONFLICT", f"{file} changed after it was read")
            return
        elif n == 1 and scenario == "unknown-conflict":
            self._error(409, "EDIT_CONFLICT", "unrecognized conflict")
            return
        elif n == 2 and scenario == "unknown-conflict":
            self._send_raw(409, b"<html><body>409 Conflict</body></html>", "text/html; charset=utf-8")
            return
        elif n == 1 and scenario == "timeout-after-save":
            status, body = self._apply(payload)
            self._event("saved" if status == 200 else "not-saved", file=file, status=status)
            self._hold()
            self._send(status, body)
            return
        elif n == 1 and scenario == "timeout-without-save":
            self._event("not-saved", file=file)
            self._hold()
            self._event("closed-without-response", file=file)
            self.close_connection = True
            return
        status, body = self._apply(payload)
        self._send(status, body)

    def _apply(self, payload):
        """The backend-like stored-plan update; returns (status, body) and saves on success."""
        file = payload.get("schedule_csv_file")
        target = payload.get("schedule_id")
        sent = payload.get("schedules")
        stored = dict(self.state.snapshot()).get(file)
        if stored is None:
            return 404, {"error": {"code": "PLAN_NOT_FOUND", "message": f"{file} not found"}}
        if not isinstance(sent, list) or not payload.get("weekly_availability"):
            return 422, {"error": {"code": "INVALID_INPUT", "message": "schedules and weekly_availability are required"}}
        if row_key(sent) != row_key(stored):
            return 409, {"error": {"code": "PLAN_CONFLICT", "message": f"{file} changed after it was read"}}
        if not str(target).startswith("study-") or all(r["schedule_id"] != target for r in stored):
            return 422, {"error": {"code": "INVALID_INPUT", "message": "schedule_id must name a study row"}}
        moved = [dict(r, start_date=shift(r["start_date"], 7), end_date=shift(r["end_date"], 7))
                 if r["schedule_id"] == target else dict(r) for r in stored]
        self.state.save(file, moved)
        self._event("saved-adjustment", file=file, schedule_id=target)
        return 200, {
            "schedule_csv_file": file, "schedule_csv_url": url_for(file), "schedules": moved,
            "schedule_csv": csv_text(moved), "action": "rescheduled_study", "target_schedule_id": target,
            "agent_summary": f"통제 테스트 서버가 {target}을 7일 뒤로 옮겼어요.", "tool_calls": [],
        }

    def handle_one_request(self):
        try:
            super().handle_one_request()
        except (BrokenPipeError, ConnectionResetError) as error:
            self.close_connection = True
            self._event("client-disconnected", path=getattr(self, "path", None), error=type(error).__name__)

    def log_message(self, fmt, *args):
        pass  # requests.jsonl and stderr already record every exchange


def main():
    parser = argparse.ArgumentParser(description="Controlled server for adjustment error-recovery checks.")
    parser.add_argument("--scenario", required=True, choices=sorted(SCENARIOS))
    parser.add_argument("--port", required=True, type=int)
    parser.add_argument("--output-dir", required=True, type=Path, help="disposable directory for requests.jsonl")
    args = parser.parse_args()
    args.output_dir.mkdir(parents=True, exist_ok=True)
    Handler.state = State(args.scenario, args.output_dir)
    server = ThreadingHTTPServer(("127.0.0.1", args.port), Handler)
    server.daemon_threads = True
    Handler.state.log({"type": "start", "scenario": args.scenario, "description": SCENARIOS[args.scenario],
                       "port": args.port, "hold_seconds": HOLD_SECONDS})
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
