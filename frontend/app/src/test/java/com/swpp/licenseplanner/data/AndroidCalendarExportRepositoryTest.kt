package com.swpp.licenseplanner.data

import com.swpp.licenseplanner.domain.CalendarEvent
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Access checks, sequential inserts and outcome counts over a fake provider. */
@OptIn(ExperimentalCoroutinesApi::class)
class AndroidCalendarExportRepositoryTest {
    private class FakeAccess : CalendarProviderAccess {
        var permitted = true
        var calendars: List<ProviderCalendar>? = listOf(ProviderCalendar(7, "공부", "me@example.test"))
        var listError: RuntimeException? = null

        /** Receipt for each insert by position; a missing entry returns the next ID. */
        val receipts = mutableMapOf<Int, () -> Long?>()
        val inserted = mutableListOf<Pair<Long, CalendarEvent>>()
        var onInsert: (Int) -> Unit = {}

        override fun hasPermissions() = permitted
        override fun writableCalendars(): List<ProviderCalendar>? {
            listError?.let { throw it }
            return calendars
        }
        override fun insert(calendarId: Long, event: CalendarEvent): Long? {
            val index = inserted.size
            inserted += calendarId to event
            onInsert(index)
            val receipt = receipts[index] ?: return 100L + index
            return receipt()
        }
    }

    private val access = FakeAccess()
    private val destination = CalendarDestination(7, "공부")
    private val events = (1..4).map { CalendarEvent("주제 $it", it * 1000L, it * 1000L + 500, "Asia/Seoul", "설명") }

    private fun repository(dispatcher: TestDispatcher) = AndroidCalendarExportRepository(access, dispatcher)

    @Test
    fun everyEventIsInsertedInOrderIntoTheChosenCalendar() = runTest {
        val result = repository(StandardTestDispatcher(testScheduler)).export(destination, events)
        assertEquals(CalendarWriteOutcome.Complete(4), result)
        assertEquals(events, access.inserted.map { it.second })
        assertTrue(access.inserted.all { it.first == 7L })
    }

    @Test
    fun accessProblemsBeforeWritingInsertNothing() = runTest {
        val repo = repository(StandardTestDispatcher(testScheduler))
        access.permitted = false
        assertEquals(CalendarWriteOutcome.NotStarted(ExportNotStarted.PermissionMissing), repo.export(destination, events))
        access.permitted = true
        access.calendars = listOf(ProviderCalendar(8, "다른 캘린더", ""))
        assertEquals(CalendarWriteOutcome.NotStarted(ExportNotStarted.DestinationUnavailable), repo.export(destination, events))
        access.calendars = null
        assertEquals(CalendarWriteOutcome.NotStarted(ExportNotStarted.CalendarsUnavailable), repo.export(destination, events))
        access.listError = SecurityException("revoked")
        assertEquals(CalendarWriteOutcome.NotStarted(ExportNotStarted.PermissionMissing), repo.export(destination, events))
        assertEquals(0, access.inserted.size)
    }

    @Test
    fun aRefusedInsertStopsWithTheConfirmedCount() = runTest {
        access.receipts[2] = { throw SecurityException("revoked") }
        val result = repository(StandardTestDispatcher(testScheduler)).export(destination, events)
        assertEquals(CalendarWriteOutcome.Stopped(added = 2, uncertain = false), result)
        assertEquals(3, access.inserted.size)
    }

    @Test
    fun missingOrMalformedReceiptsAndProviderErrorsAreUncertain() = runTest {
        val repo = repository(StandardTestDispatcher(testScheduler))
        val failures = listOf<() -> Long?>({ null }, { -1L }, { 0L }, { throw IllegalStateException("provider") })
        for (failure in failures) {
            access.inserted.clear()
            access.receipts.clear()
            access.receipts[1] = failure
            assertEquals(CalendarWriteOutcome.Stopped(added = 1, uncertain = true), repo.export(destination, events))
            // No later insert after the failed one.
            assertEquals(2, access.inserted.size)
        }
    }

    @Test
    fun cancellationStopsFurtherInsertsWithoutAnOutcome() = runTest {
        var job: Job? = null
        access.onInsert = { if (it == 0) job?.cancel() }
        val deferred = async(start = CoroutineStart.LAZY) { repository(StandardTestDispatcher(testScheduler)).export(destination, events) }
        job = deferred
        deferred.start()
        advanceUntilIdle()
        assertTrue(deferred.isCancelled)
        assertEquals(1, access.inserted.size)
    }

    @Test
    fun calendarListReportsPermissionAndQueryFailures() = runTest {
        val repo = repository(StandardTestDispatcher(testScheduler))
        assertEquals(CalendarListOutcome.Loaded(listOf(CalendarDestination(7, "공부"))), repo.calendars())
        access.calendars = emptyList()
        assertEquals(CalendarListOutcome.Loaded(emptyList()), repo.calendars())
        access.calendars = null
        assertEquals(CalendarListOutcome.Unavailable, repo.calendars())
        access.listError = IllegalStateException("provider")
        assertEquals(CalendarListOutcome.Unavailable, repo.calendars())
        access.permitted = false
        assertEquals(CalendarListOutcome.PermissionMissing, repo.calendars())
    }

    @Test
    fun equalCalendarNamesAreToldApart() {
        val labels = labelCalendars(listOf(
            ProviderCalendar(1, "공부", "a@example.test"),
            ProviderCalendar(2, "공부", "b@example.test"),
            ProviderCalendar(3, "개인", "a@example.test"),
            ProviderCalendar(4, "개인", "a@example.test"),
            ProviderCalendar(5, "", ""),
        )).map { it.label }
        assertEquals(listOf("공부 · a@example.test", "공부 · b@example.test", "개인 · a@example.test (1)", "개인 · a@example.test (2)", "이름 없는 캘린더"), labels)
    }
}
