"""Load the extensible certification list used by the selector."""
from __future__ import annotations

import json
from dataclasses import dataclass
from pathlib import Path


CATALOG_PATH = Path(__file__).with_name("certifications.json")


@dataclass(frozen=True)
class Certification:
    id: str
    name: str
    exam_authority: str
    schedule_url: str


def load_certifications(path: Path = CATALOG_PATH) -> tuple[Certification, ...]:
    """Return enabled catalog entries in configured order."""
    try:
        payload = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise ValueError(f"자격증 목록을 읽을 수 없습니다: {error}") from error

    entries = payload.get("certifications") if isinstance(payload, dict) else None
    if not isinstance(entries, list):
        raise ValueError("certifications.json must contain a certifications array")

    certifications: list[Certification] = []
    seen_ids: set[str] = set()
    for entry in entries:
        if not isinstance(entry, dict) or not entry.get("enabled", False):
            continue
        cert_id = entry.get("id")
        name = entry.get("name")
        authority = entry.get("exam_authority")
        schedule_url = entry.get("schedule_url")
        if not all(isinstance(value, str) and value.strip()
                   for value in (cert_id, name, authority, schedule_url)):
            raise ValueError("Enabled certifications require id, name, exam_authority, and schedule_url")
        if cert_id in seen_ids:
            raise ValueError(f"Duplicate certification id: {cert_id}")
        seen_ids.add(cert_id)
        certifications.append(Certification(cert_id, name, authority, schedule_url))
    return tuple(certifications)
