"""일정 조정 프로그램의 기본값과 API 설정을 관리합니다."""
from __future__ import annotations

from pathlib import Path

import openrouter_client

# 이전 프로그램과 설정을 분리해 이 프로그램 폴더의 전용 .env를 읽습니다.
PROGRAM_DIR = Path(__file__).resolve().parent
DOTENV_PATH = PROGRAM_DIR / ".env"
WORKSPACE_DOTENV_PATH = PROGRAM_DIR.parent / ".env"
USER_AVAILABILITY_PATH = PROGRAM_DIR.parent / "config" / "user_availability.json"  # 두 일정 프로그램이 공유하는 사용자 가능 시간 설정입니다.

# Agent 설정: 반복 제한과 요청별 추론·출력 한도입니다. 모델과 timeout은 openrouter_client.py에 있습니다.
AGENT_MAX_STEPS = 5  # 요청당 허용하는 Agent 대화/도구 실행 반복 횟수입니다.
AGENT_REASONING_EFFORT = "none"  # 도구 선택만 하므로 추론을 끕니다.
AGENT_MAX_OUTPUT_TOKENS = 2048  # 모델 요청 하나의 출력 토큰 한도입니다.

# CSV 열 이름과 날짜/ID 형식입니다.
CSV_HEADER = ("schedule_id", "start_date", "end_date", "topic")  # 새 일정 CSV의 열 순서입니다.
LEGACY_CSV_HEADER = CSV_HEADER[:3]  # topic 열이 없던 이전 입력 CSV 헤더입니다.
CSV_DELIMITER = ","  # CSV 열 구분 문자입니다.
CSV_LINE_TERMINATOR = "\n"  # 결과 CSV 행 끝 문자입니다.
DATETIME_FORMAT = "%Y/%m/%d/%H/%M"  # 일정 입출력 날짜·시간 형식입니다.
DATETIME_PATTERN = r"\d{4}/\d{2}/\d{2}/\d{2}/\d{2}"  # 날짜·시간 문자열 모양 검사식입니다.
EXTERNAL_ID_PREFIX = "external-"  # 취소 대상 외부 일정 ID 접두어입니다.
STUDY_ID_PREFIX = "study-"  # 재조정 대상 공부 일정 ID 접두어입니다.
FIRST_STUDY_ID = 1  # 허용하는 최소 ID 번호입니다.

# Agent가 도구 선택 시 따르는 최소 지시입니다.
AGENT_SYSTEM_PROMPT = (
    "You adjust a certification schedule only through the supplied tools. "
    "First inspect the requested schedule ID. For a study schedule with a non-empty topic, "
    "inspect the sessions for that exact topic before calling reschedule_failed_study; the "
    "failed session must be placed after existing sessions of the same topic and within the "
    "personal weekly availability returned by inspect_schedule when it is present. For a study "
    "schedule with no topic, call reschedule_failed_study after inspection. If it is an "
    "external schedule, call remove_cancelled_external_schedule. Never invent or directly edit rows. "
    "Explain tool errors clearly and summarize a successful adjustment."
)


def load_api_key() -> str:
    """입력 값: 없음
    출력 값: OpenRouter API 키 문자열
    기능: OS 환경 변수, schedule_adjuster/.env, backend/.env 순서로 OPENROUTER_API_KEY만 읽습니다.
    """
    return openrouter_client.load_api_key((DOTENV_PATH, WORKSPACE_DOTENV_PATH))


def load_model() -> str:
    """Return the optional server model override from either supported .env file."""
    return openrouter_client.load_model((DOTENV_PATH, WORKSPACE_DOTENV_PATH))
