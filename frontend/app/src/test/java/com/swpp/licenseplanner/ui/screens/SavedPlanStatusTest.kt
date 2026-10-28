package com.swpp.licenseplanner.ui.screens

import com.swpp.licenseplanner.data.model.AssessmentResults
import com.swpp.licenseplanner.data.model.ScheduleRow
import com.swpp.licenseplanner.data.model.StudyPlanRequest
import com.swpp.licenseplanner.data.model.StudyPlanResponse
import com.swpp.licenseplanner.data.model.TimeWindow
import com.swpp.licenseplanner.domain.CERTIFICATION_ID
import com.swpp.licenseplanner.domain.PlanRecovery
import com.swpp.licenseplanner.domain.SessionPlan
import com.swpp.licenseplanner.ui.PlanStorage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SavedPlanStatusTest {
    private val request = StudyPlanRequest(
        CERTIFICATION_ID, "2026/10/05/00/00", "2026/11/16/00/00", "beginner",
        AssessmentResults(CERTIFICATION_ID, emptyList()), 1, mapOf("monday" to listOf(TimeWindow("18:00", "20:00"))),
    )
    private val rows = listOf(ScheduleRow("study-1", "2026/10/05/18/00", "2026/10/05/19/00", "함수"))
    private fun plan(ordinal: Int) = SessionPlan(ordinal, request, false, StudyPlanResponse(rows), "plan.csv")

    @Test fun nothingIsClaimedBeforeTheSavedPlansAreRead() {
        assertNull(planCountNote(PlanStorage.Loading, 0, 0))
        assertNull(planCountNote(PlanStorage.LoadFailure, 0, 0))
        assertEquals("아직 만든 계획이 없어요.", planCountNote(PlanStorage.Ready, 0, 0))
        assertEquals("저장된 계획 2개가 있어요.", planCountNote(PlanStorage.Ready, 2, 2))
    }

    @Test fun unsavedChangesAreNotCalledSaved() {
        val saving = planCountNote(PlanStorage.Saving, 3, 2)!!
        assertFalse(saving.startsWith("저장된"))
        val failed = planCountNote(PlanStorage.SaveFailure, 3, 2)!!
        assertTrue(failed, "3개" in failed && "2개" in failed)
    }

    @Test fun onlyNewOrChangedEntriesAreUnsaved() {
        val saved = listOf(plan(1), plan(2))
        val shown = listOf(plan(1), plan(2).copy(recovery = PlanRecovery.Missing), plan(3))
        assertEquals(setOf(2, 3), unsavedOrdinals(shown, saved))
        assertEquals(emptySet<Int>(), unsavedOrdinals(saved, saved))
    }
}
