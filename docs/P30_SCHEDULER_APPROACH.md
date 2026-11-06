# P30 — Calendar conflict-aware scheduling approach

**Decision date:** 2026-11-03  
**Baseline:** `main` at `09d66ef` (Iteration 3 PR #8 merge)

## Decision

Keep and extend the repository's deterministic Python scheduler in
`backend/license_planner/scheduler.py`.  Do **not** add an external scheduler
package for P30.

The scheduler already subtracts `PlanRequest.busy_periods` from each weekly
availability window before calculating capacity and placing study blocks.  It
therefore provides the required invariant for US-C9(A): a generated study
block must not overlap a calendar event supplied as a busy period.

## External review

| Project | Useful idea | Decision |
| --- | --- | --- |
| [study-planner](https://github.com/dguywhoknows/study-planner) | Deadline-aware priority, replan and calendar presentation | Reference only. Its JavaScript app and spaced-repetition policy do not match the current Python/API contract. |
| [PrepFlow](https://github.com/piyusshhjangid/PrepFlow) | Keep AI output structured, then let deterministic code enforce capacity and priority | Adopt this principle. The TypeScript/PostgreSQL application itself is not a dependency. |
| [task_scheduler](https://github.com/longauer/task_scheduler) | Time slots and deadline-aware placement | Reference only. Its generic CLI task model would require replacing the existing plan, topic and Android contracts. |

## P29 → P30 contract

P29 converts each readable calendar event into this API item and supplies the
list in `StudyPlanRequest.busySchedules`:

```json
{
  "schedule_id": "12345",
  "start_date": "2026/11/04/19/00",
  "end_date": "2026/11/04/20/00",
  "topic": "외부 일정"
}
```

`schedule_id` is the provider event ID converted to a positive base-10 string;
it must not use a prefix such as `calendar:` because the existing backend
validates numeric IDs.  Once P29 supplies the list, `RequestMapper` must
serialize it as `busy_schedules`; `api_service` parses it into
`PlanRequest.busy_periods`.  P30 treats intervals as half-open `[start, end)`:
a study block ending exactly when an event starts is allowed, but any
positive-duration overlap is rejected by placement.

Until P29 lands, P30 uses fixed JSON/CSV fixtures only.  Calendar permissions,
recurrence expansion and all-day-event interpretation remain P29's ownership.

## Implementation and test scope

1. Retain `_free_segments` as the single subtraction implementation for both
   capacity calculation and block placement; do not duplicate conflict logic
   in the model prompt or Android client.
2. Extend the fixed availability fixture/runner only for scenarios absent from
   the unit suite.  Existing unit tests already cover overlapping, adjacent,
   partially clipped and fully blocked busy intervals.
3. For P30 acceptance, record: source commit, fixture input, generated blocks,
   and a check that every study block is disjoint from every busy interval.
4. When P29 is available, add the Android request-mapper test and one
   live-flavor fixed-response E2E path.  Do not make that E2E test call
   OpenRouter.

## P30 completion criteria

- A fixed busy event inside a user's available window is excluded from all
  generated study blocks.
- When busy events remove all usable time, generation returns the existing
  `NOT_ENOUGH_TIME` input error before a model call.
- Adjacent intervals remain valid and overlapping/nested calendar intervals do
  not double-subtract capacity.
- The P29 mapping test proves that the calendar list reaches the backend as
  `busy_schedules`.

## 2026-11-04 fixed-input API verification

`tests.api_tests.test_plan_generation` drives a temporary HTTP server with a
scripted model, so it does not call OpenRouter.  It verifies both P30 outcomes
through `POST /api/v1/study-plans`:

- A Monday 18:00–20:00 availability window with a fixed 18:00–19:00 busy event
  produces three 20-minute study blocks beginning at 19:00; every study block
  is checked to be disjoint from the busy interval.
- A busy event covering the whole available window returns the existing
  `NOT_ENOUGH_TIME` input error (HTTP 422) before a model call or plan-file
  write.

The Android calendar-reader implementation remains P29 work; these cases use
the final `busy_schedules` wire format as fixed input.

## Adjustment boundary

For a failed study-session adjustment, the Android client resends the original
plan's `exam_date` with the saved weekly availability.  The adjustment API
requires that value for study rows, and the deterministic slot search caps each
candidate window at the exam timestamp.  If a same-topic continuation cannot
fit before the exam, it returns `NOT_ENOUGH_TIME` before invoking the adjustment
agent or changing the saved plan.  This prevents an otherwise valid adjustment
from moving a study session beyond the exam.

## Commands

Run from `backend/`:

```bash
python -m unittest tests.api_tests.test_plan_generation
python -m unittest tests.license_planner_tests.test_planning_capacity
python -m scenarios.run_license_planner_availability_test_cases
```

The second command writes ignored, local traces beneath
`scenarios/results/license_planner_availability/`; it does not call a model.

## 2026-11-06 live verification record

The signed `liveRelease` APK built from `5216f41` was exercised against the
public CPU-server API. A plan with a 2026-11-13 exam date was generated and
re-opened after app relaunch. For a missed-session adjustment that could not be
placed before that date, the API returned the intended insufficient-time outcome:
the app preserved the selected entry and showed Korean recovery guidance instead
of placing study after the exam. A saved adjusted plan was also re-opened after
app relaunch.

This confirms the P30 deadline boundary on the public integration path. It does
not cover P29 calendar-provider reading, runtime permission handling, recurrence
expansion, or physical-device compatibility. Supporting screenshots and exact APK
provenance are in [Testing Documentation](TESTING_DOCUMENTATION.md#public-deployment-and-signed-apk-verification--2026-11-06).
