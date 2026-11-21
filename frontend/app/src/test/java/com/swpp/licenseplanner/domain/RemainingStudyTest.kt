package com.swpp.licenseplanner.domain

import com.swpp.licenseplanner.data.model.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDateTime

internal object RemainingStudyFixtures {
    val now: LocalDateTime = LocalDateTime.of(2026, 11, 16, 17, 0)
    val original = StudyPlanRequest(CERTIFICATION_ID, "2026/11/09/00/00", "2026/11/30/00/00", "beginner",
        AssessmentResults(CERTIFICATION_ID, emptyList()), 7,
        Weekday.entries.associate { it.key to listOf(TimeWindow("18:00", durationMinutes = 240)) })
    fun row(id: Int, start: String = "2026/11/16/18/00", end: String = "2026/11/16/19/00") =
        ScheduleRow("study-$id", start, end, "함수, \"복습\"\n다음")
    fun plan(demo: Boolean = true): SessionPlan {
        val rows = (1..4).map { row(it, "2026/11/${11 + it}/18/00", "2026/11/${11 + it}/19/00") }
        return SessionPlan.register(1, original, StudyPlanResponse(rows, scheduleCsvFile = if (demo) null else "plans/a.csv"), demo).copy(
            checkIns = mapOf("study-1" to StudyCheckIn(StudyCheckInStatus.Done, 20),
                "study-2" to StudyCheckIn(StudyCheckInStatus.Partly, 20),
                "study-3" to StudyCheckIn(StudyCheckInStatus.Missed, 0)))
    }
}

class RemainingStudyTest {
    private val fixture = RemainingStudyFixtures

    @Test fun countsStatusesIndependentlyOfActualEffortAndRetainsWholeMaps() {
        val source = fixture.plan().copy(keptConflicts = mapOf("study-90" to setOf("a".repeat(64))))
        val work = RemainingStudy.calculate(source)
        assertEquals(listOf(0, 40, 60, 60), work.contributions.map { it.remainingMinutes })
        assertEquals(160L, work.totalMinutes)
        assertEquals(listOf("study-91", "study-92", "study-4"), work.instructions.map { it.replacementScheduleId })
        assertEquals(3, source.checkIns.size)
        assertEquals(setOf("study-90"), source.keptConflicts.keys)
        assertEquals(work, RemainingStudy.calculate(source.copy(ordinal = 2)))
    }

    @Test fun partialEqualAndOvertimeAreProtectedWithoutChangingTheirReports() {
        val source = fixture.plan().copy(checkIns = mapOf("study-1" to StudyCheckIn(StudyCheckInStatus.Partly, 60),
            "study-2" to StudyCheckIn(StudyCheckInStatus.Partly, 75)))
        val work = RemainingStudy.calculate(source)
        assertEquals(listOf(0, 0, 60, 60), work.contributions.map { it.remainingMinutes })
        val placed = RemainingStudy.place(source, work, fixture.now.plusMinutes(1), fixture.now)!!
        assertEquals(source.current.schedules.take(2), placed.filter { it.scheduleId in setOf("study-1", "study-2") })
    }

    @Test fun overdueInProgressAndFutureUnreportedRowsAreIncludedButExternalAndAbsentReportsAreNot() {
        val rows = listOf(fixture.row(1, "2026/11/09/18/00", "2026/11/09/19/00"),
            fixture.row(2, "2026/11/16/16/30", "2026/11/16/17/30"), fixture.row(3, "2026/11/20/18/00", "2026/11/20/19/00"),
            fixture.row(4).copy(scheduleId = "external-1"))
        val source = fixture.plan().copy(current = StudyPlanResponse(rows), checkIns = mapOf("study-80" to StudyCheckIn(StudyCheckInStatus.Partly, 20)))
        assertEquals(listOf(60, 60, 60), RemainingStudy.calculate(source).contributions.map { it.remainingMinutes })
        assertEquals(180L, RemainingStudy.calculate(source).totalMinutes)
        assertEquals(setOf("study-80"), source.checkIns.keys)
    }

    @Test fun secondReplanSubtractsOnlyANewRemainderReport() {
        val source = fixture.plan()
        val first = RemainingStudy.calculate(source)
        val rows = RemainingStudy.place(source, first, fixture.now.plusMinutes(1), fixture.now)!!
        val accepted = source.copy(current = StudyPlanResponse(rows))
        assertEquals(160L, RemainingStudy.calculate(accepted).totalMinutes)
        val remainder = first.instructions.first().replacementScheduleId
        assertNull(accepted.checkInFor(remainder))
        val reported = accepted.withCheckIn(remainder, StudyCheckIn(StudyCheckInStatus.Partly, 15))!!
        val second = RemainingStudy.calculate(reported)
        assertEquals(145L, second.totalMinutes)
        assertEquals(25, second.contributions.single { it.source.scheduleId == remainder }.remainingMinutes)
        assertTrue(second.instructions.filter { it.scheduleId == remainder }.all { it.replacementScheduleId !in reported.checkIns })
        assertEquals(source.checkIns, accepted.checkIns)
    }

    @Test fun sortingUsesNumericSuffixForSimultaneousSourcesAndAvoidsHistoricalReports() {
        val source = fixture.plan().copy(current = StudyPlanResponse(listOf(fixture.row(10), fixture.row(2))),
            checkIns = mapOf("study-2" to StudyCheckIn(StudyCheckInStatus.Missed, 0), "study-80" to StudyCheckIn(StudyCheckInStatus.Done, 1)))
        assertEquals(listOf("study-2", "study-10"), RemainingStudy.calculate(source).contributions.map { it.source.scheduleId })
        assertEquals("study-81", RemainingStudy.calculate(source).instructions.first().replacementScheduleId)
    }

    @Test fun refusesUnrepresentableDurationAndExhaustedIds() {
        val source = fixture.plan()
        for (bad in listOf(source.copy(keptConflicts = mapOf("study-${Long.MAX_VALUE}" to setOf("a".repeat(64)))),
            source.copy(current = StudyPlanResponse(listOf(fixture.row(1, "0001/01/01/00/00", "9999/01/01/00/00")))))) {
            assertTrue(runCatching { RemainingStudy.calculate(bad) }.isFailure)
        }
    }

    private fun external(id: Int, start: String, end: String) = ScheduleRow("external-$id", start, end, "")
    private fun unreported(vararg rows: ScheduleRow, exam: String = fixture.original.examDate) =
        SessionPlan.register(1, fixture.original.copy(examDate = exam), StudyPlanResponse(rows.toList()), true)
    private fun placeAll(source: SessionPlan, now: LocalDateTime = fixture.now) =
        RemainingStudy.place(source, RemainingStudy.calculate(source), now.plusMinutes(1), now)

    @Test fun shortRemainderSkipsAnIsolatedGapAndJoinsAnEarlierPlacement() {
        val source = unreported(fixture.row(1, "2026/11/12/18/00", "2026/11/12/19/00"),
            fixture.row(2, "2026/11/12/19/00", "2026/11/12/19/20"),
            external(1, "2026/11/16/18/00", "2026/11/16/18/20"), external(2, "2026/11/16/18/40", "2026/11/16/22/00"))
        val placed = placeAll(source)!!.associateBy { it.scheduleId }
        // Monday's 18:20–18:40 gap would be a 20-minute session; Tuesday's placement can be joined.
        assertEquals("2026/11/17/18/00" to "2026/11/17/19/00", placed.getValue("study-1").let { it.startDate to it.endDate })
        assertEquals("2026/11/17/19/00" to "2026/11/17/19/20", placed.getValue("study-2").let { it.startDate to it.endDate })
    }

    @Test fun shortRemainderWithNowhereToJoinDoesNotFit() {
        val source = unreported(fixture.row(1, "2026/11/12/18/00", "2026/11/12/18/20"),
            external(1, "2026/11/16/18/20", "2026/11/16/22/00"), exam = "2026/11/17/00/00")
        assertNull(placeAll(source))
    }

    @Test fun removingASourceMayNotLeaveALaterSessionShort() {
        val now = LocalDateTime.of(2026, 11, 16, 18, 1)
        val rows = arrayOf(fixture.row(1, "2026/11/16/18/00", "2026/11/16/18/40"),
            fixture.row(2, "2026/11/16/18/40", "2026/11/16/18/55"))
        val done = mapOf("study-2" to StudyCheckIn(StudyCheckInStatus.Done, 15))
        // Rejoining the 15-minute study-2 keeps one 55-minute session.
        val rejoined = placeAll(unreported(*rows).copy(checkIns = done), now)!!.single { it.scheduleId == "study-1" }
        assertEquals("2026/11/16/18/55" to "2026/11/16/19/35", rejoined.startDate to rejoined.endDate)
        // With study-2 boxed in, every placement would leave it as a 15-minute session.
        val boxed = unreported(*rows, external(1, "2026/11/16/18/55", "2026/11/16/19/30")).copy(checkIns = done)
        assertNull(placeAll(boxed, now))
    }
}
