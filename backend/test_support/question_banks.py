"""Temporary question banks for tests that must not touch the checked-in collection."""
from __future__ import annotations

import contextlib
import copy
import json
import tempfile
from pathlib import Path
from typing import Iterator
from unittest.mock import patch

import assessment_service


CERT_ID = "computer_specialist_level_2"
LEVEL_2_BANK_PATH = assessment_service.QUESTIONNAIRE_DIR / "questions.json"


def level_2_bank() -> dict:
    """A fresh copy of the checked-in Level 2 bank document."""
    return json.loads(LEVEL_2_BANK_PATH.read_text(encoding="utf-8"))


def unselected_reviewed_item(question_id: int = 19, topic: str = "매크로 활용") -> dict:
    """A complete reviewed item retained outside onboarding."""
    return {"id": question_id, "topic": topic,
            "prompt": "Excel 2021에서 매크로를 기록할 때 사용할 수 있는 탭은 무엇인가요?",
            "choices": {"A": "개발 도구", "B": "검토", "C": "수식", "D": "삽입"},
            "correct_answer": "A", "possible_score": 1, "subject": "스프레드시트 일반",
            "syllabus_section": "매크로 활용",
            "explanation": "개발 도구 탭의 매크로 기록으로 매크로를 기록합니다.",
            "provenance": {"kind": "authored", "reference": "test-only authored item"},
            "review_status": "reviewed"}


def incomplete_draft(question_id: int = 20) -> dict:
    """A structurally valid draft whose answer, explanation and tags are not reviewed yet."""
    return {"id": question_id, "prompt": "검토 전 초안 문항", "choices": {"A": "후보"},
            "provenance": {"kind": "authored", "reference": "test-only draft"},
            "review_status": "draft"}


def larger_level_2_bank() -> dict:
    """The checked-in bank plus an unselected reviewed item and an incomplete draft."""
    document = level_2_bank()
    document["questions"] += [unselected_reviewed_item(), incomplete_draft()]
    return document


@contextlib.contextmanager
def temporary_question_bank(document: dict) -> Iterator[Path]:
    """Serve ``document`` as the Level 2 bank from a temporary questionnaire directory."""
    with tempfile.TemporaryDirectory(prefix="question-bank-") as directory:
        root = Path(directory)
        (root / "question_banks.json").write_text(json.dumps({CERT_ID: "questions.json"}), encoding="utf-8")
        bank_path = root / "questions.json"
        bank_path.write_text(json.dumps(copy.deepcopy(document), ensure_ascii=False), encoding="utf-8")
        with patch.object(assessment_service, "QUESTIONNAIRE_DIR", root), \
                patch.object(assessment_service, "QUESTION_BANKS_PATH", root / "question_banks.json"):
            yield bank_path
