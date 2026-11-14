# HE-11 — Adjustment schedule navigation

## Issue context

The evaluator could interpret the change of week after schedule adjustment as a missing or lost schedule. The adjustment screen now makes the original position and the new destination explicit, and lets the user follow the moved schedule.

## Changes

- After a successful adjustment, keep the currently displayed week. Do not automatically switch to the adjusted schedule's week.
- Temporarily retain each removed study session at its original date and time. Render it with a strikethrough and the `조정 전 위치 · 임시` label.
- When a matching adjusted session exists, show its destination date and time on the temporary row. The temporary row is tappable.
- On tap, smoothly scroll to the destination. When the destination is in another week, scroll to the current week's edge, show the destination week, then scroll to the destination row.
- Animate a background highlight on the destination row so the user can identify where the session moved. The highlight remains briefly, then fades.
- Match the source to its destination by schedule ID. If no matching destination exists, do not show a fabricated link.
- Keep the original plan's availability and preparation/exam date context visible in the adjustment view.

## Evidence

The following images are cropped frames extracted from the screen recording supplied on 2026-11-14. The recording shows the DEMO build (including the `DEMO` badge and `로컬 시연 계산` label), so these frames document the UI flow and are not evidence of Live backend behavior.

1. [Adjustment result and current week](evidence/he-11/01-adjustment-result.png)
2. [Temporary original position and destination link](evidence/he-11/02-temporary-source-and-destination.png)
3. [Scroll in progress between weeks](evidence/he-11/04-scroll-in-progress.png)
4. [Destination schedule highlighted](evidence/he-11/05-destination-highlight.png)
5. [Destination schedule after the highlight](evidence/he-11/03-destination-schedule.png)

Source recording: `C:\Users\junwo\Videos\Captures\LicensePlanner 2026-11-14 16-01-53.mp4` (kept at the supplied local path; not copied into the repository).

## Verification status

- Manually reviewed the supplied screen recording and extracted the evidence frames above.
- `git diff --check` passed after the UI changes.
- Android build and automated tests were not run for this documentation update.
