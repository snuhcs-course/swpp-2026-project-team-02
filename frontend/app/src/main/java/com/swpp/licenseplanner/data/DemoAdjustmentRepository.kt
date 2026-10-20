package com.swpp.licenseplanner.data

import com.swpp.licenseplanner.data.model.StudyPlanResponse
import com.swpp.licenseplanner.domain.DemoAdjustment
import com.swpp.licenseplanner.domain.DemoScheduleAdjuster

/**
 * Demo-flavor adjustment: a local, labeled calculation using the plan's original windows and
 * exam date. No HTTP, no AI summary, and nothing is saved outside the app process.
 */
class DemoAdjustmentRepository : AdjustmentRepository {
    override suspend fun adjust(input: AdjustmentInput): AdjustmentOutcome =
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
