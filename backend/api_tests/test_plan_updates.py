"""Task 2 HTTP/storage regression checks using isolated files and agent tools."""
import copy
import json
import tempfile
import threading
import unittest
from pathlib import Path
from unittest.mock import patch
from urllib.error import HTTPError
from urllib.parse import quote
from urllib.request import Request, urlopen
from http.server import ThreadingHTTPServer

import api_service
from api_server import DEFAULT_API_CONFIG, make_handler
from schedule_adjuster.csv_io import parse_schedule_rows, render_schedule_csv
from schedule_adjuster.tools import ScheduleAdjustmentTools
from study_plan_store import StudyPlanStore


ROWS = [
    {"schedule_id": "study-1", "start_date": "2026/10/19/18/00",
     "end_date": "2026/10/19/19/00", "topic": "Functions"},
    {"schedule_id": "external-2", "start_date": "2026/10/19/19/00",
     "end_date": "2026/10/19/20/00", "topic": "Meeting"},
]
WINDOWS = {"monday": [{"start": "18:00", "end": "22:00"}]}


class ToolAgent:
    """Replace only model selection; execute the production domain tools."""
    def adjust(self, request):
        tools = ScheduleAdjustmentTools(request)
        target = next(e for e in request.original_schedule if e.schedule_id == request.schedule_id)
        tools.call("inspect_schedule", {"schedule_id": target.schedule_id})
        if target.schedule_type == "study":
            if target.topic:
                tools.call("inspect_topic_schedule", {"topic": target.topic})
            tools.call("reschedule_failed_study", {"schedule_id": target.schedule_id})
        else:
            tools.call("remove_cancelled_external_schedule", {"schedule_id": target.schedule_id})
        return tools, "Test agent with real domain tools"


class PlanUpdateTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.store = StudyPlanStore(self.root)
        self.original = render_schedule_csv(parse_schedule_rows(ROWS))
        self.identifier = self.store.create(self.original)
        self.filename = self.identifier.rsplit("/", 1)[1]
        self.payload = {"schedule_csv_file": self.identifier, "schedule_id": "study-1",
                        "schedules": copy.deepcopy(ROWS), "weekly_availability": WINDOWS}
        for name, value in [("STUDY_PLAN_OUTPUT_DIR", self.root),
                            ("GeminiAdjustmentAgent", ToolAgent)]:
            p = patch.object(api_service, name, value)
            p.start()
            self.addCleanup(p.stop)
        handler = make_handler(DEFAULT_API_CONFIG)
        handler.log_message = lambda *args: None
        self.server = ThreadingHTTPServer(("127.0.0.1", 0), handler)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.addCleanup(self.stop_server)

    def stop_server(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=3)

    def call(self, path, payload=None):
        data = None if payload is None else json.dumps(payload).encode()
        request = Request(f"http://127.0.0.1:{self.server.server_port}/api/v1{path}",
                          data=data, headers={"Content-Type": "application/json"})
        try:
            response = urlopen(request, timeout=5)
        except HTTPError as error:
            response = error
        with response:
            raw = response.read().decode()
            return response.status, (json.loads(raw) if "json" in response.headers.get("Content-Type", "") else raw)

    def test_update_list_download_agree_and_other_plan_unchanged(self):
        other = self.store.create(self.original)
        status, result = self.call("/schedules/adjust", self.payload)
        self.assertEqual(status, 200, result)
        self.assertEqual(result["schedule_csv_file"], self.identifier)
        target = next(r for r in result["schedules"] if r["schedule_id"] == "study-1")
        self.assertEqual(target["start_date"], "2026/10/19/20/00")
        self.assertEqual(target["end_date"], "2026/10/19/21/00")
        self.assertIn(ROWS[1], result["schedules"])
        self.assertEqual(self.call("/study-plans/" + self.filename), (200, result["schedule_csv"]))
        status, listing = self.call("/study-plans")
        self.assertEqual(status, 200)
        selected = next(p for p in listing["plans"] if p["schedule_csv_file"] == self.identifier)
        self.assertEqual(selected["schedules"], result["schedules"])
        self.assertEqual(self.store.download(other.rsplit("/", 1)[1])[0], self.original)

    def test_reordered_snapshot_accepted(self):
        self.payload["schedules"].reverse()
        self.assertEqual(self.call("/schedules/adjust", self.payload)[0], 200)

    def test_stale_snapshot_rejected_before_agent(self):
        self.payload["schedules"][0]["topic"] = "Changed"
        with patch.object(api_service, "GeminiAdjustmentAgent") as agent:
            status, result = self.call("/schedules/adjust", self.payload)
            self.assertEqual((status, result["error"]["code"]), (409, "PLAN_CONFLICT"))
            agent.assert_not_called()
        self.assertEqual(self.store.download(self.filename)[0], self.original)

    def test_missing_plan(self):
        (self.root / self.filename).unlink()
        status, result = self.call("/schedules/adjust", self.payload)
        self.assertEqual((status, result["error"]["code"]), (404, "PLAN_NOT_FOUND"))

    def test_invalid_paths(self):
        for identifier in ("../outside.csv", "C:/outside.csv", "license_planner/output/../x.csv", None):
            with self.subTest(identifier=identifier):
                self.payload["schedule_csv_file"] = identifier
                self.assertEqual(self.call("/schedules/adjust", self.payload)[0], 422)

    def test_invalid_rows_and_availability(self):
        variants = []
        for field, value in [("schedule_id", "other-1"), ("start_date", 3),
                             ("end_date", "2026/10/19/17/00"), ("topic", None)]:
            payload = copy.deepcopy(self.payload)
            payload["schedules"][0][field] = value
            variants.append(payload)
        duplicate = copy.deepcopy(self.payload)
        duplicate["schedules"].append(copy.deepcopy(ROWS[0]))
        variants.append(duplicate)
        for windows in (None, {}, {"monday": [{"start": "18:00", "end": "18:30"}]}):
            variants.append({**self.payload, "weekly_availability": windows})
        variants.append({**self.payload, "schedule_id": "study-99"})
        for payload in variants:
            with self.subTest(payload=payload):
                self.assertEqual(self.call("/schedules/adjust", payload)[0], 422)

    def test_legacy_nonpersistent_mode(self):
        del self.payload["schedule_csv_file"]
        status, result = self.call("/schedules/adjust", self.payload)
        self.assertEqual(status, 200)
        self.assertNotIn("schedule_csv_file", result)
        self.assertEqual(self.store.download(self.filename)[0], self.original)

    def test_external_removal(self):
        self.payload["schedule_id"] = "external-2"
        status, result = self.call("/schedules/adjust", self.payload)
        self.assertEqual(status, 200)
        self.assertEqual(result["action"], "cancelled_external")
        self.assertEqual(result["schedules"], [ROWS[0]])

    def test_agent_failure_leaves_file_unchanged(self):
        with patch.object(ToolAgent, "adjust", side_effect=RuntimeError("upstream failed")):
            self.assertEqual(self.call("/schedules/adjust", self.payload)[0], 502)
        self.assertEqual(self.store.download(self.filename)[0], self.original)

    def test_atomic_replace_failure_preserves_original(self):
        with patch("study_plan_store.os.replace", side_effect=PermissionError("locked")):
            status, result = self.call("/schedules/adjust", self.payload)
            self.assertEqual((status, result["error"]["code"]), (500, "STORAGE_ERROR"))
        self.assertEqual(self.store.download(self.filename)[0], self.original)
        self.assertEqual(list(self.root.glob("*.tmp")), [])

    def test_competing_updates_only_one_commits(self):
        barrier = threading.Barrier(2)
        original_adjust = ToolAgent.adjust
        def delayed(agent, request):
            barrier.wait(timeout=5)
            return original_adjust(agent, request)
        results = []
        def submit():
            try:
                results.append(self.call("/schedules/adjust", self.payload)[0])
            except Exception as error:
                results.append(str(error))
        with patch.object(ToolAgent, "adjust", delayed):
            threads = [threading.Thread(target=submit) for _ in range(2)]
            for thread in threads:
                thread.start()
            for thread in threads:
                thread.join(timeout=8)
        self.assertCountEqual(results, [200, 409])

    def test_empty_list_and_corrupt_file(self):
        (self.root / self.filename).unlink()
        self.assertEqual(self.call("/study-plans"), (200, {"plans": []}))
        (self.root / self.filename).write_text("broken", encoding="utf-8")
        status, result = self.call("/study-plans")
        self.assertEqual((status, result["error"]["code"]), (500, "STORAGE_ERROR"))

    def call_with_file_spies(self, path):
        """Return the response plus every file name read and directory listed."""
        original_read, original_iterdir = Path.read_bytes, Path.iterdir
        read, listed = [], []
        def spy_read(file):
            read.append(file.name)
            return original_read(file)
        def spy_iterdir(directory):
            listed.append(directory)
            return original_iterdir(directory)
        with patch.object(Path, "read_bytes", spy_read), patch.object(Path, "iterdir", spy_iterdir):
            return self.call(path), read, listed

    def filter_query(self, identifier):
        return "/study-plans?schedule_csv_file=" + quote(identifier, safe="")

    def test_filtered_list_returns_selected_plan_despite_damaged_other_plan(self):
        other_path = self.root / self.store.create(self.original).rsplit("/", 1)[1]
        selected_bytes = (self.root / self.filename).read_bytes()
        for damage in ("corrupt", "unreadable"):
            with self.subTest(damage=damage):
                if damage == "corrupt":
                    other_path.write_text("broken", encoding="utf-8")
                else:
                    other_path.chmod(0)
                    self.addCleanup(other_path.chmod, 0o644)
                status, result = self.call("/study-plans")
                self.assertEqual((status, result["error"]["code"]), (500, "STORAGE_ERROR"))
                (status, listing), read, listed = self.call_with_file_spies(self.filter_query(self.identifier))
                self.assertEqual(status, 200, listing)
                self.assertEqual(listing, {"plans": [{
                    "schedule_csv_file": self.identifier,
                    "schedule_csv_url": f"/api/v1/study-plans/{self.filename}",
                    "schedules": ROWS}]})
                self.assertEqual((read, listed), ([self.filename], []))
                self.assertEqual((self.root / self.filename).read_bytes(), selected_bytes)
        other_path.chmod(0o644)
        self.assertEqual(other_path.read_text(encoding="utf-8"), "broken")

    def test_filtered_list_distinguishes_corrupt_and_missing_selected_plan(self):
        path = self.root / self.filename
        path.write_text("broken", encoding="utf-8")
        status, result = self.call(self.filter_query(self.identifier))
        self.assertEqual((status, result["error"]["code"]), (500, "STORAGE_ERROR"))
        self.assertEqual(path.read_text(encoding="utf-8"), "broken")
        path.unlink()
        self.assertEqual(self.call(self.filter_query(self.identifier)), (200, {"plans": []}))
        self.assertFalse(path.exists())

    def test_filtered_list_rejects_invalid_identifiers_without_reading_files(self):
        link = self.root / "study_plan_20261017T120000000000+0900_abcdef12.csv"
        link.symlink_to(self.root / self.filename)
        encoded = quote(self.identifier, safe="")
        queries = ["schedule_csv_file=", "schedule_csv_file",
                   f"schedule_csv_file={encoded}&schedule_csv_file={encoded}",
                   # An unencoded '+' decodes to a space and no longer names the plan.
                   "schedule_csv_file=" + self.identifier]
        for identifier in ("../outside.csv", "/etc/passwd", "license_planner/output/../x.csv",
                           self.filename, "license_planner/output/" + link.name):
            queries.append("schedule_csv_file=" + quote(identifier, safe=""))
        for query in queries:
            with self.subTest(query=query):
                (status, result), read, listed = self.call_with_file_spies("/study-plans?" + query)
                self.assertEqual((status, result["error"]["code"]), (422, "INVALID_INPUT"))
                self.assertEqual((read, listed), ([], []))

    def test_unfiltered_list_ignores_unrelated_query_parameters(self):
        other = self.store.create(self.original)
        status, listing = self.call("/study-plans")
        self.assertEqual(status, 200)
        self.assertCountEqual([p["schedule_csv_file"] for p in listing["plans"]], [self.identifier, other])
        self.assertEqual(self.call("/study-plans?page=2"), (200, listing))

    def test_agent_cannot_modify_unrelated_rows(self):
        from dataclasses import replace
        original_adjust = ToolAgent.adjust
        def invalid(agent, request):
            tools, summary = original_adjust(agent, request)
            tools.schedule = tuple(replace(e, topic="Unexpected") if e.schedule_id == "external-2"
                                   else e for e in tools.schedule)
            return tools, summary
        with patch.object(ToolAgent, "adjust", invalid):
            self.assertEqual(self.call("/schedules/adjust", self.payload)[0], 502)
        self.assertEqual(self.store.download(self.filename)[0], self.original)

    def test_saved_availability_and_conflict_only_fallback(self):
        from schedule_adjuster.availability import parse_weekly_availability
        del self.payload["schedule_csv_file"]
        del self.payload["weekly_availability"]
        saved = parse_weekly_availability({"tuesday": [{"start": "09:00", "end": "11:00"}]})
        for availability, expected in [(saved, "2026/10/20/09/00"), (None, "2026/10/19/20/00")]:
            with patch.object(api_service, "load_adjuster_availability", return_value=availability):
                status, result = self.call("/schedules/adjust", self.payload)
                self.assertEqual(status, 200)
                target = next(r for r in result["schedules"] if r["schedule_id"] == "study-1")
                self.assertEqual(target["start_date"], expected)

    def test_deleted_during_agent_is_not_recreated(self):
        original_adjust = ToolAgent.adjust
        def deleted(agent, request):
            result = original_adjust(agent, request)
            (self.root / self.filename).unlink()
            return result
        with patch.object(ToolAgent, "adjust", deleted):
            self.assertEqual(self.call("/schedules/adjust", self.payload)[0], 404)
        self.assertFalse((self.root / self.filename).exists())


if __name__ == "__main__":
    unittest.main()
