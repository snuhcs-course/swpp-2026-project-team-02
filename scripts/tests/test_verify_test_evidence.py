"""Backend coverage-report checks of verify_test_evidence.py, using synthetic reports."""
from __future__ import annotations

import importlib.util
import sys
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).resolve().parents[1] / "verify_test_evidence.py"
_spec = importlib.util.spec_from_file_location("verify_test_evidence", SCRIPT)
evidence = importlib.util.module_from_spec(_spec)
sys.modules[_spec.name] = evidence  # its dataclass resolves the defining module by name
_spec.loader.exec_module(evidence)


class BackendCoverageEvidenceTests(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name)
        self.reports = self.root / "backend" / "coverage-results"
        (self.reports / "html").mkdir(parents=True)
        (self.reports / "html" / "index.html").write_text("<html></html>", encoding="utf-8")

    def write_report(self, filenames, lines_valid=120):
        classes = "".join(f'<class name="{Path(name).stem}" filename="{name}" line-rate="0.5"/>'
                          for name in filenames)
        (self.reports / "coverage.xml").write_text(
            f'<?xml version="1.0" ?><coverage lines-valid="{lines_valid}" line-rate="0.5" branch-rate="0.4">'
            f'<packages><package name="."><classes>{classes}</classes></package></packages></coverage>',
            encoding="utf-8")

    def validate(self):
        errors = []
        summary = evidence.validate_backend(self.root, errors)
        return summary, [error.message for error in errors]

    def test_nested_module_with_the_same_name_is_not_the_production_entry(self):
        for nested in (["test_support/api_server.py"], ["vendor/api_server.py"],
                       ["test_support/api_server.py", "api_service.py"]):
            with self.subTest(nested=nested):
                self.write_report(nested)
                _, errors = self.validate()
                self.assertEqual(errors, ["coverage does not include the top-level api_server.py"])

    def test_top_level_production_module_passes_beside_nested_entries(self):
        self.write_report(["api_server.py", "api_service.py", "test_support/api_server.py"])
        summary, errors = self.validate()
        self.assertEqual(errors, [])
        self.assertEqual((summary["classes"], summary["lines_valid"]), (3, 120))

    def test_report_without_measured_lines_fails(self):
        self.write_report(["api_server.py"], lines_valid=0)
        _, errors = self.validate()
        self.assertEqual(errors, ["coverage has no production-source line denominator"])

    def test_missing_html_report_still_fails(self):
        (self.reports / "html" / "index.html").unlink()
        self.write_report(["api_server.py"])
        _, errors = self.validate()
        self.assertEqual(errors, ["missing or empty report"])


if __name__ == "__main__":
    unittest.main()
