# P7: Plan quality metrics (draft)

Iteration 5 · 남경민 · 2026-11-22. Thresholds are proposals; P9 validates them on hand-made example plans and fixes them.

This page proposes eleven metrics for judging whether a generated plan is not only valid but helps the learner study. They fall into three layers.

| Layer | Metrics | When | Needs |
| --- | --- | --- | --- |
| A. Required | M1 weak-topic allocation · M2 utilization · M3 short sessions · M4 conflicts · M5 adjustability | Right after generation | Request + generated plan |
| B. Learning support | M6 spacing · M7 final review · M8 subject balance | Right after generation | Request + plan + each topic's subject |
| C. Outcome | M9 adherence · M10 re-assessment gain · M11 adjustment frequency | After the learner follows the plan | Study reports, re-assessment (P11/P12), adjustment log |

A and B can be computed on the frozen 46-case set now. C needs real users and is collected in beta testing (December 7–11).

**Why now.** Two builds evaluated on the same 46 cases failed in opposite directions.

| Measure | UAT build `54db19b` (Nov 15) | Integration candidate `73a3c0b` (양진혁, v3 run) |
| --- | --- | --- |
| Weakest topic gets the most time (T5) | 100% | 100% |
| Median available-time utilization | 30.8% | 100.0% |
| Share of sessions under 15 min | 37.2% | 2.4% |
| Missed sessions moved automatically | 14/15 | 0/15 |

Filling 100% of the time removed short sessions but also every free slot for moving a missed session. Utilization is therefore judged as a band, together with adjustability (M5), not as "higher is better".

Sources: [Testing Documentation §3](https://github.com/snuhcs-course/swpp-2026-project-team-02/wiki/Testing-Documentation#3-ai-module-testing) (UAT build); 양진혁's Iteration 4 AI collaboration report, appendix A (integration candidate).

## Inputs and notation

Every A and B metric is computed from the generation request and response only.

| Symbol | Meaning | Source |
| --- | --- | --- |
| S, E | Preparation start, exam date | request `preparation_start`, `exam_date` |
| W | Study windows inside [S, E) (weekly windows + week overrides) | request `weekly_availability`, `availability_overrides` |
| B | Busy calendar events | request `busy_schedules` |
| c_t, n_t | Correct answers and questions for topic t | diagnosis answers + topic tags in `questions.json` |
| s_i = (t_i, a_i, b_i) | Topic, start and end of study session i | study rows of the response (not `external…`) |
| ℓ_i | Session length in minutes, b_i − a_i | computed |
| m_t, M | Minutes for topic t, total minutes | computed |
| U₀ | Usable minutes: length of W minus overlap with B | computed (same as `available_minutes` in `run_eval.py`) |
| subj(t) | Topic's subject: 3 in 컴퓨터 일반, 5 in 스프레드시트 일반 | `subject` in `questions.json` |

**Diagnostic scores are smoothed.** The 18 questions cover eight topics with one to four questions each; three topics have a single question, so their raw score is 0% or 100%. Laplace smoothing keeps few-question topics away from the extremes:

```math
\hat{s}_t = \frac{c_t + 1}{n_t + 2}, \qquad w_t = 1 - \hat{s}_t
```

One wrong answer gives ŝ = 33%, one right answer 67%, four right answers 83%. w_t is the topic's weakness. Whether to smooth is decided in P9.

## A. Required metrics

Each metric is computed per plan; over the evaluation set we report the share of plans that pass.

| Metric | Measures | Computation | Threshold (proposed) | Relation to existing criteria |
| --- | --- | --- | --- | --- |
| M1a Weak-order violations | A weaker topic gets fewer minutes | Pairs with ŝ_a < ŝ_b and m_a < m_b | 0 | Q7 (with smoothed scores) |
| M1b Target-allocation gap | How far the split is from the weakness-based target | D below | D ≤ 0.15 | T5/T6 as one number |
| M2 Utilization | Share of usable time assigned | U = M / U₀ | 0.50 ≤ U ≤ 0.85 | Q4, new upper bound |
| M3 Short sessions | Fragments too short to study | P₁₅ = sessions with ℓ_i < 15 / all sessions | P₁₅ ≤ 0.10 | Q5 |
| M4 Conflicts | Sessions overlapping busy events, other sessions, outside W or outside [S, E) | Sum of violations C | C = 0 | H1–H3, H5 |
| M5a Movable sessions | Is there room to move a missed session | R below | R ≥ 0.90 | New |
| M5b Adjustment success | Does the adjustment API actually move it | Successful adjustments / requests | ≥ 0.90 | T8 |

**M1b.** The target share τ_t gives every topic a base of 1 plus up to 1 for weakness, so the strongest and weakest topics differ by at most 2×, as in P10's design. D is the distance between actual and target shares: 0 = identical, 1 = completely different.

```math
\tau_t = \frac{1 + w_t}{\sum_k (1 + w_k)}, \qquad D = \frac{1}{2} \sum_t \left| \frac{m_t}{M} - \tau_t \right|
```

**M2 is judged as a band.** 0.50 is the existing report-only goal; 0.85 leaves 15% of usable time for moves. Both values are tuned in P9 together with M5a.

**M3 also reports the 30-minute share.** From `iteration-4-week-2` (Nov 22) the planner makes no session shorter than 30 continuous minutes. The 15-minute threshold stays for comparison with earlier builds; the under-30 share is reported too. Both must be 0 on the new build, so they serve as regression checks.

**M4 is non-negotiable.** A plan with C > 0 fails whatever its other metrics. A busy event sent in the request but missing from the plan (H5) counts as one violation.

**M5a.** Treat session i as missed and check whether a free interval of ℓ_i minutes exists after it ends and before the exam, without touching other sessions. The free set F is W minus busy events and every session other than i. It needs no model call, so it is computed for every plan.

```math
R = \frac{\left|\{\, i : \exists\, [x, y] \subseteq F \setminus \{s_i\},\ x \ge b_i,\ y \le E,\ y - x \ge \ell_i \,\}\right|}{N}
```

M5b can be measured only on the 15 adjustment cases; M5a covers every plan. P8 checks that the two move together. A plan with 100% utilization, like the integration candidate, should have M5a near 0.

## B. Learning-support metrics

A checks rules and priorities. B checks whether the same minutes are arranged so the learner remembers more and gets closer to passing. All three are computed from the plan.

| Metric | Measures | Computation | Threshold (proposed) | Rationale |
| --- | --- | --- | --- | --- |
| M6 Spacing | Is a topic spread over several days instead of one | Among topics with ≥ 60 min, the share appearing on ≥ 2 different days | ≥ 0.80 | The same time spread over days is remembered longer (spacing effect) |
| M7 Final review | Are topics revisited just before the exam | Topics with a session in the final window L / 8 | ≥ 0.75, and the weakest topic always included | Material not revisited before the exam is forgotten |
| M8 Subject balance | Is one subject starved, risking a fail | The smaller of the two subjects' minute shares g = Σ m_t (t in subject) / M | ≥ 0.30 | Below 40 points in either subject fails the exam |

**M6 and M3 pull against each other.** Splitting too finely raises short sessions (M3); grouping too much crams a topic into one day (M6). The two are judged together. Topics with under 60 minutes are excluded from M6 because they cannot form two 30-minute sessions.

**Final window L for M7** is the last 20% of the preparation period, at least 3 days (a 21-day plan → the last 4 days). The app has no separate practice-question sessions, so review is measured only as another session on the same topic.

**M8 follows the exam rule.** The 컴활 2급 written exam has 20 questions each in 컴퓨터 일반 and 스프레드시트 일반; a pass needs at least 40 points in each subject and a 60-point average ([source](https://winspec.co.kr/community/freeboard/131339894)). Our topics are 3 in 컴퓨터 일반 and 5 in 스프레드시트 일반, so an even split per topic already gives 컴퓨터 일반 only 37.5%; weak spreadsheet topics can push it lower, hence the 0.30 floor.

## C. Outcome metrics

Whether a plan helped can be measured only after the learner follows it. These three are collected in beta testing (December 7–11); for now only their formulas and data are fixed.

| Metric | Computation | Data needed | Reading |
| --- | --- | --- | --- |
| M9 Adherence | Minutes actually studied / planned minutes (sessions ended before today) | Study reports (다 했어요 / 일부 했어요 / 못 했어요 + minutes) | Low = the plan does not fit the learner's life |
| M10 Re-assessment gain | Normalized gain g_t per topic (below) | Initial diagnosis, re-assessment (P11·P12) | Larger g for weak topics than strong ones = the allocation works |
| M11 Adjustment frequency | Adjustments per week, share of sessions moved, share of moves failing for lack of room | Adjustment requests/results, calendar conflict notices | Frequent moves or failures = the plan is too tight |

```math
g_t = \frac{\text{post}_t - \text{pre}_t}{1 - \text{pre}_t}
```

g_t is the share of the possible improvement actually achieved. A topic already scoring high has little room to rise, so g compares topics better than a raw difference. We also check whether g_t grows with the minutes m_t.

**Open items.**

- [ ] Re-assessment questions must differ from the original 18 for a gain to mean anything. Confirm with 손훈일's P3/P4 (assessment question preparation) that each topic will have enough questions.
- [ ] Study reports are stored only on the device. Collecting M9/M11 in beta needs consent and an export path.

## Judging and how P8/P9 apply it

There is no single composite score: averaging metrics that pull against each other, such as utilization and adjustability, hides one side's failure. Instead:

1. **Gate:** only plans that were generated and have M4 = 0 go further. Failed plans stay in the denominator.
2. **Per-metric pass rates:** the share of plans passing each of M1a–M8, one column per build in one table, so what each build gained and lost is visible at a glance.
3. **Same conditions:** builds are compared only on the frozen 46 cases (`p10-flexible-availability-v3`) with the same model and settings.

**P8 (Nov 23): evaluate the integration branches**

1. Two targets: the assigned `feat/iteration-4-week-1` (`623f19f`) and `iteration-4-week-2` (`01adfc1`, Nov 22), which adds the 30-minute minimum session rule.
2. Generate plans without the model. When all diagnosis questions are the reviewed 18, backend code sets the workload and topic split; the model only calls two tools in order. Calling the same two tools directly generates the 46 cases' plans with no OpenRouter key or cost.
3. Compute M1–M8 with a new metric script, generate the UAT build (`54db19b`) the same way, and compare the three builds in one table.
4. Run the real model (latency, generation failures) only if needed and at small scale. From `iteration-4-week-2` a device token is required and generation is limited to 3 plans per device per hour, so the existing evaluator cannot run unchanged.

**P9 (Nov 24–25): validate the criteria and improve the planner**

1. Hand-make six example plans: one meeting every criterion, one filling 100% of the time, one cut into 10-minute pieces, one ignoring weak topics, one cramming before the exam, one starving a subject.
2. Check that each example fails only its intended metric; otherwise fix the formula or threshold.
3. Change the planning logic using the fixed metrics. Candidates: a utilization cap (keep slack), spreading each topic over at least two days, a review session in the final window.
4. Run before and after on the same 46 cases and report per-metric pass rates.

**Still open:** the thresholds (especially the M2 upper bound 0.85 and the M8 floor 0.30), whether to smooth diagnostic scores, and the length of M7's final window — all decided in P9 with the example plans.
