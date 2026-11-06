package com.swpp.licenseplanner.data

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.ZonedDateTime

@OptIn(ExperimentalCoroutinesApi::class)
class AndroidCalendarReadRepositoryTest {
    private class FakeAccess : CalendarReadAccess {
        var permitted = true
        var result: List<ProviderCalendarInstance>? = emptyList()
        var error: RuntimeException? = null
        var queried: Pair<LocalDate, LocalDate>? = null

        override fun hasPermission() = permitted
        override fun instances(start: LocalDate, endExclusive: LocalDate): List<ProviderCalendarInstance>? {
            queried = start to endExclusive
            error?.let { throw it }
            return result
        }
    }

    private fun timed(
        id: Long,
        start: ZonedDateTime,
        end: ZonedDateTime,
        recurring: Boolean = false,
    ) = ProviderCalendarInstance(id, start.toInstant().toEpochMilli(), end.toInstant().toEpochMilli(), false, recurring)

    private fun allDay(id: Long, start: LocalDate, endExclusive: LocalDate) = ProviderCalendarInstance(
        id,
        start.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli(),
        endExclusive.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli(),
        true,
        false,
    )

    @Test fun timedOccurrencesUseKoreanLocalTimeAndRoundOutward() {
        val start = ZonedDateTime.of(2026, 11, 5, 19, 0, 40, 0, CALENDAR_ZONE)
        val snapshot = mapCalendarInstances(listOf(timed(7, start, start.plusMinutes(59).plusSeconds(5))))
        assertEquals(1, snapshot.schedules.size)
        assertEquals("7", snapshot.schedules.single().scheduleId)
        assertEquals("2026/11/05/19/00", snapshot.schedules.single().startDate)
        assertEquals("2026/11/05/20/00", snapshot.schedules.single().endDate)
        assertEquals(CALENDAR_BUSY_TOPIC, snapshot.schedules.single().topic)
    }

    @Test fun recurringOccurrencesKeepEveryInstanceWithUniqueNumericIds() {
        val first = ZonedDateTime.of(2026, 11, 2, 18, 0, 0, 0, CALENDAR_ZONE)
        val snapshot = mapCalendarInstances(listOf(
            timed(42, first, first.plusHours(1), recurring = true),
            timed(42, first.plusWeeks(1), first.plusWeeks(1).plusHours(1), recurring = true),
        ))
        assertEquals(2, snapshot.recurringOccurrences)
        assertEquals(listOf("42", "43"), snapshot.schedules.map { it.scheduleId })
        assertTrue(snapshot.schedules.all { it.scheduleId.all(Char::isDigit) })
    }

    @Test fun allDayOccurrenceBlocksWholeLocalDates() {
        val snapshot = mapCalendarInstances(listOf(allDay(9, LocalDate.of(2026, 11, 5), LocalDate.of(2026, 11, 7))))
        assertEquals(1, snapshot.allDayOccurrences)
        assertEquals("2026/11/05/00/00", snapshot.schedules.single().startDate)
        assertEquals("2026/11/07/00/00", snapshot.schedules.single().endDate)
    }

    @Test fun duplicatesAndInvalidRowsAreRemoved() {
        val start = ZonedDateTime.of(2026, 11, 5, 10, 0, 0, 0, CALENDAR_ZONE)
        val row = timed(3, start, start.plusHours(1))
        val invalidId = row.copy(eventId = 0)
        val invalidRange = row.copy(eventId = 4, endMillis = row.beginMillis)
        assertEquals(listOf("3"), mapCalendarInstances(listOf(row, row, invalidId, invalidRange)).schedules.map { it.scheduleId })
    }

    @Test fun repositoryDistinguishesEmptyPermissionAndProviderFailure() = runTest {
        val access = FakeAccess()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repo = AndroidCalendarReadRepository(access, dispatcher)
        val window = CalendarReadWindow(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 12, 1))

        assertEquals(CalendarReadOutcome.Loaded(CalendarBusySnapshot(emptyList(), 0, 0)), repo.read(window))
        assertEquals(window.start to window.endExclusive, access.queried)

        access.permitted = false
        assertEquals(CalendarReadOutcome.PermissionMissing, repo.read(window))
        access.permitted = true
        access.error = SecurityException("revoked")
        assertEquals(CalendarReadOutcome.PermissionMissing, repo.read(window))
        access.error = IllegalStateException("provider")
        assertEquals(CalendarReadOutcome.Unavailable, repo.read(window))
        access.error = null
        access.result = null
        assertEquals(CalendarReadOutcome.Unavailable, repo.read(window))
    }
}
