package com.swpp.licenseplanner.data

import android.provider.CalendarContract.Attendees
import android.provider.CalendarContract.Events
import com.swpp.licenseplanner.domain.CalendarExport
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test fun localOccurrenceIdentitySurvivesQuerySetsAndDetectsSubMinuteEdits() {
        val start = ZonedDateTime.of(2031, 3, 3, 19, 0, 10, 0, CALENDAR_ZONE)
        val first = timed(42, start, start.plusHours(1), recurring = true)
        val next = timed(42, start.plusWeeks(1), start.plusWeeks(1).plusHours(1), recurring = true)
        val full = mapCalendarInstances(listOf(first, next, first))
        val subset = mapCalendarInstances(listOf(next))
        assertEquals(2, full.occurrences.size)
        assertEquals(full.occurrences[1], subset.occurrences.single())
        assertEquals(full.occurrences[0].seriesKey, full.occurrences[1].seriesKey)
        assertTrue(full.occurrences[0].key != full.occurrences[1].key)
        val edited = mapCalendarInstances(listOf(first.copy(beginMillis = first.beginMillis + 1000)))
        assertEquals(full.schedules.first(), edited.schedules.single())
        assertTrue(full.occurrences.first().key != edited.occurrences.single().key)
        val wire = ApiJson.encodeToString(com.swpp.licenseplanner.data.model.StudyPlanRequest.serializer(),
            (RequestMapper.map(com.swpp.licenseplanner.TestFixtures.completeState()) as MappingResult.Ready).request.copy(busySchedules = full.schedules))
        assertFalse(full.occurrences.any { wire.contains(it.key) || wire.contains(it.seriesKey!!) })
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

    private val monday = ZonedDateTime.of(2026, 11, 2, 18, 0, 0, 0, CALENDAR_ZONE)

    private fun row(
        instance: ProviderCalendarInstance,
        status: Int? = Events.STATUS_CONFIRMED,
        deleted: Boolean = false,
        availability: Int? = Events.AVAILABILITY_BUSY,
        self: Int? = Attendees.ATTENDEE_STATUS_ACCEPTED,
        description: String? = null,
    ) = ProviderInstanceRow(instance, status, deleted, availability, self, description)

    private fun hour(id: Long, offsetHours: Long = id) =
        timed(id, monday.plusHours(offsetHours * 24), monday.plusHours(offsetHours * 24 + 1))

    @Test fun busyAndTentativeOccurrencesBlockTime() {
        val kept = eligibleInstances(sequenceOf(
            row(hour(1), availability = Events.AVAILABILITY_BUSY),
            row(hour(2), availability = Events.AVAILABILITY_TENTATIVE),
            row(hour(3), status = Events.STATUS_TENTATIVE),
            row(hour(4), self = Attendees.ATTENDEE_STATUS_TENTATIVE),
        ))
        assertEquals(listOf(1L, 2L, 3L, 4L), kept.map { it.eventId })
        assertEquals(4, mapCalendarInstances(kept).schedules.size)
    }

    @Test fun canceledDeletedFreeAndDeclinedOccurrencesAreExcluded() {
        val kept = eligibleInstances(sequenceOf(
            row(hour(1), status = Events.STATUS_CANCELED),
            row(hour(2), deleted = true),
            row(hour(3), availability = Events.AVAILABILITY_FREE),
            row(hour(4), self = Attendees.ATTENDEE_STATUS_DECLINED),
            row(hour(5)),
        ))
        assertEquals(listOf("5"), mapCalendarInstances(kept).schedules.map { it.scheduleId })
        val none = eligibleInstances(sequenceOf(row(hour(1), status = Events.STATUS_CANCELED), row(hour(2), deleted = true)))
        assertEquals(CalendarBusySnapshot(emptyList(), 0, 0), mapCalendarInstances(none))
    }

    @Test fun absentOptionalMetadataIsNotAnExclusion() {
        val kept = eligibleInstances(sequenceOf(row(hour(1), status = null, availability = null, self = null, description = null)))
        assertEquals("2026/11/03/18/00", mapCalendarInstances(kept).schedules.single().startDate)
    }

    @Test fun eventsThisAppExportedAreExcludedButLookalikesRemain() {
        val kept = eligibleInstances(sequenceOf(
            row(hour(1), description = CalendarExport.description(1, isDemo = false)),
            row(hour(2), description = CalendarExport.description(2, isDemo = true)),
            row(hour(3), description = "License Planner에서 내보낸 공부 일정이에요.\n계획 4 · 컴퓨터활용능력 2급 필기\n" +
                "앱에서 계획을 조정해도 이 일정은 바뀌지 않아요."),
            // Same time as an export and a study-like text, but not this app's description.
            row(hour(1).copy(eventId = 11), description = "셀 참조 공부"),
            row(hour(5), description = CalendarExport.description(5, isDemo = false) + "\n메모"),
        ))
        assertEquals(listOf(11L, 5L), kept.map { it.eventId })
    }

    @Test fun countsAreTakenAfterFiltering() {
        val kept = eligibleInstances(sequenceOf(
            row(timed(42, monday, monday.plusHours(1), recurring = true)),
            row(timed(42, monday.plusWeeks(1), monday.plusWeeks(1).plusHours(1), recurring = true), status = Events.STATUS_CANCELED),
            row(timed(42, monday.plusWeeks(2), monday.plusWeeks(2).plusHours(1), recurring = true)),
            row(allDay(8, LocalDate.of(2026, 11, 4), LocalDate.of(2026, 11, 5)), availability = Events.AVAILABILITY_FREE),
            row(allDay(9, LocalDate.of(2026, 11, 6), LocalDate.of(2026, 11, 7))),
        ))
        val snapshot = mapCalendarInstances(kept)
        assertEquals(2, snapshot.recurringOccurrences)
        assertEquals(1, snapshot.allDayOccurrences)
        assertEquals(listOf("42", "9", "43"), snapshot.schedules.map { it.scheduleId })
        assertEquals("2026/11/06/00/00" to "2026/11/07/00/00", snapshot.schedules[1].let { it.startDate to it.endDate })
    }

    @Test fun descriptionsNeverReachTheSnapshot() {
        val secret = "비공개 메모: 병원 예약 user@example.com"
        val raw = row(hour(1), description = secret)
        assertFalse(raw.toString().contains(secret))
        val snapshot = mapCalendarInstances(eligibleInstances(sequenceOf(raw)))
        assertFalse(snapshot.toString().contains(secret))
        assertEquals(CALENDAR_BUSY_TOPIC, snapshot.schedules.single().topic)
    }
}
