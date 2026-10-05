# Backend application

This directory contains the existing Python API, license planner, schedule adjuster, configuration, tests, and developer documentation. Run backend commands from this directory. The repository overview is in `../README.md`.
# Study Planning Programs

## Local web API and frontend connection

The project now includes a dependency-free local HTTP API. The frontend reads
`frontend_config.json` for `api_base_url`; change that value when connecting to
a future hosted backend. The backend address, port, allowed frontend origins,
and request-size limit are in `config/api_server.json` and can be overridden
with `APP_API_HOST` and `APP_API_PORT`. Gemini credentials stay in the backend
root `.env`; never copy them into frontend configuration.

Start the API from the backend directory:

```powershell
python api_server.py
```

Available routes:

- `GET /api/v1/health`
- `GET /api/v1/certifications`
- `GET /api/v1/certifications/{certification_id}/questions`
- `GET /api/v1/availability`
- `PUT /api/v1/availability`
- `POST /api/v1/study-plans`
- `GET /api/v1/study-plans/{filename}`
- `POST /api/v1/schedules/adjust`

The POST endpoints accept JSON and return both `schedules` (an array for UI
rendering) and `schedule_csv` (the CSV representation). `POST /study-plans`
also saves each generated CSV under `license_planner/output/` and returns its
backend-relative path in `schedule_csv_file`; files are uniquely named and can
be reused as inputs to the existing CSV-based tools. Dates use
`YYYY/MM/DD/HH/MM`; recurring availability uses `HH:MM`. See
`DEVELOPER_GUIDE.md` for request examples and error handling. The local server
defaults to loopback (`127.0.0.1`); set `host` deliberately when deployment
requires another bind address, and update allowed origins for the frontend.
For frontend handoff, request/response examples, browser calls, and UI error
handling, see `FRONTEND_INTEGRATION_GUIDE.md`.

Run the automated HTTP integration case for CORS, availability persistence,
input validation, and profile-aware schedule adjustment:

```powershell
python run_api_availability_integration_test.py
```

It starts an ephemeral local HTTP server and uses a temporary profile file, so it
does not overwrite `config/user_availability.json`. Schedule adjustment calls
Gemini and requires `GEMINI_API_KEY`, network access, and available quota.
Results are saved under `api_test_results/`.

The questionnaire writes a JSON assessment result containing the question,
choices, correct answer, user answer, local `is_correct` result, and possible
score. Planning requests submit this document as `assessment_results`; the API
does not grade it. Instead, the planning agent maps each question to one or more
fixed certification topics with contribution weights summing to 1, then
aggregates topic-level strengths and weaknesses for the schedule.

This workspace contains two independent Python programs:

- `license_planner/` creates certification study sessions using a self-assessment, per-question exam results, date/time limits, and busy events.
- `schedule_adjuster/` moves a failed study session or removes a cancelled external event.

Both programs accept and emit timestamps in `YYYY/MM/DD/HH/MM` format. Planning API requests use locally-scored assessment JSON. The planner CLI can read that JSON directly for local runs. Generated study schedules are saved and can be downloaded through the API. Schedule Adjuster continues to accept a schedule CSV.

## License Planner

The License Planner MVP currently configures Computer Specialist Level 2, while topic names and question statistics are read from data rather than embedded in the planning algorithm. See [DEVELOPER_GUIDE.md](DEVELOPER_GUIDE.md) for the input validation, tool flow, schedule rules, and handoff details.

The terminal questionnaire produces
`assessment_questionnaire/output/assessment_results.json`. The same result
document can be passed to the HTTP Planning API or local planner CLI:

```json
{"assessment_results": {"certification_id": "computer_specialist_level_2", "results": [
  {"problem_id": 1, "question": "...", "choices": {"A": "...", "B": "..."}, "correct_answer": "B", "user_answer": "B", "is_correct": true, "possible_score": 1}
]}}
```

The API request combines these results with the free-form self-assessment, preparation start, exam date, and `weekly_availability`. The weekly availability object uses weekday keys and `start`/`end` time windows. Existing busy schedules, if supplied, are JSON objects. The CLI accepts plan fields through `--request-json` and assessment results through `--assessment-results-json`. The agent classifies each question across one or more catalog topics, proportionally attributes its possible/earned points, and ranks weaker topics first. The Gemini planning agent chooses date-specific study minutes per topic from the free-form self-assessment, weighted topic scores, preparation period, and actual availability. It does not classify the user into fixed readiness levels or multiply study time by a level factor. The scheduler validates and places that workload within available windows and outside busy periods, without a fixed session length or daily session-count cap.

Study sessions are placed only inside the user's weekly availability. Supply at least one weekday and `start`/`end` window in `weekly_availability`, or save a profile through `PUT /api/v1/availability`; the backend stores it in `config/user_availability.json`. If neither a request nor saved profile contains an available window, Planning API returns HTTP 422 with an input error instead of using common default hours. The generated `topic` column records each study block's assigned topic; external events have a blank topic unless supplied. The agent is instructed to balance date-specific workload across equivalent dates (for example, 120 minutes across two equivalent dates becomes about 60 minutes per date), while the backend enforces only date-level available capacity and does not guarantee this balance. Busy event ranges are excluded; intervals include their start and exclude their end. There is no fixed session length or maximum number of study blocks per day. After saving a plan, the API attempts to retain the 100 most recent generated plan CSVs by removing older matching files; locked files can prevent cleanup.

Example weekly availability JSON:

```json
{
  "monday": [{"start": "18:00", "end": "21:00"}],
  "wednesday": [{"start": "09:00", "end": "12:00"}],
  "saturday": [{"start": "10:00", "end": "14:00"}]
}
```

Send it in the `weekly_availability` field of the planning request, or save it through `PUT /api/v1/availability`. The saved profile is reused when a later planning request omits `weekly_availability`.

Example local tests and Gemini tool-trajectory cases:

```powershell
python -m unittest discover -s license_planner_tests -v
python run_license_planner_availability_test_cases.py
python run_license_planner_test_cases.py --case-id license_planner_case_04_personal_availability_api
```

The first two commands do not call Gemini. The availability runner checks JSON persistence, schedule placement, conflicts, missing-time errors, and invalid input; it saves per-case CSVs and traces under `license_planner_availability_test_results/`. The last command starts a temporary local HTTP server and sends the JSON fixture through `POST /api/v1/study-plans`; it makes a real Gemini request, requires `GEMINI_API_KEY` and network access, and records the HTTP result, tool calls, and generated schedule under `license_planner_test_results/`. Omit `--case-id` to run all Gemini scenarios. Add offline availability cases under `license_planner_availability_test_inputs/`.

## Schedule Adjuster

The Schedule Adjuster requires a target ID (`study-N` or `external-N`) and an input schedule CSV. It moves study intervals after the old end while preserving interval length, topic, and other rows. When `config/user_availability.json` exists, the new study interval must also fit the user's recurring weekly availability. Cancelled external rows are removed while other rows remain unchanged.

```powershell
python -m schedule_adjuster.cli --schedule-id study-201 `
  --schedule-csv schedule_adjuster_test_inputs/schedule_adjuster_case_01_study_move.csv `
  1> adjusted_schedule.csv 2> adjustment_trace.txt
```

Example tests:

```powershell
python -m unittest discover -s schedule_adjuster_tests -v
python run_schedule_adjuster_test_cases.py
```

See `schedule_adjuster/README.md` for behavior assumptions and the integration test format.

## Gemini configuration

Put `GEMINI_API_KEY` in `backend/.env` file or the appropriate process environment. The key is read by the Gemini Agent and is not written to generated traces. Model and tool runtime settings are configured in each program's settings/agent module.



