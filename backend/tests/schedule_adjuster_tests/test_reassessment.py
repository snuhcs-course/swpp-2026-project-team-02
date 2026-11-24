"""Shared Android/Python parity cases and reassessment boundary validation."""
import json
import unittest
from datetime import datetime
from pathlib import Path

from schedule_adjuster.csv_io import parse_schedule_rows
from schedule_adjuster.reassessment import ReassessmentPreviewExpired, resplit_study


def date(value):
    return datetime.strptime(value, "%Y/%m/%d/%H/%M")


CASES = json.loads((Path(__file__).parent / "fixtures/reassessment_parity.json").read_text(encoding="utf-8"))["cases"]


class ReassessmentTests(unittest.TestCase):
    def run_case(self, case, **overrides):
        options = dict(schedule=parse_schedule_rows(case["rows"]), keep=set(case["keep_schedule_ids"]),
                       results=[(row["topic"], row["possible_score"], row["is_correct"]) for row in case["results"]],
                       replan_from=date(case["replan_from"]), now=date(case["now"]),
                       first_new_study_id=case["first_new_study_id"])
        return resplit_study(**(options | overrides))

    def test_shared_android_parity_cases(self):
        for case in CASES:
            with self.subTest(case=case["name"]):
                if "expected_rows" in case:
                    expected = {row.schedule_id: row for row in parse_schedule_rows(case["expected_rows"])}
                    self.assertEqual({row.schedule_id: row for row in self.run_case(case)}, expected)
                elif case["expected_code"] == "REASSESSMENT_PREVIEW_EXPIRED":
                    with self.assertRaises(ReassessmentPreviewExpired):
                        self.run_case(case)
                else:
                    with self.assertRaisesRegex(ValueError, "NOT_ENOUGH_TIME" if case["expected_code"] == "NOT_ENOUGH_TIME" else "no future"):
                        self.run_case(case)

    def test_fresh_ids_cannot_collide_with_current_rows(self):
        for value in ("study-1", "study-0", "external-9", "study-05", "study--1", "study-1.5"):
            with self.subTest(value=value), self.assertRaisesRegex(ValueError, "first_new_study_id"):
                self.run_case(CASES[0], first_new_study_id=value)

    def test_only_future_study_rows_may_be_reported(self):
        for keep in ({"study-1"}, {"external-1"}, {"study-999"}):
            with self.subTest(keep=keep), self.assertRaisesRegex(ValueError, "keep_schedule_ids"):
                self.run_case(CASES[0], keep=keep)

    def test_unreported_row_at_current_minute_is_still_revisable(self):
        result = self.run_case(CASES[0], now=date("2026/12/03/19/00"))
        self.assertTrue(result)


if __name__ == "__main__":
    unittest.main()
