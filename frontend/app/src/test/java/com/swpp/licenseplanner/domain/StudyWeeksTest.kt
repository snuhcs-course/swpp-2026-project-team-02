package com.swpp.licenseplanner.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class StudyWeeksTest {
    private fun form(start: LocalDate, exam: LocalDate?) = PlanFormState(preparationStart = start, examDate = exam)

    @Test fun studyWeeksCoverThePreparationPeriodByMonday() {
        val start = LocalDate.of(2026, 11, 18) // Wednesday
        assertEquals(
            listOf(LocalDate.of(2026, 11, 16), LocalDate.of(2026, 11, 23)),
            form(start, LocalDate.of(2026, 11, 30)).studyWeeks, // a Monday exam leaves its week without a study day
        )
        assertEquals(
            listOf(LocalDate.of(2026, 11, 16), LocalDate.of(2026, 11, 23), LocalDate.of(2026, 11, 30)),
            form(start, LocalDate.of(2026, 12, 1)).studyWeeks,
        )
        assertTrue(form(start, null).studyWeeks.isEmpty())
        assertTrue(form(start, start).studyWeeks.isEmpty())
    }

    @Test fun shownWeekFallsBackToTheFirstStudyWeek() {
        val state = form(LocalDate.of(2026, 11, 16), LocalDate.of(2026, 12, 7))
        assertEquals(LocalDate.of(2026, 11, 16), state.shownWeek(null))
        assertEquals(LocalDate.of(2026, 11, 30), state.shownWeek(LocalDate.of(2026, 11, 30)))
        assertEquals(LocalDate.of(2026, 11, 16), state.shownWeek(LocalDate.of(2026, 12, 7)))
        assertEquals(null, form(LocalDate.of(2026, 11, 16), null).shownWeek(LocalDate.of(2026, 11, 16)))
    }

    @Test fun emptyWeekDiffersFromInheritanceAndKeepsIncomingSpill() {
        val monday = LocalDate.of(2026, 11, 30)
        val sunday = Weekday.entries.associateWith { DayAvailability() } +
            (Weekday.SUNDAY to DayAvailability(true, LocalTime.of(23, 30), 60))
        val inherited = PlanFormState(preparationStart = LocalDate.of(2026, 11, 16), examDate = LocalDate.of(2026, 12, 7), availability = sunday)
        assertEquals(WeekSource.BASE, inherited.weekSource(monday))

        val empty = inherited.copy(availabilityOverrides = mapOf(monday to emptyWeek()))
        assertEquals(WeekSource.EMPTY, empty.weekSource(monday))
        assertEquals(mapOf(monday to emptyMap<Weekday, List<WindowSpec>>()), empty.studyAvailability.overrides)
        assertEquals(
            listOf(StudyInterval(monday.atStartOfDay(), monday.atTime(0, 30))),
            empty.incomingWindows(monday),
        )
        val firstMonday = LocalDate.of(2026, 11, 16)
        assertEquals(
            listOf(StudyInterval(firstMonday.atStartOfDay(), firstMonday.atTime(0, 30))),
            empty.copy(availabilityOverrides = mapOf(firstMonday to emptyWeek())).incomingWindows(firstMonday),
        )

        val replaced = inherited.copy(availabilityOverrides = mapOf(monday to sunday))
        assertEquals(WeekSource.REPLACED, replaced.weekSource(monday))
    }

    @Test fun replacedWeekKeepsIncomingTimeFromThePreviousWeeksOverride() {
        val monday = LocalDate.of(2026, 11, 23)
        val state = form(LocalDate.of(2026, 11, 16), LocalDate.of(2026, 12, 7)).copy(
            availability = mapOf(Weekday.SUNDAY to DayAvailability(true, LocalTime.of(22, 0), 60)),
            availabilityOverrides = mapOf(
                monday.minusWeeks(1) to mapOf(Weekday.SUNDAY to DayAvailability(true, LocalTime.of(23, 0), 90)),
                monday to mapOf(Weekday.TUESDAY to DayAvailability(true, LocalTime.of(20, 0), 60)),
            ),
        )
        assertEquals(WeekSource.REPLACED, state.weekSource(monday))
        assertEquals(listOf(StudyInterval(monday.atStartOfDay(), monday.atTime(0, 30))), state.incomingWindows(monday))
    }

    @Test fun incomingTimeRequiresAPositiveIntersectionWithThePreparationPeriod() {
        val monday = LocalDate.of(2026, 11, 16)
        val state = form(monday, LocalDate.of(2026, 12, 7)).copy(
            availability = mapOf(Weekday.SUNDAY to DayAvailability(true, LocalTime.of(23, 0), 90)),
        )
        assertTrue(state.copy(preparationStart = monday.plusDays(1)).incomingWindows(monday).isEmpty())
        assertTrue(state.incomingWindows(LocalDate.of(2026, 12, 7)).isEmpty())
        assertTrue(state.copy(examDate = null).incomingWindows(monday).isEmpty())
        assertTrue(state.copy(examDate = monday).incomingWindows(monday).isEmpty())
        val midnightEnd = state.copy(availability = mapOf(Weekday.SUNDAY to DayAvailability(true, LocalTime.of(23, 0), 60)))
        assertTrue(midnightEnd.incomingWindows(monday).isEmpty())
    }
}
