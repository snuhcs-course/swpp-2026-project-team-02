# Iteration 3 — Test Automation and P30 Evidence

**Owner:** 조준우  
**Updated:** 2026-11-06  
**Working revision:** [`5216f41`](https://github.com/snuhcs-course/swpp-2026-project-team-02/commit/5216f41136a01218766ff5a3e13134d12704a972)

## Responsibility boundary

This record covers P16, P18, P19, and P30 work owned by 조준우:

| Item | Delivered result |
| --- | --- |
| P16 | Android `live` flavor to fixed-backend E2E: questions → create plan → persisted-plan GET, run locally and on CI. |
| P18/P19 | Backend/Android CI structure, coverage-report validation, reproducible local commands, artifact retention, and written test evidence. |
| P30 | Deterministic busy-period conflict avoidance and exam-date-bounded rescheduling; public integration validation of the deadline boundary. |

P29 (calendar-provider event reading and Android permission behavior) is explicitly
outside this document's implementation scope. P30 consumes the `busy_schedules`
wire format once P29 supplies it.

## Automation evidence

The authoritative automated result is [GitHub Actions CI #57](https://github.com/snuhcs-course/swpp-2026-project-team-02/actions/runs/42013613486), which passed at
[`e95db19`](https://github.com/snuhcs-course/swpp-2026-project-team-02/commit/e95db19).

| Job | Result | What it proves |
| --- | --- | --- |
| Backend | Passed | Offline planner, schedule-adjuster and API suites with combined coverage. |
| Android | Passed | Demo/live JVM tests, Android-to-backend request contract, JaCoCo reports, debug APKs and lint. |
| Android live E2E | Passed | On an API 35 emulator, the Android `live` code path communicates with the fixed backend fixture through real HTTP and re-reads a saved plan. |

The E2E fixture reuses production request validation, scheduling and storage but
replaces only the model call with a recorded tool trace. Consequently it provides
repeatable CI evidence without model credit or deployment dependence; it does
not prove OpenRouter behavior.

For exact command lines, test counts, coverage values, artifacts and retention,
see [Testing Documentation](TESTING_DOCUMENTATION.md). The script
[`scripts/verify_test_evidence.py`](../scripts/verify_test_evidence.py) validates
the generated backend/Android report set but does not duplicate scenario-runner
assertions.

## Scheduler decision and P30 behavior

P30 extends the repository's deterministic scheduler rather than importing an
external scheduling package. It subtracts `busy_schedules` from each availability
window before both capacity calculation and study-block placement. The same
deadline bound is used when an entry is rescheduled: a candidate must finish no
later than `exam_date`.

This prevents two invalid outcomes:

1. A study block overlapping a calendar interval that P29 has supplied.
2. A missed-study continuation being moved beyond the exam date.

If no slot remains, the API returns `NOT_ENOUGH_TIME` before changing the stored
plan. The Android client preserves the selected entry and offers Korean recovery
guidance. Details, test inputs and P29→P30 contract are in
[P30 scheduler approach](P30_SCHEDULER_APPROACH.md).

## Public deployment evidence

On 2026-11-06, the signed `liveRelease` build from
[Prod APK run #34](https://github.com/snuhcs-course/swpp-2026-project-team-02/actions/runs/42028937748) was connected to:

```text
http://swpp26t02.147-46-242-174.sslip.io/api/v1
```

The public health check succeeded. The live run created a plan, persisted it
across an app relaunch, showed the pre-exam insufficient-time adjustment outcome
without modifying the selected entry, and re-opened a saved adjusted plan after
relaunch. Screenshot evidence is in [`docs/images/iter3-live/`](images/iter3-live/).

This is a public-server Android emulator verification. It is intentionally
separate from offline CI and does not claim calendar-provider coverage or broad
physical-device compatibility. The historical intermittent adjustment HTTP 307
observation remains open until a cause and targeted retest are recorded.

## Remaining handoff notes

- Keep the current signed `liveRelease` artifact and source commit together in
  any final HE or demo record.
- Do not put `OPENROUTER_API_KEY`, signing passwords, keystore bytes, or SSH keys
  in evidence, artifacts, or documentation.
- If P29 starts supplying calendar events, add the request-mapper and one
  fixed-response E2E check described in the P30 contract before calling US-C9
  complete.
