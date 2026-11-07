package com.swpp.licenseplanner.data

import android.Manifest
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import com.swpp.licenseplanner.domain.CalendarEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** The calls made to the device's calendar provider; blocking, so callers run them off the main thread. */
interface CalendarProviderAccess {
    fun hasPermissions(): Boolean

    /** Calendars the app may add events to, or null when the query failed. */
    fun writableCalendars(): List<ProviderCalendar>?

    /** Inserts one event; the new event's ID, or null when the provider gave no valid receipt. */
    fun insert(calendarId: Long, event: CalendarEvent): Long?
}

/**
 * [CalendarExportRepository] over the Android calendar provider. Inserts one event at a time and
 * counts it only after a valid receipt, because batched provider writes have no all-or-nothing
 * guarantee. Nothing is retried or deleted.
 */
class AndroidCalendarExportRepository(
    private val access: CalendarProviderAccess,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : CalendarExportRepository {
    override suspend fun calendars(): CalendarListOutcome = withContext(io) {
        if (!access.hasPermissions()) return@withContext CalendarListOutcome.PermissionMissing
        try {
            access.writableCalendars()?.let { CalendarListOutcome.Loaded(labelCalendars(it)) } ?: CalendarListOutcome.Unavailable
        } catch (_: SecurityException) {
            CalendarListOutcome.PermissionMissing
        } catch (_: RuntimeException) {
            CalendarListOutcome.Unavailable
        }
    }

    override suspend fun export(destination: CalendarDestination, events: List<CalendarEvent>): CalendarWriteOutcome = withContext(io) {
        if (!access.hasPermissions()) return@withContext CalendarWriteOutcome.NotStarted(ExportNotStarted.PermissionMissing)
        val current = try {
            access.writableCalendars()
        } catch (_: SecurityException) {
            return@withContext CalendarWriteOutcome.NotStarted(ExportNotStarted.PermissionMissing)
        } catch (_: RuntimeException) {
            null
        } ?: return@withContext CalendarWriteOutcome.NotStarted(ExportNotStarted.CalendarsUnavailable)
        if (current.none { it.id == destination.id }) {
            return@withContext CalendarWriteOutcome.NotStarted(ExportNotStarted.DestinationUnavailable)
        }
        var added = 0
        for (event in events) {
            ensureActive()
            val id = try {
                access.insert(destination.id, event)
            } catch (error: CancellationException) {
                throw error
            } catch (_: SecurityException) {
                // Refused by the provider, so this insert created nothing.
                return@withContext CalendarWriteOutcome.Stopped(added, uncertain = false)
            } catch (_: RuntimeException) {
                return@withContext CalendarWriteOutcome.Stopped(added, uncertain = true)
            }
            if (id == null || id <= 0) return@withContext CalendarWriteOutcome.Stopped(added, uncertain = true)
            added++
        }
        CalendarWriteOutcome.Complete(added)
    }

    companion object {
        fun create(context: Context) = AndroidCalendarExportRepository(ContentResolverCalendarAccess(context.applicationContext))
    }
}

/** [CalendarProviderAccess] through the application's [ContentResolver]; holds no activity. */
private class ContentResolverCalendarAccess(private val context: Context) : CalendarProviderAccess {
    private val resolver: ContentResolver get() = context.contentResolver

    override fun hasPermissions(): Boolean = listOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
        .all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

    override fun writableCalendars(): List<ProviderCalendar>? {
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.ACCOUNT_NAME,
        )
        val selection = "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ?"
        val args = arrayOf(CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR.toString())
        return resolver.query(CalendarContract.Calendars.CONTENT_URI, projection, selection, args, "${CalendarContract.Calendars._ID} ASC")
            ?.use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        add(ProviderCalendar(cursor.getLong(0), cursor.getString(1).orEmpty(), cursor.getString(2).orEmpty()))
                    }
                }
            }
    }

    override fun insert(calendarId: Long, event: CalendarEvent): Long? {
        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.TITLE, event.title)
            put(CalendarContract.Events.DESCRIPTION, event.description)
            put(CalendarContract.Events.DTSTART, event.startMillis)
            put(CalendarContract.Events.DTEND, event.endMillis)
            put(CalendarContract.Events.EVENT_TIMEZONE, event.timeZone)
            put(CalendarContract.Events.ALL_DAY, 0)
        }
        val uri = resolver.insert(CalendarContract.Events.CONTENT_URI, values) ?: return null
        return try {
            ContentUris.parseId(uri)
        } catch (_: RuntimeException) {
            null
        }
    }
}
