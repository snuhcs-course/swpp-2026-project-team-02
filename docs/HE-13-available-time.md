# HE-13 — Available time and planned study time

## Finding

The Iteration 3 evaluator sheet records a **major** concern under “Flexibility and efficiency of use.” One evaluator reported setting 14 available hours per week but seeing less than 14 hours assigned, and said sessions were spread across alternating weekdays (for example, Monday/Wednesday/Friday one week and Tuesday/Thursday/Saturday the next). The evaluator recommended using all available time and distributing study across the week. The consolidated Improvement Plan labels HE-13 **medium** priority, so its priority label differs from the evaluator's original severity.

The finding must be read with the user's existing pre-change capture: the generated-plan summary already reported both total available time and assigned study time, and said that the remaining capacity was left as spare time. The evaluator's request to consume all availability is feedback, not an agreed scheduling rule. The current improvement-plan row therefore treats the **transparency of available versus assigned time** as the implemented portion and keeps assignment amount and distribution policy as a separate decision.

## Current implementation

- Since 2026-11-15, diagnosed plans use every usable entered minute (the entered windows inside the preparation period minus calendar conflicts); the backend, not the model, sets each date's total. Review rounds from the HE branch were not merged.
- The backend builds a deterministic summary from the accepted schedule result. It states planned minutes and usable minutes; the earlier spare-time sentence was removed because no capacity is left spare: `backend/license_planner/agent.py` (`_build_summary`).
- Under the existing week navigator, both the plan screen and plan adjustment screen display the same daily bar comparison for the selected week. It compares each weekday's available minutes and assigned study minutes, derived from the saved request windows, busy calendar rows, and current plan schedules. Days with no available time are omitted; the graph follows the selected week as the user changes weeks. The chart note identifies the gray available and black assigned bars and says calendar conflicts are removed from available time; it no longer says some availability may stay unused. The plan screen retains the backend's nonblank `agent_summary` alongside the graph, so the overall available/planned totals are not replaced by the weekly view: `frontend/app/src/main/java/com/swpp/licenseplanner/ui/screens/PlanScreen.kt`, `frontend/app/src/main/java/com/swpp/licenseplanner/ui/screens/PlanAdjustmentScreen.kt`, and `frontend/app/src/main/java/com/swpp/licenseplanner/domain/WeeklyTimeUsage.kt`.
- The weekly graph is UI behavior and can be checked with Demo data. Demo plans are calculated locally and do not provide the backend `agent_summary`; therefore, Demo evidence verifies the graph, zero-availability filtering, and guidance, but does not verify the Live backend summary or schedule generation.
- Implementation status: the requested weekly graph and its explanatory text are implemented on both screens, and the assignment amount changed as described above. The consolidated Improvement Plan still labels HE-13 as partially implemented. How sessions spread across weekdays follows the entered windows and is not separately decided.

## Scope and interpretation

1. Keep showing usable and planned time together so the user can see the difference.
2. ~~Explain remaining capacity when the plan intentionally schedules less than the usable total.~~ Superseded: plans no longer leave capacity unused.
3. ~~Do not force the scheduler to consume every available minute solely to satisfy this comment; determine the intended planning policy separately.~~ Superseded on 2026-11-15, when plans began to use every usable entered minute.
4. Do not treat weekly distribution as fixed by HE-13 without confirming the product requirement. Record the evaluator's alternating-week observation as a follow-up policy question.
5. A separate evaluator comment asks for multiple non-contiguous availability windows in one day. That input-flexibility concern is now implemented separately: the app supports multiple daily study windows.

## Evidence record

The user also supplied this pre-change plan screenshot in the conversation. It shows the overall available and assigned totals plus spare-time text, but no per-week graph. This confirms which transparency was already present before HE-13 and why the new graph is complementary.

Screenshots were captured during review and are not stored in the repository.

On 2026-11-14, before plans began to fill every usable minute, the user supplied post-change screenshots and a screen recording of the adjustment screen:

- **Plan screen:** graph shows Monday Nov 16 (1 h assigned / 4 h available), Wednesday Nov 18 (1 h / 3 h), and Sunday Nov 22 (1 h / 3 h). The other days, with no availability, are omitted. The note identifies available and assigned bars and says some available time may remain unused.
- **Plan adjustment screen:** the same week and values appear; its day cards also display the configured availability windows. The same zero-availability days are absent from the graph.
- The 20:55 recording steps through weeks 1, 2, and 3 (Nov 9–15, Nov 16–22, and Nov 23–29). The selected-week label, available-day rows, and graph update as the right arrow is pressed. Weeks 2 and 3 visibly show different date/hour data; week 1 contains only its available date. This closes the earlier evidence gap about arrow navigation changing the graph.
- The recording is an Android Studio emulator capture and the IDE shows `liveDebug` as the selected build variant. It demonstrates the adjustment screen's week navigation and graph behavior in that run; it does not verify the Live backend-generated overall summary or establish that the displayed plan was server-generated.

Screenshots were captured during review and are not stored in the repository.

No Live-only test is required to verify this UI behavior. If reporting Live backend integration as verified, separately capture the Live overall summary with the request's available total and actual assigned total. The evaluator's distribution comment remains a separate product-policy question, not a requirement to schedule every weekday. No UAT sheet edit is required for this implementation note.

## Open product question

Plans now allocate all usable time (applied 2026-11-15). As in the root README's open decisions, user testing still needs to establish whether learners mean the entered time as a limit or a target and how they prefer to distribute work across weekdays. Multiple daily availability windows are implemented.
