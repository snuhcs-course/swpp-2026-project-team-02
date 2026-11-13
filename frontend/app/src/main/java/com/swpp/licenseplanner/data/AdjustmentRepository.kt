package com.swpp.licenseplanner.data

import com.swpp.licenseplanner.data.model.ScheduleRow
import com.swpp.licenseplanner.data.model.StudyPlanRequest
import com.swpp.licenseplanner.data.model.StudyPlanResponse
import java.time.LocalDateTime

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
    /** A reviewed chosen time; null for the automatic move. */
    val manualMove: ManualMove? = null,
)

/**
 * A chosen start for the target, reviewed by the learner. [confirmOutsideAvailability] is the
 * explicit one-time exception for this destination only; it never waives bounds or overlaps.
 */
data class ManualMove(val newStart: LocalDateTime, val confirmOutsideAvailability: Boolean = false)

sealed interface AdjustmentOutcome {
    /** The validated updated plan; [response] carries rows, CSV and an optional summary. */
    data class Success(val response: StudyPlanResponse) : AdjustmentOutcome
    /** Demo-only: no permitted interval fits before the exam. */
    data object DemoNoSlot : AdjustmentOutcome
    data class Failure(val error: ApiError) : AdjustmentOutcome
}

/** Reading one plan's latest saved rows back from the server. */
sealed interface ReloadOutcome {
    /** The single exactly matching plan's validated rows; may hold no study rows at all. */
    data class Loaded(val schedules: List<ScheduleRow>) : ReloadOutcome
    /** A valid plan list without the requested identity. */
    data object Missing : ReloadOutcome
    data class Failure(val error: ApiError) : ReloadOutcome
}

/** Failed-study adjustment; live and demo implementations are chosen by flavor. */
interface AdjustmentRepository {
    suspend fun adjust(input: AdjustmentInput): AdjustmentOutcome

    /** Reads the latest saved rows of the plan stored as [scheduleCsvFile]; never changes the server. */
    suspend fun reload(scheduleCsvFile: String?): ReloadOutcome
}
