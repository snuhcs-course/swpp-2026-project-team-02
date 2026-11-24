# P12 backend reassessment — implementation and verification

Implemented on 2026-11-25 in local branch `feat/p12-backend-reassessment`, based
on `feat/plan-reassessment` at `2eec6e2ab396d8b1675a0a776b745b45fc48714d`.
The implementation and Windows/Linux verification are described below. No
deployment or actual Android device integration was performed in this task.

## Behavior

The existing Android request/response models work without changes:

- Public `GET /api/v1/certifications/{id}/reassessment-questions` serves the
  explicitly selected separate 18 example questions, with topic, explanation and
  their unchanged draft review status.
- Owner-authenticated `POST /api/v1/schedules/adjust`, `mode: "reassessment"`,
  supports `commit: false` preview and `commit: true` atomic CSV update.
- Server records validate the complete submitted quiz and answer correctness.
  Unknown answers count as incorrect; altered keys/content/scores are rejected.
- Reallocation matches Android's shared fixtures: one minute per topic, remaining
  minutes weighted by `2 - score/100`, largest-remainder rounding, weakest first.
  Every original study interval keeps its coverage. Past/in-progress, reported
  and external rows stay identical. New pieces have fresh IDs.
- Stored plan dates/windows and owner boundaries remain authoritative. Concurrent
  edits return `409 PLAN_CONFLICT`; a revisable session that has started returns
  `422 REASSESSMENT_PREVIEW_EXPIRED`. Preview writes nothing and uses no model.

The full field/error contract is in
[FRONTEND_INTEGRATION_GUIDE.md](../backend/FRONTEND_INTEGRATION_GUIDE.md#reassessment-during-an-existing-plan-p12).
Preview/commit is stateless: the same inputs and unchanged source recompute the
same result. No preview token or quiz history was added to the FE contract.

## Candidate and environment

Windows, Python 3.14.4, local temporary owner storage and loopback HTTP servers;
no live model, production server or Android device was used. Production changes
are `api_server.py`, `api_service.py`, `assessment_service.py`, the question-bank
selection and new `schedule_adjuster/reassessment.py`. Tests add HTTP/domain
coverage and fix an existing reviewed-question test to use the test helper's
IDs 901/902, which had already replaced 19/20 on the base branch.

## Executed checks

Unless stated otherwise, commands run from `backend/`. Raw suite logs and local
coverage artifacts are git-ignored under `backend/coverage-results/`.

| Command | Result | Evidence |
| --- | --- | --- |
| `python -m coverage run --append -m unittest discover -s tests/license_planner_tests -v` | 149 passed | `coverage-results/p12/planner.log` |
| `python -m coverage run --append -m unittest discover -s tests/schedule_adjuster_tests -v` | 82 passed, including 4 reassessment tests exercising all 6 shared FE parity cases | `coverage-results/p12/adjuster.log` |
| `python -m coverage run --append -m unittest discover -s tests/api_tests -p test_reassessment.py -v` | 16 passed | `coverage-results/p12/reassessment-api.log` |
| `python -m coverage run --append -m unittest discover -s tests/api_tests -p test_remaining_study.py -v` | 14 passed | `coverage-results/p12/remaining-api.log` |
| `python -m coverage run --append -m unittest discover -s tests/api_tests -p test_assessment_snapshot_cache.py -k test_invalid_or_deleted_source_fails_until_repaired -v` | 1 passed when isolated | Run result recorded here; isolates a Windows file-replacement error seen in the full run |
| `python -m unittest discover -s frontend/contract_checks -v` (repository root) | 8 passed | Run result recorded here; tests the existing pinned-source contract-check tooling, not a new Android device integration |
| `python -m coverage xml` / `python -m coverage html` | Generated successfully | `coverage-results/coverage.xml`, `coverage-results/html/index.html` |
| `python ../scripts/verify_test_evidence.py --repo-root .. --scope backend` | Passed | Coverage artifact validator; does not imply the entire API suite passed |
| `git diff --check` (repository root) | Passed | No whitespace errors |

The new reassessment module has 100% statement and branch coverage in the
completed coverage runs. This is offline coverage, not deployment evidence.

## Initial API failures and baseline comparison

The attempted full API run on the implementation (before the final two HTTP
edge-case tests were added) ran 250 tests: 3 failures, 6 errors, 4 skips.
See `coverage-results/p12/api-full-initial.log`. A separate pristine worktree at
base commit `2eec6e2` ran 236 tests: 3 failures, 5 errors, 4 skips; its log is
preserved at `coverage-results/p12/api-baseline.log`.

Both runs failed in the same existing areas:

- Concurrent question-bank replacement on Windows (`WinError 5`); the failed
  concurrent test leaves reader threads running and subsequent cache-count
  assertions fail. Both processes needed interruption after the unittest
  summary because those threads kept the process alive.
- Symlink creation lacks Windows privileges (`WinError 1314`), including an
  existing metadata-test cleanup that assumes a symlink was created.
- The old shared-root isolation test expects two entries but sees four.

The implementation run had one additional transient file-replacement error in
`test_invalid_or_deleted_source_fails_until_repaired`; that exact test passed
in isolation afterward. These initial runs were not passing full API gates.

## Follow-up fixes for the full regression suite

The failures originated in test portability and cleanup rather than reassessment
production behavior. The follow-up changes are confined to test code/support:

- The atomic question-bank writer retries only transient Windows access/sharing
  errors (5/32/33), for at most five seconds per replacement. Replacements remain
  atomic; other errors and an exhausted retry budget still fail the test.
- Concurrent readers now stop and join in a `finally` block before temporary
  sources and module patches are restored. This prevents a writer failure from
  leaking threads into later cache-count tests or hanging the test process.
- Device-isolation test paths use `as_posix()` before checking for `/`, so owner
  subdirectories are excluded from the old shared-root snapshot on Windows too.
- Metadata cleanup tolerates a missing symlink after link creation fails, restores
  the original metadata and allows subsequent assertions to execute.
- A shared symlink setup helper explicitly skips unsupported/permission-denied
  environments. Unexpected filesystem errors remain failures. Real link checks
  remain enabled wherever link creation is supported.

Regression checks inject writer failures, transient/permanent replacement errors,
and absent link privileges. They verify reader shutdown, bounded retries and that
unrelated filesystem errors are never silently skipped.

## Windows verification after the follow-up fixes

The final candidate is the same base revision plus the P12 implementation and
the test/support fixes described above. No production behavior was changed by
the follow-up. All commands below completed normally; no process interruption
was needed. Run from `backend/` on Windows / Python 3.14.4:

| Check | Final result | Saved log |
| --- | --- | --- |
| `python -m coverage run -m unittest discover -s tests/license_planner_tests -v` | 149 tests, OK | `coverage-results/p12/planner-final.log` |
| `python -m coverage run --append -m unittest discover -s tests/schedule_adjuster_tests -v` | 82 tests, OK | `coverage-results/p12/adjuster-final.log` |
| `python -m coverage run --append -m unittest discover -s tests/api_tests -v` | 259 tests in 125.186 seconds, OK (skipped=7), zero failures/errors | `coverage-results/p12/api-final.log` |
| `python -m unittest discover -s ../scripts/tests -v` | 4 tests, OK | `coverage-results/p12/evidence-tools-final.log` |
| `python -m unittest discover -s ../frontend/contract_checks -v` | 8 tests, OK | `coverage-results/p12/contract-tools-final.log` |

The API skips are reported by unittest and remain verification limits: real
symlinks cannot be created with this Windows account's privileges, and `chmod(0)`
does not make the metadata unreadable here. No skip was added to the concurrent
reader, cache-count or shared-root isolation assertions; those now pass. Actual
symlink/read-permission scenarios were subsequently verified by the Linux run
below. Android builds/device tests were outside this backend regression run.

The first follow-up full API run found one error in the newly added retry unit
test's unnecessary real `os.replace` after its mocked assertion. Removing that
operation keeps the unit test deterministic; real atomic replacement is exercised
by the concurrent test. The intermediate log is retained at
`coverage-results/p12/api-retry-test-failure.log`; the complete API suite was
rerun after the correction and the final log above supersedes it.

Coverage XML/HTML were regenerated and the evidence validator passed after the
final run. Production statement coverage is 87.13%, branch coverage 83.17%;
`schedule_adjuster/reassessment.py` retains 100% statement and branch coverage.
`git diff --check` also passed. The earlier baseline/failure logs are retained
for diagnosis and do not describe the final candidate's test outcome.

The 18 reassessment examples still await teammate content review. No deployment,
APK build or actual FE-to-server device flow was verified in this task.

## Final Linux verification — zero skips

On 2026-11-25, the current candidate was copied to native Linux storage under
`/var/tmp/swpp-p12-linux-qa-20261125/candidate` in Ubuntu 24.04.4 / WSL2. Tests
ran as UID 1000, not root, using CPython 3.14.10 and coverage 7.16.2. Both source
and temporary test files were on the Linux filesystem, not the Windows mount.
A real symlink probe succeeded and reading a file after `chmod(0)` raised
`PermissionError` before the test suites started.

All 328 source files were SHA-256 checked against the Windows candidate before
execution. The source archive SHA-256 is
`a8af87125f87289c7ee302f36517fced023837b1bbcdfd7e837cf9d248e035c3`.
The source manifest records the same base `2eec6e2`; an isolated local Linux
snapshot commit `b32c002d0d9b48a36732d47e86691bc29dc8277e` allows the pinned-source
contract-tool checks to run. This is a disposable Linux copy, not a commit or
push to the user's working branch. No additional application/test code fixes
were needed for Linux; only this verification record was updated afterward.

The five targeted methods below contain all seven conditions skipped on Windows:

| Targeted real-filesystem check | Linux result |
| --- | --- |
| Question-bank symlink retargeted outside the questionnaire directory | Passed |
| Symlinked device-owner directory | Passed |
| Damaged metadata blocking external cancellation: unreadable file and symlink subcases | Both passed |
| Selected plan CSV symlinks without reading their targets | Passed |
| Exam metadata symlinked outside the store and to another plan's metadata | Both passed |

The Linux runner fails on any unittest skip, including skipped subcases. Each
suite ran in its own Python process; coverage accumulated across the three main
backend suites. The selected methods were also included in the complete API run.

| Suite | Tests | Failures | Errors | Skips | Result |
| --- | ---: | ---: | ---: | ---: | --- |
| Previously skipped conditions (five methods, seven Windows skip conditions) | 5 | 0 | 0 | 0 | OK |
| License planner | 149 | 0 | 0 | 0 | OK |
| Schedule adjuster | 82 | 0 | 0 | 0 | OK |
| Complete API suite | 259 | 0 | 0 | 0 | OK, 102.060 seconds |
| Evidence validator tools | 4 | 0 | 0 | 0 | OK |
| Pinned-source FE/BE contract tools | 8 | 0 | 0 | 0 | OK |

The three main backend suites total **490 tests**, all passing with zero skips.
The targeted five methods are a repeated subset, not five additional unique
backend tests. Coverage XML/HTML generation and backend evidence validation also
passed. Linux production line coverage is 87.19%, branch coverage 83.33%.

Saved evidence is under `backend/coverage-results/p12/linux/` (git-ignored):

- `environment.json`, `source-manifest.json`, `linux-snapshot-commit.txt` and
  `exit-status.txt` (0) identify the runtime, exact source snapshot and success.
- `previously-skipped.log` / `.json`, `planner.log` / `.json`, `adjuster.log` /
  `.json`, `api.log` / `.json`, `evidence-tools.log` / `.json` and
  `contract-tools.log` / `.json` retain executed results and empty skip lists.
- `coverage.xml`, `html/index.html`, `coverage-summary.txt` and
  `evidence-validation.json` retain the Linux coverage/evidence outputs.

The packaging script, strict suite runner and shell orchestration used for this
execution are saved beside those results under `backend/coverage-results/p12/`
as `package_linux_source.py`, `run_linux_suite.py` and `linux_qa.sh`. Linux tests
used `coverage run [--append] <runner> <suite> <result.json>` from the isolated
candidate's `backend/` directory; the runner discovers the same test directories
as the repository CI and additionally treats skips as failures.
