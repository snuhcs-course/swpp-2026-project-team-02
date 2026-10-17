package com.swpp.licenseplanner.domain

import com.swpp.licenseplanner.data.model.AssessmentResults
import com.swpp.licenseplanner.data.model.ScheduleRow
import com.swpp.licenseplanner.data.model.StudyPlanRequest
import com.swpp.licenseplanner.data.model.TimeWindow
import org.junit.Assert.assertEquals
import org.junit.Test

/** Local rule. 2026-10-19 is a Monday. */
class DemoScheduleAdjusterTest {
    private fun request(windows: Map<String, List<TimeWindow>>, exam: String = "2026/12/14/00/00") = StudyPlanRequest(
        certificationId = CERTIFICATION_ID,
        preparationStart = "2026/10/19/00/00",
        examDate = exam,
        selfAssessment = "intermediate",
        assessmentResults = AssessmentResults(CERTIFICATION_ID, emptyList()),
        studyDaysPerWeek = windows.size,
        weeklyAvailability = windows,
    )

    private fun w(start: String, end: String) = listOf(TimeWindow(start, end))
    private val monWedFri = request(
        mapOf("monday" to w("18:00", "20:00"), "wednesday" to w("18:00", "20:00"), "friday" to w("18:00", "20:00")),
    )

    private fun moved(result: DemoAdjustment) = result as DemoAdjustment.Moved
    private fun DemoAdjustment.row(id: String) = moved(this).schedules.single { it.scheduleId == id }

    @Test
    fun waitsForTheLatestSameTopicSession() {
        val rows = listOf(
            ScheduleRow("study-1", "2026/10/19/18/00", "2026/10/19/19/00", "함수"),
            ScheduleRow("study-2", "2026/10/21/18/00", "2026/10/21/19/00", "차트"),
            ScheduleRow("study-3", "2026/10/23/18/00", "2026/10/23/19/00", "함수"),
        )
        val result = DemoScheduleAdjuster.adjust(monWedFri, rows, "study-1")
        // Not Monday 19:00 or Wednesday: after study-3 (same topic) ends on Friday at 19:00.
        assertEquals(ScheduleRow("study-1", "2026/10/23/19/00", "2026/10/23/20/00", "함수"), result.row("study-1"))
    }

    @Test
    fun skipsStudyAndExternalRowsAndMayTouchBoundaries() {
        val plan = request(mapOf("monday" to w("18:00", "21:30")))
        val rows = listOf(
            ScheduleRow("study-1", "2026/10/19/18/00", "2026/10/19/19/00", "함수"),
            ScheduleRow("study-2", "2026/10/19/19/00", "2026/10/19/20/00", "차트"),
            ScheduleRow("external-1", "2026/10/19/20/00", "2026/10/19/20/30", ""),
        )
        val result = DemoScheduleAdjuster.adjust(plan, rows, "study-1")
        // Starts exactly when the external row ends and ends exactly at the window end.
        assertEquals(ScheduleRow("study-1", "2026/10/19/20/30", "2026/10/19/21/30", "함수"), result.row("study-1"))
    }

    @Test
    fun movesToALaterPermittedWindow() {
        val plan = request(mapOf("monday" to w("18:00", "19:00"), "thursday" to w("07:00", "08:00")))
        val rows = listOf(ScheduleRow("study-1", "2026/10/19/18/00", "2026/10/19/19/00", "함수"))
        val result = DemoScheduleAdjuster.adjust(plan, rows, "study-1")
        assertEquals(ScheduleRow("study-1", "2026/10/22/07/00", "2026/10/22/08/00", "함수"), result.row("study-1"))
    }

    @Test
    fun windowTooShortForTheSessionIsSkipped() {
        val plan = request(mapOf("monday" to w("18:00", "19:30"), "tuesday" to w("18:00", "19:00")))
        val rows = listOf(ScheduleRow("study-1", "2026/10/19/18/00", "2026/10/19/19/30", "함수"))
        val result = DemoScheduleAdjuster.adjust(plan, rows, "study-1")
        // Tuesday's 60-minute window cannot hold 90 minutes; next Monday can.
        assertEquals(ScheduleRow("study-1", "2026/10/26/18/00", "2026/10/26/19/30", "함수"), result.row("study-1"))
    }

    @Test
    fun noFeasibleIntervalBeforeTheExam() {
        val plan = request(mapOf("monday" to w("18:00", "19:00"), "wednesday" to w("18:00", "19:00")), exam = "2026/10/22/00/00")
        val rows = listOf(
            ScheduleRow("study-1", "2026/10/19/18/00", "2026/10/19/19/00", "함수"),
            ScheduleRow("study-2", "2026/10/21/18/00", "2026/10/21/19/00", "차트"),
        )
        assertEquals(DemoAdjustment.NoSlot, DemoScheduleAdjuster.adjust(plan, rows, "study-1"))
        // The exam day itself is not a study day even with a window.
        val examDay = request(mapOf("monday" to w("18:00", "19:00")), exam = "2026/10/26/00/00")
        assertEquals(DemoAdjustment.NoSlot, DemoScheduleAdjuster.adjust(examDay, rows.take(1), "study-1"))
    }

    @Test
    fun preservesIdTopicDurationAndOtherRows() {
        val rows = listOf(
            ScheduleRow("external-1", "2026/10/20/09/00", "2026/10/20/10/00", ""),
            ScheduleRow("study-1", "2026/10/19/18/00", "2026/10/19/19/30", "셀 참조와 함수"),
            ScheduleRow("study-2", "2026/10/21/18/00", "2026/10/21/19/00", "차트"),
        )
        val result = moved(DemoScheduleAdjuster.adjust(monWedFri, rows, "study-1"))
        assertEquals(listOf("external-1", "study-1", "study-2"), result.schedules.map { it.scheduleId })
        assertEquals(rows[0], result.schedules[0])
        assertEquals(rows[2], result.schedules[2])
        val target = result.schedules[1]
        assertEquals("셀 참조와 함수", target.topic)
        // Wednesday 18:00 is taken; 19:00–20:30 does not fit the 20:00 window end; Friday 18:00 does.
        assertEquals("2026/10/23/18/00", target.startDate)
        assertEquals("2026/10/23/19/30", target.endDate)
    }

    @Test
    fun repeatsDeterministically() {
        val rows = listOf(
            ScheduleRow("study-1", "2026/10/19/18/00", "2026/10/19/19/00", "함수"),
            ScheduleRow("study-2", "2026/10/21/18/00", "2026/10/21/19/00", "함수"),
        )
        assertEquals(DemoScheduleAdjuster.adjust(monWedFri, rows, "study-1"), DemoScheduleAdjuster.adjust(monWedFri, rows, "study-1"))
    }

    @Test
    fun csvUsesMinimalQuoting() {
        val rows = listOf(
            ScheduleRow("study-1", "2026/10/19/18/00", "2026/10/19/19/00", "함수, \"고급\"\n복습"),
            ScheduleRow("external-1", "2026/10/20/09/00", "2026/10/20/10/00", ""),
        )
        val csv = moved(DemoScheduleAdjuster.adjust(monWedFri, rows, "study-1")).csv
        assertEquals(
            "schedule_id,start_date,end_date,topic\n" +
                "study-1,2026/10/19/19/00,2026/10/19/20/00,\"함수, \"\"고급\"\"\n복습\"\n" +
                "external-1,2026/10/20/09/00,2026/10/20/10/00,\n",
            csv,
        )
    }

    @Test
    fun externalOrMissingTargetIsRejected() {
        val rows = listOf(ScheduleRow("external-1", "2026/10/20/09/00", "2026/10/20/10/00", ""))
        assertEquals(DemoAdjustment.InvalidTarget, DemoScheduleAdjuster.adjust(monWedFri, rows, "external-1"))
        assertEquals(DemoAdjustment.InvalidTarget, DemoScheduleAdjuster.adjust(monWedFri, rows, "study-9"))
    }
}
