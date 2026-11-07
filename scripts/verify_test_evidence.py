#!/usr/bin/env python3
"""Validate generated Iteration 3 CI evidence without running tests again.

The script intentionally consumes only reports already produced by the CI jobs.
It never starts scenario runners, an emulator, a server, or an OpenRouter request.
"""

from __future__ import annotations

import argparse
import json
import sys
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from pathlib import Path


@dataclass
class EvidenceError:
    path: Path
    message: str

    def as_dict(self) -> dict[str, str]:
        return {"path": str(self.path), "message": self.message}


def parse_xml(path: Path, errors: list[EvidenceError]) -> ET.Element | None:
    if not path.is_file() or path.stat().st_size == 0:
        errors.append(EvidenceError(path, "missing or empty report"))
        return None
    try:
        return ET.parse(path).getroot()
    except ET.ParseError as error:
        errors.append(EvidenceError(path, f"invalid XML: {error}"))
        return None


def require_file(path: Path, errors: list[EvidenceError]) -> bool:
    if path.is_file() and path.stat().st_size > 0:
        return True
    errors.append(EvidenceError(path, "missing or empty report"))
    return False


def integer(attribute: str | None, path: Path, label: str, errors: list[EvidenceError]) -> int:
    try:
        return int(attribute or "0")
    except ValueError:
        errors.append(EvidenceError(path, f"invalid {label} count: {attribute!r}"))
        return 0


def validate_backend(root: Path, errors: list[EvidenceError]) -> dict[str, object] | None:
    report_dir = root / "backend" / "coverage-results"
    xml_path = report_dir / "coverage.xml"
    require_file(report_dir / "html" / "index.html", errors)
    report = parse_xml(xml_path, errors)
    if report is None:
        return None

    lines = integer(report.get("lines-valid"), xml_path, "lines-valid", errors)
    classes = [node.get("filename", "") for node in report.iter("class")]
    if lines <= 0:
        errors.append(EvidenceError(xml_path, "coverage has no production-source line denominator"))
    if not any(Path(filename).name == "api_server.py" for filename in classes):
        errors.append(EvidenceError(xml_path, "coverage does not include api_server.py"))
    return {
        "classes": len(classes),
        "lines_valid": lines,
        "line_rate": report.get("line-rate"),
        "branch_rate": report.get("branch-rate"),
    }


def validate_junit(report_dir: Path, errors: list[EvidenceError]) -> dict[str, int]:
    reports = sorted(report_dir.glob("TEST-*.xml"))
    if not reports:
        errors.append(EvidenceError(report_dir, "no JUnit XML reports found"))
        return {"suites": 0, "tests": 0, "failures": 0, "errors": 0, "skipped": 0}

    totals = {"suites": 0, "tests": 0, "failures": 0, "errors": 0, "skipped": 0}
    for path in reports:
        suite = parse_xml(path, errors)
        if suite is None:
            continue
        if suite.tag != "testsuite":
            errors.append(EvidenceError(path, f"expected testsuite root, got {suite.tag!r}"))
            continue
        totals["suites"] += 1
        for name in ("tests", "failures", "errors", "skipped"):
            totals[name] += integer(suite.get(name), path, name, errors)

    if totals["tests"] <= 0:
        errors.append(EvidenceError(report_dir, "JUnit reports contain no tests"))
    if totals["failures"] or totals["errors"]:
        errors.append(EvidenceError(
            report_dir,
            f"JUnit reports contain {totals['failures']} failures and {totals['errors']} errors",
        ))
    return totals


def validate_android_flavor(root: Path, flavor: str, errors: list[EvidenceError]) -> dict[str, object] | None:
    report_dir = root / "frontend" / "app" / "build" / "reports" / "coverage" / "test" / flavor / "debug"
    xml_path = report_dir / "report.xml"
    require_file(report_dir / "index.html", errors)
    report = parse_xml(xml_path, errors)
    if report is None:
        return None

    classes = [node for node in report.iter("class")
               if node.get("name", "").startswith("com/swpp/licenseplanner/")]
    line_counter = next((node for node in report.findall("counter") if node.get("type") == "LINE"), None)
    if line_counter is None:
        errors.append(EvidenceError(xml_path, "coverage has no LINE counter"))
        return {"classes": len(classes), "covered_lines": 0, "lines_valid": 0}

    covered = integer(line_counter.get("covered"), xml_path, "covered LINE", errors)
    missed = integer(line_counter.get("missed"), xml_path, "missed LINE", errors)
    total = covered + missed
    if not classes or total <= 0:
        errors.append(EvidenceError(xml_path, "coverage lacks project classes or a line denominator"))
    return {"classes": len(classes), "covered_lines": covered, "lines_valid": total}


def validate_android(root: Path, errors: list[EvidenceError]) -> dict[str, object]:
    app = root / "frontend" / "app" / "build"
    return {
        "junit": {
            "demo": validate_junit(app / "test-results" / "testDemoDebugUnitTest", errors),
            "live": validate_junit(app / "test-results" / "testLiveDebugUnitTest", errors),
        },
        "coverage": {
            flavor: validate_android_flavor(root, flavor, errors)
            for flavor in ("demo", "live")
        },
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo-root", type=Path, default=Path(__file__).resolve().parents[1],
                        help="repository root containing backend/ and frontend/")
    parser.add_argument("--scope", choices=("all", "backend", "android"), default="all",
                        help="evidence group to validate (default: all)")
    args = parser.parse_args()

    root = args.repo_root.resolve()
    errors: list[EvidenceError] = []
    summary: dict[str, object] = {"repository": str(root), "scope": args.scope}
    if args.scope in ("all", "backend"):
        summary["backend"] = validate_backend(root, errors)
    if args.scope in ("all", "android"):
        summary["android"] = validate_android(root, errors)

    summary["status"] = "passed" if not errors else "failed"
    summary["errors"] = [error.as_dict() for error in errors]
    print(json.dumps(summary, ensure_ascii=False, sort_keys=True))
    return 0 if not errors else 1


if __name__ == "__main__":
    raise SystemExit(main())
