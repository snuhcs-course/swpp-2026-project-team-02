package com.swpp.licenseplanner.data

import com.swpp.licenseplanner.data.model.StudyPlanResponse
import com.swpp.licenseplanner.domain.DemoAdjustment
import com.swpp.licenseplanner.domain.DemoManualAdjustment
import com.swpp.licenseplanner.domain.DemoScheduleAdjuster
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

/**
 * Demo-flavor adjustment: a local, labeled calculation using the plan's original windows and
 * exam date, for both the automatic move and a chosen time. No HTTP, no AI summary, and nothing is saved outside the app process.
 */
class DemoAdjustmentRepository(
    /** The current local minute; the manual policy refuses earlier starts. */
    private val now: () -> LocalDateTime = { LocalDateTime.now().truncatedTo(ChronoUnit.MINUTES) },
) : AdjustmentRepository {
    override suspend fun adjust(input: AdjustmentInput): AdjustmentOutcome {
        val manual = input.manualMove ?: return automatic(input)
        return when (val result = DemoScheduleAdjuster.moveManually(
            input.original, input.schedules, input.scheduleId, manual.newStart, now(), manual.confirmOutsideAvailability,
            manual.confirmExternalOverlap,
        )) {
            is DemoManualAdjustment.Moved -> AdjustmentOutcome.Success(
                StudyPlanResponse(schedules = result.schedules, scheduleCsv = result.csv, agentSummary = null),
            )
            is DemoManualAdjustment.Rejected -> AdjustmentOutcome.Failure(ApiError.ManualMoveRejected(result.reason))
            DemoManualAdjustment.InvalidTarget -> AdjustmentOutcome.Failure(ApiError.InvalidInput(null))
        }
    }

    private fun automatic(input: AdjustmentInput): AdjustmentOutcome =
        when (val result = DemoScheduleAdjuster.adjust(input.original, input.schedules, input.scheduleId)) {
            is DemoAdjustment.Moved -> AdjustmentOutcome.Success(
                StudyPlanResponse(schedules = result.schedules, scheduleCsv = result.csv, agentSummary = null),
            )
            DemoAdjustment.NoSlot -> AdjustmentOutcome.DemoNoSlot
            DemoAdjustment.InvalidTarget -> AdjustmentOutcome.Failure(ApiError.InvalidInput(null))
        }

    /** Demo plans live only in the app and never need a reload; nothing is read. */
    override suspend fun reload(scheduleCsvFile: String?): ReloadOutcome =
        ReloadOutcome.Failure(ApiError.InvalidInput(null))
}
