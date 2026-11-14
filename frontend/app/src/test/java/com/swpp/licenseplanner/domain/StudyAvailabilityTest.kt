package com.swpp.licenseplanner.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/** The same boundary cases as the backend resolver. 2026-11-16 is a Monday. */
class StudyAvailabilityTest {
    private val monday = LocalDate.of(2026, 11, 16)
    private fun w(start: String, minutes: Int) = WindowSpec(LocalTime.parse(start), minutes)
    private val overnight = StudyAvailability(mapOf(Weekday.MONDAY to listOf(w("23:00", 90))))

    /** Minutes of [day] covered by its intervals, clipped to the day and to [from, until). */
    private fun capacity(context: StudyAvailability, day: LocalDate, from: LocalDateTime = LocalDateTime.MIN, until: LocalDateTime = LocalDateTime.MAX): Long =
        context.windowsFor(day).sumOf { interval ->
            val start = maxOf(interval.start, day.atStartOfDay(), from)
            val end = minOf(interval.end, day.plusDays(1).atStartOfDay(), until)
            if (end.isAfter(start)) Duration.between(start, end).toMinutes() else 0L
        }

    @Test
    fun overnightWindowCountsSixtyPlusThirtyOnce() {
        assertEquals(listOf(StudyInterval(monday.atTime(23, 0), monday.plusDays(1).atTime(0, 30))), overnight.windowsFor(monday))
        assertEquals(overnight.windowsFor(monday), overnight.windowsFor(monday.plusDays(1)))
        assertEquals(60L, capacity(overnight, monday))
        assertEquals(30L, capacity(overnight, monday.plusDays(1)))
        // Preparation from Tuesday 00:10 leaves 20 minutes; an exam at Tuesday 00:00 leaves 60.
        assertEquals(20L, capacity(overnight, monday.plusDays(1), from = monday.plusDays(1).atTime(0, 10)))
        assertEquals(60L, capacity(overnight, monday, until = monday.plusDays(1).atStartOfDay()))
        assertTrue(overnight.fitsOneWindow(monday.atTime(23, 0), monday.plusDays(1).atTime(0, 30)))
        assertFalse(overnight.fitsOneWindow(monday.atTime(23, 0), monday.plusDays(1).atTime(0, 31)))
    }

    @Test
    fun eachWeekUsesItsOwnReplacementOrTheBase() {
        val context = StudyAvailability(
            base = mapOf(Weekday.WEDNESDAY to listOf(w("19:00", 60))),
            overrides = mapOf(
                LocalDate.of(2026, 11, 23) to emptyMap(),
                LocalDate.of(2026, 11, 30) to mapOf(Weekday.TUESDAY to listOf(w("20:00", 60)))),
        )
        assertEquals(1, context.windowsFor(LocalDate.of(2026, 11, 18)).size) // absent key: base Wednesday
        assertTrue(context.windowsFor(LocalDate.of(2026, 11, 25)).isEmpty()) // empty week
        assertTrue(context.windowsFor(LocalDate.of(2026, 12, 2)).isEmpty()) // no Wednesday invented
        assertEquals(1, context.windowsFor(LocalDate.of(2026, 12, 1)).size)
    }

    @Test
    fun sundaySpillFollowsItsOwnWeekIntoAnEmptyWeek() {
        val context = StudyAvailability(
            base = mapOf(Weekday.SUNDAY to listOf(w("23:00", 90))),
            overrides = mapOf(LocalDate.of(2026, 11, 30) to emptyMap()),
        )
        assertEquals(
            listOf(StudyInterval(LocalDate.of(2026, 11, 29).atTime(23, 0), LocalDate.of(2026, 11, 30).atTime(0, 30))),
            context.windowsFor(LocalDate.of(2026, 11, 30)),
        )
        assertTrue(context.windowsStarting(LocalDate.of(2026, 12, 6)).isEmpty())
    }

    @Test
    fun overlapRulesMatchTheBackend() {
        val nextDay = mapOf(Weekday.MONDAY to listOf(w("23:00", 90)), Weekday.TUESDAY to listOf(w("00:15", 60)))
        assertNotNull(StudyAvailability(nextDay).firstOverlap())
        assertNull(StudyAvailability(nextDay + (Weekday.TUESDAY to listOf(w("00:30", 60)))).firstOverlap())
        val sunday = mapOf(Weekday.SUNDAY to listOf(w("23:00", 90)), Weekday.MONDAY to listOf(w("00:15", 30)))
        assertNotNull(StudyAvailability(sunday).firstOverlap())
        val crossWeek = StudyAvailability(
            mapOf(Weekday.SUNDAY to listOf(w("23:00", 90))),
            mapOf(LocalDate.of(2026, 11, 30) to mapOf(Weekday.MONDAY to listOf(w("00:15", 45)))),
        )
        assertNotNull(crossWeek.firstOverlap())
        assertFalse(StudyAvailability(emptyMap(), mapOf(LocalDate.of(2026, 11, 30) to emptyMap())).hasWindows())
    }

    @Test fun removingPrimaryPromotesTheNextWindowAndKeepsOvernightCapacity() {
        val original = DayAvailability.of(listOf(w("08:00", 60), w("19:00", 120), w("23:30", 60)))
        val removed = original.removeWindow(0)!!
        assertEquals(listOf(w("19:00", 120), w("23:30", 60)), removed.windows())
        assertEquals(LocalTime.of(19, 0), removed.start)
        val adjacent = removed.appendWindow(w("21:00", 60))
        assertNull(StudyAvailability(mapOf(Weekday.MONDAY to adjacent.windows())).firstOverlap())
        assertEquals(listOf(w("08:00", 60), w("19:00", 120), w("23:30", 60)), original.windows())
        assertTrue(removed.removeWindow(0)!!.removeWindow(0)!!.windows().isEmpty())
        assertNull(original.removeWindow(3))
    }

}
