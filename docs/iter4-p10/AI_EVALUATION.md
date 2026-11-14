# P10 actual-model evaluation — 2026-11-14

## Status

The baseline run completed. The candidate run stopped after five consecutive
HTTP 502 responses caused by OpenRouter HTTP 402 credit/key-limit rejection.
The before/after comparison is **incomplete**; no candidate quality conclusion
or P10 improvement percentage can be drawn from this run.

These were actual OpenRouter calls, not the earlier offline adversarial replay.
The observer delegated every inference call to the original agent and recorded
the selected/returned model and usage. No production service or APK was changed.

## Fixed setup

| Item | Value |
| --- | --- |
| Baseline backend | `cad66888200633bd6b378dc01894b2e67fbfb37d` |
| Candidate | Uncommitted P10 runtime snapshot; exact per-file hashes are in the manifest/configuration records |
| Candidate parent | `eae17c76843efb6a6cedefd1292207775d8553fc` |
| Dataset | `p10-flexible-availability-v3`, 46 cases |
| Evaluator | Same snapshotted `run_eval.py` and cases for both stages |
| Actual selected/returned model | `openai/gpt-6-luna` |
| Provider | OpenRouter Responses API |
| Planner | reasoning `low`, 16,384 output tokens, six agent steps |
| Adjuster | reasoning `none`, 2,048 output tokens, five agent steps |
| Temperature / top-p | Omitted; provider defaults |
| Repetitions | Three per model case; D01/D02/D06 once |
| Requests | Sequential; three-second pauses; 120-second API timeout; no automatic retries |
| Stop rule | Five consecutive HTTP 5xx responses |

Sources, prompt/tool-schema hashes, configurations, inference evidence, raw API
requests/responses and partial results are under
`outputs/p10-topic-budget/live-20261114T164816+0900/`.
The baseline's one-case smoke request succeeded and is excluded from benchmark
counts. Stores were isolated from each other and from existing plans.

## Baseline results

| Metric | Result |
| --- | --- |
| Generation attempts | 132 |
| Valid 200 plans | 121 / 126 expected-200 attempts (96.0%) |
| Expected boundary rejections | Six HTTP 422 responses |
| Generation HTTP 502 | Five |
| Hard time/topic/calendar violations in 200 plans | Zero |
| Timeouts | Zero |
| API latency p50 / p95 / maximum | 9.89 / 22.16 / 36.23 seconds |
| Q1 weakest topic receives maximum time | 67.0% |
| Q2 score/minute correlation criterion | 92.2% |
| Q3 every assessed topic covered | 100% |
| Q7 complete weak-topic order | 77 / 115 applicable successful plans (67.0%) |
| Median available-time utilization | 61.5% |
| Mean proportion of sessions shorter than 15 minutes | 16.7% |
| Maximum repeated topic-share range | 0.294 |
| Actual adjustment API requests | 14: 11 succeeded, three returned 422 |
| End-to-end adjustment success, including failed generation | 11 / 15 |

Original overall verdict: **FAIL**. T5 (weakest-topic maximum) and T8
(end-to-end adjustment) missed their preregistered thresholds. T1–T4, T6 and T7
passed. The original verdict has not been rewritten after inspecting results.

Generation failures: A06 #3, B02 #3, C02 #3, C06 #2 and E03 #3. The E03 failure
prevented its adjustment request; it is still counted in the end-to-end measure.

E05 adjustment failed in all three repetitions. Each generated plan consumed
all 630 available minutes, leaving no free slot before the exam. The backend
returned `NOT_ENOUGH_TIME` and kept the plan. This is a capacity rejection,
distinct from a provider outage or schedule-corrupting defect. E05 expected
success in the fixed benchmark, so those failures remain in its raw verdict.
All 11 successful adjustments passed J2–J5.

## Candidate interruption

Five generation attempts were recorded: A01 #1–#3 and A02 #1–#2. None produced
a plan. OpenRouter refused inference with HTTP 402, and the application mapped
that upstream failure to HTTP 502. The request's 16,384-token output bound
could not be reserved within the available credit/key limit.

The stop rule fired as registered. No candidate adjustments ran. The candidate's
partial `summary.json` contains an automatic FAIL because the acceptance suite
was not completed; this must **not** be presented as an evaluated P10 quality
failure or compared to the full baseline verdict.

To continue, the account owner must resolve the OpenRouter balance/key limit.
Keep these interrupted attempts and report them explicitly. Resume/restart
policy must be recorded before further calls. Do not silently discard them,
lower only the candidate's token limit, or substitute another model and claim
the same comparison. Baseline data can be retained while the candidate stage's
completion procedure is agreed.

## Evidence and cleanup

- `manifest.json`: source snapshots/hashes, stage states, model/configuration and evaluator/case hashes.
- `before/results/20261114T164832_870907800/`: complete baseline results.
- `after/results/20261114T172220_027644100/`: interrupted candidate results.
- Stage `inference.jsonl` files: actual model request observations (460 baseline calls, ten candidate calls; smoke separate).
- Stage `server.log` files: sanitized application/provider diagnostics. Provider diagnostics can still contain account-specific administration URLs; redact before sharing logs.
- No `.env` was copied into the evidence. A byte-level scan found no API-key value in generated artifacts.
- Both local API servers and evaluation workers exited; no further model calls are running.

The earlier offline replay result (0/40 to 40/40 for adversarial proposals)
remains a deterministic code check and is not mixed into these actual-model results.
