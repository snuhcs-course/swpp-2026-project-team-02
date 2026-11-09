"""Submitted-result scoring consistency, malformed content and standalone questionnaire validation."""
import copy
import json
import shutil
import subprocess
import sys
import tempfile
import threading
import unittest
from http.server import ThreadingHTTPServer
from pathlib import Path
from unittest.mock import patch
from urllib.error import HTTPError
from urllib.request import Request, urlopen

import api_service
from api_server import DEFAULT_API_CONFIG, make_handler
from api_service import build_plan_request
from assessment_service import get_public_questions
from license_planner.agent import PlanningAgent

BACKEND_DIR = Path(__file__).resolve().parents[2]
CERT_ID = "computer_specialist_level_2"


def custom_row(**fields):
    row = {"problem_id": 101, "question": "사용자 정의 문항", "choices": {"A": "가", "B": "나", "UNKNOWN": "모르겠음"},
           "correct_answer": "A", "user_answer": "A", "is_correct": True, "possible_score": 1}
    row.update(fields)
    return row


def reviewed_row(index=0, **fields):
    question = get_public_questions(CERT_ID)["questions"][index]
    row = {"problem_id": question["id"], "question": question["prompt"],
           "choices": {**question["choices"], "UNKNOWN": "모르겠음"}, "correct_answer": question["correct_answer"],
           "user_answer": question["correct_answer"], "is_correct": True, "possible_score": question["possible_score"]}
    row.update(fields)
    return row


def payload(*rows):
    return {"certification_id": CERT_ID, "preparation_start": "2026/11/09/18/00", "exam_date": "2026/11/30/18/00",
            "weekly_availability": {"monday": [{"start": "18:00", "end": "20:00"}]},
            "assessment_results": {"certification_id": CERT_ID, "results": list(rows)}}


class SubmittedResultValidationTests(unittest.TestCase):
    def assert_rejected(self, rows, message):
        with self.assertRaisesRegex(ValueError, message):
            build_plan_request(payload(*rows), require_assessment=True)

    def test_correctness_must_match_the_answer_and_key(self):
        def wrong_answer(row):
            return next(key for key in row["choices"] if key not in (row["correct_answer"], "UNKNOWN"))

        changes = {"wrong answer marked true": lambda row: {"user_answer": wrong_answer(row), "is_correct": True},
                   "UNKNOWN marked true": lambda row: {"user_answer": "UNKNOWN", "is_correct": True},
                   "correct answer marked false": lambda row: {"is_correct": False}}
        for label, change in changes.items():
            for source, make in {"reviewed": reviewed_row, "custom": custom_row}.items():
                with self.subTest(label, source=source):
                    row = make()
                    self.assert_rejected([{**row, **change(row)}], "is_correct does not match")

    def test_consistent_answers_are_accepted_for_reviewed_and_custom_questions(self):
        rows = [reviewed_row(0), reviewed_row(1, user_answer="UNKNOWN", is_correct=False),
                custom_row(), custom_row(problem_id=102, user_answer="B", is_correct=False),
                custom_row(problem_id=103, user_answer="UNKNOWN", is_correct=False)]

        request = build_plan_request(payload(*rows), require_assessment=True)

        self.assertEqual([row.problem_id for row in request.problem_results], [1, 2])
        self.assertEqual([item.problem_id for item in request.assessment_items], [101, 102, 103])
        self.assertEqual([row.earned_score for row in request.problem_results], [1, 0])

    def test_invalid_question_values_are_input_errors(self):
        missing_prompt = custom_row()
        del missing_prompt["question"]
        missing_score = custom_row()
        del missing_score["possible_score"]
        cases = {
            "list correct key": (custom_row(correct_answer=["A"]), "correct_answer must be one of"),
            "object correct key": (custom_row(correct_answer={"A": 1}), "correct_answer must be one of"),
            "missing prompt": (missing_prompt, "non-empty prompt"),
            "blank prompt": (custom_row(question=" "), "non-empty prompt"),
            "blank choice key": (custom_row(choices={"": "가", "A": "나", "B": "다"}), "non-empty keys and text"),
            "blank choice text": (custom_row(choices={"A": "", "B": "나"}), "non-empty keys and text"),
            "one normal choice": (custom_row(choices={"A": "가", "UNKNOWN": "모르겠음"}), "at least two normal choices"),
            "UNKNOWN correct key": (custom_row(correct_answer="UNKNOWN", user_answer="UNKNOWN", is_correct=False),
                                    "correct_answer must be one of"),
            "mislabeled UNKNOWN": (custom_row(choices={"A": "가", "B": "나", "UNKNOWN": "모름"}), "labeled 모르겠음"),
            "answer outside choices": (custom_row(user_answer="C", is_correct=False), "user_answer must be a choice key"),
            "list answer": (custom_row(user_answer=["A"]), "user_answer must be a choice key"),
            "missing possible score": (missing_score, "possible_score is required"),
            "boolean score": (custom_row(possible_score=True), "possible_score must be a positive number"),
            "string score": (custom_row(possible_score="1"), "possible_score must be a positive number"),
            "zero score": (custom_row(possible_score=0), "positive finite"),
            "negative score": (custom_row(possible_score=-1), "positive finite"),
            "NaN score": (custom_row(possible_score=float("nan")), "positive finite"),
            "infinite score": (custom_row(possible_score=float("inf")), "positive finite"),
            "unrepresentable score": (custom_row(possible_score=10 ** 400), "too large"),
            "choices container": (custom_row(choices=["A", "B"]), "choices must be an object"),
        }
        for label, (row, message) in cases.items():
            with self.subTest(label):
                self.assert_rejected([row], message)

    def test_repeated_problem_ids_are_rejected_for_reviewed_and_custom_questions(self):
        cases = {"reviewed twice": [reviewed_row(0), reviewed_row(0)],
                 "custom twice": [custom_row(), custom_row(user_answer="B", is_correct=False)],
                 "custom reusing a reviewed ID": [reviewed_row(0), custom_row(problem_id=1)]}
        for label, rows in cases.items():
            with self.subTest(label):
                self.assert_rejected(rows, "Duplicate assessment result for problem_id")

    def test_individually_finite_scores_whose_total_overflows_are_rejected(self):
        self.assert_rejected([custom_row(possible_score=1e308), custom_row(problem_id=102, possible_score=1e308)],
                             "assessment_results total possible score is not finite")

    def test_invalid_results_return_422_over_http_before_the_model_or_storage(self):
        requests = []
        with tempfile.TemporaryDirectory() as store, \
                patch.object(api_service, "STUDY_PLAN_OUTPUT_DIR", Path(store)), \
                patch.object(PlanningAgent, "_ask_model", side_effect=lambda *a: requests.append(a)):
            server = ThreadingHTTPServer(("127.0.0.1", 0), make_handler(DEFAULT_API_CONFIG))
            threading.Thread(target=server.serve_forever, daemon=True).start()
            try:
                for label, row in {"contradictory": reviewed_row(user_answer="UNKNOWN"),
                                   "huge score": custom_row(possible_score=10 ** 400)}.items():
                    body = json.dumps(payload(row)).encode()
                    request = Request(f"http://127.0.0.1:{server.server_port}/api/v1/study-plans", data=body,
                                      headers={"Content-Type": "application/json"}, method="POST")
                    with self.subTest(label), self.assertRaises(HTTPError) as raised:
                        urlopen(request, timeout=10)
                    with raised.exception as error:
                        self.assertEqual(error.code, 422)
                        self.assertEqual(json.loads(error.read())["error"]["code"], "INVALID_INPUT")
            finally:
                server.shutdown()
                server.server_close()
            self.assertEqual((requests, list(Path(store).iterdir())), ([], []))


class StandaloneQuestionnaireTests(unittest.TestCase):
    """Both CLI launch forms run from an isolated copy holding only the questionnaire package."""

    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name) / "backend"
        package = self.root / "assessment_questionnaire"
        package.mkdir(parents=True)
        for name in ("__init__.py", "cli.py", "validation.py"):
            shutil.copy(BACKEND_DIR / "assessment_questionnaire" / name, package / name)

    def run_cli(self, form, document, answers=""):
        source = self.root / "custom.json"
        source.write_bytes(document if isinstance(document, bytes)
                           else json.dumps(document, ensure_ascii=False).encode("utf-8"))
        before = source.read_bytes()
        output = self.root / "result.json"
        launch = (["-m", "assessment_questionnaire.cli"] if form == "module"
                  else [str(Path("assessment_questionnaire") / "cli.py")])
        completed = subprocess.run([sys.executable, "-E", "-s", *launch, "--questions", str(source),
                                    "--output", str(output)], cwd=self.root, input=answers, capture_output=True,
                                   text=True, timeout=30)
        self.assertEqual(source.read_bytes(), before)
        return completed, output

    def test_custom_document_without_application_configuration_is_asked_and_saved(self):
        document = {"certification_id": "custom", "certification_name": "사용자 정의",
                    "questions": [{"id": 7, "prompt": "문항", "choices": {"A": "가", "B": "나", "UNKNOWN": "모르겠음"},
                                   "correct_answer": "B", "possible_score": 2}]}
        for form in ("module", "script"):
            with self.subTest(form):
                completed, output = self.run_cli(form, document, "2\n")
                self.assertEqual(completed.returncode, 0, completed.stderr)
                result = json.loads(output.read_text(encoding="utf-8"))
                self.assertEqual(result["results"], [{
                    "problem_id": 7, "question": "문항", "choices": document["questions"][0]["choices"],
                    "correct_answer": "B", "user_answer": "B", "is_correct": True, "possible_score": 2.0}])

    def test_selected_collection_asks_only_selected_reviewed_questions(self):
        bank = json.loads((BACKEND_DIR / "assessment_questionnaire" / "questions.json").read_text(encoding="utf-8"))
        bank["questions"].append({"id": 90, "prompt": "초안", "choices": {"A": "후보"}, "review_status": "draft",
                                  "provenance": {"kind": "authored", "reference": "test-only draft"}})
        bank["onboarding_question_ids"] = [3, 1]
        for form in ("module", "script"):
            with self.subTest(form):
                completed, output = self.run_cli(form, bank, "1\n1\n")
                self.assertEqual(completed.returncode, 0, completed.stderr)
                rows = json.loads(output.read_text(encoding="utf-8"))["results"]
                self.assertEqual([row["problem_id"] for row in rows], [3, 1])

    def test_malformed_documents_exit_2_before_prompting_or_writing(self):
        def question(**fields):
            row = {"id": 1, "prompt": "문항", "choices": {"A": "가", "B": "나"}, "correct_answer": "A",
                   "possible_score": 1}
            row.update(fields)
            return {"certification_id": "custom", "questions": [row]}

        missing_score = question()
        del missing_score["questions"][0]["possible_score"]
        cases = {"invalid UTF-8": b'{"questions": "\xff"}', "invalid JSON": b"{",
                 "null container": {"questions": None}, "integer container": {"questions": 3},
                 "list key": question(correct_answer=["A"]), "missing score": missing_score,
                 "huge score": question(possible_score=10 ** 400), "NaN score": b'{"questions": [{"id": 1, '
                 b'"prompt": "p", "choices": {"A": "a", "B": "b"}, "correct_answer": "A", "possible_score": NaN}]}',
                 "UNKNOWN key": question(choices={"A": "가", "B": "나", "UNKNOWN": "모르겠음"}, correct_answer="UNKNOWN"),
                 "empty selection": {**question(), "onboarding_question_ids": []}}
        for label, document in cases.items():
            for form in ("module", "script"):
                with self.subTest(label, form=form):
                    completed, output = self.run_cli(form, document, "1\n")
                    self.assertEqual(completed.returncode, 2, completed.stderr)
                    self.assertEqual(completed.stdout, "")
                    self.assertTrue(completed.stderr.startswith("오류: "), completed.stderr)
                    self.assertNotIn("Traceback", completed.stderr)
                    self.assertFalse(output.exists())


if __name__ == "__main__":
    unittest.main()
