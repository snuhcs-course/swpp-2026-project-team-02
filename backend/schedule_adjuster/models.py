"""일정 조정 도메인 객체를 정의합니다."""
from __future__ import annotations

from dataclasses import dataclass, field
from datetime import datetime, time
from typing import Literal


@dataclass(frozen=True)
class ScheduleEntry:
    """CSV 한 행에 해당하는 외부 일정 또는 공부 일정입니다."""
    # 출력에도 유지되는 분류 접두어 포함 일정 ID입니다.
    schedule_id: str
    # 일정 구간은 시작 시각을 포함하고 종료 시각은 포함하지 않습니다.
    start_datetime: datetime
    end_datetime: datetime
    # 일정이 공부와 연결된 경우 토픽명, 외부 일정이면 빈 문자열입니다.
    topic: str = ""

    @property
    def schedule_type(self) -> Literal["external", "study"]:
        """입력 값: 없음
        출력 값: external 또는 study
        기능: ID 접두어를 기준으로 일정 종류를 반환합니다.
        """
        return "external" if self.schedule_id.startswith("external-") else "study"


@dataclass(frozen=True)
class AvailabilityWindow:
    """사용자가 특정 요일에 공부할 수 있는 하루 내 시간 구간입니다."""
    # 구간 시작 및 종료 시각이며 종료 시각은 포함하지 않습니다.
    start_time: time
    end_time: time


@dataclass(frozen=True)
class WeeklyAvailability:
    """월요일부터 일요일까지 반복 적용되는 개인 공부 가능 시간입니다."""
    # weekday_windows[index]는 월요일=0 ... 일요일=6의 가능 시간 목록입니다.
    weekday_windows: tuple[tuple[AvailabilityWindow, ...], ...]

    def for_weekday(self, weekday: int) -> tuple[AvailabilityWindow, ...]:
        """입력 값: 월요일 0부터 일요일 6까지의 요일 번호
        출력 값: 해당 요일에 가능한 시간 구간
        기능: 지정한 요일의 개인 공부 가능 시간을 반환합니다.
        """
        return self.weekday_windows[weekday]


@dataclass(frozen=True)
class AdjustmentRequest:
    """일정 조정에 필요한 사용자 입력입니다."""
    # 조정 대상 일정 ID와 원본 CSV에서 읽은 전체 일정입니다.
    schedule_id: str
    original_schedule: tuple[ScheduleEntry, ...]
    # 개인 주간 가능 시간이며, 없으면 기존 조정 규칙을 사용합니다.
    weekly_availability: WeeklyAvailability | None = None


@dataclass
class ToolTrace:
    """Agent가 호출한 도구의 이름과 인자를 순서대로 저장합니다."""
    # 모델이 선택해 실행한 도구 호출 기록입니다.
    calls: list[dict] = field(default_factory=list)


@dataclass(frozen=True)
class AdjustmentOutcome:
    """도구가 완료한 조정 작업의 요약입니다."""
    # 조정 유형, 대상 ID, 결과 전체 일정입니다.
    action: Literal["rescheduled_study", "cancelled_external"]
    schedule_id: str
    schedule: tuple[ScheduleEntry, ...]
    message: str

