# Local testing workflow

Use affected checks for quick feedback while editing, then complete the task's
required gates on a stable candidate before handoff. Explicit commands, serial
prerequisites and acceptance gates in the task's plan take precedence over this
guide. Record conflicts in that plan for its owner to resolve; do not silently
revise the plan or close another task's gates.

## Choose checks for the work stage

| Affected area | While editing | Before handoff, when required by the change |
| --- | --- | --- |
| Android domain/data | Relevant JVM tests and persistence/serialization dependencies | Both flavor suites, builds/lints and agreed device checks |
| Shared snapshot, ViewModel or navigation | Relevant state/persistence tests and affected UI methods | Both flavor regressions and agreed combined UI/installed-state checks |
| Backend policy/storage/API | Relevant unittest file/class and callers/contract cases | Relevant complete backend suites and agreed contract checks |
| UI layout/copy/navigation | Affected UI methods, relevant JVM tests and visual inspection | Agreed UI suite, text/IME/rotation or installed checks |
| Instructions/recipes only | Review links/commands and run a bounded recipe smoke check | Review the documentation and cited evidence |

Choose tests for the changed behavior and its callers. During repairs, run the
failed case and affected regressions first. Expand coverage for shared state or
uncertain dependencies. A narrow pass does not complete a broader acceptance
task. Keep remaining gates in the existing task checklist.

Run one Gradle invocation at a time in a shared checkout, including report
capture before the next invocation. Batch compatible tasks. Backend and Android
work may overlap only when outputs, storage, fixtures/ports and command
dependencies are independent; keep separate command results. Commands using the
same backend coverage database must run serially.

## Development logs and handoff evidence

For ordinary development runs, log only **command, outcome and counts**. Do not
collect source hashes, copy reports, review applicability or repeat pending gates
for every edit. Retain a failure report when it helps diagnosis.

For gate results cited at handoff, record:

- Command and working directory, outcome, actual execution status/counts, and
  saved report/log location.
- One shared candidate record: revision plus relevant dirty and untracked source,
  tests, fixtures and build configuration; actual toolchain and target/environment.
  Reuse that record for runs on the same candidate.
- For cited device gates, app/test APK identities and relevant device
  configuration. Reuse unchanged artifact records across commands.
- Verification limits, and elapsed samples for timing comparisons.

Preserve reports before another run can overwrite them when they will be cited
at handoff. Do not archive every focused development run. Exclude credentials
and real user data; never read `.env`. Use temporary test storage and preserve
real availability profiles, generated plans and calendars.

## Review and reuse

Before citing an earlier gate result, compare its relevant source, test, fixture,
build and target/device inputs with the candidate under review. Matching HEAD
alone is insufficient when the working tree was dirty. Unrelated prose edits
can leave runtime evidence applicable. Identify the original result and its
candidate; reused output is not a new test execution.

Rerun affected gates when inputs changed or are unknown, checks failed, evidence
is uncertain, or a task explicitly requires fresh execution. Broaden coverage
for shared state changes. Review reuse never waives the named change's required
gates. Offline unit tests, controlled fixtures, demo UI, live providers and
production/device behavior remain separate evidence.

## Focused JVM and backend recipes

From `frontend/`, use Android Studio's launcher. Repeat `--tests` for each task;
a filter after only the second task does not restrict the first one:

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
bash ./gradlew --daemon :app:testDemoDebugUnitTest \
  --tests 'com.swpp.licenseplanner.domain.StudyWeeksTest' \
  :app:testLiveDebugUnitTest \
  --tests 'com.swpp.licenseplanner.domain.StudyWeeksTest'
```

Check both `app/build/test-results/test{Demo,Live}DebugUnitTest/` XML sets for
the requested class, positive test counts, matching method names and no
failures/errors/skips. A successful command with no selected tests is not a
pass. One flavor can suffice while editing shared code; both-flavor handoff
gates still apply.

Keep incremental outputs. Do not routinely use `clean`, `cleanTest*` or global
`--rerun-tasks`. If fresh execution is required despite unchanged inputs, add
task-specific `--rerun` to each selected test task:

```bash
bash ./gradlew --daemon :app:testDemoDebugUnitTest \
  --tests 'com.swpp.licenseplanner.domain.StudyWeeksTest' --rerun \
  :app:testLiveDebugUnitTest \
  --tests 'com.swpp.licenseplanner.domain.StudyWeeksTest' --rerun
```

Record `UP-TO-DATE` as reused output, not newly executed tests. The local daemon
choice is subject to the comparison below; retain `--no-daemon` as the fallback
if reuse fails or is unreliable, and keep an existing change's explicit command.
Do not change toolchains, memory/cache settings or dependencies, or routinely
stop shared daemons. Build caching is not enabled by this guide.

For a focused offline backend check, from `backend/` with `swpp` activated:

```bash
python -c "import sys; import api_service; print(sys.executable)"
python -m unittest discover -s tests/api_tests -p 'test_study_plan_store.py' -v
```

If activation is unavailable, prefix each command with
`conda run --no-capture-output -n swpp`. Choose the file/class and callers for
the behavior being edited. Live-provider scenarios require separate authorization;
this storage recipe uses temporary data and makes no provider request.

## Focused Compose recipes

First follow the [frontend device preflight](../frontend/README.md#demo-compose-ui-tests),
including exactly one disposable API 35 emulator, explicit `ANDROID_SERIAL`
and diagnostic collection. [CI's emulator setup](../.github/workflows/ci.yml)
disables animations. Local runs with animations enabled are not timing-comparable
with CI. Keep that procedure's device rules:
connected tasks can replace/uninstall packages. Do not use a user's installation.
After preflight, from `frontend/` with the launcher above:

```bash
# Targeted method; ANDROID_SERIAL must already identify the disposable emulator.
bash ./gradlew --daemon :app:connectedDemoDebugAndroidTest --console=plain \
  '-Pandroid.testInstrumentationRunnerArguments.class=com.swpp.licenseplanner.ui.MainFlowUiTest#blocksIncompleteInputsUntilTheyAreCorrected' \
  -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true

# Full class, when required for a broader gate.
bash ./gradlew --daemon :app:connectedDemoDebugAndroidTest --console=plain \
  -Pandroid.testInstrumentationRunnerArguments.class=com.swpp.licenseplanner.ui.MainFlowUiTest \
  -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true
```

These connected examples have **not been executed as written**.
The method exists in `MainFlowUiTest` in `androidTestDemo`.
The explicit demo task and class filter exclude live instrumentation and the
separate `CalendarProviderIntegrationTest`, which performs real provider operations.
Do not substitute aggregate instrumentation. A method pass does not replace
required full-suite, visual or installed-app checks. Preserve cited reports and
collect the README's diagnostics before uninstalling the disposable packages.

Optional: when an authorized UI run happens, record the timing of the targeted
method. Do not run a suite solely to benchmark these recipes. Compare timings
only with matching artifacts and environment.

## Bounded local daemon comparison

JBR 25 launches Gradle, but the checked-in daemon criteria require Java 17.
Even `--no-daemon` therefore starts a separate single-use daemon and stops it
afterward. These commands compare fresh versus reused daemons; they do not
compare in-process execution with a daemon. Android Studio may already have
warmed a compatible daemon. Do not stop shared daemons to manufacture a cold run.

From `frontend/` with the launcher above, the matched commands are:

```bash
bash ./gradlew --no-daemon :app:testDemoDebugUnitTest \
  --tests 'com.swpp.licenseplanner.domain.StudyWeeksTest' --rerun --console=plain
bash ./gradlew --daemon :app:testDemoDebugUnitTest \
  --tests 'com.swpp.licenseplanner.domain.StudyWeeksTest' --rerun --console=plain
```

Observe each mode once, separately from the later samples. Then run three
alternating pairs: fresh/reused, reused/fresh, fresh/reused (eight runs total).
Keep source/configuration and environment stable, avoid competing builds and
pause if another writer changes relevant inputs. Save one candidate record and
each sample's command, wall time, actual task outcome and XML counts/methods
before the next run overwrites the reports. Record launcher/daemon versions and
startup messages. Compare later-sample medians/ranges only after confirming
equal, successfully executed test work.

Adopt `--daemon` locally only when every reused run succeeds with equal counts
and its later-sample median is no slower than the fresh-daemon median. Otherwise
keep `--no-daemon`. Report unstable or unequal work as inconclusive, without a
speedup claim. This small comparison measures one JVM class's local startup
behavior; it establishes no full-build, Compose, CI or end-to-end savings.

The November 14, 2026 local comparison passed all eight runs with the same five
methods, zero failures/errors/skips and actual test execution on every run:

| Mode | First observation | Later median | Later range |
| --- | --- | --- | --- |
| Fresh (`--no-daemon`) | 7.545 s | 4.655 s | 4.621–4.668 s |
| Reused (`--daemon`) | 1.419 s | 1.279 s | 1.246–1.301 s |

This satisfies the adoption rule, so the local recipes above use `--daemon`.
The host used Gradle 9.8.1, JBR 25.0.4 as launcher and Eclipse Adoptium Java
17.0.20.1 as daemon. The smoke check had already warmed the reused daemon; the
first observation is not a cold start. The small sample, host load and warmed
configuration/dependency caches limit the result to this class on this host.
It does not justify changing CI or another change's commands.
