package com.swpp.licenseplanner.data

import android.Manifest
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.CalendarContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.swpp.licenseplanner.data.model.ScheduleRow
import com.swpp.licenseplanner.domain.CalendarExport
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * The real Calendar Provider path of import and export against calendars this test creates and
 * removes under its own local account. Dates are far in the future so that no other event of the
 * device falls into the read window.
 */
@RunWith(AndroidJUnit4::class)
class CalendarProviderIntegrationTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val resolver: ContentResolver = context.contentResolver
    private val window = CalendarReadWindow(LocalDate.of(2031, 3, 3), LocalDate.of(2031, 3, 31))
    private val owner = "owner@licenseplanner.invalid"
    private val privateText = "비공개 메모 attendee@licenseplanner.invalid"

    private fun canWrite() = context.checkSelfPermission(Manifest.permission.WRITE_CALENDAR) ==
        android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun syncAdapter(uri: Uri): Uri = uri.buildUpon()
        .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
        .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, ACCOUNT)
        .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
        .build()

    /** Removes only calendars of this test's account; their events go with them. */
    private fun removeTestCalendars() {
        resolver.delete(
            syncAdapter(CalendarContract.Calendars.CONTENT_URI),
            "${CalendarContract.Calendars.ACCOUNT_NAME} = ? AND ${CalendarContract.Calendars.ACCOUNT_TYPE} = ?",
            arrayOf(ACCOUNT, CalendarContract.ACCOUNT_TYPE_LOCAL),
        )
    }

    private fun createCalendar(name: String, visible: Boolean): Long {
        val values = ContentValues().apply {
            put(CalendarContract.Calendars.ACCOUNT_NAME, ACCOUNT)
            put(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
            put(CalendarContract.Calendars.NAME, name)
            put(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, name)
            put(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL, CalendarContract.Calendars.CAL_ACCESS_OWNER)
            put(CalendarContract.Calendars.OWNER_ACCOUNT, owner)
            put(CalendarContract.Calendars.VISIBLE, if (visible) 1 else 0)
            put(CalendarContract.Calendars.SYNC_EVENTS, 1)
            put(CalendarContract.Calendars.CALENDAR_TIME_ZONE, "Asia/Seoul")
        }
        return ContentUris.parseId(checkNotNull(resolver.insert(syncAdapter(CalendarContract.Calendars.CONTENT_URI), values)))
    }

    private fun kst(day: Int, hour: Int) =
        LocalDateTime.of(2031, 3, day, hour, 0).atZone(CALENDAR_ZONE).toInstant().toEpochMilli()

    private fun event(calendar: Long, title: String, start: Long, end: Long?, extra: ContentValues.() -> Unit = {}): Long {
        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendar)
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.DESCRIPTION, privateText)
            put(CalendarContract.Events.DTSTART, start)
            if (end != null) put(CalendarContract.Events.DTEND, end)
            put(CalendarContract.Events.EVENT_TIMEZONE, "Asia/Seoul")
            put(CalendarContract.Events.ORGANIZER, "organizer@licenseplanner.invalid")
            extra()
        }
        return ContentUris.parseId(checkNotNull(resolver.insert(CalendarContract.Events.CONTENT_URI, values)))
    }

    private fun eventCount(): Int = resolver.query(
        CalendarContract.Events.CONTENT_URI, arrayOf(CalendarContract.Events._ID),
        "${CalendarContract.Events.ACCOUNT_NAME} = ?", arrayOf(ACCOUNT), null,
    )!!.use { it.count }

    private fun read(): CalendarBusySnapshot =
        (runBlocking { AndroidCalendarReadRepository.create(context).read(window) } as CalendarReadOutcome.Loaded).snapshot

    private fun selfStatus(eventId: Long): Int? = CalendarContract.Instances.query(
        resolver, arrayOf(CalendarContract.Instances.EVENT_ID, CalendarContract.Instances.SELF_ATTENDEE_STATUS),
        kst(3, 0), kst(31, 0),
    )!!.use { cursor ->
        generateSequence { if (cursor.moveToNext()) cursor.getLong(0) to cursor.getInt(1) else null }
            .firstOrNull { it.first == eventId }?.second
    }

    @Before fun setUp() {
        if (canWrite()) removeTestCalendars()
    }

    @After fun tearDown() {
        if (canWrite()) removeTestCalendars()
    }

    @Test fun importKeepsOnlyBusyOccurrencesAndSkipsThisAppsExports() {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.grantRuntimePermission(context.packageName, Manifest.permission.READ_CALENDAR)
        automation.grantRuntimePermission(context.packageName, Manifest.permission.WRITE_CALENDAR)
        assertTrue(CalendarAccess.READ.isGranted(context))
        assertTrue(CalendarAccess.EXPORT.isGranted(context))
        removeTestCalendars()

        val visible = createCalendar("LicensePlanner test", visible = true)
        val hidden = createCalendar("LicensePlanner hidden test", visible = false)
        event(visible, "합성 바쁜 일정", kst(3, 9), kst(3, 10)) {
            put(CalendarContract.Events.AVAILABILITY, CalendarContract.Events.AVAILABILITY_BUSY)
        }
        event(visible, "합성 미정 일정", kst(3, 11), kst(3, 12)) {
            put(CalendarContract.Events.AVAILABILITY, CalendarContract.Events.AVAILABILITY_TENTATIVE)
        }
        event(visible, "합성 한가한 일정", kst(3, 13), kst(3, 14)) {
            put(CalendarContract.Events.AVAILABILITY, CalendarContract.Events.AVAILABILITY_FREE)
        }
        event(visible, "합성 취소 일정", kst(3, 15), kst(3, 16)) {
            put(CalendarContract.Events.STATUS, CalendarContract.Events.STATUS_CANCELED)
        }
        val declined = event(visible, "합성 거절 초대", kst(4, 9), kst(4, 10))
        resolver.insert(CalendarContract.Attendees.CONTENT_URI, ContentValues().apply {
            put(CalendarContract.Attendees.EVENT_ID, declined)
            put(CalendarContract.Attendees.ATTENDEE_EMAIL, owner)
            put(CalendarContract.Attendees.ATTENDEE_RELATIONSHIP, CalendarContract.Attendees.RELATIONSHIP_ATTENDEE)
            put(CalendarContract.Attendees.ATTENDEE_TYPE, CalendarContract.Attendees.TYPE_REQUIRED)
            put(CalendarContract.Attendees.ATTENDEE_STATUS, CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED)
        })
        val deleted = event(visible, "합성 삭제 일정", kst(4, 11), kst(4, 12))
        resolver.delete(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, deleted), null, null)
        event(visible, "합성 반복 일정", kst(5, 9), null) {
            put(CalendarContract.Events.RRULE, "FREQ=WEEKLY;COUNT=3")
            put(CalendarContract.Events.DURATION, "PT1H")
        }
        event(
            visible, "합성 종일 일정",
            LocalDate.of(2031, 3, 6).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli(),
            LocalDate.of(2031, 3, 7).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli(),
        ) {
            put(CalendarContract.Events.ALL_DAY, 1)
            put(CalendarContract.Events.EVENT_TIMEZONE, "UTC")
        }
        event(hidden, "숨긴 캘린더 일정", kst(3, 17), kst(3, 18))
        // Copies written by earlier app versions, in their exact description format.
        event(visible, "함수", kst(7, 18), kst(7, 19)) {
            put(CalendarContract.Events.DESCRIPTION, "License Planner에서 내보낸 공부 일정이에요.\n" +
                "계획 3 · 컴퓨터활용능력 2급 필기\n앱에서 계획을 조정해도 이 일정은 바뀌지 않아요.")
        }
        event(visible, "차트", kst(8, 18), kst(8, 19)) {
            put(CalendarContract.Events.DESCRIPTION, "License Planner에서 내보낸 공부 일정이에요.\n" +
                "계획 4 · 컴퓨터활용능력 2급 필기\n앱에서 계획을 조정해도 이 일정은 바뀌지 않아요.\n" +
                "로컬 시연 계산: 데모 플래너가 기기 안에서 만든 일정이에요.")
        }
        // An ordinary event with an exported copy's topic and time.
        event(visible, "함수", kst(7, 18), kst(7, 19)) {
            put(CalendarContract.Events.DESCRIPTION, "친구와 함수 공부")
        }
        assertEquals(
            "the provider keeps the declined self-attendance",
            CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED, selfStatus(declined),
        )

        // Export one study row of a new plan through the real export repository.
        val export = AndroidCalendarExportRepository.create(context)
        val destination = (runBlocking { export.calendars() } as CalendarListOutcome.Loaded).calendars.single { it.id == visible }
        val rows = listOf(
            ScheduleRow("study-1", "2031/03/10/18/00", "2031/03/10/19/00", "셀 참조"),
            ScheduleRow("external-1", "2031/03/03/09/00", "2031/03/03/10/00", CALENDAR_BUSY_TOPIC),
        )
        val events = checkNotNull(CalendarExport.events(rows, planOrdinal = 7, isDemo = true))
        assertEquals(CalendarWriteOutcome.Complete(1), runBlocking { export.export(destination, events) })

        val before = eventCount()
        val snapshot = read()
        assertEquals(before, eventCount()) // importing writes nothing

        assertEquals(
            listOf(
                "2031/03/03/09/00" to "2031/03/03/10/00",
                "2031/03/03/11/00" to "2031/03/03/12/00",
                "2031/03/05/09/00" to "2031/03/05/10/00",
                "2031/03/06/00/00" to "2031/03/07/00/00",
                "2031/03/07/18/00" to "2031/03/07/19/00",
                "2031/03/12/09/00" to "2031/03/12/10/00",
                "2031/03/19/09/00" to "2031/03/19/10/00",
            ),
            snapshot.schedules.map { it.startDate to it.endDate },
        )
        assertEquals(3, snapshot.recurringOccurrences)
        assertEquals(1, snapshot.allDayOccurrences)
        assertEquals(snapshot.schedules.size, snapshot.schedules.map { it.scheduleId }.toSet().size)
        assertTrue(snapshot.schedules.all { row -> row.scheduleId.toLong() > 0 && row.topic == CALENDAR_BUSY_TOPIC })
        assertFalse(snapshot.toString().contains(privateText))
        assertFalse(snapshot.toString().contains("합성"))
    }

    @Test fun freshChecksDetectNewAndSubMinuteEditedOccurrencesWithoutWriting() {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.grantRuntimePermission(context.packageName, Manifest.permission.READ_CALENDAR)
        automation.grantRuntimePermission(context.packageName, Manifest.permission.WRITE_CALENDAR)
        removeTestCalendars()
        val calendar = createCalendar("LicensePlanner conflict test", visible = true)
        val id = event(calendar, "합성 바쁜 시간", kst(3, 18), kst(3, 19))
        event(calendar, "합성 반복 시간", kst(5, 18), null) {
            put(CalendarContract.Events.RRULE, "FREQ=WEEKLY;COUNT=3")
            put(CalendarContract.Events.DURATION, "PT1H")
        }
        val rows = listOf(
            ScheduleRow("study-1", "2031/03/03/18/00", "2031/03/03/19/00", "함수"),
            ScheduleRow("study-2", "2031/03/03/19/00", "2031/03/03/20/00", "차트"),
        )
        val count = eventCount()
        val first = read()
        val targets = com.swpp.licenseplanner.domain.CalendarConflicts.sessions(rows)
        val conflicts = com.swpp.licenseplanner.domain.CalendarConflicts.compare(targets, first.occurrences)
        assertEquals(listOf("study-1"), conflicts.matches.map { it.target.scheduleId })
        val request = com.swpp.licenseplanner.data.model.StudyPlanRequest(
            com.swpp.licenseplanner.domain.CERTIFICATION_ID, "2031/03/03/00/00", "2031/03/31/00/00", "intermediate",
            com.swpp.licenseplanner.data.model.AssessmentResults(com.swpp.licenseplanner.domain.CERTIFICATION_ID, emptyList()),
            1, mapOf("monday" to listOf(com.swpp.licenseplanner.data.model.TimeWindow("18:00", durationMinutes = 120))),
        )
        val source = com.swpp.licenseplanner.domain.SessionPlan.register(1, request,
            com.swpp.licenseplanner.data.model.StudyPlanResponse(rows), true)
        val kept = com.swpp.licenseplanner.domain.CalendarConflicts.acknowledge(source, conflicts)
        assertTrue(com.swpp.licenseplanner.domain.CalendarConflicts.unacknowledged(kept,
            com.swpp.licenseplanner.domain.CalendarConflicts.compare(targets, read().occurrences)).matches.isEmpty())
        assertEquals(1, com.swpp.licenseplanner.domain.CalendarConflicts.unacknowledged(kept.copy(ordinal = 2), conflicts).matches.size)
        assertEquals(count, eventCount())
        assertEquals(3, first.occurrences.count { it.seriesKey != null })
        assertEquals(1, first.occurrences.filter { it.seriesKey != null }.map { it.seriesKey }.distinct().size)
        resolver.update(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id), ContentValues().apply {
            put(CalendarContract.Events.DTSTART, kst(3, 18) + 1000)
            put(CalendarContract.Events.DTEND, kst(3, 19))
        }, null, null)
        val edited = read()
        assertEquals(first.schedules, edited.schedules)
        assertFalse(first.occurrences == edited.occurrences)
        assertEquals(1, com.swpp.licenseplanner.domain.CalendarConflicts.unacknowledged(kept,
            com.swpp.licenseplanner.domain.CalendarConflicts.compare(targets, edited.occurrences)).matches.size)
        val manualRows = listOf(rows[1], rows[0].copy(scheduleId = "external-99", topic = ""))
        val moved = com.swpp.licenseplanner.domain.DemoScheduleAdjuster.moveManually(request, manualRows, "study-2",
            LocalDateTime.of(2031, 3, 3, 18, 30), LocalDateTime.of(2031, 3, 3, 12, 0), true, true)
        assertTrue(moved is com.swpp.licenseplanner.domain.DemoManualAdjustment.Moved)
        assertEquals(count, eventCount())
        event(calendar, "합성 새 시간", kst(3, 19), kst(3, 20))
        val fresh = read()
        assertEquals(2, com.swpp.licenseplanner.domain.CalendarConflicts.compare(targets, fresh.occurrences).matches.size)
        assertEquals(count + 1, eventCount())
        val export = AndroidCalendarExportRepository.create(context)
        val destination = (runBlocking { export.calendars() } as CalendarListOutcome.Loaded).calendars.single { it.id == calendar }
        assertEquals(CalendarWriteOutcome.Complete(2), runBlocking { export.export(destination, checkNotNull(CalendarExport.events(rows, 1, true))) })
        assertEquals(count + 3, eventCount())
        assertEquals(fresh, read()) // owned app copies are excluded from future comparison
    }

    /** A read-only access context uses the real provider reader and refuses both writer entry points. */
    @Test fun readOnlyPolicyImportsWithoutUsingWrite() {
        InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.READ_CALENDAR)
        val readOnly = object : android.content.ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun checkSelfPermission(permission: String): Int =
                if (permission == Manifest.permission.WRITE_CALENDAR) android.content.pm.PackageManager.PERMISSION_DENIED
                else super.checkSelfPermission(permission)
            override fun checkPermission(permission: String, pid: Int, uid: Int): Int =
                if (permission == Manifest.permission.WRITE_CALENDAR) android.content.pm.PackageManager.PERMISSION_DENIED
                else super.checkPermission(permission, pid, uid)
        }
        assertTrue(CalendarAccess.READ.isGranted(readOnly))
        assertFalse(CalendarAccess.EXPORT.isGranted(readOnly))
        val outcome = runBlocking { AndroidCalendarReadRepository.create(readOnly).read(window) }
        assertTrue(outcome is CalendarReadOutcome.Loaded)
        val export = AndroidCalendarExportRepository.create(readOnly)
        assertEquals(CalendarListOutcome.PermissionMissing, runBlocking { export.calendars() })
        assertEquals(CalendarWriteOutcome.NotStarted(ExportNotStarted.PermissionMissing), runBlocking {
            export.export(CalendarDestination(1, "unused"), emptyList())
        })
    }

    private companion object {
        const val ACCOUNT = "licenseplanner-instrumentation-test"
    }
}
