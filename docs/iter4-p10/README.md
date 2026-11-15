# P10 — diagnostic topic budgets

## Scope and baseline

- Branch: `feat/p10-topic-budget`, based on `cad66888200633bd6b378dc01894b2e67fbfb37d`.
- The baseline includes P21 overnight windows and A1 whole-week replacements.
  The integrated working branch also includes P6 local check-ins.
- Shared evaluator/dataset: commit `eae17c7`, version `p10-flexible-availability-v3`, 46 cases.
- The baseline and candidate must use that same evaluator and dataset. The baseline backend remains `cad6688`, not the later evaluator commit.
- No FE request/response field is required. CSV identity, availability snapshots, manual/automatic adjustment and storage remain compatible.

## Allocation rule

> **Superseded allocation input (2026-11-15).** The model no longer chooses
> total or daily minutes. Diagnosed plans now use every date's usable entered
> minutes (`usable_date_minutes`) as its total, and the model's `daily_minutes`
> are ignored. The budget formula and block placement below are unchanged. This
> file stays as the evaluation record.

The model chooses total planned minutes and daily totals from the preparation
period, availability and self-assessment. For diagnosed requests its internal
build call supplies only `daily_minutes` (`date`, `minutes`). Backend code owns
topic labels and budgets. Each day's proposal must fit actual capacity; invalid
proposals remain tool errors. Legacy topic-based internal calls remain supported.

For diagnosed topics, code reserves one minute per topic, then apportions the
remaining proposed minutes with weight `2 - score_percent / 100`. A 0% topic has
weight 2, a 100% topic weight 1. Largest-remainder rounding preserves the exact
sum; remainder ties prefer a weaker score and then a stable topic-name order.
For scores 0%, 50%, 100% and a 183-minute workload, budgets are 81, 61, 41 minutes.

V2 packs global topic budgets into coherent blocks across dates. A bounded subset
dynamic program chooses topic order; bounded pairwise minute exchanges reduce
short tails while preserving each day's total and each topic's budget. Normal
placement then enforces the effective windows,
week overrides, midnight clipping, exam deadline and busy-calendar exclusions.
The code validates actual placed totals against the budget, positive coverage
and every unequal-score pair: a weaker topic must never get fewer minutes.
Different scores can tie at small integer budgets; equal-score topics differ by
at most one minute. There is no invented score for an undiagnosed topic: when no
topic statistics exist, the original valid model proposal is retained.

The tool response records `topic_budget`, `topic_budget_policy` and the actual
`daily_topic_minutes`, plus `topic_placement_policy`. The API CSV and summary derive from the actual schedule.
Rebalancing can change topic/date combinations and session count while preserving
total minutes and each day's workload. No fixed session-length floor is added.

## Evaluation

See [the frozen evaluation instructions](../iter3-ai-eval/README.md). The evaluator
implements its own origin-date window resolver so it does not share the production
placement oracle. Q7 checks all unequal-score pairs; Q1 alone only checks a maximum.
Original T1–T8 remain visible, including failure rates and short-session statistics.

For the actual model comparison, run separate baseline and candidate services with
separate plan directories, the same model/decoding settings and the same evaluator.
Record the actual source commit or uncommitted source hashes, model settings and
prompt/tool-schema hashes for each service. Each full run is 147 requests at most.
Do not run against the shared production plan directory (100-plan retention).

Actual OpenRouter evaluation completed on 2026-11-15 after funding and resuming
only the remaining attempts. Both 132-generation ledgers are complete, with ten
historical credit failures retained in the candidate ledger. Successful-plan
weak ordering improved from 67.0% to 100%, but generation reliability and
session fragmentation require follow-up; the registered candidate verdict is
FAIL. See [the actual-model evaluation report and evidence](AI_EVALUATION.md).
That run evaluated v1; [v2 design and offline validation](V2_DESIGN.md) separately
documents the subsequent correction. The [fresh v2 actual-model evaluation](V2_AI_EVALUATION.md)
completed on November 15 with an unchanged T1–T8 **PASS**: 126/126 valid plans,
six expected boundary responses and 14/15 successful adjustments. Short-session
share improved from v1 to 37.2%, while workload adequacy remains unverified.
No secrets were copied, no fallback responses were reported as AI results, and
the deployed service was not changed.

## Offline evidence

Deterministic adversarial proposal replay uses the same 46 cases against the
baseline and candidate, deliberately proposing the largest allocation to the
strongest topic. This tests correction, not actual model quality or success rate.
Both versions returned 42 hard-valid schedules and rejected four deterministic
boundary inputs. Of 40 unequal-score cases, baseline Q7 was 0/40 and candidate
Q7 was 40/40. Records are local under:

- `outputs/p10-topic-budget/offline-proposals-before.json`
- `outputs/p10-topic-budget/offline-proposals-after.json`

Unit tests also cover exact totals, equal scores, tiny budgets, invalid scores,
input-order stability, daily-total conservation and rejection of invalid model
dates/capacities. They do not claim model-quality results.

## Local checks — 2026-11-14

Run the following from `backend/` unless noted:

| Command | Result |
| --- | --- |
| `python -m unittest discover -s tests/license_planner_tests -q` | 125 passed |
| `python -m unittest discover -s tests/schedule_adjuster_tests -q` | 49 passed |
| `python -m unittest discover -s ../docs/iter3-ai-eval -p 'test_*.py' -q` | 10 passed |
| `python -m unittest discover -s ../scripts/tests -q` | 4 passed |
| API suite with the exclusion below | 168 run, 167 passed, one existing platform skip |
| `git diff --check` | passed |

On Windows, the pre-existing
`SnapshotCacheTests.test_concurrent_reads_during_replacements_see_whole_snapshots`
was explicitly excluded: atomic replacement of an open file raises `WinError 5`
and leaves the reader threads running. Its code is unchanged from `cad6688`.
All other API cases were run; the complete log is local at
`outputs/p10-topic-budget/api-regression.log`. Linux CI must still run the
unmodified full suite, including that concurrency case.

Expected old fixture assumptions about four fixed sessions, 60-minute target
lengths and identical starts for every block were updated for actual rebalanced
sessions. Tests still enforce total/daily minutes, effective time windows,
deadline bounds and unchanged session length during subsequent adjustment.

No Android build/device test or actual OpenRouter quality comparison is claimed
by these backend checks. The later actual-model evaluation is documented
separately above. No push, PR or server deployment was performed.
