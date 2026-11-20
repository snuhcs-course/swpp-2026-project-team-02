package com.swpp.licenseplanner.ui

import com.swpp.licenseplanner.data.CalendarDestination
import com.swpp.licenseplanner.data.CalendarWriteOutcome
import com.swpp.licenseplanner.data.CalendarReadOutcome
import com.swpp.licenseplanner.domain.CalendarEvent
import com.swpp.licenseplanner.domain.CalendarExport
import com.swpp.licenseplanner.domain.PlanRecovery
import com.swpp.licenseplanner.domain.SessionPlan

/** Why a plan's calendar export cannot start right now. */
enum class ExportBlock { NoStudy, Recovery, Storage, Busy }

/**
 * Null when [plan] may be exported now. [operationBusy] is any generation, adjustment, reload,
 * calendar read or export in progress. A live plan without a server identity can still be exported.
 */
fun exportBlock(plan: SessionPlan, storage: PlanStorage, operationBusy: Boolean): ExportBlock? = when {
    CalendarExport.studyRows(plan.current.schedules).isEmpty() -> ExportBlock.NoStudy
    plan.recovery != PlanRecovery.Ready -> ExportBlock.Recovery
    storage != PlanStorage.Ready -> ExportBlock.Storage
    operationBusy -> ExportBlock.Busy
    else -> null
}

/** [ordinal]'s entry in an observed availability map; a plan not observed yet is treated as busy. */
fun Map<Int, ExportBlock?>.blockFor(ordinal: Int): ExportBlock? = if (containsKey(ordinal)) get(ordinal) else ExportBlock.Busy

/** The plan copy one export works from, captured when it starts; later plan changes never alter it. */
data class ExportSource(val planOrdinal: Int, val isDemo: Boolean, val events: List<CalendarEvent>, val capturedPlan: SessionPlan? = null)

/** One export flow, from the permission check to its result. Only [Confirming] can lead to a write. */
sealed interface CalendarExportPhase {
    /** The screen should check access and, if missing, show the system prompt once. */
    data object RequestingPermission : CalendarExportPhase
    /** The system prompt is shown; its answer arrives through the same flow, also after rotation. */
    data object AwaitingPermission : CalendarExportPhase
    data object PermissionDenied : CalendarExportPhase
    data object LoadingCalendars : CalendarExportPhase
    data object CalendarsUnavailable : CalendarExportPhase
    data object NoCalendars : CalendarExportPhase
    /** A study row cannot be exported, so nothing is. */
    data object InvalidRows : CalendarExportPhase
    data object Checking : CalendarExportPhase
    data class Choosing(val calendars: List<CalendarDestination>, val selected: CalendarDestination? = null) : CalendarExportPhase
    data class Confirming(val calendars: List<CalendarDestination>, val destination: CalendarDestination) : CalendarExportPhase
    /** Inserting; the flow can be neither closed nor confirmed again. */
    data class Writing(val destination: CalendarDestination) : CalendarExportPhase
    data class Finished(val destination: CalendarDestination, val outcome: CalendarWriteOutcome) : CalendarExportPhase
}

/** [id] tells flows apart, so a late result never lands in a newer flow. */
data class CalendarExportState(
    val id: Int,
    val source: ExportSource,
    val phase: CalendarExportPhase,
    val checked: CalendarReadOutcome? = null,
    val unchecked: Boolean = false,
)
