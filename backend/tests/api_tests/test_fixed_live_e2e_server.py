"""Regression coverage for the fixed-response server used by Android live E2E tests."""
from __future__ import annotations

import copy
import json
import tempfile
import unittest
from datetime import date, datetime, timedelta
from pathlib import Path
from unittest.mock import patch
from urllib.error import HTTPError
from urllib.parse import quote
from urllib.error import URLError
from urllib.request import Request, urlopen

import license_planner.agent
import license_planner.user_availability
import schedule_adjuster.agent
import schedule_adjuster.settings
from schedule_adjuster.csv_io import parse_schedule_rows, render_schedule_csv
from study_plan_store import StudyPlanStore
from test_support.fixed_live_e2e_server import (
    DEFAULT_CASE_ID, LEGACY_CASE_ID, ScriptedAdjustmentAgent, load_cases, running_server,
)
from test_support.question_banks import custom_question_variant

WIRE = "%Y/%m/%d/%H/%M"
ROOMY_EXAM = "2026/11/30/18/00"
LEVEL_2_TOPICS = {"스프레드시트 기본", "함수와 데이터 관리", "차트와 분석", "페이지 설정과 인쇄", "매크로 활용",
                  "컴퓨터 시스템 활용", "인터넷 자료 활용", "컴퓨터 시스템 보호"}
COMPUTER_GENERAL = {"컴퓨터 시스템 활용", "인터넷 자료 활용", "컴퓨터 시스템 보호"}
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
    """The recorded five-question classifier trace, pinned explicitly so classification and deadlines stay covered.

    Its questions are replayed as explicitly custom questions: the unchanged original five
    now resolve to reviewed topics, which the captured trace does not model.
    """

    def setUp(self):
        recorded = load_cases()[LEGACY_CASE_ID]
        self.case = {**recorded, "request": custom_question_variant(recorded["request"])}

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
        with running_server(case_id=LEGACY_CASE_ID) as server:
            status, questions = request_json(
                f"{server.api_base_url}/certifications/computer_specialist_level_2/questions")
            self.assertEqual(status, 200)
            self.assertEqual(len(questions["questions"]), 18)

            plan = self.generate(server)
            self.assertTrue(plan["schedules"])
            self.assertTrue(plan["schedule_csv"])
            self.assertTrue(plan["schedule_csv_file"])
            self.assertEqual([call["tool"] for call in plan["tool_calls"]],
                             [call["name"] for call in self.case["model_tool_calls"]])
            analysis = next(call for call in plan["tool_calls"] if call["tool"] == "analyze_exam_results")
            self.assertEqual(len(analysis["arguments"]["problem_topics"]), 5)

            status, plans = request_json(f"{server.api_base_url}/study-plans")
            self.assertEqual(status, 200, plans)
            self.assertEqual([item["schedule_csv_file"] for item in plans["plans"]],
                             [plan["schedule_csv_file"]])

    def test_adjustment_uses_each_plans_stored_exam_date(self):
        with running_server(case_id=LEGACY_CASE_ID) as server:
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
            original = next(row for row in plan_a["schedules"] if row["schedule_id"] == "study-1")
            self.assertEqual(target["start_date"], "2026/11/02/18/00")
            self.assertEqual(datetime.strptime(target["end_date"], WIRE) - datetime.strptime(target["start_date"], WIRE),
                             datetime.strptime(original["end_date"], WIRE) - datetime.strptime(original["start_date"], WIRE))
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
        with running_server(case_id=LEGACY_CASE_ID) as server:
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


class SelectedOnboardingE2EServerTests(unittest.TestCase):
    """The default synthetic trace for a complete onboarding submission."""

    def setUp(self):
        self.case = load_cases()[DEFAULT_CASE_ID]

    def test_default_case_is_the_synthetic_complete_selection_trace(self):
        self.assertEqual(DEFAULT_CASE_ID, "selected_onboarding_reviewed_topics")
        self.assertIn("not a captured provider response", self.case["provenance"])
        self.assertEqual(self.case["model_tool_calls"][0], {"name": "analyze_exam_results", "args": {}})

    def test_complete_selection_plans_both_subjects_adjusts_and_reads_back(self):
        with running_server() as server:
            status, questions = request_json(
                f"{server.api_base_url}/certifications/computer_specialist_level_2/questions")
            self.assertEqual(status, 200, questions)
            results = self.case["request"]["assessment_results"]["results"]
            self.assertEqual(
                [(row["problem_id"], row["question"], {k: v for k, v in row["choices"].items() if k != "UNKNOWN"},
                  row["correct_answer"], row["possible_score"]) for row in results],
                [(item["id"], item["prompt"], item["choices"], item["correct_answer"], item["possible_score"])
                 for item in questions["questions"]])
            self.assertIn("UNKNOWN", {row["user_answer"] for row in results})
            self.assertEqual(sum(row["possible_score"] for row in results), 18)

            status, plan = request_json(f"{server.api_base_url}/study-plans", "POST", self.case["request"])
            self.assertEqual(status, 200, plan)
            self.assertEqual([call["tool"] for call in plan["tool_calls"]],
                             ["analyze_exam_results", "build_study_schedule"])
            self.assertNotIn("problem_topics", plan["tool_calls"][0]["arguments"])
            topics = {row["topic"] for row in plan["schedules"]}
            self.assertEqual(topics, LEVEL_2_TOPICS)
            self.assertTrue(topics & COMPUTER_GENERAL and topics - COMPUTER_GENERAL)

            payload = {"schedule_csv_file": plan["schedule_csv_file"], "schedule_id": "study-1",
                       "schedules": plan["schedules"],
                       "weekly_availability": self.case["request"]["weekly_availability"]}
            status, moved = request_json(f"{server.api_base_url}/schedules/adjust", "POST", payload)
            self.assertEqual(status, 200, moved)
            self.assertEqual(moved["schedule_csv_file"], plan["schedule_csv_file"])
            status, listing = request_json(
                f"{server.api_base_url}/study-plans?schedule_csv_file={quote(plan['schedule_csv_file'], safe='')}")
            self.assertEqual(status, 200, listing)
            self.assertEqual(listing["plans"][0]["schedules"], moved["schedules"])



class CompatibilityE2EServerTests(unittest.TestCase):
    """Synthetic original-five and mixed reviewed/custom traces, selected explicitly by case ID."""

    def plan(self, case_id):
        case = load_cases()[case_id]
        self.assertIn("not a captured provider response", case["provenance"])
        with running_server(case_id=case_id) as server:
            status, plan = request_json(f"{server.api_base_url}/study-plans", "POST", case["request"])
            self.assertEqual(status, 200, plan)
            identifier = plan["schedule_csv_file"]
            status, listing = request_json(
                f"{server.api_base_url}/study-plans?schedule_csv_file={quote(identifier, safe='')}")
            self.assertEqual(status, 200, listing)
            self.assertEqual(listing["plans"][0]["schedules"], plan["schedules"])
        return case, plan

    def test_original_five_use_reviewed_topics(self):
        case, plan = self.plan("original_five_reviewed_topics")

        self.assertEqual([row["problem_id"] for row in case["request"]["assessment_results"]["results"]],
                         [1, 2, 3, 4, 5])
        self.assertEqual(plan["tool_calls"][0], {"tool": "analyze_exam_results", "arguments": {}})
        self.assertEqual({row["topic"] for row in plan["schedules"]},
                         {"스프레드시트 기본", "함수와 데이터 관리", "차트와 분석"})

    def test_mixed_case_classifies_only_the_custom_question(self):
        case, plan = self.plan("mixed_reviewed_and_custom_questions")

        mapping = plan["tool_calls"][0]["arguments"]["problem_topics"]
        self.assertEqual([item["problem_id"] for item in mapping], [501])
        self.assertEqual(len(case["request"]["assessment_results"]["results"]), 3)
        self.assertEqual({row["topic"] for row in plan["schedules"] if row["topic"]}, {"컴퓨터 시스템 보호"})


def _hour_window(start: str, minutes: int) -> list[dict]:
    return [{"start": start, "duration_minutes": minutes}]


def _empty_week() -> dict[str, list]:
    return {day: [] for day in ("monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday")}


class IsolatedScriptedServerTests(unittest.TestCase):
    """Scripted planning, isolated profile and plan storage, frozen manual clock and request log."""

    REAL_CONFIG = Path(license_planner.user_availability.__file__).resolve().parents[1] / "config"

    def setUp(self):
        self.storage = Path(self.enterContext(tempfile.TemporaryDirectory()))
        days_to_monday = 7 - date.today().weekday()
        self.monday = date.today() + timedelta(days=days_to_monday + 7)
        self.real_profile = license_planner.user_availability.USER_AVAILABILITY_PATH
        self.real_config = {path.name: path.read_bytes() for path in sorted(self.REAL_CONFIG.iterdir())
                            if path.is_file()}
        self.addCleanup(self.assert_real_configuration_untouched)

    def assert_real_configuration_untouched(self):
        self.assertEqual({path.name: path.read_bytes() for path in sorted(self.REAL_CONFIG.iterdir())
                          if path.is_file()}, self.real_config)
        self.assertEqual(license_planner.user_availability.USER_AVAILABILITY_PATH, self.real_profile)
        self.assertEqual(schedule_adjuster.settings.USER_AVAILABILITY_PATH, self.real_profile)

    def wire(self, day: date, hour: int, minute: int = 0) -> str:
        return datetime(day.year, day.month, day.day, hour, minute).strftime(WIRE)

    def plan_request(self) -> dict:
        request = copy.deepcopy(load_cases()[DEFAULT_CASE_ID]["request"])
        weekly = _empty_week()
        for day in ("monday", "tuesday", "wednesday", "thursday", "friday", "saturday"):
            weekly[day] = _hour_window("18:00", 120)
        weekly["sunday"] = _hour_window("23:00", 90)
        override = _empty_week()
        override["tuesday"] = [{"start": "20:00", "duration_minutes": 60}, {"start": "22:00", "duration_minutes": 30}]
        request.update({
            "preparation_start": self.wire(self.monday, 0),
            "exam_date": self.wire(self.monday + timedelta(days=28), 18),
            "weekly_availability": weekly,
            "availability_overrides": {(self.monday + timedelta(days=7)).strftime("%Y/%m/%d"): override},
        })
        return request

    def generate(self, server) -> dict:
        status, plan = request_json(f"{server.api_base_url}/study-plans", "POST", self.plan_request())
        self.assertEqual(status, 200, plan)
        return plan

    def manual_move(self, server, plan: dict, schedule_id: str, new_start: str) -> tuple[int, dict]:
        return request_json(f"{server.api_base_url}/schedules/adjust", "POST", {
            "mode": "manual", "schedule_csv_file": plan["schedule_csv_file"], "schedule_id": schedule_id,
            "schedules": plan["schedules"], "new_start_date": new_start})

    def test_availability_profile_is_seeded_saved_and_read_back_only_in_storage(self):
        seed = self.storage / "seed.json"
        seed.write_text(json.dumps({"weekly_availability": {"monday": _hour_window("21:00", 30)}}), encoding="utf-8")
        storage = self.storage / "server"
        changed = {"weekly_availability": {**_empty_week(), "sunday": _hour_window("23:00", 90)},
                   "availability_overrides": {self.monday.strftime("%Y/%m/%d"): _empty_week()}}

        with running_server(planning="scripted", storage_dir=storage, profile_seed=seed) as server:
            self.assertEqual(server.profile_path, storage.resolve() / "user_availability.json")
            status, loaded = request_json(f"{server.api_base_url}/availability")
            self.assertEqual(status, 200, loaded)
            self.assertEqual(loaded["weekly_availability"]["monday"][0]["duration_minutes"], 30)
            status, saved = request_json(f"{server.api_base_url}/availability", "PUT", changed)
            self.assertEqual(status, 200, saved)
            stored = json.loads(server.profile_path.read_text(encoding="utf-8"))
            self.assertEqual(stored["weekly_availability"]["sunday"][0]["duration_minutes"], 90)
            plan = self.generate(server)

        with running_server(planning="scripted", storage_dir=storage, profile_seed=seed) as restarted:
            status, loaded = request_json(f"{restarted.api_base_url}/availability")
            self.assertEqual(status, 200, loaded)
            self.assertEqual(loaded["weekly_availability"]["sunday"][0]["duration_minutes"], 90)
            self.assertEqual(loaded["weekly_availability"]["monday"], [])
            status, listing = request_json(f"{restarted.api_base_url}/study-plans?schedule_csv_file="
                                           f"{quote(plan['schedule_csv_file'], safe='')}")
            self.assertEqual(status, 200, listing)
            self.assertEqual(listing["plans"][0]["schedules"], plan["schedules"])

    def test_scripted_planning_uses_the_request_period_overrides_and_overnight_windows(self):
        with running_server(planning="scripted", storage_dir=self.storage) as server:
            plan = self.generate(server)

        self.assertEqual([call["tool"] for call in plan["tool_calls"]],
                         ["analyze_exam_results", "build_study_schedule"])
        studies = [row for row in plan["schedules"] if row["topic"]]
        self.assertTrue(studies)
        self.assertEqual({row["topic"] for row in studies}, LEVEL_2_TOPICS)
        starts = [datetime.strptime(row["start_date"], WIRE) for row in studies]
        override_week = {self.monday + timedelta(days=7 + offset) for offset in range(7)}
        self.assertEqual({start.date() for start in starts} & override_week, {self.monday + timedelta(days=8)})
        # P10 can split a proposed day's 60 minutes among topics; preserve
        # its exact workload and effective interval, not the old block count.
        override_rows = [row for row in studies if datetime.strptime(row["start_date"], WIRE).date() in override_week]
        self.assertEqual(min(row["start_date"][-5:] for row in override_rows), "20/00")
        self.assertEqual(sum((datetime.strptime(row["end_date"], WIRE) - datetime.strptime(row["start_date"], WIRE)).total_seconds()
                             for row in override_rows), 60 * 60)
        self.assertTrue(all("20:00" <= start.strftime("%H:%M") < "21:00" for start in starts if start.date() in override_week))
        self.assertTrue(all("23:00" <= start.strftime("%H:%M") for start in starts if start.weekday() == 6))
        self.assertTrue(any(start.weekday() == 6 for start in starts))
        self.assertIn((self.monday + timedelta(days=7)).strftime("%Y/%m/%d"),
                      plan["availability_snapshot"]["availability_overrides"])

    def test_frozen_clock_decides_whether_a_manual_destination_is_past(self):
        destination = self.wire(self.monday + timedelta(days=2), 19)
        frozen = datetime.combine(self.monday + timedelta(days=4), datetime.min.time()).replace(hour=12)

        with running_server(planning="scripted", storage_dir=self.storage / "frozen", now=frozen) as server:
            plan = self.generate(server)
            target = next(row for row in plan["schedules"]
                          if row["topic"] and row["start_date"] > self.wire(self.monday + timedelta(days=5), 0))
            status, refused = self.manual_move(server, plan, target["schedule_id"], destination)
            self.assertEqual(status, 422, refused)
            self.assertEqual(refused["error"]["code"], "MANUAL_MOVE_IN_PAST")

        early = datetime.combine(self.monday, datetime.min.time())
        with running_server(planning="scripted", storage_dir=self.storage / "early", now=early) as server:
            plan = self.generate(server)
            target = next(row for row in plan["schedules"]
                          if row["topic"] and row["start_date"] > self.wire(self.monday + timedelta(days=5), 0))
            status, moved = self.manual_move(server, plan, target["schedule_id"], destination)
            self.assertEqual(status, 200, moved)
            row = next(row for row in moved["schedules"] if row["schedule_id"] == target["schedule_id"])
            self.assertEqual(row["start_date"], destination)

    def test_request_log_and_test_control_switches(self):
        log, control = self.storage / "requests.jsonl", self.storage / "control.json"
        busy = {"schedule_id": "55", "start_date": self.wire(self.monday + timedelta(days=1), 18),
                "end_date": self.wire(self.monday + timedelta(days=1), 19), "topic": "외부 일정"}
        control.write_text(json.dumps({"inject_busy_schedules": [busy], "drop_next_adjust_response": True}),
                           encoding="utf-8")

        with running_server(planning="scripted", storage_dir=self.storage, request_log=log,
                            test_control=control) as server:
            request_json(f"{server.api_base_url}/availability")
            plan = self.generate(server)
            self.assertIn("external-55", {row["schedule_id"] for row in plan["schedules"]})
            target = next(row for row in plan["schedules"] if row["topic"])
            before = self.readback_rows(server, plan)
            with self.assertRaises((URLError, ConnectionError, OSError)):
                self.manual_move(server, plan, target["schedule_id"],
                                 self.wire(self.monday + timedelta(days=2), 19))
            self.assertNotEqual(self.readback_rows(server, plan), before)
            self.assertEqual(json.loads(control.read_text(encoding="utf-8")), {})

        entries = [json.loads(line) for line in log.read_text(encoding="utf-8").splitlines()]
        self.assertEqual([(entry["method"], entry["path"].split("?")[0], entry["status"]) for entry in entries], [
            ("GET", "/api/v1/availability", 200), ("POST", "/api/v1/study-plans", 200),
            ("GET", "/api/v1/study-plans", 200), ("POST", "/api/v1/schedules/adjust", 200),
            ("GET", "/api/v1/study-plans", 200)])
        self.assertEqual(entries[1]["body"]["busy_schedules"], [])
        self.assertEqual(entries[1]["test_control"][0]["rows"], [busy])
        self.assertTrue(entries[3]["response_dropped"])
        self.assertEqual(entries[3]["body"]["new_start_date"], self.wire(self.monday + timedelta(days=2), 19))

    def readback_rows(self, server, plan: dict) -> list[dict]:
        status, listing = request_json(f"{server.api_base_url}/study-plans?schedule_csv_file="
                                       f"{quote(plan['schedule_csv_file'], safe='')}")
        self.assertEqual(status, 200, listing)
        return listing["plans"][0]["schedules"]

    def test_scripted_mode_keeps_provider_access_forbidden(self):
        with running_server(planning="scripted", storage_dir=self.storage):
            for module in (license_planner.agent, schedule_adjuster.agent):
                for name in ("create_response", "load_api_key", "load_model"):
                    with self.subTest(module=module.__name__, name=name), \
                            self.assertRaisesRegex(RuntimeError, "must not read provider credentials"):
                        getattr(module, name)()

    def test_unknown_planning_mode_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "unknown planning mode"):
            with running_server(planning="live"):
                pass


if __name__ == "__main__":
    unittest.main()
