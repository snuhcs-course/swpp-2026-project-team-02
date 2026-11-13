package com.swpp.licenseplanner.data

import com.swpp.licenseplanner.BuildConfig

/** Demo flavor wiring: local questions, the labeled demo planner and demo adjuster. */
object FlavorRepositories {
    init {
        check(BuildConfig.DEMO_MODE) { "demo repositories in a non-demo build" }
    }

    fun questionRepository(): QuestionRepository = DemoQuestionRepository()
    fun planningRepository(): PlanningRepository = DemoPlanningRepository()
    fun adjustmentRepository(): AdjustmentRepository = DemoAdjustmentRepository()

    /** Demo keeps study times on the device only; there is no server profile. */
    fun availabilityProfileRepository(): AvailabilityProfileRepository? = null
}
