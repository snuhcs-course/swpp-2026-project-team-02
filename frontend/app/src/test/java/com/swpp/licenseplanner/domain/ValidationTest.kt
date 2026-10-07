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
    fun windowsReachingOrCrossingMidnightAreRejected() {
        assertNull(Validation.window(day("23:00", 120)))
        assertNull(Validation.window(day("22:00", 120))) // would end at 24:00
        assertNotNull(Validation.windowError(day("22:00", 120)))
        val availability = defaultAvailability() + (Weekday.MONDAY to day("23:00", 120))
        assertNotNull(Validation.availabilityError(availability))
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
}
