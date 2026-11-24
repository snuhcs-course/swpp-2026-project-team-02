package com.swpp.licenseplanner.data

import com.swpp.licenseplanner.BuildConfig

/**
 * Demo flavor wiring: local questions, the labeled demo planner and demo adjuster.
 * The device credential is never read: demo makes no API request.
 */
@Suppress("UNUSED_PARAMETER")
object FlavorRepositories {
    init {
        check(BuildConfig.DEMO_MODE) { "demo repositories in a non-demo build" }
    }

    fun questionRepository(tokens: DeviceTokenSource): QuestionRepository = DemoQuestionRepository()
    fun planningRepository(tokens: DeviceTokenSource): PlanningRepository = DemoPlanningRepository()
    fun adjustmentRepository(tokens: DeviceTokenSource): AdjustmentRepository = DemoAdjustmentRepository()
    fun remainingStudyRepository(tokens: DeviceTokenSource): RemainingStudyRepository = DemoRemainingStudyRepository()
    fun reassessmentRepository(tokens: DeviceTokenSource): ReassessmentRepository =
        DemoReassessmentRepository(DemoReassessmentFixture.questions)
}
