"""Certification catalog loaded from backend/config/certifications.json."""
from __future__ import annotations

import json
from dataclasses import dataclass
from pathlib import Path
from typing import Any


CATALOG_PATH = Path(__file__).resolve().parent.parent / "config" / "certifications.json"


@dataclass(frozen=True)
class Certification:
    """Planning topics and display name for one supported certification."""
    certification_id: str
    display_name: str
    topics: tuple[str, ...]


def load_certifications(path: Path = CATALOG_PATH) -> dict[str, Certification]:
    """Read and validate the editable JSON certification catalog."""
    try:
        document: Any = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise RuntimeError(f"Certification catalog could not be read: {error}") from error
    rows = document.get("certifications") if isinstance(document, dict) else None
    if not isinstance(rows, list) or not rows:
        raise RuntimeError("Certification catalog must contain a non-empty certifications array")

    catalog: dict[str, Certification] = {}
    for index, row in enumerate(rows, start=1):
        if not isinstance(row, dict):
            raise RuntimeError(f"Certification catalog item {index} must be an object")
        certification_id = row.get("id")
        display_name = row.get("display_name")
        topics = row.get("topics")
        if not isinstance(certification_id, str) or not certification_id.strip():
            raise RuntimeError(f"Certification catalog item {index} needs a non-empty id")
        if certification_id in catalog:
            raise RuntimeError(f"Duplicate certification id in catalog: {certification_id}")
        if not isinstance(display_name, str) or not display_name.strip():
            raise RuntimeError(f"Certification {certification_id} needs a non-empty display_name")
        if (not isinstance(topics, list) or not topics
                or any(not isinstance(topic, str) or not topic.strip() for topic in topics)):
            raise RuntimeError(f"Certification {certification_id} needs a non-empty topics array")
        if len(set(topics)) != len(topics):
            raise RuntimeError(f"Certification {certification_id} has duplicate topics")
        catalog[certification_id] = Certification(certification_id, display_name, tuple(topics))
    return catalog


CERTIFICATIONS = load_certifications()


def get_certification(certification_id: str) -> Certification:
    """Return one configured certification or a useful unsupported-ID error."""
    try:
        return CERTIFICATIONS[certification_id]
    except KeyError:
        supported = ", ".join(sorted(CERTIFICATIONS))
        raise ValueError(
            f"NOT_FOUND: unsupported certification_id={certification_id!r}. Choose one of: {supported}."
        ) from None
