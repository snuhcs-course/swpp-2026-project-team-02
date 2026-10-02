"""명령줄 입출력 어댑터입니다."""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

from .agent import GeminiAdjustmentAgent
from .availability import load_personal_availability
from .csv_io import parse_schedule_csv, render_schedule_csv
from .models import AdjustmentRequest
from .settings import USER_AVAILABILITY_PATH

# CLI의 CSV 파일 인코딩 설정입니다.
INPUT_ENCODING = "utf-8-sig"


def main() -> int:
    """입력 값: 명령줄의 schedule_id와 원본 일정 CSV 경로
    출력 값: 성공 0 또는 오류 2 종료 코드
    기능: 일정을 조정하고 결과 CSV를 표준 출력으로 기록합니다.
    """
    parser = argparse.ArgumentParser(description="Reschedule a failed study task or remove a cancelled external event")
    parser.add_argument("--schedule-id", required=True, help="Target ID, e.g. study-104 or external-101")
    parser.add_argument("--schedule-csv", required=True, help="Original schedule CSV with schedule_id, start_date, end_date, and optional topic columns")
    parser.add_argument("--availability-file", help=f"Optional weekly availability JSON; defaults to {USER_AVAILABILITY_PATH}")
    args = parser.parse_args()
    try:
        schedule_path = Path(args.schedule_csv)  # 입력 일정 CSV 파일 경로입니다.
        original = parse_schedule_csv(schedule_path.read_text(encoding=INPUT_ENCODING))
        availability_path = Path(args.availability_file) if args.availability_file else USER_AVAILABILITY_PATH
        weekly_availability = load_personal_availability(availability_path)  # License Planner와 공유하는 개인 시간표입니다.
        request = AdjustmentRequest(args.schedule_id, original, weekly_availability)  # 조정 도메인 요청 객체입니다.
        tools, summary = GeminiAdjustmentAgent().adjust(request)
        if tools.outcome is None:
            raise RuntimeError("No schedule adjustment was applied.")
        sys.stdout.write(render_schedule_csv(tools.schedule))
        print(f"# Action: {tools.outcome.action}; target: {tools.outcome.schedule_id}", file=sys.stderr)
        print(f"# Tool calls: {', '.join(call['tool'] for call in tools.trace.calls)}", file=sys.stderr)
        if summary:
            print(f"# Agent: {summary}", file=sys.stderr)
        return 0
    except (OSError, ValueError, RuntimeError) as error:
        print(f"ERROR: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
