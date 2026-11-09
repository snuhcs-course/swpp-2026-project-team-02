"""Ask dummy assessment questions and write License Planner assessment JSON."""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Any

if __package__:
    from .validation import QuestionContentError, document_questions, parse_json_bytes
else:  # Run as ``python assessment_questionnaire/cli.py``: this file's directory is on sys.path.
    from validation import QuestionContentError, document_questions, parse_json_bytes


QUESTION_FILE = Path(__file__).resolve().parent / "questions.json"
DEFAULT_OUTPUT_FILE = Path(__file__).resolve().parent / "output" / "assessment_results.json"


def load_questions(path: Path = QUESTION_FILE) -> dict[str, Any]:
    """Load and validate a certification's multiple-choice assessment JSON.

    A bank with ``onboarding_question_ids`` yields only that selection, in order, so
    retained unselected items and drafts are not asked. A custom document without it
    asks every question as before. Validation needs no application configuration.
    """
    try:
        data = path.read_bytes()
    except OSError as error:
        raise ValueError(f"질문 JSON을 읽을 수 없습니다: {error}") from error
    try:
        document = parse_json_bytes(data, "question JSON")
        questions = document_questions(document)
    except QuestionContentError as error:
        raise ValueError(f"질문 JSON이 올바르지 않습니다: {error}") from error
    document = {key: value for key, value in document.items() if key != "questions"}
    document["questions"] = questions
    return document


def ask_answers(document: dict[str, Any]) -> list[dict[str, Any]]:
    """Display questions and collect user answers with correctness outcomes."""
    results: list[dict[str, Any]] = []
    questions = document["questions"]
    print(f"{document.get('certification_name', '자격증')} 진단 질문 ({len(questions)}문항)")
    print("각 문항의 선택지 번호/문자를 입력하세요. 자유형 수준 설명은 Planning API 요청의 self_assessment에 입력합니다.\n")
    for question in questions:
        print(f"{question['id']}. {question['prompt']}")
        keys = list(question["choices"])
        for index, key in enumerate(keys, start=1):
            print(f"  {index}. {key}. {question['choices'][key]}")
        while True:
            try:
                answer = input(f"답변 (1-{len(keys)} 또는 {','.join(keys)}): ").strip().upper()
            except EOFError as error:
                raise ValueError("답변 입력이 종료되었습니다. 모든 문항에 응답한 뒤 다시 실행해 주세요.") from error
            if answer.isdigit() and 1 <= int(answer) <= len(keys):
                selected_key = keys[int(answer) - 1]
                break
            if answer in keys:
                selected_key = answer
                break
            print(f"선택지 번호 1-{len(keys)} 또는 {','.join(keys)} 중 하나를 입력해 주세요.")

        points = float(question["possible_score"])
        is_correct = selected_key == question["correct_answer"]
        results.append({"problem_id": question["id"], "question": question["prompt"],
                        "choices": question["choices"],
                        "correct_answer": question["correct_answer"],
                        "user_answer": selected_key, "is_correct": is_correct,
                        "possible_score": points})
        print("정답입니다.\n" if is_correct else "오답입니다.\n")
    return results


def write_results(document: dict[str, Any], results: list[dict[str, Any]], output_path: Path) -> None:
    """Write the question, user answer, and correctness JSON document."""
    output_path.parent.mkdir(parents=True, exist_ok=True)
    payload = {"certification_id": document.get("certification_id"),
               "certification_name": document.get("certification_name"),
               "results": results}
    output_path.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser(description="더미 진단 질문에 응답하고 License Planner용 JSON을 만듭니다.")
    parser.add_argument("--questions", type=Path, default=QUESTION_FILE,
                        help="질문 JSON 경로 (기본: assessment_questionnaire/questions.json)")
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT_FILE,
                        help="결과 JSON 경로 (기본: assessment_questionnaire/output/assessment_results.json)")
    args = parser.parse_args()
    try:
        document = load_questions(args.questions)
        results = ask_answers(document)
        write_results(document, results, args.output)
    except (ValueError, OSError) as error:
        print(f"오류: {error}", file=sys.stderr)
        return 2

    total = len(results)
    correct = sum(result["is_correct"] for result in results)
    print(f"진단 결과 JSON 저장: {args.output.resolve()}")
    print(f"정답: {correct}/{total}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
