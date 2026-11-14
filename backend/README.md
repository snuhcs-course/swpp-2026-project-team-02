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
stored in `license_planner/output/`, each with a `.meta.json` sidecar holding
the plan's original `exam_date` (new plans also keep `preparation_start` and the
accepted availability context); set `APP_STUDY_PLAN_DIR` to use another
directory, for example a disposable one for tests. Keep each `.csv`/`.meta.json`
pair together: study adjustment of a saved plan uses only the stored deadline,
and a plan without one must be recreated before its study sessions can move. The OpenRouter key
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
does not grade it, but it rejects (422) a result whose `is_correct` differs from
comparing `user_answer` with `correct_answer` (`UNKNOWN` is always incorrect),
malformed content, or a missing `possible_score`. Each submitted question is
matched on its own: one with the same ID, question text, normal choices, correct
answer and possible score as a complete `reviewed` record in
`assessment_questionnaire/questions.json` (an added `UNKNOWN` choice is allowed,
and the record need not be in the onboarding selection) counts once toward that
record's `topic`. The planning agent maps only the remaining (unresolved)
questions to one or more fixed certification topics with contribution weights
summing to 1, and the server combines both into topic-level strengths and
weaknesses for the schedule. A missing or invalid configured bank, mapping,
selection or catalog entry returns 500 `INTERNAL_ERROR` before any model call
instead of sending every question to the model; see
[DEVELOPER_GUIDE.md](DEVELOPER_GUIDE.md#문항-은행-유지보수).

This workspace contains two independent Python programs:

- `license_planner/` creates certification study sessions using a self-assessment, per-question exam results, date/time limits, and busy events.
- `schedule_adjuster/` moves a failed study session or removes a cancelled external event.

Both programs accept and emit timestamps in `YYYY/MM/DD/HH/MM` format. Planning API requests use locally-scored assessment JSON. The planner CLI can read that JSON directly for local runs. Generated study schedules are saved and can be downloaded through the API. Schedule Adjuster continues to accept a schedule CSV.

## License Planner

The License Planner MVP currently configures Computer Specialist Level 2, while topic names and question statistics are read from data rather than embedded in the planning algorithm. See [DEVELOPER_GUIDE.md](DEVELOPER_GUIDE.md) for the input validation, tool flow, schedule rules, and handoff details.

The terminal questionnaire (`python -m assessment_questionnaire.cli` or
`python assessment_questionnaire/cli.py` from `backend/`; see
[its README](assessment_questionnaire/README.md)) produces
`assessment_questionnaire/output/assessment_results.json`. Malformed question
documents make it exit with code 2 before asking anything. The same result
document can be passed to the HTTP Planning API or local planner CLI:

```json
{"assessment_results": {"certification_id": "computer_specialist_level_2", "results": [
  {"problem_id": 1, "question": "...", "choices": {"A": "...", "B": "..."}, "correct_answer": "B", "user_answer": "B", "is_correct": true, "possible_score": 1}
]}}
```

The API request combines these results with the free-form self-assessment, preparation start, exam date, and `weekly_availability`. The weekly availability object uses weekday keys and `start` + `duration_minutes` windows (1–1439 minutes, possibly ending the next day; same-day `start`/`end` windows are still accepted); optional `availability_overrides` replace whole weeks keyed by their Monday date. Existing busy schedules, if supplied, are JSON objects. The CLI accepts plan fields through `--request-json` and assessment results through `--assessment-results-json`. Each question that matches a complete reviewed bank record uses that record's topic; the agent classifies only the remaining questions across one or more catalog topics and proportionally attributes their possible/earned points. Before any model call, the minimum-capacity check uses the exact topic count when every question is resolved, the resolved topic count as a lower bound for a mixed request, and one topic when nothing is resolved; after analysis it uses the union of resolved and classified topics. The planning topic catalog (`config/certifications.json`) is loaded once per process, so restart the server after editing its topics. Weaker topics are ranked first. The planning agent proposes the total study workload and date-specific totals from the free-form self-assessment, diagnostic scores, preparation period, and actual availability. P10 validates that proposal, reserves one minute per assessed topic, and divides the remaining workload using weight `2 - score_percent/100` with largest-remainder rounding (ties: weaker score, then topic name). It redistributes topic labels while preserving every date's total, then validates the actual placed topic totals and weak-topic order. Equal scores may differ by one minute; limited budgets can tie different scores. Inputs without diagnostic statistics retain their original proposal. It does not classify the user into fixed readiness levels or multiply study time by a level factor. The scheduler validates and places that workload within available windows and outside busy periods, without a fixed session length or daily session-count cap.

Study sessions are placed only inside the user's weekly availability. Supply at least one window in `weekly_availability` or `availability_overrides`, or save a profile through `PUT /api/v1/availability`; the backend stores it in `config/user_availability.json`. If neither a request nor saved profile contains an available window, Planning API returns HTTP 422 with an input error instead of using common default hours. The generated `topic` column records each study block's assigned topic; external events have a blank topic unless supplied. The agent is instructed to balance date-specific workload across equivalent dates (for example, 120 minutes across two equivalent dates becomes about 60 minutes per date), while the backend enforces only date-level available capacity and does not guarantee this balance. Busy event ranges are excluded; intervals include their start and exclude their end. There is no fixed session length or maximum number of study blocks per day. After saving a plan, the API attempts to retain the 100 most recent generated plan CSVs by removing older matching files; locked files can prevent cleanup.

Example weekly availability JSON:

```json
{
  "monday": [{"start": "23:00", "duration_minutes": 90}],
  "wednesday": [{"start": "09:00", "duration_minutes": 180}],
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

The first two commands do not call a model. The availability runner checks JSON persistence, schedule placement, conflicts, missing-time errors, and invalid input; it saves per-case CSVs and traces under `scenarios/results/license_planner_availability/`. The last command starts a temporary local HTTP server and sends the JSON fixture through `POST /api/v1/study-plans`; it makes real OpenRouter `openai/gpt-6-luna` requests, requires `OPENROUTER_API_KEY`, network access, and available credit, and records the HTTP result, tool calls, generated schedule, and an `inference` record (the `selected_model` the case's agent actually requested, or `null` when the case made no model request; model request count; elapsed seconds; and per-response token/cost usage with the provider-returned model, `null` when the provider does not return it) under `scenarios/results/license_planner/`. Omit `--case-id` to run all live scenarios. Add offline availability cases under `scenarios/inputs/license_planner_availability/`.

## Schedule Adjuster

The Schedule Adjuster requires a target ID (`study-N` or `external-N`) and an input schedule CSV. It moves study intervals after the old end while preserving interval length, topic, and other rows. When `config/user_availability.json` exists, the new study interval must also fit inside one of the user's effective study windows (recurring week or week override, including windows that cross midnight). Over HTTP, a saved plan uses its retained availability context first, and `mode: "manual"` moves a session to a chosen time without a model call; see `FRONTEND_INTEGRATION_GUIDE.md`. Cancelled external rows are removed while other rows remain unchanged.

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

For focused checks while editing, see the
[local testing workflow](../docs/testing-workflow.md#focused-jvm-and-backend-recipes).
It includes a single-file offline unittest recipe and separates development logs
from cited handoff evidence. Keep the change's full required backend and contract
gates; commands sharing the coverage database below must run serially.

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
python -m unittest discover -s ../scripts/tests -v
python ../scripts/verify_test_evidence.py --repo-root .. --scope backend
```

The application has no third-party dependencies; to reproduce this locally,
install `requirements-ci.txt` into a separate virtual environment rather than the
`swpp` runtime environment. `.coveragerc` measures branch coverage over the
production modules listed in its `include` setting (the shared `openrouter_client.py` and `study_plan_store.py` are not listed yet), leaves
`tests/` and `scenarios/` out of the report, and writes `coverage-results/coverage.xml` and `coverage-results/html/`
(generated; do not commit them). CI uploads both as the `backend-coverage`
artifact, kept 7 days. No minimum percentage is enforced; agree on any threshold
from measured baselines.

`../scripts/verify_test_evidence.py` checks reports that already exist; it runs no
tests and repeats no scenario assertions. With `--scope backend` it fails when
`coverage-results/coverage.xml` or `coverage-results/html/index.html` is missing,
when the XML has no positive line denominator, or when the top-level
`api_server.py` is absent from it. `--scope android` checks the Android JUnit and
JaCoCo reports (see `../frontend/README.md#checks`), and the default `--scope all`
checks both. It prints a JSON summary. It does not record which revision,
commands or CI run produced the reports; whoever cites a result records those.

These tests require no OpenRouter key or external network access. The API tests serve the request
handler on temporary loopback ports with scripted OpenRouter Responses replies, so passing them
does not verify live model planning or a running `python api_server.py`.

CI does not cover the scenario runners in `scenarios/` (including the live
OpenRouter runners), OpenRouter credentials, credit, latency or model quality,
the CPU server's systemd/Nginx setup or public reachability, physical devices or
calendar providers, or the signed release APK (built by
`../.github/workflows/prod-apk.yml`). Record those checks separately and do not
describe them as CI coverage.

### Fixed-response API fixture

`test_support/fixed_live_e2e_server.py` serves the production HTTP handler for
the Android live-flavor instrumentation test. Validation, the planner and
adjustment agent loops, their tools, stored exam deadlines and
`StudyPlanStore` are real; only the model replies are fixed. Planning replays
one trace per server, chosen with `--case-id`. The default,
`selected_onboarding_reviewed_topics` in
`tests/api_tests/fixtures/scripted_selected_assessment_tool_calls.json`, is a
synthetic trace written for this fixture, not a captured provider response: it
submits the complete 18-question onboarding selection, analyzes it without
`problem_topics` and builds sessions for all eight topics. The same file holds
two more synthetic cases: `original_five_reviewed_topics` (the original five
questions, which match reviewed records and need no classification) and
`mixed_reviewed_and_custom_questions` (two reviewed questions plus one custom
question, the only one the trace classifies). The captured five-question traces
stay unchanged in `tests/api_tests/fixtures/recorded_model_tool_calls.json`.
They classify every question, which now matches the HTTP contract only for
custom questions, so the backend tests replay them with each prompt marked as a
custom question (`test_support/question_banks.py`) and pin
`topic_errors_then_build_on_last_request` for the classification and deadline
checks. Adjustment answers with inspect, topic
inspection and the matching mutation tool. Plans (`plans/`) and the availability
profile (`user_availability.json`) live in a temporary directory removed on
exit: `GET`/`PUT /availability` never read or write `config/user_availability.json`. Reading an OpenRouter key or model setting, or sending a
provider request, raises an error. A client that sends a captured trace's
original request unchanged gets reviewed topics, which the trace does not
model; send custom questions with those case IDs. The legacy recorded request's own exam date
(`2026/11/02/18/00`) leaves no free window after the plan, so its study
sessions cannot move; generating the same request with a later exam date gives
a plan that can be adjusted.

A synthetic case is an object keyed by its case ID with `provenance` (stating
that it is synthetic), the `request` it was written for and the
`model_tool_calls` to replay. Add new synthetic cases to
`scripted_selected_assessment_tool_calls.json`, never to the captured file, and
use an ID that neither file already has. Send each case's own `request`: the
two reviewed cases expect no `problem_topics`, and the mixed case maps only its
custom question.

```bash
# from backend/
python test_support/fixed_live_e2e_server.py --host 127.0.0.1 --port 8000
python test_support/fixed_live_e2e_server.py --case-id original_five_reviewed_topics
python test_support/fixed_live_e2e_server.py --case-id mixed_reviewed_and_custom_questions
python test_support/fixed_live_e2e_server.py --case-id topic_errors_then_build_on_last_request
python -m unittest discover -s tests/api_tests -p test_fixed_live_e2e_server.py -v
```

Options for controlled-live device checks (all off by default):

| Option | Effect |
| --- | --- |
| `--planning scripted` | The following describes its model proposal; P10 can rebalance the returned topic labels and block count. Instead of replaying `--case-id`, the fixed model asks for `analyze_exam_results {}` and then builds one 60-minute session per date with at least 60 usable minutes, cycling through the required topics, all taken from that request's own period, availability and overrides. Works for any dates; a request with custom questions (which need classification) or with fewer such dates than topics fails with `UPSTREAM_ERROR`. |
| `--storage-dir DIR` | Keep `DIR/plans/` and `DIR/user_availability.json` across restarts (restart/readback checks). |
| `--profile-seed FILE` | Copy this availability JSON to the isolated profile when the profile does not exist yet. |
| `--now YYYY/MM/DD/HH/MM` | Freeze the Korea-local clock that manual moves compare against (`MANUAL_MOVE_IN_PAST`). Plan generation and automatic adjustment are unaffected. |
| `--request-log FILE` | Append one JSON line per request: `time`, `method`, `path`, `status`, request `body`, `response`, and any test switch used. |
| `--test-control FILE` | Test scaffolding, not server behavior. A JSON object re-read on every request: `adjust_delay_seconds` (sleep before each `POST /schedules/adjust`), `drop_next_adjust_response` (apply and save the next adjustment, then close the connection without a response; consumed once), `inject_busy_schedules` (rows appended to the next `POST /study-plans` `busy_schedules`; consumed once; the log keeps the original body). |

```bash
# from backend/: controlled-live server for a disposable emulator
python test_support/fixed_live_e2e_server.py --host 127.0.0.1 --port 8000 --planning scripted \
  --storage-dir "$RUN/storage" --profile-seed "$RUN/seed.json" --now 2026/11/18/12/00 \
  --request-log "$RUN/requests.jsonl" --test-control "$RUN/control.json"
```

Stop it with Ctrl+C, or `pkill -TERM -f test_support/fixed_live_e2e_server.py`
when it runs in the background (`conda run` does not forward signals); both
shut the server down cleanly.

This is controlled integration evidence for the handler, deadline rules and
storage. It is not evidence about OpenRouter, model quality, or a deployed
server. Never run the fixture as the public server.

### Live model checks

The live scenario runners above, and any manual check against a running server,
make real OpenRouter requests. Record their results separately from the offline
suites, with enough identity to interpret them later: the date and time, the API
base URL actually targeted, the backend revision (and whether the working tree
was clean), the requested model (`OPENROUTER_MODEL` or the default) and any
provider-returned model, the exact inputs or case IDs, the APK identity when an
app was involved, and every failure, timeout and retry as it happened. Do not
substitute current source defaults for settings that were not recorded.

Use isolated storage: the runners start temporary local servers, and a manual
server can point `APP_STUDY_PLAN_DIR` at a disposable directory. The shared
public server keeps only about 100 generated plans, so a batch of generated
plans there can prune other people's plans; agree with the server owner before
writing to it. Keep API keys, raw provider error bodies and `.env` contents out
of recorded results. A passing live run describes that
run only; it does not establish provider reliability, latency or the quality of
a plan. For P10 weak-topic allocation evaluation, use the restored
[AI evaluation runner](../docs/iter3-ai-eval/README.md), updated for the selected
18-question, eight-topic bank. The earlier frozen evaluation and its failed result are preserved in the
[archived AI evaluation](https://github.com/snuhcs-course/swpp-2026-project-team-02/tree/6d8af7fe65295f286c881d7d6c059c4844bd633f/docs/iter3-ai-eval).

## Model configuration

Study-plan generation and schedule adjustment call an OpenRouter model through the Responses API (`openrouter_client.py`, Python standard library only). Set `OPENROUTER_API_KEY` in the backend process environment, which takes precedence, or in `backend/.env` (the adjuster checks `schedule_adjuster/.env` first). Set optional `OPENROUTER_MODEL` in the same server-only environment to select a model; its compatibility default is `openai/gpt-6-luna`. A nonblank process value takes precedence over the files, then the first nonblank file value in the same order as the key. `.env.example` leaves the override commented out, so a copy with only the key filled in keeps the default. Each agent reads the model once, just before its first model request, and reuses it for that agent's later requests; a configuration change applies to agents created afterwards. Rejections made before inference, such as insufficient capacity, read neither the key nor the model. `GEMINI_API_KEY` is no longer used and does not authorize requests; there is no automatic fallback to another model or to demo data. The key is read only when a model request is about to be sent, is sent only in the request's `Authorization` header, and is not written to generated traces. Without it, otherwise valid `POST /study-plans` and `POST /schedules/adjust` requests return HTTP 503 `UPSTREAM_ERROR`; provider authentication, credit, rate-limit, network, and malformed-reply failures return HTTP 502 `UPSTREAM_ERROR` and are not retried. Provider HTTP rejections and API upstream failures are logged as one server-side warning line with OpenRouter keys and Bearer tokens redacted, line breaks removed, and at most 500 characters of diagnostic text; request headers, input bodies, and full provider payloads are not logged, and client responses keep their fixed messages. Only the first 64 KiB of a provider error body is read for that diagnostic. A body reaching the cap, invalid JSON, an unexpected error shape, or an interrupted read logs a fallback (`provider message too large`, `no provider message`, or `provider message unreadable`); the response is still closed and the request keeps its status-derived 502 category, with no retry or model fallback. The endpoint and 45-second timeout are in `openrouter_client.py`; reasoning effort, output-token limits, and request limits are in each program's agent/settings module. The operator's OpenRouter spending limit is enforced by OpenRouter, not by this backend.



