# Team 2 Project

This repository is organized into separate application areas:

```text
project/
├── frontend/   # Android app (Kotlin, Jetpack Compose)
├── backend/    # Existing Python API, agents, planners, tests, and docs
└── README.md   # Repository overview and team setup
```

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

The API defaults to `http://127.0.0.1:8000/api/v1`. Configure the Gemini key in
`backend/.env`; never put API keys in frontend files. `.env` files and local
availability profiles are excluded from Git.

## Frontend (Android)

[`frontend/`](frontend/) contains the Android app for the MVP, covering
preparation for the 컴퓨터활용능력 2급 written exam (필기). The default `demo`
build runs without a backend. The `live` build needs the backend on branch
[`JunwooCho`](https://github.com/snuhcs-course/swpp-2026-project-team-02/tree/JunwooCho);
the backend in this checkout is older and does not work with it.

Build, run, and backend setup are in [`frontend/README.md`](frontend/README.md).

## Open decisions

- **Practical exam (실기):** whether and how to support it. The app currently
  plans for the written exam only.
- **Question bank:** replace the example questions with the team's agreed bank
  (for example, the eight questions on branch `assessment`).
- **Not enough study time:** the API has no structured response for this, so the
  live app shows it as a generic error.
- **Deployment:** server address, authentication, and per-user storage.
