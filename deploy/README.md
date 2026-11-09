# CPU Server Deployment

This directory holds the checked-in deployment templates for the Team 2 CPU B server and contains
no credentials. Server installation and maintenance require `ssh swpp-b` from the school network.
Public API verification works from any network.

## Topology

The templates in `nginx/` and `systemd/` describe the intended setup below. They are not proof of
what is installed: the running server may use another revision, branch, unit or Nginx
configuration. Inspect the installed state (see [Before an update](#before-an-update)) before
relying on it or changing it.

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

The checked-in unit expects the service account and repository path listed above. If another
account or path is used, update the unit in the repository before installing it rather than editing
an installed copy only. Compare an existing installation with the templates first (see
[Before an update](#before-an-update)).

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

### Before an update

This repository does not record which revision or configuration is deployed. Check it on the
server before every update, and record what you find with the change:

```bash
REPO=/home/swpp26-45/apps/swpp-2026-project-team-02
sudo -u swpp26-45 git -C "$REPO" rev-parse HEAD
sudo -u swpp26-45 git -C "$REPO" status --short --branch
systemctl cat licenseplanner.service
diff "$REPO/deploy/systemd/license-planner.service" /etc/systemd/system/licenseplanner.service
diff "$REPO/deploy/nginx/license-planner.conf" /etc/nginx/sites-available/licenseplanner
```

The backend stores generated plans in `APP_STUDY_PLAN_DIR` when its process environment sets it
(through the unit's `Environment=`, a drop-in, or its `EnvironmentFile`), and otherwise in
`backend/license_planner/output/` inside the checkout. A relative value is resolved against the
service's working directory. Update the checkout in place and never replace it with a clean copy.
Backends that store exam deadlines write a `.meta.json` file beside each newly generated plan CSV.

Establish the directory the installed service actually uses before backing it up. Run this block
in bash. It prints only `APP_STUDY_PLAN_DIR` assignments and non-secret metadata (the working
directory and environment-file paths), never a whole environment or environment file. It prints
`STOP` and exits non-zero whenever a lookup fails:

```bash
(
  UNIT=licenseplanner.service KEY=APP_STUDY_PLAN_DIR
  stop() { echo "STOP: $*. Do not assume the default directory." >&2; exit 1; }
  PID=$(systemctl show -p MainPID --value "$UNIT") || stop "cannot query $UNIT"
  [ "$PID" -gt 0 ] 2>/dev/null || stop "$UNIT is not running (MainPID=$PID)"
  sudo test -r "/proc/$PID/environ" || stop "cannot read the process environment"
  CWD=$(sudo readlink "/proc/$PID/cwd") || stop "cannot read the process working directory"
  echo "working directory: $CWD"
  sudo grep -az "^$KEY=" "/proc/$PID/environ" | tr '\0' '\n' | sed 's/^/running process: /'
  case ${PIPESTATUS[0]} in
    0) ;; 1) echo "running process: $KEY not set" ;; *) stop "cannot read the process environment" ;;
  esac
  UNIT_ENV=$(systemctl show -p Environment --value "$UNIT") || stop "cannot read the unit environment"
  MATCH=$(tr ' ' '\n' <<<"$UNIT_ENV" | grep "^$KEY=") && echo "unit Environment: $MATCH" \
    || echo "unit Environment: $KEY not set"
  FILES=$(systemctl show -p EnvironmentFiles --value "$UNIT") || stop "cannot list environment files"
  while read -r FILE _; do
    [ -n "$FILE" ] || continue
    sudo grep -H "^$KEY=" "$FILE"
    case $? in 0) ;; 1) echo "$FILE: $KEY not set" ;; *) stop "cannot read $FILE" ;; esac
  done <<<"$FILES"
)
```

Conclude that no override is set only when the block exits with status 0 and reports the key as
not set for the running process, the unit `Environment` and every environment file. A `STOP`
line, including one for a stopped service, gives no conclusion: do not use the default directory.
Confirm the directory with the owner and set `PLAN_DIR` to that explicit path instead. Resolve a
relative value against the printed working directory. If the running value differs from a
configured one, a restart would switch directories: stop and resolve this with the owner before
updating.

Set `PLAN_DIR` to that explicit absolute path, back up the whole directory, and check that the
archive holds every plan file. The two counts must match. Keep every `.csv`/`.meta.json` pair
together, and never delete sidecars during an update or rollback:

```bash
PLAN_DIR=/absolute/plan/directory   # "$REPO/backend/license_planner/output" only when not overridden
test -d "${PLAN_DIR:?}"
BACKUP="/home/swpp26-45/plan-backup-$(date +%Y%m%d%H%M%S).tgz"
sudo -u swpp26-45 tar -C "$PLAN_DIR" -czf "$BACKUP" .
sudo -u swpp26-45 find "$PLAN_DIR" -maxdepth 1 -type f \( -name '*.csv' -o -name '*.meta.json' \) | wc -l
sudo -u swpp26-45 tar -tzf "$BACKUP" | grep -cE '\.(csv|meta\.json)$'
```

### Update

With `REPO` set as above, choose the branch to deploy explicitly, then fast-forward and restart:

```bash
sudo -u swpp26-45 git -C "$REPO" fetch origin
sudo -u swpp26-45 git -C "$REPO" switch <branch>
sudo -u swpp26-45 git -C "$REPO" pull --ff-only
sudo -u swpp26-45 git -C "$REPO" rev-parse HEAD
sudo systemctl restart licenseplanner.service
```

Production updates, configuration changes and restarts need the owner's agreement.

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

The verifier is read-only (it checks `/health`, the certification list and the public question
fields). Passing it does not
verify model generation, adjustment or persistence. Those writes need a separate, agreed check:

1. Create a plan through the app or `POST /api/v1/study-plans` and note its `schedule_csv_file`.
2. Restart `licenseplanner.service` (or reboot the server) from the school network.
3. Read the plan back with `GET /api/v1/study-plans?schedule_csv_file=<URL-encoded identifier>`
   and confirm the same rows; download it from its `schedule_csv_url`.
4. Adjust one of its study sessions to confirm its stored exam deadline survived the restart.
   A plan created before the server stored deadlines answers HTTP 422 asking for a new plan.

Record the server revision from [Before an update](#before-an-update), the times, and the
identifier used. Earlier restart, reboot and off-campus results are kept in the
[archived deployment verification records](https://github.com/snuhcs-course/swpp-2026-project-team-02/blob/6d8af7fe65295f286c881d7d6c059c4844bd633f/deploy/README.md#verification); they do not establish the current state.
The Android-side procedure is in
[`../frontend/README.md`](../frontend/README.md#manual-live-check).

## Release APK

The signed live release APK is built by the manually dispatched `Prod APK` workflow
(`.github/workflows/prod-apk.yml`). It verifies the signature with `apksigner` and uploads
`app-live-release.apk` as the artifact `live-release-apk-<commit SHA>`, kept for 30 days; downloading
it needs GitHub access. Record the workflow run and source commit with every result that uses it.
Before installing, check the certificate with
`apksigner verify --print-certs app-live-release.apk`; the fingerprint identifies the signer, not
the APK contents.

Installing it outside an app store may require allowing installs from that source and dismissing a
Play Protect warning. A release APK updates an existing installation only when both use the same
signing key; otherwise uninstall the old app first, which deletes its device-local plans. Debug
builds use a different certificate (see
[`../frontend/README.md`](../frontend/README.md#physical-device-against-the-public-server)).

The workflow's API URL (the `api_base_url` input, otherwise `LIVE_API_BASE_URL`) must be
`http://swpp26t02.147-46-242-174.sslip.io/api/v1` or an HTTPS URL. Gradle rejects any other HTTP
host before packaging because the release network policy would block it on the device.

## Limitations

- Intermittent HTTP 307 adjustment failures were observed on the deployed server (see the
  [2026-10-30–31 device report](https://github.com/snuhcs-course/swpp-2026-project-team-02/blob/6d8af7fe65295f286c881d7d6c059c4844bd633f/docs/iter3-HE/03_device_verification.md)). The backend sends no 307; an Nginx or
  network redirect is only a hypothesis. The cause is unresolved, and successful retries or a later
  failure to reproduce do not establish a fix.
- The assigned public endpoint is HTTP, not HTTPS.
- The API has no authentication or per-user server storage.
- Generated plans are retained as CSV files under `backend/license_planner/output/`, up to the
  backend's configured retention limit. With a backend that stores exam deadlines, each new plan
  also has a `.meta.json` sidecar. Plans without one stay viewable and downloadable, but their
  study sessions cannot be adjusted (HTTP 422 asking for a new plan). This is an accepted
  compatibility break; a rollback to an older backend keeps the files but restores its weaker
  client-supplied deadline behavior.
- The OpenRouter provider can fail because of availability, quota, or network conditions. The app
  keeps the current state and offers retry or reload according to the API outcome.
- Server configuration changes, restarts, and reboot checks require the school-network SSH path.
