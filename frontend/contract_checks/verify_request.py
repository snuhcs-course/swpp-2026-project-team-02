"""Offline check of Android's serialized study-plan request against a pinned backend.

Usage (from the repository root, design D11 / task 4.3):

    python frontend/contract_checks/verify_request.py \
        --backend-ref 2b6e2f5babbe987afd3f107fa40b9df2c7fec98f \
        --request frontend/app/build/contract-check/study-plan-request.json

The helper extracts an allowlist of committed backend source and configuration
from ``--backend-ref`` into a temporary directory (never credential files, saved
availability profiles, generated outputs, or tests). In a separate Python
process it then compares the request's assessment results with
``assessment_service.get_public_questions`` and validates the request with
``api_service.build_plan_request(..., require_assessment=True)``.

No HTTP server, network call, or Gemini agent is used. Passing verifies
public-data-to-request mapping and input validation only; it does not verify the
HTTP handler, a running server, Gemini planning, or the Android UI.
"""
from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
import tempfile
from pathlib import Path

# Committed paths the validator needs. Anything else (".env*", saved profiles,
# outputs, tests, browser files) is never extracted.
ALLOWED_PATHS = (
    re.compile(r"backend/(api_service|assessment_service)\.py"),
    re.compile(r"backend/(license_planner|schedule_adjuster|certification_selector)/[A-Za-z0-9_]+\.py"),
    re.compile(r"backend/certification_selector/certifications\.json"),
    re.compile(r"backend/config/(api_server|certifications|license_planner)\.json"),
    re.compile(r"backend/assessment_questionnaire/(question_banks|questions)\.json"),
)

# Runs inside the snapshot with -I, so only the snapshot's backend is importable.
CHECK_SCRIPT = r'''
import json, sys
backend_dir, request_path = sys.argv[1], sys.argv[2]
sys.path.insert(0, backend_dir)
from assessment_service import get_public_questions
from api_service import build_plan_request

RESULT_KEYS = {"problem_id", "question", "choices", "correct_answer", "user_answer", "is_correct", "possible_score"}
FORBIDDEN_TOP = ("problem_results", "problem_results_csv", "assessment_answers")
problems = []
request = json.loads(open(request_path, encoding="utf-8").read())
for key in FORBIDDEN_TOP:
    if key in request:
        problems.append(f"request contains legacy field {key}")
assessment = request.get("assessment_results") or {}
results = assessment.get("results") or []
certification_id = request.get("certification_id")
public = get_public_questions(certification_id)["questions"]
if [q["id"] for q in public] != [r.get("problem_id") for r in results]:
    problems.append("result IDs/order differ from the public bank: "
                    f"{[r.get('problem_id') for r in results]} != {[q['id'] for q in public]}")
for question, result in zip(public, results):
    pid = question["id"]
    extra = set(result) - RESULT_KEYS
    missing = RESULT_KEYS - set(result)
    if extra or missing:
        problems.append(f"problem {pid}: unexpected keys {sorted(extra)}, missing {sorted(missing)}")
    if result.get("question") != question["prompt"]:
        problems.append(f"problem {pid}: question text differs from prompt")
    if result.get("correct_answer") != question["correct_answer"]:
        problems.append(f"problem {pid}: correct_answer differs")
    if result.get("possible_score") != question["possible_score"]:
        problems.append(f"problem {pid}: possible_score differs")
    choices = dict(result.get("choices") or {})
    unknown = choices.pop("UNKNOWN", None)
    if unknown != "모르겠음":
        problems.append(f"problem {pid}: UNKNOWN choice missing or mislabeled")
    original = {k: v for k, v in question["choices"].items() if k != "UNKNOWN"}
    if list(choices.items()) != list(original.items()):
        problems.append(f"problem {pid}: choices differ from the public bank")
    answer = result.get("user_answer")
    expected_correct = answer != "UNKNOWN" and answer == question["correct_answer"]
    if result.get("is_correct") is not expected_correct:
        problems.append(f"problem {pid}: is_correct does not match the local comparison")
try:
    plan = build_plan_request(request, require_assessment=True)
    accepted = {"assessment_items": len(plan.assessment_items),
                "weekdays": [d for d in range(7) if plan.weekly_availability.for_weekday(d)]}
except Exception as error:
    problems.append(f"build_plan_request rejected the request: {type(error).__name__}: {error}")
    accepted = None
print(json.dumps({"public_questions": len(public), "results": len(results),
                  "unknown_answers": sum(1 for r in results if r.get("user_answer") == "UNKNOWN"),
                  "accepted": accepted, "problems": problems}, ensure_ascii=False))
sys.exit(1 if problems else 0)
'''


def git(repo: Path, *args: str) -> bytes:
    return subprocess.run(["git", "-C", str(repo), *args], check=True, capture_output=True).stdout


def extract_snapshot(repo: Path, ref: str, destination: Path) -> list[str]:
    names = git(repo, "ls-tree", "-r", "--name-only", ref, "--", "backend").decode().splitlines()
    selected = [name for name in names if any(p.fullmatch(name) for p in ALLOWED_PATHS)]
    for name in selected:
        target = destination / name
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(git(repo, "show", f"{ref}:{name}"))
    return selected


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--backend-ref", required=True, help="Git revision of the target backend")
    parser.add_argument("--request", required=True, type=Path, help="Serialized Android request JSON")
    args = parser.parse_args()

    repo = Path(git(Path(__file__).resolve().parent, "rev-parse", "--show-toplevel").decode().strip())
    commit = git(repo, "rev-parse", f"{args.backend_ref}^{{commit}}").decode().strip()
    request_path = args.request.resolve()
    print(f"interpreter: {sys.executable}")
    print(f"backend commit: {commit}")
    print(f"request: {request_path}")

    with tempfile.TemporaryDirectory(prefix="contract-check-") as temp:
        snapshot = Path(temp)
        files = extract_snapshot(repo, commit, snapshot)
        print(f"snapshot files: {len(files)}")
        # Drop credentials from the child environment; the check never constructs an agent.
        env = {k: v for k, v in os.environ.items() if "GEMINI" not in k and "API_KEY" not in k}
        completed = subprocess.run(
            [sys.executable, "-I", "-c", CHECK_SCRIPT, str(snapshot / "backend"), str(request_path)],
            cwd=snapshot, env=env, capture_output=True, text=True,
        )
    if completed.stdout:
        print(completed.stdout.strip())
    if completed.returncode != 0 and completed.stderr:
        print(completed.stderr.strip(), file=sys.stderr)
    print("PASS" if completed.returncode == 0 else "FAIL")
    return 0 if completed.returncode == 0 else 1


if __name__ == "__main__":
    raise SystemExit(main())
