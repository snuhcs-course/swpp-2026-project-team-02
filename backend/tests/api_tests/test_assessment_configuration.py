"""Assessment configuration faults over the real HTTP handler, distinct from input and provider errors."""
import copy
import json
import tempfile
import threading
import unittest
from http.server import ThreadingHTTPServer
from pathlib import Path
from unittest.mock import patch
from urllib.error import HTTPError
from urllib.request import Request, urlopen

import api_service
from api_server import ASSESSMENT_CONFIGURATION_MESSAGE, DEFAULT_API_CONFIG, make_handler
from license_planner.agent import PlanningAgent
from test_support.question_banks import (
    CERT_ID, PRACTICE_CERT_ID, level_2_bank, practice_bank, temporary_assessment_configuration,
)


def results_for(questions):
    return [{"problem_id": question["id"], "question": question["prompt"], "choices": question["choices"],
             "correct_answer": question["correct_answer"], "user_answer": question["correct_answer"],
             "is_correct": True, "possible_score": question["possible_score"]} for question in questions]


def plan_payload(certification_id, questions):
    return {"certification_id": certification_id, "self_assessment": "처음 공부합니다.",
            "preparation_start": "2026/11/09/18/00", "exam_date": "2026/11/30/18/00",
            "weekly_availability": {"monday": [{"start": "18:00", "end": "20:00"}]},
            "assessment_results": {"certification_id": certification_id, "results": results_for(questions)}}


class AssessmentConfigurationHttpTests(unittest.TestCase):
    def setUp(self):
        self.model_requests = 0

        def no_model(*args, **kwargs):
            self.model_requests += 1
            raise AssertionError("a configuration fault must not reach the model")

        store = tempfile.TemporaryDirectory()
        self.addCleanup(store.cleanup)
        self.store = Path(store.name)
        for patcher in (patch.object(api_service, "STUDY_PLAN_OUTPUT_DIR", self.store),
                        patch.object(PlanningAgent, "_ask_model", no_model)):
            patcher.start()
            self.addCleanup(patcher.stop)
        self.server = ThreadingHTTPServer(("127.0.0.1", 0), make_handler(DEFAULT_API_CONFIG))
        threading.Thread(target=self.server.serve_forever, daemon=True).start()
        self.addCleanup(self.server.server_close)
        self.addCleanup(self.server.shutdown)
        self.base = f"http://127.0.0.1:{self.server.server_port}/api/v1"

    def call(self, path, payload=None):
        data = json.dumps(payload).encode() if payload is not None else None
        request = Request(f"{self.base}{path}", data=data, method="POST" if data else "GET",
                          headers={"Content-Type": "application/json"} if data else {})
        try:
            with urlopen(request, timeout=10) as response:
                return response.status, json.loads(response.read())
        except HTTPError as error:
            with error:
                return error.code, json.loads(error.read())

    def questions(self, certification_id):
        return self.call(f"/certifications/{certification_id}/questions")

    def assert_configuration_failure(self, status, body):
        self.assertEqual((status, body["error"]["code"]), (500, "INTERNAL_ERROR"), body)
        self.assertEqual(body["error"]["message"], ASSESSMENT_CONFIGURATION_MESSAGE)
        self.assertNotIn("questions", body)
        self.assertEqual((self.model_requests, list(self.store.iterdir())), (0, []))

    def test_newly_configured_certification_is_served_without_code_changes(self):
        with temporary_assessment_configuration(enabled=(CERT_ID, PRACTICE_CERT_ID)):
            status, body = self.questions(PRACTICE_CERT_ID)

        self.assertEqual(status, 200, body)
        self.assertEqual([question["id"] for question in body["questions"]], [2, 1])

    def test_bank_without_selection_fails_for_get_and_post_without_exposing_drafts(self):
        banks = {"questions.json": level_2_bank(), "practice.json": practice_bank(selection=None)}
        level_2 = level_2_bank()
        del level_2["onboarding_question_ids"]
        selected = [question for question in level_2_bank()["questions"]]
        cases = {PRACTICE_CERT_ID: (banks, practice_bank()["questions"][:2]),
                 CERT_ID: ({**banks, "questions.json": level_2}, selected)}
        for certification_id, (documents, submitted) in cases.items():
            with self.subTest(certification_id), temporary_assessment_configuration(
                    enabled=(CERT_ID, PRACTICE_CERT_ID), banks=documents), \
                    self.assertLogs("api_server", "ERROR") as logs:
                self.assert_configuration_failure(*self.questions(certification_id))
                self.assert_configuration_failure(*self.call("/study-plans", plan_payload(certification_id, submitted)))
            self.assertIn(f"assessment configuration for {certification_id!r}", logs.output[0])
            self.assertIn("onboarding_question_ids", logs.output[0])

    def test_enabled_certification_with_inconsistent_configuration_fails_explicitly(self):
        wrong_identity = practice_bank("another_certification")
        cases = {
            "missing planning entry": ({"planner": {}}, "no planning catalog entry"),
            "missing bank mapping": ({"bank_map": {CERT_ID: "questions.json"}}, "question bank map has no entry"),
            "missing bank file": ({"banks": {"questions.json": level_2_bank()}}, "question bank is unavailable"),
            "wrong bank identity": ({"banks": {"questions.json": level_2_bank(), "practice.json": wrong_identity}},
                                    "wrong certification"),
            "path outside the questionnaire": ({"bank_map": {PRACTICE_CERT_ID: "../certifications.json"}},
                                               "leaves assessment_questionnaire"),
        }
        for label, (configuration, diagnostic) in cases.items():
            with self.subTest(label), temporary_assessment_configuration(
                    enabled=(CERT_ID, PRACTICE_CERT_ID), **configuration), \
                    self.assertLogs("api_server", "ERROR") as logs:
                self.assert_configuration_failure(*self.questions(PRACTICE_CERT_ID))
                self.assert_configuration_failure(
                    *self.call("/study-plans", plan_payload(PRACTICE_CERT_ID, practice_bank()["questions"][:2])))
            self.assertIn(diagnostic, logs.output[0])
            self.assertNotIn("/", ASSESSMENT_CONFIGURATION_MESSAGE)

    def test_unknown_certification_keeps_not_found_and_input_errors(self):
        with temporary_assessment_configuration(enabled=(CERT_ID,)):
            status, body = self.questions(PRACTICE_CERT_ID)
            post_status, post_body = self.call(
                "/study-plans", plan_payload(PRACTICE_CERT_ID, practice_bank()["questions"][:2]))

        self.assertEqual((status, body["error"]["code"]), (404, "NOT_FOUND"))
        self.assertEqual((post_status, post_body["error"]["code"]), (422, "INVALID_INPUT"))
        self.assertEqual(self.model_requests, 0)

    def test_malformed_selected_content_returns_json_errors_without_mutating_the_bank(self):
        def changed(**fields):
            document = level_2_bank()
            document["questions"][0].update(fields)
            return document

        overflow = level_2_bank()
        for question in overflow["questions"][:2]:
            question["possible_score"] = 1e308
        null_container = level_2_bank()
        null_container["questions"] = None
        integer_container = level_2_bank()
        integer_container["questions"] = 7
        cases = {
            "list correct key": changed(correct_answer=["B"]),
            "object correct key": changed(correct_answer={"key": "B"}),
            "blank choice key": changed(choices={" ": "A1", "B": "$A$1", "C": "A$1", "D": "$A1"}),
            "blank choice text": changed(choices={"A": " ", "B": "$A$1", "C": "A$1", "D": "$A1"}),
            "huge integer score": changed(possible_score=10 ** 400),
            "boolean score": changed(possible_score=True),
            "UNKNOWN choice in bank": changed(choices={**level_2_bank()["questions"][0]["choices"],
                                                       "UNKNOWN": "모르겠음"}),
            "null questions container": null_container,
            "integer questions container": integer_container,
            "overflowing selected total": overflow,
        }
        for label, document in cases.items():
            with self.subTest(label), temporary_assessment_configuration(
                    banks={"questions.json": document}, bank_map={CERT_ID: "questions.json"}) as root, \
                    self.assertLogs("api_server", "ERROR"):
                before = (root / "questions.json").read_bytes()
                self.assert_configuration_failure(*self.questions(CERT_ID))
                self.assertEqual((root / "questions.json").read_bytes(), before)

        with temporary_assessment_configuration(banks={"questions.json": b"\xff\xfe{}"},
                                                bank_map={CERT_ID: "questions.json"}), \
                self.assertLogs("api_server", "ERROR") as logs:
            self.assert_configuration_failure(*self.questions(CERT_ID))
        self.assertIn("not valid UTF-8", logs.output[0])

    def test_reviewed_record_outside_onboarding_must_also_be_complete(self):
        document = level_2_bank()
        extra = copy.deepcopy(document["questions"][0])
        extra.update({"id": 40, "prompt": "온보딩 밖의 검토 문항"})
        del extra["explanation"]
        document["questions"].append(extra)
        with temporary_assessment_configuration(banks={"questions.json": document},
                                                bank_map={CERT_ID: "questions.json"}), \
                self.assertLogs("api_server", "ERROR") as logs:
            self.assert_configuration_failure(*self.questions(CERT_ID))
        self.assertIn("reviewed question 40 is missing explanation", logs.output[0])


if __name__ == "__main__":
    unittest.main()
