# Testing Documentation

**Owner:** 조준우  
**Iteration:** 3  
**Structure confirmed:** 2026-11-05  
**Reference revision:** `5216f41` (`docs(testing): record Android live E2E CI evidence`)

This document distinguishes repeatable offline CI evidence from paid model calls,
deployed-server checks, and physical-device runs. A passing offline CI run does not
establish that the public API, OpenRouter, or an Android device is working.

## CI structure

The source of truth is [`.github/workflows/ci.yml`](../.github/workflows/ci.yml).
It runs for pull requests targeting `main`, pushes to `main`, manual dispatch, and
the daily default-branch schedule at 00:00 UTC (09:00 KST). It has no OpenRouter
secret. Its fixed E2E job starts an isolated backend fixture and emulator; it never
contacts the public server or a model provider.

| Job | Environment | Checks | Evidence artifact |
| --- | --- | --- | --- |
| `Backend` | Ubuntu 24.04, Python 3.14 | Offline `license_planner_tests`, `schedule_adjuster_tests`, `api_tests`; branch coverage | `backend-coverage` — `coverage.xml`, HTML; retained 7 days |
| `Android` | Ubuntu 24.04, Java 25, Android API 36 | `demoDebug` and `liveDebug` JVM unit tests; request contract check; JaCoCo reports; debug APK build; lint | `android-reports` — JUnit, HTML, lint, JaCoCo; `android-debug-apks`; retained 7 days |
| `Android live E2E` | Ubuntu 24.04, Python 3.14, Java 25, API 35 emulator | Fixed backend fixture; Android `live` instrumentation path: question GET → plan POST → persisted-plan GET | `android-live-e2e-evidence` — Android connected-test reports and fixture log; retained 7 days |

The contract check reads the request JSON exported by the Android unit tests and
compares it with the backend source at the checked-out revision. It is offline.

## Explicitly out of CI scope

- `backend/scenarios/` runners, including the real OpenRouter scenario runners
- OpenRouter credentials, credit, latency, retries, provider failures, and model quality
- CPU-server systemd/Nginx configuration and public HTTP reachability
- Physical device, calendar provider, and external-network behavior
- signed `liveRelease` evaluator APK; this belongs to `prod-apk.yml`

Those checks must be recorded separately as live-model, deployment, or device
evidence. Do not label them as CI coverage.

## Reproduction commands

Run from the indicated folders. These commands match the CI suites; they do not
invoke a model.

### Backend

```bash
cd backend
python -m pip install -r requirements-ci.txt
python -m coverage erase
python -m coverage run -m unittest discover -s tests/license_planner_tests -v
python -m coverage run --append -m unittest discover -s tests/schedule_adjuster_tests -v
python -m coverage run --append -m unittest discover -s tests/api_tests -v
python -m coverage report -m
python -m coverage xml
python -m coverage html
cd ..
python scripts/verify_test_evidence.py --scope backend
```

Generated reports are `backend/coverage-results/coverage.xml` and
`backend/coverage-results/html/index.html`.

### Android

```bash
cd frontend
./gradlew --no-daemon testDemoDebugUnitTest testLiveDebugUnitTest
cd ..
python frontend/contract_checks/verify_request.py --backend-ref HEAD \
  --request frontend/app/build/contract-check/study-plan-request.json
cd frontend
./gradlew --no-daemon createDemoDebugUnitTestCoverageReport createLiveDebugUnitTestCoverageReport
cd ..
python scripts/verify_test_evidence.py --scope android
cd frontend
./gradlew --no-daemon assembleDemoDebug assembleLiveDebug lintDemoDebug lintLiveDebug
```

Generated reports are:

- `frontend/app/build/test-results/test*DebugUnitTest/`
- `frontend/app/build/reports/tests/test*DebugUnitTest/`
- `frontend/app/build/reports/coverage/test/demo/debug/report.xml`
- `frontend/app/build/reports/coverage/test/live/debug/report.xml`
- `frontend/app/build/reports/lint-results-*Debug.*`

## Recorded execution evidence

The commands above were reproduced locally on 2026-11-05 and then validated in
GitHub Actions against the same pushed revision. The remote run is the
authoritative CI evidence; local output is retained to make failures reproducible
without spending model credits or requiring a device.

| Execution | Revision | Result | Evidence |
| --- | --- | --- | --- |
| Local offline reproduction — 2026-11-05 | `7c67a10` plus the report-validator working tree | Passed | Backend: 150 tests. Android: demo 193 and live 191 tests, each with one platform-permission skip. |
| GitHub Actions CI #48 — 2026-11-05 | [`b599e4f`](https://github.com/snuhcs-course/swpp-2026-project-team-02/commit/b599e4fd74ddd23e24bae8e62bf51119eb27e205) | Passed in 3m 55s | [Run #48](https://github.com/snuhcs-course/swpp-2026-project-team-02/actions/runs/41963702935): Backend 32s, Android 3m 51s; manually dispatched from `iteration-3-scheduler`. |
| GitHub Actions CI #57 — 2026-11-05 | [`e95db19`](https://github.com/snuhcs-course/swpp-2026-project-team-02/commit/e95db19) | Passed in 21m 25s | [Run #57](https://github.com/snuhcs-course/swpp-2026-project-team-02/actions/runs/42013613486): Backend 34s, Android 4m 39s, Android live E2E 21m 22s. All three jobs passed; the E2E artifact contains the fixture log and connected-test report. |
| GitHub Actions CI #58 — 2026-11-05 | [`5216f41`](https://github.com/snuhcs-course/swpp-2026-project-team-02/commit/5216f41136a01218766ff5a3e13134d12704a972) | Passed | [Run #58](https://github.com/snuhcs-course/swpp-2026-project-team-02/actions/runs/42028945104): documentation revision regression check. |

### Measured report summary

| Report | Measured result | Interpretation |
| --- | --- | --- |
| Backend `coverage report` | 1,642 statements; 68% total coverage | Combined offline backend suites. Cobertura XML line rate is 70.77%; branch rate is 58.92%. |
| Android demo JaCoCo | 1,082 / 2,326 lines | Report contains 253 `com.swpp.licenseplanner` classes. |
| Android live JaCoCo | 1,051 / 2,294 lines | Report contains 251 `com.swpp.licenseplanner` classes. |
| Android JUnit | demo 193, live 191; failures 0, errors 0 | One permission-dependent `AtomicFilePlanStoreTest` case is skipped in each flavor on this Windows host. The remote CI job passed. |

CI #57 uploaded `backend-coverage`, `android-reports`, `android-debug-apks`, and
`android-live-e2e-evidence` as artifacts. The workflow retains test/coverage
artifacts for 7 days; debug APKs are CI evidence only and are not the evaluator's
signed release APK.

## Evidence-verification script policy

[`scripts/verify_test_evidence.py`](../scripts/verify_test_evidence.py) does **not** run scenario runners or duplicate
their assertions. It will read the generated JUnit and coverage XML files, fail
when a required report is missing or when a recorded test failure/error is present,
and print a compact JSON summary for the Testing Documentation. It accepts
`--scope backend`, `--scope android`, or the default `--scope all`.

It will validate that:

1. Backend coverage has a positive production-source denominator and `api_server.py`.
2. Android `demoDebug` and `liveDebug` reports both contain project classes and
   positive line denominators.
3. All discovered JUnit suites report zero failures and errors.
4. The report revision, commands, date, and CI run URL are recorded by the caller;
   the script does not invent a successful run.

## Fixed live-backend fixture (P16)

The Android `live` flavor's minimal E2E path uses a local fixture server rather
than the public deployment or a real model call. Start it from the repository
root before the Android instrumentation test:

```bash
python backend/test_support/fixed_live_e2e_server.py --host 0.0.0.0 --port 8000
```

The emulator reaches this server at `http://10.0.2.2:8000/api/v1`, which is the
existing default `live` base URL. The fixture runs the production
`api_server.make_handler`, request validation, planner tools/scheduler, and
`StudyPlanStore`; it isolates persisted CSV files in a temporary directory.
Only `PlanningAgent._ask_model` is replaced with the checked-in recorded tool
call trace at `backend/tests/api_tests/fixtures/recorded_model_tool_calls.json`.
It does not read an OpenRouter key or make an external request, and must not be
deployed as the public server.

`backend/tests/api_tests/test_fixed_live_e2e_server.py` asserts the same server
can serve questions, create a plan, and re-read the stored plan through real
HTTP. This regression test is included by the existing backend API-test
discovery. With the fixture still running and a booted Android emulator, run:

```bash
cd frontend
./gradlew --no-daemon :app:connectedLiveDebugAndroidTest
```

`LiveBackendE2eTest` exercises the Android `live` BuildConfig URL, real OkHttp
transport and serializers, then fixture-backed question retrieval, plan
generation, and server saved-plan readback. It passed locally on 2026-11-05 on
the `Medium_Phone` AVD: 1 test, 0 failures, 0 skipped (1.666 s). It also passed
in CI #57 on an API 35 emulator. The CI job maps emulator loopback port 8000 to
the fixture with `adb reverse` and supplies the matching live base URL only for
that test build. Actual OpenRouter and public-server validation remain separate
deployment evidence.

## Current status

| Evidence | Status | Notes |
| --- | --- | --- |
| CI structure and report paths | Passed — CI #57 | [`ci.yml`](../.github/workflows/ci.yml) invokes the reusable report validator for backend/Android and the fixed-fixture E2E job. |
| Offline backend fresh run | Passed — 2026-11-05 | 57 `license_planner` + 31 `schedule_adjuster` + 62 API tests; total coverage 68%. Reports: `backend/coverage-results/`. |
| Offline Android fresh run | Passed — 2026-11-05 | `testDemoDebugUnitTest` (193 tests, 1 platform-permission skip) and `testLiveDebugUnitTest` (191 tests, 1 platform-permission skip); failures/errors 0. JaCoCo reports and both debug APKs generated. |
| Android ↔ backend request contract | Passed — CI #57 | `verify_request.py --backend-ref HEAD` ran before coverage on the GitHub-hosted checkout. |
| Generated-report validation | Passed — CI #57 | `verify_test_evidence.py` accepted all required JUnit, JaCoCo, and backend coverage reports. |
| Android live ↔ fixed backend E2E | Passed locally and in CI #57 — 2026-11-05 | `connectedLiveDebugAndroidTest` executed `LiveBackendE2eTest`: question GET → plan POST → persisted-plan GET; local `Medium_Phone`: 1 test, 0 failures, 0 skipped; CI API 35 emulator: job passed and uploaded `android-live-e2e-evidence`. |
| Redirect behavior | Offline tests added | `LivePlanningRepositoryTest` verifies generation follows same-origin HTTP 307; `LiveAdjustmentRepositoryTest` verifies adjustment exposes 307 without replaying a state-changing POST. |
| Public-server/current signed APK result | Passed on Android emulator — 2026-11-06 | See the separate live-deployment record below. This is not fixed-fixture CI coverage. |

## Public deployment and signed-APK verification — 2026-11-06

This run is deliberately recorded separately from CI. It used the current signed
`liveRelease` artifact produced by [Prod APK run #34](https://github.com/snuhcs-course/swpp-2026-project-team-02/actions/runs/42028937748), source revision
[`5216f41`](https://github.com/snuhcs-course/swpp-2026-project-team-02/commit/5216f41136a01218766ff5a3e13134d12704a972), and the public CPU-server API:

```text
http://swpp26t02.147-46-242-174.sslip.io/api/v1
```

The public health endpoint returned `{"status":"ok"}`. The server was running the
same `iteration-3-scheduler` revision and used its server-local OpenRouter
configuration; no API key was put into the APK, CI secrets, or this record.

| Manual live check | Result | Evidence |
| --- | --- | --- |
| Plan generation | Passed | A 컴퓨터활용능력 2급 plan for 2026-11-06 to 2026-11-13 was created and displayed. |
| Persisted-plan lookup after app relaunch | Passed | The generated plan was re-opened with the same 11/9 18:00–20:30 entries. |
| Deadline-bounded adjustment | Passed | When no continuation could fit before the 2026-11-13 exam, the app kept the selected entry unchanged and explained that study time before the exam was insufficient. |
| Persisted adjusted-plan lookup after app relaunch | Passed | The adjusted plan was re-opened with the saved 11/9 20:00–21:30 entries. |

The supporting screenshots are versioned under
[`docs/images/iter3-live/`](images/iter3-live/):

1. [`01-plan-created-and-reopened.png`](images/iter3-live/01-plan-created-and-reopened.png)
2. [`02-adjustment-selection.png`](images/iter3-live/02-adjustment-selection.png)
3. [`03-not-enough-time-before-exam.png`](images/iter3-live/03-not-enough-time-before-exam.png)
4. [`04-adjusted-plan-reopened.png`](images/iter3-live/04-adjusted-plan-reopened.png)

This is an emulator validation of the signed release connected to the public
server, not a claim of physical-device compatibility. The historical intermittent
HTTP 307 adjustment report remains a separate unresolved observation; it was not
reproduced or declared fixed by this execution.
