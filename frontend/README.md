# Frontend

For the keyless HTTP integration build (`integrationDebug`), see
[Iteration 2 integration demo](../INTEGRATION_DEMO.md).

Android app for the License Planner MVP (Kotlin, Jetpack Compose, single `:app`
module, minSdk 26, target SDK 36).

**Flow:** home (계획 생성 / 계획 조정). Creation: select 컴퓨터활용능력 2급 →
enter preparation start and exam dates → choose current level and answer example
questions → set weekday study windows → generate a plan → browse it by week and
day. Adjustment: pick a plan generated in this app session → mark one missed
study session → see the updated plan. See [Plan adjustment](#plan-adjustment).

**Exam-date change (demo):** when the demo planner shows the insufficient-time
warning, “시험 일정 바꾸기” opens an editor with only the exam date. Confirming a
changed date recalculates straight away and shows a fresh warning or the plan; the
assessment answers and study windows are kept, so there is no need to retake the
example questions or re-enter availability. The original setup screen is unchanged.
Back (or an unchanged date) returns to the warning and discards the unconfirmed
date.

**Scope:** written exam (필기) only; practical exam (실기) is not included. The
questions are authored examples, not official exam questions or a validated
diagnostic.

## Build and run

Open this `frontend/` directory (not the repository root) in Android Studio and
let Gradle sync. Pick `demoDebug` (local), `integrationDebug` (HTTP demo), or `liveDebug` (Gemini).

The Gradle daemon requires Java 25 (`gradle/gradle-daemon-jvm.properties`).
From the command line, using Android Studio's bundled Java runtime:

```bash
cd frontend
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew assembleDemoDebug assembleLiveDebug           # APKs in app/build/outputs/apk/
./gradlew testDemoDebugUnitTest testLiveDebugUnitTest   # unit tests
./gradlew installDemoDebug                              # install on a running emulator
```

## Checks

CI runs these in order (Java 25, Android SDK platform 36 and build-tools
36.0.0, Python 3.14):

```bash
# from frontend/
bash ./gradlew --no-daemon testDemoDebugUnitTest testLiveDebugUnitTest

# from the repository root; uses the request exported by the unit tests above
python frontend/contract_checks/verify_request.py --backend-ref HEAD \
    --request frontend/app/build/contract-check/study-plan-request.json

# from frontend/
bash ./gradlew --no-daemon createDemoDebugUnitTestCoverageReport createLiveDebugUnitTestCoverageReport
bash ./gradlew --no-daemon assembleDemoDebug assembleLiveDebug lintDemoDebug lintLiveDebug
```

Debug builds enable JVM unit-test coverage. JaCoCo HTML and `report.xml` are
written to `app/build/reports/coverage/test/demo/debug/` and
`app/build/reports/coverage/test/live/debug/`. Each covers `main` plus that
flavor's and `debug` source sets, including Compose UI code that JVM tests do
not run; AGP leaves generated `R` and `BuildConfig` classes out. Coverage
measures JVM tests only, not on-device or instrumentation tests.

CI uploads the unit-test results, lint reports, and both coverage reports as the
`android-reports` artifact, and the two debug APKs as `android-debug-apks` when
the job succeeds; both are kept 7 days. No minimum coverage is enforced.

Gradle may report test tasks as `UP-TO-DATE` and skip them when nothing
changed; CI starts from a fresh checkout, so its tests always run.

## Flavors

| Flavor | Needs backend | Questions | Plans | Adjustment |
| --- | --- | --- | --- | --- |
| `demo` (default) | No | Five bundled example questions | Local deterministic planner, labeled "demo" on every screen | Local rule, no network |
| `live` | Yes | `GET /certifications/computer_specialist_level_2/questions` | `POST /study-plans` (Gemini, server-side) | `POST /schedules/adjust` |
| `integration` | Yes (`--demo`) | Same HTTP questions | Deterministic examples, explicitly labeled non-AI | Same API and CSV persistence as live |

**Use `demo` for evaluation without a backend.** Its plans are a demonstration
calculation, not backend or AI output. The live flavor never falls back to demo
data: on failure it shows an error with retry and keeps all inputs.

## Live backend

The live flavor connects to this repository's backend. Start it from `backend/`
with the project's Python environment and `GEMINI_API_KEY` configured in
`backend/.env`:

```bash
conda activate swpp
cd backend
python api_server.py    # http://127.0.0.1:8000/api/v1; GEMINI_API_KEY in backend/.env
```

The app's default API URL is `http://10.0.2.2:8000/api/v1`, which reaches the
host machine from the Android Emulator. To use a different backend URL, build
the live variant with:

```bash
./gradlew assembleLiveDebug -PlicensePlanner.apiBaseUrl=http://127.0.0.1:8000/api/v1
```

For a USB-connected device, run `adb reverse tcp:8000 tcp:8000` and set the
build property to `http://127.0.0.1:8000/api/v1`. Debug builds allow cleartext
HTTP only to `10.0.2.2`, `127.0.0.1`, and `localhost`. API keys stay in the
backend; the app never calls Gemini directly.

Planning runs Gemini synchronously and can take tens of seconds (request timeout
120 s). The matching routes and JSON contract are documented in
[`backend/FRONTEND_INTEGRATION_GUIDE.md`](../backend/FRONTEND_INTEGRATION_GUIDE.md).

## Plan adjustment

Home offers 계획 생성 (the existing creation flow, with earlier inputs kept)
and 계획 조정 (a list of plans generated on this device). Each
successful generation adds one entry, numbered 계획 1, 계획 2, … even when dates
match. Editing the form or a failed generation never removes an entry. The
list and each plan's original request are stored in app-private preferences, so
they survive process and device restarts. The live backend also stores plan CSVs
on disk; after a server restart, plans remain adjustable as long as the backend's
plan directory is preserved. Clearing app data or reinstalling removes the local
plan list. The server has no account-based ownership or cross-device plan sync.

On a plan, choose exactly one missed study session (`study-N`); existing
schedules (`external-N`) are shown but cannot be chosen. A live plan whose
generation response had no `schedule_csv_file` can be viewed but not adjusted.
A plan with no study sessions explains that and offers a way back. After a
successful adjustment the same entry is updated in place, the view jumps to the
moved session's week, and another session can be adjusted from the new rows.
Other plans never change, even when they share schedule IDs.

While an adjustment request runs, the screen shows progress; system back,
the top-bar back, and every other action are held, and rotation keeps the same
request. A second tap sends nothing. On failure the plan and the chosen session
stay as they were. Before another live attempt, the app reads the saved plan.
Changed rows are displayed without another mutation; unchanged rows allow retry.
A failed refresh blocks the adjustment. There is no automatic retry and no demo fallback in
the live build. A lost connection or timeout says the result could not be
confirmed, not that the server undid anything.

**Demo:** a local demonstration rule, not the backend's policy and not AI
output. The session moves to the first free interval inside the plan's
original weekday windows that starts after both its own end and the latest
other session with the same topic, and ends before the original exam date. If
nothing fits, the app reports it and keeps the plan. Nothing is saved.

**Live contract.** The app always sends four fields to
`POST /api/v1/schedules/adjust`: the plan's exact `schedule_csv_file` from
generation, the chosen `schedule_id`, every current row (external rows
included), and the `weekly_availability` from that plan's original creation
request (not the current form):

```json
{
  "schedule_csv_file": "license_planner/output/study_plan_20261016T180000000000+0900_abcdef12.csv",
  "schedule_id": "study-1",
  "schedules": [
    {"schedule_id": "study-1", "start_date": "2026/10/19/18/00", "end_date": "2026/10/19/19/00", "topic": "함수와 데이터 관리"},
    {"schedule_id": "external-1", "start_date": "2026/10/22/18/00", "end_date": "2026/10/22/19/00", "topic": ""}
  ],
  "weekly_availability": {"monday": [{"start": "18:00", "end": "20:00"}]}
}
```

The app accepts a 200 response only if `action` is `rescheduled_study`,
`target_schedule_id` matches, rows parse with end after start, IDs are
nonblank and unique, the target row is present, and any returned
`schedule_csv_file` equals the one sent. Anything else leaves the plan as it was.
The app never sends the availability profile (`PUT /availability`).

**Integrated backend:** the server uses `schedule_csv_file` and the supplied
`weekly_availability`, validates the snapshot, and atomically updates the same CSV
before success. Stale snapshots return 409; missing plans return 404. The app
uses `GET /study-plans` to recover after failed or uncertain adjustments.

## Verification

- Unit tests pass for both flavors (JVM, with MockWebServer for HTTP).
- `contract_checks/verify_request.py` checks the request the app serializes
  against a backend revision's source, offline (no server or Gemini). CI uses
  `--backend-ref HEAD`, the backend in the same checkout.
- Demo and live flows were run end to end on an emulator and compared with the
  Figma design.
- Plan adjustment: JVM tests cover the request body, response checks, the demo
  rule and the session/adjustment state. The demo flow and the live screens
  (against a scripted local test server, not the backend or Gemini) were run on
  an emulator. The added `HttpBackendIntegrationTest` exercises Kotlin repositories
  against the real Python server with deterministic demo agents, including file persistence.

CI also starts the deterministic demo server and runs the HTTP integration test.
It does not run Gemini or an emulator/UI; those require separate verification.

## Evaluation inputs

Fixed inputs for repeatable plan-quality and performance checks. Use the APKs
from `assembleDemoDebug`/`assembleLiveDebug` (or CI's `android-debug-apks`
artifact), and record the APK SHA-256 and the backend commit with each result.
Both cases choose 기본 기능은 알아요 and answer the five example questions
correctly (B, B, A, A, C), with preparation starting 2026-10-19.

| Case | Exam date and study windows | Demo result (observed 2026-10-17) |
| --- | --- | --- |
| A: adjustment | 2026-12-14; Mon 18:00 for 2 h, Tue 18:00 for 1 h, Wed 18:00 for 2 h | Six sessions. Adjusting `study-1` moves it to 10/26 19:00–20:00; adjusting it again moves it to 10/28 19:00–20:00. |
| B: no free slot | 2026-11-02; Mon, Wed, Fri 18:00 for 1 h | Six sessions fill every slot; adjusting one reports 옮길 수 있는 시간이 없어요. |

In the live build, Gemini output varies, so run each case several times. Check
the returned `schedules` against the inputs: sessions inside the chosen windows,
no overlaps, everything before the exam date, topic coverage, and response
time. Gemini behavior still requires a configured live run.

## Known limitations

- Written exam only; practical-exam support is an open decision.
- Not tested on a physical device; TalkBack support is incomplete.
- Form inputs are held in memory and are lost if the process is killed; generated
  plans are stored on the device and can be reopened after relaunch.
- Live plans come from Gemini, so session counts can vary for the same inputs.
- Live insufficient capacity arrives as a generic generation error with retry and
  “입력 수정하기”, so the warning-origin exam-date editor is demo-only.
