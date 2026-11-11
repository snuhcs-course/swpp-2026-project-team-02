"""Offline checks: no OpenRouter requests or real deployment writes."""
import copy
import hashlib
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

import build_cases
import run_eval

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "backend"))
from api_service import build_plan_request
from license_planner.catalog import get_certification


class EvaluationTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.bank = json.loads((ROOT / "backend/assessment_questionnaire/questions.json").read_text(encoding="utf-8"))
        cls.cases = build_cases.build(cls.bank)
        cls.topics = build_cases.TOPICS

    def test_dataset_matches_bank_and_api_contract(self):
        self.assertEqual(len(self.cases), 42)
        self.assertEqual(len(self.topics), 8)
        self.assertEqual(set(self.topics), set(get_certification(self.bank["certification_id"]).topics))
        for case in self.cases:
            with self.subTest(case=case["case_id"]):
                if case["case_id"] == "D06":
                    with self.assertRaises(ValueError):
                        build_plan_request(case["request"], require_assessment=True)
                    continue
                request = build_plan_request(case["request"], require_assessment=True)
                self.assertEqual(len(request.problem_results), 18)
                self.assertFalse(request.assessment_items)
                self.assertEqual(len(case["request"]["assessment_results"]["results"]), 18)

    def test_single_weak_topic_coverage(self):
        isolated = {c["expect"]["weakest_topics"][0] for c in self.cases
                    if c["group"] == "A_weak_topic" and len(c["expect"]["weakest_topics"]) == 1}
        self.assertEqual(isolated, set(self.topics))
        a10 = next(c for c in self.cases if c["case_id"] == "A10")
        self.assertEqual(a10["expect"]["topic_scores"]["함수와 데이터 관리"], 50)

    def test_frozen_cases_match_generator_and_bank_hash(self):
        frozen = json.loads(Path(__file__).with_name("cases.json").read_text(encoding="utf-8"))
        bank_path = ROOT / "backend/assessment_questionnaire/questions.json"
        self.assertEqual(frozen["bank_sha256"], hashlib.sha256(bank_path.read_text(encoding="utf-8").encode("utf-8")).hexdigest())
        self.assertEqual(frozen["cases"], self.cases)
        self.assertEqual(frozen["topics"], self.topics)
        self.assertEqual(frozen["topic_map"], build_cases.TOPIC_MAP)

    def test_bank_order_and_answer_length(self):
        bank = copy.deepcopy(self.bank)
        bank["questions"].reverse()
        self.assertEqual(build_cases.build(bank), self.cases)
        with self.assertRaises(ValueError):
            build_cases.assessment(self.bank, ["A"] * 5)

    def test_quality_detects_reversed_allocation_and_overlap(self):
        case = next(c for c in self.cases if c["case_id"] == "A03")
        weakest = case["expect"]["weakest_topics"][0]
        def rows(reverse=False):
            result, minute = [], 0
            for i, topic in enumerate(self.topics):
                duration = (1 if topic == weakest else 8) if reverse else (20 if topic == weakest else 5)
                result.append({"schedule_id": f"study-{i}", "topic": topic,
                               "start_date": f"2026/11/09/19/{minute:02d}",
                               "end_date": f"2026/11/09/19/{minute + duration:02d}"})
                minute += duration
            return result
        hard, quality, metrics = run_eval.check_plan(case, rows(), self.topics)
        self.assertTrue(all(hard.values()))
        self.assertTrue(quality["Q1"] and quality["Q2"] and quality["Q3"])
        self.assertEqual(metrics["topic_minutes"][weakest], 20)
        _, bad, _ = run_eval.check_plan(case, rows(True), self.topics)
        self.assertFalse(bad["Q1"] or bad["Q2"])
        overlapping = rows()
        overlapping[1].update(start_date=overlapping[0]["start_date"], end_date=overlapping[0]["end_date"])
        self.assertFalse(run_eval.check_plan(case, overlapping, self.topics)[0]["H3"])
        self.assertFalse(all(run_eval.check_plan(case, [], self.topics)[0].values()))

    def test_cli_records_exchange_metadata_and_outputs_without_model(self):
        case = next(c for c in self.cases if c["case_id"] == "D01")
        response = {"status": 422, "error_kind": None, "error": None, "seconds": 0.01,
                    "json": {"error": {"code": "INVALID_INPUT", "message": "NOT_ENOUGH_TIME"}}}
        with tempfile.TemporaryDirectory() as directory:
            argv = ["run_eval.py", "--base-url", "http://offline.invalid/api/v1", "--only", "D01",
                    "--server-commit", "offline-test", "--model", "mock", "--output-dir", directory]
            with patch.object(sys, "argv", argv), patch.object(run_eval, "post", return_value=response) as post, patch.object(run_eval.time, "sleep"):
                run_eval.main()
            post.assert_called_once_with("http://offline.invalid/api/v1/study-plans", case["request"])
            out = next(Path(directory).iterdir())
            summary = json.loads((out / "summary.json").read_text(encoding="utf-8"))
            self.assertTrue(summary["meta"]["dry_run"])
            self.assertEqual(len(summary["meta"]["cases_sha256"]), 64)
            self.assertEqual(json.loads((out / "exchanges.jsonl").read_text(encoding="utf-8"))["request"], case["request"])
            for filename in ("runs.jsonl", "runs.csv", "summary.md"):
                self.assertTrue((out / filename).exists())

    def test_adjustment_uses_generated_plan_and_original_deadline(self):
        case = next(c for c in self.cases if c["case_id"] == "E01")
        row = {"schedule_id": "study-1", "topic": self.topics[0],
               "start_date": "2026/11/09/19/00", "end_date": "2026/11/09/19/30"}
        moved = {**row, "start_date": "2026/11/09/20/00", "end_date": "2026/11/09/20/30"}
        def response(body):
            return {"status": 200, "error_kind": None, "error": None, "seconds": 0.01, "json": body}
        replies = [response({"schedule_csv_file": "test.csv", "schedules": [row]}),
                   response({"action": "rescheduled_study", "schedules": [moved]})]
        with tempfile.TemporaryDirectory() as directory:
            with patch.object(sys, "argv", ["run_eval.py", "--base-url", "http://offline.invalid/api/v1",
                                            "--only", "E01", "--reps", "1", "--output-dir", directory]), \
                    patch.object(run_eval, "post", side_effect=replies) as post, patch.object(run_eval.time, "sleep"):
                run_eval.main()
            body = post.call_args_list[1].args[1]
            self.assertEqual(body["schedule_csv_file"], "test.csv")
            self.assertEqual(body["schedules"], [row])
            self.assertEqual(body["schedule_id"], row["schedule_id"])
            self.assertEqual(body["weekly_availability"], case["request"]["weekly_availability"])
            self.assertEqual(body["exam_date"], case["request"]["exam_date"])
            runs = [json.loads(line) for line in next(Path(directory).iterdir()).joinpath("runs.jsonl").read_text(encoding="utf-8").splitlines()]
            self.assertTrue(all(runs[1]["adjust"][k] for k in ("J1", "J2", "J3", "J4", "J5")))


if __name__ == "__main__":
    unittest.main()
