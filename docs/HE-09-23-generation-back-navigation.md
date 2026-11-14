# HE-09/23 — Confirm back navigation during plan generation

## Finding

When the user presses Back while a plan-generation request is still running, leaving the screen immediately gives no chance to continue waiting or to cancel intentionally. The two HE findings are addressed together because they concern this same interruption and recovery flow.

## Change

- Back during the loading state opens a confirmation dialog with **뒤로 돌아가기 취소** and **계획 생성 취소**.
- **뒤로 돌아가기 취소** closes the dialog and leaves the active generation request running.
- **계획 생성 취소** cancels the in-flight generation request and returns to the study-time input screen. Previously entered availability and start times remain in the form.
- Cancellation is effective for Live HTTP requests: coroutine cancellation cancels the underlying OkHttp call. A late result from the cancelled request is ignored.
- The dialog is only offered while generation is loading. It does not interrupt the save operation after a successful response.

## Manual verification

The requester confirmed both emulator paths:

1. During generation, press Back. The confirmation dialog appears.
2. Select **뒤로 돌아가기 취소**. The dialog closes and generation continues.
3. During generation, press Back again and select **계획 생성 취소**. The app returns to study-time input with the previously entered values preserved.

The supplied post-change recording visibly captures the confirmation dialog and the return to the study-time input screen with Monday 4 hours, Wednesday 3 hours, and Sunday 2 hours retained. It does not visibly capture the intermediate dismissal-and-continue branch; that branch was separately confirmed by the requester during emulator testing.

## Evidence

- [Back confirmation dialog](evidence/he-09-23/01-generation-back-confirmation.png)
- [Confirmation options during generation](evidence/he-09-23/02-generation-cancel-confirmation.png)
- [Returned availability values preserved](evidence/he-09-23/03-returned-availability-values-preserved.png)

The first image is a frame from the supplied pre-change recording, showing the generation screen before its back-navigation confirmation was added. The latter two are frames from the supplied post-change recording. Both recordings remain at their original local paths and are not copied into this repository.

## Automated verification

`PlanFormViewModelTest` covers cancelling an active generation and ignoring its late result. `ApiHttpTest` covers cancellation propagation to the underlying HTTP call. See the test run recorded with the commit for the current branch result.
