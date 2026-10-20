package com.swpp.licenseplanner.ui.screens

import com.swpp.licenseplanner.TestFixtures
import com.swpp.licenseplanner.data.ApiError
import com.swpp.licenseplanner.data.MappingResult
import com.swpp.licenseplanner.data.RequestMapper
import com.swpp.licenseplanner.data.model.ScheduleRow
import com.swpp.licenseplanner.data.model.StudyPlanResponse
import com.swpp.licenseplanner.domain.PlanRecovery
import com.swpp.licenseplanner.domain.SessionPlan
import com.swpp.licenseplanner.ui.AdjustmentState
import org.junit.Assert.assertEquals
import org.junit.Test

/** The adjustment screen's offered actions follow the stored recovery before the transient outcome. */
class AdjustmentModeTest {
    private val request = (RequestMapper.map(TestFixtures.completeState()) as MappingResult.Ready).request
    private val rows = listOf(
        ScheduleRow("study-1", "2026/10/05/18/00", "2026/10/05/19/00", "함수"),
        ScheduleRow("external-1", "2026/10/06/18/00", "2026/10/06/19/00", ""),
    )
    private val live = SessionPlan.register(1, request, StudyPlanResponse(rows, scheduleCsvFile = "plan.csv"), isDemo = false)

    private val transient = listOf(
        AdjustmentState.Idle,
        AdjustmentState.Failure(ApiError.Connection),
        AdjustmentState.Success("study-1"),
        AdjustmentState.Reloaded,
    )

    @Test
    fun missingPlanStaysMissingWhateverTheOutcome() {
        val missing = live.copy(recovery = PlanRecovery.Missing)
        for (state in transient + AdjustmentState.Failure(ApiError.PlanNotFound)) {
            assertEquals(AdjustmentMode.Missing, adjustmentMode(missing, state))
        }
    }

    @Test
    fun reloadRequiredOffersOnlyReading() {
        val gated = live.copy(recovery = PlanRecovery.ReloadRequired)
        // Including after reopening the plan, when the failure itself is gone.
        for (state in transient + AdjustmentState.Failure(ApiError.PlanConflict)) {
            assertEquals(AdjustmentMode.ReloadNeeded, adjustmentMode(gated, state))
        }
        assertEquals(AdjustmentMode.Reloading, adjustmentMode(gated, AdjustmentState.Reloading))
        assertEquals(AdjustmentMode.ReloadFailed, adjustmentMode(gated, AdjustmentState.ReloadFailure(ApiError.Configuration)))
    }

    @Test
    fun readyPlansKeepTheExistingModes() {
        assertEquals(AdjustmentMode.Adjustable, adjustmentMode(live, AdjustmentState.Reloaded))
        assertEquals(AdjustmentMode.Adjustable, adjustmentMode(live, AdjustmentState.Failure(ApiError.ServerFailure(503))))
        assertEquals(AdjustmentMode.NoIdentity, adjustmentMode(live.copy(scheduleCsvFile = null), AdjustmentState.Idle))
        val externalOnly = live.copy(current = live.current.copy(schedules = rows.drop(1)))
        assertEquals(AdjustmentMode.NoStudy, adjustmentMode(externalOnly, AdjustmentState.Reloaded))
    }
}
