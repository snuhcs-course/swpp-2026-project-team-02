package com.swpp.licenseplanner.data

import com.swpp.licenseplanner.data.model.ScheduleRow
import com.swpp.licenseplanner.domain.WireDateTime
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/** The device-calendar interval used by one generation request: start inclusive, exam exclusive. */
data class CalendarReadWindow(val start: LocalDate, val endExclusive: LocalDate)

/** Calendar occurrences converted to the backend's privacy-preserving busy-schedule contract. */
data class CalendarBusySnapshot(
    val schedules: List<ScheduleRow>,
    val recurringOccurrences: Int,
    val allDayOccurrences: Int,
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

    val schedules = ordered.mapNotNull { instance ->
        val (start, end) = instance.localInterval()
        if (!end.isAfter(start)) return@mapNotNull null
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
    )
}

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
