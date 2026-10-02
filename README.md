# Team 2 Project

This repository is organized into separate application areas:

```text
project/
├── frontend/   # Mobile application UI (frontend team)
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

## Frontend

The `frontend/` directory is reserved for the mobile app implementation. Add
the frontend project there when it is ready.
