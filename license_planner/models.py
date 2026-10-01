"""Domain models shared by the planner, tools, and CSV adapter."""
from __future__ import annotations

from dataclasses import dataclass, field
from datetime import datetime, time
from typing import Optional
from .settings import DEFAULT_CERTIFICATION_ID, DEFAULT_STUDY_DAYS_PER_WEEK


@dataclass(frozen=True)
class BusyPeriod:
    """기존에 사용자가 입력한 바쁜 일정 구간입니다."""
    # 일정 CSV 첫 번째 열의 원본 ID입니다.
    schedule_id: str
    # 기존 일정의 시작/종료 시각입니다. 구간은 시작 포함, 종료 제외로 취급합니다.
    start_datetime: datetime
    end_datetime: datetime
    # 기존 일정 주제(외부 일정 등 주제가 없으면 빈 문자열)입니다.
    topic: str = ""


@dataclass(frozen=True)
class ProblemResult:
    """진단 시험에서 한 문제에 대해 받은 점수입니다."""
    # 양의 정수 문제 ID와 입력에서 받은 토픽명입니다.
    problem_id: int
    topic: str
    # 배점과 실제 획득 점수이며 0 <= 획득 점수 <= 배점입니다.
    possible_score: float
    earned_score: float


@dataclass(frozen=True)
class TopicStatistics:
    """한 토픽의 배점·획득 점수와 정답률 통계입니다."""
    # 통계의 토픽, 배점 합, 획득 점수 합, 백분율 점수입니다.
    topic: str
    possible_score: float
    earned_score: float
    score_percent: float
    problem_count: int


@dataclass(frozen=True)
class AvailabilityWindow:
    """사용자가 특정 요일에 공부할 수 있는 하루 내 시간 구간입니다."""
    # 구간 시작/종료 시각이며 종료는 포함하지 않습니다.
    start_time: time
    end_time: time


@dataclass(frozen=True)
class WeeklyAvailability:
    """월요일부터 일요일까지의 사용자별 주간 공부 가능 시간입니다."""
    # weekday_windows[index]는 월요일=0 ... 일요일=6의 시간 구간 목록입니다.
    weekday_windows: tuple[tuple[AvailabilityWindow, ...], ...]

    def for_weekday(self, weekday: int) -> tuple[AvailabilityWindow, ...]:
        """입력 값: 월요일 0부터 일요일 6까지의 요일 번호
        출력 값: 해당 요일의 시간 구간 튜플
        기능: 주간 가능 시간에서 요청된 요일 구간을 반환합니다.
        """
        return self.weekday_windows[weekday]


@dataclass(frozen=True)
class PlanRequest:
    """앱에서 전달하는 사용자 입력을 묶은 요청 객체입니다."""
    # 수준에 대한 문장형 자기평가와 문제별 진단 결과입니다.
    self_assessment: Optional[str]
    problem_results: tuple[ProblemResult, ...]
    # 다른 시스템에서 전달할 준비 시작일과 시험일입니다.
    preparation_start: Optional[datetime]
    exam_date: Optional[datetime]
    # 기간 내 기존 일정과 자격증/주간 공부일 설정입니다.
    busy_periods: tuple[BusyPeriod, ...] = ()
    certification_id: str = DEFAULT_CERTIFICATION_ID
    study_days_per_week: int = DEFAULT_STUDY_DAYS_PER_WEEK
    # 저장된 개인용 반복 주간 가능 시간입니다.
    weekly_availability: Optional[WeeklyAvailability] = None


@dataclass(frozen=True)
class StudyBlock:
    """프로그램이 새로 생성한 공부 일정 한 행입니다."""
    # 출력 CSV의 일정 ID와 공부일 범위입니다.
    schedule_id: str
    start_datetime: datetime
    end_datetime: datetime
    # 생성 로직이 배정한 주제로 CSV 네 번째 열에 기록됩니다.
    topic: str = ""


@dataclass
class ToolTrace:
    """Agent가 실제로 호출한 도구 이름과 전달 인자를 보관합니다."""
    # 도구 호출 순서대로 기록한 이벤트 목록입니다.
    calls: list[dict] = field(default_factory=list)

