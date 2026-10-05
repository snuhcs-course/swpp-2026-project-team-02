"""Question bank access and validation for locally-scored assessment JSON."""
from __future__ import annotations

import json
import math
from pathlib import Path
from typing import Any

from certification_selector.catalog import load_certifications
from license_planner.models import AssessmentItem


QUESTIONNAIRE_DIR = Path(__file__).resolve().parent / "assessment_questionnaire"
QUESTION_BANKS_PATH = QUESTIONNAIRE_DIR / "question_banks.json"


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


def get_public_questions(certification_id: str) -> dict[str, Any]:
    """Return the dummy question data needed for local answer comparison."""
    document = _load_question_bank(certification_id)
    questions = []
    for question in document["questions"]:
        if not isinstance(question, dict):
            raise ValueError("Assessment question bank contains an invalid question")
        questions.append({key: question[key] for key in
                          ("id", "prompt", "choices", "correct_answer", "possible_score")
                          if key in question})
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
