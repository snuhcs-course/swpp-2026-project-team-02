# License Planner - Iteration 3 Demo

License Planner helps a learner prepare for the **Computer Specialist Level 2 written exam (컴퓨터활용능력 2급 필기)**. It creates a study plan from authored example questions and weekly availability, saves it on the phone, and moves one missed study session to a valid time before the exam.

## Demo version and video

- Branch: `iteration-3-demo`, based on scheduler source `66a225d8dee66b946887da7231fe397c87a92d4d`.
- The recorded demo uses signed APK source `5216f41136a01218766ff5a3e13134d12704a972`. The difference to `66a225d` is documentation only.
- [Demo video](https://drive.google.com/file/d/1iN3XWwX0RAyXgFX-ZcGzoq40OhipBL-8/view?usp=sharing): November 6, 2026, 18:11 KST, Galaxy S26 Ultra / Android 17 / mobile data.
- [Signed APK, Prod APK run #34](https://github.com/snuhcs-course/swpp-2026-project-team-02/actions/runs/42028937748): download the `live-release-apk-5216f41136a01218766ff5a3e13134d12704a972` artifact and extract `app-live-release.apk`. GitHub read access is required; artifacts expire after 30 days.
- Public API: `http://swpp26t02.147-46-242-174.sslip.io/api/v1`.
- Server source `5216f41` was reported for the recorded demo. A healthy endpoint does not establish its current revision.

## Run the signed demo

1. Download and extract the APK from the run above.
2. Install it on Android 8.0/API 26 or later. Updating an installed release with the same signing certificate preserves its data. Removing an app or clearing its data removes its saved plans.
3. Check `GET http://swpp26t02.147-46-242-174.sslip.io/api/v1/health`.
4. Open the app and follow the scenario below.

Use the scheduler APK for adjustment. The older Drive HE APK and the calendar-export branch omit the exam date required by this server contract and can receive HTTP 422.

## Development environment and local run

- Python 3.14; the backend uses the standard library. The project environment is Conda `swpp`.
- Android Studio, SDK platform 36/build-tools 36.0.0, and the checked-in Gradle wrapper.
- Java 17 selected by `frontend/gradle/gradle-daemon-jvm.properties`.
- An Android emulator or device. Minimum SDK 26; target SDK 36.
- Live generation/adjustment requires network access and a server-side `OPENROUTER_API_KEY`. Configure it privately in the backend environment. The configured model default is `openai/gpt-6-luna`; a deployed process may override it.

Clone this branch:

```bash
git clone --branch iteration-3-demo https://github.com/snuhcs-course/swpp-2026-project-team-02.git
cd swpp-2026-project-team-02
```

Start the backend in one terminal:

```bash
conda activate swpp
cd backend
python -c "import sys; import api_service; print(sys.executable)"
python api_server.py
```

In another terminal, build/install the emulator live app:

```bash
cd frontend
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew assembleLiveDebug -PlicensePlanner.apiBaseUrl=http://10.0.2.2:8000/api/v1
adb install -r app/build/outputs/apk/live/debug/app-live-debug.apk
```

For a USB-connected physical device, use `adb reverse tcp:8000 tcp:8000` and build with `-PlicensePlanner.apiBaseUrl=http://127.0.0.1:8000/api/v1`.

For the local, deterministic screen flow without a backend or model:

```bash
cd frontend
./gradlew installDemoDebug
```

The demo flavor is labeled as a demo and does not call an AI model. Further setup and Windows commands are in [frontend/README.md](frontend/README.md) and [backend/FRONTEND_INTEGRATION_GUIDE.md](backend/FRONTEND_INTEGRATION_GUIDE.md).

## Recorded demo scenario

| Input | Value |
| --- | --- |
| Certification | 컴퓨터활용능력 2급 필기 |
| Preparation start / exam | November 9 / November 30, 2026 |
| Experience | 기본 기능은 알아요 |
| Answers | Q1 B, Q2 B, Q3 모르겠음, Q4 B, Q5 C |
| Weekly windows | Monday/Wednesday/Friday 19:00-20:30; Saturday 10:00-12:00 |

1. Create a plan using these inputs. Results show three correct answers out of five, with topic scores 2/2, 1/2 and 0/1.
2. Generate the plan. Check that sessions fit the entered windows and do not overlap.
3. Browse the weeks and daily session times.
4. Close and reopen the app, then choose adjustment and reopen the saved plan.
5. Select the first missed study session and request adjustment. Verify that its topic/duration remain and its new time is valid and before the exam.
6. Create another plan with the November 11 exam date and only 30 minutes on Saturday. Check the insufficient-time explanation and input-correction action.

The live model can produce different allocations from the same input. For future runs, shift both dates and any calendar fixtures together and record the dates actually used.

## Iteration 3 progress

| Area | Progress |
| --- | --- |
| Public backend and signed APK | Available; Nginx fronts the Python API. |
| Plan creation, weekly/day view, device-local saved plans | Included. |
| Missed-session adjustment with exam bound and reload recovery | Included in this scheduler source. |
| Offline backend/Android CI and fixed-backend emulator E2E | Recorded passing in [CI #58](https://github.com/snuhcs-course/swpp-2026-project-team-02/actions/runs/42028945104). The model call is replaced in the fixture. |
| Calendar export / calendar reading | Separate integration work; absent from this demo APK. Integrated physical-device US-C8/US-C9 acceptance pending. |
| AI allocation evaluation | Overall FAIL: weakest-topic priority 48.4% against the 80% target. Improvement and a comparable rerun are pending. |

The seven frozen UAT stories remain US-B3, US-B5, US-B6, US-C3, US-C4, US-C8 and US-C9. The demo is partial evidence, not a completed run of all seven.

## Limitations

- Five authored spreadsheet example questions; no computer-general question set, practical-exam support or validated diagnostic.
- Intermittent HTTP 307 on adjustment remains unexplained. Successful later runs do not establish a fix.
- Export is a one-way copy on its separate branch. The app does not synchronize later calendar edits or track completed study.
- The public HTTP backend has no authentication or per-user isolation. Saved plan rows and external busy intervals are publicly readable. It retains 100 generated plans across all users; older server plans can disappear while a local copy remains.
- Historical AI evaluation metadata does not establish its exact model, deployed revision, decoding settings or prompt version.
- Server-authoritative exam-deadline storage is planned separately; it is not included in this pinned source.

[Testing and evidence](https://github.com/snuhcs-course/swpp-2026-project-team-02/wiki/Testing-Documentation) · [Requirements](https://github.com/snuhcs-course/swpp-2026-project-team-02/wiki/Requirements-and-Specifications) · [Design](https://github.com/snuhcs-course/swpp-2026-project-team-02/wiki/Design-Documentation) · [Deployment guide](deploy/README.md)
