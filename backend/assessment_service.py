"""Question bank access, submitted-result validation and per-question reviewed matching."""
from __future__ import annotations

import json
import os
import threading
from collections import OrderedDict
from dataclasses import dataclass
from pathlib import Path
from types import MappingProxyType
from typing import Any, Mapping

import certification_selector.catalog as selector_catalog
from assessment_questionnaire.validation import (
    UNKNOWN_CHOICE, QuestionContentError, check_question_content, check_total, is_nonempty_text,
    is_positive_id, parse_json_bytes, retained_records, select_questions,
)
from license_planner.catalog import CERTIFICATIONS
from license_planner.models import AssessmentItem, ProblemResult


QUESTIONNAIRE_DIR = Path(__file__).resolve().parent / "assessment_questionnaire"
QUESTION_BANKS_PATH = QUESTIONNAIRE_DIR / "question_banks.json"
PUBLIC_QUESTION_FIELDS = ("id", "prompt", "choices", "correct_answer", "possible_score")
# Latest validated snapshot per configured certification and source location; older
# locations are evicted so temporary or edited sources cannot grow the cache.
SNAPSHOT_CACHE_LIMIT = 8
# Rebuilds retried when a source changes while it is being read and validated.
SNAPSHOT_BUILD_ATTEMPTS = 3


class AssessmentConfigurationError(Exception):
    """Configured certification, bank or selection sources are missing, invalid or disagree.

    The message is an internal diagnostic naming the certification and failed source
    or field; HTTP responses use a fixed public message instead.
    """

    def __init__(self, certification_id: str, detail: str):
        super().__init__(f"assessment configuration for {certification_id!r}: {detail}")


@dataclass(frozen=True)
class _ReviewedQuestion:
    """Immutable copy of one validated reviewed record."""
    id: int
    prompt: str
    choices: tuple[tuple[str, str], ...]
    correct_answer: str
    possible_score: Any
    topic: str

    def public(self) -> dict[str, Any]:
        return {"id": self.id, "prompt": self.prompt, "choices": dict(self.choices),
                "correct_answer": self.correct_answer, "possible_score": self.possible_score}

    def matches(self, item: AssessmentItem) -> bool:
        """Whether a submitted result carries exactly this record's content, allowing UNKNOWN."""
        normal = {key: text for key, text in item.choices.items() if key != UNKNOWN_CHOICE}
        return (item.question == self.prompt and normal == dict(self.choices)
                and item.correct_answer == self.correct_answer
                and item.possible_score == float(self.possible_score))


@dataclass(frozen=True)
class _BankSnapshot:
    certification_name: str
    selected: tuple[_ReviewedQuestion, ...]
    reviewed: Mapping[int, _ReviewedQuestion]
    document_json: str


_SNAPSHOTS: OrderedDict[tuple, tuple[tuple, Path, Path, _BankSnapshot]] = OrderedDict()
_SNAPSHOT_LOCK = threading.Lock()


def _revision(path: Path) -> tuple[int, int, int, int, int]:
    status = os.stat(path)
    return (status.st_dev, status.st_ino, status.st_size, status.st_mtime_ns, status.st_ctime_ns)


def _read_json_source(path: Path, label: str, certification_id: str) -> Any:
    try:
        data = path.read_bytes()
    except OSError as error:
        raise AssessmentConfigurationError(certification_id, f"{label} could not be read: {error}") from error
    try:
        return parse_json_bytes(data, label)
    except QuestionContentError as error:
        raise AssessmentConfigurationError(certification_id, str(error)) from error


def _enabled_ids(path: Path, certification_id: str) -> set[str]:
    try:
        return {item.id for item in selector_catalog.load_certifications(path)}
    except ValueError as error:
        raise AssessmentConfigurationError(certification_id, f"certification selector catalog: {error}") from error


def _bank_path(bank_map: Any, certification_id: str) -> tuple[Path, Path]:
    """Return the mapped bank's configured path and its contained resolved path."""
    if not isinstance(bank_map, dict):
        raise AssessmentConfigurationError(certification_id, "question bank map must be a JSON object")
    relative_path = bank_map.get(certification_id)
    if not is_nonempty_text(relative_path):
        raise AssessmentConfigurationError(certification_id, "question bank map has no entry")
    candidate = QUESTIONNAIRE_DIR / relative_path
    resolved = candidate.resolve()
    if not resolved.is_relative_to(QUESTIONNAIRE_DIR.resolve()):
        raise AssessmentConfigurationError(certification_id, "question bank path leaves assessment_questionnaire")
    return candidate, resolved


def _validated_snapshot(document: Any, certification_id: str, topics: tuple[str, ...]) -> _BankSnapshot:
    """Validate a parsed bank against its certification and permitted planning topics."""
    if not isinstance(document, dict) or document.get("certification_id") != certification_id:
        raise AssessmentConfigurationError(certification_id, "question bank is mapped to the wrong certification")
    name = document.get("certification_name", certification_id)
    if not is_nonempty_text(name):
        raise AssessmentConfigurationError(certification_id, "question bank certification_name must be text")
    try:
        by_id = retained_records(document)
        reviewed = {question_id: record for question_id, record in by_id.items()
                    if record["review_status"] == "reviewed"}
        for record in reviewed.values():
            if record["topic"] not in topics:
                raise QuestionContentError(f"reviewed question {record['id']} has an unsupported topic")
        selected = select_questions(document, by_id)
    except QuestionContentError as error:
        raise AssessmentConfigurationError(certification_id, f"question bank: {error}") from error
    frozen = {question_id: _ReviewedQuestion(question_id, record["prompt"], tuple(record["choices"].items()),
                                             record["correct_answer"], record["possible_score"], record["topic"])
              for question_id, record in reviewed.items()}
    return _BankSnapshot(name, tuple(frozen[record["id"]] for record in selected), MappingProxyType(frozen),
                         json.dumps(document, ensure_ascii=False))


def _build_snapshot(certification_id: str, selector_path: Path, topics: tuple[str, ...] | None,
                    ) -> tuple[tuple, Path, Path, _BankSnapshot]:
    """Read and validate all sources, returning the snapshot with its source revisions."""
    try:
        before = (_revision(selector_path), _revision(QUESTION_BANKS_PATH))
    except OSError as error:
        raise AssessmentConfigurationError(certification_id, f"configuration file is unavailable: {error}") from error
    if certification_id not in _enabled_ids(selector_path, certification_id):
        raise KeyError(f"Unsupported certification_id: {certification_id}")
    if topics is None:
        raise AssessmentConfigurationError(certification_id, "enabled certification has no planning catalog entry")
    bank_map = _read_json_source(QUESTION_BANKS_PATH, "question bank map", certification_id)
    candidate, resolved = _bank_path(bank_map, certification_id)
    try:
        bank_revision = _revision(candidate)
    except OSError as error:
        raise AssessmentConfigurationError(certification_id, f"question bank is unavailable: {error}") from error
    snapshot = _validated_snapshot(_read_json_source(candidate, "question bank", certification_id),
                                   certification_id, topics)
    return (*before, bank_revision, topics), candidate, resolved, snapshot


def _current_revision(selector_path: Path, candidate: Path, topics: tuple[str, ...] | None) -> tuple | None:
    try:
        return (_revision(selector_path), _revision(QUESTION_BANKS_PATH), _revision(candidate), topics)
    except OSError:
        return None


def _snapshot(certification_id: str) -> _BankSnapshot:
    """Return the validated snapshot for an enabled certification, rebuilding it on any source change.

    Raises ``KeyError`` for a certification that is not enabled and
    ``AssessmentConfigurationError`` for invalid or inconsistent sources; a failed
    rebuild discards the earlier snapshot rather than serving it.
    """
    selector_path = Path(selector_catalog.CATALOG_PATH)
    certification = CERTIFICATIONS.get(certification_id)
    topics = certification.topics if certification is not None else None
    key = (certification_id, str(QUESTIONNAIRE_DIR), str(QUESTION_BANKS_PATH), str(selector_path))
    with _SNAPSHOT_LOCK:
        cached = _SNAPSHOTS.pop(key, None)
        if cached is not None:
            revision, candidate, resolved, snapshot = cached
            if (_current_revision(selector_path, candidate, topics) == revision
                    and candidate.resolve() == resolved):
                _SNAPSHOTS[key] = cached
                return snapshot
        for _ in range(SNAPSHOT_BUILD_ATTEMPTS):
            revision, candidate, resolved, snapshot = _build_snapshot(certification_id, selector_path, topics)
            if _current_revision(selector_path, candidate, topics) == revision:
                _SNAPSHOTS[key] = (revision, candidate, resolved, snapshot)
                while len(_SNAPSHOTS) > SNAPSHOT_CACHE_LIMIT:
                    _SNAPSHOTS.popitem(last=False)
                return snapshot
        raise AssessmentConfigurationError(certification_id, "sources kept changing while being validated")


def get_public_certifications() -> dict[str, Any]:
    """Return the enabled certification choices without assessment answers."""
    selector_path = Path(selector_catalog.CATALOG_PATH)
    try:
        certifications = selector_catalog.load_certifications(selector_path)
    except ValueError as error:
        raise AssessmentConfigurationError("catalog", f"certification selector catalog: {error}") from error
    return {"certifications": [
        {"id": item.id, "name": item.name, "exam_authority": item.exam_authority,
         "schedule_url": item.schedule_url}
        for item in certifications
    ]}


def _load_question_bank(certification_id: str) -> dict[str, Any]:
    """Return a fresh copy of an enabled certification's validated bank document."""
    return json.loads(_snapshot(certification_id).document_json)


def _onboarding_selection(certification_id: str) -> tuple[dict[str, Any], list[dict[str, Any]]]:
    """Return a fresh bank document and its configured onboarding records, in order."""
    snapshot = _snapshot(certification_id)
    document = json.loads(snapshot.document_json)
    by_id = {question["id"]: question for question in document["questions"]}
    return document, [by_id[question.id] for question in snapshot.selected]


def get_public_questions(certification_id: str) -> dict[str, Any]:
    """Return the configured onboarding selection's public fields, in configured order.

    Internal review metadata, the syllabus version and unselected retained items stay
    server-side. Every served bank needs an explicit valid selection.
    """
    snapshot = _snapshot(certification_id)
    return {"certification_id": certification_id,
            "certification_name": snapshot.certification_name,
            "questions": [question.public() for question in snapshot.selected]}


def parse_assessment_results(certification_id: str, assessment: Any) -> tuple[AssessmentItem, ...]:
    """Validate locally-scored JSON results, including their correctness flags.

    Each result needs explicit question content and a positive finite possible score;
    ``is_correct`` must equal the normal answer/key comparison, with ``UNKNOWN``
    always incorrect. Inconsistent results are rejected, never rewritten.
    """
    if not isinstance(assessment, dict):
        raise ValueError("assessment_results must be an object")
    if assessment.get("certification_id") != certification_id:
        raise ValueError("assessment_results certification_id does not match the request")
    results = assessment.get("results")
    if not isinstance(results, list):
        raise ValueError("assessment_results.results must be an array")
    if not results:
        raise ValueError("assessment_results.results must contain at least one item")
    submitted: list[AssessmentItem] = []
    seen_ids: set[int] = set()
    for item in results:
        if not isinstance(item, dict):
            raise ValueError("each assessment_results item must be an object")
        question_id = item.get("problem_id")
        if not isinstance(question_id, int) or isinstance(question_id, bool):
            raise ValueError("assessment result problem_id must be an integer")
        if not is_positive_id(question_id):
            raise ValueError("assessment result problem_id must be positive")
        if question_id in seen_ids:
            raise ValueError(f"Duplicate assessment result for problem_id: {question_id}")
        if "possible_score" not in item:
            raise ValueError(f"possible_score is required for problem_id: {question_id}")
        choices, correct_answer = item.get("choices"), item.get("correct_answer")
        _, possible_score = check_question_content(
            question_id, item.get("question"), choices, correct_answer, item["possible_score"],
            allow_unknown_choice=True)
        answer, is_correct = item.get("user_answer"), item.get("is_correct")
        if not isinstance(answer, str) or answer not in choices:
            raise ValueError(f"user_answer must be a choice key for problem_id: {question_id}")
        if not isinstance(is_correct, bool):
            raise ValueError(f"is_correct must be a boolean for problem_id: {question_id}")
        if is_correct != (answer != UNKNOWN_CHOICE and answer == correct_answer):
            raise ValueError(f"is_correct does not match user_answer and correct_answer for problem_id: {question_id}")
        submitted.append(AssessmentItem(question_id, item["question"], dict(choices), correct_answer, answer,
                                        is_correct, possible_score))
        seen_ids.add(question_id)
    check_total([item.possible_score for item in submitted], "assessment_results")
    return tuple(submitted)


def partition_assessment(certification_id: str, items: tuple[AssessmentItem, ...],
                         ) -> tuple[tuple[ProblemResult, ...], tuple[AssessmentItem, ...]]:
    """Split submitted results into reviewed-topic results and unresolved questions.

    A result is resolved when it has the same ID, prompt, normal choices, key and
    possible score as a complete reviewed record of the certification, whether or not
    that record is in the onboarding selection; it then counts once toward the stored
    topic with its validated local correctness. Other results, including reused IDs
    with changed content, stay unresolved for classification. Invalid configuration
    raises ``AssessmentConfigurationError`` rather than leaving every result unresolved.
    """
    try:
        reviewed = _snapshot(certification_id).reviewed
    except KeyError:
        raise ValueError(f"Unsupported certification_id: {certification_id}") from None
    fixed: list[ProblemResult] = []
    unresolved: list[AssessmentItem] = []
    for item in items:
        record = reviewed.get(item.problem_id)
        if record is not None and record.matches(item):
            fixed.append(ProblemResult(item.problem_id, record.topic, item.possible_score,
                                       item.possible_score if item.is_correct else 0.0))
        else:
            unresolved.append(item)
    return tuple(fixed), tuple(unresolved)
