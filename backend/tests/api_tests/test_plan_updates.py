"""Task 2 HTTP/storage regression checks using isolated files and agent tools."""
import copy
import itertools
import json
import tempfile
import threading
import unittest
from datetime import datetime
from pathlib import Path
from unittest.mock import patch
from urllib.error import HTTPError
from urllib.parse import quote
from urllib.request import Request, urlopen
from http.server import ThreadingHTTPServer

import api_service
from api_server import DEFAULT_API_CONFIG, make_handler
from schedule_adjuster.agent import AdjustmentAgent
from schedule_adjuster.availability import load_personal_availability as load_adjuster_availability
from schedule_adjuster.csv_io import parse_schedule_rows, render_schedule_csv
from schedule_adjuster.settings import AGENT_MAX_STEPS
from schedule_adjuster.tools import ScheduleAdjustmentTools
from study_plan_store import StudyPlanStore


ROWS = [
    {"schedule_id": "study-1", "start_date": "2026/10/19/18/00",
     "end_date": "2026/10/19/19/00", "topic": "Functions"},
    {"schedule_id": "external-2", "start_date": "2026/10/19/19/00",
     "end_date": "2026/10/19/20/00", "topic": "Meeting"},
]
WINDOWS = {"monday": [{"start": "18:00", "end": "22:00"}]}
EXAM_DATE = "2026/10/20/22/00"
EXAM = datetime(2026, 10, 20, 22, 0)
OTHER_EXAM = datetime(2026, 11, 30, 9, 0)
MISSING_STORED_EXAM_DATE = ("This saved plan has no stored exam date. "
                            "Create a new plan before adjusting study sessions.")


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


CALL_IDS = itertools.count(1)


def model_call(name, **args):
    return {"status": "completed", "output": [{"type": "function_call", "call_id": f"call_{next(CALL_IDS)}",
                                               "name": name, "arguments": json.dumps(args)}]}


def model_text(text):
    return {"status": "completed", "output": [{"type": "message", "role": "assistant",
                                               "content": [{"type": "output_text", "text": text}]}]}


def scripted_agent(*replies):
    """Run the production agent loop and tools; only provider replies are scripted."""
    class ScriptedAgent(AdjustmentAgent):
        script = list(replies)

        def __init__(self):
            super().__init__(api_key="dummy-key")

        def _ask_model(self, input_items):
            self.api_request_count += 1
            reply = ScriptedAgent.script.pop(0)
            if isinstance(reply, Exception):
                raise reply
            return reply
    return ScriptedAgent


class PlanUpdateTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.store = StudyPlanStore(self.root)
        self.original = render_schedule_csv(parse_schedule_rows(ROWS))
        self.identifier = self.store.create(self.original, EXAM)
        self.filename = self.identifier.rsplit("/", 1)[1]
        self.metadata = self.metadata_path(self.identifier)
        self.metadata_bytes = self.metadata.read_bytes()
        self.payload = {"schedule_csv_file": self.identifier, "schedule_id": "study-1",
                        "schedules": copy.deepcopy(ROWS), "weekly_availability": WINDOWS,
                        "exam_date": EXAM_DATE}
        for name, value in [("STUDY_PLAN_OUTPUT_DIR", self.root),
                            ("AdjustmentAgent", ToolAgent)]:
            p = patch.object(api_service, name, value)
            p.start()
            self.addCleanup(p.stop)
        handler = make_handler(DEFAULT_API_CONFIG)
        handler.log_message = lambda *args: None
        self.server = ThreadingHTTPServer(("127.0.0.1", 0), handler)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.addCleanup(self.stop_server)

    def metadata_path(self, identifier):
        return self.root / (Path(identifier.rsplit("/", 1)[1]).stem + ".meta.json")

    def assert_selected_plan_unchanged(self):
        self.assertEqual(self.store.download(self.filename)[0], self.original)
        self.assertEqual(self.metadata.read_bytes(), self.metadata_bytes)

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
        other = self.store.create(self.original, OTHER_EXAM)
        other_metadata = self.metadata_path(other).read_bytes()
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
        self.assertEqual(self.metadata_path(other).read_bytes(), other_metadata)
        self.assertEqual(self.metadata.read_bytes(), self.metadata_bytes)

    def test_reordered_snapshot_accepted(self):
        self.payload["schedules"].reverse()
        self.assertEqual(self.call("/schedules/adjust", self.payload)[0], 200)

    def test_stale_snapshot_rejected_before_agent(self):
        self.payload["schedules"][0]["topic"] = "Changed"
        with patch.object(api_service, "AdjustmentAgent") as agent:
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
        self.assert_selected_plan_unchanged()
        self.assertEqual(len(list(self.root.iterdir())), 2)

    def test_external_removal(self):
        self.payload["schedule_id"] = "external-2"
        del self.payload["exam_date"]
        status, result = self.call("/schedules/adjust", self.payload)
        self.assertEqual(status, 200)
        self.assertEqual(result["action"], "cancelled_external")
        self.assertEqual(result["schedules"], [ROWS[0]])
        self.assertEqual(self.store.download(self.filename)[0], result["schedule_csv"])
        self.assertEqual(self.metadata.read_bytes(), self.metadata_bytes)

    def test_damaged_stored_exam_date_blocks_external_cancellation(self):
        """External removal needs no deadline but must not bypass a damaged plan snapshot."""
        self.payload["schedule_id"] = "external-2"
        del self.payload["exam_date"]
        valid_metadata = self.metadata_bytes
        outside_dir = tempfile.TemporaryDirectory()
        self.addCleanup(outside_dir.cleanup)
        outside = Path(outside_dir.name) / "outside.meta.json"
        outside.write_text('{"exam_date":"2026/12/31/23/00"}', encoding="utf-8")
        outside_bytes = outside.read_bytes()

        def malformed(content):
            def damage():
                self.metadata.write_bytes(content)
                return lambda: self.assertEqual(self.metadata.read_bytes(), content)
            return damage

        def unreadable():
            self.metadata.chmod(0)
            try:
                self.metadata.read_bytes()
            except PermissionError:
                pass
            else:
                self.skipTest("metadata stays readable after chmod 0 here")
            def unchanged():
                self.metadata.chmod(0o644)
                self.assertEqual(self.metadata.read_bytes(), valid_metadata)
            return unchanged

        def symlinked():
            self.metadata.unlink()
            self.symlink(self.metadata, outside)
            def unchanged():
                self.assertTrue(self.metadata.is_symlink())
                self.assertEqual(outside.read_bytes(), outside_bytes)
            return unchanged

        def restore():
            if not self.metadata.is_symlink():
                self.metadata.chmod(0o644)
            self.metadata.unlink()
            self.metadata.write_bytes(valid_metadata)

        damages = {"invalid timestamp": malformed(b'{"exam_date": "soon"}'),
                   "invalid JSON": malformed(b"{broken"),
                   "wrong type": malformed(b'{"exam_date": 202610202200}'),
                   "unreadable": unreadable,
                   "symlinked": symlinked}
        for label, damage in damages.items():
            with self.subTest(label):
                try:
                    assert_metadata_unchanged = damage()
                    with patch.object(api_service, "AdjustmentAgent") as agent:
                        status, result = self.call("/schedules/adjust", self.payload)
                    self.assertEqual((status, result["error"]["code"]), (500, "STORAGE_ERROR"))
                    agent.assert_not_called()
                    self.assertEqual(self.store.download(self.filename)[0], self.original)
                    self.assertEqual(self.call("/study-plans/" + self.filename), (200, self.original))
                    status, listing = self.call(self.filter_query(self.identifier))
                    self.assertEqual((status, listing["plans"][0]["schedules"]), (200, ROWS))
                    assert_metadata_unchanged()
                finally:
                    restore()
        status, result = self.call("/schedules/adjust", self.payload)
        self.assertEqual((status, result["schedules"]), (200, [ROWS[0]]))
        self.assertEqual(self.metadata.read_bytes(), valid_metadata)

    def test_reschedule_stops_before_stored_exam_and_keeps_stored_plan(self):
        """A failed session is not moved past its plan's original exam time."""
        identifier = self.store.create(self.original, datetime(2026, 10, 19, 20, 30))
        metadata = self.metadata_path(identifier).read_bytes()
        payload = {**self.payload, "schedule_csv_file": identifier}
        del payload["exam_date"]
        with patch.object(api_service, "AdjustmentAgent") as agent:
            status, result = self.call("/schedules/adjust", payload)
        self.assertEqual((status, result["error"]["code"]), (422, "INVALID_INPUT"))
        self.assertIn("NOT_ENOUGH_TIME", result["error"]["message"])
        agent.assert_not_called()
        self.assertEqual(self.store.download(identifier.rsplit("/", 1)[1])[0], self.original)
        self.assertEqual(self.metadata_path(identifier).read_bytes(), metadata)

    def test_request_without_exam_date_uses_stored_deadline(self):
        other = self.store.create(self.original, datetime(2026, 10, 19, 20, 30))
        del self.payload["exam_date"]
        status, result = self.call("/schedules/adjust", self.payload)
        self.assertEqual(status, 200, result)
        target = next(r for r in result["schedules"] if r["schedule_id"] == "study-1")
        self.assertEqual((target["start_date"], target["end_date"]), ("2026/10/19/20/00", "2026/10/19/21/00"))
        self.assertEqual(self.call("/study-plans/" + self.filename), (200, result["schedule_csv"]))
        self.assertEqual(self.metadata.read_bytes(), self.metadata_bytes)
        self.assertEqual(self.store.download(other.rsplit("/", 1)[1])[0], self.original)

    def test_each_saved_plan_uses_only_its_own_stored_deadline(self):
        """Two plans with identical rows but different deadlines are adjusted independently."""
        early = self.store.create(self.original, datetime(2026, 10, 19, 20, 30))
        early_name = early.rsplit("/", 1)[1]
        early_metadata = self.metadata_path(early).read_bytes()
        omitted = {key: value for key, value in self.payload.items() if key != "exam_date"}

        status, result = self.call("/schedules/adjust", {**omitted, "schedule_csv_file": early})
        self.assertEqual((status, result["error"]["code"]), (422, "INVALID_INPUT"))
        self.assertIn("NOT_ENOUGH_TIME", result["error"]["message"])

        status, result = self.call("/schedules/adjust", omitted)
        self.assertEqual(status, 200, result)
        target = next(r for r in result["schedules"] if r["schedule_id"] == "study-1")
        self.assertEqual((target["start_date"], target["end_date"]), ("2026/10/19/20/00", "2026/10/19/21/00"))

        status, result = self.call("/schedules/adjust", {**omitted, "schedule_csv_file": early})
        self.assertEqual((status, result["error"]["code"]), (422, "INVALID_INPUT"))
        self.assertEqual(self.store.download(early_name)[0], self.original)
        self.assertEqual(self.metadata_path(early).read_bytes(), early_metadata)
        self.assertEqual(self.metadata.read_bytes(), self.metadata_bytes)

    def test_saved_adjustment_avoids_and_keeps_imported_busy_rows(self):
        """Imported calendar rows saved with a plan stay unchanged and keep blocking a moved session."""
        rows = [
            {"schedule_id": "study-1", "start_date": "2026/10/19/18/00",
             "end_date": "2026/10/19/19/00", "topic": "Functions"},
            {"schedule_id": "external-55", "start_date": "2026/10/19/19/00",
             "end_date": "2026/10/19/20/00", "topic": "외부 일정"},
            {"schedule_id": "external-56", "start_date": "2026/10/19/19/30",
             "end_date": "2026/10/19/21/00", "topic": "외부 일정"},
        ]
        identifier = self.store.create(render_schedule_csv(parse_schedule_rows(rows)), EXAM)
        metadata = self.metadata_path(identifier).read_bytes()
        payload = {"schedule_csv_file": identifier, "schedule_id": "study-1",
                   "schedules": copy.deepcopy(rows), "weekly_availability": WINDOWS}
        status, result = self.call("/schedules/adjust", payload)
        self.assertEqual(status, 200, result)
        target = next(r for r in result["schedules"] if r["schedule_id"] == "study-1")
        self.assertEqual((target["start_date"], target["end_date"]), ("2026/10/19/21/00", "2026/10/19/22/00"))
        self.assertEqual([r for r in result["schedules"] if r["schedule_id"].startswith("external-")], rows[1:])
        self.assertEqual(self.call("/study-plans/" + identifier.rsplit("/", 1)[1]), (200, result["schedule_csv"]))
        self.assertEqual(self.metadata_path(identifier).read_bytes(), metadata)
        self.assert_selected_plan_unchanged()

    def test_supplied_exam_date_must_match_stored_deadline(self):
        self.store.create(self.original, OTHER_EXAM)
        mismatched = {"earlier": "2026/10/19/23/00", "later": "2026/12/31/23/00",
                      "other plan": OTHER_EXAM.strftime("%Y/%m/%d/%H/%M")}
        invalid = {"null": None, "malformed": "2026-10-20 22:00", "number": 202610202200}
        for label, value in {**mismatched, **invalid}.items():
            with self.subTest(label), patch.object(api_service, "AdjustmentAgent") as agent:
                status, result = self.call("/schedules/adjust", {**self.payload, "exam_date": value})
                self.assertEqual((status, result["error"]["code"]), (422, "INVALID_INPUT"))
                if label in mismatched:
                    self.assertEqual(result["error"]["message"],
                                     "exam_date: must match the selected plan's stored exam date")
                else:
                    self.assertRegex(result["error"]["message"], "^exam_date")
                agent.assert_not_called()
                self.assert_selected_plan_unchanged()

    def test_saved_plan_without_stored_exam_date_requires_recreation(self):
        legacy = self.store.create(self.original)
        legacy_name = legacy.rsplit("/", 1)[1]
        files = sorted(path.name for path in self.root.iterdir())
        for label, exam_date in (("client deadline", EXAM_DATE), ("omitted", None)):
            payload = {**self.payload, "schedule_csv_file": legacy}
            if exam_date is None:
                del payload["exam_date"]
            with self.subTest(label), patch.object(api_service, "AdjustmentAgent") as agent:
                status, result = self.call("/schedules/adjust", payload)
                self.assertEqual((status, result), (422, {"error": {
                    "code": "INVALID_INPUT", "message": MISSING_STORED_EXAM_DATE}}))
                agent.assert_not_called()
                self.assertEqual(sorted(path.name for path in self.root.iterdir()), files)
        self.assertEqual(self.call("/study-plans/" + legacy_name), (200, self.original))
        status, listing = self.call("/study-plans?schedule_csv_file=" + quote(legacy, safe=""))
        self.assertEqual((status, listing["plans"][0]["schedules"]), (200, ROWS))
        self.assert_selected_plan_unchanged()

    def test_saved_plan_row_errors_precede_deadline_checks(self):
        legacy = self.store.create(self.original)
        payload = {**self.payload, "schedule_csv_file": legacy}
        payload["schedules"][0]["topic"] = "Changed"
        self.assertEqual(self.call("/schedules/adjust", payload)[1]["error"]["code"], "PLAN_CONFLICT")
        (self.root / legacy.rsplit("/", 1)[1]).unlink()
        del payload["exam_date"]
        self.assertEqual(self.call("/schedules/adjust", payload)[1]["error"]["code"], "PLAN_NOT_FOUND")

    def test_saved_plan_without_stored_exam_date_can_cancel_external_entry(self):
        legacy = self.store.create(self.original)
        payload = {**self.payload, "schedule_csv_file": legacy, "schedule_id": "external-2"}
        del payload["exam_date"]
        status, result = self.call("/schedules/adjust", payload)
        self.assertEqual(status, 200, result)
        self.assertEqual(result["schedules"], [ROWS[0]])
        self.assertEqual(self.store.download(legacy.rsplit("/", 1)[1])[0], result["schedule_csv"])
        self.assertFalse(self.metadata_path(legacy).exists())

    def test_damaged_stored_exam_date_is_a_storage_error(self):
        self.metadata.write_text('{"exam_date": "soon"}', encoding="utf-8")
        self.metadata_bytes = self.metadata.read_bytes()
        for label in ("supplied", "omitted"):
            payload = copy.deepcopy(self.payload)
            if label == "omitted":
                del payload["exam_date"]
            with self.subTest(label), patch.object(api_service, "AdjustmentAgent") as agent:
                status, result = self.call("/schedules/adjust", payload)
                self.assertEqual((status, result["error"]["code"]), (500, "STORAGE_ERROR"))
                agent.assert_not_called()
                self.assert_selected_plan_unchanged()

    def test_stored_exam_date_changed_during_agent_is_a_conflict(self):
        original_adjust = ToolAgent.adjust
        def changed(agent, request):
            result = original_adjust(agent, request)
            self.metadata.write_text('{"exam_date":"2026/12/31/23/00"}', encoding="utf-8")
            return result
        with patch.object(ToolAgent, "adjust", changed):
            status, result = self.call("/schedules/adjust", self.payload)
        self.assertEqual((status, result["error"]["code"]), (409, "PLAN_CONFLICT"))
        self.assertEqual(self.store.download(self.filename)[0], self.original)

    def test_nonpersistent_mode_requires_a_supplied_exam_date(self):
        del self.payload["schedule_csv_file"]
        files = sorted(path.name for path in self.root.iterdir())
        for value in (None, "missing"):
            payload = copy.deepcopy(self.payload)
            if value == "missing":
                del payload["exam_date"]
            else:
                payload["exam_date"] = value
            with self.subTest(value), patch.object(api_service, "AdjustmentAgent") as agent:
                status, result = self.call("/schedules/adjust", payload)
                self.assertEqual((status, result["error"]["code"]), (422, "INVALID_INPUT"))
                agent.assert_not_called()
        self.assertEqual(sorted(path.name for path in self.root.iterdir()), files)

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
                self.assertEqual((read, listed), ([self.filename, self.metadata.name], []))
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
        encoded = quote(self.identifier, safe="")
        queries = ["schedule_csv_file=", "schedule_csv_file",
                   f"schedule_csv_file={encoded}&schedule_csv_file={encoded}",
                   # An unencoded '+' decodes to a space and no longer names the plan.
                   "schedule_csv_file=" + self.identifier]
        for identifier in ("../outside.csv", "/etc/passwd", "license_planner/output/../x.csv",
                           self.filename):
            queries.append("schedule_csv_file=" + quote(identifier, safe=""))
        for query in queries:
            with self.subTest(query=query):
                (status, result), read, listed = self.call_with_file_spies("/study-plans?" + query)
                self.assertEqual((status, result["error"]["code"]), (422, "INVALID_INPUT"))
                self.assertEqual((read, listed), ([], []))

    def symlink(self, link, target):
        """Create a real symlink, skipping only where the platform refuses to."""
        try:
            link.symlink_to(target)
        except (OSError, NotImplementedError) as error:
            self.skipTest(f"cannot create a file symlink here: {error}")
        self.addCleanup(link.unlink, missing_ok=True)

    def test_selected_plan_symlinks_are_rejected_without_reading_targets(self):
        outside_dir = tempfile.TemporaryDirectory()
        self.addCleanup(outside_dir.cleanup)
        outside = Path(outside_dir.name) / "outside.csv"
        outside.write_text(self.original, encoding="utf-8")
        inside = self.root / self.filename
        targets = {"outside the store": outside, "a valid plan in the store": inside}
        before = {path: path.read_bytes() for path in targets.values()}
        for index, (label, target) in enumerate(targets.items()):
            link_name = f"study_plan_20261017T12000000000{index}+0900_abcdef1{index}.csv"
            self.symlink(self.root / link_name, target)
            identifier = "license_planner/output/" + link_name
            with self.subTest(label):
                query = "schedule_csv_file=" + quote(identifier, safe="")
                (status, result), read, listed = self.call_with_file_spies("/study-plans?" + query)
                self.assertEqual((status, result["error"]["code"]), (422, "INVALID_INPUT"))
                self.assertEqual((read, listed), ([], []))
                with patch.object(api_service, "AdjustmentAgent") as agent:
                    status, result = self.call("/schedules/adjust", {**self.payload, "schedule_csv_file": identifier})
                self.assertEqual((status, result["error"]["code"]), (422, "INVALID_INPUT"))
                agent.assert_not_called()
                (status, _), read, _ = self.call_with_file_spies("/study-plans/" + link_name)
                self.assertEqual((status, read), (404, []))
        for path, content in before.items():
            self.assertEqual(path.read_bytes(), content)

    def test_symlinked_exam_metadata_is_a_storage_error_without_reading_it(self):
        outside_dir = tempfile.TemporaryDirectory()
        self.addCleanup(outside_dir.cleanup)
        outside = Path(outside_dir.name) / "outside.meta.json"
        outside.write_text('{"exam_date":"2026/12/31/23/00"}', encoding="utf-8")
        in_store = self.metadata_path(self.store.create(self.original, OTHER_EXAM))
        targets = {"outside the store": outside, "another plan's metadata": in_store}
        before = {path: path.read_bytes() for path in targets.values()}
        original_read = Path.read_bytes
        self.metadata.unlink()
        for label, target in targets.items():
            with self.subTest(label):
                self.metadata.unlink(missing_ok=True)
                self.symlink(self.metadata, target)
                read = []
                def spy_read(file):
                    read.append(file.name)
                    return original_read(file)
                with patch.object(api_service, "AdjustmentAgent") as agent, \
                        patch.object(Path, "read_bytes", spy_read):
                    status, result = self.call("/schedules/adjust", self.payload)
                self.assertEqual((status, result["error"]["code"]), (500, "STORAGE_ERROR"))
                agent.assert_not_called()
                self.assertEqual(read, [self.filename])
                self.assertTrue(self.metadata.is_symlink())
        for path, content in before.items():
            self.assertEqual(path.read_bytes(), content)
        self.assertEqual(self.store.download(self.filename)[0], self.original)

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

    def test_agent_loop_study_move_persists_without_post_success_model_request(self):
        other = self.store.create(self.original, OTHER_EXAM)
        profile = self.root / "profile.json"
        profile.write_text(json.dumps({"tuesday": [{"start": "09:00", "end": "11:00"}]}), encoding="utf-8")
        profile_bytes = profile.read_bytes()
        del self.payload["weekly_availability"]
        del self.payload["exam_date"]
        agent = scripted_agent(model_call("inspect_schedule", schedule_id="study-1"),
                               model_call("inspect_topic_schedule", topic="Functions"),
                               model_call("reschedule_failed_study", schedule_id="study-1"),
                               RuntimeError("provider failure after success"))
        with patch.object(api_service, "AdjustmentAgent", agent), \
                patch.object(api_service, "load_adjuster_availability",
                             lambda: load_adjuster_availability(profile)):
            status, result = self.call("/schedules/adjust", self.payload)
        self.assertEqual(status, 200, result)
        self.assertEqual(len(agent.script), 1)
        target = next(r for r in result["schedules"] if r["schedule_id"] == "study-1")
        self.assertEqual(target["start_date"], "2026/10/20/09/00")
        self.assertEqual(self.store.download(self.filename)[0], result["schedule_csv"])
        self.assertEqual(self.call("/study-plans/" + self.filename), (200, result["schedule_csv"]))
        self.assertEqual(self.store.download(other.rsplit("/", 1)[1])[0], self.original)
        self.assertEqual(profile.read_bytes(), profile_bytes)
        self.assertEqual(self.metadata.read_bytes(), self.metadata_bytes)

    def test_agent_loop_cancelling_the_only_row_persists_a_header_only_plan(self):
        rows = [copy.deepcopy(ROWS[1])]
        identifier = self.store.create(render_schedule_csv(parse_schedule_rows(rows)), EXAM)
        agent = scripted_agent(model_call("inspect_schedule", schedule_id="external-2"),
                               model_call("remove_cancelled_external_schedule", schedule_id="external-2"),
                               RuntimeError("provider failure after success"))
        payload = {**self.payload, "schedule_csv_file": identifier,
                   "schedule_id": "external-2", "schedules": rows}
        with patch.object(api_service, "AdjustmentAgent", agent):
            status, result = self.call("/schedules/adjust", payload)
        self.assertEqual(status, 200, result)
        self.assertEqual(len(agent.script), 1)
        self.assertEqual((result["action"], result["schedules"]), ("cancelled_external", []))
        self.assertEqual(result["schedule_csv"].strip(), "schedule_id,start_date,end_date,topic")
        self.assertEqual(self.store.download(identifier.rsplit("/", 1)[1])[0], result["schedule_csv"])
        self.assertEqual(self.store.download(self.filename)[0], self.original)

    def test_agent_loop_without_accepted_outcome_saves_nothing(self):
        scripts = {
            "text claim": (model_call("inspect_schedule", schedule_id="study-1"),
                           model_text("study-1 was moved.")),
            "failed mutation": (model_call("inspect_schedule", schedule_id="study-1"),
                                model_call("reschedule_failed_study", schedule_id="study-1"),
                                model_text("Done.")),
            "inspection only": tuple(model_call("inspect_schedule", schedule_id="study-1")
                                     for _ in range(AGENT_MAX_STEPS)),
        }
        for name, replies in scripts.items():
            with self.subTest(name), patch.object(api_service, "AdjustmentAgent",
                                                  scripted_agent(*replies)):
                status, result = self.call("/schedules/adjust", self.payload)
                self.assertEqual((status, result["error"]["code"]), (502, "UPSTREAM_ERROR"))
                self.assertEqual(self.store.download(self.filename)[0], self.original)


MANUAL_ROWS = [
    {"schedule_id": "study-1", "start_date": "2026/11/16/18/00", "end_date": "2026/11/16/19/00",
     "topic": "함수와 데이터 관리"},
    {"schedule_id": "study-2", "start_date": "2026/11/18/18/00", "end_date": "2026/11/18/19/00",
     "topic": "함수와 데이터 관리"},
    {"schedule_id": "external-3", "start_date": "2026/11/17/12/00", "end_date": "2026/11/17/13/00",
     "topic": ""},
]
MANUAL_CONTEXT = {
    "weekly_availability": {"monday": [{"start": "18:00", "duration_minutes": 120}],
                            "tuesday": [{"start": "18:00", "duration_minutes": 60}],
                            "wednesday": [{"start": "18:00", "duration_minutes": 60}]},
    "availability_overrides": {"2026/11/23": {}},
}
MANUAL_PREPARATION = datetime(2026, 11, 16, 0, 0)
MANUAL_EXAM = datetime(2026, 12, 7, 0, 0)
FROZEN_NOW = datetime(2026, 11, 16, 12, 0)
REAL_KOREA_MINUTE = api_service.current_korea_minute


class NoAgent:
    """Fails the test if any adjustment agent is constructed."""
    constructed = 0

    def __init__(self, *args, **kwargs):
        NoAgent.constructed += 1
        raise AssertionError("manual mode must not construct an adjustment agent")


class RetainedContextAndManualMoveTests(unittest.TestCase):
    """Plans keep their accepted context; manual moves are deterministic and typed."""

    def setUp(self):
        from study_availability import parse_availability_context
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name) / "plans"
        self.store = StudyPlanStore(self.root)
        self.profile = Path(self.temp.name) / "profile.json"
        self.profile.write_text(json.dumps({"thursday": [{"start": "09:00", "end": "11:00"}]}), encoding="utf-8")
        self.profile_bytes = self.profile.read_bytes()
        self.context = parse_availability_context(MANUAL_CONTEXT)
        self.csv = render_schedule_csv(parse_schedule_rows(MANUAL_ROWS))
        self.identifier = self.store.create(self.csv, MANUAL_EXAM, preparation_start=MANUAL_PREPARATION,
                                            availability=self.context)
        self.other = self.store.create(self.csv, MANUAL_EXAM, preparation_start=MANUAL_PREPARATION,
                                       availability=parse_availability_context({"friday": [
                                           {"start": "07:00", "duration_minutes": 30}]}))
        self.before = self.files()
        NoAgent.constructed = 0
        for name, value in [("STUDY_PLAN_OUTPUT_DIR", self.root), ("AdjustmentAgent", NoAgent),
                            ("current_korea_minute", lambda: FROZEN_NOW),
                            ("load_adjuster_availability", lambda: load_adjuster_availability(self.profile))]:
            patcher = patch.object(api_service, name, value)
            patcher.start()
            self.addCleanup(patcher.stop)
        handler = make_handler(DEFAULT_API_CONFIG)
        handler.log_message = lambda *args: None
        self.server = ThreadingHTTPServer(("127.0.0.1", 0), handler)
        thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        thread.start()
        self.addCleanup(lambda: (self.server.shutdown(), self.server.server_close(), thread.join(timeout=3)))

    call = PlanUpdateTests.call

    def files(self):
        return {path.name: path.read_bytes() for path in self.root.iterdir()} | {"profile": self.profile.read_bytes()}

    def metadata(self, identifier):
        return self.root / (Path(identifier.rsplit("/", 1)[1]).stem + ".meta.json")

    def manual(self, new_start, **fields):
        return {"mode": "manual", "schedule_csv_file": self.identifier, "schedule_id": "study-1",
                "schedules": copy.deepcopy(MANUAL_ROWS), "new_start_date": new_start, **fields}

    def target(self, result):
        return next(row for row in result["schedules"] if row["schedule_id"] == "study-1")

    def assert_rejected(self, payload, status, code):
        response_status, result = self.call("/schedules/adjust", payload)
        self.assertEqual((response_status, result["error"]["code"]), (status, code), result)
        self.assertEqual(self.files(), self.before)
        return result

    # Retained context, automatic mode

    def test_generation_context_is_retained_and_returned_in_listing(self):
        status, listing = self.call("/study-plans?schedule_csv_file=" + quote(self.identifier, safe=""))
        self.assertEqual(status, 200)
        snapshot = listing["plans"][0]["availability_snapshot"]
        self.assertEqual(snapshot["weekly_availability"]["monday"],
                         [{"start": "18:00", "duration_minutes": 120, "end": "20:00"}])
        self.assertEqual(snapshot["availability_overrides"]["2026/11/23"]["monday"], [])
        stored = json.loads(self.metadata(self.identifier).read_text(encoding="utf-8"))
        self.assertEqual(stored["version"], 2)
        self.assertEqual(stored["preparation_start"], "2026/11/16/00/00")
        self.assertNotIn("end", stored["availability_snapshot"]["weekly_availability"]["monday"][0])

    def test_automatic_move_uses_retained_context_before_shared_profile(self):
        with patch.object(api_service, "AdjustmentAgent", ToolAgent):
            payload = {"schedule_csv_file": self.identifier, "schedule_id": "study-2",
                       "schedules": copy.deepcopy(MANUAL_ROWS)}
            status, result = self.call("/schedules/adjust", payload)
        self.assertEqual(status, 200, result)
        moved = next(row for row in result["schedules"] if row["schedule_id"] == "study-2")
        # Week of Nov 23 is an explicit empty week, so the next base Monday is Nov 30.
        self.assertEqual(moved["start_date"], "2026/11/30/18/00")
        self.assertEqual(result["availability_snapshot"]["availability_overrides"], {
            "2026/11/23": {day: [] for day in ("monday", "tuesday", "wednesday", "thursday",
                                               "friday", "saturday", "sunday")}})
        self.assertEqual(self.profile.read_bytes(), self.profile_bytes)
        self.assertEqual(self.metadata(self.identifier).read_bytes(), self.before[self.metadata(self.identifier).name])

    def test_explicit_base_suppresses_retained_overrides_and_overrides_alone_replace_them(self):
        cases = [
            ({"weekly_availability": {"monday": [{"start": "20:00", "duration_minutes": 60}]}},
             "2026/11/23/20/00"),
            ({"availability_overrides": {}}, "2026/11/23/18/00"),
            ({"availability_overrides": {"2026/11/23": {"tuesday": [{"start": "21:00", "duration_minutes": 60}]}}},
             "2026/11/24/21/00"),
        ]
        for fields, expected in cases:
            with self.subTest(fields=fields), patch.object(api_service, "AdjustmentAgent", ToolAgent):
                payload = {"schedule_id": "study-2", "schedules": copy.deepcopy(MANUAL_ROWS),
                           "exam_date": "2026/12/07/00/00", "schedule_csv_file": self.identifier, **fields}
                payload["schedules"][1]["start_date"] = "2026/11/22/18/00"
                payload["schedules"][1]["end_date"] = "2026/11/22/19/00"
                del payload["schedule_csv_file"]
                with patch.object(api_service, "load_adjuster_availability", lambda: self.context):
                    status, result = self.call("/schedules/adjust", payload)
                self.assertEqual(status, 200, result)
                moved = next(row for row in result["schedules"] if row["schedule_id"] == "study-2")
                self.assertEqual(moved["start_date"], expected)
                self.assertNotIn("availability_snapshot", result)
        self.assertEqual(self.files(), self.before)

    def test_old_plan_uses_shared_profile_and_invalid_context_is_rejected(self):
        legacy = self.store.create(self.csv, MANUAL_EXAM)
        legacy_metadata = self.metadata(legacy).read_bytes()
        with patch.object(api_service, "AdjustmentAgent", ToolAgent):
            status, result = self.call("/schedules/adjust", {
                "schedule_csv_file": legacy, "schedule_id": "study-2", "schedules": copy.deepcopy(MANUAL_ROWS)})
        self.assertEqual(status, 200, result)
        self.assertEqual(next(r for r in result["schedules"] if r["schedule_id"] == "study-2")["start_date"],
                         "2026/11/19/09/00")
        self.assertNotIn("availability_snapshot", result)
        self.assertEqual(self.metadata(legacy).read_bytes(), legacy_metadata)
        self.before = self.files()
        base = {"schedule_csv_file": self.identifier, "schedule_id": "study-2", "schedules": copy.deepcopy(MANUAL_ROWS)}
        for fields in ({"weekly_availability": None}, {"availability_overrides": None},
                       {"weekly_availability": {}}, {"weekly_availability": {}, "availability_overrides": {}},
                       {"availability_overrides": {"2026/11/24": {}}},
                       {"weekly_availability": {"monday": [{"start": "23:00", "duration_minutes": 90}],
                                                "tuesday": [{"start": "00:15", "duration_minutes": 60}]}}):
            with self.subTest(fields=fields):
                self.assert_rejected({**base, **fields}, 422, "INVALID_INPUT")

    def test_malformed_retained_context_blocks_both_modes(self):
        metadata = self.metadata(self.identifier)
        record = json.loads(metadata.read_text(encoding="utf-8"))
        record["availability_snapshot"]["weekly_availability"]["monday"].append(
            {"start": "19:00", "duration_minutes": 30})
        metadata.write_text(json.dumps(record), encoding="utf-8")
        self.before = self.files()
        self.assert_rejected(self.manual("2026/11/17/18/00"), 500, "STORAGE_ERROR")
        with patch.object(api_service, "AdjustmentAgent", ToolAgent):
            self.assert_rejected({"schedule_csv_file": self.identifier, "schedule_id": "study-1",
                                  "schedules": copy.deepcopy(MANUAL_ROWS)}, 500, "STORAGE_ERROR")
        status, listing = self.call("/study-plans")
        self.assertEqual(status, 200)
        selected = next(p for p in listing["plans"] if p["schedule_csv_file"] == self.identifier)
        self.assertNotIn("availability_snapshot", selected)
        self.assertIn("availability_snapshot",
                      next(p for p in listing["plans"] if p["schedule_csv_file"] == self.other))

    # Manual mode

    def test_manual_move_to_a_free_window_before_the_automatic_order_bound(self):
        status, result = self.call("/schedules/adjust", self.manual("2026/11/17/18/00"))
        self.assertEqual(status, 200, result)
        self.assertEqual(self.target(result), {**MANUAL_ROWS[0], "start_date": "2026/11/17/18/00",
                                               "end_date": "2026/11/17/19/00"})
        self.assertCountEqual([r for r in result["schedules"] if r["schedule_id"] != "study-1"], MANUAL_ROWS[1:])
        self.assertEqual((result["action"], result["target_schedule_id"], result["tool_calls"]),
                         ("rescheduled_study", "study-1", []))
        self.assertEqual(result["agent_summary"], "선택한 공부 일정을 11월 17일 18:00–19:00로 옮겼어요.")
        self.assertEqual(result["schedule_csv_file"], self.identifier)
        self.assertEqual(result["availability_snapshot"]["weekly_availability"]["tuesday"],
                         [{"start": "18:00", "duration_minutes": 60, "end": "19:00"}])
        self.assertEqual(NoAgent.constructed, 0)
        restarted = StudyPlanStore(self.root)
        self.assertEqual(restarted.download(self.identifier.rsplit("/", 1)[1])[0], result["schedule_csv"])
        after = self.files()
        csv_name = self.identifier.rsplit("/", 1)[1]
        self.assertEqual({k: v for k, v in after.items() if k != csv_name},
                         {k: v for k, v in self.before.items() if k != csv_name})

    def test_outside_window_requires_explicit_confirmation(self):
        for flag in ({}, {"confirm_outside_availability": False}):
            with self.subTest(flag=flag):
                result = self.assert_rejected(self.manual("2026/11/17/14/00", **flag), 422, "OUTSIDE_STUDY_WINDOWS")
                self.assertEqual(result["error"]["message"],
                                 "The chosen time is outside the study windows. Confirm this one-time exception.")
        status, result = self.call("/schedules/adjust",
                                   self.manual("2026/11/17/14/00", confirm_outside_availability=True))
        self.assertEqual(status, 200, result)
        self.assertEqual(self.target(result)["start_date"], "2026/11/17/14/00")
        self.assertEqual(self.metadata(self.identifier).read_bytes(),
                         self.before[self.metadata(self.identifier).name])
        self.assertEqual(NoAgent.constructed, 0)

    def test_external_overlap_needs_separate_consent_and_exact_http_readback(self):
        for fields in ({}, {"confirm_external_overlap": False}, {"confirm_outside_availability": True}):
            self.assert_rejected(self.manual("2026/11/17/12/30", **fields), 422, "EXTERNAL_EVENT_OVERLAP")
        self.assert_rejected(self.manual("2026/11/17/12/30", confirm_external_overlap=True),
                             422, "OUTSIDE_STUDY_WINDOWS")
        status, result = self.call("/schedules/adjust", self.manual(
            "2026/11/17/12/30", confirm_external_overlap=True, confirm_outside_availability=True))
        self.assertEqual(status, 200, result)
        self.assertEqual(self.target(result), {**MANUAL_ROWS[0], "start_date": "2026/11/17/12/30",
                                               "end_date": "2026/11/17/13/30"})
        self.assertCountEqual([r for r in result["schedules"] if r["schedule_id"] != "study-1"], MANUAL_ROWS[1:])
        status, listing = self.call("/study-plans?schedule_csv_file=" + quote(self.identifier, safe=""))
        self.assertEqual(status, 200, listing)
        self.assertCountEqual(listing["plans"][0]["schedules"], result["schedules"])
        self.assertEqual(result["schedule_csv_file"], self.identifier)
        csv_name = self.identifier.rsplit("/", 1)[1]
        self.assertEqual({k: v for k, v in self.files().items() if k != csv_name},
                         {k: v for k, v in self.before.items() if k != csv_name})
        self.assertEqual((NoAgent.constructed, result["tool_calls"]), (0, []))

    def test_external_consent_does_not_waive_bounds_or_study_overlap(self):
        for start, code in (("2026/11/16/11/59", "MANUAL_MOVE_IN_PAST"),
                            ("2026/12/06/23/30", "EXAM_BOUND_EXCEEDED"),
                            ("2026/11/18/18/30", "STUDY_SESSION_OVERLAP")):
            self.assert_rejected(self.manual(start, confirm_external_overlap=True,
                                              confirm_outside_availability=True), 422, code)

    def test_external_consent_is_boolean_and_manual_only(self):
        for value in (None, 0, 1, "true", [], {}):
            self.assert_rejected(self.manual("2026/11/17/12/30", confirm_external_overlap=value),
                                 422, "INVALID_INPUT")
        for mode in (None, "automatic", "remaining"):
            for value in (False, True):
                payload = self.manual("2026/11/17/12/30", confirm_external_overlap=value)
                if mode is None:
                    payload.pop("mode")
                else:
                    payload["mode"] = mode
                self.assert_rejected(payload, 422, "INVALID_INPUT")
        self.assertEqual(NoAgent.constructed, 0)

    def test_external_consent_preserves_stale_missing_and_storage_authority(self):
        payload = self.manual("2026/11/17/12/30", confirm_external_overlap=True,
                              confirm_outside_availability=True)
        stale = copy.deepcopy(payload)
        stale["schedules"][1]["topic"] = "changed"
        self.assert_rejected(stale, 409, "PLAN_CONFLICT")
        missing = {**payload, "schedule_csv_file": "license_planner/output/study_plan_20990101T000000_deadbeef.csv"}
        self.assert_rejected(missing, 404, "PLAN_NOT_FOUND")
        self.metadata(self.identifier).write_text("{broken", encoding="utf-8")
        self.before = self.files()
        self.assert_rejected(payload, 500, "STORAGE_ERROR")
        self.assertEqual(NoAgent.constructed, 0)

    def test_hard_rejections_ignore_confirmation(self):
        cases = [("2026/11/16/11/59", "MANUAL_MOVE_IN_PAST"),
                 ("2026/12/06/23/30", "EXAM_BOUND_EXCEEDED"),
                 ("2026/11/18/18/30", "STUDY_SESSION_OVERLAP"),
                 ("2026/11/17/12/30", "EXTERNAL_EVENT_OVERLAP")]
        for start, code in cases:
            for confirm in (False, True):
                with self.subTest(code=code, confirm=confirm):
                    self.assert_rejected(self.manual(start, confirm_outside_availability=confirm), 422, code)
        with patch.object(api_service, "current_korea_minute", lambda: datetime(2026, 11, 1, 9, 0)):
            self.assert_rejected(self.manual("2026/11/15/20/00", confirm_outside_availability=True),
                                 422, "BEFORE_PREPARATION_START")
        self.assertEqual(NoAgent.constructed, 0)

    def test_adjacent_and_exam_end_boundaries_are_accepted(self):
        for start in ("2026/11/17/13/00", "2026/12/06/23/00", "2026/11/16/12/00"):
            with self.subTest(start=start):
                rows = json.loads(json.dumps(MANUAL_ROWS))
                current = self.store.snapshot(self.identifier).entries
                rows = [{"schedule_id": e.schedule_id, "start_date": e.start_datetime.strftime("%Y/%m/%d/%H/%M"),
                         "end_date": e.end_datetime.strftime("%Y/%m/%d/%H/%M"), "topic": e.topic}
                        for e in current]
                status, result = self.call("/schedules/adjust", {
                    **self.manual(start, confirm_outside_availability=True), "schedules": rows})
                self.assertEqual(status, 200, result)
                self.assertEqual(self.target(result)["start_date"], start)

    def test_malformed_manual_requests_are_invalid_input(self):
        variants = [
            {**self.manual("2026/11/17/18/00"), "mode": None},
            {**self.manual("2026/11/17/18/00"), "mode": "assisted"},
            {k: v for k, v in self.manual("2026/11/17/18/00").items() if k != "new_start_date"},
            self.manual("2026/11/17/18/0"),
            self.manual(None),
            self.manual("2026/11/17/18/00", confirm_outside_availability="yes"),
            self.manual("2026/11/17/18/00", confirm_outside_availability=None),
            {k: v for k, v in self.manual("2026/11/17/18/00").items() if k != "schedule_csv_file"},
            {**self.manual("2026/11/17/18/00"), "schedule_id": "external-3"},
            self.manual("2026/11/17/18/00", exam_date="2026/12/08/00/00"),
            self.manual("2026/11/17/18/00", preparation_start="2026/11/10/00/00"),
        ]
        for payload in variants:
            with self.subTest(payload=payload):
                self.assert_rejected(payload, 422, "INVALID_INPUT")
        self.assertEqual(NoAgent.constructed, 0)

    def test_stale_missing_and_changed_metadata_precede_manual_rules(self):
        stale = self.manual("2026/11/16/08/00", confirm_outside_availability=True)
        stale["schedules"][1]["topic"] = "changed"
        self.assert_rejected(stale, 409, "PLAN_CONFLICT")
        missing = self.manual("2026/11/17/18/00")
        missing["schedule_csv_file"] = "license_planner/output/study_plan_20990101T000000_deadbeef.csv"
        self.assert_rejected(missing, 404, "PLAN_NOT_FOUND")
        original = api_service.place_study_entry_manually
        metadata = self.metadata(self.identifier)

        def change_metadata(*args, **kwargs):
            metadata.write_bytes(metadata.read_bytes() + b" ")
            return original(*args, **kwargs)
        with patch.object(api_service, "place_study_entry_manually", change_metadata):
            status, result = self.call("/schedules/adjust", self.manual("2026/11/17/18/00"))
        self.assertEqual((status, result["error"]["code"]), (409, "PLAN_CONFLICT"))
        csv_name = self.identifier.rsplit("/", 1)[1]
        self.assertEqual((self.root / csv_name).read_bytes(), self.before[csv_name])
        self.assertEqual(NoAgent.constructed, 0)

    def test_legacy_plans_keep_exam_authority_and_need_a_supplied_preparation_bound(self):
        exam_only = self.store.create(self.csv, MANUAL_EXAM)
        csv_only = self.store.create(self.csv)
        self.before = self.files()
        for identifier, fields, message in [
                (exam_only, {}, "preparation_start is required"),
                (exam_only, {"preparation_start": "2026/11/16"}, "preparation_start"),
                (exam_only, {"preparation_start": "2026/12/07/00/00"}, "before exam_date"),
                (csv_only, {"preparation_start": "2026/11/16/00/00"}, "Create a new plan"),
                (csv_only, {"preparation_start": "2026/11/16/00/00", "exam_date": "2026/12/07/00/00"},
                 "Create a new plan")]:
            with self.subTest(fields=fields, identifier=identifier):
                result = self.assert_rejected({**self.manual("2026/11/17/18/00", **fields),
                                               "schedule_csv_file": identifier}, 422, "INVALID_INPUT")
                self.assertIn(message, result["error"]["message"])
        exam_only_metadata = self.metadata(exam_only).read_bytes()
        status, result = self.call("/schedules/adjust", {
            **self.manual("2026/11/17/14/00", preparation_start="2026/11/16/00/00",
                          confirm_outside_availability=True), "schedule_csv_file": exam_only})
        self.assertEqual(status, 200, result)
        self.assertNotIn("availability_snapshot", result)
        self.assertEqual(self.metadata(exam_only).read_bytes(), exam_only_metadata)
        self.assertEqual(NoAgent.constructed, 0)

    def test_current_korea_minute_is_truncated_utc_plus_nine(self):
        from datetime import timezone, timedelta
        value = REAL_KOREA_MINUTE()
        expected = datetime.now(timezone.utc).replace(tzinfo=None) + timedelta(hours=9)
        self.assertEqual((value.second, value.microsecond), (0, 0))
        self.assertLess(abs(value - expected), timedelta(minutes=2))


if __name__ == "__main__":
    unittest.main()
