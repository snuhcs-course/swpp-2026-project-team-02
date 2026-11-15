# HE-01 — Contextual help placement

## Issue context

General guidance was difficult to find when it was concentrated in one place, while some screens and actions needed their own explanation. The help entry points are now placed beside the related workflow and explain the relevant action in place.

## Changes

- Add a start guide on the home screen describing plan setup and local plan storage. The demo guide says generation and adjustment are calculated on this device for demonstration; the live guide says the server processes them.
- Add availability guidance beside weekday study-time controls. It originally described the 30-minute increment and automatic end time of the old editor; it now describes adding time windows with a typed start time and minutes, overnight times and per-week changes.
- Add a calendar-reading guide on the availability screen covering the read period, Busy/Free and declined/deleted event handling, all-day and recurring events, and the no-permission manual-input path.
- Add saved-plan guidance on the plan list, distinguishing app-stored plans from phone calendar copies.
- Add adjustment guidance on the adjustment screen covering which missed session can move, what remains unchanged, and what happens when no time is available. It also explains 시간 직접 옮기기 for moving a selected session to a chosen time and 남은 공부 다시 계획하기 for gathering unfinished study minutes and placing them again before the exam.
- Add calendar-export guidance where export is offered, including that exported copies are independent and repeated export can create duplicates.

## Evidence

The first two images are the user's session-supplied pre-change captures. They show the home and availability screens before the contextual-help entries were added. The remaining screenshots were supplied with the HE-01 review and show the updated help placement. The post-change images show the DEMO badge; they document the UI content and placement in the captured build, not a separately verified Live release.

### Before

Screenshots were captured during review and are not stored in the repository.

### After

Screenshots were captured during review and are not stored in the repository.

## Verification status

- Reviewed the supplied screenshots and matched them to the contextual-help entries above.
- `git diff --check` passed for the current working tree before the HE-01/HE-11 commits.
- Android build and automated tests were not run in this documentation pass.
