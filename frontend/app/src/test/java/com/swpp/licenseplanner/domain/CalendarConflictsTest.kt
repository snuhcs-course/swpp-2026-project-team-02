package com.swpp.licenseplanner.domain

import com.swpp.licenseplanner.data.CalendarOccurrence
import com.swpp.licenseplanner.data.model.ScheduleRow
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class CalendarConflictsTest {
    private val monday = LocalDate.of(2031, 3, 3)
    private fun occurrence(key: String, day: LocalDate = monday, hour: Int = 19, series: String? = null) =
        CalendarOccurrence(key, series, day.atTime(hour, 30), day.atTime(hour + 1, 30), false)

    @Test fun overlapIsHalfOpenAndDuplicateOccurrencesAreComparedOnce() {
        val targets = CalendarConflicts.windows(StudyAvailability(mapOf(Weekday.MONDAY to listOf(WindowSpec(LocalTime.of(19, 0), 60)))), monday, monday.plusWeeks(1))
        val busy = occurrence("one")
        val adjacent = busy.copy(key = "adjacent", start = monday.atTime(18, 0), end = monday.atTime(19, 0))
        val result = CalendarConflicts.compare(targets, listOf(busy, busy, adjacent))
        assertEquals(1, result.matches.size)
        assertTrue(result.recurringWeekdays.isEmpty())
    }

    @Test fun sundayCarryInKeepsItsOriginAndIsClippedOnceAcrossReplacementBoundary() {
        val availability = StudyAvailability(mapOf(Weekday.SUNDAY to listOf(WindowSpec(LocalTime.of(23, 0), 90))), mapOf(monday to emptyMap()))
        val targets = CalendarConflicts.windows(availability, monday, monday.plusWeeks(1))
        assertEquals(1, targets.size)
        assertEquals(Weekday.SUNDAY, targets.single().weekday)
        assertEquals(StudyInterval(monday.atStartOfDay(), monday.atTime(0, 30)), targets.single().interval)
        assertEquals(1, CalendarConflicts.compare(targets, listOf(occurrence("all").copy(start = monday.atStartOfDay(), end = monday.plusDays(1).atStartOfDay(), allDay = true))).matches.size)
    }

    @Test fun recurrenceAdviceRequiresTwoDistinctActualSeriesOccurrencesOnTheOwningWeekday() {
        val targets = CalendarConflicts.windows(StudyAvailability(mapOf(Weekday.MONDAY to listOf(WindowSpec(LocalTime.of(19, 0), 60)))), monday, monday.plusWeeks(3))
        val first = occurrence("first", series = "series")
        val second = occurrence("second", monday.plusWeeks(1), series = "series")
        assertEquals(setOf(Weekday.MONDAY), CalendarConflicts.compare(targets, listOf(first, second)).recurringWeekdays)
        assertTrue(CalendarConflicts.compare(targets, listOf(first, first)).recurringWeekdays.isEmpty())
        assertTrue(CalendarConflicts.compare(targets, listOf(first, second.copy(seriesKey = "other"))).recurringWeekdays.isEmpty())
        assertTrue(CalendarConflicts.compare(targets, listOf(first.copy(seriesKey = null), second.copy(seriesKey = null))).recurringWeekdays.isEmpty())
    }

    @Test fun recurringOvernightAdviceUsesTheOwningSundayEvenWhenTheOccurrenceIsOnMonday() {
        val targets = CalendarConflicts.windows(StudyAvailability(mapOf(Weekday.SUNDAY to listOf(WindowSpec(LocalTime.of(23, 0), 90)))), monday, monday.plusWeeks(2))
        val first = CalendarOccurrence("first", "series", monday.atTime(0, 10), monday.atTime(0, 20), false)
        val second = first.copy(key = "second", start = first.start.plusWeeks(1), end = first.end.plusWeeks(1))
        assertEquals(setOf(Weekday.SUNDAY), CalendarConflicts.compare(targets, listOf(first, second)).recurringWeekdays)
    }

    @Test fun savedRowsIncludeEveryWeekAndEveryEventPerSessionWithTimingChanges() {
        val rows = listOf(
            ScheduleRow("study-1", "2031/03/03/19/00", "2031/03/03/20/00", "A"),
            ScheduleRow("study-2", "2031/03/10/19/00", "2031/03/10/20/00", "B"),
            ScheduleRow("external-1", "2031/03/03/19/00", "2031/03/03/20/00"),
        )
        val targets = CalendarConflicts.sessions(rows)
        val events = listOf(occurrence("one"), occurrence("two"), occurrence("three", monday.plusWeeks(1)))
        val result = CalendarConflicts.compare(targets, events)
        assertEquals(3, result.matches.size)
        assertEquals(setOf("study-1", "study-2"), result.matches.map { it.target.scheduleId }.toSet())
        assertNotEquals(result, CalendarConflicts.compare(targets, events.map { it.copy(start = it.start.plusMinutes(1)) }))
    }
}
