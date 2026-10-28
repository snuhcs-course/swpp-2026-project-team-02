# Backend application

This directory contains the Python API used by the Android app's `live` flavor,
license planner, schedule adjuster, configuration, and developer documentation.
Run backend commands from this directory. The repository overview is in
`../README.md`.

Offline unit suites live in `tests/` (`tests/api_tests/`,
`tests/license_planner_tests/`, `tests/schedule_adjuster_tests/`); run each with
`python -m unittest discover -s tests/<suite> -v`. Scenario runners live in
`scenarios/`, with fixtures in `scenarios/inputs/<module>/` and generated,
git-ignored output in `scenarios/results/<module>/`. Run them as modules, for
example `python -m scenarios.run_certification_selector_test_cases`; running
`python scenarios/<runner>.py` directly cannot import the backend packages.
# Study Planning Programs

## Local API and Android frontend connection

The project includes a dependency-free local HTTP API used by the Android
frontend's `live` flavor. The Android Emulator reaches the default backend at
`http://10.0.2.2:8000/api/v1`; configure a different public API URL with the
Gradle property `licensePlanner.apiBaseUrl`. Backend bind address, port, browser
origins, and request-size limit are in `config/api_server.json` and can be
overridden with `APP_API_HOST` and `APP_API_PORT`. Generated plan CSVs are
stored in `license_planner/output/`; set `APP_STUDY_PLAN_DIR` to use another
directory, for example a disposable one for tests. The OpenRouter key
(`OPENROUTER_API_KEY`) stays in the backend process environment or the backend
root `.env`; never copy it into frontend configuration.

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
- `GET /api/v1/study-plans`
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
requires another bind address. Browser-based clients need an allowed origin;
the Android app does not use browser CORS. For the app's request/response
contract and emulator setup, see `FRONTEND_INTEGRATION_GUIDE.md`.

Run the automated HTTP integration case for CORS, availability persistence,
input validation, and profile-aware schedule adjustment:

```powershell
python -m scenarios.run_api_availability_integration_test
```

It starts an ephemeral local HTTP server and uses a temporary profile file, so it
does not overwrite `config/user_availability.json`. Schedule adjustment calls
OpenRouter model `openai/gpt-6-luna` and requires `OPENROUTER_API_KEY`, network
access, and available credit.
Results are saved under `scenarios/results/api/`.

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

The API request combines these results with the free-form self-assessment, preparation start, exam date, and `weekly_availability`. The weekly availability object uses weekday keys and `start`/`end` time windows. Existing busy schedules, if supplied, are JSON objects. The CLI accepts plan fields through `--request-json` and assessment results through `--assessment-results-json`. The agent classifies each question across one or more catalog topics, proportionally attributes its possible/earned points, and ranks weaker topics first. The planning agent chooses date-specific study minutes per topic from the free-form self-assessment, weighted topic scores, preparation period, and actual availability. It does not classify the user into fixed readiness levels or multiply study time by a level factor. The scheduler validates and places that workload within available windows and outside busy periods, without a fixed session length or daily session-count cap.

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

Example local tests and live model tool-trajectory cases:

```powershell
python -m unittest discover -s tests/license_planner_tests -v
python -m scenarios.run_license_planner_availability_test_cases
python -m scenarios.run_license_planner_test_cases --case-id license_planner_case_04_personal_availability_api
```

The first two commands do not call a model. The availability runner checks JSON persistence, schedule placement, conflicts, missing-time errors, and invalid input; it saves per-case CSVs and traces under `scenarios/results/license_planner_availability/`. The last command starts a temporary local HTTP server and sends the JSON fixture through `POST /api/v1/study-plans`; it makes real OpenRouter `openai/gpt-6-luna` requests, requires `OPENROUTER_API_KEY`, network access, and available credit, and records the HTTP result, tool calls, generated schedule, and an `inference` record (selected model, model request count, elapsed seconds, and per-response token/cost usage, `null` when the provider does not return it) under `scenarios/results/license_planner/`. Omit `--case-id` to run all live scenarios. Add offline availability cases under `scenarios/inputs/license_planner_availability/`.

## Schedule Adjuster

The Schedule Adjuster requires a target ID (`study-N` or `external-N`) and an input schedule CSV. It moves study intervals after the old end while preserving interval length, topic, and other rows. When `config/user_availability.json` exists, the new study interval must also fit the user's recurring weekly availability. Cancelled external rows are removed while other rows remain unchanged.

```powershell
python -m schedule_adjuster.cli --schedule-id study-201 `
  --schedule-csv scenarios/inputs/schedule_adjuster/schedule_adjuster_case_01_study_move.csv `
  1> adjusted_schedule.csv 2> adjustment_trace.txt
```

Example tests:

```powershell
python -m unittest discover -s tests/schedule_adjuster_tests -v
python -m scenarios.run_schedule_adjuster_test_cases
```

See `schedule_adjuster/README.md` for behavior assumptions and the integration test format.

## Checks and coverage

CI ([`../.github/workflows/ci.yml`](../.github/workflows/ci.yml)) runs the
three offline unit suites in `tests/` under coverage with Python 3.14. Each suite
runs even if an earlier one fails, and any failure fails the job. Scenario
runners in `scenarios/` are not run in CI:

```bash
python -m pip install -r requirements-ci.txt   # CI-only: pinned coverage.py
python -c "import sys; import api_service; print(sys.executable)"
python -m coverage erase
python -m coverage run -m unittest discover -s tests/license_planner_tests -v
python -m coverage run --append -m unittest discover -s tests/schedule_adjuster_tests -v
python -m coverage run --append -m unittest discover -s tests/api_tests -v
python -m coverage report -m
python -m coverage xml
python -m coverage html
```

The application has no third-party dependencies; to reproduce this locally,
install `requirements-ci.txt` into a separate virtual environment rather than the
`swpp` runtime environment. `.coveragerc` measures branch coverage over the
production modules listed in its `include` setting (the shared `openrouter_client.py` is not listed yet), leaves
`tests/` and `scenarios/` out of the report, and writes `coverage-results/coverage.xml` and `coverage-results/html/`
(generated; do not commit them). CI uploads both as the `backend-coverage`
artifact, kept 7 days. No minimum percentage is enforced; agree on any threshold
from measured baselines.

These tests require no OpenRouter key or external network access. The API tests serve the request
handler on temporary loopback ports with scripted OpenRouter Responses replies, so passing them
does not verify live model planning or a running `python api_server.py`.

## Model configuration

Study-plan generation and schedule adjustment both call OpenRouter model `openai/gpt-6-luna` through the Responses API (`openrouter_client.py`, Python standard library only). Set `OPENROUTER_API_KEY` in the backend process environment, which takes precedence, or in `backend/.env` (the adjuster checks `schedule_adjuster/.env` first). `GEMINI_API_KEY` is no longer used and does not authorize requests; there is no automatic fallback to another model or to demo data. The key is read only when a model request is about to be sent, is sent only in the request's `Authorization` header, and is not written to generated traces. Without it, otherwise valid `POST /study-plans` and `POST /schedules/adjust` requests return HTTP 503 `UPSTREAM_ERROR`; provider authentication, credit, rate-limit, network, and malformed-reply failures return HTTP 502 `UPSTREAM_ERROR` and are not retried. The model ID, endpoint, and 45-second timeout are in `openrouter_client.py`; reasoning effort, output-token limits, and request limits are in each program's agent/settings module. The operator's OpenRouter spending limit is enforced by OpenRouter, not by this backend.



