package com.swpp.licenseplanner.data

import com.swpp.licenseplanner.BuildConfig

/** Live flavor wiring: HTTP repositories against the public API base URL; no demo fallback. */
object FlavorRepositories {
    init {
        check(!BuildConfig.DEMO_MODE) { "live repositories in a demo build" }
    }

    private val client by lazy { createApiHttpClient() }

    fun questionRepository(): QuestionRepository = LiveQuestionRepository(BuildConfig.API_BASE_URL, client)
    fun planningRepository(): PlanningRepository = LivePlanningRepository(BuildConfig.API_BASE_URL, client)
    fun adjustmentRepository(): AdjustmentRepository = LiveAdjustmentRepository(BuildConfig.API_BASE_URL, client)

    /** The shared server profile, read or saved only on explicit request. */
    fun availabilityProfileRepository(): AvailabilityProfileRepository? =
        LiveAvailabilityProfileRepository(BuildConfig.API_BASE_URL, client)
}
