"""Synthetic device tokens for HTTP tests; never real installation credentials."""
from __future__ import annotations

from pathlib import Path

from study_plan_store import StudyPlanStore

DEVICE_TOKEN = "0123456789abcdef" * 4
OTHER_DEVICE_TOKEN = "fedcba9876543210" * 4


def bearer(token: str = DEVICE_TOKEN) -> dict[str, str]:
    """The Authorization header one test client sends with every plan request."""
    return {"Authorization": f"Bearer {token}"}


def owner_store(plan_root: Path, token: str = DEVICE_TOKEN) -> StudyPlanStore:
    """The store a token's HTTP requests use, with its owner directory created."""
    store = StudyPlanStore.for_owner(plan_root, token)
    store.directory.mkdir(parents=True, exist_ok=True)
    return store
