# Iteration 3 CPU Server Deployment

This directory records the deployed Team 2 CPU B configuration and contains no credentials.
Server installation and maintenance require `ssh swpp-b` from the school network. Public API
verification works from any network.

## Recorded deployment (2026-10-30)

These settings and results describe the deployment recorded on 2026-10-30;
they were not rechecked against the running server during the 2026-11-01
documentation pass. Deployment documentation was merged by [PR #6](https://github.com/snuhcs-course/swpp-2026-project-team-02/pull/6)
into `iteration-3-pr4-base`, not `main`. Branch and verification scope are
summarized in the [documentation index](../docs/README.md).

- SSH alias: `swpp-b`
- Public host: `swpp26t02.147-46-242-174.sslip.io`
- Public API base URL: `http://swpp26t02.147-46-242-174.sslip.io/api/v1`
- Backend listener: `127.0.0.1:8000`
- Public listener: Nginx on `0.0.0.0:80`
- Service account: `swpp26-45`
- Repository: `/home/swpp26-45/apps/swpp-2026-project-team-02`
- systemd unit: `licenseplanner.service`

Nginx is the only public listener. Do not expose port 8000. The release APK permits cleartext
HTTP only for the assigned `.174` host; debug builds additionally permit local development hosts.

### Proxy read timeout

The checked-in `/api/v1/` location sets `proxy_read_timeout 300s`. Nginx applies it between two
successive reads from the backend, not to the whole request, so it is an idle limit rather than a
guaranteed completion time. Plan generation and adjustment answer only after all model calls
finish, sending no data meanwhile. 300 seconds covers the planner's nominal budget of six
45-second OpenRouter requests (adjustment allows five) plus local work, and matches the planner
scenario client's 300-second timeout. Nginx's 60-second default could discard valid operations.

The Android app keeps its 120-second call deadline, so it can time out before the proxy does. In
that case, existing recovery applies: the app reports an uncertain outcome, does not resend an
adjustment automatically, and reloads the plan only when the user asks. The backend may still
finish that request. The timeout reaches the deployed server only after a later deployment copies
this file to `/etc/nginx/sites-available/licenseplanner`, passes `sudo nginx -t`, and reloads Nginx
as shown below. Until then, the server keeps its previously installed configuration.

## Install or reconcile from the school network

The checked-in unit matches the current server. If another account or path is used later, update
the unit before installing it rather than editing an installed copy only.

```bash
ssh swpp-b

sudo apt update
sudo apt install -y nginx
cd /home/swpp26-45/apps/swpp-2026-project-team-02

sudo cp deploy/nginx/license-planner.conf /etc/nginx/sites-available/licenseplanner
sudo ln -sfn /etc/nginx/sites-available/licenseplanner /etc/nginx/sites-enabled/licenseplanner
sudo rm -f /etc/nginx/sites-enabled/default
sudo nginx -t

sudo cp deploy/systemd/license-planner.service /etc/systemd/system/licenseplanner.service
sudo systemctl daemon-reload
sudo systemctl enable --now licenseplanner.service
sudo systemctl reload nginx
```

The deployed checkout currently follows `iteration-3-pr4-base`. Update it without deleting the
directory because generated plans are stored inside the checkout.

```bash
sudo -u swpp26-45 git -C /home/swpp26-45/apps/swpp-2026-project-team-02 fetch origin
sudo -u swpp26-45 git -C /home/swpp26-45/apps/swpp-2026-project-team-02 switch iteration-3-pr4-base
sudo -u swpp26-45 git -C /home/swpp26-45/apps/swpp-2026-project-team-02 pull --ff-only
sudo systemctl restart licenseplanner.service
```

## Server-only model configuration

Generation and adjustment use the OpenRouter Responses API. Keep the key only in
`backend/.env`, owned by the service account with mode `600`.

```bash
sudo -u swpp26-45 bash -c '
  cd /home/swpp26-45/apps/swpp-2026-project-team-02/backend
  umask 077
  read -rsp "OpenRouter API key: " OPENROUTER_API_KEY && echo
  printf "OPENROUTER_API_KEY=%s\nOPENROUTER_MODEL=openai/gpt-6-luna\n" "$OPENROUTER_API_KEY" > .env
  unset OPENROUTER_API_KEY
'
sudo systemctl restart licenseplanner.service
```

Never place the API key, SSH private key, signing keystore, or signing passwords in Git, the APK,
CI artifacts, shell arguments, or documentation.

## Verification

From the server:

```bash
systemctl is-enabled licenseplanner.service
systemctl is-active licenseplanner.service
systemctl is-active nginx
sudo nginx -t
curl -fsS http://127.0.0.1:8000/api/v1/health
curl -fsS -H 'Host: swpp26t02.147-46-242-174.sslip.io' http://127.0.0.1/api/v1/health
```

From a non-campus network:

```powershell
python deploy/verify_public_api.py
```

The verifier is read-only. Actual model verification must separately create, query, and adjust a
plan. After a service restart or server reboot, query the same `schedule_csv_file` again to verify
persistence. On 2026-10-30, both service restart and full reboot recovery were verified, followed
by a successful off-campus generation, lookup, adjustment, CSV download, and retained-plan check.
These results were reported in the team chat on 2026-10-30 by Junwoo Cho (16:16 KST: service
restart with the saved CSV and plan lookup retained) and Hunil Son (17:32 KST: service restart,
full reboot and the off-campus flow). The 2026-10-30–31 device run did not repeat these restarts.

## Evaluator APK

Use the signed `app-live-release.apk` artifact produced by `.github/workflows/prod-apk.yml`. The
artifact URL, signature fingerprint, installation steps, and known limitations are recorded in
[`../ITER3_SUBMISSION.md`](../ITER3_SUBMISSION.md). Use [HE_EVALUATION.md](HE_EVALUATION.md) for
the fixed device flow.

The workflow's API URL (the `api_base_url` input, otherwise `LIVE_API_BASE_URL`) must be
`http://swpp26t02.147-46-242-174.sslip.io/api/v1` or an HTTPS URL. Gradle rejects any other HTTP
host before packaging because the release network policy would block it on the device.

## Limitations

- The [device report](../docs/iter3-HE/03_device_verification.md) records intermittent
  HTTP 307 adjustment failures on 2026-10-30–31. The cause is unresolved;
  successful retries or a later failure to reproduce do not establish a fix.
- The assigned public endpoint is HTTP, not HTTPS.
- The API has no authentication or per-user server storage.
- Generated plans are retained as CSV files under `backend/license_planner/output/`, up to the
  backend's configured retention limit.
- The OpenRouter provider can fail because of availability, quota, or network conditions. The app
  keeps the current state and offers retry or reload according to the API outcome.
- Server configuration changes, restarts, and reboot checks require the school-network SSH path.
