package com.swpp.licenseplanner.data

import com.swpp.licenseplanner.data.model.StudyPlanResponse
import com.swpp.licenseplanner.data.model.TimeWindow
import com.swpp.licenseplanner.domain.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class DemoRemainingStudyRepositoryTest {
    private val fixture = RemainingStudyFixtures
    private fun replan(source: SessionPlan) = runBlocking {
        DemoRemainingStudyRepository { fixture.now }.replan(RemainingStudyInput(source, RemainingStudy.calculate(source), fixture.now.plusMinutes(1)))
    }

    @Test fun placesAllContributionsAndPreservesCompletedRowsReportsAndKeeps() {
        val source = fixture.plan().copy(keptConflicts = mapOf("study-20" to setOf("a".repeat(64))))
        val result = (replan(source) as AdjustmentOutcome.Success).response
        assertEquals(source.current.schedules.first(), result.schedules.first { it.scheduleId == "study-1" })
        assertEquals(listOf("study-21", "study-22", "study-4"), result.schedules.filter { it.scheduleId != "study-1" }.map { it.scheduleId })
        assertEquals(160L, RemainingStudy.calculate(source.copy(current = result)).totalMinutes)
        assertEquals(result, (replan(source) as AdjustmentOutcome.Success).response)
        assertTrue(result.scheduleCsv.contains("\"\"복습\"\""))
    }

    @Test fun laterFailureReturnsNoPartialCandidateAndDoesNotSplitAWholeSession() {
        val source = fixture.plan().copy(original = fixture.original.copy(examDate = "2026/11/16/19/00",
            weeklyAvailability = mapOf("monday" to listOf(TimeWindow("18:00", durationMinutes = 60)))))
        val before = source.copy()
        assertEquals(AdjustmentOutcome.DemoNoSlot, replan(source))
        assertEquals(before, source)
    }

    @Test fun previousDayCarryInEmptyWeekAndExactExamEndUseEffectiveWindows() {
        val source = fixture.plan().copy(original = fixture.original.copy(preparationStart = "2026/11/16/00/00", examDate = "2026/11/16/18/00",
            weeklyAvailability = mapOf("sunday" to listOf(TimeWindow("23:00", durationMinutes = 1200))),
            availabilityOverrides = mapOf("2026/11/16" to emptyMap())),
            current = StudyPlanResponse(listOf(fixture.row(1, end = "2026/11/16/18/59"))), checkIns = emptyMap())
        val rows = (replan(source) as AdjustmentOutcome.Success).response.schedules
        assertEquals("2026/11/16/17/01", rows.single().startDate)
        assertEquals("2026/11/16/18/00", rows.single().endDate)
    }

    @Test fun protectedOverlapsRemainButPlacedRowsAvoidThemAndMayTouchTheirEnd() {
        val source = fixture.plan().copy(current = StudyPlanResponse(listOf(fixture.row(1), fixture.row(2),
            fixture.row(5).copy(scheduleId = "external-1"))), checkIns = mapOf("study-2" to StudyCheckIn(StudyCheckInStatus.Done, 1)))
        val result = (replan(source) as AdjustmentOutcome.Success).response
        assertEquals("2026/11/16/19/00", result.schedules.single { it.scheduleId == "study-1" }.startDate)
        assertEquals(source.current.schedules.drop(1).toSet(), result.schedules.filter { it.scheduleId != "study-1" }.toSet())
    }
    @Test fun confirmedDemoTimesNeedConsentAndAreSavedAtExactlyThoseStarts() = runBlocking {
        val source = fixture.plan()
        val work = RemainingStudy.calculate(source)
        val starts = work.instructions.mapIndexed { index, item -> item.scheduleId to fixture.now.toLocalDate().plusDays(1).atTime(10 + index, 0) }.toMap()
        val input = RemainingStudyInput(source, work, fixture.now.plusMinutes(1), starts)
        val repository = DemoRemainingStudyRepository { fixture.now }
        assertEquals(ApiError.ManualMoveRejected(ManualMoveRejection.OutsideStudyWindows),
            (repository.replan(input) as AdjustmentOutcome.Failure).error)
        val response = (repository.replan(input.copy(confirmOutside = true)) as AdjustmentOutcome.Success).response
        for (instruction in work.instructions) assertEquals(WireDateTime.format(starts.getValue(instruction.scheduleId)),
            response.schedules.single { it.scheduleId == instruction.replacementScheduleId }.startDate)
        assertEquals(source.current.schedules.first(), response.schedules.single { it.scheduleId == "study-1" })
    }

}
