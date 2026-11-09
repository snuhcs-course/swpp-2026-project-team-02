# License Planner

This repository is organized into separate application areas:

```text
project/
├── frontend/   # Android app (Kotlin, Jetpack Compose)
├── backend/    # Existing Python API, agents, planners, tests, and docs
└── README.md   # Repository overview and team setup
```

## Documentation

| Guide | Covers |
| --- | --- |
| This README | Product scope, quick start, guide index and open decisions |
| [`backend/README.md`](backend/README.md) | Backend setup and configuration, offline checks and coverage, scenario runners, the fixed-response fixture and live model checks |
| [`backend/DEVELOPER_GUIDE.md`](backend/DEVELOPER_GUIDE.md) | Internal architecture, validation, scheduling and storage rules, question-bank maintenance |
| [`backend/FRONTEND_INTEGRATION_GUIDE.md`](backend/FRONTEND_INTEGRATION_GUIDE.md) | HTTP contract used by the Android app: payloads, errors, deadlines and reload recovery |
| [`frontend/README.md`](frontend/README.md) | Android build, flavors and signing, app behavior, calendar permissions and verification procedures |
| [`deploy/README.md`](deploy/README.md) | Server topology, installed-state inspection, updates and backups, release APK and operational checks |

Package READMEs (for example `backend/schedule_adjuster/README.md`) describe
their own command-line tools.

## Backend

The existing backend project is in [`backend/`](backend/). Backend commands and
paths in its documentation are relative to that directory. Start by reading
[`backend/README.md`](backend/README.md) and
[`backend/FRONTEND_INTEGRATION_GUIDE.md`](backend/FRONTEND_INTEGRATION_GUIDE.md).

To run the API locally:

```powershell
cd backend
python api_server.py
```

The API defaults to `http://127.0.0.1:8000/api/v1`. Study-plan generation and
schedule adjustment call OpenRouter model `openai/gpt-6-luna`; configure
`OPENROUTER_API_KEY` in the backend process environment or `backend/.env`. Never
put API keys in frontend files. `.env` files and local
availability profiles are excluded from Git.

## Frontend (Android)

[`frontend/`](frontend/) contains the Android app for the MVP, covering
preparation for the 컴퓨터활용능력 2급 written exam (필기). The default `demo`
build runs without a backend. The `live` build connects to this checkout's
backend at `http://10.0.2.2:8000/api/v1` from the Android Emulator. The backend
provides the question-bank and locally-scored assessment API contract used by
the app.

Build, run, and backend setup are in [`frontend/README.md`](frontend/README.md).

## Continuous integration

[`.github/workflows/ci.yml`](.github/workflows/ci.yml) runs on pull requests and
pushes to `main`, on manual dispatch, and daily at 00:00 UTC. It runs the three
offline backend unit suites in `backend/tests/` with coverage (not the scenario
runners in `backend/scenarios/`) plus unit tests for the evidence validator in
`scripts/tests/`, and the Android unit tests, request contract check, coverage,
debug APK builds, and lint. Only after both of those jobs succeed, a third job
starts the fixed-response backend fixture and runs the live-flavor
instrumentation test on an API 35 emulator, using an emulator action pinned to a
full commit SHA. Commands and report locations are in
[`backend/README.md`](backend/README.md#checks-and-coverage) and
[`frontend/README.md`](frontend/README.md#checks). CI uses no OpenRouter key or
deployed server, so it does not verify live model planning or the Android UI
screens.

## Deployment

The public API is `http://swpp26t02.147-46-242-174.sslip.io/api/v1`, served by
the shared CPU server through Nginx. Its systemd and Nginx templates, how to
inspect the installed revision before an update, plan backups, the read-only
public check and the signed release APK are documented in
[`deploy/README.md`](deploy/README.md). This checkout does not establish which
revision or configuration the server is running.

## Open decisions

- **Practical exam (실기):** whether and how to support it. The app currently
  plans for the written exam only.
- **Question bank:** onboarding presents a configured selection of 18 authored
  example questions, nine each for 컴퓨터 일반 and 스프레드시트 일반, sampling
  every major section of the KCCI 2024–2026 written syllabus with one to four
  items per section. The stored collection may grow beyond that selection
  without changing onboarding; it currently holds exactly those 18 items. They
  are not an official or validated assessment. Their internal `reviewed` flags
  were prepared for PR review; the second (teammate) content review is still
  pending. The official comparison with the 2027–2029 syllabus is done: the bank
  stays mapped to 2024–2026 (`syllabus_version: "2024-2026"`, IDs 1–18
  unchanged), and updating it is planned as a separate change in 2027. Carried
  forward are the Windows 10 basis of IDs 6/8 (their prompts are now
  version-neutral, without newer-cycle remapping), the section rename for IDs
  9–12, the remapping of ID 10, and missing AI basics, AI ethics and ICT
  convergence items. From 2027-01-01 the bank is behind the current syllabus
  until that update lands. Deeper coverage, routine check-ins, practical
  workbook tasks and timed mock exams remain later decisions. See
  [question-bank maintenance](backend/DEVELOPER_GUIDE.md#문항-은행-유지보수).
- **Not enough study time:** proven infeasibility returns HTTP 422 with capacity,
  topic basis and recovery advice in `error.message`; the live app presents it in
  Korean. A richer structured shortfall schema remains an open decision.
- **Summary labeling:** generation summaries are composed by server code from
  accepted schedule counts. The app labels them “계획 요약” in a plain gray box, without the AI tag.
- **Deployment:** authentication, per-user storage and plan retention remain
  open; the public server and release APK use the shared HTTP endpoint above.

## Historical records

Earlier reports, demonstrations and experiments were removed from the current
tree. They are preserved unchanged at commit
[`6d8af7f`](https://github.com/snuhcs-course/swpp-2026-project-team-02/tree/6d8af7fe65295f286c881d7d6c059c4844bd633f). Their dates, builds, models and results describe
those runs only and are not evidence about the current code, model or server.

- [AI plan evaluation](https://github.com/snuhcs-course/swpp-2026-project-team-02/tree/6d8af7fe65295f286c881d7d6c059c4844bd633f/docs/iter3-ai-eval): frozen 37-case protocol,
  runner and raw results. The 2026-11-04 run was judged **FAIL** against its
  preregistered criteria (weakest-topic allocation missed its threshold), and
  its server revision, model and decoding settings were not recorded.
- [Heuristic-evaluation package](https://github.com/snuhcs-course/swpp-2026-project-team-02/tree/6d8af7fe65295f286c881d7d6c059c4844bd633f/docs/iter3-HE),
  [fixed walkthrough](https://github.com/snuhcs-course/swpp-2026-project-team-02/blob/6d8af7fe65295f286c881d7d6c059c4844bd633f/deploy/HE_EVALUATION.md),
  [submission record](https://github.com/snuhcs-course/swpp-2026-project-team-02/blob/6d8af7fe65295f286c881d7d6c059c4844bd633f/ITER3_SUBMISSION.md) and
  [demo record](https://github.com/snuhcs-course/swpp-2026-project-team-02/blob/6d8af7fe65295f286c881d7d6c059c4844bd633f/docs/iter3-demo/README.md): fixed inputs, APK
  identities, the 2026-10-30–31 physical-device run (including the unresolved
  intermittent HTTP 307 on adjustment) and the question review.
- [Testing record](https://github.com/snuhcs-course/swpp-2026-project-team-02/blob/6d8af7fe65295f286c881d7d6c059c4844bd633f/docs/TESTING_DOCUMENTATION.md),
  [test-automation and scheduler record](https://github.com/snuhcs-course/swpp-2026-project-team-02/blob/6d8af7fe65295f286c881d7d6c059c4844bd633f/docs/ITER3_TEST_AUTOMATION_AND_P30_EVIDENCE.md),
  [scheduler decision](https://github.com/snuhcs-course/swpp-2026-project-team-02/blob/6d8af7fe65295f286c881d7d6c059c4844bd633f/docs/P30_SCHEDULER_APPROACH.md) and
  [verification index](https://github.com/snuhcs-course/swpp-2026-project-team-02/blob/6d8af7fe65295f286c881d7d6c059c4844bd633f/docs/README.md): dated CI runs, coverage
  figures, public-server emulator screenshots and ownership notes.
- [Gemini performance package](https://github.com/snuhcs-course/swpp-2026-project-team-02/tree/6d8af7fe65295f286c881d7d6c059c4844bd633f/docs/performance): the 2026-10-15
  `gemini-3.5-flash-lite` measurements and their reproduction tools. They do not
  describe the current OpenRouter model.
- The earlier [frontend guide](https://github.com/snuhcs-course/swpp-2026-project-team-02/blob/6d8af7fe65295f286c881d7d6c059c4844bd633f/frontend/README.md),
  [deployment guide](https://github.com/snuhcs-course/swpp-2026-project-team-02/blob/6d8af7fe65295f286c881d7d6c059c4844bd633f/deploy/README.md),
  [developer guide](https://github.com/snuhcs-course/swpp-2026-project-team-02/blob/6d8af7fe65295f286c881d7d6c059c4844bd633f/backend/DEVELOPER_GUIDE.md) and
  [integration guide](https://github.com/snuhcs-course/swpp-2026-project-team-02/blob/6d8af7fe65295f286c881d7d6c059c4844bd633f/backend/FRONTEND_INTEGRATION_GUIDE.md) contain dated
  device-test APK, device-run, server-restart, Gemini comparison and
  backend-verification records.
