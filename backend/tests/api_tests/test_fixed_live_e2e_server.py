"""Regression coverage for the fixed-response server used by Android live E2E tests."""
from __future__ import annotations

import json
import unittest
from urllib.error import HTTPError
from urllib.request import Request, urlopen

from test_support.fixed_live_e2e_server import DEFAULT_CASE_ID, load_cases, running_server


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
    def test_real_handler_serves_questions_generates_and_reads_a_saved_plan(self):
        fixture = load_cases()[DEFAULT_CASE_ID]
        with running_server() as server:
            status, questions = request_json(
                f"{server.api_base_url}/certifications/computer_specialist_level_2/questions")
            self.assertEqual(status, 200)
            self.assertEqual(len(questions["questions"]), 5)

            status, plan = request_json(f"{server.api_base_url}/study-plans", "POST", fixture["request"])
            self.assertEqual(status, 200, plan)
            self.assertTrue(plan["schedules"])
            self.assertTrue(plan["schedule_csv"])
            self.assertTrue(plan["schedule_csv_file"])
            self.assertEqual(len(plan["tool_calls"]), len(fixture["model_tool_calls"]))

            status, plans = request_json(f"{server.api_base_url}/study-plans")
            self.assertEqual(status, 200, plans)
            self.assertEqual([item["schedule_csv_file"] for item in plans["plans"]],
                             [plan["schedule_csv_file"]])


if __name__ == "__main__":
    unittest.main()
