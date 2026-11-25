"""Offline checks of the contract-check adaptation report and its pinned source extraction.

Run from the repository root:

    python -m unittest discover -s frontend/contract_checks -p 'test_*.py'
"""
from __future__ import annotations

import json
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import verify_request  # noqa: E402

REPO = Path(__file__).resolve().parents[2]
CERT_ID = "computer_specialist_level_2"


class AdaptationReportTests(unittest.TestCase):
    def test_full_reviewed_request(self):
        ids = list(range(1, 19))

        self.assertEqual(verify_request.adaptation_report(ids, ids, []), {
            "submitted_questions": 18, "reviewed_questions": 18, "unresolved_questions": 0,
            "assessment_items": 0, "problem_result_rows": 18, "path": "reviewed"})

    def test_mixed_request_counts_questions_not_weighted_rows(self):
        report = verify_request.adaptation_report([4, 13, 501], [4, 13, 13], [501])

        self.assertEqual((report["submitted_questions"], report["reviewed_questions"],
                          report["unresolved_questions"], report["problem_result_rows"], report["path"]),
                         (3, 2, 1, 3, "mixed"))

    def test_all_unresolved_request_uses_the_model_path(self):
        report = verify_request.adaptation_report([101, 102], [], [101, 102])

        self.assertEqual((report["reviewed_questions"], report["unresolved_questions"], report["path"]),
                         (0, 2, "model"))

    def test_counts_that_do_not_reconcile_fail(self):
        cases = {"missing question": ([1, 2], [1], []), "overlap": ([1], [1], [1]),
                 "unknown question": ([1], [1, 2], []), "repeated submission": ([1, 1], [1], [])}
        for label, args in cases.items():
            with self.subTest(label), self.assertRaises(ValueError):
                verify_request.adaptation_report(*args)

    def test_unreadable_child_output_fails(self):
        output, problems = verify_request.report("Traceback (most recent call last): ...")

        self.assertIsNone(output)
        self.assertTrue(problems)

    def test_allowlist_extracts_pure_validation_but_not_private_or_test_paths(self):
        allowed = ["backend/assessment_questionnaire/validation.py", "backend/assessment_questionnaire/__init__.py",
                   "backend/assessment_service.py", "backend/assessment_questionnaire/questions.json",
                   "backend/study_availability.py"]
        excluded = ["backend/assessment_questionnaire/cli.py", "backend/.env", "backend/config/user_availability.json",
                    "backend/license_planner/output/plan.csv", "backend/tests/api_tests/test_plan_generation.py",
                    "backend/scenarios/run_license_planner_test_cases.py", "backend/test_support/question_banks.py",
                    "backend/tests/license_planner_tests/test_study_availability.py",
                    "backend/tests/api_tests/test_availability_profile.py", "backend/study_availability_notes.py"]
        matches = lambda name: any(pattern.fullmatch(name) for pattern in verify_request.ALLOWED_PATHS)

        self.assertTrue(all(matches(name) for name in allowed))
        self.assertFalse(any(matches(name) for name in excluded))


class PinnedSnapshotRunTests(unittest.TestCase):
    """Run the unchanged script inside a throwaway repository holding the working-tree backend sources."""

    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.repo = Path(temp.name)
        names = subprocess.run(["git", "-C", str(REPO), "ls-files", "--cached", "--others", "--exclude-standard",
                                "backend"], check=True, capture_output=True, text=True).stdout.splitlines()
        for name in names:
            if any(pattern.fullmatch(name) for pattern in verify_request.ALLOWED_PATHS) and (REPO / name).exists():
                target = self.repo / name
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy(REPO / name, target)
        script = self.repo / "frontend" / "contract_checks" / "verify_request.py"
        script.parent.mkdir(parents=True)
        shutil.copy(Path(verify_request.__file__), script)
        for command in (["init", "-q"], ["add", "-A"],
                        ["-c", "user.name=contract-check", "-c", "user.email=contract-check@example.invalid",
                         "commit", "-q", "-m", "snapshot"]):
            subprocess.run(["git", "-C", str(self.repo), *command], check=True, capture_output=True)
        self.script = script

    def run_check(self, request):
        path = self.repo / "request.json"
        path.write_text(request if isinstance(request, str) else json.dumps(request, ensure_ascii=False),
                        encoding="utf-8")
        return subprocess.run([sys.executable, str(self.script), "--backend-ref", "HEAD", "--request", str(path)],
                              capture_output=True, text=True, timeout=60)

    def full_request(self):
        bank = json.loads((REPO / "backend/assessment_questionnaire/questions.json").read_text(encoding="utf-8"))
        by_id = {question["id"]: question for question in bank["questions"]}
        results = []
        for index, question_id in enumerate(bank["onboarding_question_ids"]):
            question = by_id[question_id]
            answer = "UNKNOWN" if index % 5 == 0 else question["correct_answer"]
            results.append({"problem_id": question_id, "question": question["prompt"],
                            "choices": {**question["choices"], "UNKNOWN": "모르겠음"},
                            "correct_answer": question["correct_answer"], "user_answer": answer,
                            "is_correct": answer == question["correct_answer"],
                            "possible_score": question["possible_score"]})
        return {"certification_id": CERT_ID, "self_assessment": "기본 기능은 알아요",
                "preparation_start": "2026/11/09/00/00", "exam_date": "2026/11/30/00/00",
                "weekly_availability": {"monday": [{"start": "18:00", "end": "20:00"}]},
                "busy_schedules": [], "assessment_results": {"certification_id": CERT_ID, "results": results}}

    def test_full_selected_request_reports_every_question_reviewed(self):
        completed = self.run_check(self.full_request())

        self.assertEqual(completed.returncode, 0, completed.stdout + completed.stderr)
        result = json.loads(completed.stdout.splitlines()[-2])
        self.assertEqual({key: result["accepted"][key] for key in
                          ("submitted_questions", "reviewed_questions", "unresolved_questions", "path")},
                         {"submitted_questions": 18, "reviewed_questions": 18, "unresolved_questions": 0,
                          "path": "reviewed"})
        self.assertIn("snapshot files:", completed.stdout)

    def test_malformed_and_contradictory_requests_fail(self):
        contradictory = self.full_request()
        contradictory["assessment_results"]["results"][0]["is_correct"] = True
        for label, request in {"malformed JSON": "{", "contradictory correctness": contradictory}.items():
            with self.subTest(label):
                completed = self.run_check(request)
                self.assertEqual(completed.returncode, 1, completed.stdout)
                self.assertEqual(completed.stdout.splitlines()[-1], "FAIL")


if __name__ == "__main__":
    unittest.main()
