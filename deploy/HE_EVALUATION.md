# Iteration 3 Fixed HE Evaluation

Use a physical Android device on mobile data or non-campus Wi-Fi. Install the signed
`app-live-release.apk` artifact linked from `ITER3_SUBMISSION.md`.

## Fixed input

- Certification: 컴퓨터활용능력 2급 필기
- Current level: `기본 기능은 알아요`
- Assessment answers: `1=B, 2=A, 3=A, 4=B, 5=C`
- Preparation start: `2026-10-31`
- Exam date: `2026-11-30`
- Weekly availability:
  - Monday `18:00-21:00`
  - Wednesday `18:00-21:00`
  - Saturday `10:00-14:00`

The fixed answers produce three correct and two incorrect results with the pinned five-question
example bank.

## Required flow

1. Launch the app and complete the fixed input above.
2. Generate a plan and wait for either a result or a recoverable error.
3. Confirm that at least one study session is shown.
4. Return home and open the saved-plan list.
5. Open the generated plan and select one `study-N` session as missed.
6. Request adjustment and confirm that the selected session moves while unrelated rows remain.
7. Return to the plan list and reopen the same plan.
8. Force-stop and reopen the app, then confirm that the saved plan remains available.

## Failure checks

- Disable network access before generation. Confirm that the app remains open, preserves the form,
  and offers retry.
- Restore the network and retry once.
- Disable network access during adjustment. Confirm that the existing plan remains visible and the
  outcome is treated as uncertain.
- Restore the network. Reload the saved plan before submitting another adjustment.

## Evidence to record

- APK artifact link, filename, build commit, and signature verification
- Device model, Android version, and network type
- Public API base URL
- Screen recording of generation, saved-list lookup, adjustment, and app restart
- Server request timestamps when available
- Any provider failure, delay, or known limitation
