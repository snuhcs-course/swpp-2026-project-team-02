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

All routes, relative to `/api/v1` ("token" is the [device token](#device-token);
"slot" is one admission under [request limits](#request-limits-and-public-mode)):

| Method and path | Token | Slot | Success and route-specific errors |
| --- | --- | --- | --- |
| `GET /health` | No | No | `200 {"status":"ok"}` |
| `GET /certifications` | No | No | `200` public certification list |
| `GET /certifications/{id}/questions` | No | No | `200` [question bank](#question-bank); unknown ID `404 NOT_FOUND` |
| `GET /certifications/{id}/reassessment-questions` | No | No | `200` [reassessment questions](#reassessment-during-an-existing-plan); unknown ID `404 NOT_FOUND` |
| `OPTIONS` any path | No | No | `204`; CORS headers only for a configured origin |
| `GET /availability`, `PUT /availability` | No | No | Shared profile ([other endpoints](#other-backend-endpoints)); `404 NOT_FOUND` when `APP_SHARED_AVAILABILITY_ENABLED=0` |
| `POST /study-plans` | Yes | Generation | `200` [generated plan](#generate-a-plan), saved for this token |
| `GET /study-plans` | Yes | No | `200 {"plans":[...]}` of this token's plans; with `?schedule_csv_file=` the [exact readback](#select-a-plan) |
| `GET /study-plans/{filename}` | Yes | No | `200` [CSV download](#download-a-plan-csv) |
| `DELETE /study-plans/{filename}` | Yes | No | `204` [plan deletion](#delete-a-plan); missing or foreign plan `404 PLAN_NOT_FOUND` |
| `POST /schedules/adjust` | Yes | Adjustment or preview | `200` result of an [adjustment](#adjust-the-selected-plan) or [reassessment](#reassessment-during-an-existing-plan) |

A protected route checks the token first and may answer `401 INVALID_DEVICE_TOKEN`.
A budgeted POST may then answer `503 SERVICE_UNAVAILABLE` if the proxy address
is missing or `429 RATE_LIMITED` if the applicable quota is full. Every error uses the envelope in
[Error handling and limits](#error-handling-and-limits). Any other path returns
`404 NOT_FOUND`.

### Device token

Every plan route requires one installation credential:
`Authorization: Bearer <token>`, where the token is exactly 64 lowercase
hexadecimal characters (32 random bytes). The scheme name is case-insensitive.
A well-formed token that the server has not seen before is accepted as a new
owner; there is no enrollment route. Protected routes are `POST /study-plans`,
`GET /study-plans` (with or without `schedule_csv_file`),
`GET /study-plans/{filename}`, `DELETE /study-plans/{filename}` and every mode of `POST /schedules/adjust`,
including stateless automatic adjustment of supplied rows. Health,
certifications and questions stay public, and `OPTIONS` preflight needs no token
(configured origins may send `Authorization` and `Content-Type`).

A missing, empty, repeated or malformed credential, or one header with several
credentials, returns `401` before the body or filter is read:

```json
{"error":{"code":"INVALID_DEVICE_TOKEN","message":"A valid device token is required."}}
```

with `WWW-Authenticate: Bearer realm="license-planner"`. Every protected
response, successful or not, carries `Cache-Control: no-store`. Keep the token
out of URLs, request bodies, logs and test evidence; the server stores only a
SHA-256 digest of it as the owner's directory name and never forwards it to the
model provider.

Each token sees only its own plans. The plan identifier and
`schedule_csv_url` keep their existing form and contain no owner information.
A different token's plan behaves exactly like an absent plan: filtered readback
returns `{"plans": []}`. Download, deletion and adjustment return `404 PLAN_NOT_FOUND`.
Plans stored before owner scoping (directly in the plan directory) are not
reachable through the API. A new token therefore starts with an empty list, and
reinstalling the app or clearing its data loses access to earlier server plans.

A direct client or test keeps one token per simulated installation in memory
and sends it as a header only. From a POSIX shell:

```bash
token="$(python -c 'import secrets; print(secrets.token_hex(32))')"
curl -fsS -H "Authorization: Bearer $token" http://127.0.0.1:8000/api/v1/study-plans
```

Reuse the same value for every request about that token's plans, including
after a server restart; a different value is a different owner.

### Request limits and public mode

The two POST routes are admitted within rolling 60-minute windows, counted
separately per route for the token owner, the client IP and the whole server
process:

| POST route | Per token | Per client IP | Whole server |
| --- | ---: | ---: | ---: |
| `/study-plans` | 3 | 12 | 24 |
| `/schedules/adjust` (reassessment previews) | 12 | 40 | 60 |
| `/schedules/adjust` (commits and all other modes) | 12 | 40 | 60 |

The server checks the token and client address before decoding the body. A
generation request reserves quota before decoding. The adjustment route first
decodes the size-limited JSON body to identify requests with `mode: "reassessment"`
and `commit: false`. Those requests use the preview quota. Reassessment commits,
other adjustment modes and malformed bodies use the adjustment quota. Admitted
requests keep their slots if validation, the model or storage later fails. A
request that exceeds a limit reserves no slots and returns before model or plan
access.

```json
{"error":{"code":"RATE_LIMITED","message":"Too many requests. Try again later."}}
```

with status `429`, `Cache-Control: no-store` and `Retry-After: <seconds>`, a
positive integer that covers every full bucket. Wait for an explicit user retry;
do not retry automatically. Token rejections, GET reads and `OPTIONS` use no
slot. Counters live in process memory and reset when the server restarts.

Two backend settings select public behavior (each exactly `0` or `1`):

- `APP_SHARED_AVAILABILITY_ENABLED` (default `1`). With `0`, `GET` and `PUT
  /availability` return `404 NOT_FOUND` before the profile or request body is
  read, with or without a token, and generation and adjustment never fall back
  to the shared profile.
- `APP_TRUST_LOCAL_PROXY` (default `0`). With `0`, the client IP is the socket
  peer and `X-Real-IP`/`X-Forwarded-For` are ignored. With `1`, a request from a
  loopback peer must carry exactly one valid IP literal in `X-Real-IP` (set by the
  local proxy, which must overwrite any client value); `X-Forwarded-For` is never
  used. A loopback POST to either route without one valid `X-Real-IP` returns
  `503 SERVICE_UNAVAILABLE` (`The service cannot identify the client address. Try
  again later.`) before admission. A non-loopback peer always uses its socket
  address.

P10 v2 changes the internal model tool, not the HTTP request: diagnosed plans use
every date's usable minutes (`usable_date_minutes`) as its total, with
backend-computed topic budgets/blocks; the model does not choose the workload. The frontend
continues to send the same assessment and availability fields and render returned
`schedules`. It must not send the internal `daily_minutes` tool argument.

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
results, sent with the installation's [device token](#device-token); the new
plan belongs to that token. Dates use `YYYY/MM/DD/HH/MM` and availability times use `HH:MM`.

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
  With `APP_SHARED_AVAILABILITY_ENABLED=0` the profile is never read: generation
  without request windows returns `422 INVALID_INPUT`, and adjustment uses
  request or retained context only.
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
single shared, unauthenticated profile when `APP_SHARED_AVAILABILITY_ENABLED=1`
(the local default); otherwise both return `404 NOT_FOUND`. Both return
`{"weekly_availability": {...}, "availability_overrides": {...}}` with all seven
weekdays and `start`, `duration_minutes` and derived `end` on every window; a
missing profile returns empty lists and `{}`. PUT accepts that wrapper or a
direct weekday map. When PUT omits `availability_overrides`, stored overrides are
kept; an explicit `{}` removes them. The file is written atomically in duration
form (no `end`) only on a successful PUT; reads never rewrite an older start/end
file, and a rejected PUT leaves the file byte-identical. Whoever saves last
wins; existing plans keep their own snapshots. The Android app sends availability
with generation and uses the selected plan's original context for adjustment.
The shared-profile endpoints are available to direct API clients. Android calls the
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

`GET /api/v1/study-plans` returns the caller's own plans (see
[Device token](#device-token)) as `{"plans": [...]}`, newest modification first.
Each item includes `schedule_csv_file`, `schedule_csv_url`, and `schedules`, and
`availability_snapshot` when the plan was generated with retained context (it is
omitted for older plans and when that plan's metadata cannot be read, so the CSV
stays listable). An owner with no plans gets `{"plans": []}`. Only recognized generated CSVs are listed;
an unreadable or malformed plan produces `500 STORAGE_ERROR`.

To read back one retained plan, add the optional query
`?schedule_csv_file=<URL-encoded identifier>` (encode it once; the `+` in the
identifier must be sent as `%2B`). The server then reads only that file, so a
damaged unrelated plan does not block it. The response keeps the same envelope:
one item for the selected plan, or `{"plans": []}` when that valid identifier no
longer exists or belongs to another token. A selected plan that is unreadable or malformed still returns
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

### Download a plan CSV

`GET` the plan's `schedule_csv_url` with the owning token to download the stored
rows as `text/csv; charset=utf-8` with
`Content-Disposition: attachment; filename="<file name>"` and
`Cache-Control: no-store`. After a successful adjustment the same URL returns the
adjusted rows. A valid file name that is absent or belongs to another token
returns `404 PLAN_NOT_FOUND` (`The selected study plan was not found.`), the same
response as adjustment; a name that is not a server-issued plan file returns
`404 NOT_FOUND`. The Android app does not call this route.

### Delete a plan

Send `DELETE` to the returned `schedule_csv_url` with the owning installation's
`Authorization: Bearer <token>` header and no body. Success is `204` with an
empty body. The owner’s CSV and metadata are removed and the plan no longer
appears in the owner’s list. Exported device calendar events are separate copies.

An absent, already deleted or foreign plan returns `404 PLAN_NOT_FOUND`.
Retrying with the same URL and token after a lost response is safe. Treat `204`
or `404 PLAN_NOT_FOUND` as confirmation the server copy is gone. Malformed file
names return `404 NOT_FOUND`, and missing or invalid tokens return
`401 INVALID_DEVICE_TOKEN`. The route does not charge generation or adjustment
quota. Configured origins may request `DELETE` in preflight.

### Adjust the selected plan

`POST /api/v1/schedules/adjust` retains the existing request fields and accepts
these additional fields:

| Field | Behavior |
| --- | --- |
| `schedule_csv_file` | When present, update this existing plan; when absent, preserve the legacy non-persistent mode |
| `weekly_availability` | Optional request context under the selection rules above; otherwise the plan's retained context, then the shared profile when enabled. An explicit null or a context with no window is invalid |
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
`certification_id`, `preparation_start` and the accepted `availability_snapshot` in duration form:

```json
{"version":2,"exam_date":"2026/12/07/00/00","preparation_start":"2026/11/16/00/00",
 "certification_id":"computer_specialist_level_2",
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
reading or writing the shared availability profile. The live Android 남은 공부
다시 계획하기 review and result flow sends it; the demo flavor computes the batch
on the device. Every field below is
required and must not be null:

| Field | Meaning |
| --- | --- |
| `schedule_csv_file` | Server-issued identity of the selected plan |
| `schedules` | The complete current rows, including external and completed rows; must match the stored plan |
| `weekly_availability`, `availability_overrides` | The plan's original context; must equal the retained context after normalization (window order and `end`/`duration_minutes` forms do not matter) |
| `preparation_start`, `exam_date` | The plan's original bounds; must equal the retained values |
| `replan_from` | `YYYY/MM/DD/HH/MM`; no session is placed before it |
| `remaining_sessions` | Non-empty array of `{"schedule_id", "remaining_minutes", "replacement_schedule_id"}` objects; optionally add `new_start_date` to every item |

`schedule_id`, top-level `new_start_date` and the manual-only
`confirm_external_overlap` must be absent. Without item destinations,
`confirm_outside_availability` must also be absent. Each instruction names a distinct `study-N` source row.
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

Optionally, supply `new_start_date` (`YYYY/MM/DD/HH/MM`) on **every** instruction
and a top-level boolean `confirm_outside_availability` (default false). Partial,
null or malformed destinations and nonboolean confirmation return 422 `INVALID_INPUT`.
For example, the instructions above may instead be:

```json
{
  "remaining_sessions": [
    {"schedule_id": "study-1", "remaining_minutes": 60, "replacement_schedule_id": "study-1", "new_start_date": "2026/11/17/10/00"},
    {"schedule_id": "study-4", "remaining_minutes": 10, "replacement_schedule_id": "study-5", "new_start_date": "2026/11/17/11/00"}
  ],
  "confirm_outside_availability": true
}
```

With destinations, the server validates exactly those starts and never searches
for alternatives. In source order it checks: start before the next Korea-local
minute (`MANUAL_MOVE_IN_PAST`), before preparation (`BEFORE_PREPARATION_START`),
before `replan_from` (`INVALID_INPUT`), end beyond the exam (`EXAM_BOUND_EXCEEDED`),
overlap with a kept study row or an earlier destination (`STUDY_SESSION_OVERLAP`),
retained external overlap (`EXTERNAL_EVENT_OVERLAP`), and outside one effective
window without consent (`OUTSIDE_STUDY_WINDOWS`). Each is HTTP 422. Touching is
allowed, including ending exactly at the exam. Outside-window consent waives
only the window check; external overlaps cannot be waived. No calendar content
or fresh busy intervals are needed or sent. The batch is saved once, atomically,
with the same optimistic conflict checks, identity and success envelope.

Without destinations, behavior is unchanged:

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
| 401 / `INVALID_DEVICE_TOKEN` | The request carried no valid device token; nothing was read, admitted or saved |
| 429 / `RATE_LIMITED` | A request limit is full; no plan was read or saved. Keep inputs and let the user retry after `Retry-After` seconds |
| 503 / `SERVICE_UNAVAILABLE` | A proxied server could not identify the client address; nothing was admitted |
| 422 / `INVALID_INPUT` | Correct invalid fields, duplicate IDs, missing target, or unusable availability; for the fixed no-stored-exam-date message, create a new plan |
| 422 / manual-move codes | Keep the chosen time for correction or explicitly review the independent outside-window and external-overlap exceptions described above |
| 404 / `PLAN_NOT_FOUND` | On deletion, proceed with local removal. On adjustment, refresh the plan list. Direct download also returns this code for a missing or foreign plan |
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
store retains up to 100 generated plans per device token on a best-effort basis,
oldest modification first; one token's generation never prunes another token's
plans, and pruning a CSV also removes its sidecar.

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

Plans are visible only to the token that generated them, so set `$token` to the
disposable token used for generation and reuse it for every request about that
plan. Keep it in a shell variable rather than in files or shared history. A new
token can be made with:

```powershell
$bytes = [byte[]]::new(32)
[System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
$token = -join ($bytes | ForEach-Object { $_.ToString('x2') })
```

```powershell
$auth = @{ Authorization = "Bearer $token" }
$plans = Invoke-RestMethod -Headers $auth http://127.0.0.1:8000/api/v1/study-plans
$selected = $plans.plans | Select-Object -First 1
if ($null -eq $selected) { throw 'Generate a plan before adjusting.' }
$failed = $selected.schedules | Where-Object { $_.schedule_id -like 'study-*' } | Select-Object -First 1
if ($null -eq $failed) { throw 'The selected plan has no study session.' }
$payload = @{
    schedule_csv_file = $selected.schedule_csv_file
    schedule_id = $failed.schedule_id
    schedules = @($selected.schedules)
} | ConvertTo-Json -Depth 10
Invoke-RestMethod -Method Post -Headers $auth `
    -Uri http://127.0.0.1:8000/api/v1/schedules/adjust `
    -ContentType 'application/json; charset=utf-8' `
    -Body ([System.Text.Encoding]::UTF8.GetBytes($payload))
```

For a static payload template, see `scenarios/inputs/api/plan_adjustment_request.json`.
Its sample file does not exist automatically; replace both the identifier and
schedule rows with those from generation or listing. The Android Emulator base
URL remains `http://10.0.2.2:8000/api/v1`.

## Reassessment during an existing plan

All paths below use the existing `/api/v1` prefix.

- `GET /certifications/{id}/reassessment-questions` is public. It returns
  `certification_id`, `certification_name` and `questions`. Each question has
  `id`, `prompt`, `choices`, `correct_answer`, `possible_score`, `topic` and
  `explanation`. The bank selects them with `reassessment_question_ids`; the
  current 18 reviewed authored examples (IDs 19–36) differ from onboarding and
  are not an official or validated assessment. A certification whose bank has no
  selection returns `404 NOT_FOUND`; an invalid selection returns
  `500 INTERNAL_ERROR` while onboarding keeps working.
- `POST /schedules/adjust` requires the existing device Bearer token and
  `mode: "reassessment"`. Required fields match Android `ReassessmentRequest`:
  `commit` (boolean), `schedule_csv_file`, `schedules`, `weekly_availability`,
  `availability_overrides`, `preparation_start`, `exam_date`, `replan_from`,
  `keep_schedule_ids`, `first_new_study_id`, and `assessment_results`. A
  commit also requires `previewed_schedules`, the rows the learner confirmed, and
  the `preview_token` from that preview; a preview must omit both.
  `study_reports` maps current study IDs to `{ "status": "done" | "partly" | "missed",
  "actual_minutes": integer }`. Done/partly need positive minutes; missed needs zero.
  It may be omitted for older callers, meaning no progress has been reported.
  Every reported future row must also appear in `keep_schedule_ids`.
  Submit the whole selected quiz in the existing locally scored result format,
  with `assessment_results.certification_id` matching the selected plan. New
  plans retain their certification ID. Older plans with retained study context
  use the original Level 2 certification. The server checks question IDs, content,
  choices, answer keys, possible scores and correctness against that bank.
  `UNKNOWN: "모르겠음"` is accepted and always incorrect. A preview calls the model.

`commit: false` returns `action: "reassessment_preview"` without changing any
files. After confirmation, resend the same input with `commit: true`; it returns
`action: "reassessed"` and atomically updates only the selected owner's CSV.
Both responses include `schedules`, `schedule_csv`, the same `schedule_csv_file`,
`availability_snapshot`, `agent_summary` and `preview_token`. Preview also returns
the accepted proposal in `tool_calls`; commit returns an empty tool trace.

Preview is model-generated and read-only. The server validates the proposed
pieces and signs their rows plus the current plan, progress and diagnostic context.
Commit verifies the token, unchanged source/context, current bank scores and time;
it never calls the model a second time. A changed row, progress report, context,
expired token or changed bank score returns `422 REASSESSMENT_PREVIEW_EXPIRED`
without writing. Tokens expire after 15 minutes. Set process environment variable
`APP_REASSESSMENT_SIGNING_KEY` to a stable random secret shared by all backend workers
when previews must survive process restarts. Without it, the key is process-local
and a restart requires a new preview. No preview or study-report history is saved.
The existing
optimistic storage update rejects concurrent writes; CSV and metadata changes
yield `409 PLAN_CONFLICT`.

Only study rows starting at or after `replan_from` and absent from
`keep_schedule_ids` can change. Reported IDs come from the device; the backend
does not retain study reports. Past rows, in-progress rows, external rows and
kept rows remain identical. Each revisable row keeps its exact time coverage;
only topic boundaries may split it into adjacent pieces. Unchanged single-topic
rows keep their IDs; changed pieces receive fresh IDs from `first_new_study_id`,
which must exceed every current study ID. FE may reserve a higher number for
retained reports. Preparation dates, exam date and study windows cannot change.

The model receives each topic's current planned minutes, actual study minutes,
completed planned work, unmet planned work, revisable minutes and new score.
Done credits the row's planned work, partly credits up to its scheduled minutes;
unreported past rows are not inferred to be completed. The model chooses pieces
inside each revisable interval and records its explanation in the proposal trace.
The app's `agent_summary` is generated from verified progress/remaining-time data;
free-form model prose is not shown as a claim about mastery or passing the exam.
Every topic below 100% on the sample diagnostic must receive future study;
100%-scoring topics can receive zero. All new pieces must be at least 15 minutes;
an already shorter original interval stays indivisible. Invalid proposals are
returned to the model for correction, at most four requests. Failure never falls
back to the old fixed score formula and never saves a plan. Study reports are
sent transiently to the backend/model for this user-requested review, not retained
in server files. Demo remains an explicitly local simulation, not an agent preview.

Errors use the existing error envelope:

| HTTP | Code | Meaning |
| --- | --- | --- |
| 401 | `INVALID_DEVICE_TOKEN` | Missing/invalid owner credential |
| 404 | `PLAN_NOT_FOUND` | No selected plan owned by this device |
| 409 | `PLAN_CONFLICT` | Client snapshot or stored metadata changed |
| 422 | `REASSESSMENT_PREVIEW_EXPIRED` | A revisable row now starts before the current Seoul minute, or the commit's result differs from `previewed_schedules` |
| 422 | `INVALID_INPUT` | Malformed/incomplete quiz, invalid IDs, missing retained context, or no revisable rows |
| 422 | `INVALID_INPUT` with message starting `NOT_ENOUGH_TIME` | Remaining minutes cannot give every assessed topic one minute |
| 429 | `RATE_LIMITED` | The applicable preview or adjustment quota is full |
| 500 | `INTERNAL_ERROR` | Invalid question-bank configuration; internal details stay server-side |
| 500 | `STORAGE_ERROR` | Plan storage failure |
