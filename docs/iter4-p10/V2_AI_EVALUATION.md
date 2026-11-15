# P10 v2 actual-model evaluation — 2026-11-15

## Result

**PASS under the unchanged registered T1–T8 criteria.** The full fresh run
completed: 132 generation requests and 15 adjustment requests. All 126
expected-200 generation requests returned hard-valid plans; the other six
returned their expected boundary HTTP 422 responses. No timeout, HTTP 5xx,
topic-name error or hard scheduling violation occurred.

One adjustment returned HTTP 422 because no sufficiently long later slot
remained. It stays a failed adjustment in the ledger. A PASS permits the
registered adjustment success threshold (90%); it does not mean every request
succeeded or every plan-quality issue is resolved.

## Setup and provenance

| Item | Executed configuration |
| --- | --- |
| Time | 2026-11-15 17:53–18:14 KST |
| Parent commit | `966537500de1004f3b6cd16e0ca840fa1c5f811b` |
| Runtime | Uncommitted v2 snapshot; exact executed source hashes in evidence |
| Model selected / returned | `openai/gpt-6-luna` for all 420 actual model requests |
| Provider | OpenRouter Responses API; original agents, no fixed responses or inference fallback |
| Planner | Reasoning low, 16,384 output tokens, six steps |
| Adjuster | Reasoning none, 2,048 output tokens, five steps |
| Decoding | Temperature and top-p omitted, provider defaults |
| Dataset | Frozen `p10-flexible-availability-v3`: 46 cases, 132 generation attempts |
| Repetitions | Three, except D01/D02/D06 once |
| Policy | Sequential, three-second pauses, 120-second evaluator API timeout, 45-second provider-request timeout; no automatic retries |
| Stop rule | Five consecutive HTTP 5xx, unchanged; not triggered |

Dataset SHA-256: `070bc7bbe794973908da96147a17c4f14bdb46d88b9b40fd581082f0309181be`.
Evaluator SHA-256: `5c5e0e2324181327502e19e4885f8b0e87cc84e3eb2da608bcdcee21c4d421fa`.
These match the prior comparison. Model settings remain unchanged; the v2 system
prompt, diagnosed build schema and allocation implementation changed as described
in [V2_DESIGN.md](V2_DESIGN.md).

The run used an isolated localhost API and plan directory. Credentials stayed in
the local settings/child process environment; no key was copied into snapshots
or shared evidence. The observer delegated every call to the original model
agents. Every accepted generation used `daily_minutes` in its final build call.
The evaluator, thresholds and runtime snapshot stayed fixed during execution.

## Comparison with completed historical runs

Quality statistics are conditional on successful plans. Historical baseline/v1
runs were not repeated; they ran on November 14–15 with the same frozen inputs
and model settings. This is an observed comparison across separate runs with
provider-default decoding, not a controlled claim of statistical significance.

| Metric | Baseline | P10 v1 | P10 v2 |
| --- | ---: | ---: | ---: |
| Valid plans / expected-200 attempts | 121/126 (96.0%) | 104/126 (82.5%) | 126/126 (100%) |
| Expected boundary 422 responses | 6 | 6 | 6 |
| Generation HTTP 502 | 5 | 22 | 0 |
| Timeouts / hard violations in 200 plans | 0 / 0 | 0 / 0 | 0 / 0 |
| Q7 complete weak-topic order | 77/115 (67.0%) | 104/104 (100%) | 120/120 (100%) |
| Q4 median availability utilization | 61.5% | 41.0% | 30.8% |
| Q5 mean per-plan share below 15 minutes | 16.7% | 99.3% | 37.2% |
| Median study sessions | 24 | 96 | 18 |
| Q6 maximum repeated topic-share range | 0.294 | 0.011 | 0.075 |
| API latency p50 / p95 / maximum, seconds | 9.89 / 22.16 / 36.23 | 16.78 / 37.64 / 47.56 | 5.73 / 8.22 / 9.89 |
| Successful adjustment calls / actual calls | 11/14 | 13/13 | 14/15 |
| End-to-end adjustment successes / 15 registered cases | 11/15 | 13/15 | 14/15 |
| Original T1–T8 verdict | FAIL | FAIL | PASS |

V1 includes ten historical credit failures, retained in its primary denominator.
Those funding interruptions did not recur in v2. The v1 report includes a
separate funded-only diagnostic; its failures were not erased or replaced by
the new run. See [the historical actual-model report](AI_EVALUATION.md).

## Thresholds

| Criterion | V2 value | Result |
| --- | --- | --- |
| T1 valid generation | 126/126 | Pass |
| T2 hard violations | 0 | Pass |
| T3 boundary handling | Deterministic 3/3; D03 no 5xx/timeout | Pass |
| T4 latency/timeouts | p95 8.22 seconds; no timeout | Pass |
| T5 weakest-topic maximum | 100% | Pass |
| T6 score/minute correlation criterion | 98.3% | Pass |
| T7 topic coverage | 100% | Pass |
| T8 adjustment | 14/15; all 14 satisfy J2–J5 | Pass |

## Remaining failure and limitations

- **E02, repetition 1:** `study-19` occupies November 28, 10:22–11:20 and lasts
  58 minutes. The existing move rule searches after the target and other sessions
  on its topic. The final available window ends at 12:00, leaving only 40 minutes.
  An independent subtraction of occupied intervals confirms no later 58-minute
  slot. The API returned `NOT_ENOUGH_TIME`; the original CSV remained unchanged.
  The evaluator expected 200, so the attempt remains `UNEXPECTED_STATUS`.
- All 14 successful adjustment responses match the rows saved in their selected
  CSV files; the failed request's saved rows match its original request.
- Short sessions dropped substantially from v1, but 37.2% remains higher than
  the historical baseline's 16.7%. Nineteen successful plans have at least one
  entire topic budget below 15 minutes. Small daily totals and physical window
  cuts also constrain block length. The v2 algorithm preserves the proposed
  daily workload and does not force a 15-minute minimum.
- Utilization is lower than in the historical runs. Total study workload still
  comes from the model; these checks do not establish that the chosen workload
  is sufficient preparation for the certification. Q4–Q7 remain reported metrics,
  not newly introduced acceptance thresholds.
- The offline replay result (17.8% short share on fixed v1 workloads) and this
  fresh-model result (37.2%) measure different workloads. They must not be
  presented as the same experiment.
- This benchmark uses the fixed reviewed diagnostic questions. It does not
  establish correctness of arbitrary unreviewed question classification or
  Android/device behavior. The deployed service was not changed.

## Evidence and execution

- [V2 summary, settings and executed source hashes](evidence/v2-live-summary.json)
- [All 147 registered request results](evidence/v2-live-runs.jsonl)
- [Comparison, usage totals and failure/persistence analysis](evidence/v2-live-comparison.json)

Local artifacts are under `outputs/p10-v2/live-20261115T175337+0900/`: immutable
source/evaluator snapshots, `inference.jsonl`, request/response exchanges,
generated plans, logs and `analysis.json`. The isolated API process was stopped
after completion. The provider reported total usage cost **$0.03387394** across
420 completed model calls (642,154 input and 36,513 output tokens).

Executed from the repository root with the configured backend environment:

```powershell
python outputs/p10-v2/run_live_v2.py
python outputs/p10-v2/export_live_v2.py
```

These local operational helpers require the historical local evaluation folder
and are not replacements for the frozen evaluator. Starting the first command
again creates a new paid run. No commit, push or deployment was performed.
