"""오프라인 자격증 카탈로그/CLI 테스트 케이스 러너.

외부 API나 Gemini를 호출하지 않습니다. 케이스별 판정 JSON과 실행 요약은
certification_selector_test_results/ 아래에 기록합니다.
"""
from __future__ import annotations

import builtins
import contextlib
import io
import json
import sys
import tempfile
from pathlib import Path
from typing import Any
from unittest.mock import patch

from certification_selector import cli
from certification_selector.catalog import load_certifications


BACKEND_ROOT = Path(__file__).resolve().parent
INPUT_DIR = BACKEND_ROOT / "certification_selector_test_inputs"
RESULT_DIR = BACKEND_ROOT / "certification_selector_test_results"
CASE_FILE = INPUT_DIR / "certification_selector_test_cases.json"
SUMMARY_FILE = RESULT_DIR / "certification_selector_test_summary.json"


def _run_catalog_case(case: dict[str, Any]) -> dict[str, Any]:
    expected_error = case.get("expected_error")
    if case["kind"] == "project_catalog":
        catalog_path = BACKEND_ROOT / "certification_selector" / "certifications.json"
        try:
            certifications = load_certifications(catalog_path)
        except ValueError as error:
            return {"actual_error": str(error), "passed": False}
    else:
        with tempfile.TemporaryDirectory(prefix="cert-selector-") as temp_dir:
            catalog_path = Path(temp_dir) / "catalog.json"
            if case["kind"] == "raw_catalog":
                catalog_path.write_text(case["catalog_json_raw"], encoding="utf-8")
            else:
                catalog_path.write_text(
                    json.dumps(case["catalog_document"], ensure_ascii=False),
                    encoding="utf-8",
                )
            try:
                certifications = load_certifications(catalog_path)
            except ValueError as error:
                actual_error = str(error)
                return {
                    "expected_error": expected_error,
                    "actual_error": actual_error,
                    "passed": bool(expected_error and expected_error in actual_error),
                }

    actual_ids = [cert.id for cert in certifications]
    expected_ids = case.get("expected_ids", [])
    return {
        "expected_ids": expected_ids,
        "actual_ids": actual_ids,
        "passed": actual_ids == expected_ids and expected_error is None,
    }


def _run_cli_case(case: dict[str, Any]) -> dict[str, Any]:
    stdout = io.StringIO()
    stderr = io.StringIO()
    input_values = [case["choice"]] if "choice" in case else []
    argv = ["certification-selector"]
    if case.get("list_json"):
        argv.append("--list-json")
    with contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr), patch.object(
        sys, "argv", argv
    ), patch.object(builtins, "input", side_effect=input_values):
        actual_exit_code = cli.main()

    output = stdout.getvalue()
    error_output = stderr.getvalue().strip()
    expected_exit_code = case["expected_exit_code"]
    actual_selected_id = None
    selected_record: dict[str, Any] | None = None
    selected: dict[str, Any] | list[dict[str, Any]] | None = None
    if actual_exit_code == 0:
        try:
            marker = "[" if case.get("list_json") else "{"
            selected = json.loads(output[output.index(marker):])
            selected_record = selected[0] if isinstance(selected, list) and selected else selected
            if isinstance(selected_record, dict):
                actual_selected_id = selected_record.get("id")
        except (ValueError, json.JSONDecodeError):
            pass
    expected_selected_id = case.get("expected_selected_id")
    expected_error = case.get("expected_error")
    passed = actual_exit_code == expected_exit_code
    if expected_selected_id is not None:
        passed = passed and actual_selected_id == expected_selected_id
    if expected_error is not None:
        passed = passed and expected_error in error_output
    expected_schedule_url = case.get("expected_schedule_url")
    if expected_schedule_url is not None:
        passed = passed and isinstance(selected_record, dict) and selected_record.get("schedule_url") == expected_schedule_url
    if case.get("expect_no_region_prompt"):
        passed = passed and "조회할 지역" not in output
    return {
        "expected_exit_code": expected_exit_code,
        "actual_exit_code": actual_exit_code,
        "expected_selected_id": expected_selected_id,
        "actual_selected_id": actual_selected_id,
        "expected_error": expected_error,
        "actual_error": error_output or None,
        "expected_schedule_url": expected_schedule_url,
        "actual_schedule_url": selected_record.get("schedule_url") if selected_record else None,
        "passed": passed,
    }


def run_case(case: dict[str, Any]) -> dict[str, Any]:
    """Run one input case and return its expected/actual result record."""
    try:
        if case["kind"] == "cli_choice":
            outcome = _run_cli_case(case)
        elif case["kind"] == "cli_list":
            outcome = _run_cli_case(case)
        else:
            outcome = _run_catalog_case(case)
    except Exception as error:  # Keep the remaining cases running and report unexpected errors.
        outcome = {"passed": False, "unexpected_error": f"{type(error).__name__}: {error}"}
    return {
        "case_id": case["case_id"],
        "description": case["description"],
        **outcome,
    }


def main() -> int:
    RESULT_DIR.mkdir(parents=True, exist_ok=True)
    cases = json.loads(CASE_FILE.read_text(encoding="utf-8"))
    results = [run_case(case) for case in cases]
    for result in results:
        result_path = RESULT_DIR / f"{result['case_id']}_result.json"
        result_path.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    summary = {
        "case_count": len(results),
        "passed_count": sum(bool(result["passed"]) for result in results),
        "failed_count": sum(not result["passed"] for result in results),
        "results": results,
    }
    SUMMARY_FILE.write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"Cases: {summary['case_count']}; passed: {summary['passed_count']}; failed: {summary['failed_count']}")
    print(f"Results: {RESULT_DIR}")
    return 0 if summary["failed_count"] == 0 else 1


if __name__ == "__main__":
    raise SystemExit(main())
