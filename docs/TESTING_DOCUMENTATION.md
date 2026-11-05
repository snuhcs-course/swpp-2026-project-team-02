# Testing Documentation

**Owner:** 조준우  
**Iteration:** 3  
**Structure confirmed:** 2026-11-05  
**Reference revision:** `7c67a10` (`test(ci): align local reproduction with adjustment contract`)

This document distinguishes repeatable offline CI evidence from paid model calls,
deployed-server checks, and physical-device runs. A passing offline CI run does not
establish that the public API, OpenRouter, or an Android device is working.

## CI structure

The source of truth is [`.github/workflows/ci.yml`](../.github/workflows/ci.yml).
It runs for pull requests targeting `main`, pushes to `main`, manual dispatch, and
the daily default-branch schedule at 00:00 UTC (09:00 KST). It has no OpenRouter
secret and does not start a backend process or emulator.

| Job | Environment | Checks | Evidence artifact |
| --- | --- | --- | --- |
| `Backend` | Ubuntu 24.04, Python 3.14 | Offline `license_planner_tests`, `schedule_adjuster_tests`, `api_tests`; branch coverage | `backend-coverage` — `coverage.xml`, HTML; retained 7 days |
| `Android` | Ubuntu 24.04, Java 25, Android API 36 | `demoDebug` and `liveDebug` JVM unit tests; request contract check; JaCoCo reports; debug APK build; lint | `android-reports` — JUnit, HTML, lint, JaCoCo; `android-debug-apks`; retained 7 days |

The contract check reads the request JSON exported by the Android unit tests and
compares it with the backend source at the checked-out revision. It is offline.

## Explicitly out of CI scope

- `backend/scenarios/` runners, including the real OpenRouter scenario runners
- OpenRouter credentials, credit, latency, retries, provider failures, and model quality
- CPU-server systemd/Nginx configuration and public HTTP reachability
- Android emulator, physical device, calendar provider, and external-network behavior
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
./gradlew --no-daemon assembleDemoDebug assembleLiveDebug lintDemoDebug lintLiveDebug
```

Generated reports are:

- `frontend/app/build/test-results/test*DebugUnitTest/`
- `frontend/app/build/reports/tests/test*DebugUnitTest/`
- `frontend/app/build/reports/coverage/test/demo/debug/report.xml`
- `frontend/app/build/reports/coverage/test/live/debug/report.xml`
- `frontend/app/build/reports/lint-results-*Debug.*`

## Evidence-verification script policy

The Iteration 3 verification script will **not** run scenario runners or duplicate
their assertions. It will read the generated JUnit and coverage XML files, fail
when a required report is missing or when a recorded test failure/error is present,
and print a compact machine-readable summary for the Testing Documentation.

It will validate that:

1. Backend coverage has a positive production-source denominator and `api_server.py`.
2. Android `demoDebug` and `liveDebug` reports both contain project classes and
   positive line denominators.
3. All discovered JUnit suites report zero failures and errors.
4. The report revision, commands, date, and CI run URL are recorded by the caller;
   the script does not invent a successful run.

## Current status

| Evidence | Status | Notes |
| --- | --- | --- |
| CI structure and report paths | Confirmed | This document and `ci.yml` agree. |
| Offline backend fresh run | Passed — 2026-11-05 | 57 `license_planner` + 31 `schedule_adjuster` + 62 API tests; total coverage 68%. Reports: `backend/coverage-results/`. |
| Offline Android fresh run | Passed — 2026-11-05 | `testDemoDebugUnitTest` (193 tests, 1 platform-permission skip) and `testLiveDebugUnitTest` (191 tests, 1 platform-permission skip); failures/errors 0. JaCoCo reports and both debug APKs generated. |
| Android ↔ backend request contract | Passed — 2026-11-05 | `verify_request.py --backend-ref HEAD` passed with 5 public questions, 5 results, and 0 problems. |
| Redirect behavior | Offline tests added | `LivePlanningRepositoryTest` verifies generation follows same-origin HTTP 307; `LiveAdjustmentRepositoryTest` verifies adjustment exposes 307 without replaying a state-changing POST. |
| Public-server/device result | Separate evidence | 2026-11-04 release-APK adjustment returned HTTP 200; historical intermittent 307 remains unresolved and is not a CI result. |
