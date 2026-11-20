"""Stored exam-deadline and availability metadata kept beside each generated plan CSV."""
import json
import os
import tempfile
import unittest
from datetime import datetime
from pathlib import Path
from unittest.mock import patch

import study_plan_store
from schedule_adjuster.csv_io import parse_schedule_rows, render_schedule_csv
from study_plan_store import PlanConflictError, PlanStorageError, StudyPlanStore

ROWS = [
    {"schedule_id": "study-1", "start_date": "2026/10/19/18/00",
     "end_date": "2026/10/19/19/00", "topic": "Functions"},
]
EXAM = datetime(2026, 10, 20, 22, 0)


class StoredExamDateTests(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name)
        self.store = StudyPlanStore(self.root)
        self.csv = render_schedule_csv(parse_schedule_rows(ROWS))

    def paths(self, identifier):
        path = self.root / identifier.rsplit("/", 1)[1]
        return path, path.with_name(path.stem + ".meta.json")

    def files(self):
        return sorted(path.name for path in self.root.iterdir())

    def test_new_plan_keeps_exact_deadline_across_store_instances(self):
        identifier = self.store.create(self.csv, EXAM)
        csv_path, metadata = self.paths(identifier)
        self.assertEqual(csv_path.read_text(encoding="utf-8"), self.csv)
        self.assertEqual(metadata.read_text(encoding="utf-8"), '{"exam_date":"2026/10/20/22/00"}')
        snapshot = StudyPlanStore(self.root).snapshot(identifier)
        self.assertEqual(snapshot.exam_date, EXAM)
        self.assertIsNotNone(snapshot.metadata_digest)

    def test_listing_and_download_ignore_metadata_files(self):
        identifier = self.store.create(self.csv, EXAM)
        legacy = self.store.create(self.csv)
        (self.root / "study_plan_orphan.meta.json").write_text("{}", encoding="utf-8")
        self.assertCountEqual([plan["schedule_csv_file"] for plan in self.store.list_plans()],
                              [identifier, legacy])
        self.assertEqual(self.store.download(identifier.rsplit("/", 1)[1])[0], self.csv)

    def test_legacy_plan_has_no_deadline(self):
        identifier = self.store.create(self.csv)
        self.assertEqual(self.files(), [identifier.rsplit("/", 1)[1]])
        snapshot = self.store.snapshot(identifier)
        self.assertIsNone(snapshot.exam_date)
        self.assertIsNone(snapshot.metadata_digest)

    def test_present_but_damaged_metadata_is_a_storage_failure(self):
        identifier = self.store.create(self.csv, EXAM)
        csv_path, metadata = self.paths(identifier)
        damaged = {
            "not json": b"{",
            "not utf-8": b"\xff\xfe",
            "not an object": b'["2026/10/20/22/00"]',
            "extra key": b'{"exam_date":"2026/10/20/22/00","x":1}',
            "wrong type": b'{"exam_date":202610202200}',
            "null": b'{"exam_date":null}',
            "invalid timestamp": b'{"exam_date":"2026/13/20/22/00"}',
            "non-canonical": b'{"exam_date":"2026/10/20/22/0"}',
        }
        for label, content in damaged.items():
            with self.subTest(label):
                metadata.write_bytes(content)
                with self.assertRaises(PlanStorageError):
                    self.store.snapshot(identifier)
                self.assertEqual(metadata.read_bytes(), content)
                self.assertEqual(csv_path.read_text(encoding="utf-8"), self.csv)

    def test_unreadable_metadata_is_not_treated_as_missing(self):
        identifier = self.store.create(self.csv, EXAM)
        _, metadata = self.paths(identifier)
        original_read = Path.read_bytes

        def deny_metadata(path):
            if path == metadata:
                raise PermissionError("denied")
            return original_read(path)

        with patch.object(Path, "read_bytes", deny_metadata), self.assertRaises(PlanStorageError):
            self.store.snapshot(identifier)

    def test_metadata_write_failure_publishes_no_plan(self):
        real_replace = os.replace

        def fail_metadata(source, target):
            if str(target).endswith(".meta.json"):
                raise PermissionError("locked")
            return real_replace(source, target)

        with patch("study_plan_store.os.replace", fail_metadata), self.assertRaises(PlanStorageError):
            self.store.create(self.csv, EXAM)
        self.assertEqual(self.files(), [])

    def test_csv_publication_failure_removes_its_metadata(self):
        real_replace = os.replace

        def fail_csv(source, target):
            if str(target).endswith(".csv"):
                raise PermissionError("locked")
            return real_replace(source, target)

        with patch("study_plan_store.os.replace", fail_csv), self.assertRaises(PlanStorageError):
            self.store.create(self.csv, EXAM)
        self.assertEqual(self.files(), [])
        self.assertEqual(self.store.list_plans(), [])

    def test_update_preserves_metadata_bytes(self):
        identifier = self.store.create(self.csv, EXAM)
        csv_path, metadata = self.paths(identifier)
        before = metadata.read_bytes()
        snapshot = self.store.snapshot(identifier)
        self.store.update(snapshot, "schedule_id,start_date,end_date,topic\n")
        self.assertEqual(metadata.read_bytes(), before)
        self.assertEqual(csv_path.read_text(encoding="utf-8"), "schedule_id,start_date,end_date,topic\n")

    def test_metadata_change_or_removal_after_snapshot_is_a_conflict(self):
        changes = {
            "changed": lambda metadata: metadata.write_text('{"exam_date":"2026/12/31/23/00"}', encoding="utf-8"),
            "removed": lambda metadata: metadata.unlink(),
        }
        for label, change in changes.items():
            with self.subTest(label):
                identifier = self.store.create(self.csv, EXAM)
                csv_path, metadata = self.paths(identifier)
                snapshot = self.store.snapshot(identifier)
                change(metadata)
                after_change = metadata.read_bytes() if metadata.exists() else None
                with self.assertRaises(PlanConflictError):
                    self.store.update(snapshot, "schedule_id,start_date,end_date,topic\n")
                self.assertEqual(csv_path.read_text(encoding="utf-8"), self.csv)
                self.assertEqual(metadata.read_bytes() if metadata.exists() else None, after_change)

    def test_metadata_added_to_legacy_plan_after_snapshot_is_a_conflict(self):
        identifier = self.store.create(self.csv)
        csv_path, metadata = self.paths(identifier)
        snapshot = self.store.snapshot(identifier)
        metadata.write_text('{"exam_date":"2026/10/20/22/00"}', encoding="utf-8")
        with self.assertRaises(PlanConflictError):
            self.store.update(snapshot, "schedule_id,start_date,end_date,topic\n")
        self.assertEqual(csv_path.read_text(encoding="utf-8"), self.csv)

    def test_pruning_removes_metadata_only_with_its_csv(self):
        with patch.object(study_plan_store, "MAX_STUDY_PLAN_CSV_FILES", 2):
            first = self.store.create(self.csv, EXAM)
            second = self.store.create(self.csv, EXAM)
            unrelated = self.root / "unrelated.meta.json"
            unrelated.write_text("{}", encoding="utf-8")
            first_csv, first_meta = self.paths(first)
            os.utime(first_csv, ns=(1, 1))
            third = self.store.create(self.csv, EXAM)
        self.assertFalse(first_csv.exists())
        self.assertFalse(first_meta.exists())
        for identifier in (second, third):
            for path in self.paths(identifier):
                self.assertTrue(path.exists(), path)
        self.assertTrue(unrelated.exists())

    def test_failed_csv_pruning_keeps_that_plans_metadata(self):
        with patch.object(study_plan_store, "MAX_STUDY_PLAN_CSV_FILES", 1):
            first = self.store.create(self.csv, EXAM)
            first_csv, first_meta = self.paths(first)
            os.utime(first_csv, ns=(1, 1))
            real_unlink = Path.unlink

            def refuse_csv(path, missing_ok=False):
                if path == first_csv:
                    raise PermissionError("in use")
                return real_unlink(path, missing_ok=missing_ok)

            with patch.object(Path, "unlink", refuse_csv):
                self.store.create(self.csv, EXAM)
        self.assertTrue(first_csv.exists())
        self.assertEqual(self.store.snapshot(first).exam_date, EXAM)


PREPARATION = datetime(2026, 10, 12, 0, 0)
CONTEXT = {"weekly_availability": {"monday": [{"start": "23:00", "duration_minutes": 90}],
                                   "tuesday": [{"start": "08:00", "end": "09:00"}, {"start": "19:00", "end": "20:00"}]},
           "availability_overrides": {"2026/10/12": {}, "2026/10/19": {"sunday": [{"start": "20:00",
                                                                                     "duration_minutes": 30}]}}}


class RetainedContextMetadataTests(unittest.TestCase):
    """Version-2 sidecars keep each plan's accepted availability context."""

    def setUp(self):
        from study_availability import parse_availability_context
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name)
        self.store = StudyPlanStore(self.root)
        self.csv = render_schedule_csv(parse_schedule_rows(ROWS))
        self.context = parse_availability_context(CONTEXT)

    def create(self, context=None):
        return self.store.create(self.csv, EXAM, preparation_start=PREPARATION, availability=context or self.context)

    def metadata(self, identifier):
        return self.root / (Path(identifier.rsplit("/", 1)[1]).stem + ".meta.json")

    def test_context_round_trips_across_store_instances_in_canonical_form(self):
        identifier = self.create()
        record = json.loads(self.metadata(identifier).read_text(encoding="utf-8"))
        self.assertEqual((record["version"], record["exam_date"], record["preparation_start"]),
                         (2, "2026/10/20/22/00", "2026/10/12/00/00"))
        self.assertEqual(record["availability_snapshot"]["weekly_availability"]["tuesday"],
                         [{"start": "08:00", "duration_minutes": 60}, {"start": "19:00", "duration_minutes": 60}])
        self.assertEqual(record["availability_snapshot"]["availability_overrides"]["2026/10/12"],
                         {day: [] for day in ("monday", "tuesday", "wednesday", "thursday",
                                              "friday", "saturday", "sunday")})
        snapshot = StudyPlanStore(self.root).snapshot(identifier)
        self.assertEqual((snapshot.exam_date, snapshot.preparation_start, snapshot.availability),
                         (EXAM, PREPARATION, self.context))
        listed = StudyPlanStore(self.root).list_plans(identifier)[0]["availability_snapshot"]
        self.assertEqual(listed["weekly_availability"]["monday"],
                         [{"start": "23:00", "duration_minutes": 90, "end": "00:30"}])

    def test_each_plan_keeps_its_own_context(self):
        from study_availability import parse_availability_context
        first = self.create()
        second = self.create(parse_availability_context({"friday": [{"start": "07:00", "duration_minutes": 30}]}))
        self.assertEqual(self.store.snapshot(first).availability, self.context)
        self.assertNotEqual(self.store.snapshot(second).availability, self.context)

    def test_damaged_context_blocks_mutation_but_not_listing_or_download(self):
        identifier = self.create()
        metadata = self.metadata(identifier)
        valid = json.loads(metadata.read_text(encoding="utf-8"))
        damaged = {
            "unknown version": {**valid, "version": 3},
            "boolean version": {**valid, "version": True},
            "missing preparation": {k: v for k, v in valid.items() if k != "preparation_start"},
            "preparation after exam": {**valid, "preparation_start": "2026/10/21/00/00"},
            "null context": {**valid, "availability_snapshot": None},
            "missing overrides": {**valid, "availability_snapshot": {
                "weekly_availability": valid["availability_snapshot"]["weekly_availability"]}},
            "overlapping windows": {**valid, "availability_snapshot": {
                "weekly_availability": {"monday": [{"start": "23:00", "duration_minutes": 90}],
                                        "tuesday": [{"start": "00:15", "duration_minutes": 30}]},
                "availability_overrides": {}}},
            "non-Monday override": {**valid, "availability_snapshot": {
                "weekly_availability": {}, "availability_overrides": {"2026/10/13": {}}}},
        }
        for label, record in damaged.items():
            with self.subTest(label):
                content = json.dumps(record).encode()
                metadata.write_bytes(content)
                with self.assertRaises(PlanStorageError):
                    self.store.snapshot(identifier)
                item = self.store.list_plans(identifier)[0]
                self.assertNotIn("availability_snapshot", item)
                self.assertEqual(self.store.download(identifier.rsplit("/", 1)[1])[0], self.csv)
                self.assertEqual(metadata.read_bytes(), content)

    def test_exam_only_and_csv_only_plans_are_read_without_backfill(self):
        exam_only = self.store.create(self.csv, EXAM)
        csv_only = self.store.create(self.csv)
        before = self.metadata(exam_only).read_bytes()
        snapshot = self.store.snapshot(exam_only)
        self.assertEqual((snapshot.preparation_start, snapshot.availability), (None, None))
        self.store.update(snapshot, self.csv)
        for item in self.store.list_plans():
            self.assertNotIn("availability_snapshot", item)
        self.assertEqual(self.metadata(exam_only).read_bytes(), before)
        self.assertFalse(self.metadata(csv_only).exists())

    def test_failed_context_save_publishes_no_plan(self):
        real_replace = os.replace

        def fail_metadata(source, target):
            if str(target).endswith(".meta.json"):
                raise PermissionError("locked")
            return real_replace(source, target)

        with patch("study_plan_store.os.replace", fail_metadata), self.assertRaises(PlanStorageError):
            self.create()
        self.assertEqual(list(self.root.iterdir()), [])

    def test_context_change_after_snapshot_is_a_conflict(self):
        identifier = self.create()
        snapshot = self.store.snapshot(identifier)
        metadata = self.metadata(identifier)
        record = json.loads(metadata.read_text(encoding="utf-8"))
        record["availability_snapshot"]["availability_overrides"] = {}
        metadata.write_text(json.dumps(record), encoding="utf-8")
        changed = metadata.read_bytes()
        with self.assertRaises(PlanConflictError):
            self.store.update(snapshot, "schedule_id,start_date,end_date,topic\n")
        self.assertEqual(self.store.download(identifier.rsplit("/", 1)[1])[0], self.csv)
        self.assertEqual(metadata.read_bytes(), changed)


if __name__ == "__main__":
    unittest.main()
