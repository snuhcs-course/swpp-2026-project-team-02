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
- `GET /api/v1/availability`
- `PUT /api/v1/availability`
- `POST /api/v1/study-plans`
- `POST /api/v1/schedules/adjust`

The POST endpoints accept JSON and return both `schedules` (an array for UI
rendering) and `schedule_csv` (the CSV representation). Dates use
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

This workspace contains two independent Python programs:

- `license_planner/` creates certification study sessions using a self-assessment, per-question exam results, date/time limits, and busy events.
- `schedule_adjuster/` moves a failed study session or removes a cancelled external event.

Both programs accept and emit timestamps in `YYYY/MM/DD/HH/MM` format. Schedule CSV files use `schedule_id,start_date,end_date,topic`; legacy three-column schedule inputs remain readable.

## License Planner

The License Planner MVP currently configures Computer Specialist Level 2, while topic names and question statistics are read from data rather than embedded in the planning algorithm. See [DEVELOPER_GUIDE.md](DEVELOPER_GUIDE.md) for the input validation, tool flow, schedule rules, and handoff details.

Problem results use four columns:

```csv
problem_id,topic,possible_score,earned_score
1,Functions,10,6
2,Charts,5,2
```

The program aggregates earned/possible points by the supplied topic, ranks weaker topics first, and gives those topics more study sessions. Unknown topic names are accepted. If problem results are missing, the selected certification's configured topics are used as a fallback.

Study sessions are one hour by default, placed in the configurable 18:00–22:00 study window, with one session per day. A user's recurring weekly availability can override that default. Provide it as JSON with weekday keys and `start`/`end` windows; License Planner saves it to `config/user_availability.json` and uses it while planning. The generated `topic` column records each study session's assigned topic; external events have a blank topic unless supplied. Edit `config/license_planner.json` to change the default study window, session length/count, topic effort, and level multipliers. Busy event ranges are skipped; intervals include their start and exclude their end.

Example availability file:

```json
{
  "monday": [{"start": "18:00", "end": "21:00"}],
  "wednesday": [{"start": "09:00", "end": "12:00"}],
  "saturday": [{"start": "10:00", "end": "14:00"}]
}
```

Pass it with `--availability-file availability.json`. The saved profile is reused for later runs when no new availability is supplied.

Example local tests and Gemini tool-trajectory cases:

```powershell
python -m unittest discover -s license_planner_tests -v
python run_license_planner_availability_test_cases.py
python run_license_planner_test_cases.py --case-id license_planner_case_04_personal_availability_api
```

The first two commands do not call Gemini. The availability runner checks JSON persistence, schedule placement, conflicts, default fallback, and invalid input; it saves per-case CSVs and traces under `license_planner_availability_test_results/`. The last command makes real Gemini API requests for just the personal-availability integration case; it requires `GEMINI_API_KEY` and network access, then records successful response counts, tool calls, and a result CSV under `license_planner_test_results/`. Omit `--case-id` to run all Gemini scenarios. Add offline availability cases under `license_planner_availability_test_inputs/`.

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



