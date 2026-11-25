# P8: Plan quality of the integration branches

Iteration 5 · 남경민 · 2026-11-25. Metrics and thresholds: [P7](P7_PLAN_QUALITY_METRICS.md) (thresholds still proposed).

**Result:** both integration branches pass the hard constraints (M4) and the weak-topic and subject-balance metrics, but no plan passes every metric. All 42 plans use 100% of the usable time (M2), so no missed session can be moved (M5a = 0), and 98% of plans skip a final review of most topics (M7).

## Method

- **Cases:** the frozen 46-case set `p10-flexible-availability-v3` (`docs/iter3-ai-eval/cases.json` at tag `iter4-uat-20261115`).
- **Branches:** `feat/iteration-4-week-1` (`623f19f`) and `iteration-4-week-2` (`01adfc1`, adds the 30-minute minimum session).
- **Generation without a model:** every case answers the 18 reviewed questions, so the backend sets the workload and topic split and the model only calls `analyze_exam_results` and then `build_study_schedule`. `plan_metrics.py` calls the same two tools directly; no OpenRouter key, cost or request limit is involved. Latency and model failures are therefore not measured here.
- **Metrics:** M1–M8 from P7, computed by `plan_metrics.py` from each request and generated plan.

Both branches generated 42 plans; the other 4 cases (D01, D02, D03, D06) were rejected with 422 as expected.

## Results

| Metric (threshold) | week-1 `623f19f` | week-2 `01adfc1` | Median (week-2) |
| --- | --- | --- | --- |
| M1a weak-order violations (0) | 73.8% | 76.2% | — |
| M1b target gap (≤ 0.15) | 100% | 100% | 0.068 |
| M2 utilization (0.50–0.85) | **0%** | **0%** | 1.00 |
| M3 sessions < 15 min (≤ 10%) | 88.1% | 88.1% | 0.0 (under 30 min: 15.8%) |
| M4 conflicts (0) | 100% | 100% | — |
| M5a movable sessions (≥ 0.90) | **0%** | **0%** | 0.00 |
| M6 spacing (≥ 0.80) | 81.0% | 81.0% | 1.00 |
| M7 final review (≥ 0.75, weakest included) | **2.4%** | **2.4%** | 0.25 |
| M8 subject balance (≥ 0.30) | 100% | 100% | 0.317 |
| All metrics passed | 0 / 42 | 0 / 42 | |

Pass rates are the share of the 42 generated plans that meet each threshold.

## Findings

1. **No room to adjust.** Both branches set each day's workload to all usable minutes (`iteration-4-week-2` README), so utilization is 1.00 and no session has a free slot to move to before the exam. This matches 양진혁's v3 run of the integration candidate (adjustment 0/15) and is the main fix for P9: cap the workload below the usable time.
2. **No final review.** P10 places each topic in contiguous blocks, weakest first, so the last days hold only one or two topics (median 2 of 8). The weakest topic appeared in the final window in only 9 of 42 plans (week-2).
3. **Short pieces remain on week-2.** The 30-minute rule applies to free segments, but a topic switch inside a segment still creates sessions under 30 minutes (median share 15.8%).
4. **M1a with smoothed scores is too strict.** Every violation is a 1-minute difference between topics with the same raw score but different question counts (e.g. A01, all answers correct). With raw scores there are no violations on either branch, so the planner is correct and P9 should compare raw scores for M1a (or ignore differences under 5 minutes).
5. **Week-1 and week-2 differ little on these metrics:** the 30-minute rule changed a few plans but not the pass rates beyond M1a.

## Reproduce

```bash
git worktree add ../w2 origin/iteration-4-week-2
git show iter4-uat-20261115:docs/iter3-ai-eval/cases.json > cases.json
python docs/iter5-plan-quality/plan_metrics.py ../w2/backend cases.json week2 .
```

## Not done yet (P9)

- Example plans to validate each threshold, a planner change (utilization cap, review sessions in the final window) and a before/after run.
- The UAT build (`54db19b`) cannot be generated this way because its model still proposes the daily minutes; it needs a real-model run.
