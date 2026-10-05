"""Interactive command-line certification picker."""
from __future__ import annotations

import argparse
import json
import sys

from .catalog import load_certifications


def main() -> int:
    parser = argparse.ArgumentParser(description="자격증을 선택합니다.")
    parser.add_argument("--list-json", action="store_true", help="선택 가능한 자격증을 JSON으로 출력")
    args = parser.parse_args()

    try:
        certifications = load_certifications()
    except ValueError as error:
        print(str(error), file=sys.stderr)
        return 2
    if not certifications:
        print("현재 선택 가능한 자격증이 없습니다.", file=sys.stderr)
        return 2

    if args.list_json:
        print(json.dumps([cert.__dict__ for cert in certifications], ensure_ascii=False, indent=2))
        return 0

    print("자격증을 선택하세요:")
    for index, certification in enumerate(certifications, start=1):
        print(f"{index}. {certification.name}")
    try:
        selected_index = int(input("번호: ").strip())
    except (ValueError, EOFError):
        print("목록에 있는 번호를 입력해 주세요.", file=sys.stderr)
        return 2
    if not 1 <= selected_index <= len(certifications):
        print("목록에 있는 번호를 입력해 주세요.", file=sys.stderr)
        return 2

    selected = certifications[selected_index - 1]
    print(json.dumps(selected.__dict__, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
