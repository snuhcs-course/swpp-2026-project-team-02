"""프로젝트 실행 및 변경 가능한 기본 설정을 모아 둡니다."""
from __future__ import annotations

import os
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
# Legacy request field retained for compatibility. Actual study dates and
# session durations are selected from the user's availability and API plan.
DEFAULT_STUDY_DAYS_PER_WEEK = 5
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

