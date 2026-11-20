# License Planner - Iteration 4 Demo

License Planner helps a learner prepare for the **Computer Specialist Level 2 written exam (컴퓨터활용능력 2급 필기)**. The learner answers 18 example questions, enters when they can study, and gets a day-by-day plan that gives weaker topics more time and avoids events already in the phone calendar. The plan can be copied to the phone calendar, studied sessions can be recorded, and missed work can be moved or re-planned.

## Demo version and video

- Branch: `iteration-4-demo`, created from tag `iter4-uat-20261115` (commit `54db19b`), the build frozen for UAT on November 15, 2026.
- [Demo video](https://drive.google.com/file/d/1D53hDg2WHme4H6wOyElodOEi3QqqvXOw/view?usp=sharing) (about 4 minutes, edited from a full recording): physical Android phone, public server.
- [Signed APK, Prod APK run 43716265710](https://github.com/snuhcs-course/swpp-2026-project-team-02/actions/runs/43716265710): download the `live-release-apk-54db19b96c457ee2c04870a8c08601ed1d5de4a7` artifact. Version 0.1.0, SHA-256 `9b0f532bae1772d6b27a9592867bd57feebc1f990e3d1e90d8dc192db356f418`.
- Public API: `http://swpp26t02.147-46-242-174.sslip.io/api/v1` (deployed from `54db19b` for UAT).

## Run the signed demo

1. Download and extract the APK from the run above (a GitHub login is required to download Actions artifacts).
2. Install `app-live-release.apk` on Android 8.0 (API 26) or later. Allow installation from this source if Android asks. It has the same signing certificate as earlier release APKs, so it installs as an update; a debug build must be removed first.
3. Open `http://swpp26t02.147-46-242-174.sslip.io/api/v1/health` in a browser and check `{"status":"ok"}`. If the network blocks it, switch to mobile data.
4. Open the app and follow the scenario below.

Plans saved by earlier releases have no stored exam date: they can be viewed, but to move their sessions create a new plan.

## Development environment and local run

- Python 3.14 (3.13 also runs the tests); the backend uses only the standard library.
- Android Studio, SDK platform 36, the checked-in Gradle wrapper, Java 17 (`frontend/gradle/gradle-daemon-jvm.properties`).
- Android emulator or device: minimum SDK 26, target SDK 36.
- Live generation and automatic adjustment call OpenRouter model `openai/gpt-6-luna` from the backend. Put `OPENROUTER_API_KEY` only in the backend environment or `backend/.env` (never in the app or Git). Manual moves and remaining-work re-planning do not call the model.

```bash
git clone --branch iteration-4-demo https://github.com/snuhcs-course/swpp-2026-project-team-02.git
cd swpp-2026-project-team-02

# backend
cd backend
python api_server.py            # http://127.0.0.1:8000/api/v1

# Android, in another terminal (emulator reaches the host at 10.0.2.2)
cd frontend
./gradlew assembleLiveDebug -PlicensePlanner.apiBaseUrl=http://10.0.2.2:8000/api/v1
adb install -r app/build/outputs/apk/live/debug/app-live-debug.apk
```

Without a backend or model, `./gradlew installDemoDebug` installs the demo flavor. It is labeled as a demo and plans on the device with a fixed rule.

Tests (from `backend/`): `python -m unittest discover -s tests/license_planner_tests`, `.../schedule_adjuster_tests`, `.../api_tests`. On November 20 the UAT build ran 382 backend tests (381 passed, 1 skipped). Android: `./gradlew testDemoDebugUnitTest testLiveDebugUnitTest`. Details are in [frontend/README.md](frontend/README.md), [backend/README.md](backend/README.md) and the [Testing Documentation](https://github.com/snuhcs-course/swpp-2026-project-team-02/wiki/Testing-Documentation).

## Demo scenario

| Input | Value |
| --- | --- |
| Certification | 컴퓨터활용능력 2급 필기 |
| Preparation start / exam | November 16 / December 7, 2026 (if these are in the past, move both by the same number of weeks) |
| Experience | 기본 기능은 알아요 |
| Answers (A–D = 1st–4th choice) | 1 B, 2 B, 3 모르겠음, 4 B, 5 C, 6 B, 7 D, 8 A, 9 C, 10 B, 11 D, 12 A, 13 C, 14 B, 15 D, 16 A, 17 C, 18 B |
| Study times | Mon/Wed/Fri 19:00 for 1 h 30 min; Sat 10:00 for 2 h |

1. **Diagnosis:** 18 questions one at a time with 모르겠음. Result: 16 of 18 correct; 스프레드시트 기본 4/4, 함수와 데이터 관리 1/2, 차트와 분석 0/1, other five topics full.
2. **Calendar check:** on 계획 만들기 the app asks for calendar permission, then summarizes events that overlap the study times and offers 겹치지 않게 계획 만들기.
3. **Plan:** weekly and daily sessions inside the study times, around calendar events, with a 계획 요약 and a weekly available/assigned time graph. 차트와 분석 (weakest) gets the most time.
4. **Calendar export:** 캘린더에 N개 일정 추가 copies the sessions to a chosen phone calendar.
5. **Saved plan and adjustment:** reopen the plan from 계획 조정 after restarting the app, select a missed session and adjust it; the moved session is highlighted and stays before the exam.

## What the demo shows

**Features implemented**

| Area | Feature |
| --- | --- |
| Diagnosis | 18 authored questions covering both subjects and eight topics; local scoring; 모르겠음 option. |
| Study times | Several windows per weekday, windows past midnight, week-specific changes, saved drafts (이어서 만들기). |
| Plan generation | Model proposes per-date minutes; code splits them by diagnostic score and places contiguous topic blocks (P10). |
| Calendar | Reads busy times (no titles) with permission, conflict review before generation, conflict notices on app open, one-way export. |
| After generation | Study reports (done / partly / missed + minutes, stored on the device), automatic adjustment of a missed session, manual move to any valid time, re-planning all remaining work at once. |
| Feedback | Contextual help on each screen, cause-specific error messages with details, Back confirmation during generation. |

**Major progress in Iteration 4**

- **Weak-topic priority fixed (P10):** the frozen AI evaluation went from FAIL (weakest topic first in 48.4% of plans, Iteration 3) to PASS on all eight criteria (100%, 126/126 valid plans, p95 8.2 s).
- **One integrated build:** calendar reading, conflict review and export now ship with planning and adjustment; physical-device test passed on November 12.
- **New flows:** study reports, manual moves, remaining-work re-planning, flexible study windows and drafts.
- **Heuristic Evaluation fixes:** HE-01 help, HE-09/23 Back during generation, HE-11 adjustment destination, HE-12 error causes, HE-13 available vs. assigned time.
- **UAT cycle 1 (November 16):** 159 of 163 executed steps passed with three testers; follow-ups are per-topic totals in the summary, a first-attempt generation failure, one missing notice and the time picker.

## Known limitations

- Android only; written exam only.
- About a third of sessions are shorter than 15 minutes, and plans use a median 31% of the entered time; the model still chooses the total workload.
- The 18 questions are authored examples, not a validated test.
- The server is shared over HTTP with no accounts; saved plans and busy times on it are not private.
- Exporting the same plan again creates duplicate calendar copies (the app warns first).
