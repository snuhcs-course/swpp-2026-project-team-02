# Frontend

Android app for the License Planner MVP (Kotlin, Jetpack Compose, single `:app`
module, minSdk 26, target SDK 36).

**Flow:** home (계획 생성 / 계획 조정). An explicitly saved draft adds 이어서 만들기.
Creation: select 컴퓨터활용능력 2급 →
enter preparation start and exam dates → choose current level and answer example
questions → set weekday study windows (and optional per-week changes; see
[Study availability](#study-availability)) → generate a plan → browse it by week and
day. Adjustment: pick a plan saved on this device → mark one missed
study session → see the updated plan, or move that session to a time you
choose (시간 직접 옮기기). See [Plan adjustment](#plan-adjustment).
Both the new plan and a reopened saved plan let you record how each study session
went (공부 기록하기); see [Study reports](#study-reports).

**Exam-date change (demo):** when the demo planner shows the insufficient-time
warning, “시험 일정 바꾸기” opens an editor with only the exam date. Confirming a
changed date recalculates straight away and shows a fresh warning or the plan; the
assessment answers and study windows are kept, so there is no need to retake the
example questions or re-enter availability. The original setup screen is unchanged.
Back (or an unchanged date) returns to the warning and discards the unconfirmed
date.

**Scope:** written exam (필기) only; practical exam (실기) is not included. The
assessment shows the backend's configured onboarding selection: 18 questions
sampling both written subjects (컴퓨터 일반 and 스프레드시트 일반, nine each).
They are authored examples, not official exam questions or a validated
diagnostic. Questions the backend stores outside that selection are never
shown, so adding them needs no app change or new APK while the selection stays
the same. The live app rejects a question response (error with retry, inputs
kept) when an item has fewer than two normal choices besides 모르겠음, a reserved
`UNKNOWN` key is misused, or the total possible score is not finite, matching
the backend's checks. Routine study check-ins using further questions are not
implemented.

## Build and run

Open this `frontend/` directory (not the repository root) in Android Studio and
let Gradle sync. Pick a build variant: `demoDebug` (default) or `liveDebug`.

The checked-in Gradle daemon requests Java 17
(`gradle/gradle-daemon-jvm.properties`).
From the command line, using Android Studio's bundled Java runtime:

```bash
cd frontend
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew assembleDemoDebug assembleLiveDebug           # APKs in app/build/outputs/apk/
./gradlew testDemoDebugUnitTest testLiveDebugUnitTest   # unit tests
./gradlew installDemoDebug                              # install on a running emulator
```

## Checks

For local editing, use the [local testing workflow](../docs/testing-workflow.md)
for task-specific JVM filters, incremental/fresh execution, focused Compose
methods and evidence reuse. Its [bounded local comparison](../docs/testing-workflow.md#bounded-local-daemon-comparison)
supports `--daemon` for local recipes on the measured toolchain (five JVM tests;
later medians 1.279 s reused versus 4.655 s fresh). This is one class on one
host with a warmed daemon, not a full-build, Compose or CI speed claim. Keep
`--no-daemon` as the fallback and preserve existing named-change commands and
all required full gates/device recipes.

The commands below reproduce CI; the local recipes do not replace them.
CI runs these in order (Android SDK platform 36 and build-tools 36.0.0,
Python 3.14). `ci.yml` provisions Java 25, while the checked-in Gradle daemon
criteria select Java 17. The separate signed-release workflow provisions Java 17:

```bash
# from frontend/
bash ./gradlew --no-daemon testDemoDebugUnitTest testLiveDebugUnitTest

# from the repository root; uses the request exported by the unit tests above
python frontend/contract_checks/verify_request.py --backend-ref HEAD \
    --request frontend/app/build/contract-check/study-plan-request.json
# offline tests of the check itself (counts, extraction allowlist, failures)
python -m unittest discover -s frontend/contract_checks -p 'test_*.py'

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

### Demo Compose UI tests

`app/src/androidTestDemo/.../ui/MainFlowUiTest.kt` mounts the real navigation,
screens and ViewModel in a standard Compose test activity, using production demo
repositories and the AtomicFile store in a fresh test-owned directory. Sixteen cases cover:

- Home-bound save/discard/stay for visible and system Back; untouched defaults,
  delayed/failed saves and local retry, failed discard and explicit replacement.
- Same-question resume without refetching, an earlier edit moving the checkpoint,
  a replaced availability week with hidden windows and busy rows, and atomic
  plan completion removing the draft. Fresh injected state is not process death.

- Creation through all 18 loaded questions, unknown-answer scores, authored-example
  disclosure, weekday minutes entry, written/demo plan rows, week bounds and home count.
- Disabled progression for missing certification, equal/reversed dates, an unanswered
  question and empty availability; ordinary valid corrections enable progression.
- Replacing a normal answer with unknown and retaining that sole selection through
  forward navigation and the activity's system Back dispatcher.
- Generating, restoring from saved bytes with fresh navigation/ViewModel/store,
  selecting and automatically adjusting a row, and reopening the saved new time.
  Saved-row comparisons confirm only the target times changed; the result card shows
  the topic and the sent and saved rows' full times with 이 기기에 저장했어요;
  restoration does not replay adjustment or retain the card or success marker.
- Showing the outside-window question without manual scrolling, resubmitting the
  retained time for fresh validation and applying only an explicitly confirmed move,
  with the chosen time in the result card.
- Showing usable overnight carry-in during the first empty preparation week and
  a later replaced week.
- Reporting a generated plan's session from a later week: no preselected status, a
  status-required error, done suggesting the scheduled minutes then edited, partly
  with overtime after a rejected nonnumeric entry, cancel keeping the saved report,
  return to the same week, and no planning or adjustment request.
- Reporting a reopened saved plan's session as missed (minutes disabled, zero saved)
  without changing the session selected for adjustment, then reopening it.
- A report save refused by the test-owned store: the edit stays shown as unsaved
  with the earlier report kept on disk, a local 다시 저장하기 saves it once without a
  request, and the report follows its session through an adjustment and reopening.
- An adjustment whose save the test-owned store holds and then refuses: the result
  card stays with 기기에 저장하는 중이에요 (no saved wording or retry yet), then
  아직 이 기기에 저장하지 못했어요 with the single storage-alert 다시 저장하기 and the
  old rows on disk, then 이 기기에 저장했어요 after a local retry with one adjustment
  and no further request.

No backend, model, calendar permissions, account or events are needed. Fixed input
dates, manual-move clock and host locale/timezone make the cases repeatable. Each test removes only
its own plan directory after disposing navigation and clearing its ViewModel owner.
This injected host bypasses `MainActivity.Factory`; it does not verify production
activity wiring, process death, live Compose behavior or physical-device behavior.

Before installing or running connected tests, use `adb devices -l` and confirm
exactly one attached, disposable API 35 AVD (the CI baseline). The current
Espresso dependency fails input-injection initialization on the local API 37 image.
Extra, offline, unauthorized or physical devices block this procedure. Connected tasks may replace/uninstall packages;
never use a device containing a user's app installation. Then run from `frontend/`:

```bash
export ANDROID_SERIAL=<disposable-emulator-serial>
bash ./gradlew --no-daemon :app:connectedDemoDebugAndroidTest --console=plain \
  -Pandroid.testInstrumentationRunnerArguments.class=com.swpp.licenseplanner.ui.MainFlowUiTest \
  -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true
```

The leave-installed option retains only these disposable test packages so private
diagnostics can be pulled after Gradle finishes; stop/delete the throwaway AVD after
collecting evidence. Do not enable it on a user device.

For one case, append `#<methodName>` to the class filter. In Android Studio select
`demoDebug` and run this class under `app/src/androidTestDemo` as instrumented tests
on the disposable AVD. The class is absent from live instrumentation. The shared
`CalendarProviderIntegrationTest` remains separate; the class filter above excludes
it and avoids its real provider operations.

Demo XML results are under `app/build/outputs/androidTest-results/connected/debug/flavors/demo/`
and HTML reports under `app/build/reports/androidTests/connected/debug/flavors/demo/`.
On assertion failure, the test writes a semantics tree and screenshot to the demo
app's private `files/compose-ui-diagnostics/<method>/failure.{txt,png}`. Creation and
adjustment also save representative screenshots there for visual inspection. Pull
only this test-owned directory before any uninstall, from `frontend/`:

```bash
mkdir -p app/build/compose-ui-diagnostics
adb -s "$ANDROID_SERIAL" exec-out run-as com.swpp.licenseplanner.demo \
  tar -C files/compose-ui-diagnostics -cf - . | tar -xf - -C app/build/compose-ui-diagnostics
```

The existing gated `android-live-e2e` job runs this filtered demo suite and the live
suite on the same API 35 emulator, attempts both despite assertion failures, and
fails if either fails. Its always-run evidence upload preserves separate reports,
explicitly pulled UI diagnostics and fixture logs for seven days. A local pass
does not establish a hosted run; hosted execution remains unverified until observed.

### Live instrumentation test

`app/src/androidTestLive/.../data/LiveBackendE2eTest.kt` sits in the live flavor's
source set, so only `connectedLiveDebugAndroidTest` discovers this API test;
demo instrumentation contacts no server. It loads the 18 onboarding questions, submits all of them
(some answered 모르겠음), checks that the plan covers the topics of both written
subjects, adjusts one study session, checks that the whole moved session ends
by the plan's exam date and that no other row changed, and reads the plan back
by its exact identity. The backend is
`backend/test_support/fixed_live_e2e_server.py`: the real API handler,
scheduler, adjustment loop and plan store with fixed model replies and no
OpenRouter access (see `backend/README.md`). Its default case,
`selected_onboarding_reviewed_topics`, is the synthetic complete-selection
trace this test needs. The synthetic `original_five_reviewed_topics` and
`mixed_reviewed_and_custom_questions` cases (selected with `--case-id`) cover
the original five questions and a reviewed-plus-custom request for backend
tests; this instrumentation test does not use them. Without that server the test
fails rather than skipping. Use a disposable emulator:

```bash
# from backend/
python test_support/fixed_live_e2e_server.py --host 127.0.0.1 --port 8000
# in another terminal
adb -s <test-device> reverse tcp:8000 tcp:8000
# from frontend/: live API test (shared provider tests require their own device setup)
bash ./gradlew --no-daemon connectedLiveDebugAndroidTest -PlicensePlanner.apiBaseUrl=http://127.0.0.1:8000/api/v1
```

For manual controlled-live checks on a disposable emulator (current dates,
availability load/save, manual moves), start the same fixture with
`--planning scripted` and the isolation options described in
`backend/README.md` (Fixed-response API fixture): `--storage-dir`,
`--profile-seed`, `--now`, `--request-log` and `--test-control`. It never
touches `backend/config/user_availability.json` or the real plan output, and
calls no model:

```bash
# from backend/
python test_support/fixed_live_e2e_server.py --host 127.0.0.1 --port 8000 --planning scripted \
  --storage-dir "$RUN/storage" --profile-seed "$RUN/seed.json" --now 2026/11/18/12/00 \
  --request-log "$RUN/requests.jsonl" --test-control "$RUN/control.json"
# in another terminal
adb -s <test-device> reverse tcp:8000 tcp:8000
# from frontend/
bash ./gradlew --no-daemon assembleLiveDebug -PlicensePlanner.apiBaseUrl=http://127.0.0.1:8000/api/v1
```

CI's `android-live-e2e` job preserves the `connectedLiveDebugAndroidTest` command above alongside the filtered demo
UI suite on an API 35 emulator. The live suite exercises repositories/API rather
than Compose screens. It supplies controlled integration evidence, not evidence
about the model or the deployed server.

## Flavors

| Flavor | Needs backend | Questions | Plans | Adjustment |
| --- | --- | --- | --- | --- |
| `demo` (default) | No | Bundled copy of the 18-question onboarding selection (both written subjects) | Local deterministic planner, labeled "demo" on every screen | Local rule, no network |
| `live` | Yes | `GET /certifications/computer_specialist_level_2/questions` | `POST /study-plans` (OpenRouter `openai/gpt-6-luna`, server-side) | `POST /schedules/adjust`; `GET /study-plans` only to reload the selected plan |

**Use `demo` for evaluation without a backend.** Its plans are a demonstration
calculation, not backend or AI output. Demo workload: each topic shown on the
score screen gets its own session count, one base session plus one extra when
its score is below 60%. Each part is multiplied by the stricter of the chosen
level and the overall-score level (80% advanced ×0.5, 60% intermediate ×1,
otherwise beginner ×2) and rounded up separately, so an advanced learner still
gets a weak topic's extra session. Extra sessions go to the weak topics that
earned them. All 18 answered 모르겠음 at beginner level needs 32 sessions (four
per topic); all 18 correct at advanced level needs eight (one per topic). These
are demonstration assumptions for a practical evaluation journey, not the live
model's allocation or an estimate of the preparation the exam needs; a perfect
score keeps every topic and does not mean mastery. When the sessions do not fit,
the demo shows the required and available counts and no partial plan. The live flavor never falls back to demo
data: on failure it shows an error with retry and keeps all inputs.

## Live backend

The live flavor connects to this repository's backend. Start it from `backend/`
with the project's Python environment and `OPENROUTER_API_KEY` configured in
the backend process environment or `backend/.env`:

```bash
conda activate swpp
cd backend
python api_server.py    # http://127.0.0.1:8000/api/v1; OPENROUTER_API_KEY on the backend
```

The app's default API URL is `http://10.0.2.2:8000/api/v1`, which reaches the
host machine from the Android Emulator. To use a different backend URL, build
the live variant with:

```bash
./gradlew assembleLiveDebug -PlicensePlanner.apiBaseUrl=http://127.0.0.1:8000/api/v1
```

For a USB-connected device, run `adb reverse tcp:8000 tcp:8000` and set the
build property to `http://127.0.0.1:8000/api/v1`. Debug builds allow cleartext
HTTP to `10.0.2.2`, `127.0.0.1`, `localhost`, and the assigned deployment host
`swpp26t02.147-46-242-174.sslip.io`. Release builds allow cleartext HTTP only
to that deployment host. API keys stay in the backend; the app never calls the
model provider directly. The live API contract is unchanged by the model choice.

The signed release APK for the public endpoint is built by the `Prod APK`
workflow; its artifact, signature check and installation are described in the
[deployment guide](../deploy/README.md#release-apk). Record the workflow run and
source commit with any result, since a local debug build is a different APK.

### Physical device against the public server

A live debug build can target the public server directly:

```bash
./gradlew :app:assembleLiveDebug -PlicensePlanner.apiBaseUrl=http://swpp26t02.147-46-242-174.sslip.io/api/v1 --no-daemon
adb install -r app/build/outputs/apk/live/debug/app-live-debug.apk
```

Never install a default live build (pointing at `10.0.2.2`) on a physical
device; it cannot reach a backend. A debug build is signed with the local debug
certificate, not the release certificate, so installing it over a release build
(or the reverse) fails with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`. Use a separate
test device or profile, or uninstall the old app only after accepting the loss of
its device-local plans. The public server is shared and unauthenticated; plans
created there are visible to anyone who lists them.

### Live release builds

Before packaging, `assembleLiveRelease` and `bundleLiveRelease` check
`licensePlanner.apiBaseUrl`. It must be an absolute `http://` or `https://` URL
with a host. HTTP is accepted only for a host that
`app/src/release/res/xml/network_security_config.xml` permits for cleartext
(currently `swpp26t02.147-46-242-174.sslip.io`). Host case, port, and a trailing
slash do not matter. Any HTTPS host is accepted; the build does not contact it,
so passing does not prove that the server is reachable or its certificate valid.
The default emulator URL, `localhost`, the raw server IP, and other HTTP hosts fail
the build with the allowed host named. Debug and demo builds are not checked.

```bash
./gradlew assembleLiveRelease -PlicensePlanner.apiBaseUrl=http://swpp26t02.147-46-242-174.sslip.io/api/v1
```

Release signing uses four properties: `licensePlanner.releaseStoreFile`,
`licensePlanner.releaseStorePassword`, `licensePlanner.releaseKeyAlias`, and
`licensePlanner.releaseKeyPassword`. With none of them set, the release is
unsigned: `app/build/outputs/apk/live/release/app-live-release-unsigned.apk`,
which cannot be installed as is and is not the evaluator APK. If any of them is
set, all four must be nonblank. Otherwise Gradle stops during configuration, for
every task, and names the missing or blank properties without printing their
values. Pass signing values only from a protected source such as CI secrets. The
`Prod APK` workflow requires all four secrets and verifies the APK signature.

Planning calls the model synchronously and can take tens of seconds (request timeout
120 s). The matching routes and JSON contract are documented in
[`backend/FRONTEND_INTEGRATION_GUIDE.md`](../backend/FRONTEND_INTEGRATION_GUIDE.md).

**Connections.** Every API request is sent with `Connection: close`, so each
exchange opens its own TCP connection. The backend (Python's default HTTP/1.0
request handler) closes the socket after every response without a `Connection`
header; reusing that socket shortly afterwards made an adjustment sent right
after another response fail before reaching the server, which the app then
had to treat as an unknown result. The extra connection per request is
acceptable for local use. Nothing else changed: there is still no automatic
retry, and a lost or timed-out adjustment still needs 최신 계획 불러오기 (see
[Plan adjustment](#plan-adjustment)).

### Validation errors

An HTTP 422 is shown in Korean, never as the backend's English text. The
backend's `error.message` stays in the error value for debugging but is not
displayed. Generation, question loading, adjustment and reload share one mapping
that recognizes only exact backend messages or tightly anchored patterns:

| Backend cause | Shown explanation |
| --- | --- |
| Invalid or missing `exam_date` / `preparation_start` | 시험일 (or 준비 시작일) 을 확인할 수 없어요 |
| `preparation_start must be before exam_date` | 준비 시작일은 시험일보다 빨라야 해요 |
| No study windows | 공부할 수 있는 시간이 등록되지 않았어요 |
| A weekday window with a bad time, reversed order, or overlap | The weekday in Korean (for example 수요일) and the problem |
| `NOT_ENOUGH_TIME:` with a current backend capacity sentence | 시험일 전까지 공부할 시간이 부족해요, plus the usable minutes and topic count. A zero-minute early check gives no topic count. |
| `NOT_ENOUGH_TIME:` with any other text | The same headline without numbers |
| Exactly `This saved plan has no stored exam date. Create a new plan before adjusting study sessions.` | 이 계획에는 시험일 정보가 저장되어 있지 않아요. / 목록으로 돌아가 새 계획을 만든 뒤 다시 조정해 주세요. |
| Assessment-results payload checks | 답안 정보를 서버에서 처리하지 못했어요, with a note that it does not mean a wrong answer |
| Anything else, including blank, technical, or path/trace text | 요청 정보를 확인하지 못했어요 |

The next step depends on the screen. On the generation error it points to
“입력 수정하기”. An adjustment or reload uses the selected plan's own saved
windows, so editing the form cannot change them; there the message says to go
back with 목록으로 and create a new plan with corrected input. Capacity numbers
are shown only when they agree with the backend sentence. No count is inferred
from the number of questions. Retry, reload and retained inputs, rows and
selection behave as before. 다시 시도 is always explicit, and none of these
messages claims that an unchanged retry fixes the input.

## Plan adjustment

Home offers 계획 생성 (the existing creation flow, with earlier inputs kept)
and 계획 조정 (a list of the plans generated and saved on this device). The first
creation step (자격증) has a top-bar back arrow that, like system back, returns
home without sending a request or clearing the inputs. Each
successful generation adds one entry, numbered 계획 1, 계획 2, … even when dates
match. Editing the form or a failed generation never removes an entry. The app
never imports plans from the server; it reads the server's plan list only to
reload one plan it already has (see below).

**Local storage.** The list is saved on the device, so it survives rotation,
navigation, closing the app and process death. Each entry keeps its number,
original creation request (dates, assessment, windows), current rows, CSV text
and summary, demo/live identity, and reload/missing state with its retained
session. The whole list is one versioned JSON snapshot, replaced atomically
(`AtomicFile`) in the app's private no-backup directory; backup stays disabled.
The demo build and each live API base URL (`licensePlanner.apiBaseUrl`,
trailing slash ignored) keep separate lists, so switching backends never shows
another server's plans, and switching back restores the earlier list.
Uninstalling or clearing app data removes the list; plans lost that way, or
created before this storage existed, cannot be recovered from the server,
whose list lacks the original creation inputs. Form input that was not
explicitly saved as a draft, navigation and the moved-session tag are not saved.

**Snapshot versions.** Saving writes snapshot version 4. Each entry keeps that
plan's study-session reports (status and actual minutes) by study-row ID;
identical IDs in two plans never share a report, and a report whose row is no
longer in the plan is kept but not shown as a current session's. Next to the
entries, version 4 has an optional slot for one unfinished plan creation: the
entered inputs (incomplete or not yet valid ones included), the loaded example
questions with their answers, the input screen to reopen, the question position
and the selected availability week. Keeping it in the same file lets a generated
plan and the removal of its draft be saved in one atomic replacement. A stored
draft is checked only for values the input screens could hold; a missing exam
date, dates in the wrong order or no study day stay editable input, while a
damaged or unknown draft value makes the whole file fail to load like any other
corruption.

**Creation draft position and scope.** The resume position is the furthest
input screen reached, or the screen of the latest actual edit: changing a level,
date or answer after reaching availability moves it back to that screen (and
question), while merely going back through earlier screens or questions to leave
does not. A generation failure or demo shortfall counts as availability, and the
selected availability week is kept with it. Only an explicit save is kept: there
is no autosave, edits after the last save are lost if the process ends, and a
save, discard or retry finishes writing before creation is left or reset. A
failed write keeps the earlier saved file and the current inputs; 다시 시도
writes the same draft again without any request, and staying drops that write.
Resuming restores the saved inputs, loaded questions and answers without
fetching them again, keeps dates that have since passed for correction, and opens
the first study week if the saved week is no longer one. Imported calendar busy
rows are restored as the saved snapshot and are not read again.
Draft device acceptance uses the demo flavor on a disposable API 35 emulator,
including normal-launcher force-stop checks for quiz and replaced-week drafts.
Installed write failure/interruption remains unverified; those paths use controlled
JVM and injected-host stores. Live provider, production, physical-device and calendar
behavior are not established by these checks.
Home offers 이어서 만들기 only when an explicitly saved draft exists; it does not
count as a saved plan. 계획 생성 (also 새 계획 만들기 from saved plans) asks whether
to resume, discard the saved inputs and start fresh, or cancel. Only Back from
certification to Home asks 임시 저장하고 나가기 / 입력 버리고 나가기 / 계속 작성하기;
ordinary step/question Back is unchanged. Visible and system Back agree, and
Back on the decision means stay. Saving/discarding holds navigation until the
local write succeeds; failure offers 다시 저장하기 or stay with the prior bytes intact.

A successful generated plan and clearing its consumed draft are committed together.
Generation failure or demo shortfall leaves the last saved draft available. If the
outcome write fails, the result stays unsaved in memory and local retry writes the
same plan plus draft removal without another generation. Ending the process before
that succeeds restores the earlier saved draft, not the unsaved result or request.

Version 1, 2 and 3 lists saved by earlier builds still load (versions 1 and 2
as plans without reports), always without a draft, and the file is not
rewritten until the next save of the list, which writes version 4. An older
build cannot read a newer version and shows the load failure above without
changing the file, so downgrading after a version 4 save needs a compatible
backup of the earlier file; nothing is converted back automatically.

At start the app reads the saved list before anything can change it. While it
reads, home and the list show 저장된 계획을 불러오는 중이에요 instead of a count or
an empty list. If the saved data cannot be read (damaged, or written by a newer
version), they show 저장된 계획을 불러오지 못했어요 with 다시 불러오기, which reads
the file again and sends nothing. The file is never deleted, reset or
overwritten, and generating, adjusting and reloading stay unavailable until it
loads. Once loaded, home shows 저장된 계획 N개가 있어요.

The whole list is saved after every generation, adjustment (live or demo),
reload, and change to a reload-required or missing state. Until that save
finishes, generating, adjusting and reloading wait. If it fails, the new result
stays on screen but may not survive closing the app: the screen shows
기기에 계획을 저장하지 못했어요 with 다시 저장하기, home compares the shown and
saved counts, and changed entries in the list carry 저장 안 됨. 다시 저장하기 only
writes the local file; it never repeats the generation, adjustment or reload.
A new plan whose save failed stays on the generation screen, which also offers
저장하지 않은 계획 보기. Plans keep their numbers, and the next generation gets the
next number after the highest saved one.

**Saving a study report.** A report is written only by an explicit save of the
report editor, which replaces that plan's report for that one study row and
saves the whole list the same way; cancelling before saving writes nothing.
Opening or saving a report never sends a generation, adjustment, reload or
calendar request, and does not change the plan's rows, adjustment selection or
reload/missing state, so a plan awaiting reload or missing can still be
reported. A report cannot be saved while a generation, adjustment, reload,
calendar export or another save is running, and a second tap while saving does
nothing. If the save fails, the edited report stays shown but is not saved: the
list is in the save-failure state above, other plan changes wait, and 다시 저장하기
writes only the local file. Reports stay on this device in the same per-build
list; they are not sent to the server, shared between devices or kept after
uninstalling.

**Closing the app during a request.** A live adjustment, including 다시 시도,
is sent only after the plan has been saved as needing a reload, together with
the chosen session; if that save fails, nothing is sent and the selection waits
for a new tap. If the app is closed or killed before the result is saved,
reopening the plan shows its earlier rows, the chosen session selected but
disabled, and 최신 계획 불러오기, whether or not the server applied the change.
The app never resends it or reloads by itself. A reload interrupted the same way
stays required. A confirmed failure (422, 503, …) is saved as it is, so after a
restart the plan can be adjusted again with a new selection; the old retry is
not kept. A generation that had not returned is neither added nor repeated,
even if the server created a plan file. Demo work only changes the device and
restarts from the last saved list.

On a plan, choose exactly one missed study session (`study-N`); existing
schedules (`external-N`) are shown but cannot be chosen. A live plan whose
generation response had no `schedule_csv_file` can be viewed but not adjusted.
A plan with no study sessions explains that and offers a way back. After a
successful adjustment the same entry is updated in place, the view jumps to the
moved session's week, and another session can be adjusted from the new rows.
The moved session carries a “옮긴 일정” tag on this screen only. The tag stays
through rotation and clears when another session is chosen, the plan is opened
again from the list, or another plan is opened. Nothing about it is stored, and
the generated-plan screen never shows it.
Other plans never change, even when they share schedule IDs.

**Accepted versus saved.** An accepted move (automatic or chosen time) and
saving it on this device are separate steps. With the result, the app keeps the
moved session's row as it was sent (so a session moved earlier starts from its
latest time, not its generated one) and as it was accepted, with the returned
topic, full dates and start/end times; it never reads them from the server's
summary. That pair has the same lifetime as the tag above, and a restart, a
reload or a failure never recreates it. The result also tracks its own local
save: saving while the automatic save of the changed list runs, saved only once
that save has written this result, and not saved when it failed. A failed save
keeps the moved rows on screen, holds other plan changes, and 다시 저장하기
writes only the local file, without another adjustment or generation; a later
save of something else (for example a study report) never changes what is
known about this move. Lost or invalid live responses, failed pre-request saves
and missing plans never produce a move result: they follow the recovery rules
below. If the app ends before the result is saved, the plan restarts behind
최신 계획 불러오기 with its pre-request rows, as described above, without
claiming either rows are on the server and without resending. These rules are
checked by JVM tests that start a fresh view model over the same stored bytes;
they do not kill a real process.

On screen, the adjustment result card under 계획이 조정됐어요 shows the returned
topic, 원래 시간 and 바뀐 시간 as full dates with weekday and start–end times (an
end on another day names its own date), and the save line 기기에 저장하는 중이에요,
이 기기에 저장했어요 or 아직 이 기기에 저장하지 못했어요. The server summary stays a
separate 계획 요약 box. There is no save button on the card: a failure is explained
by the storage alert above it, whose 다시 저장하기 is the only retry. The demo UI
tests delay and refuse saves only in their own injected store; they do not show
a real disk failure, process death or the live flavor.

While an adjustment request runs, the screen shows progress; system back,
the top-bar back, and every other action are held, and rotation keeps the same
request. A second tap sends nothing. On failure the plan and the chosen session
stay as they were. There is no automatic retry and no demo fallback in the live
build. What follows depends on the failure:

| Live failure | Message | Next step |
| --- | --- | --- |
| 422, 500, 502, 503, other statuses | 422: Korean explanation of a recognized cause (see [Validation errors](#validation-errors)), or 요청 정보를 확인하지 못했어요; other errors use app-defined wording | 다시 시도 resends the identical request; choosing another session builds a new one |
| 404 without `PLAN_NOT_FOUND` (wrong URL or old server) | API 경로를 찾을 수 없어요 … (HTTP 404) | 다시 시도, as above; the plan is not treated as deleted |
| 404 `PLAN_NOT_FOUND` | 서버에서 이 계획을 찾을 수 없어요 … | The plan stays viewable but cannot be adjusted, also after a restart; 목록으로 or 새 계획 만들기 |
| 409 `PLAN_CONFLICT` | 서버의 계획이 변경되었어요 … | 최신 계획 불러오기 |
| Any other 409, lost connection or timeout, unreadable or mismatched 200 | 조정 결과를 확인할 수 없어요. 서버에 반영되었을 수 있으니 … | 최신 계획 불러오기 |

The last two rows mean the server's copy is unknown (a timed-out request may
already have been saved), so the app never resends that request. The plan
keeps showing its earlier rows and selection as an unconfirmed copy, and the
plan cannot be adjusted (neither 다시 시도 nor a new selection) until a reload
succeeds. This requirement stays on the plan when the user picks another row,
rotates, goes back to the list or home, or adjusts another plan and returns.
The session whose request is uncertain stays with the plan too: reopening it,
even after visiting another plan with the same schedule IDs, opens that
session's week with the session selected but disabled, without the earlier
error or a retry; the other weeks can still be browsed. Another
plan's selection or outcome is never carried over, and switching between plans
that need no reload still starts with no selection.
Screen entry and rotation send nothing; only the explicit button does.
Whenever study sessions are shown as choices but cannot be chosen (for
example while a request runs or a reload is required), their cards turn grey
and read 선택 변경 불가;
the chosen session keeps its marker, and existing schedules stay plain rows.

**Reload.** 최신 계획 불러오기 sends one
`GET /api/v1/study-plans?schedule_csv_file=<identity>` (the plan's identity,
URL-encoded once) and nothing else; there is no fallback request. The app
takes the single item whose `schedule_csv_file` equals the plan's
own identity exactly (no filename, suffix, order or schedule-ID matching; other
plans are never imported) and checks its rows the same way as an adjustment
response (nonblank unique IDs, `YYYY/MM/DD/HH/MM` timestamps, end after start).
Unknown fields and the CSV URL are ignored. While it runs, back and every other
action are held, a second tap does nothing, and rotation keeps the same request.

- Success replaces only that plan's rows in place (shown with 최신 저장 계획을
  불러왔어요), keeps its number, identity and original creation inputs
  (windows, dates), drops the old CSV text and AI summary that no longer
  describe the rows, clears the old selection and request, and shows the first
  week. A new adjustment needs a fresh selection and an explicit tap, even for
  the same row. An empty or external-only saved plan is valid and shows the
  no-study state. This is not an adjustment success and does not show which
  request changed the plan.
- A valid list without that identity (including an empty list) marks the plan
  unavailable, as for `PLAN_NOT_FOUND`; similarly named plans are never used.
- Any other failure (route 404, other statuses, connection or timeout,
  malformed JSON, missing `plans`/`schedules`, two matching items, invalid
  rows) keeps the earlier rows and the reload requirement; 최신 계획 다시
  불러오기 tries the GET again and return navigation is available.

**Demo:** a local demonstration rule, not the backend's policy and not AI
output. The session moves to the first free interval inside the plan's
original weekday windows that starts after both its own end and the latest
other session with the same topic, and ends before the original exam date. If
nothing fits, the app reports it and keeps the plan and the selected session.
Submitting the same session again is disabled (다른 일정을 선택해 주세요),
because it would give the same result. Choosing a different study session
allows a new attempt, and 목록으로 and 새 계획 만들기 are offered. The plan's
own windows never change; a new plan can use different study times. Nothing is
sent to a server; the moved rows are saved on the device like any other change.

**Chosen time (시간 직접 옮기기).** Next to 선택한 일정 조정하기, the
adjustment screen offers a separate 시간 직접 옮기기 for the selected study
session. It is enabled only when that plan could also be adjusted
automatically: a study session (never an external row) is selected, the plan
is ready (not awaiting reload, not missing, with a live identity), the
device list is saved, and no request is running. It opens 시간 직접 옮기기
(top bar 계획 조정) with the session's topic, a 날짜 field and a 시작 시간 field
that open the existing date and time pickers, the original duration as
read-only text (기존 공부 시간 · 60분 (그대로 유지)), and a preview of 기존 일정 and
옮길 일정 as complete intervals (`11월 29일(일) 23:30–다음 날 00:30`). Fields start
at the session's current time; 이 시간으로 옮기기 stays disabled
(날짜나 시작 시간을 바꿔 주세요) until another time is chosen. Back or the top-bar
arrow discards the draft without a request and returns to the same plan,
week and selection. While the request runs, back and every action are held
(옮기는 중…), and rotation keeps the draft and the request without sending it
again, because the draft lives in the ViewModel.

The app first sends the chosen time without the exception. If the time is
otherwise valid but outside the plan's study windows, the screen asks
설정한 공부 시간 밖이에요. with the exact interval (… 이번 일정만 옮길까요? 설정한 공부
시간은 그대로예요.) and scrolls the question into view. 이번 한 번만 옮기기 resends that same time once with the
exception, and 시간 다시 고르기 returns to the fields with the time kept and
nothing sent (sending the same time again is checked from the start). Any
change to the date or time clears an earlier question or exception. Other
refusals are shown under the field they concern, keep the chosen time and
disable 이 시간으로 옮기기 until the time changes; no refusal can be bypassed:

| Server code | Shown under | Message |
| --- | --- | --- |
| `MANUAL_MOVE_IN_PAST` | 날짜 | 이미 지난 시간이에요. 앞으로의 시간을 골라 주세요. |
| `BEFORE_PREPARATION_START` | 날짜 | 준비 시작일 전이에요. 준비 기간 안에서 날짜를 골라 주세요. |
| `EXAM_BOUND_EXCEEDED` | 시작 시간 | 공부가 시험일을 넘겨요. 더 이른 시간을 골라 주세요. |
| `STUDY_SESSION_OVERLAP` | 시작 시간 | 다른 공부 일정과 겹쳐요. 다른 시간을 골라 주세요. |
| `EXTERNAL_EVENT_OVERLAP` | 시작 시간 | 캘린더에서 가져온 일정과 겹쳐요. 다른 시간을 골라 주세요. |
| `OUTSIDE_STUDY_WINDOWS` | (question above) | one-time confirmation only |

Every other outcome follows the automatic rules above: a definite failure
(for example HTTP 500 or 503) keeps the chosen time and offers 다시 시도, which
resends exactly the reviewed request; 404 `PLAN_NOT_FOUND`, any 409, a lost
response or timeout, and a 200 that does not show exactly the target at the
chosen time with the same topic and duration (and every other row unchanged)
close the editor and require 최신 계획 불러오기, after which a session and time
must be chosen again; nothing is replayed. The unresolved-request snapshot is
saved before a live request as for automatic moves, and a failed save sends
nothing and clears any exception. Success returns to the adjustment screen
with 계획이 조정됐어요 and the moved session's week. The demo applies the same
checks locally against the plan's original windows and the device clock and
labels the editor 로컬 시연 계산. Neither flavor writes the device calendar or the
shared availability profile. Live requests add `"mode": "manual"`,
`new_start_date`, `confirm_outside_availability` and the plan's
`preparation_start` to the fields below.

**Live contract.** The app always sends five fields to
`POST /api/v1/schedules/adjust`: the plan's exact `schedule_csv_file` from
generation, the chosen `schedule_id`, every current row (external rows
included), and the `weekly_availability` and `exam_date` from that plan's
original creation request (not the current form or another plan):

```json
{
  "schedule_csv_file": "license_planner/output/study_plan_20261016T180000000000+0900_abcdef12.csv",
  "schedule_id": "study-1",
  "schedules": [
    {"schedule_id": "study-1", "start_date": "2026/10/19/18/00", "end_date": "2026/10/19/19/00", "topic": "함수와 데이터 관리"},
    {"schedule_id": "external-1", "start_date": "2026/10/22/18/00", "end_date": "2026/10/22/19/00", "topic": ""}
  ],
  "weekly_availability": {"monday": [{"start": "18:00", "end": "20:00"}]},
  "exam_date": "2026/11/16/00/00"
}
```

The backend in this checkout stores each new plan's exam timestamp when it
generates the plan and always uses that stored value for study adjustment. The
`exam_date` the app sends must equal it; a different value returns 422. Other
API clients may omit `exam_date` for such plans, so earlier app releases that
send only the first four fields can still adjust newly generated plans. The
backend's non-persistent mode (no `schedule_csv_file`, which the app never uses)
requires a valid `exam_date` for a study move and answers 422 `INVALID_INPUT`
when it is missing, null or malformed. Whether the deployed server and earlier
released APKs behave this way has not been verified from this checkout. A plan
saved before the server stored exam dates cannot have its study sessions
moved, even though the app sends a date: the server answers 422 with the fixed
message in [Validation errors](#validation-errors). The current app then shows
이 계획에는 시험일 정보가 저장되어 있지 않아요. 목록으로 돌아가 새 계획을 만든 뒤
다시 조정해 주세요. in the existing error panel. Every plan, its rows, the
selection and the original inputs stay as they were, and nothing is sent
automatically. 다시 시도 follows the usual rules and gets the same answer;
목록으로 and then 새 계획 만들기 is the way forward. Old plans stay viewable.
Earlier app releases do not know this message and show the general
요청 정보를 확인하지 못했어요 instead; only an app update changes that.

The app accepts a 200 response only if `action` is `rescheduled_study`,
`target_schedule_id` matches, rows parse with end after start, IDs are
nonblank and unique, the target row is present, and any returned
`schedule_csv_file` equals the one sent. Anything else leaves the plan as it was.
Adjustment never reads or writes the shared availability profile; only the
explicit study-time actions in [Study availability](#study-availability) do.

**Backend dependency** The backend in this checkout (merged from `main` at
`11c822e`, plus the stored exam deadline described above) updates the selected
plan file before it returns success and keeps the plan's stored exam date. It uses
supplied valid availability instead of the saved profile; omission keeps the
saved-profile fallback. Explicit null, invalid or all-empty windows return 422
without changing the plan or profile. Android always sends the selected plan's
original non-null windows. It returns 404 `PLAN_NOT_FOUND` for a pruned plan
and 409 `PLAN_CONFLICT` when the sent rows no longer match the stored file,
and lists stored plans (up to about 100, newest first) at
`GET /api/v1/study-plans` as
`{"plans":[{"schedule_csv_file":"…","schedule_csv_url":"…","schedules":[…]}]}`.
With the `schedule_csv_file` filter it reads only that plan: the list holds that
one item, is empty when the plan no longer exists, and fails (500
`STORAGE_ERROR`) only when that plan itself is unreadable, so a damaged
unrelated plan no longer blocks reload. An older server that ignores the filter
returns the full list, which the app matches the same way, but there an
unreadable unrelated plan still makes the whole list fail (500). An older server
without the list route answers the reload with a route 404, which the app
reports as such and keeps the plan blocked. The client recovery flow above is
covered by MockWebServer and scripted-server checks. Later deployment and
physical-device records cover real OpenRouter generation, viewing, adjustment,
restart retention, and reload after a lost adjustment response. They do not
attach the full two-plan same-file/CSV/original-window isolation procedure;
that detailed gate remains unverified. See [Verification](#verification).

## Study availability

The study-time screen (공부 시간) edits one visible window per weekday: a start
time (time picker) and a duration in minutes. The steppers change the duration in
30-minute steps; tapping the minutes box opens 공부 시간 입력, which accepts any
whole number from 1 to 1439 and shows the derived end before it is applied. A
window that runs past midnight shows its end as `– 다음 날 00:30` and belongs to
the weekday it starts on. Lowering a day to 30 minutes or less with − turns that
day off (0분).

**Retained windows.** A day can hold more windows than this editor shows (for
example from a loaded profile). The earliest window is the visible one; the rest
are kept unchanged and counted under the day as 추가 공부 시간 N개 유지. Editing
or turning off the visible window never removes them, and they are included in
validation, the generation request, an explicit profile save and the plan's saved
original context. A full multi-window editor is not part of this screen.

**Weeks.** With valid dates, a Monday-based week picker (`N / M주`) covers the
weeks from the preparation start to the day before the exam; other weeks cannot be
shown or changed. Each week is one of:

- 기본 시간 (no change for that week): 이번 주는 기본 시간을 사용해요, and the
  editor below (매주 기본 시간) changes the recurring base for every such week.
  이번 주 시간 바꾸기 gives the week its own copy of the base (hidden windows
  included); 이번 주 공부 안 함 makes it an empty week.
- Replaced (이번 주 공부 시간): the editor changes only that week. 이번 주 공부 안 함
  empties it; 기본 시간으로 되돌리기 removes the change so the week follows the
  base again.
- Empty (이번 주 공부 안 함): no window starts in that week. A window that starts
  on the previous Sunday and runs into Monday is
  still used. 이번 주 시간 바꾸기 and
  기본 시간으로 되돌리기 are offered.

Every week shows 지난주에서 이어지는 시간 when the previous Sunday's window
continues into it, including replaced weeks and the first preparation week. The
notice shows only the part inside the preparation/exam period, such as 월요일
00:00–00:30, using the previous week's settings even when the new week is empty.

An empty week is sent as an explicit empty `availability_overrides` entry, while a
reset week has no entry, so the two are never confused. Changing the dates keeps
week changes; those outside the new period are kept but not shown. The summary
box (공부하는 요일, 공부 시간) counts every window of the shown week, hidden ones
included.

**Conflicts.** Windows that overlap, including a window spilling past midnight
into the next day's window, a Sunday spill into Monday, or a replaced week's
boundary with its neighbours, switch the heading to 공부 시간을 확인해 주세요 and
show which days and times overlap (for example 월요일과 화요일 공부 시간이 겹쳐요 /
화요일 00:15–00:30이 겹쳐요). Both windows stay as entered, and 계획 만들기 and
서버에 공부 시간 저장 stay disabled until it is fixed. Ending exactly when the next
window starts is allowed.

**Server study time (live only).** The live build adds 서버 공부 시간 with the
notice 서버의 공용 공부 시간이에요. 다른 사용자가 바꿀 수 있고, 이미 만든 계획은
바뀌지 않아요. The profile is a single shared, unauthenticated record on the
server; the last successful save wins.

- 저장된 공부 시간 불러오기 sends `GET /availability` and shows a preview
  (기본 시간, 별도 설정, 추가 공부 시간). The form changes only after 불러온 시간 적용,
  which replaces the base and all week changes with the loaded ones, hidden windows
  included. 취소 or Back keeps the current draft.
- 서버에 공부 시간 저장 sends the complete current base and every week change with
  `PUT /availability` (an empty override map when there are none). The response
  must match what was sent.
- A failed load or save shows 공부 시간을 불러오지/저장하지 못했어요 with the current
  draft summary; the draft and hidden windows are unchanged. 다시 시도 repeats the
  action once on tap; 취소 returns to the editor. Nothing is retried automatically.

Generation, navigation, adjustment and restored plans never load or save the
profile. The demo build has no server study time: these actions are absent and
study times stay on the device.

## Calendar import

On the study-time screen (공부 시간), 캘린더 일정 불러오기 reads the device
calendar's occurrences between the preparation start and the exam date once,
only after that tap. Each occurrence is sent as a `busy_schedules` row with a
numeric ID, Korean wire times and the generic topic 외부 일정; titles and other
event details never leave the device. Changing either date clears the imported
rows. Planning without an import sends an empty array.

**Permission.** Import asks only for `READ_CALENDAR`, and only after that tap;
export needs `READ_CALENDAR` and `WRITE_CALENDAR` (see
[Calendar export](#calendar-export)). With only read access granted, import
works without a further prompt while export still asks for write access. The
prompts and the provider checks share one definition of these permissions. If
access is withdrawn after the prompt, the import reports the permission
message and the plan can still be generated without calendar data.

**Which events count.** Android returns occurrences of visible calendars only,
with recurrences expanded. Of those, busy and tentative occurrences block time;
canceled or deleted events, events marked free, and invitations you declined do
not. A missing status value does not exclude an event. Study sessions this app
exported earlier (live or demo) are also left out, recognized on the device by
the exact description the export writes; the description is read only for that
check and is never stored or sent. An exported event whose description was
edited or removed can no longer be recognized and is imported like any other
busy event; the app does not guess from titles or times and never changes or
deletes calendar events.

**Demo planner.** The demo planner keeps its usual workload and one session per
enabled day in that day's first window, with the usual session length (60
minutes, or the whole window if shorter). On each day it uses the earliest
start where the whole session fits outside every imported interval (intervals
are start-inclusive, end-exclusive, so an event ending at 18:00 does not block
a session starting then). It never splits or shortens a session to fit between
events; a day without such a gap gets no session, and too few usable days give
the usual labeled demo shortfall. Imported rows appear in the plan as
`external-<ID>` rows (기존 일정) in the same order in its JSON and CSV, and they
are not counted or exported as study sessions.

## Calendar export

A plan's study sessions can be copied once into a calendar that already exists
on the device, through Android's calendar provider. It is a one-way copy: the
app never reads, updates or deletes calendar events afterwards, and it never
creates a calendar or account.

- **Permissions.** The app declares `READ_CALENDAR` and `WRITE_CALENDAR` and
  asks for them only after 캘린더에 N개 일정 추가 is tapped, never at start or
  while viewing a plan. Reading lists the calendars the app may write to
  (contributor access or higher); writing adds the events. If access is denied,
  nothing is written and the app explains how to allow it under 설정 › 앱 ›
  권한; it does not ask again by itself.
- **Device calendar setup.** At least one writable calendar must exist, for
  example a Google account added in the device's Settings or a local calendar
  created in a calendar app. Without one the app reports that no calendar can
  receive events. An emulator image without a calendar app or account cannot
  complete an export.
- **What is copied.** Each `study-N` row of the selected plan, across all weeks
  and including past dates, becomes one timed event with the exact topic as its
  title. Existing schedules (`external-N`) are not copied, and no reminders,
  repetition, exam milestones or attendees are added. If any study row has a bad
  timestamp, an end not after its start, or a repeated ID, nothing is copied.
- **Korean time.** The plan's timestamps have no time zone; export reads them
  as Korean time (`Asia/Seoul`) and the confirmation says so. A calendar set to
  another zone shows the same instant in its own zone (18:00 KST is 09:00 UTC).
- **Demo builds write real events.** The demo planner is a local simulation,
  but its export adds real events to the chosen calendar. The confirmation says
  so, and every demo event's description carries “로컬 시연 계산”. Export never
  contacts the backend or the model provider, in either flavor.
- **Success means the device accepted the events.** The result counts only
  events the calendar provider confirmed on this device. Whether and when they
  appear on other devices or in Google Calendar on the web depends on that
  calendar account's sync, which the app does not check.

**Steps.** On a generated plan (내 계획) the action sits below the weeks; on a
plan opened from 계획 조정 it sits beside the adjustment actions. It counts the
plan's current study sessions across all weeks. Tap 캘린더에 N개 일정 추가 →
allow calendar access if asked → choose a calendar (equal names show the
account) → 다음 → check the plan number, calendar, count and 한국 시간
(Asia/Seoul) → tap N개 일정 추가. Back or 취소 before that last tap adds
nothing. While events are being added, the dialog cannot be closed and every
other plan action waits; rotation keeps the same export. The action is
unavailable for a plan with no study sessions, a plan that needs 최신 계획
불러오기 or can no longer be found on the server, while plans are being saved
or could not be saved, and while generation, adjustment, reload or a calendar
import (asking for permission, waiting for the answer or reading) runs. Both plan
screens show the same availability the app checks when export starts, and it
updates as soon as the blocking work ends. A live plan without a server file
identity can still be exported.

**Results.** Full success names the calendar and the number added. If adding
stops part way, the result shows how many were confirmed and, when the last
attempt's outcome is unknown, that more may have been added; check the
calendar before exporting again. There is no retry button and no automatic
retry; nothing already added is deleted.

**Limits.** Every export adds new copies: exporting the same plan again
duplicates its events, also after a restart, and the confirmation warns about
this each time. Adjusting a plan afterwards does not move or delete exported
events; export again to copy the new times, then remove the old events in the
calendar app. Export state is not saved, so closing the app never resumes or
repeats an export, and the saved plans are unchanged by it.

**Manual check (Android Studio).** JVM tests cover the mapping, the flow and
the insert counting with fakes, not the real calendar provider. To check a
device: open `frontend/` in Android Studio, run `demoDebug` on an emulator or
device that has a disposable writable calendar (add one in the calendar app or
device settings; an emulator without one shows only the no-calendar message).
Do not clear app data. Generate a demo plan and note its study-session count;
confirm no calendar prompt appeared before tapping export. Deny once and check
the settings guidance and that nothing was added; export again, allow, cancel at
choice and at confirmation (nothing added), then export once and compare the
new events' topics, times and durations with the plan and the “로컬 시연 계산”
description. Rotate during choice and confirmation. Export the same plan again
to see the duplicate warning and N more copies. For a non-Korean display zone,
check one event's instant against its plan time in Asia/Seoul. For live saved
plans, use the [controlled adjustment server](#controlled-adjustment-server)
with `--scenario normal` (and `conflict` / `plan-not-found` for the unavailable
states); export sends no request, which `requests.jsonl` can show. Record the
variant, device and API level, calendar type, expected and observed counts and
times, without account names. These checks have not been run for this change.

## Verification

This section describes what each kind of check covers. It is not a record of a
fresh run of this checkout; record each run's revision, APK and environment with
its result. Earlier dated device, deployment and emulator runs are kept in the
[archived frontend verification records](https://github.com/snuhcs-course/swpp-2026-project-team-02/blob/6d8af7fe65295f286c881d7d6c059c4844bd633f/frontend/README.md#verification).

- JVM checks cover both flavors, with MockWebServer for HTTP.
- `contract_checks/verify_request.py` checks the request the app serializes
  against a backend revision's source, offline (no server or model). CI uses
  `--backend-ref HEAD`, the backend in the same checkout. It also reports how
  the backend adapted the questions: distinct submitted questions, how many
  matched reviewed bank records, how many remain for model classification,
  the raw `assessment_items` and converted `problem_results` row counts, and the
  reviewed/model/mixed path. Reviewed plus unresolved must equal submitted; the
  app's full 18-question request reports 18/18/0 on the reviewed path.
  `contract_checks/test_verify_request.py` tests the counts, mixed reporting,
  malformed input and the extraction allowlist offline.
- Demo and live flows were run end to end on an emulator and compared with the
  Figma design.
- Plan adjustment: JVM tests cover the request body, response checks, the demo
  rule and the session/adjustment state. The demo flow and the live screens
  (against a scripted local test server, not the backend or model) were run on
  an emulator. Later live adjustment evidence is listed below; it does not
  replace the full isolation procedure described above.
- Adjustment error recovery: JVM tests (MockWebServer) cover the 404/409
  mapping, the reload GET and its identity matching and validation, and
  request sequences such as conflict → failed reload → reload → fresh
  adjustment with no repeated POST. Emulator checks use the controlled server
  in [Controlled adjustment server](#controlled-adjustment-server). Neither is
  evidence about the real backend or model.
- Successive requests: JVM tests (`ApiHttpTest`) send requests back to back on
  one shared client to a local server that answers HTTP/1.0 and closes the
  connection after every response without saying so: an immediate identical
  retry after a route 404, a fresh adjustment right after a reload, and
  adjustments right after loading questions and generating a plan. Each
  request must reach the server once, and the adjustment is still never
  resent automatically. The emulator retest at normal speed is described
  under [Controlled adjustment server](#controlled-adjustment-server). A manual
  device journey is not a repetition of this exact request-count matrix.
- Physical device and deployed backend: only the
  [manual live check](#manual-live-check) below exercises these. Earlier runs
  recorded adjustment as conditional because of an intermittent HTTP 307 that
  needed several explicit retries; its cause is unresolved, and later
  non-reproduction does not establish a fix.

### Manual live check

Use this procedure on a device or emulator connected to a real backend (a local
`python api_server.py` or the public server). It makes real model requests and
writes plans to that server; use the public server only when its owner agrees.
It checks rules the app and backend enforce, not a particular schedule: model
output, session counts and response times vary between runs, so do not compare
against a schedule or timing from an earlier run.

1. **Generate.** Choose 컴퓨터활용능력 2급, a preparation start and exam date,
   a level, answers to the example questions (including 모르겠음 at least once),
   and a few weekday windows. Check the answer summary and per-topic scores
   against the answers given, that progress is shown while the plan is made, and
   that the app stays open. In the plan, every study session must lie inside a
   chosen weekday window, after the preparation start and before the exam, and
   must not overlap another session or an imported calendar event.
2. **Reopen.** Force-stop and relaunch the app, open 계획 조정, and confirm the
   plan is listed under its number with the same rows.
3. **Adjust.** Choose one study session and adjust it. On success the view
   jumps to the moved session's week; the moved session keeps its topic and
   duration, lies inside the plan's original windows, ends by the exam date and
   overlaps nothing, and every other row is unchanged. Relaunch the app and
   confirm the adjusted rows remain. If no slot fits before the exam, the app
   explains the shortage and the plan stays unchanged.
4. **Generation failure.** Turn on airplane mode during generation. The app
   shows that the plan could not be made, keeps every input, and offers 다시
   시도 and 입력 수정하기. After restoring the network, 다시 시도 generates
   without re-entering anything.
5. **Uncertain adjustment.** Turn on airplane mode during an adjustment. The
   app reports that the result cannot be confirmed, keeps the earlier rows, and
   blocks further adjustment of that plan. After restoring the network, 최신
   계획 불러오기 must succeed before a new selection can be submitted; the app
   never resends the earlier request by itself.
6. **Input checks.** Confirm the form blocks: no study weekday, a window ending
   after midnight, and an exam date before the preparation start. A period whose
   windows give no usable time returns the insufficient-time explanation with
   입력 수정하기.

Record the APK's workflow run or build command, source commit and signature
check; the device model, Android version and network type; the API base URL and,
when known, the backend revision; timestamps of generation and adjustment; and
every failure, delay or retry as it happened. Record each HTTP 307 on
adjustment with its time and the number of retries; a successful retry does not
show that it is fixed. To check server-side persistence across a restart, see
the [deployment guide](../deploy/README.md#verification).

### Controlled adjustment server

`test_support/adjustment_server.py` is a test-only stand-in for the API, for
repeating adjustment error and recovery cases on an emulator. It uses the
Python standard library only, calls no AI service, reads no backend settings,
profile or `.env`, and keeps plans in memory: every start begins from the same
state, so restarting it is the reset. It serves the example questions (the
unit-test fixture), plan generation, adjustment and the plan list.

Generation returns plan A first and plan B second (then A, B again). Both use
generated-plan identities, B has the same study IDs as A, A has a long quoted
topic and external rows, and a third stored plan C is never generated, so it
must never appear in the app. Generation records the `exam_date` the app sent
for the returned plan. An unscripted adjustment answers 404 `PLAN_NOT_FOUND`
for an unknown file, 422 unless the body has exactly the current app's five
fields (`schedule_csv_file`, `schedule_id`, `schedules`, `weekly_availability`,
`exam_date`), 409 `PLAN_CONFLICT` when the sent rows differ from the stored
ones, and 422 when `exam_date` is missing, null, malformed, or differs from the
recorded one. Otherwise the chosen study row moves 7 days later and is saved,
unless the moved session would end after the recorded exam date: then it
answers 422 `NOT_ENOUGH_TIME` and saves nothing. A plan this server process
did not generate has no recorded exam date and gets the backend's fixed
recreation message, so generate plans again after restarting the server.

This is a simulation for app serialization and error handling. The fixed 7-day
move is not the backend's scheduler, and the strict five-field check is
stricter than the backend, which accepts an omitted `exam_date` for plans with
a stored deadline. Neither its successes nor its rejections are evidence about
production scheduling; the backend's deadline rules are tested against the
real handler with `backend/test_support/fixed_live_e2e_server.py`.

| `--scenario` | Scripted behavior |
| --- | --- |
| `normal` | No scripted failures |
| `plan-not-found` | A is pruned before its first adjustment: 404 `PLAN_NOT_FOUND` |
| `route-not-found` | Adjustment and plan list answer 404 `NOT_FOUND` (old server or wrong URL) |
| `conflict` | First adjustment: another client changes A, then 409 `PLAN_CONFLICT`; first list read 500; later reads list the changed A |
| `unknown-conflict` | First adjustment 409 with an unknown code, second 409 with an HTML body; nothing changes |
| `timeout-after-save` | First adjustment saves A, then withholds the response for 125 s (past the app's 120 s deadline) |
| `timeout-without-save` | First adjustment saves nothing, withholds for 125 s, then closes without a response |
| `invalid-readback` | First adjustment 409; list reads 1–4 are malformed JSON, no `plans`, duplicate row IDs, A listed twice; then valid |
| `readback-missing` | First adjustment 409 and A disappears; the list holds B, C and a similarly named plan |
| `legacy-plan` | Generated plans count as saved before exam dates were stored: every study adjustment gets 422 with the fixed recreation message, and nothing is saved |

```bash
# from frontend/: build a live APK for the server, start it, install
bash ./gradlew assembleLiveDebug -PlicensePlanner.apiBaseUrl=http://10.0.2.2:8765/api/v1
python3 -I test_support/adjustment_server.py --scenario conflict --port 8765 --output-dir "$(mktemp -d)"
adb install -r app/build/outputs/apk/live/debug/app-live-debug.apk
```

In the app, create two plans (A, then B with different windows), open A from
계획 조정, choose a study session and adjust it. Every request, response
status and scripted event (for example `saved`, `not-saved`,
`changed-elsewhere`) is appended to `requests.jsonl` in the output directory;
count POSTs and GETs there, since a screenshot cannot show that nothing was
resent. Stop the server with Ctrl+C and start it again for the next case.

The server closes the connection after every response, like the backend, so
it also exercises quick successive requests. Run these without waiting
between actions (each tap within about 5 s of the previous result):

- `route-not-found`: after the route error, tap 다시 시도 twice. Each tap adds
  one `POST /api/v1/schedules/adjust` with status 404 and the same body to
  `requests.jsonl`, and the route message stays; the plan never asks for a
  reload.
- `conflict`: after the 409, reload (500), reload again (200), then choose a
  session and adjust straight away. The log shows POST 409, GET 500, GET 200,
  POST 200, and the last POST carries the reloaded rows and A's original
  windows.
- `normal`: generate a plan, adjust a session, then adjust another one right
  away; both POSTs return 200. Choose an exam date at least a week after the
  chosen session, or the simulated move is refused with `NOT_ENOUGH_TIME`.
- `legacy-plan`: create A and B, open A and adjust a study session. The error
  panel shows 이 계획에는 시험일 정보가 저장되어 있지 않아요. 목록으로 돌아가 새
  계획을 만든 뒤 다시 조정해 주세요.; A and B keep their rows, the session stays
  selected, and `requests.jsonl` shows a single POST with no automatic GET or
  generation. 목록으로 and then 새 계획 만들기 continue from there.

These checks show how the app handles the scripted responses. They are not
evidence about the real backend or model.

Passing CI's unit tests, contract check, coverage, build, and lint is offline
evidence only. CI's separate gated emulator job runs the backend-free
[demo Compose UI tests](#demo-compose-ui-tests) and the
[live instrumentation test](#live-instrumentation-test) against the fixed-response
backend fixture. Live model planning, the deployed server and live UI/device
behavior still need the manual live and emulator checks above.

## Evaluation inputs

Fixed inputs for repeatable plan-quality and performance checks. Use the APKs
from `assembleDemoDebug`/`assembleLiveDebug` (or CI's `android-debug-apks`
artifact), and record the APK SHA-256 and the backend commit with each result.
Both cases choose 기본 기능은 알아요 and answer every example question
correctly, with preparation starting 2026-10-19. The demo results below were
observed on 2026-10-17 with the earlier five-question build (answers B, B, A, A,
C) and the earlier demo workload rule; the 18-question bank has more topics and
the demo now uses per-topic quotas, so re-observe them before comparing.

| Case | Exam date and study windows | Demo result (five-question build, 2026-10-17) |
| --- | --- | --- |
| A: adjustment | 2026-12-14; Mon 18:00 for 2 h, Tue 18:00 for 1 h, Wed 18:00 for 2 h | Six sessions. Adjusting `study-1` moves it to 10/26 19:00–20:00; adjusting it again moves it to 10/28 19:00–20:00. |
| B: no free slot | 2026-11-02; Mon, Wed, Fri 18:00 for 1 h | Six sessions fill every slot; adjusting one reports 옮길 수 있는 시간이 없어요. |

In the live build, model output varies, so run each case several times. Check
the returned `schedules` against the inputs: sessions inside the chosen windows,
no overlaps, everything before the exam date, topic coverage, and response
time. Live adjustment results mean nothing until the backend dependency in
[Plan adjustment](#plan-adjustment) is met.

## Study reports

Every study session of the new plan (내 계획) and of a reopened saved plan
(실패한 일정 선택) shows its report, or 아직 기록하지 않았어요, with a separate
공부 기록하기 / 기록 수정하기 action under the row. Existing calendar rows have no
action. The action does not select the session for adjustment, and closing the
editor returns to the same week with any selection unchanged. It waits while a
request or save runs (it is disabled during a save or an adjustment).

A new report starts on the session's scheduled day, by the device's local date
(a 19:00 session can be reported earlier that day). An unreported session on a
later date shows 일정 당일부터 기록할 수 있어요 instead of the action, and the
editor does not open for it. The date is read again whenever the rows are shown
and when the editor opens, so after midnight the session offers 공부 기록하기
once its week is shown again (for example after changing weeks or reopening the
plan). A session that already has a report keeps it and stays editable, even
after an adjustment or move places it on a later date.

The editor shows the plan, topic, scheduled time and planned duration, then the
status (다 했어요 / 일부 했어요 / 못 했어요; none is preselected for a new report) and
the actual minutes as a separate field. 다 했어요 suggests the planned minutes;
both 다 했어요 and 일부 했어요 accept any positive whole number of minutes, including
more than planned; 못 했어요 disables the field and saves 0. Invalid entries show
an error and keep the saved report. 기록 저장하기 / 기록 수정하기 saves explicitly
and returns to the plan; 취소 or back writes nothing. Rows show the saved status
and actual minutes (for example 다 했어요 · 실제 20분) apart from the scheduled
duration.

If the save fails, the editor says 공부 기록을 저장하지 못했어요 and offers
다시 저장하기; after closing it, the row shows the edit with 저장 안 됨 and the plan
screen offers the same local retry. See **Saving a study report** above for
blocking and device-local limits. Reports do not change the schedule, are not
sent to the server or the calendar, and do not estimate mastery; there is no
skip (이미 아는 내용이에요) action and no history of earlier reports.

## Known limitations

- Written exam only; practical-exam support is an open decision.
- Physical-device evidence is limited to a few recorded single-device runs
  (see [2026-10-30–31 device report](https://github.com/snuhcs-course/swpp-2026-project-team-02/blob/6d8af7fe65295f286c881d7d6c059c4844bd633f/docs/iter3-HE/03_device_verification.md)); it is not broad
  device or reliability coverage. TalkBack support is incomplete.
- Deployed adjustment can intermittently fail with HTTP 307; the cause and fix
  remain unverified.
- The 18 selected questions sample each major section of the written syllabus
  with one to four items, so a single answer can move a topic score a long way.
  Their teammate content review is still pending. They are mapped to the
  2024–2026 syllabus; the official 2027–2029 comparison is done, but its
  findings (IDs 6/8 Windows basis, IDs 9–12 section rename, ID 10 remap, no AI
  basics, AI ethics or ICT convergence items) are left to a separate 2027
  update, so from 2027-01-01 the questions are behind the current syllabus. See
  the [question-bank notes](../backend/DEVELOPER_GUIDE.md#문항-은행-유지보수).
- Calendar events are read once, when a plan is created. Adjustment uses the
  saved plan's external rows; re-reading the calendar after a plan is saved,
  detecting newly conflicting study sessions and rescheduling them are not
  implemented.
- A 500 `STORAGE_ERROR` from adjustment or reload is shown as a generic server
  failure with retry, not as a storage problem. Retrying cannot succeed until the
  saved plan file on the server is repaired; the plan stays viewable and unchanged.
- Form state is held in memory and is lost if the process is killed. The
  generated-plan list is saved on this device only (no sync or server import).
- Live plans come from the OpenRouter Luna model, so session counts can vary for the same inputs.
- Demo session counts follow the fixed demonstration rule in
  [Flavors](#flavors) (32 sessions for all 모르겠음 at beginner level,
  eight for a perfect advanced score). They are not the live model's allocation
  or an estimate of the preparation the exam needs, and the 2026-10-17 demo
  evaluation results predate this rule.
- Proven live insufficient capacity arrives as HTTP 422 and is shown as described
  in [Validation errors](#validation-errors), with retry and “입력 수정하기”. It
  does not open the demo shortfall screen; the warning-origin exam-date editor
  remains demo-only.
- Validation wording depends on the backend's free-text `error.message`. If that
  text changes, the app shows the neutral fallback until the mapping is updated.
- The app shows them under “계획 요약” in a plain gray box, without the AI tag, so the label does not claim model authorship.
