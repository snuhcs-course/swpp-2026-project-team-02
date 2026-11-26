package com.swpp.licenseplanner.data

import org.junit.Assert.assertTrue
import org.junit.Test

/** The demo build wires only local repositories and never reads a device credential. */
class DemoFlavorRepositoriesTest {
    private val tokens = DeviceTokenSource { throw AssertionError("demo read the device credential") }

    @Test fun demoRepositoriesAreLocalAndNeverReadTheCredential() {
        assertTrue(FlavorRepositories.questionRepository(tokens) is DemoQuestionRepository)
        assertTrue(FlavorRepositories.planningRepository(tokens) is DemoPlanningRepository)
        assertTrue(FlavorRepositories.adjustmentRepository(tokens) is DemoAdjustmentRepository)
        assertTrue(FlavorRepositories.remainingStudyRepository(tokens) is DemoRemainingStudyRepository)
        assertTrue(FlavorRepositories.reassessmentRepository(tokens) is DemoReassessmentRepository)
    }
}
