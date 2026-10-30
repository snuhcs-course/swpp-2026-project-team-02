"""일정 조정 프로그램의 기본값과 API 설정을 관리합니다."""
from __future__ import annotations

import os
from pathlib import Path

# 이전 프로그램과 설정을 분리해 이 프로그램 폴더의 전용 .env를 읽습니다.
PROGRAM_DIR = Path(__file__).resolve().parent
DOTENV_PATH = PROGRAM_DIR / ".env"
WORKSPACE_DOTENV_PATH = PROGRAM_DIR.parent / ".env"
OPENROUTER_API_KEY = ""
OPENROUTER_MODEL = "google/gemini-2.5-flash"
USER_AVAILABILITY_PATH = PROGRAM_DIR.parent / "config" / "user_availability.json"  # 두 일정 프로그램이 공유하는 사용자 가능 시간 설정입니다.

# Agent 설정: OpenRouter 모델, 반복 제한, HTTP 통신 옵션입니다.
OPENROUTER_TIMEOUT_SECONDS = 45
AGENT_MAX_STEPS = 5  # 요청당 허용하는 Agent 대화/도구 실행 반복 횟수입니다.
FUNCTION_CALL_MODE = "AUTO"  # 모델이 함수를 호출할지 선택하도록 하는 모드입니다.

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
    출력 값: Gemini API 키 문자열 또는 빈 문자열
    기능: OS 환경 변수와 프로젝트 루트 .env에서 OpenRouter 키만 읽습니다.
    """
    if not os.getenv("OPENROUTER_API_KEY"):
        for dotenv_path in (DOTENV_PATH, WORKSPACE_DOTENV_PATH):
            if not dotenv_path.exists():
                continue
            for raw_line in dotenv_path.read_text(encoding="utf-8").splitlines():
                line = raw_line.strip()
                if line.startswith("OPENROUTER_API_KEY="):
                    value = line.split("=", 1)[1].strip().strip("\"'")
                    if value:
                        os.environ["OPENROUTER_API_KEY"] = value
                        return value
    return os.getenv("OPENROUTER_API_KEY", OPENROUTER_API_KEY)


def load_model() -> str:
    """Read an optional model override without putting it in source control."""
    if not os.getenv("OPENROUTER_MODEL"):
        for dotenv_path in (DOTENV_PATH, WORKSPACE_DOTENV_PATH):
            if not dotenv_path.exists():
                continue
            for raw_line in dotenv_path.read_text(encoding="utf-8").splitlines():
                line = raw_line.strip()
                if line.startswith("OPENROUTER_MODEL="):
                    value = line.split("=", 1)[1].strip().strip("\"'")
                    if value:
                        os.environ["OPENROUTER_MODEL"] = value
                        return value
    return os.getenv("OPENROUTER_MODEL", OPENROUTER_MODEL)

