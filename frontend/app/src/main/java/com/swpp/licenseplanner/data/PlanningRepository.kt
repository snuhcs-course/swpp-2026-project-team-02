package com.swpp.licenseplanner.data

import com.swpp.licenseplanner.data.model.StudyPlanRequest
import com.swpp.licenseplanner.data.model.StudyPlanResponse

sealed interface PlanOutcome {
    /** A plan to show; [isDemo] marks a local demonstration result. */
    data class Success(val response: StudyPlanResponse, val isDemo: Boolean) : PlanOutcome
    /** Demo-only: required sessions do not fit before the exam (GEN-06). */
    data class DemoShortfall(val required: Int, val available: Int) : PlanOutcome
    data class Failure(val error: ApiError) : PlanOutcome
}

/** Plan generation; live and demo implementations are chosen by flavor (design D2). */
interface PlanningRepository {
    suspend fun generate(request: StudyPlanRequest): PlanOutcome
}
