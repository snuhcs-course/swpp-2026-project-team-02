package com.swpp.licenseplanner.domain

import com.swpp.licenseplanner.data.CalendarOccurrence
import com.swpp.licenseplanner.data.model.RemainingSession
import com.swpp.licenseplanner.data.model.ScheduleRow
import java.time.LocalDateTime
import java.time.LocalTime
import org.junit.Assert.*
import org.junit.Test

class FreeTimeTest {
    private fun at(value: String) = LocalDateTime.parse(value)
    private val now = at("2026-11-17T09:05")
    private val prep = at("2026-11-09T00:00")
    private val exam = at("2026-11-23T00:00")
    private val availability = StudyAvailability(mapOf(
        Weekday.MONDAY to listOf(WindowSpec(LocalTime.of(19, 0), 90)),
        Weekday.SATURDAY to listOf(WindowSpec(LocalTime.of(10, 0), 120))))
    private fun row(id: String, start: String, minutes: Int) = ScheduleRow(id,
        WireDateTime.format(at(start)), WireDateTime.format(at(start).plusMinutes(minutes.toLong())), "동적 주제")
    private val target = row("study-1", "2026-11-10T19:00", 60)
    private fun occurrence(start: String, minutes: Int, allDay: Boolean = false) =
        CalendarOccurrence("local-only", null, at(start), at(start).plusMinutes(minutes.toLong()), allDay)

    @Test fun usualBandAvoidsAllBusySourcesAndSpreadsDates() {
        val rows = listOf(target, row("study-2", "2026-11-17T10:00", 60), row("external-1", "2026-11-18T10:00", 480))
        val result = FreeTime.suggest(availability, rows, target.scheduleId,
            listOf(occurrence("2026-11-19T10:00", 630)), prep, exam, now)
        assertEquals(listOf("2026-11-17T11:00", "2026-11-18T18:00", "2026-11-20T10:00").map(::at), result.map { it.start })
        assertTrue(result.all { it.outsideWindows && it.end == it.start.plusHours(1) })
    }
    @Test fun noWindowsGiveNoBandOrSuggestionsOrBatch() {
        val empty = StudyAvailability(emptyMap())
        assertNull(FreeTime.band(empty))
        assertTrue(FreeTime.suggest(empty, listOf(target), target.scheduleId, emptyList(), prep, exam, now).isEmpty())
        assertNull(FreeTime.placeBatch(empty, listOf(target), listOf(RemainingSession("study-1", 60, "study-1")), emptyList(), prep, exam, now, now))
    }
    @Test fun overnightCarryHasOneSuggestionPerStartDate() {
        val overnight = StudyAvailability(mapOf(Weekday.MONDAY to listOf(WindowSpec(LocalTime.of(23, 0), 120))))
        val short = row("study-1", "2026-11-10T19:00", 30)
        val result = FreeTime.suggest(overnight, listOf(short), short.scheduleId, emptyList(), prep, exam, at("2026-11-17T00:05"))
        assertEquals(listOf("2026-11-17T00:10", "2026-11-18T00:00", "2026-11-19T00:00").map(::at), result.map { it.start })
        assertEquals(result.size, result.map { it.start.toLocalDate() }.toSet().size)
    }
    @Test fun overrideWidensBand() {
        val wide = availability.copy(overrides = mapOf(now.toLocalDate().minusDays(1) to
            mapOf(Weekday.TUESDAY to listOf(WindowSpec(LocalTime.of(8, 0), 900)))))
        assertEquals(FreeTimeBand(480, 1380), FreeTime.band(wide))
    }
    @Test fun touchingIsAllowedButOneMinuteOverlapRoundsPastBlocker() {
        fun start(busy: CalendarOccurrence) = FreeTime.suggest(availability, listOf(target), target.scheduleId,
            listOf(busy), prep, exam, now).first().start
        assertEquals(at("2026-11-17T10:00"), start(occurrence("2026-11-17T09:00", 60)))
        assertEquals(at("2026-11-17T10:10"), start(occurrence("2026-11-17T09:00", 61)))
    }
    @Test fun examEdgeEndAllowedButLaterEndRejected() {
        val edge = at("2026-11-17T11:00")
        assertEquals(edge, FreeTime.suggest(availability, listOf(target), target.scheduleId, emptyList(), prep, edge, now).single().end)
        assertTrue(FreeTime.suggest(availability, listOf(target), target.scheduleId, emptyList(), prep, edge.minusMinutes(1), now).isEmpty())
    }
    @Test fun roundsNowAndLaterPreparationUpToTenMinutes() {
        assertEquals(at("2026-11-17T09:10"), FreeTime.roundUp10(now))
        val later = at("2026-11-17T11:05")
        assertEquals(at("2026-11-17T11:10"), FreeTime.suggest(availability, listOf(target), target.scheduleId, emptyList(), later, exam, now).first().start)
    }
    @Test fun gapInsideEffectiveWindowHasNoOutsideLabel() {
        assertFalse(FreeTime.suggest(availability, listOf(target), target.scheduleId, emptyList(), prep, exam, at("2026-11-21T10:00")).first().outsideWindows)
    }
    @Test fun allDayOccurrenceBlocksEntireDate() {
        assertEquals(at("2026-11-18T10:00"), FreeTime.suggest(availability, listOf(target), target.scheduleId,
            listOf(occurrence("2026-11-17T00:00", 1440, true)), prep, exam, now).first().start)
    }
    @Test fun completeBatchUsesSourceOrderAndEarlierPlacementsBlockLaterOnes() {
        val second = row("study-2", "2026-11-11T19:00", 30)
        val rows = listOf(target, second, row("external-1", "2026-11-17T10:00", 60))
        val instructions = listOf(RemainingSession("study-2", 30, "study-4"), RemainingSession("study-1", 60, "study-3"))
        val result = FreeTime.placeBatch(availability, rows, instructions, listOf(occurrence("2026-11-17T12:00", 30)), prep, exam, now, now)!!
        assertEquals(listOf("study-1", "study-2"), result.map { it.sourceId })
        assertEquals(listOf("study-3", "study-4"), result.map { it.row.scheduleId })
        assertEquals(listOf("2026/11/17/11/00", "2026/11/17/12/30"), result.map { it.row.startDate })
        assertEquals(listOf(60L, 30L), PlanGrouping.sessions(result.map { it.row }).map { it.durationMinutes })
        assertTrue(result.all { it.row.topic == target.topic })
    }
    @Test fun lateContributionFailureReturnsNoPartialBatch() {
        val second = row("study-2", "2026-11-11T19:00", 30)
        assertNull(FreeTime.placeBatch(availability, listOf(target, second),
            listOf(RemainingSession("study-1", 60, "study-1"), RemainingSession("study-2", 30, "study-2")),
            emptyList(), prep, at("2026-11-17T11:00"), now, now))
    }
    @Test fun tiesUseEndThenNumericSuffixAndNextMinuteLowerBound() {
        val rows = listOf(row("study-10", "2026-11-10T19:00", 60), row("study-2", "2026-11-10T19:00", 60), row("study-3", "2026-11-10T19:00", 30))
        val result = FreeTime.placeBatch(availability, rows, rows.map { RemainingSession(it.scheduleId, 30, it.scheduleId) },
            emptyList(), prep, exam, now, at("2026-11-17T10:00"))!!
        assertEquals(listOf("study-3", "study-2", "study-10"), result.map { it.sourceId })
        assertEquals("2026/11/17/10/10", result.first().row.startDate)
    }
}
