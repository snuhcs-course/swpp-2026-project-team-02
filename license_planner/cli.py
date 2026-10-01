"""Command-line interface; dates are supplied by the calling app/system."""
from __future__ import annotations

import argparse
import sys

from .agent import GeminiToolAgent
from .csv_io import parse_busy_csv, parse_datetime, parse_problem_results_csv, render_schedule_csv
from .models import PlanRequest
from .settings import (CSV_INPUT_ENCODING, DEFAULT_CERTIFICATION_ID,
                       DEFAULT_STUDY_DAYS_PER_WEEK)
from .user_availability import load_personal_availability, save_personal_availability


def main() -> int:
    """입력 값: 명령줄 인자
    출력 값: 프로세스 종료 코드
    기능: 명령줄 입력을 읽고 Agent를 실행해 CSV와 도구 이력을 출력합니다.
    """
    parser = argparse.ArgumentParser(description="Generate a personalized certification study-plan CSV")
    parser.add_argument("--self-assessment", default=None, help="Free-text current-level description")  # 문장형 수준 입력 옵션입니다.
    parser.add_argument("--preparation-start", default=None, help="System-provided datetime YYYY/MM/DD/HH/MM")  # 다른 시스템이 전달할 시작 시각입니다.
    parser.add_argument("--exam-date", default=None, help="System-provided datetime YYYY/MM/DD/HH/MM")  # 다른 시스템이 전달할 시험 시각입니다.
    parser.add_argument("--results-csv", default=None, help="Path to optional per-problem results CSV")  # 문제별 점수 CSV 경로입니다.
    parser.add_argument("--busy-csv", default=None, help="Path to optional busy-period CSV")  # 기존 일정 CSV 경로입니다.
    parser.add_argument("--certification", default=DEFAULT_CERTIFICATION_ID)  # 자격증 카탈로그 ID입니다.
    parser.add_argument("--study-days-per-week", type=int, default=DEFAULT_STUDY_DAYS_PER_WEEK)  # 주당 공부 가능한 요일 수입니다.
    availability_group = parser.add_mutually_exclusive_group()
    availability_group.add_argument("--availability-json", default=None,
                                    help='Weekly availability JSON, e.g. {"monday":[{"start":"18:00","end":"21:00"}]}')
    availability_group.add_argument("--availability-file", default=None,
                                    help="Path to a JSON file containing weekly availability")
    args = parser.parse_args()  # 파싱된 명령줄 입력을 담습니다.
    try:
        availability_payload = args.availability_json
        if args.availability_file:
            with open(args.availability_file, encoding=CSV_INPUT_ENCODING) as availability_file:
                availability_payload = availability_file.read()
        weekly_availability = (save_personal_availability(availability_payload)
                               if availability_payload is not None else load_personal_availability())
        busy_csv = None  # 기존 일정 파일을 전달하지 않으면 빈 일정 입력으로 처리합니다.
        if args.busy_csv:
            with open(args.busy_csv, encoding=CSV_INPUT_ENCODING, newline="") as csv_file:
                busy_csv = csv_file.read()
        results_csv = None  # 문제 결과 파일이 없으면 자기평가와 기본 토픽 기준으로 처리합니다.
        if args.results_csv:
            with open(args.results_csv, encoding=CSV_INPUT_ENCODING, newline="") as csv_file:
                results_csv = csv_file.read()
        request = PlanRequest(
            self_assessment=args.self_assessment,
            problem_results=parse_problem_results_csv(results_csv),
            preparation_start=parse_datetime(args.preparation_start) if args.preparation_start else None,
            exam_date=parse_datetime(args.exam_date) if args.exam_date else None,
            busy_periods=parse_busy_csv(busy_csv),
            certification_id=args.certification,
            study_days_per_week=args.study_days_per_week,
            weekly_availability=weekly_availability,
        )
        tools, summary = GeminiToolAgent().run(request)
        if tools.schedule is None:
            raise RuntimeError("Agent did not generate a schedule")
        sys.stdout.write(render_schedule_csv(request.busy_periods, tools.schedule))
        if summary:
            print(f"# Agent: {summary}", file=sys.stderr)
        print(f"# Tool calls: {', '.join(call['tool'] for call in tools.trace.calls)}", file=sys.stderr)
        return 0
    except (OSError, ValueError, RuntimeError) as error:
        print(f"ERROR: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())

