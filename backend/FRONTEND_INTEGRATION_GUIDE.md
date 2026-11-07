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

`GET /api/v1/certifications/{certification_id}/questions` returns questions in
bank order. Each question contains `id`, `prompt`, `choices`, `correct_answer`,
and `possible_score`; the response also includes `certification_id` and
`certification_name`. The MVP question bank is authored example content, not an
official exam bank or validated diagnostic.

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
    "monday": [{"start": "18:00", "end": "21:00"}]
  },
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
must include a positive integer `problem_id`, question text, string choice map,
correct and user choice keys, boolean `is_correct`, and positive numeric
`possible_score`. The server validates the result structure; the Android app is
responsible for comparing the selected answer with the answer key.

The response includes `schedules`, with `schedule_id`, `start_date`, `end_date`,
and `topic` on each row, plus `schedule_csv` and `agent_summary`. The server may
also return CSV location and diagnostic fields. The app ignores unknown fields
and displays the schedule rows. Date fields use the same
`YYYY/MM/DD/HH/MM` format.

The Android live flavor does not fall back to demo data after an API error. It
keeps the form values and surfaces the error. Common statuses are `422` for
invalid input, `502` for upstream planning errors, `503` for missing server
configuration, and `500` for unexpected server errors. Error bodies use
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
saved. The check runs in two stages. Before any model call the exact topic count
is unknown, so only zero usable minutes is rejected there, even when no server key
is configured. After the model maps the questions to topics, the actual number of
distinct assessed topics is checked before any schedule is built. Passing the
check does not mean the time is enough to prepare. Invalid model allocations,
repeated tool failures and provider errors stay `502`/`503`; they never become
`NOT_ENOUGH_TIME`. On success, `agent_summary` is a short server-written summary
of the accepted schedule. No response fields were added.

## Other backend endpoints

The API also exposes availability profile read/write for other clients. Android
does not call the profile endpoints; it sends availability with generation and
uses the selected plan's original windows for adjustment. Android calls the
adjustment endpoint and explicitly reloads a retained plan through the plan-list
endpoint as described below; it does not import the server's plans into its list.

## Task 2: select, adjust, and update a saved plan

Later deployed OpenRouter and physical-device results are linked in the
[verification index](../docs/README.md#verification-status). The 2026-10-30 to 31
device report includes successful adjustment and explicit reload after a lost
response, but records an unresolved intermittent HTTP 307. It does not attach
the full two-plan same-file/CSV/original-window isolation matrix. These recorded
results are distinct from the historical backend-only check below.

Historical backend verification (2026-10-17): Fifteen isolated HTTP/storage tests
passed using a replacement agent that executes the real domain tools. Fifteen
existing adjustment tests passed; the planner suite passed 12/13 with an existing
weekday-normalization expectation mismatch. Live Gemini calls were not run
because no key was configured. Android tests could not run because the SDK
location was unavailable. That backend task did not change the Android UI.
Later frontend adjustment and recovery implementation is described here and in `../frontend/README.md`; the
old suite/SDK observations above are dated evidence, not current blockers.

### Select a plan

`GET /api/v1/study-plans` returns `{"plans": [...]}`, newest modification first.
Each item includes `schedule_csv_file`, `schedule_csv_url`, and `schedules`.
An empty store returns `{"plans": []}`. Only recognized generated CSVs are listed;
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
three additional fields:

| Field | Behavior |
| --- | --- |
| `schedule_csv_file` | When present, update this existing plan; when absent, preserve the legacy non-persistent mode |
| `weekly_availability` | Optional request override; otherwise read the saved profile. An explicit null or empty profile is invalid |
| `exam_date` | Saved plan (`schedule_csv_file` present): optional. The server uses the exam timestamp stored when the plan was generated; a supplied value must equal it exactly, and explicit null or a malformed value is rejected. Without `schedule_csv_file`: required when moving a `study-N` row. Never needed for `external-N` removal. |

Always send the complete schedule currently displayed, not only the failed row.
The rows must match the stored plan (array order does not matter). Existing IDs,
topics and session durations are preserved for study-session movement. An
`external-N` target retains the existing external-event removal behavior.

#### Exam deadline of a saved plan

Each plan generated by `POST /study-plans` stores its validated `exam_date`
beside the CSV, in a sidecar named after the CSV with `.meta.json` instead of
`.csv`. The CSV identity, download format and response fields do not change, and
there is no metadata route. Moving a study session of a saved plan always uses
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
- Present but damaged or unreadable metadata returns `500 STORAGE_ERROR`; the
  request date is never used as a substitute.

Stale rows (409) and a missing plan (404) are reported before any deadline
check. Without `schedule_csv_file`, the non-persistent mode bounds the move by
the supplied `exam_date` and saves nothing; no stored deadline is involved.

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
identifier, download URL and stored exam timestamp remain unchanged. Replace FE's entire displayed
schedule with the response rows. Non-persistent responses keep their previous
shape and omit the two file fields.

### Error handling and limits

| Status / code | FE handling |
| --- | --- |
| 422 / `INVALID_INPUT` | Correct invalid fields, duplicate IDs, missing target, or unusable availability; for the fixed no-stored-exam-date message, create a new plan |
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
If neither an override nor a saved profile exists, adjustment uses the legacy
conflict-only rule. The current Android app retains the original creation
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
