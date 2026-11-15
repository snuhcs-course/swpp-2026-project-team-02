# P10 actual-model evaluation — 2026-11-14–15

This report describes **v1** (`9665375`). The later uncommitted v2 change and
offline replay are documented in [V2_DESIGN.md](V2_DESIGN.md); they do not replace
this attempt ledger or its verdict.
The later [fresh v2 actual-model run](V2_AI_EVALUATION.md) has its own complete
ledger and a PASS under the unchanged criteria.

## Conclusion

The registered before/after attempt ledger is complete. P10 enforces weak-topic
ordering in successful plans, but the current version is **not ready for acceptance**:
sessions become excessively fragmented and generation reliability is lower.

Both versions receive the unchanged overall **FAIL** verdict. Baseline misses
T5/T8; P10 misses T1/T8. No threshold, failed attempt or input was removed to
improve that verdict.

## Fixed setup and provenance

| Item | Value |
| --- | --- |
| Baseline backend | `cad66888200633bd6b378dc01894b2e67fbfb37d` |
| P10 implementation | `3ef1a0b` |
| Integrated candidate | `966537500de1004f3b6cd16e0ca840fa1c5f811b` |
| Runtime identity | The original P10 snapshot's 40 runtime/configuration file hashes match the committed candidate; P6 integration changed frontend only |
| Evaluator registration | `eae17c7`; same snapshotted evaluator and original case requests |
| Dataset | `p10-flexible-availability-v3`, 46 cases |
| Model selected and returned | `openai/gpt-6-luna`, actual OpenRouter Responses calls |
| Planner | reasoning low, 16,384 output tokens, six agent steps |
| Adjuster | reasoning none, 2,048 output tokens, five agent steps |
| Temperature / top-p | Omitted; provider defaults |
| Repetitions | Three per model case; D01/D02/D06 once |
| Request policy | Sequential, three-second pauses, 120-second API timeout, no automatic retries |
| Stop rule | Five consecutive HTTP 5xx |

The observer always delegated to the original agents; it did not supply fixed
responses or replace allocation. The one-case baseline smoke run is excluded.
Sources, inference observations and requests/responses are retained locally under
`outputs/p10-topic-budget/live-20261114T164816+0900/`.

## Primary registered results

Rates describing plan quality are conditional on successful plans. Generation
success and the interruption history are reported separately.

| Metric | Baseline | P10 |
| --- | --- | --- |
| Registered generation attempts | 132 | 132 |
| Valid plans / expected-200 attempts | 121/126 (96.0%) | 104/126 (82.5%) |
| Expected boundary HTTP 422 responses | 6 | 6 |
| Generation HTTP 502 | 5 | 22: ten credit failures + 12 model/proposal failures |
| Hard time/topic/calendar violations in 200 plans | 0 | 0 |
| Timeouts | 0 | 0 |
| Q1 weakest topic gets maximum time | 67.0% | 100% |
| Q2 score/minute correlation criterion | 92.2% | 100% |
| Q3 all diagnosed topics covered | 100% | 100% |
| Q7 complete weak-topic order | 77/115 (67.0%) | 104/104 (100%) |
| Q4 median availability utilization | 61.5% | 41.0% |
| Q5 mean per-plan share of sessions under 15 minutes | 16.7% | 99.3% |
| Q6 maximum repeated-run topic-share range | 0.294 | 0.011 |
| Median study sessions per successful plan | 24 | 96 |
| API latency p50 / p95 / maximum, seconds | 9.89 / 22.16 / 36.23 | 16.78 / 37.64 / 47.56 |
| Successful adjustment API calls / actual calls | 11/14 | 13/13 |
| End-to-end adjustment successes / 15 cases | 11/15 | 13/15 |
| J2–J5 among successful adjustments | 11/11 | 13/13 |
| Original T1–T8 verdict | FAIL: T5, T8 | FAIL: T1, T8 |

The ten credit failures remain in P10's primary T1 calculation. They were never
retried as replacement attempts. The candidate covered all registered IDs once,
including those failed IDs; it did not produce funded plans for those ten slots.

## Diagnostic comparisons — not replacement verdicts

Funding interruptions affect the denominator. For the same 116 expected-200
input/repetition IDs actually attempted after funding:

- Baseline: 111/116 valid plans (95.7%).
- P10: 104/116 valid plans (89.7%).

For the 100 unequal-score input/repetition IDs that produced hard-valid plans in
both versions, weak-order passes were **64/100 before and 100/100 after**.
Thus ordering improved within paired successful attempts, while observed
generation reliability did not. These are explicitly post-hoc diagnostics; they
do not replace T1–T8 or establish statistical significance.

Runs occurred across two days with an interruption and provider-default decoding.
They are observations for this fixed benchmark, not a general reliability guarantee.

## Failure analysis and required follow-up

### Generation proposals

Baseline's five generation failures comprise two agent-step-limit failures and
three invalid/missing-topic-name failures. After funding, P10's 12 comprise:

- Seven invalid/missing-topic-name failures, including multilingual rewrites of
  exact allowed labels (for example `함수와 데이터管理`).
- Four agent-step-limit failures.
- One repeated-invalid-proposal failure.

P10's topic budget can be calculated locally, but the model is still required to
submit every exact topic label in its proposal. This redundant responsibility
remains a source of invalid tool calls. A follow-up should simplify the model's
job to dates/daily workload or use stable internal topic IDs, keeping the public
FE–BE contract unchanged.

### Fragmentation and workload

P10 apportions each day's workload over remaining topic budgets. This creates
many short topic/date entries. The model's median proposed distinct topic/date
entries also rose from 24 to 56; actual study sessions rose from 24 to 96.
The prompt/model proposal and backend redistribution both contribute to the
changed pipeline behavior.

The per-plan mean short-session share rose to 99.3%, far beyond the report-only
10% goal. Global budget/order correctness is not sufficient for a usable plan.
Before accepting this version, placement should preserve coherent topic blocks
and avoid unnecessary daily splits. The current benchmark's criteria must remain
unchanged for any follow-up candidate evaluation.

Median utilization fell to 41.0%, below the report-only 50% goal. The backend
preserves each proposal's total and daily workload, but changing the prompt can
change the model's proposed workload; that invariant does not guarantee equal
workload across independent model runs.

### Adjustment outcomes

Baseline E03 #3 failed generation, so adjustment was not sent. All three E05
plans used all 630 available minutes; the backend correctly preserved them and
returned NOT_ENOUGH_TIME, although the fixed benchmark expected success.

Candidate E04 #3 and E05 #2 failed generation. Its 13 issued adjustments all
succeeded and passed J2–J5. The different generated workloads and session lengths
also affect adjustment feasibility, so improvement is not attributed solely to
the rescheduling implementation. No adjustment code changed for P10.

T8 still fails: 13/15 is below the preregistered 90% end-to-end threshold.
The original expectation for fully occupied plans has not been changed post hoc.

## Interrupted attempts and continuation

1. 2026-11-14: A01 #1–#3 and A02 #1–#2 failed with provider HTTP 402.
2. 2026-11-15 first continuation: A02 #3, A03 #1–#3 and A04 #1 failed for the same account-credit issue. Read-only checks confirmed that key allowance remained, but account credits were insufficient.
3. After funding: only the remaining 122 generation slots were executed. The original failed ten remain in the aggregate. No account/key settings were changed by the evaluator.

Derived remaining-case lists only select unexecuted registered repetitions.
Offsets map segment-local repetition numbers back to original IDs. Aggregation
uses the original full case document and original evaluator. Requests were
verified equal to their registered bodies, and all 132 generation identities
were verified unique and complete.

## Evidence and cleanup

Shareable, credential-free records:

- [Comparison and diagnostics](evidence/comparison.json)
- [Baseline summary](evidence/baseline-summary.json)
- [Candidate summary](evidence/candidate-summary.json)
- [Baseline attempt ledger](evidence/baseline-runs.jsonl)
- [Candidate attempt ledger](evidence/candidate-runs.jsonl)

Raw local evidence:

- Baseline: `before/results/20261114T164832_870907800/`.
- Original interrupted candidate: `after/results/20261114T172220_027644100/`.
- First continuation: `after-resume-20261115T014506+0900/`.
- Funded continuation and final aggregate: `after-resume-20261115T041519+0900/aggregate/`.
- `resume-index.json` points to the final candidate aggregate.
- Actual provider requests, excluding smoke: baseline 460; candidate 479 across segments, including ten provider rejections. Selected/returned model was the same throughout.

Original server logs may contain account-specific administration URLs and are
kept local. No .env file was copied into evaluation sources/evidence. No
production deployment, APK change or model-parameter change was made.
Evaluation API servers and workers were shut down.

The earlier offline adversarial replay (0/40 to 40/40) remains a code check.
It is not mixed into these actual-model measurements.
