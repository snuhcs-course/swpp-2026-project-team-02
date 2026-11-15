# P10 v2 — workload-only proposals and coherent topic blocks

## Implemented change

> **Superseded allocation input (2026-11-15).** The model no longer chooses
> total or daily minutes. Diagnosed plans now use every date's usable entered
> minutes (`usable_date_minutes`) as its total, and the model's `daily_minutes`
> are ignored. The budget formula, ordering and smoothing below are unchanged. This
> file stays as the design and evaluation record.

V1's real-model evaluation found translated topic labels, rejected proposals and
excessive session fragmentation. V2 changes the internal planning boundary:

- The model selects date totals through `build_study_schedule({"daily_minutes":
  [{"date": "2026/11/16", "minutes": 60}]})` after diagnostic analysis. The build
  schema contains no topic names. Unreviewed question classification still uses
  the existing validated topic-mapping tool.
- Backend code keeps the existing `diagnostic-linear-v1` budget formula: reserve
  one minute per assessed topic and split the remainder by `2 - score/100` using
  largest-remainder rounding. Exact total, topic coverage and weak-topic ordering
  remain checked against the placed schedule.
- Instead of dividing every topic across every date, pack each topic budget
  consecutively along the selected dates. For up to ten topics, subset dynamic
  programming selects the order minimizing `(chunks below 15 minutes, chunks)`;
  ties use stable weak-first indices. Larger catalogs use a deterministic order.
- Up to 32 improving pairwise exchanges then reduce short tails: add minutes to
  topic T on day A and topic U on day B, subtract the same amount from U/A and
  T/B. Both daily and topic totals remain exact. This is a bounded local search,
  not a global optimum over every possible timetable.
- Normal placement retains the selected topic order and enforces effective time
  windows, overrides, busy periods and deadline clipping. Busy-window cuts can
  still create short physical sessions. Fifteen minutes is a soft objective,
  never a minimum allowed duration. Tiny feasible workloads remain accepted.

The model still determines total workload and date distribution. V2 does not
increase utilization automatically or replace model failure with a fallback.
Requests without diagnostics keep the legacy topic-based path. Existing internal
topic-based calls remain validated before rebalancing. Public HTTP fields, plan
file identity, storage and adjustment contracts are unchanged.

The internal tool response keeps `daily_topic_minutes`, `topic_budget` and
`topic_budget_policy`, and adds `topic_placement_policy=contiguous-topic-blocks-v2`.
The agent's model, token budget, reasoning setting and step limit are unchanged.

## Offline replay — 2026-11-15

Replayed all successful recorded generation workloads from the completed v1
comparison, converting their last build proposal into date totals. These are
**225 deterministic replays, not new model calls**. Failed model attempts are
not included; this cannot measure generation reliability or model latency.

| Recorded workload source | Plans | Hard-valid v2 | Q7 v2, applicable only | Mean short-session share, original → v2 | Median sessions, original → v2 |
| --- | ---: | ---: | ---: | ---: | ---: |
| P10 v1 | 104 | 104/104 | 104/104 | 99.32% → 17.84% | 96 → 18 |
| Baseline | 121 | 121/121 | 115/115 | 16.73% → 4.88% | 24 → 19 |

Short means below 15 minutes; the reported share is the mean of per-plan shares.
All replays preserve original total and every date's workload. The v1 cohort also
preserves every original topic budget exactly. Baseline budgets intentionally
change to the P10 rule; six equal-score baseline cases have no applicable Q7.
Hard checks and Q7 use the frozen independent evaluator, not production placement
helpers. The dataset and evaluator hashes remain unchanged.

Summary and source hashes: [v2 offline evidence](evidence/v2-offline-replay-summary.json).
Full local artifacts: `outputs/p10-v2/{v1-workload-replay.json,baseline-workload-replay.json}`.
Local replay command, from the repository root:

```powershell
python outputs/p10-topic-budget/replay_v2_workloads.py
```

The script requires the original local evaluation ledgers under
`outputs/p10-topic-budget/live-20261114T164816+0900/`; those raw artifacts are not
part of the shared sanitized evidence. The historical actual-model FAIL verdict
remains in [AI_EVALUATION.md](AI_EVALUATION.md). A separate
[fresh v2 actual-model run](V2_AI_EVALUATION.md) subsequently completed with a
T1–T8 PASS. It uses its own full ledger; old completed ledgers were not resumed
with changed runtime code.

## Regression checks

Run planner, adjuster and frozen evaluator suites from `backend/`:

```powershell
python -m unittest discover -s tests/license_planner_tests -q
python -m unittest discover -s tests/schedule_adjuster_tests -q
python -m unittest discover -s ../docs/iter3-ai-eval -p 'test_*.py' -q
```

Planner tests cover exact budgets/daily totals, tiny workloads, deterministic
ordering, an exhaustive small ordering oracle, randomized conservation, invalid
dates/capacity/types/extra fields, and real tools driven by a mocked model.
The HTTP test uses all 18 reviewed questions with date-only build proposals and
checks returned topic coverage, minutes and saved artifacts.

| Check | Result |
| --- | --- |
| License planner | 135 passed |
| Schedule adjuster | 49 passed |
| Frozen evaluator tests | 10 passed |
| API suite in isolated tracked-code copy | 168 run: 167 passed, one existing platform skip |
| Recorded successful-workload replay | 225/225 hard-valid |
| `git diff --check` | Passed |

API command from the root: `python outputs/p10-v2/check_backend.py` (local helper).
The copy includes the committed `.env.example` and current tracked code edits,
but excludes personal `.env`; the user's deleted local template stays deleted.
The pre-existing Windows snapshot-replacement concurrency case is explicitly
excluded because replacing an open file raises `WinError 5` and leaves reader
threads running. Linux CI must still run that unmodified case. The separate
platform skip above is the suite's existing skip. Full API log:
`outputs/p10-v2/api-tests.log`.

No actual-model calls are included in the offline replay or regression results
above. The separate actual-model run is linked above. No Android builds/device
tests, deployment, commit or push are included in this validation.
