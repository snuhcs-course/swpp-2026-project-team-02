package com.swpp.licenseplanner.data

import com.swpp.licenseplanner.BuildConfig

/** Demo flavor wiring (design D2): local questions and the labeled demo planner. */
object FlavorRepositories {
    init {
        check(BuildConfig.DEMO_MODE) { "demo repositories in a non-demo build" }
    }

    fun questionRepository(): QuestionRepository = DemoQuestionRepository()
    fun planningRepository(): PlanningRepository = DemoPlanningRepository()
}
