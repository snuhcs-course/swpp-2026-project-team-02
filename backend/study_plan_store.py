"""Generated-plan storage shared by generation, downloads and adjustment.

AI-assisted implementation with Codex, 2026-10-17; execution verification pending.
"""
from __future__ import annotations

import csv
import hashlib
import io
import json
import os
import re
import tempfile
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path
from threading import Lock
from uuid import uuid4

from schedule_adjuster.csv_io import parse_schedule_csv
from schedule_adjuster.models import ScheduleEntry
from study_availability import AvailabilityContext, context_payload, parse_availability_payload, make_context

PLAN_PREFIX = "license_planner/output/"
STUDY_PLAN_FILENAME = re.compile(r"study_plan_[0-9A-Fa-fT+_-]+\.csv")
MAX_STUDY_PLAN_CSV_FILES = 100
METADATA_SUFFIX = ".meta.json"
EXAM_DATE_FORMAT = "%Y/%m/%d/%H/%M"
CONTEXT_METADATA_VERSION = 2
_CONTEXT_METADATA_KEYS = {"version", "exam_date", "preparation_start", "availability_snapshot"}
STUDY_PLAN_OUTPUT_LOCK = Lock()


class PlanNotFoundError(Exception):
    pass


class PlanConflictError(Exception):
    pass


class PlanStorageError(Exception):
    pass


@dataclass(frozen=True)
class PlanSnapshot:
    identifier: str
    digest: bytes
    entries: tuple[ScheduleEntry, ...]
    # None means the plan has no stored exam metadata (a legacy plan).
    exam_date: datetime | None = None
    metadata_digest: bytes | None = None
    # Only plans created with retained availability context store these two.
    preparation_start: datetime | None = None
    availability: AvailabilityContext | None = None


@dataclass(frozen=True)
class PlanMetadata:
    exam_date: datetime
    preparation_start: datetime | None = None
    availability: AvailabilityContext | None = None


def plan_fields(identifier: str) -> dict[str, str]:
    return {"schedule_csv_file": identifier,
            "schedule_csv_url": f"/api/v1/study-plans/{identifier.removeprefix(PLAN_PREFIX)}"}


class StudyPlanStore:
    """Single-process optimistic updates; never hold the lock during LLM calls."""

    def __init__(self, directory: Path):
        self.directory = directory

    def _resolve(self, identifier: str) -> Path:
        if not isinstance(identifier, str) or not identifier.startswith(PLAN_PREFIX):
            raise ValueError("schedule_csv_file must be a server-issued plan identifier")
        filename = identifier[len(PLAN_PREFIX):]
        if not STUDY_PLAN_FILENAME.fullmatch(filename):
            raise ValueError("Invalid schedule_csv_file")
        path = self.directory / filename
        if path.is_symlink() or path.resolve().parent != self.directory.resolve():
            raise ValueError("Invalid schedule_csv_file")
        return path

    def _read(self, path: Path) -> bytes:
        try:
            return path.read_bytes()
        except FileNotFoundError as error:
            raise PlanNotFoundError("Selected study plan no longer exists") from error
        except OSError as error:
            raise PlanStorageError("Could not read study plan") from error

    def _parse(self, content: bytes) -> tuple[ScheduleEntry, ...]:
        try:
            return parse_schedule_csv(content.decode("utf-8"))
        except (ValueError, UnicodeError, csv.Error) as error:
            raise PlanStorageError("Stored study plan contains invalid schedule data") from error

    def _metadata_path(self, path: Path) -> Path:
        """Derive the sidecar only from an already validated plan path."""
        metadata = path.with_name(path.stem + METADATA_SUFFIX)
        if metadata.is_symlink() or metadata.resolve().parent != self.directory.resolve():
            raise PlanStorageError("Stored study plan metadata is invalid")
        return metadata

    def _read_metadata(self, path: Path) -> bytes | None:
        """Return sidecar bytes, or None when the plan has no metadata."""
        try:
            return self._metadata_path(path).read_bytes()
        except FileNotFoundError:
            return None
        except OSError as error:
            raise PlanStorageError("Could not read study plan metadata") from error

    @staticmethod
    def _parse_timestamp(value: object, field: str) -> datetime:
        if not isinstance(value, str):
            raise ValueError(f"{field} must be a string")
        parsed = datetime.strptime(value, EXAM_DATE_FORMAT)
        if parsed.strftime(EXAM_DATE_FORMAT) != value:
            raise ValueError(f"{field} is not in canonical form")
        return parsed

    @classmethod
    def _parse_metadata(cls, content: bytes) -> PlanMetadata:
        """Read an exam-only sidecar or a version-2 sidecar with retained context."""
        try:
            data = json.loads(content.decode("utf-8"))
            if isinstance(data, dict) and data.keys() == {"exam_date"}:
                return PlanMetadata(cls._parse_timestamp(data["exam_date"], "exam_date"))
            if (not isinstance(data, dict) or data.keys() != _CONTEXT_METADATA_KEYS
                    or type(data["version"]) is not int or data["version"] != CONTEXT_METADATA_VERSION):
                raise ValueError("unsupported study plan metadata")
            exam_date = cls._parse_timestamp(data["exam_date"], "exam_date")
            preparation_start = cls._parse_timestamp(data["preparation_start"], "preparation_start")
            if preparation_start >= exam_date:
                raise ValueError("preparation_start must be before exam_date")
            snapshot = data["availability_snapshot"]
            if not isinstance(snapshot, dict) or snapshot.keys() != {"weekly_availability",
                                                                    "availability_overrides"}:
                raise ValueError("availability_snapshot must contain base and overrides")
            base, overrides = parse_availability_payload(snapshot)
            return PlanMetadata(exam_date, preparation_start, make_context(base, overrides or ()))
        except (ValueError, UnicodeError) as error:
            raise PlanStorageError("Stored study plan metadata is invalid") from error

    def snapshot(self, identifier: str) -> PlanSnapshot:
        with STUDY_PLAN_OUTPUT_LOCK:
            path = self._resolve(identifier)
            content = self._read(path)
            entries = self._parse(content)
            metadata = self._read_metadata(path)
            if metadata is None:
                return PlanSnapshot(identifier, hashlib.sha256(content).digest(), entries)
            parsed = self._parse_metadata(metadata)
            return PlanSnapshot(identifier, hashlib.sha256(content).digest(), entries,
                                parsed.exam_date, hashlib.sha256(metadata).digest(),
                                parsed.preparation_start, parsed.availability)

    def download(self, filename: str) -> tuple[str, str]:
        with STUDY_PLAN_OUTPUT_LOCK:
            try:
                path = self._resolve(PLAN_PREFIX + filename)
            except ValueError as error:
                raise FileNotFoundError("Study plan CSV not found") from error
            try:
                return self._read(path).decode("utf-8"), filename
            except PlanNotFoundError as error:
                raise FileNotFoundError("Study plan CSV not found") from error
            except UnicodeError as error:
                raise PlanStorageError("Stored study plan is not UTF-8") from error

    def _retained_availability(self, path: Path) -> dict | None:
        """Response form of a plan's retained context; None when absent or unreadable.

        Listing stays usable for CSV-only, exam-only and damaged sidecars.
        """
        try:
            metadata = self._read_metadata(path)
            parsed = None if metadata is None else self._parse_metadata(metadata)
        except PlanStorageError:
            return None
        if parsed is None or parsed.availability is None:
            return None
        return context_payload(parsed.availability, include_end=True)

    def _plan_item(self, identifier: str, path: Path, content: bytes) -> dict:
        self._parse(content)
        item = {**plan_fields(identifier),
                "schedules": list(csv.DictReader(io.StringIO(content.decode("utf-8"))))}
        availability = self._retained_availability(path)
        if availability is not None:
            item["availability_snapshot"] = availability
        return item

    def list_plans(self, identifier: str | None = None) -> list[dict]:
        with STUDY_PLAN_OUTPUT_LOCK:
            try:
                if identifier is not None:
                    # Read only the selected file so damage elsewhere cannot block it.
                    path = self._resolve(identifier)
                    try:
                        content = self._read(path)
                    except PlanNotFoundError:
                        return []
                    return [self._plan_item(identifier, path, content)]
                if not self.directory.exists():
                    return []
                paths = [p for p in self.directory.iterdir()
                         if STUDY_PLAN_FILENAME.fullmatch(p.name)
                         and p.is_file() and not p.is_symlink()]
                paths.sort(key=lambda p: (p.stat().st_mtime_ns, p.name), reverse=True)
                plans = []
                for path in paths:
                    resolved = self._resolve(PLAN_PREFIX + path.name)
                    content = self._read(resolved)
                    plans.append(self._plan_item(PLAN_PREFIX + path.name, resolved, content))
                return plans
            except OSError as error:
                raise PlanStorageError("Could not list study plans") from error

    def _atomic_write(self, path: Path, content: str) -> None:
        temporary = None
        try:
            with tempfile.NamedTemporaryFile(mode="w", encoding="utf-8", newline="",
                                             dir=self.directory, suffix=".tmp", delete=False) as stream:
                temporary = Path(stream.name)
                stream.write(content)
                stream.flush()
                os.fsync(stream.fileno())
            os.replace(temporary, path)
        except OSError as error:
            raise PlanStorageError("Could not save study plan") from error
        finally:
            if temporary is not None:
                try:
                    temporary.unlink(missing_ok=True)
                except OSError:
                    pass

    def create(self, content: str, exam_date: datetime | None = None, *,
               preparation_start: datetime | None = None,
               availability: AvailabilityContext | None = None) -> str:
        """Save a new plan; API generation always passes its validated exam date.

        With ``preparation_start`` and ``availability`` the sidecar is version 2
        and retains the accepted context; otherwise it is the exam-only form.
        The metadata sidecar is published before the CSV, so a listed plan
        never lacks a deadline or context it was created with.
        """
        if (preparation_start is None) != (availability is None) or (
                exam_date is None and preparation_start is not None):
            raise ValueError("preparation_start and availability require each other and exam_date")
        with STUDY_PLAN_OUTPUT_LOCK:
            try:
                self.directory.mkdir(parents=True, exist_ok=True)
                timestamp = datetime.now().astimezone().strftime("%Y%m%dT%H%M%S%f%z")
                filename = f"study_plan_{timestamp}_{uuid4().hex[:8]}.csv"
                path = self.directory / filename
                if exam_date is not None:
                    metadata = self._metadata_path(path)
                    record: dict = {"exam_date": exam_date.strftime(EXAM_DATE_FORMAT)}
                    if availability is not None:
                        record = {"version": CONTEXT_METADATA_VERSION, **record,
                                  "preparation_start": preparation_start.strftime(EXAM_DATE_FORMAT),
                                  "availability_snapshot": context_payload(availability, include_end=False)}
                    self._atomic_write(metadata, json.dumps(record, ensure_ascii=False, separators=(",", ":")))
                    try:
                        self._atomic_write(path, content)
                    except PlanStorageError:
                        try:
                            metadata.unlink(missing_ok=True)
                        except OSError:
                            pass
                        raise
                else:
                    self._atomic_write(path, content)
                self._prune()
                return PLAN_PREFIX + filename
            except OSError as error:
                raise PlanStorageError("Could not create study plan") from error

    def _prune(self) -> None:
        # Cleanup is best-effort; it shares the write lock with updates.
        try:
            paths = [p for p in self.directory.iterdir()
                     if STUDY_PLAN_FILENAME.fullmatch(p.name) and p.is_file() and not p.is_symlink()]
            paths.sort(key=lambda p: (p.stat().st_mtime_ns, p.name))
            for path in paths[:-MAX_STUDY_PLAN_CSV_FILES]:
                try:
                    path.unlink()
                except OSError:
                    continue  # A surviving plan keeps its metadata.
                try:
                    path.with_name(path.stem + METADATA_SUFFIX).unlink(missing_ok=True)
                except OSError:
                    pass
        except OSError:
            pass

    def update(self, snapshot: PlanSnapshot, content: str) -> None:
        """Replace the CSV only if its bytes and metadata match the snapshot."""
        with STUDY_PLAN_OUTPUT_LOCK:
            path = self._resolve(snapshot.identifier)
            if hashlib.sha256(self._read(path)).digest() != snapshot.digest:
                raise PlanConflictError("The selected plan has changed. Reload it before adjusting.")
            metadata = self._read_metadata(path)
            metadata_digest = None if metadata is None else hashlib.sha256(metadata).digest()
            if metadata_digest != snapshot.metadata_digest:
                raise PlanConflictError("The selected plan has changed. Reload it before adjusting.")
            self._atomic_write(path, content)
