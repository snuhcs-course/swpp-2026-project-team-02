"""Dependency-free validation of question documents, selections and question content.

This module is shared by the API's assessment service and the standalone
questionnaire CLI. It uses only the standard library and must not import the
planning service, catalogs, credential loaders or network clients.
"""
from __future__ import annotations

import json
import math
from typing import Any


UNKNOWN_CHOICE = "UNKNOWN"
UNKNOWN_LABEL = "모르겠음"
SELECTION_KEY = "onboarding_question_ids"
REVIEW_STATUSES = ("draft", "reviewed")
PROVENANCE_KINDS = ("authored", "external")
REVIEWED_TEXT_FIELDS = ("topic", "subject", "syllabus_section", "explanation")


class QuestionContentError(ValueError):
    """A question document, selection or question's content is malformed."""


def is_nonempty_text(value: Any) -> bool:
    return isinstance(value, str) and bool(value.strip())


def is_positive_id(value: Any) -> bool:
    return isinstance(value, int) and not isinstance(value, bool) and value >= 1


def parse_json_bytes(data: bytes, label: str) -> Any:
    """Decode strict UTF-8 JSON, reporting malformed bytes as a content error."""
    try:
        return json.loads(data.decode("utf-8"))
    except UnicodeDecodeError as error:
        raise QuestionContentError(f"{label} is not valid UTF-8") from error
    except (json.JSONDecodeError, RecursionError) as error:
        raise QuestionContentError(f"{label} is not valid JSON") from error


def check_score(value: Any, label: str) -> float:
    """Return a positive finite score, rejecting bools and unrepresentable numbers."""
    if not isinstance(value, (int, float)) or isinstance(value, bool):
        raise QuestionContentError(f"{label} must be a positive number")
    try:
        score = float(value)
    except OverflowError as error:
        raise QuestionContentError(f"{label} is too large") from error
    if not math.isfinite(score) or score <= 0:
        raise QuestionContentError(f"{label} must be a positive finite number")
    return score


def check_total(scores: list[float], label: str) -> float:
    """Return the sum of finite scores, rejecting a total that overflows."""
    total = sum(scores)
    if not math.isfinite(total):
        raise QuestionContentError(f"{label} total possible score is not finite")
    return total


def check_question_content(question_id: Any, prompt: Any, choices: Any, correct_answer: Any,
                           possible_score: Any, *, allow_unknown_choice: bool) -> tuple[dict[str, str], float]:
    """Validate one question's learner-facing content.

    Returns its normal choices (without ``UNKNOWN``) and its score. ``UNKNOWN`` is
    reserved: it is never a correct key, and when ``allow_unknown_choice`` permits a
    supplied ``UNKNOWN`` choice it must carry the ``모르겠음`` label.
    """
    if not is_nonempty_text(prompt):
        raise QuestionContentError(f"question {question_id} needs a non-empty prompt")
    if not isinstance(choices, dict):
        raise QuestionContentError(f"question {question_id} choices must be an object")
    normal: dict[str, str] = {}
    for key, text in choices.items():
        if not is_nonempty_text(key) or not is_nonempty_text(text):
            raise QuestionContentError(f"question {question_id} choices need non-empty keys and text")
        if key == UNKNOWN_CHOICE:
            if not allow_unknown_choice:
                raise QuestionContentError(f"question {question_id} must not define the reserved UNKNOWN choice")
            if text != UNKNOWN_LABEL:
                raise QuestionContentError(f"question {question_id} UNKNOWN choice must be labeled {UNKNOWN_LABEL}")
            continue
        normal[key] = text
    if len(normal) < 2:
        raise QuestionContentError(f"question {question_id} needs at least two normal choices")
    if not isinstance(correct_answer, str) or correct_answer == UNKNOWN_CHOICE or correct_answer not in normal:
        raise QuestionContentError(f"question {question_id} correct_answer must be one of its normal choice keys")
    return normal, check_score(possible_score, f"question {question_id} possible_score")


def check_question_record(question: dict[str, Any], *, allow_unknown_choice: bool = False) -> float:
    """Validate a question-document record's content and return its score."""
    return check_question_content(question.get("id"), question.get("prompt"), question.get("choices"),
                                  question.get("correct_answer"), question.get("possible_score"),
                                  allow_unknown_choice=allow_unknown_choice)[1]


def check_retained_record(question: Any) -> int:
    """Validate the minimal identity, status and provenance every retained record needs."""
    if not isinstance(question, dict) or not is_positive_id(question.get("id")):
        raise QuestionContentError("every retained question needs a positive integer id")
    question_id = question["id"]
    if question.get("review_status") not in REVIEW_STATUSES:
        raise QuestionContentError(f"question {question_id} has an unknown review_status")
    provenance = question.get("provenance")
    if (not isinstance(provenance, dict) or provenance.get("kind") not in PROVENANCE_KINDS
            or not is_nonempty_text(provenance.get("reference"))):
        raise QuestionContentError(f"question {question_id} needs provenance with a kind and a reference")
    if provenance["kind"] == "external" and not is_nonempty_text(provenance.get("reuse_basis")):
        raise QuestionContentError(f"external question {question_id} needs a provenance reuse_basis")
    return question_id


def check_reviewed_record(question: dict[str, Any]) -> float:
    """Validate a reviewed record's complete content and review metadata; return its score."""
    score = check_question_record(question)
    for field in REVIEWED_TEXT_FIELDS:
        if not is_nonempty_text(question.get(field)):
            raise QuestionContentError(f"reviewed question {question['id']} is missing {field}")
    return score


def question_list(document: Any) -> list[Any]:
    """Return a document's non-empty questions array."""
    if not isinstance(document, dict):
        raise QuestionContentError("question document must be a JSON object")
    questions = document.get("questions")
    if not isinstance(questions, list) or not questions:
        raise QuestionContentError("question document needs a non-empty questions array")
    return questions


def retained_records(document: Any) -> dict[int, dict[str, Any]]:
    """Validate every retained record and every reviewed record; return records by ID.

    Drafts need only identity, status and provenance; reviewed records, selected or
    not, must be complete.
    """
    by_id: dict[int, dict[str, Any]] = {}
    for question in question_list(document):
        question_id = check_retained_record(question)
        if question_id in by_id:
            raise QuestionContentError(f"duplicate retained question id: {question_id}")
        if question["review_status"] == "reviewed":
            check_reviewed_record(question)
        by_id[question_id] = question
    return by_id


def select_questions(document: Any, by_id: dict[int, dict[str, Any]]) -> list[dict[str, Any]]:
    """Resolve the explicit ordered selection to reviewed records with a finite total score."""
    if not isinstance(document, dict) or SELECTION_KEY not in document:
        raise QuestionContentError(f"question bank has no {SELECTION_KEY}")
    selection = document[SELECTION_KEY]
    if not isinstance(selection, list) or not selection:
        raise QuestionContentError(f"{SELECTION_KEY} must be a non-empty array")
    if not all(is_positive_id(question_id) for question_id in selection):
        raise QuestionContentError(f"{SELECTION_KEY} must contain positive integer ids")
    if len(set(selection)) != len(selection):
        raise QuestionContentError(f"{SELECTION_KEY} repeats a question id")
    missing = [question_id for question_id in selection if question_id not in by_id]
    if missing:
        raise QuestionContentError(f"{SELECTION_KEY} references unknown question ids: {missing}")
    selected = [by_id[question_id] for question_id in selection]
    drafts = [question["id"] for question in selected if question["review_status"] != "reviewed"]
    if drafts:
        raise QuestionContentError(f"{SELECTION_KEY} selects unreviewed questions: {drafts}")
    check_total([float(question["possible_score"]) for question in selected], SELECTION_KEY)
    return selected


def document_questions(document: Any) -> list[dict[str, Any]]:
    """Return the questions a standalone questionnaire asks.

    A document with an explicit selection yields only its selected reviewed records;
    a custom document without selection metadata yields every question after content
    validation, allowing only a correctly labeled ``UNKNOWN`` choice.
    """
    if isinstance(document, dict) and SELECTION_KEY in document:
        return select_questions(document, retained_records(document))
    questions = question_list(document)
    seen: set[int] = set()
    scores = []
    for question in questions:
        if not isinstance(question, dict) or not is_positive_id(question.get("id")):
            raise QuestionContentError("every question needs a positive integer id")
        if question["id"] in seen:
            raise QuestionContentError(f"duplicate question id: {question['id']}")
        scores.append(check_question_record(question, allow_unknown_choice=True))
        seen.add(question["id"])
    check_total(scores, "question document")
    return questions
