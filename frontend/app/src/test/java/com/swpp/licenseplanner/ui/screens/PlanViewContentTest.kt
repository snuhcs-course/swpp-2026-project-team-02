package com.swpp.licenseplanner.ui.screens

import com.swpp.licenseplanner.TestFixtures
import com.swpp.licenseplanner.data.MappingResult
import com.swpp.licenseplanner.data.RequestMapper
import com.swpp.licenseplanner.data.model.ScheduleRow
import com.swpp.licenseplanner.data.model.StudyPlanResponse
import com.swpp.licenseplanner.domain.PlanRecovery
import com.swpp.licenseplanner.domain.SessionPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The plan view's alert and creation action follow the stored plan and the held creation inputs only. */
class PlanViewContentTest {
    private val request = (RequestMapper.map(TestFixtures.completeState()) as MappingResult.Ready).request
    private val rows = listOf(ScheduleRow("study-1", "2026/10/05/18/00", "2026/10/05/19/00", "함수"))
    private val live = SessionPlan.register(1, request, StudyPlanResponse(rows, scheduleCsvFile = "plan.csv"), isDemo = false)
    private val withoutIdentity = SessionPlan.register(2, request, StudyPlanResponse(rows), isDemo = false)

    @Test
    fun planAwaitingReloadWarnsThatTheShownRowsMayBeOutdated() {
        val alert = planViewAlert(live.copy(recovery = PlanRecovery.ReloadRequired))
        assertEquals(PlanViewAlert.ReloadRequired, alert)
        assertEquals("최신 계획을 확인해 주세요", alert?.title)
    }

    @Test
    fun planMissingOnTheServerSaysItCanNoLongerBeAdjusted() {
        assertEquals("이 계획은 더 이상 조정할 수 없어요", planViewAlert(live.copy(recovery = PlanRecovery.Missing))?.title)
        // The stored recovery state is reported before a missing identity.
        assertEquals(PlanViewAlert.Missing, planViewAlert(withoutIdentity.copy(recovery = PlanRecovery.Missing)))
    }

    @Test
    fun livePlanWithoutIdentitySaysItCannotBeAdjusted() {
        assertEquals("이 계획은 조정할 수 없어요", planViewAlert(withoutIdentity)?.title)
    }

    @Test
    fun readyPlansShowNoAlert() {
        assertNull(planViewAlert(live))
        assertNull(planViewAlert(SessionPlan.register(3, request, StudyPlanResponse(rows), isDemo = true)))
    }

    @Test
    fun inputActionIsOfferedOnlyForThePlanWhoseInputsAreHeld() {
        assertEquals(PlanInputAction.EditInputs, planInputAction(ordinal = 2, generatedPlan = 2, hasSessions = true))
        assertEquals("입력 바꿔 새 계획 만들기", PlanInputAction.EditInputs.label)
        assertNull(planInputAction(ordinal = 1, generatedPlan = 2, hasSessions = true))
    }

    @Test
    fun inputActionDisappearsOnceTheHeldInputsAreCleared() {
        assertNull(planInputAction(ordinal = 2, generatedPlan = null, hasSessions = true))
    }

    @Test
    fun emptyPlanOffersNewCreationUnlessItsInputsAreHeld() {
        assertEquals(PlanInputAction.NewPlan, planInputAction(ordinal = 1, generatedPlan = null, hasSessions = false))
        assertEquals("새 계획 만들기", PlanInputAction.NewPlan.label)
        assertEquals(PlanInputAction.EditInputs, planInputAction(ordinal = 1, generatedPlan = 1, hasSessions = false))
    }
}
