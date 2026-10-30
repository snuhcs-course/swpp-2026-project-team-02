package com.swpp.licenseplanner.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/** Demo plans exist only in the app, so there is never a saved plan to read back. */
class DemoAdjustmentRepositoryTest {
    @Test
    fun reloadReadsNothing() {
        val repository = DemoAdjustmentRepository()
        for (identity in listOf(null, "license_planner/output/study_plan_a.csv")) {
            assertEquals(ReloadOutcome.Failure(ApiError.InvalidInput(null)), runBlocking { repository.reload(identity) })
        }
    }
}
