"""일정 CSV 파일과 도메인 객체 사이의 변환을 담당합니다."""
from __future__ import annotations

import csv
import io
import re
from datetime import datetime

from .models import ScheduleEntry
from .schedule_logic import classify_schedule_id
from .settings import (CSV_DELIMITER, CSV_HEADER, CSV_LINE_TERMINATOR,
                      DATETIME_FORMAT, DATETIME_PATTERN, LEGACY_CSV_HEADER)


def parse_datetime(value: str) -> datetime:
    """입력 값: YYYY/MM/DD/HH/MM 날짜·시간 문자열
    출력 값: 날짜·시간 객체
    기능: 지정된 형식이 맞는지 검사하고 datetime으로 변환합니다.
    """
    value = value.strip()
    if not re.fullmatch(DATETIME_PATTERN, value):
        raise ValueError("datetime must use exact YYYY/MM/DD/HH/MM format")
    return datetime.strptime(value, DATETIME_FORMAT)


def parse_schedule_csv(content: str) -> tuple[ScheduleEntry, ...]:
    """입력 값: 헤더 포함 일정 CSV 문자열
    출력 값: ScheduleEntry 객체 튜플
    기능: ID 종류와 날짜를 검증하고 기존 일정 전체를 읽습니다.
    """
    if not content.strip():
        raise ValueError("INVALID_CSV: schedule CSV is empty.")
    rows = list(csv.reader(io.StringIO(content), delimiter=CSV_DELIMITER))
    header = tuple(cell.strip().lower() for cell in rows[0]) if rows else ()
    is_english_header = header in (CSV_HEADER, LEGACY_CSV_HEADER)
    is_korean_header = (len(header) in (3, 4) and "id" in header[0]
                        and "시작" in header[1] and "끝" in header[2])
    if is_english_header or is_korean_header:
        rows = rows[1:]
    entries: list[ScheduleEntry] = []  # 검증을 통과한 원본 일정 행입니다.
    seen_ids: set[str] = set()  # 중복 일정 ID를 차단하기 위한 집합입니다.
    for row_number, row in enumerate(rows, start=2):
        if len(row) not in (3, 4):
            raise ValueError(f"INVALID_CSV: row {row_number} must have 3 or 4 columns.")
        schedule_id = row[0].strip()
        if classify_schedule_id(schedule_id) is None:
            raise ValueError(f"INVALID_ID: row {row_number} ID must use external-N or study-N format.")
        if schedule_id in seen_ids:
            raise ValueError(f"DUPLICATE_ID: schedule_id {schedule_id!r} appears more than once.")
        seen_ids.add(schedule_id)
        try:
            start_date, end_date = parse_datetime(row[1]), parse_datetime(row[2])
        except ValueError as error:
            raise ValueError(f"INVALID_DATE: row {row_number} dates must use YYYY/MM/DD/HH/MM.") from error
        if start_date >= end_date:
            raise ValueError(f"INVALID_DATE_RANGE: row {row_number} start must be before end.")
        topic = row[3].strip() if len(row) == 4 else ""
        entries.append(ScheduleEntry(schedule_id, start_date, end_date, topic))
    return tuple(entries)


def render_schedule_csv(schedule: tuple[ScheduleEntry, ...]) -> str:
    """입력 값: 일정 객체 튜플
    출력 값: 네 열(schedule_id, start_date, end_date, topic) 형식의 CSV 문자열
    기능: 시작 시각과 ID순으로 일정을 정렬해 YYYY/MM/DD/HH/MM 형식으로 출력합니다.
    """
    output = io.StringIO(newline="")  # CSV 문자열 생성 버퍼입니다.
    writer = csv.writer(output, delimiter=CSV_DELIMITER, lineterminator=CSV_LINE_TERMINATOR)  # CSV 직렬화기입니다.
    writer.writerow(CSV_HEADER)
    for entry in sorted(schedule, key=lambda item: (item.start_datetime, item.schedule_id)):
        writer.writerow((entry.schedule_id, entry.start_datetime.strftime(DATETIME_FORMAT),
                         entry.end_datetime.strftime(DATETIME_FORMAT), entry.topic))
    return output.getvalue()

