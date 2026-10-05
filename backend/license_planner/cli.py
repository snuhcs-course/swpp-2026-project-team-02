"""JSON request command-line wrapper for the License Planner API service."""
from __future__ import annotations

import argparse
import json
import sys

from api_service import create_study_plan


def main() -> int:
    """Read one API-shaped JSON request and write the generated schedule CSV."""
    parser = argparse.ArgumentParser(
        description="Generate a personalized certification study plan from a JSON request"
    )
    parser.add_argument("--request-json", required=True,
                        help="Path to a UTF-8 JSON file using the POST /api/v1/study-plans request shape")
    parser.add_argument("--assessment-results-json", dest="assessment_results_json",
                        help="Assessment JSON file; overrides assessment_results in the request JSON")
    args = parser.parse_args()
    try:
        with open(args.request_json, encoding="utf-8") as request_file:
            payload = json.load(request_file)
        if args.assessment_results_json:
            if not isinstance(payload, dict):
                raise ValueError("request JSON must contain an object")
            with open(args.assessment_results_json, encoding="utf-8") as results_file:
                payload.pop("assessment_answers", None)
                payload["assessment_results"] = json.load(results_file)
        result = create_study_plan(payload)
        sys.stdout.write(result["schedule_csv"])
        if result.get("agent_summary"):
            print(f"# Agent: {result['agent_summary']}", file=sys.stderr)
        calls = result.get("tool_calls", [])
        print(f"# Tool calls: {', '.join(call['tool'] for call in calls)}", file=sys.stderr)
        return 0
    except (OSError, UnicodeDecodeError, json.JSONDecodeError, ValueError, RuntimeError, KeyError) as error:
        print(f"ERROR: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
