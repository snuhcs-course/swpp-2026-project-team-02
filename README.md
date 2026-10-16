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

The API defaults to `http://127.0.0.1:8000/api/v1`. Configure the Gemini key in
`backend/.env`; never put API keys in frontend files. `.env` files and local
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

[`.github/workflows/ci.yml`](.github/workflows/ci.yml) runs two independent
jobs, **Backend** and **Android**, on pull requests to `main`, pushes to `main`,
manual dispatch, and daily at 00:00 UTC (09:00 Asia/Seoul). GitHub runs the
scheduled event on the default branch only and may start it late.

**Backend** (Python 3.14, from `backend/`):

```bash
python -m pip install -r requirements-ci.txt   # CI-only: pinned coverage.py
python -c "import sys; import api_service; print(sys.executable)"
python -m coverage erase
python -m coverage run -m unittest discover -s license_planner_tests -v
python -m coverage run --append -m unittest discover -s schedule_adjuster_tests -v
python -m coverage report -m
python -m coverage xml
python -m coverage html
```

The backend application has no third-party dependencies; to reproduce locally,
install `requirements-ci.txt` into a separate virtual environment rather than the
`swpp` runtime environment. [`backend/.coveragerc`](backend/.coveragerc) measures
branch coverage over the production modules, including HTTP and Gemini agent
code that no unit test runs, and writes `backend/coverage-results/` (generated;
do not commit it).

**Android** (Java 25, Android SDK 36) runs both flavors' JVM unit tests, the
request contract check against this checkout's backend, JaCoCo unit-test
coverage, debug APK builds, and lint. Commands are in
[`frontend/README.md`](frontend/README.md#checks).

**Artifacts** (kept 7 days): `backend-coverage` (XML and HTML),
`android-reports` (unit-test results, lint, and demo/live coverage under
`frontend/app/build/reports/coverage/test/{demo,live}/debug/`), and
`android-debug-apks` (only when the Android job succeeds). Coverage is reported
without a minimum percentage; any threshold should be agreed from measured
baselines.

**Offline** means the tests call no external application service: no Gemini
key, running backend, emulator, or deployed server is used. Downloading actions,
Python packages, the JDK, the Android SDK, and Gradle dependencies still requires
network access. A passing run therefore does not verify Gemini planning, the
HTTP server, or the Android UI.

## Open decisions

- **Practical exam (실기):** whether and how to support it. The app currently
  plans for the written exam only.
- **Question bank:** replace the example questions with the team's agreed bank
  (for example, the eight questions on branch `assessment`).
- **Not enough study time:** the API has no structured response for this, so the
  live app shows it as a generic error.
- **Deployment:** server address, authentication, and per-user storage.
