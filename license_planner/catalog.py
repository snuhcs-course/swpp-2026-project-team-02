"""Extensible certification catalog and readiness rules."""
from __future__ import annotations

from dataclasses import dataclass
from .settings import DEFAULT_STUDY_DAYS_PER_TOPIC

# 자격증별 변경 데이터: 시험 범위, 점수 구간, 자기평가 단서입니다.
CERTIFICATION_CONFIGS = {
    "computer_specialist_level_2": {
        "display_name": "컴퓨터활용능력 2급",
        "topics": ("스프레드시트 기본", "함수와 데이터 관리", "차트와 분석", "실기 문제 풀이", "오답 복습"),
        "score_thresholds": ((80, "advanced"), (60, "intermediate"), (0, "beginner")),
        "self_assessment_keywords": (
            (("처음", "모른", "경험 없", "초보"), "beginner"),
            (("익숙", "잘함", "능숙", "경험 많"), "advanced"),
        ),
        "study_days_per_topic": DEFAULT_STUDY_DAYS_PER_TOPIC,
    }
}


@dataclass(frozen=True)
class Certification:
    """한 종류의 자격증에 대한 계획 생성 설정입니다."""
    # 자격증 고유 ID와 화면 표시 이름입니다.
    certification_id: str
    display_name: str
    # 이 자격증의 학습 단원, 점수 경계, 자기평가 단서, 단원별 학습 일수입니다.
    topics: tuple[str, ...]
    score_thresholds: tuple[tuple[float, str], ...]
    self_assessment_keywords: tuple[tuple[tuple[str, ...], str], ...]
    default_study_days_per_topic: int = DEFAULT_STUDY_DAYS_PER_TOPIC

    def level_from_score(self, score: float) -> str:
        """입력 값: 0~100 점수
        출력 값: 설정에서 찾은 수준 문자열
        기능: 점수가 속하는 가장 높은 기준 구간의 수준을 반환합니다.
        """
        for minimum, level in self.score_thresholds:
            if score >= minimum:
                return level
        return "beginner"


# 실행 시 읽기 편한 자격증 객체 목록입니다. 새 자격증은 위 설정 사전에 추가합니다.
CERTIFICATIONS: dict[str, Certification] = {
    cert_id: Certification(
        certification_id=cert_id,
        display_name=config["display_name"],
        topics=config["topics"],
        score_thresholds=config["score_thresholds"],
        self_assessment_keywords=config["self_assessment_keywords"],
        default_study_days_per_topic=config["study_days_per_topic"],
    )
    for cert_id, config in CERTIFICATION_CONFIGS.items()
}


def get_certification(certification_id: str) -> Certification:
    """입력 값: 자격증 ID
    출력 값: 자격증 설정 객체
    기능: 지원 목록에서 설정을 찾거나 이용 가능한 ID를 포함한 오류를 냅니다.
    """
    try:
        return CERTIFICATIONS[certification_id]
    except KeyError:
        supported = ", ".join(sorted(CERTIFICATIONS))
        raise ValueError(f"NOT_FOUND: unsupported certification_id={certification_id!r}. Choose one of: {supported}.") from None

