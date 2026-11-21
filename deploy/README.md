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

Nginx is the only public listener. Do not expose port 8000: in public mode the backend trusts the
client address that any loopback connection puts in `X-Real-IP`. The release APK permits cleartext
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

## Public mode

The checked-in unit runs the backend in public mode with two settings (each exactly `0` or `1`):

| Setting | Unit value | Effect |
| --- | --- | --- |
| `APP_TRUST_LOCAL_PROXY` | `1` | Per-IP request limits use the single `X-Real-IP` that the loopback Nginx sets. A budgeted POST from loopback without exactly one valid value returns `503 SERVICE_UNAVAILABLE`, so a direct `curl -X POST http://127.0.0.1:8000/...` on the server is refused by design. `X-Forwarded-For` is never used. |
| `APP_SHARED_AVAILABILITY_ENABLED` | `0` | `GET` and `PUT /api/v1/availability` return `404 NOT_FOUND`, and generation and adjustment never read the shared profile file. The file itself is left in place. |

Keep both in the unit. systemd lets a value from `EnvironmentFile=` override `Environment=`, so do
not add either setting to `backend/.env`. Without them the backend falls back to local mode
(socket-peer limits behind the proxy, which would put every public client in one bucket, and a
public shared profile).

### Device tokens

Every plan route (`POST /study-plans`, `GET /study-plans`, `GET /study-plans/{filename}` and all
modes of `POST /schedules/adjust`) needs `Authorization: Bearer <64 lowercase hex characters>`.
The live app creates one token per installation. Health, the certification list and questions stay
public, so `verify_public_api.py` needs no token. APKs built before device tokens get `401
INVALID_DEVICE_TOKEN` on every plan route; release the backend and a token-sending APK together
(see [Cutover with a legacy plan wipe](#cutover-with-a-legacy-plan-wipe)). The contract is in
[`../backend/FRONTEND_INTEGRATION_GUIDE.md`](../backend/FRONTEND_INTEGRATION_GUIDE.md#device-token).

The endpoint is cleartext HTTP. Anyone who can observe traffic on the network path can read a
token and replay it to list, download and adjust that installation's plans and to spend its quota.
This is an accepted risk of the current release, not account authentication: there is no sign-in,
recovery or transfer, and HTTPS is out of scope.

Nginx forwards the `Authorization` header unchanged (the site sets no override). The site's
`licenseplanner_api` access-log format records the address, time, request line, status, size,
referrer, user agent and duration only. Do not add `$http_authorization`, `$http_*` header dumps or
debug-level error logging to the installed site, and keep tokens out of shell history, issue text
and evidence; record owners as labels such as A and B.

### Request limits

The backend admits each POST route within rolling 60-minute windows, counted per token owner, per
client IP and for the whole process: generation 3/12/24 and adjustment 12/40/60. A full bucket
returns `429 RATE_LIMITED` with `Retry-After`. Counters live in process memory: a restart resets
them, and the limits hold for this single-process deployment only. Clients behind one NAT share
the IP bucket and can reach it early. The whole-process ceiling bounds admitted work, but the
OpenRouter charge still depends on model pricing and call counts; keep the provider's own spending
limit set.

### Owner-scoped plan storage

Each token's plans are stored in `<plan root>/<SHA-256 hex digest of the token>/`, where the plan
root is `APP_STUDY_PLAN_DIR` or the default `backend/license_planner/output/`. The raw token is not
stored. Retention keeps up to 100 newest plans per owner, so total disk use grows with the number of
installations; an uninstalled app leaves its directory behind. CSV files directly in the plan root
(written before owner scoping) are never read, listed or pruned through the API.

Never return an unscoped (pre-token) backend to public service, even as a rollback: it would expose
every owner's plans and reopen anonymous generation. If the scoped backend cannot start, keep the
plan service stopped until it is repaired or a tested owner-scoped revision is restored.

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
archive holds every plan file, including those in owner directories. The two counts must match.
Keep every `.csv`/`.meta.json` pair together, and never delete sidecars during an update or
rollback; the only exception is the reviewed wipe in
[Cutover with a legacy plan wipe](#cutover-with-a-legacy-plan-wipe):

```bash
PLAN_DIR=/absolute/plan/directory   # "$REPO/backend/license_planner/output" only when not overridden
test -d "${PLAN_DIR:?}"
BACKUP="/home/swpp26-45/plan-backup-$(date +%Y%m%d%H%M%S).tgz"
sudo -u swpp26-45 tar -C "$PLAN_DIR" -czf "$BACKUP" .
sudo -u swpp26-45 find "$PLAN_DIR" -type f \( -name '*.csv' -o -name '*.meta.json' \) | wc -l
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

Production updates, configuration changes and restarts need the owner's agreement. When the update
changes `deploy/`, also install the unit and site as in
[Install or reconcile](#install-or-reconcile-from-the-school-network) (`sudo nginx -t` before the
Nginx reload, `sudo systemctl daemon-reload` before the restart).

### Cutover with a legacy plan wipe

Moving the public server from shared to owner-scoped storage is a coordinated release. The legacy
plans in the plan root are demo data that the new backend can no longer reach; deleting them is
authorized only for this cutover, after the backup above, and only as follows.

1. **Stage both candidates.** Identify the backend ref to deploy. Build and verify the signed live
   APK for the public URL (see [Release APK](#release-apk)) and keep it outside the served path.
   Record the installed revision and settings (see [Before an update](#before-an-update)), the
   actual APK download URL, its file and the served file's SHA-256. Diff the installed unit and
   site against the candidate templates and keep any installed APK location; an unexplained
   difference blocks the cutover. Run `sudo nginx -t` with the staged site.
2. **Stop writes.** `sudo systemctl stop licenseplanner.service`, then confirm
   `systemctl is-active licenseplanner.service` no longer prints `active`.
3. **Back up and inventory.** Run the backup and count check above for the resolved `PLAN_DIR`.
   Confirm `realpath -- "$PLAN_DIR"` matches the directory resolved before the stop, then list the
   candidates:

   ```bash
   sudo -u swpp26-45 find "$PLAN_DIR" -maxdepth 1 -type f \( -name '*.csv' -o -name '*.meta.json' \) -print
   ```

4. **Delete reviewed files only.** Compare the list with the expected generated demo plans and
   sidecars (orphan sidecars included). Delete exactly those regular files by path, one
   `sudo -u swpp26-45 rm -- "<path>"` each, with no recursion, glob or symlink target. Keep the
   root directory and its permissions, every subdirectory and every other file. Rerun the
   inventory and record how many files were removed. If the directory, an unexpected file or
   ownership cannot be reconciled, stop and record the blocker instead.
5. **Activate.** Update the checkout to the staged ref, install the unit and site, then run
   `sudo systemctl daemon-reload`, `sudo nginx -t`, `sudo systemctl reload nginx` and
   `sudo systemctl restart licenseplanner.service`. Record the running revision and time.
6. **Verify before announcing.** Run the server checks and `verify_public_api.py` under
   [Verification](#verification), the token and profile gates, and the two-owner check. If any
   gate fails, stop the service rather than rolling back to an unscoped revision.
7. **Publish the APK.** Replace only the confirmed served APK with the staged one by an atomic
   rename on the same filesystem, keeping the previous APK outside the served path. Download it
   from the public URL and match its SHA-256 and signer to the staged artifact. Record the APK and
   backend revisions together.

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

Public mode gates, from any network (none of these spends model credit):

```bash
API=http://swpp26t02.147-46-242-174.sslip.io/api/v1
curl -s -o /dev/null -w '%{http_code}\n' "$API/study-plans"                   # 401
curl -s -o /dev/null -w '%{http_code}\n' -X POST "$API/study-plans" \
  -H 'Content-Type: application/json' -d '{}'                                # 401
token="$(python3 -c 'import secrets; print(secrets.token_hex(32))')"
curl -s -o /dev/null -w '%{http_code}\n' -H "Authorization: Bearer $token" "$API/availability"   # 404
curl -s -o /dev/null -w '%{http_code}\n' -X PUT -H "Authorization: Bearer $token" \
  -H 'Content-Type: application/json' -d '{}' "$API/availability"            # 404
unset token
```

The verifier is read-only (it checks `/health`, the certification list and the public question
fields). Passing it does not
verify model generation, adjustment, persistence or owner isolation. Those writes need a separate,
agreed check. Use disposable tokens kept only in shell variables, one per simulated installation,
and reuse each for every request about its plans:

1. As owner A, create a plan with `POST /api/v1/study-plans` and note its `schedule_csv_file`.
   As owner B, create one synthetic plan too. Each listing shows only its own plan.
2. As B, read back A's identifier (`{"plans": []}`), download A's `schedule_csv_url` and adjust
   A's plan (both `404 PLAN_NOT_FOUND`); confirm A's rows are unchanged.
3. Restart `licenseplanner.service` (or reboot the server) from the school network.
4. As A, read the plan back with `GET /api/v1/study-plans?schedule_csv_file=<URL-encoded
   identifier>`, confirm the same rows, and download it from its `schedule_csv_url`.
5. As A, adjust one of its study sessions to confirm its stored exam deadline survived the restart.

Each generation is a paid model request; do not load-test production or retry generation
automatically. The request limits and proxy-address handling are checked offline and against a
local proxy, not by exhausting the public server.

Record the server revision from [Before an update](#before-an-update), the times, the owner
labels and the identifiers used, never the tokens. Earlier restart, reboot and off-campus results are kept in the
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

A release built before device tokens cannot use the plan routes of a token-enforcing server.
Publish the token-sending APK together with that backend, as in
[Cutover with a legacy plan wipe](#cutover-with-a-legacy-plan-wipe). An in-place update keeps the
installation's local plans viewable; reinstalling or clearing app data creates a new token that
cannot reach the earlier server plans.

The workflow's API URL (the `api_base_url` input, otherwise `LIVE_API_BASE_URL`) must be
`http://swpp26t02.147-46-242-174.sslip.io/api/v1` or an HTTPS URL. Gradle rejects any other HTTP
host before packaging because the release network policy would block it on the device.

## Limitations

- The October device check recorded intermittent HTTP 307 responses to schedule adjustment.
  The backend handler has no 307 response, and the installed Nginx site inspected on
  2026-11-22 KST proxied `/api/v1/` without a redirect rule. Retained access logs covered
  November 5–20 UTC and contained no 307 for the adjustment route. The October incident
  predates those logs, so its cause remains unresolved. A controlled same-host redirect test
  confirms the app surfaces the 307 and sends a second POST only after an explicit retry.
  [October device report](https://github.com/snuhcs-course/swpp-2026-project-team-02/blob/6d8af7fe65295f286c881d7d6c059c4844bd633f/docs/iter3-HE/03_device_verification.md)
- The assigned public endpoint is HTTP, not HTTPS, so device tokens can be observed and replayed
  on the network path (see [Device tokens](#device-tokens)).
- Plans are isolated per anonymous installation token, not per account. There is no sign-in,
  token recovery or plan transfer, and request limits reset when the service restarts.
- Generated plans are retained as CSV files in one directory per token under the plan root
  (`backend/license_planner/output/` unless `APP_STUDY_PLAN_DIR` is set), up to 100 per token. With a backend that stores exam deadlines, each new plan
  also has a `.meta.json` sidecar. Plans without one stay viewable and downloadable, but their
  study sessions cannot be adjusted (HTTP 422 asking for a new plan). This is an accepted
  compatibility break; a rollback to an older backend keeps the files but restores its weaker
  client-supplied deadline behavior.
- The OpenRouter provider can fail because of availability, quota, or network conditions. The app
  keeps the current state and offers retry or reload according to the API outcome.
- Server configuration changes, restarts, and reboot checks require the school-network SSH path.
