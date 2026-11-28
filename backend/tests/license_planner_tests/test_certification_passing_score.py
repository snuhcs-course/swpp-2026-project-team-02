import json
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest

from license_planner.catalog import load_certifications


class CertificationPassingScoreTests(unittest.TestCase):
    def load(self, passing):
        row = {"id": "example", "display_name": "Example", "topics": ["A"]}
        if passing != "missing":
            row["passing_score"] = passing
        with TemporaryDirectory() as folder:
            path = Path(folder) / "certifications.json"
            path.write_text(json.dumps({"certifications": [row]}), encoding="utf-8")
            return load_certifications(path)["example"]

    def test_passing_scores_are_configuration_values_without_a_silent_default(self):
        for score in (0, 60, 80, 100):
            self.assertEqual(self.load(score).passing_score, score)
        self.assertIsNone(self.load("missing").passing_score)

    def test_invalid_passing_scores_are_rejected(self):
        for score in (True, "60", -1, 101, float("nan"), float("inf")):
            with self.subTest(score=score), self.assertRaisesRegex(RuntimeError, "passing_score"):
                self.load(score)
