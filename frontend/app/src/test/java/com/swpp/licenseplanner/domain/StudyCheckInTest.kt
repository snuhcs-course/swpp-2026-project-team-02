package com.swpp.licenseplanner.domain

import com.swpp.licenseplanner.data.model.AssessmentResults
import com.swpp.licenseplanner.data.model.ScheduleRow
import com.swpp.licenseplanner.data.model.StudyPlanRequest
import com.swpp.licenseplanner.data.model.StudyPlanResponse
import com.swpp.licenseplanner.data.model.TimeWindow
import com.swpp.licenseplanner.domain.StudyCheckInStatus.Done
import com.swpp.licenseplanner.domain.StudyCheckInStatus.Missed
import com.swpp.licenseplanner.domain.StudyCheckInStatus.Partly
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StudyCheckInTest {
    private val request = StudyPlanRequest(
        certificationId = CERTIFICATION_ID,
        preparationStart = "2026/10/05/00/00",
        examDate = "2026/11/16/00/00",
        selfAssessment = "intermediate",
        assessmentResults = AssessmentResults(CERTIFICATION_ID, emptyList()),
        studyDaysPerWeek = 1,
        weeklyAvailability = mapOf("monday" to listOf(TimeWindow("18:00", "19:00"))),
    )

    /** A 30-minute study session and an external event, under IDs that every plan may reuse. */
    private val rows = listOf(
        ScheduleRow("study-1", "2026/10/05/18/00", "2026/10/05/18/30", "함수"),
        ScheduleRow("study-2", "2026/10/12/18/00", "2026/10/12/19/00", "차트"),
        ScheduleRow("external-1", "2026/10/06/18/00", "2026/10/06/19/00", ""),
    )

    private fun plan(ordinal: Int) = SessionPlan(ordinal, request, true, StudyPlanResponse(rows), null)

    @Test fun earlyCompletionAndOvertimeAreBothAccepted() {
        val done = StudyCheckIn.fromInput(Done, "20")
        val partly = StudyCheckIn.fromInput(Partly, "45")
        assertEquals(StudyCheckIn(Done, 20), done)
        assertEquals(StudyCheckIn(Partly, 45), partly)

        val reported = plan(1).withCheckIn("study-1", done!!)!!.withCheckIn("study-1", partly!!)!!
        assertEquals(mapOf("study-1" to StudyCheckIn(Partly, 45)), reported.checkIns)
        assertEquals(rows, reported.current.schedules)
    }

    @Test fun missedRecordsZeroWhateverWasTyped() {
        for (typed in listOf("", "30", "abc")) {
            assertEquals(typed, StudyCheckIn(Missed, 0), StudyCheckIn.fromInput(Missed, typed))
        }
        assertTrue(StudyCheckIn(Missed, 0).isValid)
        assertFalse(StudyCheckIn(Missed, 30).isValid)
    }

    @Test fun minutesUpToTheLargestIntAreAccepted() {
        assertEquals(1, StudyCheckIn.parseActualMinutes("1"))
        assertEquals(Int.MAX_VALUE, StudyCheckIn.parseActualMinutes(Int.MAX_VALUE.toString()))
        assertEquals(StudyCheckIn(Partly, Int.MAX_VALUE), StudyCheckIn.fromInput(Partly, "2147483647"))
        assertTrue(StudyCheckIn(Done, 1).isValid)
    }

    @Test fun unusableMinutesAreRejectedRatherThanRepaired() {
        val rejected = listOf("", " ", "0", "00", "-5", "+5", "1.5", "20분", "abc", " 20", "20 ", "٣٠", "2147483648", "99999999999999999999")
        for (text in rejected) {
            assertNull(text, StudyCheckIn.parseActualMinutes(text))
            assertNull(text, StudyCheckIn.fromInput(Done, text))
            assertNull(text, StudyCheckIn.fromInput(Partly, text))
        }
        assertFalse(StudyCheckIn(Done, 0).isValid)
        assertFalse(StudyCheckIn(Partly, -1).isValid)
    }

    @Test fun invalidReportLeavesThePlanUnchanged() {
        val committed = plan(1).withCheckIn("study-1", StudyCheckIn(Missed, 0))!!
        assertNull(committed.withCheckIn("study-1", StudyCheckIn(Done, 0)))
        assertNull(committed.withCheckIn("study-1", StudyCheckIn(Missed, 5)))
        assertEquals(StudyCheckIn(Missed, 0), committed.checkInFor("study-1"))
    }

    @Test fun identicalStudyIdsInTwoPlansKeepSeparateReports() {
        val a = plan(1).withCheckIn("study-1", StudyCheckIn(Done, 20))!!
        val b = plan(2)
        assertNull(b.checkInFor("study-1"))

        val reportedB = b.withCheckIn("study-1", StudyCheckIn(Missed, 0))!!
        assertEquals(StudyCheckIn(Done, 20), a.checkInFor("study-1"))
        assertEquals(StudyCheckIn(Missed, 0), reportedB.checkInFor("study-1"))
        assertNull(reportedB.checkInFor("study-2"))
    }

    @Test fun onlyCurrentStudyRowsCanBeReported() {
        val plan = plan(1)
        assertNull(plan.withCheckIn("external-1", StudyCheckIn(Done, 30)))
        assertNull(plan.withCheckIn("study-9", StudyCheckIn(Done, 30)))
        assertTrue(plan.checkIns.isEmpty())
        assertFalse(SessionPlan.isStudyId("external-1"))
        assertFalse(SessionPlan.isStudyId(" "))
        assertTrue(SessionPlan.isStudyId("study-9"))
    }

    @Test fun reportForARowNoLongerShownIsKeptButNotCurrent() {
        val reported = plan(1).withCheckIn("study-2", StudyCheckIn(Partly, 45))!!
        val reloaded = reported.copy(current = StudyPlanResponse(rows.filter { it.scheduleId != "study-2" }))
        assertEquals(StudyCheckIn(Partly, 45), reloaded.checkIns["study-2"])
        assertNull(reloaded.checkInFor("study-2"))
        assertNull(reloaded.checkInFor("study-1"))
    }

    @Test fun newPlansAndUnreportedRowsHaveNoReport() {
        val registered = SessionPlan.register(3, request, StudyPlanResponse(rows), isDemo = true)
        assertTrue(registered.checkIns.isEmpty())
        assertNull(registered.checkInFor("study-1"))
    }
}
