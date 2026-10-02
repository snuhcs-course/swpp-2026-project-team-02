"""개인용 앱의 주간 공부 가능 시간을 JSON 파일로 관리합니다."""
from __future__ import annotations

import json
import os
import re
import tempfile
from datetime import time
from pathlib import Path

from .models import AvailabilityWindow, WeeklyAvailability
from .settings import PROJECT_ROOT

# 개인용 앱에서 현재 사용자의 반복 주간 가능 시간을 보관하는 단일 파일입니다.
USER_AVAILABILITY_PATH = PROJECT_ROOT / "config" / "user_availability.json"
WEEKDAY_NAMES = ("monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday")
TIME_PATTERN = re.compile(r"(?:[01]\d|2[0-3]):[0-5]\d")


def parse_weekly_availability(payload: str | dict | None) -> WeeklyAvailability | None:
    """입력 값: 주간 가능 시간 JSON 문자열/객체 또는 None
    출력 값: 검증된 WeeklyAvailability 또는 None
    기능: 요일별 시간 구간, 시간 형식, 중복 및 겹침을 검사해 도메인 객체로 변환합니다.
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
        windows.sort(key=lambda window: window.start_time)
        for previous, current in zip(windows, windows[1:]):
            if current.start_time < previous.end_time:
                raise ValueError(f"{weekday} availability windows cannot overlap")
        days.append(tuple(windows))
    return WeeklyAvailability(tuple(days))


def save_personal_availability(payload: str | dict,
                               path: Path = USER_AVAILABILITY_PATH) -> WeeklyAvailability:
    """입력 값: 주간 가능 시간 JSON과 설정 파일 경로
    출력 값: 저장한 WeeklyAvailability 객체
    기능: 가능 시간을 검증해 개인용 설정 JSON 파일에 원자적으로 기록합니다.
    """
    availability = parse_weekly_availability(payload)
    if availability is None:
        raise ValueError("availability cannot be None when saving a user profile")
    normalized = {
        "weekly_availability": {
            weekday: [{"start": window.start_time.strftime("%H:%M"),
                       "end": window.end_time.strftime("%H:%M")}
                      for window in availability.for_weekday(index)]
            for index, weekday in enumerate(WEEKDAY_NAMES)
        },
    }
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary_path: Path | None = None
    try:
        with tempfile.NamedTemporaryFile("w", encoding="utf-8", newline="\n",
                                         dir=path.parent, suffix=".tmp", delete=False) as temporary_file:
            temporary_path = Path(temporary_file.name)
            json.dump(normalized, temporary_file, ensure_ascii=False, indent=2)
            temporary_file.write("\n")
        os.replace(temporary_path, path)
    finally:
        if temporary_path is not None and temporary_path.exists():
            temporary_path.unlink()
    return availability


def load_personal_availability(path: Path = USER_AVAILABILITY_PATH) -> WeeklyAvailability | None:
    """입력 값: 개인 가능 시간 설정 파일 경로
    출력 값: 저장된 주간 가능 시간 또는 파일이 없으면 None
    기능: 저장된 JSON을 읽고 동일한 검증 규칙으로 객체화합니다.
    """
    if not path.exists():
        return None
    try:
        record = json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as error:
        raise ValueError("saved personal availability JSON is invalid") from error
    if not isinstance(record, dict):
        raise ValueError("saved availability JSON must be an object")
    return parse_weekly_availability(record)
