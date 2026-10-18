package com.swpp.licenseplanner.data

import com.swpp.licenseplanner.BuildConfig

/** Explicit HTTP demo: same repositories and wire contract as live, separate app ID and label. */
object FlavorRepositories {
    private val client by lazy { createApiHttpClient() }

    fun questionRepository(): QuestionRepository = LiveQuestionRepository(BuildConfig.API_BASE_URL, client)
    fun planningRepository(): PlanningRepository = LivePlanningRepository(BuildConfig.API_BASE_URL, client)
    fun adjustmentRepository(): AdjustmentRepository = LiveAdjustmentRepository(BuildConfig.API_BASE_URL, client)
}
