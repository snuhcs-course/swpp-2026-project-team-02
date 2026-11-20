"""HTTP checks for the shared availability profile using a temporary profile file."""
import json
import tempfile
import threading
import unittest
from http.server import ThreadingHTTPServer
from pathlib import Path
from unittest.mock import patch
from urllib.error import HTTPError
from urllib.request import Request, urlopen

import license_planner.user_availability as planner_availability
import schedule_adjuster.settings as adjuster_settings
from api_server import DEFAULT_API_CONFIG, make_handler
from schedule_adjuster.availability import load_personal_availability as load_adjuster_profile

EMPTY_WEEK = {day: [] for day in ("monday", "tuesday", "wednesday", "thursday",
                                  "friday", "saturday", "sunday")}


class AvailabilityProfileApiTests(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.profile = Path(temp.name) / "user_availability.json"
        for target in (planner_availability, adjuster_settings):
            patcher = patch.object(target, "USER_AVAILABILITY_PATH", self.profile)
            patcher.start()
            self.addCleanup(patcher.stop)
        handler = make_handler(DEFAULT_API_CONFIG)
        handler.log_message = lambda *args: None
        server = ThreadingHTTPServer(("127.0.0.1", 0), handler)
        threading.Thread(target=server.serve_forever, daemon=True).start()
        self.addCleanup(server.server_close)
        self.addCleanup(server.shutdown)
        self.base_url = f"http://127.0.0.1:{server.server_address[1]}/api/v1/availability"

    def request(self, method="GET", body=None):
        data = None if body is None else json.dumps(body).encode("utf-8")
        request = Request(self.base_url, data=data, method=method,
                          headers={"Content-Type": "application/json"})
        try:
            with urlopen(request, timeout=5) as response:
                return response.status, json.loads(response.read())
        except HTTPError as error:
            with error:
                return error.code, json.loads(error.read())

    def stored(self):
        return json.loads(self.profile.read_text(encoding="utf-8"))

    def test_missing_profile_returns_empty_envelope_without_creating_a_file(self):
        self.assertEqual(self.request(), (200, {"weekly_availability": EMPTY_WEEK, "availability_overrides": {}}))
        self.assertFalse(self.profile.exists())

    def test_legacy_profile_reads_keep_bytes_until_an_explicit_save(self):
        self.profile.write_text('{"weekly_availability": {"monday": [{"start": "18:00", "end": "20:00"}]}}\n',
                                encoding="utf-8")
        before = self.profile.read_bytes()

        for _ in range(2):
            status, body = self.request()
            self.assertEqual(status, 200)
            self.assertEqual(body["weekly_availability"]["monday"],
                             [{"start": "18:00", "duration_minutes": 120, "end": "20:00"}])
            self.assertEqual(body["availability_overrides"], {})
        planner_view = planner_availability.load_personal_availability()
        self.assertEqual(planner_view, load_adjuster_profile())
        self.assertEqual(self.profile.read_bytes(), before)

        self.assertEqual(self.request("PUT", body)[0], 200)
        self.assertEqual(self.stored()["weekly_availability"]["monday"],
                         [{"start": "18:00", "duration_minutes": 120}])

    def test_direct_put_returns_envelope_and_stores_duration_form(self):
        status, body = self.request("PUT", {"monday": [{"start": "18:00", "end": "20:00"}],
                                            "tuesday": [{"start": "23:00", "duration_minutes": 90}]})

        self.assertEqual(status, 200)
        self.assertEqual(body["weekly_availability"]["monday"],
                         [{"start": "18:00", "duration_minutes": 120, "end": "20:00"}])
        self.assertEqual(body["weekly_availability"]["tuesday"],
                         [{"start": "23:00", "duration_minutes": 90, "end": "00:30"}])
        self.assertEqual(self.stored()["weekly_availability"]["tuesday"],
                         [{"start": "23:00", "duration_minutes": 90}])
        self.assertNotIn('"end"', self.profile.read_text(encoding="utf-8"))
        self.assertEqual(self.request(), (200, body))

    def test_omitted_overrides_are_retained_and_explicit_empty_map_clears_them(self):
        overrides = {"2026/11/23": {}, "2026/11/30": {"tuesday": [{"start": "20:00", "duration_minutes": 60}]}}
        status, body = self.request("PUT", {"weekly_availability": {"wednesday": [
            {"start": "19:00", "end": "20:00"}]}, "availability_overrides": overrides})
        self.assertEqual(status, 200)
        self.assertEqual(body["availability_overrides"]["2026/11/23"], EMPTY_WEEK)
        self.assertEqual(body["availability_overrides"]["2026/11/30"]["tuesday"],
                         [{"start": "20:00", "duration_minutes": 60, "end": "21:00"}])

        status, body = self.request("PUT", {"monday": [{"start": "18:00", "end": "20:00"}]})
        self.assertEqual(status, 200)
        self.assertEqual(sorted(body["availability_overrides"]), ["2026/11/23", "2026/11/30"])
        self.assertEqual(body["weekly_availability"]["wednesday"], [])
        self.assertEqual(sorted(self.stored()["availability_overrides"]), ["2026/11/23", "2026/11/30"])

        status, body = self.request("PUT", {"weekly_availability": body["weekly_availability"],
                                            "availability_overrides": {}})
        self.assertEqual(status, 200)
        self.assertEqual(body["availability_overrides"], {})
        self.assertEqual(self.stored()["availability_overrides"], {})

    def test_response_can_be_put_back_without_losing_duration(self):
        self.assertEqual(self.request("PUT", {"weekly_availability": {
            "sunday": [{"start": "23:00", "duration_minutes": 90}]},
            "availability_overrides": {"2026/11/30": {"monday": [{"start": "00:30", "duration_minutes": 30}]}}})[0], 200)
        stored = self.profile.read_bytes()
        _, body = self.request()

        self.assertEqual(self.request("PUT", body), (200, body))
        self.assertEqual(self.profile.read_bytes(), stored)

    def test_invalid_updates_return_422_and_preserve_bytes(self):
        self.assertEqual(self.request("PUT", {"monday": [{"start": "18:00", "end": "20:00"}]})[0], 200)
        before = self.profile.read_bytes()
        invalid = (
            {"monday": [{"start": "23:00", "duration_minutes": 90}],
             "tuesday": [{"start": "00:15", "duration_minutes": 60}]},
            {"monday": [{"start": "23:00", "duration_minutes": 90, "end": "00:15"}]},
            {"monday": [{"start": "18:00", "duration_minutes": 1440}]},
            {"weekly_availability": {}, "availability_overrides": None},
            {"weekly_availability": {}, "availability_overrides": {"2026/11/24": {}}},
            {"weekly_availability": {"sunday": [{"start": "23:00", "duration_minutes": 90}]},
             "availability_overrides": {"2026/11/30": {"monday": [{"start": "00:15", "duration_minutes": 45}]}}},
        )
        for value in invalid:
            with self.subTest(value=value):
                status, body = self.request("PUT", value)
                self.assertEqual(status, 422)
                self.assertEqual(body["error"]["code"], "INVALID_INPUT")
                self.assertEqual(self.profile.read_bytes(), before)


if __name__ == "__main__":
    unittest.main()
