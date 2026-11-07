package com.swpp.licenseplanner.data

import com.swpp.licenseplanner.domain.CalendarEvent

/** A writable device calendar offered as an export destination; [label] tells equal names apart. */
data class CalendarDestination(val id: Long, val label: String)

sealed interface CalendarListOutcome {
    /** Writable calendars; empty when the device has none. */
    data class Loaded(val calendars: List<CalendarDestination>) : CalendarListOutcome
    data object PermissionMissing : CalendarListOutcome
    /** The calendar query failed; nothing is known about the device's calendars. */
    data object Unavailable : CalendarListOutcome
}

/** Why an export stopped before its first insert. */
enum class ExportNotStarted {
    PermissionMissing,
    /** The calendars could not be read again just before writing. */
    CalendarsUnavailable,
    /** The chosen calendar is gone or no longer writable. */
    DestinationUnavailable,
    /** The plan is no longer the one confirmed, or may no longer be exported. */
    PlanUnavailable,
}

/** What an export did. Counts include only inserts that returned a valid event identity. */
sealed interface CalendarWriteOutcome {
    data class Complete(val added: Int) : CalendarWriteOutcome
    /** Nothing was inserted. */
    data class NotStarted(val reason: ExportNotStarted) : CalendarWriteOutcome
    /**
     * [added] inserts were confirmed, then one failed. When [uncertain], the failed insert may
     * still have created an event, so the calendar can hold more than [added] new events.
     */
    data class Stopped(val added: Int, val uncertain: Boolean) : CalendarWriteOutcome
}

/** One-way copy of events into an existing device calendar; never updates, deletes or retries. */
interface CalendarExportRepository {
    suspend fun calendars(): CalendarListOutcome

    /** Checks access and [destination] again, then inserts [events] in order, stopping at the first failure. */
    suspend fun export(destination: CalendarDestination, events: List<CalendarEvent>): CalendarWriteOutcome
}

/** Used where no device calendar is wired in; it never reports a write. */
object UnavailableCalendarExport : CalendarExportRepository {
    override suspend fun calendars() = CalendarListOutcome.Unavailable
    override suspend fun export(destination: CalendarDestination, events: List<CalendarEvent>) =
        CalendarWriteOutcome.NotStarted(ExportNotStarted.CalendarsUnavailable)
}

/** A calendar row as the provider returns it. */
data class ProviderCalendar(val id: Long, val displayName: String, val accountName: String)

/**
 * Labels calendars by display name, adding the account only for names shared by several calendars
 * and a number when that is still ambiguous.
 */
internal fun labelCalendars(calendars: List<ProviderCalendar>): List<CalendarDestination> {
    val names = calendars.map { it.displayName.ifBlank { "이름 없는 캘린더" } }
    val withAccounts = calendars.mapIndexed { index, calendar ->
        val name = names[index]
        if (names.count { it == name } > 1 && calendar.accountName.isNotBlank()) "$name · ${calendar.accountName}" else name
    }
    return calendars.mapIndexed { index, calendar ->
        val label = withAccounts[index]
        val same = withAccounts.indices.filter { withAccounts[it] == label }
        CalendarDestination(calendar.id, if (same.size > 1) "$label (${same.indexOf(index) + 1})" else label)
    }
}
