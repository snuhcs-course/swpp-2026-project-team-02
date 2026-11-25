package com.swpp.licenseplanner.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class ValidationTest {
    private fun day(start: String, minutes: Int) = DayAvailability(true, LocalTime.parse(start), minutes)

    @Test
    fun preparationPeriodIs42Days() {
        assertEquals(42L, Validation.preparationDays(LocalDate.of(2026, 10, 5), LocalDate.of(2026, 11, 16)))
        assertNull(Validation.datesError(LocalDate.of(2026, 10, 5), LocalDate.of(2026, 11, 16)))
    }

    @Test
    fun sameOrEarlierExamIsRejected() {
        val start = LocalDate.of(2026, 10, 5)
        assertNotNull(Validation.datesError(start, start))
        assertNotNull(Validation.datesError(start, start.minusDays(1)))
        assertNotNull(Validation.datesError(start, null))
    }

    @Test
    fun startPlusDurationGivesSameDayWindow() {
        assertEquals(StudyWindow(LocalTime.of(18, 0), LocalTime.of(20, 0)), Validation.window(day("18:00", 120)))
        assertEquals(StudyWindow(LocalTime.of(21, 0), LocalTime.of(23, 0)), Validation.window(day("21:00", 120)))
        assertNull(Validation.windowError(day("21:00", 120)))
    }

    @Test
    fun windowsReachingOrCrossingMidnightEndOnTheNextDay() {
        assertEquals(StudyWindow(LocalTime.of(23, 0), LocalTime.of(1, 0), endsNextDay = true), Validation.window(day("23:00", 120)))
        assertEquals(StudyWindow(LocalTime.of(22, 0), LocalTime.MIDNIGHT, endsNextDay = true), Validation.window(day("22:00", 120)))
        assertNull(Validation.windowError(day("22:00", 120)))
        val availability = defaultAvailability() + (Weekday.MONDAY to day("23:00", 120))
        assertNull(Validation.availabilityError(availability))
    }

    @Test
    fun durationsOutsideOneTo1439MinutesAreRejected() {
        assertNotNull(Validation.window(day("00:00", 1)))
        assertNotNull(Validation.window(day("00:00", 1439)))
        assertNull(Validation.window(day("00:00", 0)))
        assertNull(Validation.window(day("00:00", 1440)))
        assertNotNull(Validation.availabilityError(defaultAvailability() + (Weekday.MONDAY to day("18:00", 1440))))
    }

    @Test
    fun disabledInvalidDayDoesNotBlock() {
        val availability = defaultAvailability() +
            (Weekday.MONDAY to day("18:00", 60)) +
            (Weekday.TUESDAY to DayAvailability(false, LocalTime.of(23, 0), 120))
        assertNull(Validation.availabilityError(availability))
    }

    @Test
    fun noEnabledDaysIsRejected() {
        assertNotNull(Validation.availabilityError(defaultAvailability()))
    }

    @Test
    fun nextDayAndWeekBoundarySpillsCannotOverlap() {
        val monday = Weekday.MONDAY to day("23:00", 90)
        val overlapping = defaultAvailability() + monday + (Weekday.TUESDAY to day("00:15", 60))
        assertNotNull(Validation.availabilityError(overlapping))
        assertNull(Validation.availabilityError(defaultAvailability() + monday + (Weekday.TUESDAY to day("00:30", 60))))
        val sundaySpill = defaultAvailability() + (Weekday.SUNDAY to day("23:00", 90))
        val nextWeek = mapOf(LocalDate.of(2026, 11, 30) to mapOf(Weekday.MONDAY to day("00:15", 45)))
        assertNotNull(Validation.availabilityError(sundaySpill, nextWeek))
        assertNull(Validation.availabilityError(sundaySpill, mapOf(LocalDate.of(2026, 11, 30) to emptyMap())))
    }

    @Test
    fun extraWindowsCountAndConflict() {
        val extraOnly = DayAvailability(false, LocalTime.of(8, 0), 60, extras = listOf(WindowSpec(LocalTime.of(19, 0), 120)))
        // The primary is disabled but the extra window keeps the day usable.
        assertNull(Validation.availabilityError(defaultAvailability() + (Weekday.MONDAY to extraOnly)))
        val clash = extraOnly.copy(enabled = true, start = LocalTime.of(18, 30))
        assertNotNull(Validation.availabilityError(defaultAvailability() + (Weekday.MONDAY to clash)))
        assertEquals(listOf(WindowSpec(LocalTime.of(18, 30), 60), WindowSpec(LocalTime.of(19, 0), 120)), clash.windows())
    }

    @Test
    fun overrideOnlyContextIsUsableButAnEmptyContextIsNot() {
        val overrides = mapOf(LocalDate.of(2026, 11, 30) to mapOf(Weekday.TUESDAY to day("20:00", 60)))
        assertNull(Validation.availabilityError(defaultAvailability(), overrides))
        assertNotNull(Validation.availabilityError(defaultAvailability(), mapOf(LocalDate.of(2026, 11, 30) to emptyMap())))
    }
}
