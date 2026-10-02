"""프로젝트 실행 및 변경 가능한 기본 설정을 모아 둡니다."""
from __future__ import annotations

import os
import json
from datetime import time
from pathlib import Path

# 프로젝트 최상위 폴더와 비밀 설정 파일의 위치입니다.
PROJECT_ROOT = Path(__file__).resolve().parent.parent
DOTENV_PATH = PROJECT_ROOT / ".env"

# API 키만 .env/OS 환경변수에서 읽습니다. 나머지 Agent 설정은 agent.py에 있습니다.
GEMINI_API_KEY = ""

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
ALLOWED_READINESS_LEVELS = ("beginner", "intermediate", "advanced")
LEVEL_ORDER = {"beginner": 0, "intermediate": 1, "advanced": 2}
SCHEDULE_CONFIG_PATH = PROJECT_ROOT / "config" / "license_planner.json"
DEFAULT_SCHEDULE_CONFIG = {
    "study_window_start": "18:00", "study_window_end": "22:00",
    "session_length_minutes": 60, "max_sessions_per_day": 1,
    "study_days_per_week": 5, "sessions_per_topic": 2,
    "weak_topic_extra_sessions": 3, "unknown_topic_extra_sessions": 1,
    "level_session_multipliers": {"beginner": 2.0, "intermediate": 1.0, "advanced": 0.5},
}


def _load_schedule_config(path: Path = SCHEDULE_CONFIG_PATH) -> dict:
    """입력 값: JSON 설정 파일 경로
    출력 값: 검증된 공부 일정 설정 딕셔너리
    기능: 외부 JSON 설정을 기본값과 병합하고 값의 범위를 검증합니다.
    """
    config = dict(DEFAULT_SCHEDULE_CONFIG)
    config["level_session_multipliers"] = dict(DEFAULT_SCHEDULE_CONFIG["level_session_multipliers"])
    if path.exists():
        supplied = json.loads(path.read_text(encoding="utf-8"))
        if not isinstance(supplied, dict):
            raise ValueError("schedule config root must be a JSON object")
        unknown_keys = set(supplied) - set(DEFAULT_SCHEDULE_CONFIG)
        if unknown_keys:
            raise ValueError(f"unknown schedule config keys: {', '.join(sorted(unknown_keys))}")
        config.update(supplied)
        if "level_session_multipliers" in supplied:
            config["level_session_multipliers"].update(supplied["level_session_multipliers"])
    try:
        start = time.fromisoformat(config["study_window_start"])
        end = time.fromisoformat(config["study_window_end"])
        for key in ("session_length_minutes", "max_sessions_per_day", "study_days_per_week", "sessions_per_topic"):
            if not isinstance(config[key], int) or isinstance(config[key], bool) or config[key] <= 0:
                raise ValueError(f"{key} must be a positive integer")
        if config["study_days_per_week"] > 7:
            raise ValueError("study_days_per_week must be between 1 and 7")
        if start >= end or config["session_length_minutes"] > (end.hour * 60 + end.minute - start.hour * 60 - start.minute):
            raise ValueError("study window must fit at least one full session")
        multipliers = config["level_session_multipliers"]
        if any(not isinstance(multipliers.get(level), (int, float)) or multipliers[level] <= 0
               for level in ALLOWED_READINESS_LEVELS):
            raise ValueError("level_session_multipliers must define positive values for all readiness levels")
        for key in ("weak_topic_extra_sessions", "unknown_topic_extra_sessions"):
            if not isinstance(config[key], int) or config[key] < 0:
                raise ValueError(f"{key} must be a non-negative integer")
    except (KeyError, TypeError) as error:
        raise ValueError(f"invalid schedule config: {error}") from error
    return config


_SCHEDULE_CONFIG = _load_schedule_config()
DEFAULT_STUDY_DAYS_PER_WEEK = _SCHEDULE_CONFIG["study_days_per_week"]
DEFAULT_STUDY_DAYS_PER_TOPIC = _SCHEDULE_CONFIG["sessions_per_topic"]
BEGINNER_STUDY_DAY_MULTIPLIER = _SCHEDULE_CONFIG["level_session_multipliers"]["beginner"]
ADVANCED_STUDY_SESSION_MULTIPLIER = _SCHEDULE_CONFIG["level_session_multipliers"]["advanced"]
LEVEL_SESSION_MULTIPLIERS = _SCHEDULE_CONFIG["level_session_multipliers"]
WEAK_TOPIC_EXTRA_SESSIONS = _SCHEDULE_CONFIG["weak_topic_extra_sessions"]
UNKNOWN_TOPIC_EXTRA_SESSIONS = _SCHEDULE_CONFIG["unknown_topic_extra_sessions"]
DEFAULT_SESSION_MINUTES = _SCHEDULE_CONFIG["session_length_minutes"]
DEFAULT_STUDY_WINDOW_START = time.fromisoformat(_SCHEDULE_CONFIG["study_window_start"])
DEFAULT_STUDY_WINDOW_END = time.fromisoformat(_SCHEDULE_CONFIG["study_window_end"])
DEFAULT_STUDY_SESSIONS_PER_DAY = _SCHEDULE_CONFIG["max_sessions_per_day"]
def load_dotenv(path: Path = DOTENV_PATH) -> None:
    """입력 값: .env 파일 경로
    출력 값: 없음
    기능: 파일의 KEY=VALUE 설정을 환경 변수로 읽습니다.
    """
    if not path.exists():
        return
    for raw_line in path.read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        key, value = key.strip(), value.strip().strip("\"'")
        if key == "GEMINI_API_KEY":  # .env에서는 비밀 키 외 설정을 읽지 않습니다.
            os.environ.setdefault(key, value)


# 환경변수는 .env와 OS 환경에서 읽고, 위의 값은 로컬 개발 기본값으로 사용합니다.
load_dotenv()
GEMINI_API_KEY = os.getenv("GEMINI_API_KEY", GEMINI_API_KEY)

