"""프로젝트 실행 및 변경 가능한 기본 설정을 모아 둡니다."""
from __future__ import annotations

from pathlib import Path

import openrouter_client

# 프로젝트 최상위 폴더와 비밀 설정 파일의 위치입니다.
PROJECT_ROOT = Path(__file__).resolve().parent.parent
DOTENV_PATH = PROJECT_ROOT / ".env"

# 입출력 형식과 기본 일정 생성 설정입니다.
DATETIME_FORMAT = "%Y/%m/%d/%H/%M"
DATETIME_PATTERN = r"\d{4}/\d{2}/\d{2}/\d{2}/\d{2}"
CSV_HEADER = ("schedule_id", "start_date", "end_date", "topic")
LEGACY_CSV_HEADER = CSV_HEADER[:3]
PROBLEM_RESULTS_HEADER = ("problem_id", "topic", "possible_score", "earned_score")
FIRST_SCHEDULE_ID = 1
EXTERNAL_SCHEDULE_ID_PREFIX = "external"
STUDY_SCHEDULE_ID_PREFIX = "study"
CSV_DELIMITER = ","
CSV_LINE_TERMINATOR = "\n"
CSV_INPUT_ENCODING = "utf-8-sig"
CSV_OUTPUT_ENCODING = "utf-8"
DEFAULT_CERTIFICATION_ID = "computer_specialist_level_2"
# Legacy request field retained for compatibility. Actual study dates and
# session durations are selected from the user's availability and API plan.
DEFAULT_STUDY_DAYS_PER_WEEK = 5


def load_api_key() -> str:
    """입력 값: 없음
    출력 값: OpenRouter API 키 문자열
    기능: OS 환경 변수를 먼저 확인하고, 없으면 backend/.env에서 OPENROUTER_API_KEY만 읽습니다.
    """
    # 모델 요청 직전에만 호출하므로 결정론적 경로와 사전 거절은 비밀 설정을 읽지 않습니다.
    return openrouter_client.load_api_key((DOTENV_PATH,))
