"""Question bank access and validation for locally-scored assessment JSON."""
from __future__ import annotations

import json
import math
from pathlib import Path
from typing import Any

from certification_selector.catalog import load_certifications
from license_planner.catalog import CERTIFICATIONS
from license_planner.models import AssessmentItem, ProblemResult


QUESTIONNAIRE_DIR = Path(__file__).resolve().parent / "assessment_questionnaire"
QUESTION_BANKS_PATH = QUESTIONNAIRE_DIR / "question_banks.json"
# Banks whose onboarding route must serve an explicit reviewed selection, never the whole collection.
SELECTION_REQUIRED = frozenset({"computer_specialist_level_2"})
PUBLIC_QUESTION_FIELDS = ("id", "prompt", "choices", "correct_answer", "possible_score")
# A bank may retain more questions than onboarding presents, including drafts;
# ``onboarding_question_ids`` lists, in order, the reviewed items onboarding uses.
SELECTION_KEY = "onboarding_question_ids"
REVIEW_STATUSES = ("draft", "reviewed")
PROVENANCE_KINDS = ("authored", "external")
UNKNOWN_CHOICE = "UNKNOWN"


def _nonempty_text(value: Any) -> bool:
    return isinstance(value, str) and bool(value.strip())


def _positive_id(value: Any) -> bool:
    return isinstance(value, int) and not isinstance(value, bool) and value >= 1


def _check_provenance(question_id: int, provenance: Any) -> None:
    if (not isinstance(provenance, dict) or provenance.get("kind") not in PROVENANCE_KINDS
            or not _nonempty_text(provenance.get("reference"))):
        raise ValueError(f"Question {question_id} needs provenance with a kind and a reference")
    if provenance["kind"] == "external" and not _nonempty_text(provenance.get("reuse_basis")):
        raise ValueError(f"External question {question_id} needs a provenance reuse_basis")


def _check_reviewed(question: dict[str, Any]) -> None:
    """Reject a selected item unless its learner-facing content and review metadata are complete."""
    question_id = question["id"]
    if question.get("review_status") != "reviewed":
        raise ValueError(f"Onboarding question {question_id} is not reviewed")
    choices = question.get("choices")
    points = question.get("possible_score")
    if (not _nonempty_text(question.get("prompt"))
            or not isinstance(choices, dict) or len(choices) < 2 or UNKNOWN_CHOICE in choices
            or not all(isinstance(key, str) and _nonempty_text(value) for key, value in choices.items())
            or question.get("correct_answer") not in choices
            or not isinstance(points, (int, float)) or isinstance(points, bool)
            or not math.isfinite(points) or points <= 0):
        raise ValueError(f"Onboarding question {question_id} has incomplete question content")
    for field in ("topic", "subject", "syllabus_section", "explanation"):
        if not _nonempty_text(question.get(field)):
            raise ValueError(f"Onboarding question {question_id} is missing {field}")


def select_onboarding_questions(document: dict[str, Any], *,
                                required: bool) -> list[dict[str, Any]] | None:
    """Return the configured onboarding questions in order, or ``None`` without a selection.

    Every retained record needs a unique positive ID and provenance, as in a draft. Each
    selected ID must resolve to a complete reviewed record. A missing selection is an error
    only when ``required``; any invalid selection raises ``ValueError`` without a fallback.
    """
    if SELECTION_KEY not in document:
        if required:
            raise ValueError(f"Question bank has no {SELECTION_KEY}")
        return None
    by_id: dict[int, dict[str, Any]] = {}
    for question in document.get("questions", ()):
        if not isinstance(question, dict) or not _positive_id(question.get("id")):
            raise ValueError("Every retained question needs a positive integer id")
        if question["id"] in by_id:
            raise ValueError(f"Duplicate retained question id: {question['id']}")
        if question.get("review_status") not in REVIEW_STATUSES:
            raise ValueError(f"Question {question['id']} has an unknown review_status")
        _check_provenance(question["id"], question.get("provenance"))
        by_id[question["id"]] = question
    selection = document[SELECTION_KEY]
    if not isinstance(selection, list) or not selection:
        raise ValueError(f"{SELECTION_KEY} must be a non-empty array")
    if not all(_positive_id(question_id) for question_id in selection):
        raise ValueError(f"{SELECTION_KEY} must contain positive integer ids")
    if len(set(selection)) != len(selection):
        raise ValueError(f"{SELECTION_KEY} repeats a question id")
    missing = [question_id for question_id in selection if question_id not in by_id]
    if missing:
        raise ValueError(f"{SELECTION_KEY} references unknown question ids: {missing}")
    selected = [by_id[question_id] for question_id in selection]
    for question in selected:
        _check_reviewed(question)
    return selected


def get_public_certifications() -> dict[str, Any]:
    """Return the enabled certification choices without assessment answers."""
    return {"certifications": [
        {"id": item.id, "name": item.name, "exam_authority": item.exam_authority,
         "schedule_url": item.schedule_url}
        for item in load_certifications()
    ]}


def _load_question_bank(certification_id: str) -> dict[str, Any]:
    """Load the configured bank for one enabled certification."""
    enabled_ids = {item.id for item in load_certifications()}
    if certification_id not in enabled_ids:
        raise KeyError(f"Unsupported certification_id: {certification_id}")
    try:
        bank_map = json.loads(QUESTION_BANKS_PATH.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise ValueError(f"Question bank map could not be read: {error}") from error
    relative_path = bank_map.get(certification_id) if isinstance(bank_map, dict) else None
    if not isinstance(relative_path, str) or not relative_path.strip():
        raise KeyError(f"No assessment question bank for certification_id: {certification_id}")
    bank_path = (QUESTIONNAIRE_DIR / relative_path).resolve()
    if not bank_path.is_relative_to(QUESTIONNAIRE_DIR.resolve()):
        raise ValueError("Question bank path must stay inside assessment_questionnaire")
    try:
        document = json.loads(bank_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise ValueError(f"Assessment question bank could not be read: {error}") from error
    if (not isinstance(document, dict)
            or document.get("certification_id") != certification_id
            or not isinstance(document.get("questions"), list)
            or not document["questions"]):
        raise ValueError("Assessment question bank is invalid or mapped to the wrong certification")
    return document


def _onboarding_selection(certification_id: str) -> tuple[dict[str, Any], list[dict[str, Any]] | None]:
    """Load a bank and resolve its configured onboarding selection with catalog topics."""
    document = _load_question_bank(certification_id)
    selected = select_onboarding_questions(document, required=certification_id in SELECTION_REQUIRED)
    if selected is not None:
        certification = CERTIFICATIONS.get(certification_id)
        topics = certification.topics if certification is not None else ()
        for question in selected:
            if question["topic"] not in topics:
                raise ValueError(f"Onboarding question {question['id']} has an unsupported topic")
    return document, selected


def get_public_questions(certification_id: str) -> dict[str, Any]:
    """Return the onboarding question data needed for local answer comparison.

    A bank with a configured selection serves only those items, in order; internal
    review metadata and unselected retained items stay server-side.
    """
    document, selected = _onboarding_selection(certification_id)
    questions = []
    for question in document["questions"] if selected is None else selected:
        if not isinstance(question, dict):
            raise ValueError("Assessment question bank contains an invalid question")
        questions.append({key: question[key] for key in PUBLIC_QUESTION_FIELDS if key in question})
    return {"certification_id": certification_id,
            "certification_name": document.get("certification_name", certification_id),
            "questions": questions}


def parse_assessment_results(certification_id: str, assessment: Any) -> tuple[AssessmentItem, ...]:
    """Validate locally-scored JSON results without regrading them in the API."""
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
        answer = item.get("user_answer")
        if not isinstance(question_id, int) or isinstance(question_id, bool):
            raise ValueError("assessment result problem_id must be an integer")
        if question_id < 1:
            raise ValueError("assessment result problem_id must be positive")
        if question_id in seen_ids:
            raise ValueError(f"Duplicate assessment result for problem_id: {question_id}")
        question = item.get("question")
        choices = item.get("choices")
        correct_answer = item.get("correct_answer")
        is_correct = item.get("is_correct")
        possible_score = item.get("possible_score", 1)
        if not isinstance(question, str) or not question.strip():
            raise ValueError(f"question must be a non-empty string for problem_id: {question_id}")
        if (not isinstance(choices, dict) or not choices
                or not all(isinstance(key, str) and isinstance(value, str)
                           for key, value in choices.items())):
            raise ValueError(f"choices must be a string map for problem_id: {question_id}")
        if not isinstance(correct_answer, str) or not correct_answer.strip():
            raise ValueError(f"correct_answer must be a non-empty string for problem_id: {question_id}")
        if not isinstance(answer, str) or not answer.strip():
            raise ValueError(f"user_answer must be a non-empty string for problem_id: {question_id}")
        if answer not in choices or correct_answer not in choices:
            raise ValueError(f"user_answer and correct_answer must be choice keys for problem_id: {question_id}")
        if not isinstance(is_correct, bool):
            raise ValueError(f"is_correct must be a boolean for problem_id: {question_id}")
        if (not isinstance(possible_score, (int, float)) or isinstance(possible_score, bool)
                or not math.isfinite(possible_score) or possible_score <= 0):
            raise ValueError(f"possible_score must be positive for problem_id: {question_id}")
        submitted.append(AssessmentItem(question_id, question, choices, correct_answer, answer,
                                        is_correct, float(possible_score)))
        seen_ids.add(question_id)
    return tuple(submitted)


def _matches_selected(item: AssessmentItem, question: dict[str, Any]) -> bool:
    """Whether a submitted item carries the selected question's content, allowing UNKNOWN."""
    choices = {key: value for key, value in item.choices.items() if key != UNKNOWN_CHOICE}
    return (item.question == question["prompt"] and choices == question["choices"]
            and item.correct_answer == question["correct_answer"]
            and item.possible_score == float(question["possible_score"]))


def reviewed_topic_results(certification_id: str,
                           items: tuple[AssessmentItem, ...]) -> tuple[ProblemResult, ...]:
    """Classify a complete onboarding submission by its selected items' reviewed topics.

    The submission must contain exactly the configured selection's IDs, in any order,
    each with the selected prompt, normal choices, key and possible score. Each item
    then counts once toward its reviewed topic with its submitted local correctness.
    Any other submission, or a bank without a valid selection, returns ``()`` so the
    planning agent classifies it as before.
    """
    try:
        _, selected = _onboarding_selection(certification_id)
    except (KeyError, ValueError):
        return ()
    if not selected:
        return ()
    by_id = {question["id"]: question for question in selected}
    submitted = {item.problem_id: item for item in items}
    if len(submitted) != len(items) or submitted.keys() != by_id.keys():
        return ()
    if not all(_matches_selected(item, by_id[item.problem_id]) for item in items):
        return ()
    return tuple(
        ProblemResult(item.problem_id, by_id[item.problem_id]["topic"], item.possible_score,
                      item.possible_score if item.is_correct else 0.0)
        for item in items
    )
