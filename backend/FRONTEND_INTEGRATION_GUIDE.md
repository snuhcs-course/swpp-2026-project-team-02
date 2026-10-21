# Android frontend API integration

This guide documents the HTTP contract used by `frontend/`'s Android `live`
flavor. Run the API from this repository's `backend/` directory. The API key is
server-side only and must remain in `backend/.env`.

## Run locally

Start the backend with the project Python environment and `GEMINI_API_KEY`
configured:

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
`The planning service is not configured on the server.` when the Gemini key is
missing. They never include provider responses, tool names or arguments, agent
traces, or server paths, so clients must not parse them for a cause. `422`
`INVALID_INPUT` messages, including `NOT_ENOUGH_TIME`, and the `404`/`409`/`500`
plan-storage messages keep their specific text.

When the available time provably cannot give every required topic at least one
minute, plan generation returns `422` / `INVALID_INPUT` with an `error.message`
that starts with `NOT_ENOUGH_TIME`. After that code, the Korean message states the usable minutes and the
topic basis, and advises adding study windows, starting preparation earlier, or
choosing a later exam date. No schedule fields are returned and no plan file is
saved. The check runs in two stages. Before any Gemini call the exact topic count
is unknown, so only zero usable minutes is rejected there, even when no server key
is configured. After Gemini maps the questions to topics, the actual number of
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
two additional fields:

| Field | Behavior |
| --- | --- |
| `schedule_csv_file` | When present, update this existing plan; when absent, preserve the legacy non-persistent mode |
| `weekly_availability` | Optional request override; otherwise read the saved profile. An explicit null or empty profile is invalid |

Always send the complete schedule currently displayed, not only the failed row.
The rows must match the stored plan (array order does not matter). Existing IDs,
topics and session durations are preserved for study-session movement. An
`external-N` target retains the existing external-event removal behavior.

Example request (replace the example identifier and rows with actual values):

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
identifier and download URL remain unchanged. Replace FE's entire displayed
schedule with the response rows. Non-persistent responses keep their previous
shape and omit the two file fields.

### Error handling and limits

| Status / code | FE handling |
| --- | --- |
| 422 / `INVALID_INPUT` | Correct invalid fields, duplicate IDs, missing target, or unusable availability |
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
Android/Gemini same-file A/B acceptance remains pending.

If a call times out, reload before retrying: the write may have completed.
There is no idempotency key. File reads and replacements share a process lock;
the original file digest is checked again after the agent runs. Conflicting
requests cannot silently overwrite one another within one server process.
Cross-process locking is not provided.

Availability in a generation or adjustment request is not saved automatically.
If neither an override nor a saved profile exists, adjustment uses the legacy
conflict-only rule. CSV files do not retain exam dates or plan-specific
availability, and this task does not add partial-completion or exam-deadline
constraints. The store retains up to 100 generated plans on a best-effort basis.

### Local execution

With `GEMINI_API_KEY` configured on the backend, start from the repository root:

```powershell
Set-Location backend
python api_server.py
```

In another terminal from `backend/`, list plans and prepare a request using
actual data. These are manual execution instructions, not recorded test results.

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

For a static payload template, see `api_test_inputs/plan_adjustment_request.json`.
Its sample file does not exist automatically; replace both the identifier and
schedule rows with those from generation or listing. The Android Emulator base
URL remains `http://10.0.2.2:8000/api/v1`.
