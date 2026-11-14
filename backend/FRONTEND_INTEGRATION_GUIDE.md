# Android frontend API integration

This guide documents the HTTP contract used by `frontend/`'s Android `live`
flavor. Run the API from this repository's `backend/` directory. The OpenRouter
API key is server-side only and must remain in the backend process environment or
`backend/.env`.

## Run locally

Start the backend with the project Python environment and `OPENROUTER_API_KEY`
configured (generation and adjustment use OpenRouter model `openai/gpt-6-luna`):

```powershell
cd backend
python api_server.py
```

The API listens on `127.0.0.1:8000` by default, under `/api/v1`. The Android
Emulator reaches the host machine through `http://10.0.2.2:8000/api/v1`; this is
the app's default live URL. A USB-connected device can use `adb reverse tcp:8000
tcp:8000` and a live build configured with
`-PlicensePlanner.apiBaseUrl=http://127.0.0.1:8000/api/v1`.

Check connectivity with `GET /api/v1/health`, which returns
`{"status":"ok"}`. The app live flavor calls:

- `GET /api/v1/certifications/{certification_id}/questions`
- `POST /api/v1/study-plans`
- `POST /api/v1/schedules/adjust`
- `GET /api/v1/study-plans` for explicit reload of a retained plan

The current MVP uses `computer_specialist_level_2`.

## Question bank

`GET /api/v1/certifications/{certification_id}/questions` returns the bank's
configured onboarding selection in its configured order. Each question contains
`id`, `prompt`, `choices`, `correct_answer`, and `possible_score`; the response
also includes `certification_id` and `certification_name`. The MVP question bank
is authored example content, not an official exam bank or validated diagnostic.
For Level 2 the selection is IDs 1–18: four-choice, one-point items, nine each
for 컴퓨터 일반 and 스프레드시트 일반. The response does not include topic labels,
review metadata or questions stored outside the selection, so the stored
collection can grow without changing this response. Every configured bank must
name its selection explicitly; there is no whole-bank fallback. A missing or
invalid selection (empty, a repeated or unknown ID, a selected item that is not
reviewed and complete, or selected scores whose total is not finite), an
incomplete reviewed record, or an enabled certification whose planning entry,
bank mapping or bank file is missing or mismatched returns `500` /
`INTERNAL_ERROR` with a fixed message and no questions; the message never
contains file paths. An ID that is not enabled still returns `404`.
The selection is mapped to the KCCI 2024–2026 written syllabus. Its update for
the 2027–2029 syllabus is a separate planned change, so from 2027-01-01 the
served questions are behind the current syllabus; see
[question-bank maintenance](DEVELOPER_GUIDE.md#문항-은행-유지보수).

```json
{
  "certification_id": "computer_specialist_level_2",
  "certification_name": "컴퓨터활용능력 2급",
  "questions": [
    {
      "id": 1,
      "prompt": "Question text",
      "choices": {"A": "Choice A", "B": "Choice B"},
      "correct_answer": "B",
      "possible_score": 1
    }
  ]
}
```

The current app performs answer comparison locally. The public question response
includes `correct_answer` for that purpose; do not treat this MVP endpoint as a
secure exam or grading service.

## Generate a plan

`POST /api/v1/study-plans` accepts the form values and locally-scored assessment
results. Dates use `YYYY/MM/DD/HH/MM` and availability times use `HH:MM`.

```json
{
  "certification_id": "computer_specialist_level_2",
  "preparation_start": "2026/10/05/00/00",
  "exam_date": "2026/11/30/00/00",
  "self_assessment": "엑셀을 처음 공부하는 초보입니다.",
  "study_days_per_week": 1,
  "weekly_availability": {
    "monday": [{"start": "18:00", "duration_minutes": 180}]
  },
  "availability_overrides": {},
  "busy_schedules": [],
  "assessment_results": {
    "certification_id": "computer_specialist_level_2",
    "results": [
      {
        "problem_id": 1,
        "question": "Question text",
        "choices": {"A": "Choice A", "B": "Choice B", "UNKNOWN": "모르겠음"},
        "correct_answer": "B",
        "user_answer": "UNKNOWN",
        "is_correct": false,
        "possible_score": 1
      }
    ]
  }
}
```

`assessment_results.certification_id` must match the top-level ID. Each result
must include a positive integer `problem_id`, non-empty question text, a choice
map with non-empty keys and labels and at least two normal choices besides
`UNKNOWN`, correct and user choice keys, boolean `is_correct`, and a positive
finite numeric `possible_score` (it is required; there is no one-point default).
`UNKNOWN` is reserved: its label must be `모르겠음` and it is never the correct
key. The Android app compares the selected answer with the answer key; the
server checks that `is_correct` equals that comparison, with `UNKNOWN` always
incorrect, and that the total possible score is finite. Any violation returns
`422` / `INVALID_INPUT` before a model call or saved plan; inconsistent flags
are rejected, never rewritten.

### Study windows and week overrides

A window is `{"start":"HH:MM","duration_minutes":N}` with an integer duration
from 1 to 1439. It belongs to the weekday it starts on and may end on the next
day, so `{"start":"23:00","duration_minutes":90}` on Monday runs until Tuesday
00:30. The older same-day `{"start","end"}` form (start before end) is still
accepted, and a window may send all three keys when `end` equals start plus
duration modulo one day. Booleans, fractions, null, 0 and 1440 durations,
unknown keys and non-`HH:MM` times return `422 INVALID_INPUT`. Effective windows
must not overlap, including a next-day spill (Monday 23:00/90 with Tuesday
00:15) and the Sunday-to-Monday spill; touching windows (Tuesday 00:30) are
valid.

`availability_overrides` maps a real Monday date, written `YYYY/MM/DD`, to a
whole replacement week in the same weekday shape. Within that week the
replacement is used instead of `weekly_availability`; `{}` is an explicit week
with no study windows, while a missing key means the week uses the base. A
window always follows the week of the date it starts on, so a Sunday 23:00
window keeps running past midnight into an empty override week. A null override
map, a non-Monday key or a non-object week returns `422 INVALID_INPUT`.

Context selection, for generation and adjustment alike:

- A non-null `weekly_availability` replaces the inherited context completely;
  inherited week overrides are not mixed in unless `availability_overrides` is
  sent with it.
- A supplied `availability_overrides` replaces the whole inherited override map
  for this request, and keeps the selected base.
- Without request availability, generation reads the shared profile
  (`weekly_availability: null` also does). Adjustment uses the selected plan's
  retained context, then the shared profile; explicit null is `422` there.
- Request availability is never saved to the profile or the plan.

A context with no window anywhere is invalid; a valid context whose relevant
weeks are all empty has zero usable time and follows `NOT_ENOUGH_TIME`.
Generation counts usable minutes per civil date: Monday 23:00/90 gives 60
minutes on Monday and 30 on Tuesday, clipped to the preparation start, exam
date and busy items. Generated rows may therefore split at midnight.

The successful response adds `availability_snapshot`, the accepted base and
full override map, with `start`, `duration_minutes` and a derived `end` (which
wraps past midnight) on every window. The plan keeps that context for later
adjustment even if the shared profile changes.

### Busy schedules

`busy_schedules` lists existing commitments the plan must avoid. It may be
omitted, `null` or empty. Each item is an object:

```json
{
  "schedule_id": "12345",
  "start_date": "2026/11/04/19/00",
  "end_date": "2026/11/04/20/00",
  "topic": "외부 일정"
}
```

`schedule_id` must be a positive base-10 integer string, unique within the
request; a prefix such as `calendar:` or a repeated ID returns `422
INVALID_INPUT`. Leading zeros are normalized away. Intervals are half-open
`[start_date, end_date)`: a study block may end exactly when a busy item starts,
but no study block overlaps one by a positive duration, and overlapping busy items
are subtracted once. Busy items that leave no usable time return the
`NOT_ENOUGH_TIME` error described below before any model call. Every item is
returned as an `external-<ID>` row in `schedules` and `schedule_csv`.

The Android calendar import sends one item per calendar occurrence with the
generic topic `외부 일정`. Recurring occurrences share a provider event ID, so the
app keeps that ID for the first occurrence and assigns later occurrences unused
numeric IDs above the largest provider ID. Timed events are widened to whole
minutes in Korean local time; all-day events block their whole dates. Titles and
other event details are not sent. See `../frontend/README.md#calendar-import` for
which events the app includes.

The response includes `schedules`, with `schedule_id`, `start_date`, `end_date`,
and `topic` on each row, plus `schedule_csv` and `agent_summary`. The server may
also return CSV location and diagnostic fields. The app ignores unknown fields
and displays the schedule rows. Date fields use the same
`YYYY/MM/DD/HH/MM` format.

The Android live flavor does not fall back to demo data after an API error. It
keeps the form values and surfaces the error. Common statuses are `422` for
invalid input, `502` for upstream planning errors, `503` for missing server
configuration, and `500` for unexpected server errors or a question-bank
configuration fault (fixed message, no paths; retrying cannot help until the
server's bank or catalogs are repaired). Error bodies use
`{"error":{"code":"...","message":"..."}}`.

`502`/`503` `UPSTREAM_ERROR` messages are fixed public sentences, not the
server's exception text: `Study plan generation failed. Try again later.` for
generation, `Schedule adjustment failed. Try again later.` for adjustment, and
`The planning service is not configured on the server.` when the OpenRouter key
is missing. They never include provider responses, tool names or arguments, agent
traces, or server paths, so clients must not parse them for a cause. `422`
`INVALID_INPUT` messages, including `NOT_ENOUGH_TIME`, and the `404`/`409`/`500`
plan-storage messages keep their specific text.

When the available time provably cannot give every required topic at least one
minute, plan generation returns `422` / `INVALID_INPUT` with an `error.message`
that starts with `NOT_ENOUGH_TIME`. After that code, the Korean message states the usable minutes and the
topic basis, and advises adding study windows, starting preparation earlier, or
choosing a later exam date. No schedule fields are returned and no plan file is
saved. Each submitted question is matched on its own: when its ID, question
text, normal choices, correct answer and possible score equal a complete
reviewed bank record (any subset, in any order, with `UNKNOWN` allowed; the
record need not be in the onboarding selection), its reviewed topic is used and
the model does not classify it. Only the remaining questions are classified by
the model. The check runs in two stages. Before any model call, a request whose
questions are all resolved uses its exact topic count; a mixed request uses the
number of resolved topics as a lower bound (unresolved questions may share
those topics); a request with no resolved question can only be rejected for
zero usable minutes, even when no server key is configured. After the model
maps the unresolved questions, the union of all assessed topics is checked
before any schedule is built. A bank or configuration fault returns `500`
instead of sending every question to the model. Passing the
check does not mean the time is enough to prepare. Invalid model allocations,
repeated tool failures and provider errors stay `502`/`503`; they never become
`NOT_ENOUGH_TIME`. On success, `agent_summary` is a short server-written summary
of the accepted schedule. Apart from `availability_snapshot`, described above,
no response fields were added.

## Other backend endpoints

`GET /api/v1/availability` and `PUT /api/v1/availability` read and replace the
single shared, unauthenticated profile. Both return
`{"weekly_availability": {...}, "availability_overrides": {...}}` with all seven
weekdays and `start`, `duration_minutes` and derived `end` on every window; a
missing profile returns empty lists and `{}`. PUT accepts that wrapper or a
direct weekday map. When PUT omits `availability_overrides`, stored overrides are
kept; an explicit `{}` removes them. The file is written atomically in duration
form (no `end`) only on a successful PUT; reads never rewrite an older start/end
file, and a rejected PUT leaves the file byte-identical. Whoever saves last
wins; existing plans keep their own snapshots. The Android app sends availability
with generation, uses the selected plan's original context for adjustment, and
reads or saves the profile only when the learner asks to. Android calls the
adjustment endpoint and explicitly reloads a retained plan through the plan-list
endpoint as described below; it does not import the server's plans into its list.

## Task 2: select, adjust, and update a saved plan

The behavior below is covered by the offline API suite (`tests/api_tests/`,
including the real-handler fixture in `test_support/fixed_live_e2e_server.py`)
and by the Android client's controlled tests described in
`../frontend/README.md#verification`. Neither exercises OpenRouter or the
deployed server. Earlier deployed and physical-device runs, including an
unresolved intermittent HTTP 307 on adjustment, are kept in the
[2026-10-30–31 device report](https://github.com/snuhcs-course/swpp-2026-project-team-02/blob/6d8af7fe65295f286c881d7d6c059c4844bd633f/docs/iter3-HE/03_device_verification.md); they did not include
the full two-plan same-file/CSV/original-window isolation procedure, which
remains unverified against a real server.

### Select a plan

`GET /api/v1/study-plans` returns `{"plans": [...]}`, newest modification first.
Each item includes `schedule_csv_file`, `schedule_csv_url`, and `schedules`, and
`availability_snapshot` when the plan was generated with retained context (it is
omitted for older plans and when that plan's metadata cannot be read, so the CSV
stays listable). An empty store returns `{"plans": []}`. Only recognized generated CSVs are listed;
an unreadable or malformed plan produces `500 STORAGE_ERROR`.

To read back one retained plan, add the optional query
`?schedule_csv_file=<URL-encoded identifier>` (encode it once; the `+` in the
identifier must be sent as `%2B`). The server then reads only that file, so a
damaged unrelated plan does not block it. The response keeps the same envelope:
one item for the selected plan, or `{"plans": []}` when that valid identifier no
longer exists. A selected plan that is unreadable or malformed still returns
`500 STORAGE_ERROR`. A blank, repeated, or invalid identifier (path, symlink, or
anything that is not a server-issued identifier) returns `422 INVALID_INPUT`
without reading files. Without the query, listing behaves as above; other
query parameters are ignored.

```json
{
  "plans": [{
    "schedule_csv_file": "license_planner/output/study_plan_20261017T120000000000+0900_ab12cd34.csv",
    "schedule_csv_url": "/api/v1/study-plans/study_plan_20261017T120000000000+0900_ab12cd34.csv",
    "schedules": [{
      "schedule_id": "study-1",
      "start_date": "2026/10/19/18/00",
      "end_date": "2026/10/19/19/00",
      "topic": "Functions"
    }]
  }]
}
```

Alternatively, retain `schedule_csv_file` and `schedules` directly from the
existing generation response. Treat the identifier as opaque and retain it with
the schedule; do not construct a filename. The Android DTO retains this
field and uses it unchanged for adjustment and exact-identity reload.

### Adjust the selected plan

`POST /api/v1/schedules/adjust` retains the existing request fields and accepts
these additional fields:

| Field | Behavior |
| --- | --- |
| `schedule_csv_file` | When present, update this existing plan; when absent, preserve the legacy non-persistent mode |
| `weekly_availability` | Optional request context under the selection rules above; otherwise the plan's retained context, then the shared profile. An explicit null or a context with no window is invalid |
| `availability_overrides` | Optional; replaces the inherited override map for this request only. Null is invalid |
| `mode` | Omitted or `"automatic"`: the agent-based move. `"manual"`: the deterministic move described below. `"remaining"`: the deterministic remaining-work batch described below, with its own required fields. Anything else returns `422 INVALID_INPUT` |
| `exam_date` | Saved plan (`schedule_csv_file` present): optional. The server uses the exam timestamp stored when the plan was generated; a supplied value must equal it exactly, and explicit null or a malformed value is rejected. Without `schedule_csv_file`: a valid `YYYY/MM/DD/HH/MM` value is required when moving a `study-N` row; missing, null or malformed returns `422 INVALID_INPUT` before any model call. Never needed for `external-N` removal. |

Always send the complete schedule currently displayed, not only the failed row.
The rows must match the stored plan (array order does not matter). Existing IDs,
topics and session durations are preserved for study-session movement. An
`external-N` target retains the existing external-event removal behavior.

#### Exam deadline of a saved plan

Each plan generated by `POST /study-plans` stores its validated `exam_date`
beside the CSV, in a sidecar named after the CSV with `.meta.json` instead of
`.csv`. New plans write version 2 of that sidecar, which also keeps
`preparation_start` and the accepted `availability_snapshot` in duration form:

```json
{"version":2,"exam_date":"2026/12/07/00/00","preparation_start":"2026/11/16/00/00",
 "availability_snapshot":{"weekly_availability":{"monday":[{"start":"23:00","duration_minutes":90}],"tuesday":[],"wednesday":[],"thursday":[],"friday":[],"saturday":[],"sunday":[]},"availability_overrides":{}}}
```

Older exam-only sidecars are still read as before and are never rewritten or
backfilled. The CSV identity and download format do not change, and there is no
metadata route. Moving a study session of a saved plan always uses
that stored timestamp, also after a server restart:

- `exam_date` omitted: the stored deadline is used. Earlier Android releases that
  send only the four fields `schedule_csv_file`, `schedule_id`, `schedules` and
  `weekly_availability` can adjust newly generated plans.
- `exam_date` equal to the stored value: accepted; the metadata is not rewritten.
- A different valid timestamp (earlier, later, or another plan's): `422
  INVALID_INPUT` with `exam_date: must match the selected plan's stored exam date`.
  Explicit null or a malformed value also returns `422 INVALID_INPUT`.
- Plans saved before deadlines were stored have no sidecar. Moving one of their
  study sessions always returns `422 INVALID_INPUT` with exactly
  `This saved plan has no stored exam date. Create a new plan before adjusting study sessions.`
  A supplied `exam_date` does not bypass this or create metadata. These plans
  stay listable, downloadable and viewable, and their `external-N` rows can still
  be removed. To adjust study sessions, generate a new plan.
- Present but damaged or unreadable metadata, including an invalid retained
  context, returns `500 STORAGE_ERROR`; the request date or availability is never
  used as a substitute.

Stale rows (409) and a missing plan (404) are reported before any deadline
check. Without `schedule_csv_file`, the non-persistent mode bounds the move by
the supplied `exam_date` and saves nothing; no stored deadline is involved.
There a study move must supply a valid `exam_date`: a missing, null or malformed
value returns `422 INVALID_INPUT` before any model call, and nothing is written.

Example request for a saved plan (replace the example identifier and rows with
actual values; `exam_date` may be omitted, and if sent must be that plan's
original creation timestamp):

```json
{
  "schedule_csv_file": "license_planner/output/study_plan_20261017T120000000000+0900_ab12cd34.csv",
  "schedule_id": "study-1",
  "schedules": [{
    "schedule_id": "study-1",
    "start_date": "2026/10/19/18/00",
    "end_date": "2026/10/19/19/00",
    "topic": "Functions"
  }],
  "weekly_availability": {
    "monday": [{"start": "18:00", "end": "21:00"}]
  }
}
```

Illustrative success response; summary and trace depend on the agent:

```json
{
  "schedule_csv_file": "license_planner/output/study_plan_20261017T120000000000+0900_ab12cd34.csv",
  "schedule_csv_url": "/api/v1/study-plans/study_plan_20261017T120000000000+0900_ab12cd34.csv",
  "schedules": [{
    "schedule_id": "study-1",
    "start_date": "2026/10/19/19/00",
    "end_date": "2026/10/19/20/00",
    "topic": "Functions"
  }],
  "schedule_csv": "schedule_id,start_date,end_date,topic\nstudy-1,2026/10/19/19/00,2026/10/19/20/00,Functions\n",
  "action": "rescheduled_study",
  "target_schedule_id": "study-1",
  "agent_summary": "The failed session was moved.",
  "tool_calls": []
}
```

A persistent request returns 200 only after replacing the selected file. Its
identifier, download URL and stored metadata remain unchanged. When the plan has
a retained context, the response adds that original `availability_snapshot`,
never the request's one-time availability. Replace FE's entire displayed
schedule with the response rows. Non-persistent responses keep their previous
shape and omit the two file fields.

#### Manual move

`"mode": "manual"` moves one study row of a saved plan to a chosen start without
any model request. It requires `schedule_csv_file`, the full current
`schedules`, a `study-N` `schedule_id` and `new_start_date`
(`YYYY/MM/DD/HH/MM`). Optional: `confirm_outside_availability` and
`confirm_external_overlap` (independent booleans, each default false), `exam_date` (same stored-deadline rule as above), request availability
(same selection rules) and `preparation_start`. A plan with retained context uses
its stored preparation start, and a supplied value must equal it. An exam-only
plan requires a valid `preparation_start` before its exam; it is only a
client-supplied lower bound for this request and is not stored. A plan without a
sidecar returns the no-stored-exam-date error above.

```json
{
  "mode": "manual",
  "schedule_csv_file": "license_planner/output/study_plan_20261017T120000000000+0900_ab12cd34.csv",
  "schedule_id": "study-1",
  "schedules": [{"schedule_id": "study-1", "start_date": "2026/11/23/23/00",
                 "end_date": "2026/11/24/00/00", "topic": "함수와 데이터 관리"}],
  "new_start_date": "2026/11/24/14/00",
  "confirm_outside_availability": false
}
```

The row keeps its ID, topic and duration; other rows are unchanged. The move
ignores the automatic after-original/same-topic order, so an earlier free future
time is allowed. Checks run after request parsing, stale-row (409), missing-plan
(404) and stored-metadata checks, in this order:

| Status / code | Meaning |
| --- | --- |
| 422 / `MANUAL_MOVE_IN_PAST` | Starts before the server's current Asia/Seoul minute |
| 422 / `BEFORE_PREPARATION_START` | Starts before the preparation start |
| 422 / `EXAM_BOUND_EXCEEDED` | Would end after the stored exam timestamp (ending exactly at it is allowed) |
| 422 / `STUDY_SESSION_OVERLAP` | Overlaps another `study-N` row |
| 422 / `EXTERNAL_EVENT_OVERLAP` | Overlaps an `external-N` row |
| 422 / `OUTSIDE_STUDY_WINDOWS` | Does not fit inside one effective study window (an overnight window counts whole); message: `The chosen time is outside the study windows. Confirm this one-time exception.` |

Only `OUTSIDE_STUDY_WINDOWS` is waived by `confirm_outside_availability: true`;
`confirm_external_overlap: true` waives only `EXTERNAL_EVENT_OVERLAP`. Both
flags are required when both constraints apply. Study overlap and temporal bounds
remain mandatory. The external flag rejects null/nonboolean values and any
supplied value outside manual mode as `422 INVALID_INPUT`, before provider access
or mutation. Automatic requests must omit it. The server checks retained external
rows only; fresh device occurrences and their private keys are never sent.
A context with no study window always needs outside-window confirmation. Touching rows are not overlaps. A missing
destination or identity, an `external-N` target, a non-boolean confirmation or a
mismatching `exam_date`/`preparation_start` returns `422 INVALID_INPUT`. Every
rejection leaves the plan, its metadata and the profile unchanged. A confirmed
resubmission is validated again from scratch.

Success uses the normal response: `schedules`, `schedule_csv`, the same file
identity and URL, `action` `rescheduled_study`, the requested
`target_schedule_id`, a server-written Korean `agent_summary`, `tool_calls: []`,
and the retained `availability_snapshot` when the plan has one. It uses the same
digest-checked same-file replacement as automatic moves, so a lost response has
no rollback or exactly-once guarantee: reload before choosing again.

#### Remaining-work re-planning

`"mode": "remaining"` places the remaining minutes of several study rows of a
saved plan in one deterministic batch, without any model request and without
reading or writing the shared availability profile. Every field below is
required and must not be null:

| Field | Meaning |
| --- | --- |
| `schedule_csv_file` | Server-issued identity of the selected plan |
| `schedules` | The complete current rows, including external and completed rows; must match the stored plan |
| `weekly_availability`, `availability_overrides` | The plan's original context; must equal the retained context after normalization (window order and `end`/`duration_minutes` forms do not matter) |
| `preparation_start`, `exam_date` | The plan's original bounds; must equal the retained values |
| `replan_from` | `YYYY/MM/DD/HH/MM`; no session is placed before it |
| `remaining_sessions` | Non-empty array of `{"schedule_id", "remaining_minutes", "replacement_schedule_id"}` objects with exactly those keys |

`schedule_id`, `new_start_date` and `confirm_outside_availability` must be
absent. Each instruction names a distinct `study-N` source row.
`remaining_minutes` is a JSON integer from 1 to the source row's scheduled
minutes (booleans and decimals such as `60.0` are rejected). A
`replacement_schedule_id` equal to its source keeps the ID and needs the full
duration; otherwise it must be a new `study-N` numbered above every current
study row, not used by any row, and unique within the request.

```json
{
  "mode": "remaining",
  "schedule_csv_file": "license_planner/output/study_plan_20261017T120000000000+0900_ab12cd34.csv",
  "schedules": [{"schedule_id": "study-1", "start_date": "2026/11/16/18/00",
                 "end_date": "2026/11/16/19/00", "topic": "함수와 데이터 관리"},
                {"schedule_id": "study-4", "start_date": "2026/11/17/18/30",
                 "end_date": "2026/11/17/19/00", "topic": "함수와 데이터 관리"}],
  "weekly_availability": {"monday": [{"start": "18:00", "duration_minutes": 120}],
                          "tuesday": [{"start": "18:00", "duration_minutes": 60}]},
  "availability_overrides": {},
  "preparation_start": "2026/11/16/00/00",
  "exam_date": "2026/12/07/00/00",
  "replan_from": "2026/11/16/20/31",
  "remaining_sessions": [
    {"schedule_id": "study-1", "remaining_minutes": 60, "replacement_schedule_id": "study-1"},
    {"schedule_id": "study-4", "remaining_minutes": 10, "replacement_schedule_id": "study-5"}
  ]
}
```

The server removes every listed source row, keeps all other rows unchanged as
busy time, and places the instructions in the sources' original order (start,
end, then `study-N` number). Each becomes one whole session with the source
topic in the earliest free part of one effective study window that starts no
earlier than the latest of the preparation start, `replan_from` and the
server's next Asia/Seoul minute, and ends by the exam timestamp. Touching rows
are not overlaps; existing overlaps among unselected rows are kept. Placement is
the same rule set as the schedule-adjuster README describes; it never splits a
session.

Success returns `schedules`, `schedule_csv`, the same file identity and URL,
the retained `availability_snapshot`, `action` `replanned_remaining`, a Korean
`agent_summary` with the placed count and minutes, and `tool_calls: []`. There
is no `target_schedule_id`. The whole batch is one digest-checked same-file
replacement.

Errors: invalid fields or instructions, mismatched bounds/context or an invalid
identity return `422 INVALID_INPUT`. A plan whose metadata lacks a retained
preparation start and context (exam-only or CSV-only) returns `422
INVALID_INPUT` with a create-a-new-plan message. If any instruction has no whole
fit, the request returns `422 INVALID_INPUT` with `NOT_ENOUGH_TIME` and nothing
is saved; a different order or splitting might still fit, so this is not proof
that no schedule exists. Stale rows or a CSV/metadata change before the write
return 409 `PLAN_CONFLICT`, a missing plan 404 `PLAN_NOT_FOUND`, damaged metadata
or a failed write 500 `STORAGE_ERROR`. Every rejection leaves the plan, its
metadata, other plans and the profile unchanged. A server without this mode
answers `422 INVALID_INPUT` (`mode must be ...`); do not retry in another mode.

### Error handling and limits

| Status / code | FE handling |
| --- | --- |
| 422 / `INVALID_INPUT` | Correct invalid fields, duplicate IDs, missing target, or unusable availability; for the fixed no-stored-exam-date message, create a new plan |
| 422 / manual-move codes | Keep the chosen time for correction or explicitly review the independent outside-window and external-overlap exceptions described above |
| 404 / `PLAN_NOT_FOUND` | Refresh the plan list; the file may have been pruned |
| 409 / `PLAN_CONFLICT` | Reload the plan before resubmitting; never overwrite with stale rows |
| 500 / `STORAGE_ERROR` | Report storage failure |
| 502 or 503 / `UPSTREAM_ERROR` | Report agent/configuration failure |

Errors keep `{"error":{"code":"...","message":"..."}}`. Android distinguishes
adjustment 404 `PLAN_NOT_FOUND` and 409 `PLAN_CONFLICT` from route errors. Any
adjustment 409, lost connection/timeout, or unreadable/mismatched success requires
an explicit reload before a fresh selection and submission; it never replays the
uncertain mutation. A missing plan stays viewable but unavailable for adjustment
for the app session. These client paths have controlled-test evidence; real
Android/OpenRouter Luna same-file A/B acceptance remains pending.

If a call times out, reload before retrying: the write may have completed.
There is no idempotency key. File reads and replacements share a process lock;
the original CSV and exam-metadata digests are checked again after the agent
runs, and a change to either returns 409 `PLAN_CONFLICT`. Conflicting
requests cannot silently overwrite one another within one server process.
Cross-process locking is not provided.

Availability in a generation or adjustment request is not saved automatically.
If no request context, retained plan context or saved profile exists, automatic
adjustment uses the legacy conflict-only rule. The current Android app retains the original creation
request and therefore resends its plan-specific availability and exam timestamp
(five fields); the server checks that timestamp against the stored one. A study
session with no slot ending by the effective exam timestamp returns `422
INVALID_INPUT` with `NOT_ENOUGH_TIME` and leaves the stored plan unchanged. The
store retains up to 100 generated plans on a best-effort basis; pruning a CSV
also removes its sidecar.

### Local execution

With `OPENROUTER_API_KEY` configured on the backend, start from the repository root:

```powershell
Set-Location backend
python api_server.py
```

In another terminal from `backend/`, list plans and prepare a request using
actual data. These are manual execution instructions, not recorded test results.
The request omits `exam_date`, so the server uses the selected plan's stored
deadline; a plan saved before deadlines were stored returns the recreation
message instead.

```powershell
$plans = Invoke-RestMethod http://127.0.0.1:8000/api/v1/study-plans
$selected = $plans.plans | Select-Object -First 1
if ($null -eq $selected) { throw 'Generate a plan before adjusting.' }
$failed = $selected.schedules | Where-Object { $_.schedule_id -like 'study-*' } | Select-Object -First 1
if ($null -eq $failed) { throw 'The selected plan has no study session.' }
$payload = @{
    schedule_csv_file = $selected.schedule_csv_file
    schedule_id = $failed.schedule_id
    schedules = @($selected.schedules)
} | ConvertTo-Json -Depth 10
Invoke-RestMethod -Method Post `
    -Uri http://127.0.0.1:8000/api/v1/schedules/adjust `
    -ContentType 'application/json; charset=utf-8' `
    -Body ([System.Text.Encoding]::UTF8.GetBytes($payload))
```

For a static payload template, see `scenarios/inputs/api/plan_adjustment_request.json`.
Its sample file does not exist automatically; replace both the identifier and
schedule rows with those from generation or listing. The Android Emulator base
URL remains `http://10.0.2.2:8000/api/v1`.
