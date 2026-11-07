package com.swpp.licenseplanner.data

import android.content.ContentResolver
import android.content.Context
import android.provider.CalendarContract
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate

internal interface CalendarReadAccess {
    fun hasPermission(): Boolean
    fun instances(start: LocalDate, endExclusive: LocalDate): List<ProviderCalendarInstance>?
}

class AndroidCalendarReadRepository internal constructor(
    private val access: CalendarReadAccess,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : CalendarReadRepository {
    override suspend fun read(window: CalendarReadWindow): CalendarReadOutcome = withContext(io) {
        if (!access.hasPermission()) return@withContext CalendarReadOutcome.PermissionMissing
        if (!window.endExclusive.isAfter(window.start)) return@withContext CalendarReadOutcome.Unavailable
        val instances = try {
            access.instances(window.start, window.endExclusive)
        } catch (_: SecurityException) {
            return@withContext CalendarReadOutcome.PermissionMissing
        } catch (_: RuntimeException) {
            return@withContext CalendarReadOutcome.Unavailable
        } ?: return@withContext CalendarReadOutcome.Unavailable
        CalendarReadOutcome.Loaded(mapCalendarInstances(instances))
    }

    companion object {
        fun create(context: Context): CalendarReadRepository = AndroidCalendarReadRepository(
            AndroidCalendarReadAccess(context.applicationContext, context.contentResolver),
        )
    }
}

private class AndroidCalendarReadAccess(
    private val context: Context,
    private val resolver: ContentResolver,
) : CalendarReadAccess {
    override fun hasPermission(): Boolean = CalendarAccess.READ.isGranted(context)

    override fun instances(start: LocalDate, endExclusive: LocalDate): List<ProviderCalendarInstance>? {
        val begin = start.atStartOfDay(CALENDAR_ZONE).toInstant().toEpochMilli()
        val end = endExclusive.atStartOfDay(CALENDAR_ZONE).toInstant().toEpochMilli()
        val projection = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.RRULE,
            CalendarContract.Instances.STATUS,
            CalendarContract.Events.DELETED,
            CalendarContract.Instances.AVAILABILITY,
            CalendarContract.Instances.SELF_ATTENDEE_STATUS,
            CalendarContract.Instances.DESCRIPTION,
        )
        // The provider expands recurrences and returns only visible calendars' instances.
        val cursor = CalendarContract.Instances.query(resolver, projection, begin, end) ?: return null
        return cursor.use {
            fun optionalInt(index: Int): Int? = if (it.isNull(index)) null else it.getInt(index)
            eligibleInstances(
                generateSequence { if (it.moveToNext()) Unit else null }.map { _ ->
                    ProviderInstanceRow(
                        instance = ProviderCalendarInstance(
                            eventId = it.getLong(0),
                            beginMillis = it.getLong(1),
                            endMillis = it.getLong(2),
                            allDay = it.getInt(3) != 0,
                            recurring = !it.isNull(4) && it.getString(4).isNotBlank(),
                        ),
                        status = optionalInt(5),
                        deleted = (optionalInt(6) ?: 0) != 0,
                        availability = optionalInt(7),
                        selfAttendeeStatus = optionalInt(8),
                        description = if (it.isNull(9)) null else it.getString(9),
                    )
                },
            )
        }
    }
}
