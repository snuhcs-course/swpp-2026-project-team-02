"""개인 가능 시간 JSON을 읽고 검증합니다.

License Planner와 같은 ``study_availability`` 공용 파서를 사용합니다.
"""
from __future__ import annotations

from pathlib import Path

from study_availability import (
    TIME_PATTERN, WEEKDAY_NAMES, AvailabilityContext, load_availability_profile,
    parse_availability_context,
)

from . import settings

__all__ = ["TIME_PATTERN", "WEEKDAY_NAMES", "load_personal_availability", "parse_weekly_availability"]


def parse_weekly_availability(payload: str | dict | None) -> AvailabilityContext | None:
    """입력 값: 주간 가능 시간 JSON 문자열/객체 또는 None
    출력 값: 검증된 가능 시간 문맥 또는 None
    기능: 공용 파서로 요일, 시간 형식, 길이와 겹침 여부를 확인합니다.
    """
    return parse_availability_context(payload)


def load_personal_availability(path: Path | None = None) -> AvailabilityContext | None:
    """입력 값: 가능 시간 JSON 파일 경로
    출력 값: 저장된 가능 시간 문맥 또는 파일이 없으면 None
    기능: License Planner와 공유하는 개인 시간표 파일을 읽고 검증합니다.
    """
    return load_availability_profile(path or settings.USER_AVAILABILITY_PATH)
