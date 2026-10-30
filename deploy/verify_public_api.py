"""Read-only verification for the public Team 2 API."""
from __future__ import annotations

import argparse
import json
from urllib.request import urlopen


DEFAULT_BASE_URL = "http://swpp26t02.147-46-242-174.sslip.io/api/v1"
CERTIFICATION_ID = "computer_specialist_level_2"


def read_json(url: str, timeout: float) -> dict:
    with urlopen(url, timeout=timeout) as response:
        if response.status != 200:
            raise RuntimeError(f"{url} returned HTTP {response.status}")
        content_type = response.headers.get_content_type()
        if content_type != "application/json":
            raise RuntimeError(f"{url} returned {content_type}, not application/json")
        value = json.load(response)
    if not isinstance(value, dict):
        raise RuntimeError(f"{url} did not return a JSON object")
    return value


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default=DEFAULT_BASE_URL)
    parser.add_argument("--timeout", type=float, default=10.0)
    args = parser.parse_args()
    base = args.base_url.rstrip("/")

    health = read_json(f"{base}/health", args.timeout)
    if health != {"status": "ok"}:
        raise RuntimeError(f"unexpected health response: {health!r}")

    certifications = read_json(f"{base}/certifications", args.timeout)
    ids = {
        item.get("id")
        for item in certifications.get("certifications", [])
        if isinstance(item, dict)
    }
    if CERTIFICATION_ID not in ids:
        raise RuntimeError(f"{CERTIFICATION_ID} missing from certifications")

    questions = read_json(
        f"{base}/certifications/{CERTIFICATION_ID}/questions", args.timeout
    )
    public_questions = questions.get("questions")
    if not isinstance(public_questions, list) or not public_questions:
        raise RuntimeError("public question list is empty or invalid")
    required = {"id", "prompt", "choices", "correct_answer", "possible_score"}
    incomplete = [
        item.get("id") if isinstance(item, dict) else None
        for item in public_questions
        if not isinstance(item, dict) or not required.issubset(item)
    ]
    if incomplete:
        raise RuntimeError(
            f"public questions are missing contract fields: {incomplete}"
        )

    print(
        f"PASS: {base} is healthy; "
        f"{len(public_questions)} public questions available"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
