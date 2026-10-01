"""개인 가능 시간 JSON을 읽고 검증합니다."""
from __future__ import annotations

import json
import re
from datetime import time
from pathlib import Path

from .models import AvailabilityWindow, WeeklyAvailability
from .settings import USER_AVAILABILITY_PATH

# 지원하는 요일 순서와 24시간 HH:MM 형식입니다.
WEEKDAY_NAMES = ("monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday")
TIME_PATTERN = re.compile(r"(?:[01]\d|2[0-3]):[0-5]\d")


def parse_weekly_availability(payload: str | dict | None) -> WeeklyAvailability | None:
    """입력 값: 주간 가능 시간 JSON 문자열/객체 또는 None
    출력 값: 검증된 주간 가능 시간 또는 None
    기능: 요일, 시간 형식, 구간 순서와 중복 여부를 확인해 객체로 변환합니다.
    """
    if payload is None:
        return None
    try:
        data = json.loads(payload) if isinstance(payload, str) else payload
    except json.JSONDecodeError as error:
        raise ValueError(f"availability JSON is invalid: {error.msg}") from error
    if not isinstance(data, dict):
        raise ValueError("availability must be a JSON object keyed by weekday")
    if "weekly_availability" in data:
        data = data["weekly_availability"]
    if not isinstance(data, dict):
        raise ValueError("weekly_availability must be a JSON object")
    unknown_days = set(data) - set(WEEKDAY_NAMES)
    if unknown_days:
        raise ValueError(f"unknown weekday names: {', '.join(sorted(unknown_days))}")

    days: list[tuple[AvailabilityWindow, ...]] = []
    for weekday in WEEKDAY_NAMES:
        raw_windows = data.get(weekday, [])
        if not isinstance(raw_windows, list):
            raise ValueError(f"{weekday} must contain a JSON list of time windows")
        windows: list[AvailabilityWindow] = []
        for position, raw_window in enumerate(raw_windows, start=1):
            if not isinstance(raw_window, dict) or set(raw_window) != {"start", "end"}:
                raise ValueError(f"{weekday} window {position} must contain only start and end")
            start_text, end_text = raw_window["start"], raw_window["end"]
            if (not isinstance(start_text, str) or not TIME_PATTERN.fullmatch(start_text)
                    or not isinstance(end_text, str) or not TIME_PATTERN.fullmatch(end_text)):
                raise ValueError(f"{weekday} window {position} times must use HH:MM")
            start_time, end_time = time.fromisoformat(start_text), time.fromisoformat(end_text)
            if start_time >= end_time:
                raise ValueError(f"{weekday} window {position} start must be before end")
            windows.append(AvailabilityWindow(start_time, end_time))
        windows.sort(key=lambda item: item.start_time)
        if any(current.start_time < previous.end_time
               for previous, current in zip(windows, windows[1:])):
            raise ValueError(f"{weekday} availability windows cannot overlap")
        days.append(tuple(windows))
    return WeeklyAvailability(tuple(days))


def load_personal_availability(path: Path = USER_AVAILABILITY_PATH) -> WeeklyAvailability | None:
    """입력 값: 가능 시간 JSON 파일 경로
    출력 값: 저장된 주간 가능 시간 또는 파일이 없으면 None
    기능: License Planner와 공유하는 개인 시간표 파일을 읽고 검증합니다.
    """
    if not path.exists():
        return None
    try:
        payload = json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as error:
        raise ValueError("saved personal availability JSON is invalid") from error
    return parse_weekly_availability(payload)
