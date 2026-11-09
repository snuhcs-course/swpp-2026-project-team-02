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


def custom_question_variant(payload: dict) -> dict:
    """A copy of a planning request whose questions are explicitly custom, not bank records.

    The captured five-question provider traces classify every submitted question with the
    model. The unchanged original five now match reviewed records, so replaying those
    traces over HTTP marks each prompt as a custom question that stays unresolved.
    """
    variant = copy.deepcopy(payload)
    for row in variant["assessment_results"]["results"]:
        row["question"] = f"[사용자 정의] {row['question']}"
    return variant


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


PRACTICE_CERT_ID = "practice_certification"
PRACTICE_TOPICS = ("연습 주제 가", "연습 주제 나")


def practice_bank(certification_id: str = PRACTICE_CERT_ID, *, selection=(2, 1)) -> dict:
    """A small reviewed bank for a non-Level-2 certification, plus an unselected draft."""
    def reviewed(question_id, topic):
        return {"id": question_id, "topic": topic, "prompt": f"연습 문항 {question_id}",
                "choices": {"A": "정답", "B": "오답"}, "correct_answer": "A", "possible_score": 1,
                "subject": "연습 과목", "syllabus_section": "연습 단원", "explanation": "A가 정답입니다.",
                "provenance": {"kind": "authored", "reference": "test-only authored item"},
                "review_status": "reviewed"}
    document = {"certification_id": certification_id, "certification_name": "연습 자격증",
                "questions": [reviewed(1, PRACTICE_TOPICS[0]), reviewed(2, PRACTICE_TOPICS[1]), incomplete_draft(3)]}
    if selection is not None:
        document["onboarding_question_ids"] = list(selection)
    return document


@contextlib.contextmanager
def temporary_assessment_configuration(*, enabled=(CERT_ID,), planner=None, bank_map=None,
                                       banks=None) -> Iterator[Path]:
    """Serve temporary selector, planner and bank configuration without touching real catalogs.

    ``planner`` maps certification IDs to topics added to the planning catalog for the
    duration; ``banks`` maps file names to documents or raw bytes. Level 2 keeps its
    checked-in planning entry and, unless replaced, a copy of its checked-in bank.
    """
    import certification_selector.catalog as selector_catalog
    from license_planner import catalog as planner_catalog

    planner = {PRACTICE_CERT_ID: PRACTICE_TOPICS} if planner is None else planner
    bank_map = ({CERT_ID: "questions.json", PRACTICE_CERT_ID: "practice.json"}
                if bank_map is None else bank_map)
    banks = {"questions.json": level_2_bank(), "practice.json": practice_bank()} if banks is None else banks
    with tempfile.TemporaryDirectory(prefix="assessment-config-") as directory:
        root = Path(directory)
        questionnaire = root / "assessment_questionnaire"
        questionnaire.mkdir()
        selector_path = root / "certifications.json"
        selector_path.write_text(json.dumps({"certifications": [
            {"id": certification_id, "name": certification_id, "enabled": True, "exam_authority": "테스트 기관",
             "schedule_url": "https://example.invalid/schedule"} for certification_id in enabled]},
            ensure_ascii=False), encoding="utf-8")
        (questionnaire / "question_banks.json").write_text(json.dumps(bank_map), encoding="utf-8")
        for name, content in banks.items():
            data = content if isinstance(content, bytes) else json.dumps(content, ensure_ascii=False).encode("utf-8")
            (questionnaire / name).write_bytes(data)
        entries = {certification_id: planner_catalog.Certification(certification_id, certification_id, tuple(topics))
                   for certification_id, topics in planner.items()}
        with patch.object(assessment_service, "QUESTIONNAIRE_DIR", questionnaire), \
                patch.object(assessment_service, "QUESTION_BANKS_PATH", questionnaire / "question_banks.json"), \
                patch.object(selector_catalog, "CATALOG_PATH", selector_path), \
                patch.dict(planner_catalog.CERTIFICATIONS, entries):
            yield questionnaire
