# Testing Documentation

**Owner:** 조준우  
**Iteration:** 3  
**Structure confirmed:** 2026-11-05  
**Reference revision:** `b599e4f` (`test(ci): validate generated evidence reports`)

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

### Measured report summary

| Report | Measured result | Interpretation |
| --- | --- | --- |
| Backend `coverage report` | 1,642 statements; 68% total coverage | Combined offline backend suites. Cobertura XML line rate is 70.77%; branch rate is 58.92%. |
| Android demo JaCoCo | 1,082 / 2,326 lines | Report contains 253 `com.swpp.licenseplanner` classes. |
| Android live JaCoCo | 1,051 / 2,294 lines | Report contains 251 `com.swpp.licenseplanner` classes. |
| Android JUnit | demo 193, live 191; failures 0, errors 0 | One permission-dependent `AtomicFilePlanStoreTest` case is skipped in each flavor on this Windows host. The remote CI job passed. |

CI #48 uploaded `backend-coverage`, `android-reports`, and `android-debug-apks` as
artifacts. The workflow retains test/coverage artifacts for 7 days; debug APKs
are CI evidence only and are not the evaluator's signed release APK.

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

## Current status

| Evidence | Status | Notes |
| --- | --- | --- |
| CI structure and report paths | Passed — CI #48 | [`ci.yml`](../.github/workflows/ci.yml) invokes the reusable report validator for both jobs. |
| Offline backend fresh run | Passed — 2026-11-05 | 57 `license_planner` + 31 `schedule_adjuster` + 62 API tests; total coverage 68%. Reports: `backend/coverage-results/`. |
| Offline Android fresh run | Passed — 2026-11-05 | `testDemoDebugUnitTest` (193 tests, 1 platform-permission skip) and `testLiveDebugUnitTest` (191 tests, 1 platform-permission skip); failures/errors 0. JaCoCo reports and both debug APKs generated. |
| Android ↔ backend request contract | Passed — CI #48 | `verify_request.py --backend-ref HEAD` ran before coverage on the GitHub-hosted checkout. |
| Generated-report validation | Passed — CI #48 | `verify_test_evidence.py` accepted all required JUnit, JaCoCo, and backend coverage reports. |
| Redirect behavior | Offline tests added | `LivePlanningRepositoryTest` verifies generation follows same-origin HTTP 307; `LiveAdjustmentRepositoryTest` verifies adjustment exposes 307 without replaying a state-changing POST. |
| Public-server/device result | Separate evidence | 2026-11-04 release-APK adjustment returned HTTP 200; historical intermittent 307 remains unresolved and is not a CI result. |
