# HE-12: Connection and server error recovery

## Finding

The Iteration 3 evaluator reported that an internet-related failure was described only as a server problem. The expected guidance should use plain language and help the user recover.

## Change

Transport failures are separated into DNS/no-route, timeout, and refused/unreachable host cases. Each has a distinct Korean recovery message without claiming that every network failure means the device is offline. Other unrecognized HTTP statuses and exceptions are classified as unknown cause.

Error screens for question loading, plan creation, and schedule adjustment include a **세부 메시지 보기** disclosure. It reveals the retained developer diagnostic (HTTP status and server message, exception class/message, or response parse detail) only when opened. Unrecognized error causes explicitly say that the cause is unknown and point to this disclosure.

The existing error mapping keeps connection failures (`IOException` and timeouts), HTTP 5xx failures, unavailable-server responses, invalid input, and API configuration errors in separate categories. Generation and adjustment failure screens retain the entered request/selection and offer retry; retry uses the same captured request. Adjustment outcomes that may have reached the server require reloading the latest plan instead of blindly resending the mutation.

## Error recovery checks

`ErrorMessagesTest`, the live repository tests and the view-model tests cover the
error-cause mapping and retained diagnostics used by **세부 메시지 보기**. To
reproduce a connection failure on an emulator, point a live debug build at an
unreachable API URL, follow the normal question-loading flow, and open the
disclosure on the resulting error screen.

## Completion checks

- DNS/no-route failure: show network/address guidance, preserve the current input, and allow retry.
- Connection timeout: show a timeout-specific message, preserve the current input, and allow retry.
- Refused/unreachable host: show a server-connection message, preserve the current input, and allow retry.
- HTTP 5xx: show a server processing error with a retry suggestion, without labeling it as an internet outage.
- HTTP 503: show that the server is not ready and suggest trying again later.
- Unrecognized HTTP status, exception, or invalid-input detail: say the cause is unknown and expose the original diagnostic after the user opens the disclosure.
- Invalid input and API configuration errors: retain their own guidance and expose the recognized status/message as developer detail.

## Verification

- `:app:testLiveDebugUnitTest :app:testDemoDebugUnitTest`: passed after the HE-11 and HE-12 expectation updates (Live 264 tests, Demo 266 tests; one permission-assumption test is skipped in each flavor because the local user can still open the file).
- `:app:compileDemoDebugKotlin :app:compileLiveDebugKotlin`: passed after adding the Demo manual-test launcher and request fault injection.
- Emulator captures confirm the question-load and plan-generation error screens show the selected user guidance and expanded synthetic developer diagnostic. Other selectable cases can be exercised through the steps above.

## Evidence

The post-change screenshots supplied on 2026-11-14 are now stored in the repository:

- [Example-question request error with developer details](evidence/he-12/01-question-error-detail.png)
- [Plan-generation request error with developer details](evidence/he-12/02-generation-error-detail.png)
- [Plan-generation error screen before opening details](evidence/he-12/03-generation-error-entry.png)
- [Plan-generation error screen with details expanded](evidence/he-12/04-generation-error-details-expanded.png)

The additional 2026-11-14 20:52 recording shows the plan-generation failure screen and the transition to the expanded developer detail. The diagnostic shown is `HTTP 502 / Study plan generation failed. Try again later.` The Android Studio frame shows the `liveDebug` build variant; the recording does not include enough backend/log context to establish whether this 502 came from the live server or a test injector. These captures document the generation error flow, but do not document every selectable error category or the adjustment error flow.
