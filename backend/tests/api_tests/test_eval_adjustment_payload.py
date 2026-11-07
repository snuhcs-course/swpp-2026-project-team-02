"""Offline check of the evaluation script's generate-then-adjust requests and J1 outcome."""
import copy
import importlib.util
import json
import os
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

SCRIPT = Path(__file__).resolve().parents[3] / "docs" / "iter3-ai-eval" / "run_eval.py"
TOPICS = ["스프레드시트 기본", "함수와 데이터 관리", "차트와 분석"]
REQUEST = {
    "certification_id": "computer_specialist_level_2",
    "preparation_start": "2026/11/09/00/00",
    "exam_date": "2026/11/30/09/00",
    "weekly_availability": {"monday": [{"start": "19:00", "end": "21:00"}],
                            "wednesday": [{"start": "19:00", "end": "21:00"}]},
    "busy_schedules": [],
}
GENERATED = [
    {"schedule_id": f"study-{index + 1}", "start_date": start, "end_date": end, "topic": topic}
    for index, (start, end, topic) in enumerate([
        ("2026/11/09/19/00", "2026/11/09/20/00", TOPICS[0]),
        ("2026/11/11/19/00", "2026/11/11/20/00", TOPICS[1]),
        ("2026/11/16/19/00", "2026/11/16/20/00", TOPICS[2]),
    ])
]
PLAN_FILE = "license_planner/output/study_plan_20261109T000000000000+0900_abcdef12.csv"


def load_script():
    spec = importlib.util.spec_from_file_location("iter3_run_eval", SCRIPT)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def response(status, payload):
    return {"status": status, "error_kind": None, "error": None, "seconds": 0.01, "json": payload}


class EvaluationAdjustmentPayloadTests(unittest.TestCase):
    def setUp(self):
        self.run_eval = load_script()
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.workdir = Path(self.temp.name)
        case = {"case_id": "Z01", "expected_status": "200", "repetitions": 1,
                "request": REQUEST, "adjust": {"target": "first_study"},
                "expect": {"ordering_applies": False}}
        self.cases = self.workdir / "cases.json"
        self.cases.write_text(json.dumps({"version": "synthetic", "topics": TOPICS, "cases": [case]},
                                         ensure_ascii=False), encoding="utf-8")
        previous = os.getcwd()
        os.chdir(self.workdir)
        self.addCleanup(os.chdir, previous)

    def run_main(self, adjust_reply):
        sent = []

        def fake_post(url, body):
            sent.append((url.rsplit("/api/v1", 1)[1], copy.deepcopy(body)))
            if url.endswith("/study-plans"):
                return response(200, {"schedules": copy.deepcopy(GENERATED), "schedule_csv_file": PLAN_FILE})
            return adjust_reply

        argv = ["run_eval.py", "--base-url", "http://127.0.0.1:9/api/v1", "--cases", str(self.cases)]
        with patch.object(self.run_eval, "post", fake_post), \
                patch.object(self.run_eval.time, "sleep", lambda seconds: None), \
                patch("sys.argv", argv), patch("builtins.print"):
            self.run_eval.main()
        [out] = (self.workdir / "results").iterdir()
        runs = [json.loads(line) for line in (out / "runs.jsonl").read_text(encoding="utf-8").splitlines()]
        return sent, runs

    def assert_adjust_body(self, sent):
        self.assertEqual([path for path, _ in sent], ["/study-plans", "/schedules/adjust"])
        self.assertEqual(sent[0][1], REQUEST)
        self.assertEqual(sent[1][1], {"schedules": GENERATED, "schedule_id": "study-1",
                                      "schedule_csv_file": PLAN_FILE,
                                      "weekly_availability": REQUEST["weekly_availability"],
                                      "exam_date": REQUEST["exam_date"]})

    def test_adjustment_repeats_the_case_exam_date_and_records_success(self):
        moved = copy.deepcopy(GENERATED)
        moved[0].update(start_date="2026/11/09/20/00", end_date="2026/11/09/21/00")
        sent, runs = self.run_main(response(200, {"action": "rescheduled_study", "schedules": moved}))
        self.assert_adjust_body(sent)
        adjust = next(run for run in runs if run["kind"] == "adjust")
        self.assertEqual((adjust["outcome"], adjust["target"]), ("OK", "study-1"))
        self.assertTrue(adjust["adjust"]["J1"])

    def test_genuine_adjustment_error_remains_a_j1_failure(self):
        error = {"error": {"code": "INVALID_INPUT", "message": "NOT_ENOUGH_TIME: no later slot"}}
        sent, runs = self.run_main(response(422, error))
        self.assert_adjust_body(sent)
        adjust = next(run for run in runs if run["kind"] == "adjust")
        self.assertEqual((adjust["status"], adjust["outcome"]), (422, "UNEXPECTED_STATUS"))
        self.assertEqual(adjust["adjust"], {"J1": False})
        self.assertIn("NOT_ENOUGH_TIME", adjust["error"])


if __name__ == "__main__":
    unittest.main()
