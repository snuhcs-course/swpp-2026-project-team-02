# Team 2 — Iteration 2 Demo

This branch contains the integrated Android frontend and Python backend demo for Iteration 2. The recommended `integrationDebug` build uses the real HTTP API and CSV persistence while generating deterministic plans without an API key.

## Demo video

[Watch the Iteration 2 demo](iteration-2-demo.mp4) for the key assessment, plan generation, plan-list, and saved-adjustment flows.

## How to run the demo

### Tested environment

- Windows build 10.0.26200
- Android Studio 2026.1.3
- Android Gradle Plugin 9.3.0 and Gradle 9.8.0
- JetBrains Runtime / Java 25.0.2
- Android SDK 36 with an Android API 35 or 36 emulator
- Python 3.14; no third-party Python packages are required for the demo server

### 1. Start the backend

Open PowerShell in the repository root and run:

```powershell
cd backend
python api_server.py --demo
```

Keep this terminal open. Confirm that the API is ready at `http://127.0.0.1:8000/health`.

`--demo` runs the complete HTTP and CSV storage flow with deterministic plan generation and adjustment. It does not require a Gemini API key. Generated plans are stored under `backend/demo_output/`, which is ignored by Git.

### 2. Run the Android app

In Android Studio:

1. Open the `frontend` directory as the project.
2. Set **Gradle JDK** to Java 25.
3. Sync the Gradle project.
4. Create or select an Android API 35 or 36 emulator.
5. Select the `integrationDebug` build variant.
6. Select the `app` run configuration and click **Run**.

The emulator reaches the host backend through `http://10.0.2.2:8000/api/v1`. To install from PowerShell instead, run:

```powershell
cd frontend
.\gradlew.bat installIntegrationDebug
```

If port 8000 is unavailable, start the server on another port and pass the matching emulator URL while building:

```powershell
cd backend
python api_server.py --demo --port 8001

cd ..\frontend
.\gradlew.bat installIntegrationDebug "-PlicensePlanner.apiBaseUrl=http://10.0.2.2:8001/api/v1"
```

### 3. Exercise the demo flow

1. Complete the assessment and enter weekly availability.
2. Generate a study plan.
3. Return to the main page and open **Generated Plans**.
4. Select the generated plan.
5. Select a missed study schedule and request an adjustment.
6. Confirm the adjusted schedule in the app.
7. Reopen the plan list and confirm that the same plan file contains the saved adjustment.

For request and response examples, see [INTEGRATION_DEMO.md](INTEGRATION_DEMO.md). The backend contract is documented in [backend/FRONTEND_INTEGRATION_GUIDE.md](backend/FRONTEND_INTEGRATION_GUIDE.md).

## What the demo demonstrates

### Implemented features

- Assessment-based study-plan generation through the Android UI
- Plan identity based on the returned `schedule_csv_file`
- Retrieval and display of generated plan files
- Selection of a missed schedule by `schedule_id`
- Adjustment using the selected plan, current schedule snapshot, and weekly availability
- Atomic persistence of the adjusted schedule to the same CSV plan file
- Refresh of the saved plan after adjustment

### Error flows

- A missing plan returns HTTP 404 and leaves the displayed plan unchanged.
- A stale schedule snapshot returns HTTP 409 instead of overwriting a newer plan.
- Timeout or uncertain network failure preserves the current plan and shows a retry action.
- Before retrying an uncertain adjustment, the app reloads the plan. If the first request was saved, the app displays that saved result and does not send a duplicate adjustment.

## Major progress in Iteration 2

- Combined the frontend plan-adjustment flow with the backend plan-list and schedule-adjustment APIs.
- Replaced in-memory-only integration behavior with an HTTP-connected demo that writes and updates real CSV plan files.
- Added explicit conflict detection and frontend recovery for ambiguous network failures.
- Added backend and Android automated tests for generation, listing, adjustment, persistence, conflict handling, and retry behavior.
- Added CI checks for backend coverage, Android unit tests, FE-BE request-contract compatibility, APK builds, lint, coverage reports, and APK artifacts.

## Verification

The integrated branch is checked with the following commands:

```powershell
# Backend
cd backend
python -m unittest discover -s license_planner_tests -v
python -m unittest discover -s schedule_adjuster_tests -v
python -m unittest discover -s api_tests -v

# Android unit tests, APKs, and lint
cd ..\frontend
.\gradlew.bat testDemoDebugUnitTest testLiveDebugUnitTest testIntegrationDebugUnitTest
.\gradlew.bat assembleDemoDebug assembleLiveDebug assembleIntegrationDebug
.\gradlew.bat lintDemoDebug lintLiveDebug lintIntegrationDebug

# FE-BE adjustment request contract
cd ..
python frontend/contract_checks/verify_request.py --backend-ref HEAD --request frontend/app/build/contract-check/study-plan-request.json
```

The GitHub Actions workflow also runs on pushes to `iteration-2-demo`. It uploads backend and Android coverage reports and the three debug APK variants as artifacts.

## Current scope

- The integrated demo targets the 컴퓨터활용능력 2급 written exam.
- `integrationDebug` uses deterministic local generation so that the submission can be reproduced without credentials.
- `liveDebug` uses Gemini plan generation and requires `GEMINI_API_KEY` in `backend/.env` before starting `python api_server.py`.
- Storage is local CSV without authentication or per-user isolation; these are outside the Iteration 2 demo scope.
