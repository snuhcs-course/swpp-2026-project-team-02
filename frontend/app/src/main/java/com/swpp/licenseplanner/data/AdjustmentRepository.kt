package com.swpp.licenseplanner.data

import com.swpp.licenseplanner.data.model.ScheduleRow
import com.swpp.licenseplanner.data.model.StudyPlanRequest
import com.swpp.licenseplanner.data.model.StudyPlanResponse

/**
 * One adjustment, captured before sending and reused unchanged on retry.
 * [original] is the selected plan's creation request, so its windows and exam date never
 * come from the current form; [scheduleCsvFile] is the live server identity (null for demo).
 */
data class AdjustmentInput(
    val original: StudyPlanRequest,
    val scheduleCsvFile: String?,
    val schedules: List<ScheduleRow>,
    val scheduleId: String,
)

sealed interface AdjustmentOutcome {
    /** The validated updated plan; [response] carries rows, CSV and an optional summary. */
    data class Success(val response: StudyPlanResponse) : AdjustmentOutcome
    /** Demo-only: no permitted interval fits before the exam. */
    data object DemoNoSlot : AdjustmentOutcome
    data class Failure(val error: ApiError) : AdjustmentOutcome
}

/** Failed-study adjustment; live and demo implementations are chosen by flavor. */
interface AdjustmentRepository {
    suspend fun adjust(input: AdjustmentInput): AdjustmentOutcome
}
