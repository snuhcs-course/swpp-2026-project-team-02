# P10 AI evaluation — written examination

Restored from `6d8af7f` for the current selected 18-question, eight-topic bank.
The [original benchmark and failed results](https://github.com/snuhcs-course/swpp-2026-project-team-02/tree/6d8af7fe65295f286c881d7d6c059c4844bd633f/docs/iter3-ai-eval)
remain in history. This is a new dataset version, not a rerun of that three-topic
benchmark. No new model-quality results are claimed by this restoration.

## Dataset

`build_cases.py` reads the reviewed onboarding selection in
`backend/assessment_questionnaire/questions.json`. Every request includes all
18 selected answers. Expected scores use reviewed topic tags independently of
the scheduler. `cases.json` records the UTF-8 bank SHA-256 after normalizing
line endings to LF so Windows and Linux checkouts match.

46 cases: 15 weak-topic allocation, eight availability, six busy-calendar,
six capacity/input, five adjustment, two self-assessment and four flexible-availability cases. Five new
allocation cases individually weaken computer-system use, internet use, system
protection, printing and macros. Existing weak-topic patterns now miss all
questions in those topics; A10 misses only question 2. The HE pattern retains
UNKNOWN for question 3 and a wrong answer for question 4.

P10 baseline backend: `cad66888200633bd6b378dc01894b2e67fbfb37d`.
Version `p10-flexible-availability-v3` adds duration-only/overnight windows,
an empty replacement week, a different replacement week and an overnight busy
interval. Its interval oracle is independent of the backend's resolver and
uses the starting date's week for Sunday spill into Monday.

Fixed default dates: 2026-11-09 through 2026-11-30 09:00. Boundary cases have
explicit dates. These are reproducible benchmark inputs, not today's dates.
Default availability: Mon/Wed/Fri 19:00–20:30, Sat 10:00–12:00.

## Run and compare

Python 3.10+, standard library only. From the repository root:

```bash
python docs/iter3-ai-eval/build_cases.py
python -m unittest discover -s docs/iter3-ai-eval -p "test_*.py" -v
# Live smoke test: DOES call the backend/model.
python docs/iter3-ai-eval/run_eval.py --base-url http://127.0.0.1:8000/api/v1 --only A09 --reps 1
# Full run against an isolated backend configured with the actual model.
python docs/iter3-ai-eval/run_eval.py --base-url http://127.0.0.1:8000/api/v1 --server-commit ACTUAL_DEPLOYED_COMMIT --model ACTUAL_MODEL --inference-config run-config.json --output-dir outputs/p10-before
```

Use backend/README.md for server setup and an isolated `APP_STUDY_PLAN_DIR`.
The shared server's retention limit can prune other users' plans. Fixed-response
servers verify integration only and cannot establish AI quality.

Before running, prepare `run-config.json` containing actual provider/model,
temperature/decoding settings (explicitly state omitted/provider defaults),
reasoning effort, token/step limits and prompt/tool-schema hashes. Include APK
version if paired with Android validation. Pass the actual deployed commit.
Never put API keys in this configuration.

For P10 before/after comparison, freeze this runner, dataset, model and inference
settings. Run against baseline and improved deployments with their actual commit
IDs and separate output directories. Compare Q1/Q2, per-topic minutes and shares,
Q6 variation, utilization and latency. Do not compare old three-topic results
directly with this eight-topic version.

Model cases run three times; D01/D02/D06 once: 132 generation requests and up to
15 adjustments, 147 total. Requests are sequential with three-second pauses,
a 120-second timeout and no automatic retries. Five consecutive 5xx stop the run;
partial results are retained. Subset, overridden-repetition and interrupted runs
are not full acceptance results.

## Fixed checks and thresholds

Original thresholds are retained, applied to all eight topics. Freeze this
dataset/criteria before collecting the P10 baseline; do not tune after results.

| Check | Meaning |
| --- | --- |
| H1–H5 | Nonempty study plan; within availability and preparation/exam bounds; no study/busy overlaps; valid topics; busy intervals retained |
| Q1 | At least one weakest topic receives the largest minute allocation |
| Q2 | Pearson correlation between topic scores and minutes ≤ −0.5 |
| Q3 | Every topic receives positive minutes (except D03) |
| Q4/Q5/Q6 | Utilization / share of sessions under 15 minutes / repeated-run topic-share range |
| Q7 | No score-ordered pair receives fewer minutes for the weaker topic; integer-minute ties are allowed |
| J1–J5 | Successful rescheduling; target moved; topic/duration preserved; other rows unchanged; available and conflict-free |
| J6 | Movement beyond the exam reported separately, as in the original evaluator |

Q1/Q2/Q7 apply when scores differ. Q7 is the P10 ordering criterion: all
successful, hard-valid applicable candidate plans must pass it. It is reported
separately from the original T1–T8 verdict; keep both visible. The criterion,
dataset and oracle must be identical for the baseline and candidate runs.

| Criterion | Threshold |
| --- | --- |
| T1 | ≥90% of expected-200 generations return 200 and pass H |
| T2 | Zero H violations |
| T3 | D01/D02: 422 NOT_ENOUGH_TIME; D06: 422 INVALID_INPUT; D03: no 5xx/timeouts (200 or 422 NOT_ENOUGH_TIME accepted) |
| T4 | Zero timeouts; latency p95 ≤90 seconds |
| T5/T6 | Q1 and Q2 each pass ≥80% of applicable successful plans |
| T7 | Q3 passes ≥90% of applicable successful plans |
| T8 | ≥90% adjustment success; all successful adjustments pass J2–J5 |

Report-only goals: median utilization ≥50%, short-session share ≤10%,
maximum topic-share range ≤0.15. Connection errors, HTTP 5xx, unexpected status,
hard violations and timeouts remain in recorded outcomes.

## Outputs

Timestamped directories contain `exchanges.jsonl` (API requests/responses),
`runs.jsonl`, `runs.csv`, `summary.json` and `summary.md`. Metadata records the
supplied deployment/model/configuration, runner commit and runner/dataset/bank
hashes. File hashes identify uncommitted runner changes; commit alone does not.
Keep raw outputs local and redact personal data/secrets before sharing.
Historical result folders are not restored as current results.
