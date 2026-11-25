# P9: Validating the plan quality criteria and improving the planner

Iteration 5 · 남경민 · 2026-11-25. Metrics: [P7](P7_PLAN_QUALITY_METRICS.md) (final definitions). Branch evaluation: [P8](P8_RESULTS.md).

**Result:** after four definition fixes, the criteria pass a well-made example plan and fail each bad example on its intended metric. A planner change that keeps buffer days and adds a final review raised the plans passing every metric from 0 of 42 to 17 of 42 on the frozen 46-case set, with no new conflicts and the weak-topic order intact. The change breaks 14 backend tests that assert the current "use all usable time" design, so it is a proposal for the planner owner, not merged code.

## 1. Validating the criteria with example plans

`p9_examples.py` builds six plans by hand on the same request (same study windows, same diagnosis) and computes the metrics. A good criterion set passes plan 1 and fails each other plan on the metric named in its title.

| Example | A06 (spreadsheet topics weak) | A11 (컴퓨터 일반 weak) |
| --- | --- | --- |
| 1 Good: ~80% of the time, every 4th block free, review rotation at the end | passes all | passes all |
| 2 Fills 100% of the time | M2, M5a | M2, M5a |
| 3 Same plan cut into 10-minute pieces | M3, M7 | M3 |
| 4 Ignores weak topics | M1a, M1b, M7 | M1a, M1b, M7 |
| 5 Crams each topic into consecutive blocks | M6, M7 | M7 |
| 6 Starves 컴퓨터 일반 | M1b, M7, M8 | M1a, M1b, M7, M8 |

With the P7 draft definitions, plan 1 failed in every attempt. Four changes fixed that:

| Change | Why |
| --- | --- |
| M1a compares raw scores, not smoothed ones | Smoothing ranked a 4/4 topic above a 1/1 topic, so 1-minute differences between equally scored topics counted as violations (all violations in P8 were of this kind) |
| Final window L = the last 20% of usable minutes, not of calendar days | With four study days a week the calendar window held only two or three study blocks |
| M7 threshold 0.75 → 0.50 | Even a full final window fits about four 45-minute review sessions in sparse schedules |
| M8 threshold 0.30 → 0.25; M5a counts only sessions before L | A learner strong in 컴퓨터 일반 gets exactly 0.30 by design; a missed review session just before the exam has no later slot by definition |

**Limits.** Plan 5 passes M6 on A11, because a topic packed into three consecutive blocks still spans two dates; M6 detects cramming only when a topic sits on a single day. On C03 (three calendar events) the example builder left free blocks shorter than the sessions, so even plan 1 fails M5a; the metric is right, the example is not.

## 2. Planner change (proposal)

Two additions on top of the P10 topic split, in `backend/license_planner/plan_quality.py` (`p9_planner_change.patch`, applies to `iteration-4-week-2` at `01adfc1`):

1. **Buffer days.** The last study date before the final window, and every 4th date counting back from it, get no study. A missed session then has a later free date to move to.
2. **Final review.** The dates holding the last 20% of the study minutes are split into sessions of about 45 minutes and rotate through the topics, weakest first. The earlier dates are re-packed with P10's own placement (`rebalance_topic_minutes`, given the remaining budget), so each topic's total and the weak-topic order do not change.

## 3. Before and after

Same 46 cases, plans generated without a model (as in P8), final metric definitions. Both branches rejected D01, D02, D03 and D06 as expected and generated 42 plans.

| Metric (threshold) | week-1 `623f19f` | week-2 `01adfc1` | week-2 + P9 change | Median after |
| --- | --- | --- | --- | --- |
| M1a weak-order violations (0) | 100% | 100% | 100% | — |
| M1b target gap (≤ 0.15) | 100% | 100% | 100% | 0.068 |
| M2 utilization (0.50–0.85) | 0% | 0% | **92.9%** | 0.77 |
| M3 sessions < 15 min (≤ 10%) | 88.1% | 88.1% | 83.3% | 0.0 |
| M4 conflicts (0) | 100% | 100% | 100% | — |
| M5a movable sessions (≥ 0.90) | 0% | 0% | **61.9%** | 0.92 |
| M6 spacing (≥ 0.80) | 81.0% | 81.0% | 81.0% | 1.0 |
| M7 final review (≥ 0.50, weakest included) | 0% | 0% | **85.7%** | 0.50 |
| M8 subject balance (≥ 0.25) | 100% | 100% | 100% | 0.317 |
| **All metrics passed** | **0 / 42** | **0 / 42** | **17 / 42** | |

What it costs: the plan now uses a median 77% of the usable time instead of 100%, so each topic gets fewer minutes; M3 dropped slightly (two more plans have a few pieces under 15 minutes where review sessions meet the earlier block).

Why M5a is not higher: a session needs a later free slot at least as long as itself. A buffer date whose free time is shorter than a session cannot take it, and sessions on dates after the last long-enough buffer date have nowhere to go.

## 4. Effect on existing tests

`iteration-4-week-2` with the change: schedule adjuster tests all pass (78); planner tests 11 of 149 fail and API tests 3 of 236 fail. Every failure asserts the current design that a diagnosed plan fills all usable time (for example `test_two_weekly_windows_over_two_weeks_are_planned_in_full`, `test_summary_states_filled_totals_without_a_spare_time_claim`, expected totals 1620 vs 1260 minutes, a buffer date missing from the expected dates). No failure points to a broken rule (busy overlap, window bounds, budget totals).

**Decision needed from the team:** whether a plan should fill all entered time (current design, `c3f9758`) or keep buffer time. If the team keeps the buffer, the planner owner applies the patch and updates those 14 tests and the planner README.

## Reproduce

```bash
git worktree add ../w2 origin/iteration-4-week-2
git show iter4-uat-20261115:docs/iter3-ai-eval/cases.json > cases.json
python docs/iter5-plan-quality/plan_metrics.py ../w2/backend cases.json week2 .
python docs/iter5-plan-quality/p9_examples.py ../w2/backend cases.json A06 examples_A06.json
(cd ../w2 && git apply ../<repo>/docs/iter5-plan-quality/p9_planner_change.patch)
python docs/iter5-plan-quality/plan_metrics.py ../w2/backend cases.json p9after .
```
