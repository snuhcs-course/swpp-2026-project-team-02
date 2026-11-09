"""The questions route serves only the configured onboarding selection over HTTP."""
import json
import threading
import unittest
from http.server import ThreadingHTTPServer
from urllib.error import HTTPError
from urllib.request import urlopen

from api_server import DEFAULT_API_CONFIG, make_handler
from test_support.question_banks import (
    CERT_ID, incomplete_draft, larger_level_2_bank, level_2_bank, temporary_question_bank,
)

PUBLIC_FIELDS = {"id", "prompt", "choices", "correct_answer", "possible_score"}


class OnboardingQuestionRouteTests(unittest.TestCase):
    def setUp(self):
        self.server = ThreadingHTTPServer(("127.0.0.1", 0), make_handler(DEFAULT_API_CONFIG))
        threading.Thread(target=self.server.serve_forever, daemon=True).start()
        self.addCleanup(self.server.server_close)
        self.addCleanup(self.server.shutdown)
        self.url = f"http://127.0.0.1:{self.server.server_port}/api/v1/certifications/{CERT_ID}/questions"

    def get(self):
        try:
            with urlopen(self.url, timeout=10) as response:
                return response.status, json.loads(response.read())
        except HTTPError as error:
            with error:
                return error.code, json.loads(error.read())

    def serve(self, document):
        with temporary_question_bank(document) as bank_path:
            before = bank_path.read_bytes()
            status, body = self.get()
            self.assertEqual(bank_path.read_bytes(), before, "the route must not rewrite the bank")
        return status, body

    def test_checked_in_bank_serves_the_selected_eighteen(self):
        status, body = self.get()

        self.assertEqual(status, 200, body)
        self.assertEqual(set(body), {"certification_id", "certification_name", "questions"})
        self.assertEqual([question["id"] for question in body["questions"]], list(range(1, 19)))
        self.assertEqual(sum(question["possible_score"] for question in body["questions"]), 18)
        for question in body["questions"]:
            self.assertEqual(set(question), PUBLIC_FIELDS)

    def test_larger_collection_serves_the_same_selection_in_configured_order(self):
        _, baseline = self.serve(level_2_bank())
        larger = larger_level_2_bank()
        reordered = larger_level_2_bank()
        reordered["questions"].reverse()

        for label, document in {"extra reviewed and draft items": larger,
                                "reversed storage order": reordered}.items():
            with self.subTest(label):
                status, body = self.serve(document)
                self.assertEqual(status, 200, body)
                self.assertEqual(body, baseline)
                self.assertNotIn("syllabus_version", json.dumps(body))
                self.assertNotIn("onboarding_question_ids", body)

    def test_configured_order_is_served_rather_than_storage_order(self):
        document = level_2_bank()
        document["onboarding_question_ids"] = [6, 1, 18]

        status, body = self.serve(document)

        self.assertEqual(status, 200, body)
        self.assertEqual([question["id"] for question in body["questions"]], [6, 1, 18])

    def test_invalid_selection_fails_without_any_question_set(self):
        def with_selection(selection):
            document = larger_level_2_bank()
            document["onboarding_question_ids"] = selection
            return document

        missing = larger_level_2_bank()
        del missing["onboarding_question_ids"]
        incomplete = level_2_bank()
        del incomplete["questions"][3]["explanation"]
        unsupported_topic = level_2_bank()
        unsupported_topic["questions"][0]["topic"] = "실기 작업"
        cases = {
            "missing selection": missing,
            "empty selection": with_selection([]),
            "duplicate id": with_selection([1, 2, 2]),
            "unknown id": with_selection([1, 2, 99]),
            "selected draft": with_selection([*range(1, 19), incomplete_draft()["id"]]),
            "selected item missing explanation": incomplete,
            "selected item with unsupported topic": unsupported_topic,
        }
        for label, document in cases.items():
            with self.subTest(label):
                status, body = self.serve(document)
                self.assertEqual((status, body["error"]["code"]), (500, "INTERNAL_ERROR"), body)
                self.assertNotIn("questions", body)


if __name__ == "__main__":
    unittest.main()
