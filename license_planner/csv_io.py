"""CSV adapters for user busy periods and generated study rows."""
from __future__ import annotations

import csv
import io
import math
import re
from datetime import datetime
from typing import Iterable

from .models import BusyPeriod, ProblemResult, StudyBlock
from .settings import (CSV_DELIMITER, CSV_HEADER, CSV_LINE_TERMINATOR,
                      DATETIME_FORMAT, DATETIME_PATTERN, EXTERNAL_SCHEDULE_ID_PREFIX,
                      LEGACY_CSV_HEADER, PROBLEM_RESULTS_HEADER)


def parse_datetime(value: str) -> datetime:
    """입력 값: YYYY/MM/DD/HH/MM 날짜·시간 문자열
    출력 값: 날짜·시간 객체
    기능: 지정된 형식에 맞는지 검사하고 datetime으로 변환합니다.
    """
    value = value.strip()
    if not re.fullmatch(DATETIME_PATTERN, value):
        raise ValueError("datetime must use exact YYYY/MM/DD/HH/MM format")
    return datetime.strptime(value, DATETIME_FORMAT)


def parse_problem_results_csv(content: str | None) -> tuple[ProblemResult, ...]:
    """입력 값: 문제별 점수 CSV 문자열 또는 None
    출력 값: ProblemResult 객체 튜플
    기능: 문제 ID, 토픽, 배점, 획득 점수를 검증하고 읽습니다.
    """
    if not content or not content.strip():
        return ()
    rows = list(csv.reader(io.StringIO(content), delimiter=CSV_DELIMITER))
    if rows and tuple(cell.strip().lower() for cell in rows[0]) == PROBLEM_RESULTS_HEADER:
        rows = rows[1:]
    results: list[ProblemResult] = []
    seen_ids: set[int] = set()
    for row_number, row in enumerate(rows, start=2):
        if len(row) != len(PROBLEM_RESULTS_HEADER):
            raise ValueError(f"problem results row {row_number} must contain exactly 4 columns")
        try:
            problem_id = int(row[0].strip())
            possible_score = float(row[2].strip())
            earned_score = float(row[3].strip())
        except ValueError as error:
            raise ValueError(f"problem results row {row_number} has an invalid number") from error
        topic = row[1].strip()
        if problem_id < 1 or problem_id in seen_ids:
            raise ValueError(f"problem results row {row_number} ID must be a unique positive integer")
        if not topic:
            raise ValueError(f"problem results row {row_number} topic cannot be empty")
        if (not math.isfinite(possible_score) or not math.isfinite(earned_score)
                or possible_score <= 0 or not 0 <= earned_score <= possible_score):
            raise ValueError(f"problem results row {row_number} must satisfy 0 <= earned_score <= possible_score")
        seen_ids.add(problem_id)
        results.append(ProblemResult(problem_id, topic, possible_score, earned_score))
    return tuple(results)


def parse_busy_csv(content: str | None) -> tuple[BusyPeriod, ...]:
    """입력 값: 일정 CSV 문자열 또는 None
    출력 값: 바쁜 일정 객체의 튜플
    기능: 각 일정 ID와 시작일/종료일을 읽고 잘못된 행은 설명과 함께 거부합니다.
    """
    if not content or not content.strip():
        return ()
    reader = csv.reader(io.StringIO(content), delimiter=CSV_DELIMITER)  # 입력 원본의 CSV 행입니다.
    rows = list(reader)  # 헤더 판단과 행 번호 검증을 위해 전체 입력을 읽습니다.
    header = tuple(cell.strip().lower() for cell in rows[0]) if rows else ()  # 첫 행의 헤더 후보입니다.
    korean_header = (len(header) in (3, 4) and "id" in header[0]
                     and "시작" in header[1] and "끝" in header[2])
    has_header = header in (CSV_HEADER, LEGACY_CSV_HEADER) or korean_header
    if has_header:
        rows = rows[1:]
    periods: list[BusyPeriod] = []  # 검증을 마친 기존 일정 목록입니다.
    seen_ids: set[str] = set()  # 중복 외부 일정 ID를 막습니다.
    for row_number, row in enumerate(rows, start=1):
        if len(row) not in (3, 4):
            raise ValueError(f"busy CSV row {row_number} must contain 3 or 4 columns")
        schedule_id = row[0].strip()
        if not schedule_id:
            raise ValueError(f"busy CSV row {row_number} has an empty schedule ID")
        if not schedule_id.isdigit() or int(schedule_id) < 1:
            raise ValueError(f"busy CSV row {row_number} schedule ID must be a positive integer")
        schedule_id = str(int(schedule_id))  # 입력 ID도 선행 0이 없는 단순 숫자로 정규화합니다.
        if schedule_id in seen_ids:
            raise ValueError(f"busy CSV row {row_number} has a duplicate schedule ID")
        seen_ids.add(schedule_id)
        try:
            start, end = parse_datetime(row[1]), parse_datetime(row[2])
        except ValueError as error:
            raise ValueError(f"busy CSV row {row_number} dates must use YYYY/MM/DD/HH/MM") from error
        if start >= end:
            raise ValueError(f"busy CSV row {row_number} start must be before end")
        topic = row[3].strip() if len(row) == 4 else ""
        periods.append(BusyPeriod(schedule_id, start, end, topic))
    return tuple(periods)


def render_schedule_csv(busy: Iterable[BusyPeriod], study: Iterable[StudyBlock]) -> str:
    """입력 값: 기존 바쁜 일정과 새 공부 일정
    출력 값: 헤더가 포함된 CSV 문자열
    기능: 두 종류의 일정을 시작 시각순으로 YYYY/MM/DD/HH/MM 형식에 맞춰 직렬화합니다.
    """
    output = io.StringIO(newline="")  # 메모리에서 CSV 문자열을 생성할 출력 버퍼입니다.
    writer = csv.writer(output, delimiter=CSV_DELIMITER, lineterminator=CSV_LINE_TERMINATOR)  # CSV 직렬화 도구입니다.
    writer.writerow(CSV_HEADER)
    rows = [(f"{EXTERNAL_SCHEDULE_ID_PREFIX}-{int(item.schedule_id)}", item.start_datetime, item.end_datetime, item.topic)
            for item in busy]  # 외부 일정 ID에 출처 구분 접두어를 붙입니다.
    rows += [(item.schedule_id, item.start_datetime, item.end_datetime, item.topic) for item in study]
    rows.sort(key=lambda row: (row[1], row[2], row[0]))
    for schedule_id, start, end, topic in rows:
        writer.writerow((schedule_id, start.strftime(DATETIME_FORMAT), end.strftime(DATETIME_FORMAT), topic))
    return output.getvalue()

