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

## Other backend endpoints

The API also exposes availability profile read/write and schedule adjustment
endpoints for other clients. The current Android MVP does not call them; it
sends weekly availability as part of each plan request.
