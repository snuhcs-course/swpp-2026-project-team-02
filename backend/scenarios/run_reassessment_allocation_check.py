"""Run the real-HTTP two-answer comparison and export its assertion-backed evidence.

From backend: python scenarios/run_reassessment_allocation_check.py --output coverage-results/reassessment-allocation.json
The test server uses isolated storage, a fixed clock and the reviewed question bank.
Reassessment and persistence business logic run unchanged, without model providers.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import sys
import unittest

ROOT = Path(__file__).resolve().parents[1]
sys.path[:0] = [str(ROOT), str(ROOT / "tests/api_tests")]
from test_reassessment import ReassessmentApiTests  # noqa: E402


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    case = ReassessmentApiTests("test_two_weakness_profiles_preview_commit_and_http_readback")
    result = unittest.TextTestRunner(verbosity=2).run(unittest.TestSuite([case]))
    if not result.wasSuccessful():
        return 1
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps({"profiles": case.comparison_report}, ensure_ascii=False, indent=2) + "\n",
                           encoding="utf-8")
    print(args.output.resolve())
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
