package com.swpp.licenseplanner.data

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.net.Uri
import android.provider.CalendarContract
import androidx.test.platform.app.InstrumentationRegistry
import com.swpp.licenseplanner.domain.WireDateTime
import org.junit.Test
import java.io.File
import java.time.ZoneId

/** Explicit instrumentation-only setup for a disposable local calendar and synthetic occurrences. */
class CalendarConflictFixture {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val resolver = context.contentResolver
    private val account = "calendar-conflict-fixture@licenseplanner.invalid"
    private fun owned(uri: Uri) = uri.buildUpon()
        .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
        .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, account)
        .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL).build()

    private fun calendars(): List<Long> = resolver.query(
        CalendarContract.Calendars.CONTENT_URI, arrayOf(CalendarContract.Calendars._ID),
        "${CalendarContract.Calendars.ACCOUNT_NAME} = ? AND ${CalendarContract.Calendars.ACCOUNT_TYPE} = ?",
        arrayOf(account, CalendarContract.ACCOUNT_TYPE_LOCAL), null,
    )!!.use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getLong(0)) } }

    @Test fun manageOwnedCalendar() {
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.READ_CALENDAR)
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.WRITE_CALENDAR)
        val args = InstrumentationRegistry.getArguments()
        when (args.getString("action", "count")) {
            "seed" -> {
                for (id in calendars()) resolver.delete(owned(ContentUris.withAppendedId(CalendarContract.Calendars.CONTENT_URI, id)), null, null)
                val values = ContentValues().apply {
                    put(CalendarContract.Calendars.ACCOUNT_NAME, account)
                    put(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
                    put(CalendarContract.Calendars.NAME, "LicensePlanner owned fixture")
                    put(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, "테스트 전용 캘린더")
                    put(CalendarContract.Calendars.OWNER_ACCOUNT, account)
                    put(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL, CalendarContract.Calendars.CAL_ACCESS_OWNER)
                    put(CalendarContract.Calendars.VISIBLE, 1)
                    put(CalendarContract.Calendars.SYNC_EVENTS, 1)
                    put(CalendarContract.Calendars.CALENDAR_TIME_ZONE, "Asia/Seoul")
                }
                checkNotNull(resolver.insert(owned(CalendarContract.Calendars.CONTENT_URI), values))
            }
            "add" -> {
                val calendar = calendars().single()
                val start = checkNotNull(WireDateTime.parseOrNull(checkNotNull(args.getString("start"))))
                val end = checkNotNull(WireDateTime.parseOrNull(checkNotNull(args.getString("end"))))
                check(end > start)
                val values = ContentValues().apply {
                    put(CalendarContract.Events.CALENDAR_ID, calendar)
                    put(CalendarContract.Events.TITLE, "합성 외부 일정")
                    put(CalendarContract.Events.DESCRIPTION, "테스트 전용 합성 시간")
                    put(CalendarContract.Events.DTSTART, start.atZone(ZoneId.of("Asia/Seoul")).toInstant().toEpochMilli())
                    put(CalendarContract.Events.DTEND, end.atZone(ZoneId.of("Asia/Seoul")).toInstant().toEpochMilli())
                    put(CalendarContract.Events.EVENT_TIMEZONE, "Asia/Seoul")
                    put(CalendarContract.Events.AVAILABILITY, CalendarContract.Events.AVAILABILITY_BUSY)
                }
                checkNotNull(resolver.insert(CalendarContract.Events.CONTENT_URI, values))
            }
            "cleanup" -> for (id in calendars()) resolver.delete(owned(ContentUris.withAppendedId(CalendarContract.Calendars.CONTENT_URI, id)), null, null)
            "count" -> Unit
            else -> error("Unknown fixture action")
        }
        val ids = calendars()
        val count = resolver.query(CalendarContract.Events.CONTENT_URI, arrayOf(CalendarContract.Events._ID),
            "${CalendarContract.Events.ACCOUNT_NAME} = ?", arrayOf(account), null)!!.use { it.count }
        File(context.filesDir, "calendar-conflict-fixture.txt").writeText("calendars=${ids.joinToString(",")}\nevents=$count\n")
    }
}
