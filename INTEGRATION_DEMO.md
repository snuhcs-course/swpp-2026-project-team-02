# Iteration 2 integration demo

The `integrationDebug` Android build uses the real Python HTTP API and CSV store.
It displays **HTTP 통합 데모 · AI 아님**. Start the server explicitly with `--demo`:
no Gemini key is needed and no automatic fallback is used.

## Run

Requirements: Python 3.14, Java 25, Android SDK 36, and an Android emulator.
Start the backend in one terminal:

```powershell
cd backend
python api_server.py --demo
```

In another terminal, build and install the app:

```powershell
cd frontend
.\gradlew.bat installIntegrationDebug
```

Launch License Planner (`com.swpp.licenseplanner.integration`). The APK is
`frontend/app/build/outputs/apk/integration/debug/app-integration-debug.apk`.
The emulator reaches the host at `http://10.0.2.2:8000/api/v1`.
For another port, set `$env:APP_API_PORT='8001'` before starting the server and
pass `'-PlicensePlanner.apiBaseUrl=http://10.0.2.2:8001/api/v1'` to Gradle.

For a physical USB device, use `adb reverse tcp:8000 tcp:8000` and build with
`'-PlicensePlanner.apiBaseUrl=http://127.0.0.1:8000/api/v1'`.

## Walkthrough

1. From home, create a 컴퓨터활용능력 2급 plan. Set the exam at least four weeks after preparation starts.
2. Choose a level, answer the example questions, and enable Monday and Wednesday, 18:00–20:00.
3. Generate the plan and inspect the weekly schedule.
4. Open the generated-plan list, select the plan, choose one missed study session, and adjust it.
5. Verify the new time. The same plan file is updated; a second generated plan remains unchanged.

The demo planner creates 30-minute examples for the certification topics within
the supplied availability, skipping busy periods. It does not estimate study time
from diagnostic scores. Adjustment executes the production domain tools, snapshot
validation, and atomic CSV replacement. Demo storage is `backend/demo_output/`;
the normal server uses `backend/license_planner/output/`.

After a failed adjustment, another attempt first reads `GET /api/v1/study-plans`.
If the saved rows changed, the app shows them and clears the selection without
sending another adjustment. If unchanged, it retries. A failed refresh prevents
another mutation. The server rejects stale snapshots with 409 and missing files
with 404. Neither automatic resends nor silent demo fallbacks are enabled.

## Verification

With the demo server running, run the real Kotlin-to-Python HTTP regression:

```powershell
cd frontend
$env:LICENSE_PLANNER_TEST_API='http://127.0.0.1:8000/api/v1'
.\gradlew.bat testIntegrationDebugUnitTest
```

`HttpBackendIntegrationTest` loads questions, generates a plan, adjusts it, verifies
list/download persistence, submits a stale snapshot, and verifies refresh recovery.
Without the environment variable, that test is skipped. CI starts the demo server
and enables it. Regular backend API tests also run in CI.

## Scope

- Plan lists retain plans created in the current app process. Process restart clears
  the list; server files remain. Restoring the original creation context is not implemented.
- Questions are the existing authored examples, not official exam questions.
- The backend adjustment contract does not include an exam-date bound; the demo does
  not add one. Choose a sufficiently distant exam date for the walkthrough.
- The `demoDebug` flavor remains entirely local. For Gemini, run `api_server.py`
  without `--demo`, configure `GEMINI_API_KEY`, and install `liveDebug`.
- Passing the deterministic demo does not verify Gemini behavior.
