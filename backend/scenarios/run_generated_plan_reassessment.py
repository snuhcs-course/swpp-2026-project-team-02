"""Generate through the real model-backed HTTP API, then compare two reassessments.

No planner, model response, schedule or reassessment implementation is mocked.
Only the clock, isolated storage and credential configuration are injected.
"""
from __future__ import annotations

import argparse
from collections import Counter
from contextlib import ExitStack
from datetime import datetime
from http.server import ThreadingHTTPServer
import json
from pathlib import Path
import sys
from tempfile import TemporaryDirectory
import threading
from unittest.mock import patch
from urllib.error import HTTPError
from urllib.parse import urlencode
from urllib.request import Request, urlopen

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
import api_service
from api_server import DEFAULT_API_CONFIG, make_handler
from assessment_service import get_public_questions
from license_planner.agent import PlanningAgent
from license_planner import settings
from schedule_adjuster import settings as adjuster_settings
from schedule_adjuster.reassessment_agent import ReassessmentAgent
from schedule_adjuster.csv_io import parse_schedule_rows
from test_support.admission import roomy_admission
from test_support.device_tokens import bearer, owner_store

CERT = "computer_specialist_level_2"
FORMAT = "%Y/%m/%d/%H/%M"
CUTOFF = "2026/12/02/18/10"
AGENTS = []
REASSESSMENT_AGENTS = []


class RecordingAgent(PlanningAgent):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, **kwargs)
        AGENTS.append(self)


class RecordingReassessmentAgent(ReassessmentAgent):
    def run(self, context):
        REASSESSMENT_AGENTS.append(self)
        self.captured_context = context
        return super().run(context)


def stamp(value):
    return datetime.strptime(value, FORMAT)


def minutes(row):
    return int((stamp(row["end_date"]) - stamp(row["start_date"])).total_seconds() // 60)


def totals(rows):
    result = Counter()
    for row in rows:
        if row["schedule_id"].startswith("study-"):
            result[row["topic"]] += minutes(row)
    return dict(result)


def answers(questions, weak, topics_by_id=None):
    return [{"problem_id": q["id"], "question": q["prompt"],
             "choices": q["choices"] | {"UNKNOWN": "모르겠음"}, "correct_answer": q["correct_answer"],
             "possible_score": q["possible_score"],
             "user_answer": "UNKNOWN" if (topics_by_id or {}).get(q["id"], q.get("topic")) in weak else q["correct_answer"],
             "is_correct": (topics_by_id or {}).get(q["id"], q.get("topic")) not in weak} for q in questions]


def dump(path, data):
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--credential-backend", type=Path,
                        help="Existing backend configuration directory; credentials are loaded by production settings only")
    parser.add_argument("--fixture-output", type=Path,
                        help="Explicitly export an offline regression capture for Python and Android tests")
    parser.add_argument("--reuse-generation", type=Path,
                        help="Reuse a captured real model generation; reassessment still uses live model requests")
    options = parser.parse_args()
    options.output.parent.mkdir(parents=True, exist_ok=True)
    public = get_public_questions(CERT)["questions"]
    weekly = {day: [{"start": "18:00", "duration_minutes": 120}] for day in
              ("monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday")}
    creation = {"certification_id": CERT, "preparation_start": "2026/11/30/00/00",
                "exam_date": "2026/12/08/00/00", "weekly_availability": weekly, "availability_overrides": {},
                "busy_schedules": [{"schedule_id": "1", "start_date": "2026/12/03/16/00",
                                    "end_date": "2026/12/03/17/00", "topic": "기존 외부 일정"}],
                "assessment_results": {"certification_id": CERT, "results": answers(public, set())}}
    parsed = api_service.build_plan_request(creation, require_assessment=True, shared_availability=False)
    topics_by_id = {r.problem_id: r.topic for r in parsed.problem_results}
    topics = sorted(set(topics_by_id.values()))
    assert len(topics) == 8
    creation["assessment_results"]["results"] = answers(public, set(topics[::2]), topics_by_id)
    with TemporaryDirectory(prefix="generated-reassessment-") as folder, ExitStack() as stack:
        root = Path(folder)
        stack.enter_context(patch.object(api_service, "STUDY_PLAN_OUTPUT_DIR", root))
        stack.enter_context(patch.object(api_service, "current_korea_minute", lambda: stamp(CUTOFF)))
        stack.enter_context(patch.object(api_service, "PlanningAgent", RecordingAgent))
        stack.enter_context(patch.object(api_service, "ReassessmentAgent", RecordingReassessmentAgent))
        if options.credential_backend:
            stack.enter_context(patch.object(settings, "DOTENV_PATH", options.credential_backend.resolve() / ".env"))
            stack.enter_context(patch.object(adjuster_settings, "WORKSPACE_DOTENV_PATH", options.credential_backend.resolve() / ".env"))
        handler = make_handler(DEFAULT_API_CONFIG, roomy_admission())
        handler.log_message = lambda *args: None
        server = ThreadingHTTPServer(("127.0.0.1", 0), handler)
        worker = threading.Thread(target=server.serve_forever, daemon=True)
        worker.start()
        stack.callback(lambda: (server.shutdown(), server.server_close(), worker.join(timeout=3)))

        def call(path, payload=None):
            request = Request(f"http://127.0.0.1:{server.server_port}/api/v1{path}",
                              data=json.dumps(payload).encode() if payload is not None else None,
                              headers={"Content-Type": "application/json", **bearer()})
            try:
                response = urlopen(request, timeout=300)
            except HTTPError as error:
                response = error
            with response:
                body = json.loads(response.read().decode())
                assert response.status in (200, 201), (path, response.status, body)
                return body

        if options.reuse_generation:
            capture = json.loads(options.reuse_generation.read_text(encoding="utf-8"))
            assert capture["creation_request"] == creation and capture["provenance"]["model_mocked"] is False
            generated = capture["generated"].copy()
            request = api_service.build_plan_request(creation, require_assessment=True, shared_availability=False)
            generated["schedule_csv_file"] = api_service._save_study_plan_csv(generated["schedule_csv"], request, owner_store(root))
            generated["schedule_csv_url"] = "/api/v1/study-plans/" + Path(generated["schedule_csv_file"]).name
            provenance = capture["provenance"] | {"generation_reused": True}
            print("Reusing captured real model generation; running live agent reassessments", flush=True)
        else:
            print("Generating one plan through the real model-backed POST /study-plans", flush=True)
            generated = call("/study-plans", creation)
            assert AGENTS and sum(a.successful_response_count for a in AGENTS) > 0
            provenance = {"model_mocked": False, "planner_mocked": False, "reassessment_mocked": False,
                      "models": [a.model for a in AGENTS],
                      "model_requests": sum(a.api_request_count for a in AGENTS),
                      "successful_model_responses": sum(a.successful_response_count for a in AGENTS),
                      "tool_calls": generated["tool_calls"]}
        dump(options.output.with_name(options.output.stem + "-generation.json"),
             {"creation_request": creation, "generated": generated, "provenance": provenance})
        print("Real generation succeeded; validating two independent reassessments", flush=True)
        original = generated["schedules"]
        plan_id = generated["schedule_csv_file"]
        store = owner_store(root)
        snapshot = store.snapshot(plan_id)
        revisable = [r for r in original if r["schedule_id"].startswith("study-") and stamp(r["start_date"]) >= stamp(CUTOFF)]
        fixed = [r for r in original if r not in revisable]
        past = [r for r in fixed if r["schedule_id"].startswith("study-") and stamp(r["end_date"]) < stamp(CUTOFF)]
        assert len(past) >= 2 and len(totals(original)) == 8 and len(revisable) >= 2
        check_ins = {past[0]["schedule_id"]: {"status": "Done", "actualMinutes": minutes(past[0])},
                     past[1]["schedule_id"]: {"status": "Partly", "actualMinutes": max(1, minutes(past[1]) // 2)}}
        questions = call(f"/certifications/{CERT}/reassessment-questions")["questions"]
        assert set(q["topic"] for q in questions) == set(topics)
        next_id = max(int(r["schedule_id"][6:]) for r in original if r["schedule_id"].startswith("study-")) + 1
        report = {"provenance": provenance, "creation_request": creation, "generated": generated,
                  "replan_from": CUTOFF, "check_ins": check_ins, "questions": questions,
                  "original_total_topic_minutes": totals(original), "original_remaining_topic_minutes": totals(revisable),
                  "original_remaining_minutes": sum(minutes(r) for r in revisable), "profiles": []}
        before_metadata = {p.name: p.read_bytes() for p in store.directory.glob("*.json")}
        for label, weak in (("A", set(topics[:4])), ("B", set(topics[4:]))):
            store.update(store.snapshot(plan_id), generated["schedule_csv"])
            baseline_files = {p.name: p.read_bytes() for p in store.directory.iterdir() if p.is_file()}
            payload = {"mode": "reassessment", "commit": False, "schedule_csv_file": plan_id,
                       "schedules": original, "weekly_availability": weekly, "availability_overrides": {},
                       "preparation_start": creation["preparation_start"], "exam_date": creation["exam_date"],
                       "replan_from": CUTOFF, "keep_schedule_ids": [], "first_new_study_id": f"study-{next_id}",
                       "study_reports": {key: {"status": value["status"].lower(), "actual_minutes": value["actualMinutes"]}
                                         for key, value in check_ins.items()},
                       "assessment_results": {"certification_id": CERT, "results": answers(questions, weak)}}
            preview = call("/schedules/adjust", payload)
            assert baseline_files == {p.name: p.read_bytes() for p in store.directory.iterdir() if p.is_file()}
            rows = preview["schedules"]
            for protected in fixed:
                assert protected in rows
            for key in check_ins:
                assert next(r for r in rows if r["schedule_id"] == key) == next(r for r in original if r["schedule_id"] == key)
            changed = [r for r in rows if r not in fixed]
            for source in revisable:
                pieces = [r for r in changed if stamp(source["start_date"]) <= stamp(r["start_date"]) < stamp(source["end_date"])]
                assert pieces[0]["start_date"] == source["start_date"] and pieces[-1]["end_date"] == source["end_date"]
                assert all(a["end_date"] == b["start_date"] for a, b in zip(pieces, pieces[1:]))
                assert all(minutes(r) >= min(15, minutes(source)) for r in pieces)
            allocation = {t: totals(changed).get(t, 0) for t in topics}
            assert sum(allocation.values()) == report["original_remaining_minutes"]
            assert all(a.api_request_count > 0 for a in REASSESSMENT_AGENTS)
            assert REASSESSMENT_AGENTS[-1].captured_context["study_reports"] == payload["study_reports"]
            agents_before_commit = len(REASSESSMENT_AGENTS)
            commit = payload | {"commit": True, "previewed_schedules": rows, "preview_token": preview["preview_token"]}
            saved = call("/schedules/adjust", commit)
            readback = call("/study-plans?" + urlencode({"schedule_csv_file": plan_id}))["plans"][0]
            assert preview["action"] == "reassessment_preview" and saved["action"] == "reassessed"
            assert saved["schedules"] == readback["schedules"] == rows
            assert len(REASSESSMENT_AGENTS) == agents_before_commit
            assert store.snapshot(plan_id).entries == parse_schedule_rows(rows)
            assert before_metadata == {p.name: p.read_bytes() for p in store.directory.glob("*.json")}
            assert snapshot.exam_date == store.snapshot(plan_id).exam_date
            report["profiles"].append({"label": label, "weak_topics": sorted(weak), "topic_minutes": allocation,
                "preview_request": payload, "preview": preview, "commit_request": commit, "saved": saved,
                "readback": readback, "remaining_minutes": sum(allocation.values()),
                "agent_context": REASSESSMENT_AGENTS[-1].captured_context,
                "agent_model_requests": REASSESSMENT_AGENTS[-1].api_request_count,
                "minimum_remaining_session_minutes": min(map(minutes, changed)),
                "new_short_sessions": sum(minutes(r) < 15 for r in changed if r not in revisable),
                "checks": {"preview_read_only": True, "protected_rows_and_reported_ids_preserved": True,
                           "every_original_interval_covered": True, "remaining_minutes_preserved": True,
                           "progress_sent_to_agent": True, "commit_did_not_resample": True, "metadata_preserved": True,
                           "preview_saved_readback_and_disk_equal": True}})
        a, b = [r["topic_minutes"] for r in report["profiles"]]
        assert a != b
        assert sum(a[t] for t in topics[:4]) > sum(b[t] for t in topics[:4])
        assert sum(a[t] for t in topics[4:]) < sum(b[t] for t in topics[4:])
        dump(options.output, report)
        if options.fixture_output:
            fixture = {k: report[k] for k in ("provenance", "creation_request", "generated", "replan_from", "check_ins", "questions")}
            fixture["profiles"] = [{"label": p["label"],
                "answers": {str(a["problem_id"]): a["user_answer"] for a in p["preview_request"]["assessment_results"]["results"]},
                "preview": p["preview"], "saved": p["saved"]} for p in report["profiles"]]
            options.fixture_output.parent.mkdir(parents=True, exist_ok=True)
            dump(options.fixture_output, fixture)
        print(json.dumps({"remaining_before": report["original_remaining_topic_minutes"],
                          "profiles": [{k: p[k] for k in ("label", "topic_minutes", "remaining_minutes", "minimum_remaining_session_minutes", "new_short_sessions", "checks")}
                                       for p in report["profiles"]]}, ensure_ascii=True, indent=2), flush=True)
        print(options.output.resolve())
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
