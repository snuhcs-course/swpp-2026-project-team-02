"""Regression coverage for the fixed-response server used by Android live E2E tests."""
from __future__ import annotations

import copy
import json
import unittest
from datetime import datetime
from pathlib import Path
from unittest.mock import patch
from urllib.error import HTTPError
from urllib.parse import quote
from urllib.request import Request, urlopen

import license_planner.agent
import schedule_adjuster.agent
from schedule_adjuster.csv_io import parse_schedule_rows, render_schedule_csv
from study_plan_store import StudyPlanStore
from test_support.fixed_live_e2e_server import (
    DEFAULT_CASE_ID, ScriptedAdjustmentAgent, load_cases, running_server,
)

WIRE = "%Y/%m/%d/%H/%M"
ROOMY_EXAM = "2026/11/30/18/00"
MISSING_STORED_EXAM_DATE = ("This saved plan has no stored exam date. "
                            "Create a new plan before adjusting study sessions.")


def request_json(url: str, method: str = "GET", payload: dict | None = None) -> tuple[int, dict]:
    body = json.dumps(payload).encode("utf-8") if payload is not None else None
    request = Request(url, data=body,
                      headers={"Content-Type": "application/json"} if body else {}, method=method)
    try:
        with urlopen(request, timeout=10) as response:
            return response.status, json.loads(response.read())
    except HTTPError as error:
        with error:
            return error.code, json.loads(error.read())


class FixedLiveE2EServerTests(unittest.TestCase):
    def setUp(self):
        self.case = load_cases()[DEFAULT_CASE_ID]

    def generate(self, server, exam_date: str | None = None) -> dict:
        payload = copy.deepcopy(self.case["request"])
        if exam_date is not None:
            payload["exam_date"] = exam_date
        status, plan = request_json(f"{server.api_base_url}/study-plans", "POST", payload)
        self.assertEqual(status, 200, plan)
        return plan

    def adjust_payload(self, plan: dict, schedule_id: str, **extra) -> dict:
        return {"schedule_csv_file": plan["schedule_csv_file"], "schedule_id": schedule_id,
                "schedules": plan["schedules"],
                "weekly_availability": self.case["request"]["weekly_availability"], **extra}

    def adjust(self, server, payload: dict) -> tuple[int, dict]:
        return request_json(f"{server.api_base_url}/schedules/adjust", "POST", payload)

    def readback(self, server, identifier: str) -> list[dict]:
        status, listing = request_json(
            f"{server.api_base_url}/study-plans?schedule_csv_file={quote(identifier, safe='')}")
        self.assertEqual(status, 200, listing)
        self.assertEqual([plan["schedule_csv_file"] for plan in listing["plans"]], [identifier])
        return listing["plans"][0]["schedules"]

    @staticmethod
    def stored_files(server) -> dict[str, bytes]:
        return {path.name: path.read_bytes() for path in sorted(server.output_dir.iterdir())}

    def test_real_handler_serves_questions_generates_and_reads_a_saved_plan(self):
        with running_server() as server:
            status, questions = request_json(
                f"{server.api_base_url}/certifications/computer_specialist_level_2/questions")
            self.assertEqual(status, 200)
            self.assertEqual(len(questions["questions"]), 5)

            plan = self.generate(server)
            self.assertTrue(plan["schedules"])
            self.assertTrue(plan["schedule_csv"])
            self.assertTrue(plan["schedule_csv_file"])
            self.assertEqual(len(plan["tool_calls"]), len(self.case["model_tool_calls"]))

            status, plans = request_json(f"{server.api_base_url}/study-plans")
            self.assertEqual(status, 200, plans)
            self.assertEqual([item["schedule_csv_file"] for item in plans["plans"]],
                             [plan["schedule_csv_file"]])

    def test_adjustment_uses_each_plans_stored_exam_date(self):
        with running_server() as server:
            plan_a = self.generate(server, ROOMY_EXAM)
            # The recorded request's own exam leaves no free window after the plan.
            plan_b = self.generate(server)
            legacy = StudyPlanStore(server.output_dir).create(
                render_schedule_csv(parse_schedule_rows(plan_a["schedules"])))
            legacy_plan = {**plan_a, "schedule_csv_file": legacy}
            b_name = Path(plan_b["schedule_csv_file"]).name

            # Rejections are decided before the agent asks the model anything.
            before = self.stored_files(server)
            rejections = {
                "another plan's exam date": (
                    self.adjust_payload(plan_a, "study-1", exam_date=self.case["request"]["exam_date"]),
                    "exam_date: must match the selected plan's stored exam date"),
                "explicit null": (self.adjust_payload(plan_a, "study-1", exam_date=None), None),
                "legacy plan with a date": (
                    self.adjust_payload(legacy_plan, "study-1", exam_date=ROOMY_EXAM), MISSING_STORED_EXAM_DATE),
                "legacy plan without a date": (
                    self.adjust_payload(legacy_plan, "study-1"), MISSING_STORED_EXAM_DATE),
                "no capacity before the original exam": (self.adjust_payload(plan_b, "study-1"), None),
            }
            with patch.object(ScriptedAdjustmentAgent, "_ask_model",
                              side_effect=AssertionError("model consulted for a rejected adjustment")):
                for label, (payload, message) in rejections.items():
                    with self.subTest(label):
                        status, body = self.adjust(server, payload)
                        self.assertEqual((status, body["error"]["code"]), (422, "INVALID_INPUT"), body)
                        if message is not None:
                            self.assertEqual(body["error"]["message"], message)
                        if label.startswith("no capacity"):
                            self.assertTrue(body["error"]["message"].startswith("NOT_ENOUGH_TIME:"))
                        self.assertEqual(self.stored_files(server), before)

            # Omitted date: the stored deadline applies, and readback agrees at the same identity.
            status, moved = self.adjust(server, self.adjust_payload(plan_a, "study-1"))
            self.assertEqual(status, 200, moved)
            self.assertEqual(moved["schedule_csv_file"], plan_a["schedule_csv_file"])
            self.assertEqual([call["tool"] for call in moved["tool_calls"]],
                             ["inspect_schedule", "inspect_topic_schedule", "reschedule_failed_study"])
            target = next(row for row in moved["schedules"] if row["schedule_id"] == "study-1")
            self.assertEqual((target["start_date"], target["end_date"]), ("2026/11/02/18/00", "2026/11/02/19/00"))
            self.assertLessEqual(datetime.strptime(target["end_date"], WIRE), datetime.strptime(ROOMY_EXAM, WIRE))
            self.assertEqual(self.readback(server, plan_a["schedule_csv_file"]), moved["schedules"])

            # Matching date from the latest rows is accepted.
            status, second = self.adjust(server, {**self.adjust_payload(moved, "study-2"), "exam_date": ROOMY_EXAM})
            self.assertEqual(status, 200, second)
            self.assertEqual(self.readback(server, plan_a["schedule_csv_file"]), second["schedules"])

            # Other plans and every stored deadline are unchanged; a reopened store sees A's deadline.
            after = self.stored_files(server)
            self.assertEqual(after[b_name], before[b_name])
            self.assertEqual(after[Path(legacy).name], before[Path(legacy).name])
            for name, content in before.items():
                if name.endswith(".meta.json"):
                    self.assertEqual(after[name], content)
            self.assertNotIn(Path(legacy).stem + ".meta.json", after)
            self.assertEqual(StudyPlanStore(server.output_dir).snapshot(plan_a["schedule_csv_file"]).exam_date,
                             datetime.strptime(ROOMY_EXAM, WIRE))

    def test_external_removal_runs_through_the_scripted_agent(self):
        with running_server() as server:
            payload = copy.deepcopy(self.case["request"])
            payload["exam_date"] = ROOMY_EXAM
            payload["busy_schedules"] = [{"schedule_id": "7", "start_date": "2026/10/20/09/00",
                                          "end_date": "2026/10/20/10/00", "topic": "외부 일정"}]
            status, plan = request_json(f"{server.api_base_url}/study-plans", "POST", payload)
            self.assertEqual(status, 200, plan)
            status, result = self.adjust(server, self.adjust_payload(plan, "external-7"))
            self.assertEqual(status, 200, result)
            self.assertEqual(result["action"], "cancelled_external")
            self.assertNotIn("external-7", [row["schedule_id"] for row in result["schedules"]])
            self.assertEqual(self.readback(server, plan["schedule_csv_file"]), result["schedules"])

    def test_provider_credentials_and_transport_are_unreachable(self):
        real = {(module, name): getattr(module, name)
                for module in (license_planner.agent, schedule_adjuster.agent)
                for name in ("create_response", "load_api_key", "load_model")}
        self.addCleanup(lambda: self.assertEqual(
            {key: getattr(*key) for key in real}, real, "fixture patches outlived the server"))
        with running_server():
            for module in (license_planner.agent, schedule_adjuster.agent):
                for name in ("create_response", "load_api_key", "load_model"):
                    with self.subTest(module=module.__name__, name=name), \
                            self.assertRaisesRegex(RuntimeError, "must not read provider credentials"):
                        getattr(module, name)()


if __name__ == "__main__":
    unittest.main()
