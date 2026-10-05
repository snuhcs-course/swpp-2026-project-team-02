"""Run offline case-based checks for assessment_questionnaire."""
from __future__ import annotations

import contextlib
import io
import json
import sys
import tempfile
from datetime import datetime
from pathlib import Path
from unittest.mock import patch

from assessment_questionnaire import cli


BACKEND_DIR = Path(__file__).resolve().parent
INPUT_FILE = BACKEND_DIR / "assessment_questionnaire_test_inputs" / "assessment_questionnaire_test_cases.json"
RESULT_DIR = BACKEND_DIR / "assessment_questionnaire_test_results"
QUESTION_FILE = BACKEND_DIR / "assessment_questionnaire" / "questions.json"


def _read_results(path: Path):
    content = path.read_text(encoding="utf-8")
    return content, json.loads(content)


def _run_case(case: dict) -> dict:
    case_id = case["case_id"]
    expected: dict = {}
    actual: dict = {}
    kind = case["kind"]

    if kind == "project_question_bank":
        document = cli.load_questions(QUESTION_FILE)
        actual = {
            "certification_id": document.get("certification_id"),
            "question_count": len(document["questions"]),
            "topics": sorted({question["topic"] for question in document["questions"]}),
        }
        expected = {
            "certification_id": case["expected_certification_id"],
            "question_count": case["expected_question_count"],
            "topics": sorted(case["expected_topics"]),
        }
        passed = actual == expected

    elif kind == "assessment_flow":
        with tempfile.TemporaryDirectory(prefix="assessment-case-") as temp_dir:
            output_path = Path(temp_dir) / "assessment_results.json"
            document = case["question_document"]
            with patch("builtins.input", side_effect=case["answers"]), contextlib.redirect_stdout(io.StringIO()) as captured:
                results = cli.ask_answers(document)
            cli.write_results(document, results, output_path)
            content, parsed = _read_results(output_path)
            parsed_rows = parsed["results"]
            actual = {
                "results": parsed_rows,
                "correct_count": sum(row["is_correct"] for row in parsed_rows),
                "certification_id": parsed["certification_id"],
                "utf8_without_bom": not content.startswith("\ufeff"),
                "invalid_answer_message_seen": (
                    case["expected_invalid_answer_message"] in captured.getvalue()
                    if case.get("expected_invalid_answer_message") else False
                ),
            }
            expected = {
                "results": case["expected_results"],
                "correct_count": case["expected_correct_count"],
                "certification_id": document.get("certification_id"),
                "utf8_without_bom": case.get("expect_utf8_without_bom", True),
                "invalid_answer_message_seen": bool(case.get("expected_invalid_answer_message")),
            }
            passed = actual == expected

    elif kind == "invalid_question_document":
        with tempfile.TemporaryDirectory(prefix="assessment-invalid-") as temp_dir:
            question_path = Path(temp_dir) / "questions.json"
            question_path.write_text(json.dumps(case["question_document"], ensure_ascii=False), encoding="utf-8")
            try:
                cli.load_questions(question_path)
                actual = {"rejected": False, "error": ""}
            except ValueError as error:
                actual = {"rejected": True, "error": str(error)}
        expected = {"rejected": True, "error_contains": case["expected_error"]}
        passed = actual["rejected"] and expected["error_contains"] in actual["error"]

    elif kind == "eof_no_partial_output":
        with tempfile.TemporaryDirectory(prefix="assessment-eof-") as temp_dir:
            question_path = Path(temp_dir) / "questions.json"
            output_path = Path(temp_dir) / "partial.json"
            question_path.write_text(json.dumps(case["question_document"], ensure_ascii=False), encoding="utf-8")
            answers = [*case["answers_before_eof"], EOFError]
            with patch.object(sys, "argv", ["assessment-questionnaire", "--questions", str(question_path), "--output", str(output_path)]), \
                    patch("builtins.input", side_effect=answers), contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
                exit_code = cli.main()
            actual = {"exit_code": exit_code, "output_exists": output_path.exists()}
            expected = {"exit_code": case["expected_exit_code"], "output_exists": False}
            passed = actual == expected

    elif kind == "cli_project_bank":
        with tempfile.TemporaryDirectory(prefix="assessment-cli-") as temp_dir:
            output_path = Path(temp_dir) / "assessment_results.json"
            with patch.object(sys, "argv", ["assessment-questionnaire", "--output", str(output_path)]), \
                    patch("builtins.input", side_effect=case["answers"]), contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
                exit_code = cli.main()
            content, parsed = _read_results(output_path)
            parsed_rows = parsed["results"]
            actual = {
                "exit_code": exit_code,
                "result_count": len(parsed_rows),
                "correct_count": sum(row["is_correct"] for row in parsed_rows),
                "has_question_and_answer": all(row.get("question") and row.get("user_answer") for row in parsed_rows),
                "utf8_without_bom": not content.startswith("\ufeff"),
            }
            expected = {
                "exit_code": case["expected_exit_code"],
                "result_count": case["expected_result_count"],
                "correct_count": case["expected_correct_count"],
                "has_question_and_answer": True,
                "utf8_without_bom": True,
            }
            passed = actual == expected
    else:
        raise ValueError(f"Unknown test case kind: {kind}")

    return {
        "case_id": case_id,
        "description": case["description"],
        "passed": passed,
        "expected": expected,
        "actual": actual,
    }


def main() -> int:
    cases = json.loads(INPUT_FILE.read_text(encoding="utf-8"))
    RESULT_DIR.mkdir(parents=True, exist_ok=True)
    results = []
    for case in cases:
        try:
            result = _run_case(case)
        except Exception as error:  # Store unexpected errors in the case report for diagnosis.
            result = {
                "case_id": case.get("case_id", "unknown"),
                "description": case.get("description", ""),
                "passed": False,
                "error": f"{type(error).__name__}: {error}",
            }
        results.append(result)
        (RESULT_DIR / f"{result['case_id']}_result.json").write_text(
            json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
        )

    summary = {
        "executed_at": datetime.now().astimezone().isoformat(timespec="seconds"),
        "input_file": str(INPUT_FILE),
        "result_directory": str(RESULT_DIR),
        "total": len(results),
        "passed": sum(result["passed"] for result in results),
        "failed": sum(not result["passed"] for result in results),
        "results": results,
    }
    (RESULT_DIR / "assessment_questionnaire_test_summary.json").write_text(
        json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    print(f"Assessment questionnaire: {summary['passed']}/{summary['total']} passed, {summary['failed']} failed")
    print(f"Results: {RESULT_DIR}")
    for result in results:
        print(f"{'PASS' if result['passed'] else 'FAIL'} {result['case_id']}")
    return 0 if summary["failed"] == 0 else 1


if __name__ == "__main__":
    raise SystemExit(main())
