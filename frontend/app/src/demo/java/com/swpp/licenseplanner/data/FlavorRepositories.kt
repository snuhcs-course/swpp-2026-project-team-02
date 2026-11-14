package com.swpp.licenseplanner.data

import com.swpp.licenseplanner.BuildConfig

/** Demo flavor wiring: local questions, the labeled demo planner and demo adjuster. */
object FlavorRepositories {
    private val questions = DemoQuestionRepository()
    private val planning = DemoPlanningRepository()
    private val adjustment = DemoAdjustmentRepository()

    init {
        check(BuildConfig.DEMO_MODE) { "demo repositories in a non-demo build" }
    }

    fun questionRepository(): QuestionRepository = questions
    fun planningRepository(): PlanningRepository = planning
    fun adjustmentRepository(): AdjustmentRepository = adjustment

    fun armDemoFailure(operation: DemoRequestOperation, error: ApiError) {
        DemoFailureInjector.arm(operation, error)
    }
}
