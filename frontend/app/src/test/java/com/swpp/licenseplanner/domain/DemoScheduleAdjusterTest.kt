package com.swpp.licenseplanner.domain

import com.swpp.licenseplanner.data.model.AssessmentResults
import com.swpp.licenseplanner.data.model.ScheduleRow
import com.swpp.licenseplanner.data.model.StudyPlanRequest
import com.swpp.licenseplanner.data.model.TimeWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    // --- Effective windows and the manual policy ---

    private fun d(start: String, minutes: Int) = TimeWindow(start, durationMinutes = minutes)

    @Test
    fun ninetyMinutesFitAFutureOverrideAcrossMidnightWithoutSplitting() {
        val plan = request(mapOf("wednesday" to listOf(d("19:00", 60))))
            .copy(availabilityOverrides = mapOf("2026/10/26" to mapOf("monday" to listOf(d("23:00", 90)))))
        val rows = listOf(ScheduleRow("study-1", "2026/10/19/18/00", "2026/10/19/19/30", "함수"))
        assertEquals(
            ScheduleRow("study-1", "2026/10/26/23/00", "2026/10/27/00/30", "함수"),
            DemoScheduleAdjuster.adjust(plan, rows, "study-1").row("study-1"),
        )
    }

    @Test
    fun emptyOverrideWeekIsSkippedWithoutBaseFallback() {
        val base = mapOf("monday" to listOf(d("20:00", 60)))
        val rows = listOf(ScheduleRow("study-1", "2026/10/19/18/00", "2026/10/19/19/00", "함수"))
        assertEquals("2026/10/19/20/00", DemoScheduleAdjuster.adjust(request(base), rows, "study-1").row("study-1").startDate)
        val emptyWeek = request(base).copy(availabilityOverrides = mapOf("2026/10/19" to emptyMap()))
        assertEquals("2026/10/26/20/00", DemoScheduleAdjuster.adjust(emptyWeek, rows, "study-1").row("study-1").startDate)
    }

    private val manualRows = listOf(
        ScheduleRow("study-1", "2026/10/19/18/00", "2026/10/19/19/00", "함수"),
        ScheduleRow("study-2", "2026/10/21/18/00", "2026/10/21/19/00", "함수"),
        ScheduleRow("external-3", "2026/10/20/12/00", "2026/10/20/13/00", ""),
    )
    private val tuesday = request(mapOf("tuesday" to listOf(d("18:00", 60))))
    private val now = java.time.LocalDateTime.of(2026, 10, 19, 12, 0)

    private fun manual(start: String, confirm: Boolean = false, at: java.time.LocalDateTime = now) =
        DemoScheduleAdjuster.moveManually(tuesday, manualRows, "study-1", WireDateTime.parseOrNull(start)!!, at, confirm)

    private fun rejection(start: String, confirm: Boolean = false, at: java.time.LocalDateTime = now) =
        (manual(start, confirm, at) as DemoManualAdjustment.Rejected).reason

    @Test
    fun manualMoveUsesTheChosenTimeAndKeepsDurationTopicAndOtherRows() {
        val moved = manual("2026/10/20/18/00") as DemoManualAdjustment.Moved
        assertEquals(ScheduleRow("study-1", "2026/10/20/18/00", "2026/10/20/19/00", "함수"), moved.schedules[0])
        assertEquals(manualRows.drop(1), moved.schedules.drop(1))
    }

    @Test
    fun manualMoveRejectionsMatchTheServerOrderAndConfirmationWaivesOnlyWindows() {
        assertEquals(ManualMoveRejection.OutsideStudyWindows, rejection("2026/10/20/14/00"))
        assertEquals("2026/10/20/14/00", ((manual("2026/10/20/14/00", confirm = true)) as DemoManualAdjustment.Moved).schedules[0].startDate)
        for (confirm in listOf(false, true)) {
            assertEquals(ManualMoveRejection.InPast, rejection("2026/10/19/11/59", confirm))
            assertEquals(ManualMoveRejection.ExamBoundExceeded, rejection("2026/12/13/23/30", confirm))
            assertEquals(ManualMoveRejection.StudySessionOverlap, rejection("2026/10/21/18/30", confirm))
            assertEquals(ManualMoveRejection.ExternalEventOverlap, rejection("2026/10/20/12/30", confirm))
        }
        assertEquals(ManualMoveRejection.BeforePreparationStart,
            rejection("2026/10/18/20/00", confirm = true, at = java.time.LocalDateTime.of(2026, 10, 1, 0, 0)))
        // Adjacent rows, the exam end and the current minute itself are allowed.
        for (start in listOf("2026/10/20/13/00", "2026/12/13/23/00", "2026/10/19/12/00")) {
            assertTrue(start, manual(start, confirm = true) is DemoManualAdjustment.Moved)
        }
    }
}
