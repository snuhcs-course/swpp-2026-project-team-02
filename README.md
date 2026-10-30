# Team 2 Project

This repository is organized into separate application areas:

```text
project/
├── frontend/   # Android app (Kotlin, Jetpack Compose)
├── backend/    # Existing Python API, agents, planners, tests, and docs
└── README.md   # Repository overview and team setup
```

## Backend

The existing backend project is in [`backend/`](backend/). Backend commands and
paths in its documentation are relative to that directory. Start by reading
[`backend/README.md`](backend/README.md) and
[`backend/FRONTEND_INTEGRATION_GUIDE.md`](backend/FRONTEND_INTEGRATION_GUIDE.md).

To run the API locally:

```powershell
cd backend
python api_server.py
```

The API defaults to `http://127.0.0.1:8000/api/v1`. Study-plan generation and
schedule adjustment call OpenRouter model `openai/gpt-6-luna`; configure
`OPENROUTER_API_KEY` in the backend process environment or `backend/.env`. Never
put API keys in frontend files. `.env` files and local
availability profiles are excluded from Git.

## Frontend (Android)

[`frontend/`](frontend/) contains the Android app for the MVP, covering
preparation for the 컴퓨터활용능력 2급 written exam (필기). The default `demo`
build runs without a backend. The `live` build connects to this checkout's
backend at `http://10.0.2.2:8000/api/v1` from the Android Emulator. The backend
provides the question-bank and locally-scored assessment API contract used by
the app.

Build, run, and backend setup are in [`frontend/README.md`](frontend/README.md).

## Continuous integration

[`.github/workflows/ci.yml`](.github/workflows/ci.yml) runs on pull requests and
pushes to `main`, on manual dispatch, and daily at 00:00 UTC. It runs the three
offline backend unit suites in `backend/tests/` with coverage (not the scenario
runners in `backend/scenarios/`), and the Android unit tests, request contract check,
coverage, debug APK builds, and lint. Commands and report locations are in
[`backend/README.md`](backend/README.md#checks-and-coverage) and
[`frontend/README.md`](frontend/README.md#checks). CI uses no OpenRouter key, running
backend, or emulator, so it does not verify live model planning or the Android UI.

## Iteration 3 deployment

The deployed CPU-server topology, systemd and Nginx templates, public API check,
and fixed device evaluation flow are documented in [`deploy/`](deploy/README.md).
The current evaluator API is
`http://swpp26t02.147-46-242-174.sslip.io/api/v1`; release APK installation and
submission details are in [`ITER3_SUBMISSION.md`](ITER3_SUBMISSION.md).

## Open decisions

- **Practical exam (실기):** whether and how to support it. The app currently
  plans for the written exam only.
- **Question bank:** replace the example questions with the team's agreed bank
  (for example, the eight questions on branch `assessment`).
- **Not enough study time:** proven infeasibility returns HTTP 422 with capacity,
  topic basis and recovery advice in `error.message`; the live app presents it in
  Korean. A richer structured shortfall schema remains an open decision.
- **Summary labeling:** generation summaries are composed by server code from
  accepted schedule counts. The app still labels them “AI가 작성한 설명”; aligning
  that label with the summary source remains a copy decision.
- **Deployment:** authentication and per-user storage remain open; the current
  Iteration 3 server and APK use the shared HTTP endpoint documented above.
