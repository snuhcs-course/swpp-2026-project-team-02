package com.swpp.licenseplanner.data

import com.swpp.licenseplanner.BuildConfig

/** Live flavor wiring (design D2): HTTP repositories against the public API base URL; no demo fallback. */
object FlavorRepositories {
    init {
        check(!BuildConfig.DEMO_MODE) { "live repositories in a demo build" }
    }

    private val client by lazy { createApiHttpClient() }

    fun questionRepository(): QuestionRepository = LiveQuestionRepository(BuildConfig.API_BASE_URL, client)
    fun planningRepository(): PlanningRepository = LivePlanningRepository(BuildConfig.API_BASE_URL, client)
}
