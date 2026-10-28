# Frontend

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
let Gradle sync. Pick a build variant: `demoDebug` (default) or `liveDebug`.

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
| `live` | Yes | `GET /certifications/computer_specialist_level_2/questions` | `POST /study-plans` (OpenRouter `openai/gpt-6-luna`, server-side) | `POST /schedules/adjust`; `GET /study-plans` only to reload the selected plan |

**Use `demo` for evaluation without a backend.** Its plans are a demonstration
calculation, not backend or AI output. The live flavor never falls back to demo
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
HTTP only to `10.0.2.2`, `127.0.0.1`, and `localhost`. API keys stay in the
backend; the app never calls the model provider directly. The live API URL and
contract are unchanged by the backend's model choice.

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
and 계획 조정 (a list of the plans generated in this app process). The first
creation step (자격증) has a top-bar back arrow that, like system back, returns
home without sending a request or clearing the inputs. Each
successful generation adds one entry, numbered 계획 1, 계획 2, … even when dates
match. Editing the form or a failed generation never removes an entry. The
list is held in memory only: it survives rotation and navigation but is lost
when the process ends. The app never imports plans from the server; it reads
the server's plan list only to reload one plan it already has (see below).

On a plan, choose exactly one missed study session (`study-N`); existing
schedules (`external-N`) are shown but cannot be chosen. A live plan whose
generation response had no `schedule_csv_file` can be viewed but not adjusted.
A plan with no study sessions explains that and offers a way back. After a
successful adjustment the same entry is updated in place, the view jumps to the
moved session's week, and another session can be adjusted from the new rows.
The moved session carries a “옮긴 일정” tag on this screen only. The tag stays
through rotation and clears when another session is chosen or another plan is
opened. Nothing about it is stored, and the generated-plan screen never shows it.
Other plans never change, even when they share schedule IDs.

While an adjustment request runs, the screen shows progress; system back,
the top-bar back, and every other action are held, and rotation keeps the same
request. A second tap sends nothing. On failure the plan and the chosen session
stay as they were. There is no automatic retry and no demo fallback in the live
build. What follows depends on the failure:

| Live failure | Message | Next step |
| --- | --- | --- |
| 422, 500, 502, 503, other statuses | 422: Korean explanation of a recognized cause (see [Validation errors](#validation-errors)), or 요청 정보를 확인하지 못했어요; other errors use app-defined wording | 다시 시도 resends the identical request; choosing another session builds a new one |
| 404 without `PLAN_NOT_FOUND` (wrong URL or old server) | API 경로를 찾을 수 없어요 … (HTTP 404) | 다시 시도, as above; the plan is not treated as deleted |
| 404 `PLAN_NOT_FOUND` | 서버에서 이 계획을 찾을 수 없어요 … | The plan stays viewable but cannot be adjusted for the rest of the session; 목록으로 or 새 계획 만들기 |
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
saved.

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

**Backend dependency** The backend in this checkout (merged from `main` at
`11c822e`) updates the selected plan file before it returns success. It uses
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
checked against MockWebServer and a scripted local server only;
Android/OpenRouter Luna same-file verification against the real backend is
still pending.

## Verification

- Unit tests pass for both flavors (JVM, with MockWebServer for HTTP).
- `contract_checks/verify_request.py` checks the request the app serializes
  against a backend revision's source, offline (no server or model). CI uses
  `--backend-ref HEAD`, the backend in the same checkout.
- Demo and live flows were run end to end on an emulator and compared with the
  Figma design.
- Plan adjustment: JVM tests cover the request body, response checks, the demo
  rule and the session/adjustment state. The demo flow and the live screens
  (against a scripted local test server, not the backend or model) were run on
  an emulator. Adjustment against a real backend is not verified yet (see the
  backend dependency above).
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
  under [Controlled adjustment server](#controlled-adjustment-server); the real
  backend has not been checked for this.

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
must never appear in the app. Unscripted adjustments behave like the backend:
404 `PLAN_NOT_FOUND` for an unknown file, 409 `PLAN_CONFLICT` when the sent
rows differ from the stored ones, otherwise the chosen study row moves 7 days
later and is saved.

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
  away; both POSTs return 200.

These checks show how the app handles the scripted responses. They are not
evidence about the real backend or model.

Passing CI (unit tests, contract check, coverage, build, and lint) is offline
evidence only. It does not run the backend server, live model planning, an emulator,
or the UI; those still need the manual live and emulator checks above.

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

In the live build, model output varies, so run each case several times. Check
the returned `schedules` against the inputs: sessions inside the chosen windows,
no overlaps, everything before the exam date, topic coverage, and response
time. Live adjustment results mean nothing until the backend dependency in
[Plan adjustment](#plan-adjustment) is met.

## Known limitations

- Written exam only; practical-exam support is an open decision.
- Not tested on a physical device; TalkBack support is incomplete.
- Form state and the generated-plan list are held in memory and are lost if
  the process is killed.
- Live plans come from the OpenRouter Luna model, so session counts can vary for the same inputs.
- Proven live insufficient capacity arrives as HTTP 422 and is shown as described
  in [Validation errors](#validation-errors), with retry and “입력 수정하기”. It
  does not open the demo shortfall screen; the warning-origin exam-date editor
  remains demo-only.
- Validation wording depends on the backend's free-text `error.message`. If that
  text changes, the app shows the neutral fallback until the mapping is updated.
- Generation summaries are composed by backend code from the accepted schedule.
  The current “AI가 작성한 설명” label does not establish model authorship of that
  text; its wording still needs reconciliation.
