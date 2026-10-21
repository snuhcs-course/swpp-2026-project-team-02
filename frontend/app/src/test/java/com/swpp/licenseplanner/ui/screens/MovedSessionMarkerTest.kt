package com.swpp.licenseplanner.ui.screens

import com.swpp.licenseplanner.TestFixtures
import com.swpp.licenseplanner.data.ApiError
import com.swpp.licenseplanner.data.MappingResult
import com.swpp.licenseplanner.data.RequestMapper
import com.swpp.licenseplanner.data.model.ScheduleRow
import com.swpp.licenseplanner.data.model.StudyPlanResponse
import com.swpp.licenseplanner.domain.SessionPlan
import com.swpp.licenseplanner.ui.AdjustmentState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Only the study row of the current successful adjustment carries the moved tag. */
class MovedSessionMarkerTest {
    private val request = (RequestMapper.map(TestFixtures.completeState()) as MappingResult.Ready).request
    private val rows = listOf(
        ScheduleRow("study-1", "2026/10/12/18/00", "2026/10/12/19/00", "함수"),
        ScheduleRow("study-2", "2026/10/05/19/00", "2026/10/05/20/00", "차트"),
        ScheduleRow("external-1", "2026/10/06/18/00", "2026/10/06/19/00", ""),
    )
    private val plan = SessionPlan.register(1, request, StudyPlanResponse(rows, scheduleCsvFile = "plan.csv"), isDemo = false)

    @Test
    fun successMarksItsReturnedStudyRow() {
        assertEquals("study-1", movedScheduleId(plan, AdjustmentState.Success("study-1")))
    }

    @Test
    fun externalOrAbsentTargetsAreNeverMarked() {
        assertNull(movedScheduleId(plan, AdjustmentState.Success("external-1")))
        assertNull(movedScheduleId(plan, AdjustmentState.Success("study-9")))
    }

    @Test
    fun anyOtherStateShowsNoMarker() {
        val others = listOf(
            AdjustmentState.Idle,
            AdjustmentState.Loading,
            AdjustmentState.Failure(ApiError.Connection),
            AdjustmentState.DemoNoSlot,
            AdjustmentState.Reloading,
            AdjustmentState.ReloadFailure(ApiError.Connection),
            AdjustmentState.Reloaded,
        )
        for (state in others) assertNull(state.toString(), movedScheduleId(plan, state))
    }
}
