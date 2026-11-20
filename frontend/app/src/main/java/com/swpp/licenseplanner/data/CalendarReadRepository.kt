package com.swpp.licenseplanner.data

import android.provider.CalendarContract
import com.swpp.licenseplanner.data.model.ScheduleRow
import com.swpp.licenseplanner.domain.CalendarExport
import com.swpp.licenseplanner.domain.WireDateTime
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.security.MessageDigest

/** The device-calendar interval used by one generation request: start inclusive, exam exclusive. */
data class CalendarReadWindow(val start: LocalDate, val endExclusive: LocalDate)

/** Calendar occurrences converted to the backend's privacy-preserving busy-schedule contract. */
data class CalendarBusySnapshot(
    val schedules: List<ScheduleRow>,
    val recurringOccurrences: Int,
    val allDayOccurrences: Int,
    val occurrences: List<CalendarOccurrence> = schedules.mapNotNull {
        val start = WireDateTime.parseOrNull(it.startDate) ?: return@mapNotNull null
        val end = WireDateTime.parseOrNull(it.endDate) ?: return@mapNotNull null
        CalendarOccurrence(it.scheduleId, null, start, end, false)
    },
)

/** Local timing identity only. These keys never belong to a request or stored plan. */
data class CalendarOccurrence(
    val key: String,
    val seriesKey: String?,
    val start: LocalDateTime,
    val end: LocalDateTime,
    val allDay: Boolean,
)

sealed interface CalendarReadOutcome {
    data class Loaded(val snapshot: CalendarBusySnapshot) : CalendarReadOutcome
    data object PermissionMissing : CalendarReadOutcome
    data object Unavailable : CalendarReadOutcome
}

interface CalendarReadRepository {
    suspend fun read(window: CalendarReadWindow): CalendarReadOutcome
}

object UnavailableCalendarRead : CalendarReadRepository {
    override suspend fun read(window: CalendarReadWindow) = CalendarReadOutcome.Unavailable
}

/** One expanded row from CalendarContract.Instances. Titles are deliberately never read. */
internal data class ProviderCalendarInstance(
    val eventId: Long,
    val beginMillis: Long,
    val endMillis: Long,
    val allDay: Boolean,
    val recurring: Boolean,
)

/**
 * One raw provider row while the cursor is read. The status fields and [description] only decide
 * whether the occurrence blocks time; they are dropped before mapping, and this is deliberately not a
 * data class so the description cannot end up in a log through toString.
 */
internal class ProviderInstanceRow(
    val instance: ProviderCalendarInstance,
    val status: Int?,
    val deleted: Boolean,
    val availability: Int?,
    val selfAttendeeStatus: Int?,
    val description: String?,
) {
    /**
     * Busy and tentative occurrences block time. Canceled, deleted, free and self-declined ones do not,
     * nor do events this app exported itself. An absent optional value is not a reason to drop a row.
     */
    fun blocksTime(): Boolean =
        status != CalendarContract.Events.STATUS_CANCELED &&
            !deleted &&
            availability != CalendarContract.Events.AVAILABILITY_FREE &&
            selfAttendeeStatus != CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED &&
            !CalendarExport.isExportedDescription(description)
}

/** The timing-only instances of the rows that block time. */
internal fun eligibleInstances(rows: Sequence<ProviderInstanceRow>): List<ProviderCalendarInstance> =
    rows.filter { it.blocksTime() }.map { it.instance }.toList()

/**
 * Maps expanded instances to unique positive numeric IDs and Korean local wire times.
 *
 * A recurring event reuses its provider event ID for every occurrence. The first occurrence keeps
 * that ID; later occurrences receive deterministic IDs above the largest provider ID so the
 * backend's unique-ID validation can retain every occurrence. Exact duplicate provider rows are
 * removed. Timed events are rounded outward to a minute so sub-minute calendar time is never
 * treated as free. All-day events use their UTC calendar dates and block those local dates.
 */
internal fun mapCalendarInstances(instances: List<ProviderCalendarInstance>): CalendarBusySnapshot {
    val ordered = instances
        .asSequence()
        .filter { it.eventId > 0 && it.endMillis > it.beginMillis }
        .distinctBy { listOf(it.eventId, it.beginMillis, it.endMillis, it.allDay) }
        .sortedWith(compareBy<ProviderCalendarInstance> { it.beginMillis }.thenBy { it.endMillis }.thenBy { it.eventId })
        .toList()
    val providerIds = ordered.map { it.eventId }.toSet()
    val used = mutableSetOf<Long>()
    var synthetic = providerIds.maxOrNull()?.takeIf { it < Long.MAX_VALUE }?.plus(1) ?: 1L

    fun uniqueId(providerId: Long): Long {
        if (used.add(providerId)) return providerId
        while (synthetic <= 0 || synthetic in providerIds || synthetic in used) {
            synthetic = if (synthetic == Long.MAX_VALUE) 1L else synthetic + 1L
        }
        return synthetic.also {
            used += it
            synthetic = if (it == Long.MAX_VALUE) 1L else it + 1L
        }
    }

    val occurrences = mutableListOf<CalendarOccurrence>()
    val schedules = ordered.mapNotNull { instance ->
        val (start, end) = instance.localInterval()
        if (!end.isAfter(start)) return@mapNotNull null
        occurrences += CalendarOccurrence(
            timingKey("${instance.eventId}/${instance.beginMillis}/${instance.endMillis}/${instance.allDay}"),
            if (instance.recurring) timingKey("series/${instance.eventId}") else null,
            start, end, instance.allDay,
        )
        ScheduleRow(
            scheduleId = uniqueId(instance.eventId).toString(),
            startDate = WireDateTime.format(start),
            endDate = WireDateTime.format(end),
            topic = CALENDAR_BUSY_TOPIC,
        )
    }
    return CalendarBusySnapshot(
        schedules = schedules,
        recurringOccurrences = ordered.count { it.recurring },
        allDayOccurrences = ordered.count { it.allDay },
        occurrences = occurrences,
    )
}

private fun timingKey(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

private fun ProviderCalendarInstance.localInterval(): Pair<LocalDateTime, LocalDateTime> = if (allDay) {
    val startDate = Instant.ofEpochMilli(beginMillis).atZone(ZoneOffset.UTC).toLocalDate()
    val endDate = Instant.ofEpochMilli(endMillis).atZone(ZoneOffset.UTC).toLocalDate()
    startDate.atStartOfDay() to endDate.atStartOfDay()
} else {
    val start = Instant.ofEpochMilli(beginMillis).atZone(CALENDAR_ZONE).toLocalDateTime().floorMinute()
    val rawEnd = Instant.ofEpochMilli(endMillis).atZone(CALENDAR_ZONE).toLocalDateTime()
    start to rawEnd.ceilMinute()
}

private fun LocalDateTime.floorMinute() = withSecond(0).withNano(0)

private fun LocalDateTime.ceilMinute(): LocalDateTime {
    val floor = floorMinute()
    return if (second == 0 && nano == 0) floor else floor.plusMinutes(1)
}

internal val CALENDAR_ZONE: ZoneId = ZoneId.of("Asia/Seoul")
internal const val CALENDAR_BUSY_TOPIC = "외부 일정"
