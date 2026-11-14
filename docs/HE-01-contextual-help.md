# HE-01 — Contextual help placement

## Issue context

General guidance was difficult to find when it was concentrated in one place, while some screens and actions needed their own explanation. The help entry points are now placed beside the related workflow and explain the relevant action in place.

## Changes

- Add a start guide on the home screen describing plan setup, local plan storage, and server processing.
- Add availability guidance beside weekday study-time controls, including the 30-minute increment and automatic end-time behavior.
- Add a calendar-reading guide on the availability screen covering the read period, Busy/Free and declined/deleted event handling, all-day and recurring events, and the no-permission manual-input path.
- Add saved-plan guidance on the plan list, distinguishing app-stored plans from phone calendar copies.
- Add adjustment guidance on the adjustment screen covering which missed session can move, what remains unchanged, and what happens when no time is available.
- Add calendar-export guidance where export is offered, including that exported copies are independent and repeated export can create duplicates.

## Evidence

The images below are the screenshots supplied with the HE-01 review and copied into this evidence folder. They show the DEMO badge; they document the UI content and placement in the captured build, not a separately verified Live release.

1. [Home screen help entry](evidence/he-01/01-home-context-help.png)
2. [Home screen start guide](evidence/he-01/02-home-start-help-dialog.png)
3. [Availability inline guide](evidence/he-01/03-availability-inline-guide.png)
4. [Calendar reading guide](evidence/he-01/04-availability-calendar-help-dialog.png)
5. [Plan screen export help entry](evidence/he-01/05-plan-export-help-entry.png)
6. [Calendar export guide](evidence/he-01/06-plan-export-help-dialog.png)
7. [Saved-plan help entry](evidence/he-01/07-saved-plans-help-entry.png)
8. [Saved-plan guide](evidence/he-01/08-saved-plans-help-dialog.png)
9. [Adjustment screen help entry](evidence/he-01/09-adjustment-help-entry.png)
10. [Adjustment guide](evidence/he-01/10-adjustment-help-dialog.png)

## Verification status

- Reviewed the supplied screenshots and matched them to the contextual-help entries above.
- `git diff --check` passed for the current working tree before the HE-01/HE-11 commits.
- Android build and automated tests were not run in this documentation pass.
