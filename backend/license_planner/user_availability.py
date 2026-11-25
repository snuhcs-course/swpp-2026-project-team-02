"""개인용 앱의 주간 공부 가능 시간을 JSON 파일로 관리합니다.

검증·해석 규칙은 License Planner와 Schedule Adjuster가 함께 쓰는
``study_availability`` 모듈에 있으며, 이 모듈은 기본 파일 경로만 더합니다.
"""
from __future__ import annotations

from pathlib import Path

from study_availability import (
    TIME_PATTERN, WEEKDAY_NAMES, AvailabilityContext, load_availability_profile,
    parse_availability_context, save_availability_profile,
)

from .settings import PROJECT_ROOT

# 개인용 앱에서 현재 사용자의 반복 주간 가능 시간과 주별 교체 시간을 보관하는 공용 파일입니다.
USER_AVAILABILITY_PATH = PROJECT_ROOT / "config" / "user_availability.json"

__all__ = ["TIME_PATTERN", "USER_AVAILABILITY_PATH", "WEEKDAY_NAMES", "load_personal_availability",
           "parse_weekly_availability", "save_personal_availability"]


def parse_weekly_availability(payload: str | dict | None) -> AvailabilityContext | None:
    """입력 값: 요일별 가능 시간 JSON 문자열/객체(또는 weekly_availability 래퍼) 또는 None
    출력 값: 검증된 가능 시간 문맥 또는 None
    기능: 공용 파서로 시간 형식, 길이, 다음 날까지 이어지는 구간의 겹침을 검사합니다.
    """
    return parse_availability_context(payload)


def save_personal_availability(payload: str | dict, path: Path | None = None) -> AvailabilityContext:
    """입력 값: 가능 시간 JSON과 설정 파일 경로
    출력 값: 저장한 가능 시간 문맥
    기능: 검증한 시간을 start/duration_minutes 형식으로 원자적으로 기록합니다.
    """
    return save_availability_profile(payload, path or USER_AVAILABILITY_PATH)


def load_personal_availability(path: Path | None = None) -> AvailabilityContext | None:
    """입력 값: 개인 가능 시간 설정 파일 경로
    출력 값: 저장된 가능 시간 문맥 또는 파일이 없으면 None
    기능: 예전 start/end 형식과 새 duration 형식을 모두 읽으며 파일을 다시 쓰지 않습니다.
    """
    return load_availability_profile(path or USER_AVAILABILITY_PATH)
