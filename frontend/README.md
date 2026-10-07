# Frontend

Android app for the License Planner MVP (Kotlin, Jetpack Compose, single `:app`
module, minSdk 26, target SDK 36).

**Flow:** select 컴퓨터활용능력 2급 → enter preparation start and exam dates →
choose current level and answer example questions → set weekday study windows →
generate a plan → browse it by week and day.

**Scope:** written exam (필기) only; practical exam (실기) is not included. The
questions are authored examples, not official exam questions or a validated
diagnostic.

## Build and run

Open this `frontend/` directory (not the repository root) in Android Studio and
let Gradle sync. Pick a build variant: `demoDebug` (default) or `liveDebug`.

From the command line, using Android Studio's bundled Java runtime:

```bash
cd frontend
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew assembleDemoDebug assembleLiveDebug           # APKs in app/build/outputs/apk/
./gradlew testDemoDebugUnitTest testLiveDebugUnitTest   # unit tests
./gradlew installDemoDebug                              # install on a running emulator
```

## Flavors

| Flavor | Needs backend | Questions | Plans |
| --- | --- | --- | --- |
| `demo` (default) | No | Five bundled example questions | Local deterministic planner, labeled "demo" on every screen |
| `live` | Yes | `GET /certifications/computer_specialist_level_2/questions` | `POST /study-plans` (Gemini, server-side) |

**Use `demo` for evaluation without a backend.** Its plans are a demonstration
calculation, not backend or AI output. The live flavor never falls back to demo
data: on failure it shows an error with retry and keeps all inputs.

## Live backend

The live flavor connects to this repository's backend. Start it from `backend/`
with the project's Python environment and `GEMINI_API_KEY` configured in
`backend/.env`:

```bash
conda activate swpp
cd backend
python api_server.py    # http://127.0.0.1:8000/api/v1; GEMINI_API_KEY in backend/.env
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
backend; the app never calls Gemini directly.

Planning runs Gemini synchronously and can take tens of seconds (request timeout
120 s). The matching routes and JSON contract are documented in
[`backend/FRONTEND_INTEGRATION_GUIDE.md`](../backend/FRONTEND_INTEGRATION_GUIDE.md).

## Verification

- Unit tests pass for both flavors (JVM, with MockWebServer for HTTP).
- `contract_checks/verify_request.py` checks the request the app serializes
  against the pinned backend source, offline (no server or Gemini).
- Demo and live flows were run end to end on an emulator and compared with the
  Figma design.

## Known limitations

- Written exam only; practical-exam support is an open decision.
- Not tested on a physical device; TalkBack support is incomplete.
- Form state is held in memory and is lost if the process is killed.
- Live plans come from Gemini, so session counts can vary for the same inputs.
