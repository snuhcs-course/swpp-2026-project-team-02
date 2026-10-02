"""Deterministic assessment and datetime scheduling rules."""
from __future__ import annotations

from collections import defaultdict
from datetime import date, datetime, timedelta
from math import ceil

from .catalog import Certification
from .models import AvailabilityWindow, PlanRequest, ProblemResult, StudyBlock, TopicStatistics
from .settings import (DEFAULT_SESSION_MINUTES,
                       DEFAULT_STUDY_SESSIONS_PER_DAY, DEFAULT_STUDY_WINDOW_END,
                       DEFAULT_STUDY_WINDOW_START, LEVEL_ORDER,
                       FIRST_SCHEDULE_ID, STUDY_SCHEDULE_ID_PREFIX,
                       LEVEL_SESSION_MULTIPLIERS,
                       UNKNOWN_TOPIC_EXTRA_SESSIONS, WEAK_TOPIC_EXTRA_SESSIONS)

def summarize_topic_results(results: tuple[ProblemResult, ...]) -> tuple[TopicStatistics, ...]:
    """입력 값: 문제별 배점·획득 점수 튜플
    출력 값: 점수 낮은 토픽 순서의 통계 튜플
    기능: 토픽별 배점과 획득 점수를 합산해 백분율을 계산합니다.
    """
    totals: dict[str, list[float | int]] = defaultdict(lambda: [0.0, 0.0, 0])
    for result in results:
        totals[result.topic][0] += result.possible_score
        totals[result.topic][1] += result.earned_score
        totals[result.topic][2] += 1
    stats = [TopicStatistics(topic, values[0], values[1], values[1] / values[0] * 100, values[2])
             for topic, values in totals.items()]
    return tuple(sorted(stats, key=lambda item: (item.score_percent, item.topic.casefold())))


def readiness_level(profile_text: str | None, topic_statistics: tuple[TopicStatistics, ...],
                    cert: Certification) -> str:
    """입력 값: 자기평가 문장, 토픽별 결과 통계, 자격증 설정
    출력 값: beginner/intermediate/advanced 준비 수준
    기능: 총점 환산 결과와 자기평가 중 더 많은 학습이 필요한 수준을 선택합니다.
    """
    candidates: list[str] = []
    possible = sum(item.possible_score for item in topic_statistics)
    earned = sum(item.earned_score for item in topic_statistics)
    if possible > 0:
        candidates.append(cert.level_from_score(earned / possible * 100))
    if profile_text:
        lowered = profile_text.casefold()
        for keywords, level in cert.self_assessment_keywords:
            if any(word in lowered for word in keywords):
                candidates.append(level)
                break
    if not candidates:
        return "intermediate"
    return min(candidates, key=LEVEL_ORDER.__getitem__)


def _overlaps(start: datetime, end: datetime, busy_start: datetime, busy_end: datetime) -> bool:
    """입력 값: 두 일정 구간의 시작과 종료 시각
    출력 값: 구간이 겹치면 True, 아니면 False
    기능: 시작 포함·종료 제외 구간의 충돌 여부를 검사합니다.
    """
    return start < busy_end and busy_start < end


def _sessions_for_topic(stat: TopicStatistics | None, level: str, cert: Certification) -> int:
    """입력 값: 토픽 통계(또는 None), 준비 수준, 자격증 설정
    출력 값: 해당 토픽에 배정할 세션 수
    기능: 점수가 낮거나 결과가 없는 토픽에 추가 학습 세션을 배정합니다.
    """
    multiplier = LEVEL_SESSION_MULTIPLIERS[level]
    base_sessions = ceil(cert.default_study_days_per_topic * multiplier)
    if stat is None:
        return base_sessions + UNKNOWN_TOPIC_EXTRA_SESSIONS
    deficit = max(0.0, 100.0 - stat.score_percent) / 100.0
    weakness_sessions = ceil(deficit * WEAK_TOPIC_EXTRA_SESSIONS)
    return base_sessions + weakness_sessions


def generate_schedule(request: PlanRequest, cert: Certification,
                      topic_statistics: tuple[TopicStatistics, ...], level: str) -> list[StudyBlock]:
    """입력 값: 사용자 요청, 자격증 설정, 토픽 통계, 평가 수준
    출력 값: 시작·종료 시각이 있는 공부 일정 블록 목록
    기능: 취약 토픽 우선으로 공부 세션을 바쁜 일정과 겹치지 않게 배치합니다.
    """
    if request.preparation_start is None or request.exam_date is None:
        raise ValueError("preparation_start and exam_date are required before generating a dated schedule")
    if request.preparation_start >= request.exam_date:
        raise ValueError("preparation_start must be before exam_date")
    if not 1 <= request.study_days_per_week <= 7:
        raise ValueError("study_days_per_week must be an integer from 1 to 7")

    stats_by_topic = {item.topic: item for item in topic_statistics}
    topics = ([item.topic for item in topic_statistics] if topic_statistics
              else list(cert.topics))
    required_sessions = [(topic, _sessions_for_topic(stats_by_topic.get(topic), level, cert))
                         for topic in topics]

    busy_periods = list(request.busy_periods)
    used_ids = {int(period.schedule_id) for period in busy_periods}
    next_id = max(used_ids, default=FIRST_SCHEDULE_ID - 1) + 1
    blocks: list[StudyBlock] = []
    sessions_for_day: dict[date, int] = defaultdict(int)
    current_day = request.preparation_start.date()
    last_day = request.exam_date.date()
    duration = timedelta(minutes=DEFAULT_SESSION_MINUTES)
    while current_day <= last_day:
        if request.weekly_availability is not None:
            daily_windows = request.weekly_availability.for_weekday(current_day.weekday())
        elif current_day.weekday() < request.study_days_per_week:
            daily_windows = (AvailabilityWindow(DEFAULT_STUDY_WINDOW_START, DEFAULT_STUDY_WINDOW_END),)
        else:
            daily_windows = ()
        for availability_window in daily_windows:
            window_start = datetime.combine(current_day, availability_window.start_time)
            window_end = datetime.combine(current_day, availability_window.end_time)
            if current_day == request.preparation_start.date():
                window_start = max(window_start, request.preparation_start)
            if current_day == request.exam_date.date():
                window_end = min(window_end, request.exam_date)
            candidate = window_start
            while (required_sessions and candidate + duration <= window_end
                   and sessions_for_day[current_day] < DEFAULT_STUDY_SESSIONS_PER_DAY):
                conflict = next((busy for busy in busy_periods
                                 if _overlaps(candidate, candidate + duration,
                                              busy.start_datetime, busy.end_datetime)), None)
                if conflict is None:
                    topic, remaining = required_sessions[0]
                    while next_id in used_ids:
                        next_id += 1
                    blocks.append(StudyBlock(f"{STUDY_SCHEDULE_ID_PREFIX}-{next_id}",
                                             candidate, candidate + duration, topic))
                    used_ids.add(next_id)
                    next_id += 1
                    sessions_for_day[current_day] += 1
                    if remaining == 1:
                        required_sessions.pop(0)
                    else:
                        required_sessions[0] = (topic, remaining - 1)
                    candidate += duration
                    continue
                candidate = max(candidate + timedelta(minutes=1), conflict.end_datetime)
        current_day += timedelta(days=1)

    if required_sessions:
        raise ValueError("NOT_ENOUGH_TIME: available conflict-free study slots do not fit the topic plan")
    return blocks
