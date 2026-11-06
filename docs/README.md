# Documentation and verification

Reviewed on 2026-11-06. The tables below consolidate dated evidence. Follow the
source revision attached to a result rather than assuming every branch or APK
contains the same behavior.

## Find the right guide

| Purpose | Document |
| --- | --- |
| Local setup, scope and open decisions | [Repository README](../README.md) |
| Android build, flavors, saved plans and recovery | [Frontend README](../frontend/README.md) |
| Backend commands, model configuration and offline checks | [Backend README](../backend/README.md) |
| Android HTTP request/response contract | [Frontend integration guide](../backend/FRONTEND_INTEGRATION_GUIDE.md) |
| Calendar conflict-aware scheduling decision and P29→P30 contract | [P30 scheduler approach](P30_SCHEDULER_APPROACH.md) |
| Iteration 3 CI scope, reproduction commands, and evidence boundary | [Testing Documentation](TESTING_DOCUMENTATION.md) |
| P16/P18/P19/P30 ownership, CI and deployment handoff | [Test automation and P30 evidence](ITER3_TEST_AUTOMATION_AND_P30_EVIDENCE.md) |
| Backend tools, validation and historical comparisons | [Developer guide](../backend/DEVELOPER_GUIDE.md) |
| Server setup, public endpoint and operating checks | [Deployment guide](../deploy/README.md) |
| Evaluator download, installation, tasks and limitations | [HE submission package](iter3-HE/04_submission_package.md) |
| Fixed inputs and expected outcomes | [HE cases](iter3-HE/02_he_cases.md), [deployment walkthrough](../deploy/HE_EVALUATION.md) |
| Device, network, outcomes and failure record | [Device verification](iter3-HE/03_device_verification.md) |
| Example questions and scope review | [Question review](iter3-HE/01_question_review.md) |
| Historical Gemini measurements and reproduction | [Performance package](performance/README.md) |

## Branch and publication status

GitHub PR metadata was checked on 2026-11-01. This is a dated snapshot.

| Source | Status and scope |
| --- | --- |
| `main` | Contains the local setup/API/Android guides, CI and historical performance package. Deployment and HE branch evidence must not be presented as a completed merge to `main`. |
| [PR #5](https://github.com/snuhcs-course/swpp-2026-project-team-02/pull/5), `iteration-3` → `main` | Open at `26e1a9c6ba406fe592a999db48667bd92c857238`; description is still the unfilled template. It is a different source from the evaluator APK and this HE branch. |
| [PR #6](https://github.com/snuhcs-course/swpp-2026-project-team-02/pull/6) | Merged into `iteration-3-pr4-base` at `7dca5bfb7c90603fcb51d9825640b02142a34eae`; adds deployment configuration and documentation. |
| [PR #7](https://github.com/snuhcs-course/swpp-2026-project-team-02/pull/7), `docs/iter3-he-package` → `iteration-3-pr4-base` | Open at `24d9f9f`; includes four HE documents. Its description still lists three. |
| [Wiki](https://github.com/snuhcs-course/swpp-2026-project-team-02/wiki) | Publication is separate from repository edits. The fetched Wiki snapshot still has older provider, test-command and persistence descriptions; local correction drafts have not been published. |

## Verification status

| Area | Recorded evidence | What remains unverified |
| --- | --- | --- |
| Offline CI | [CI #57](https://github.com/snuhcs-course/swpp-2026-project-team-02/actions/runs/42013613486), `e95db19`, passed Backend, Android and the fixed-backend Android live E2E job. [Testing Documentation](TESTING_DOCUMENTATION.md) records commands, coverage and artifacts. | CI never calls OpenRouter or the public server. |
| Evaluator build | [Prod APK run #34](https://github.com/snuhcs-course/swpp-2026-project-team-02/actions/runs/42028937748) succeeded from `iteration-3-scheduler` at `5216f41`; its signed artifact is retained for 30 days. | The certificate fingerprint identifies the signer, not the APK bytes. A local debug APK is a separate build. |
| Current public-server signed-APK run | 2026-11-06: generated/reopened a plan, verified deadline-bounded adjustment feedback, and re-opened the adjusted plan on an Android emulator using the public `.174` API. | This is emulator evidence, not a physical-device compatibility claim. Historical HTTP 307 adjustment observations remain open. |
| Public API and server persistence | [Deployment record](../deploy/README.md#verification), 2026-10-30: service restart/full reboot, off-campus generation, lookup, adjustment, CSV download and retained-plan checks. Provider recorded as OpenRouter `openai/gpt-6-luna`. | Exact deployed backend commit and raw request/file comparisons are not attached to the summary. No current uptime check was performed here. Adjustment of a plan created before a restart is [not recorded](iter3-HE/02_he_cases.md). |
| Physical device | [2026-10-30 23:10–10-31 01:00 KST report](iter3-HE/03_device_verification.md): Galaxy S26 Ultra, Android 17, off-campus LG U+ 5G; generation, reopen after app exit, adjustment and failure recovery. Server-restart result is attributed to a separate 10/30 team check ([deployment record](../deploy/README.md#verification)). | Adjustment is conditional: intermittent HTTP 307 required about four retries. Cause and fix remain unresolved. One device/network run is not broad compatibility or reliability evidence. |
| Recovery and isolation | Frontend controlled tests cover conflicts, missing plans, uncertain results and explicit reload. The device report also records reload after a lost adjustment response. | The full two-plan response/stored-file/CSV comparison, original-window isolation, unchanged-profile and successive-request-count matrix is not attached to the deployed device report. |
| Performance and AI quality | [Historical package](performance/README.md): `d706ad2`, 2026-10-15, Gemini `gemini-3.5-flash-lite`, 17 inputs × 5 repeats, 77/85 successes. Device report has descriptive workload/latency observations. | These do not establish current OpenRouter success rate, latency distribution or pedagogical adequacy. A current frozen ≥30-case evaluation with predeclared thresholds is not supplied by these records. |

The HTTP 307 failure is observed; an Nginx or network redirect is only a hypothesis.
A successful retry or later non-reproduction does not close it. Keep that issue
visible in evaluator instructions until a cause, correction and retest are recorded.

## Reading historical evidence

Preserve dated counts, raw logs and archived reports. Gemini references in the
performance package describe its original experiment; current backend setup uses
`OPENROUTER_API_KEY`. Record a new source/build, model, inputs, date, commands and
result when extending evidence. Documentation changes alone close no runtime gate.
