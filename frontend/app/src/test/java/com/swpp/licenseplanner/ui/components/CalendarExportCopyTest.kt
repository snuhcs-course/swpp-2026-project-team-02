package com.swpp.licenseplanner.ui.components

import com.swpp.licenseplanner.data.CalendarDestination
import com.swpp.licenseplanner.data.CalendarWriteOutcome
import com.swpp.licenseplanner.data.ExportNotStarted
import com.swpp.licenseplanner.data.model.AssessmentResults
import com.swpp.licenseplanner.data.model.ScheduleRow
import com.swpp.licenseplanner.data.model.StudyPlanRequest
import com.swpp.licenseplanner.data.model.StudyPlanResponse
import com.swpp.licenseplanner.domain.PlanRecovery
import com.swpp.licenseplanner.domain.SessionPlan
import com.swpp.licenseplanner.ui.ExportBlock
import com.swpp.licenseplanner.ui.PlanStorage
import com.swpp.licenseplanner.ui.exportBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CalendarExportCopyTest {
    private val destination = CalendarDestination(3, "공부 · a@example.test")

    @Test
    fun actionUsesTheRuntimeCountAndGenericCalendarWording() {
        assertEquals("캘린더에 7개 일정 추가", exportButtonText(7))
        assertFalse(EXPORT_SUBTITLE.contains("구글"))
    }

    @Test
    fun confirmationWarningsAndTimeBasis() {
        assertEquals("다시 내보내면 일정이 중복될 수 있어요.", DUPLICATE_WARNING)
        assertEquals("계획을 조정해도 캘린더 일정은 바뀌지 않아요.", NO_UPDATE_WARNING)
        assertEquals("데모 일정도 실제 캘린더에 추가돼요.", DEMO_WRITE_WARNING)
        assertEquals("한국 시간 (Asia/Seoul)", TIME_BASIS)
    }

    @Test
    fun resultsStateOnlyConfirmedCounts() {
        val complete = exportResultMessage(CalendarWriteOutcome.Complete(5), destination, 5)
        assertEquals("5개 일정을 공부 · a@example.test에 추가했어요", complete.title)
        assertFalse(complete.body.contains("구글"))

        val stopped = exportResultMessage(CalendarWriteOutcome.Stopped(2, uncertain = false), destination, 5)
        assertTrue(stopped.body.contains("2개 일정을 추가한 뒤 멈췄어요"))
        assertTrue(stopped.body.contains("나머지 3개는 추가하지 않았어요"))

        val uncertain = exportResultMessage(CalendarWriteOutcome.Stopped(2, uncertain = true), destination, 5)
        assertTrue(uncertain.body.contains("2개 일정이 추가된 것을 확인했어요"))
        assertTrue(uncertain.body.contains("추가되었을 수 있어요"))
        assertTrue(uncertain.body.contains("캘린더에서 먼저 확인"))

        for (reason in ExportNotStarted.entries) {
            assertTrue(reason.name, exportResultMessage(CalendarWriteOutcome.NotStarted(reason), destination, 5).body.startsWith("일정을 추가하지 않았어요"))
        }
    }

    @Test
    fun exportIsBlockedForEmptyRecoveringUnsavedOrBusyPlans() {
        val request = StudyPlanRequest("c", "2026/10/19/00/00", "2026/12/14/00/00", "beginner", AssessmentResults("c", emptyList()), 1, emptyMap())
        val study = ScheduleRow("study-1", "2026/10/19/18/00", "2026/10/19/19/00", "함수")
        val external = ScheduleRow("external-1", "2026/10/20/18/00", "2026/10/20/19/00", "")
        val plan = SessionPlan(1, request, isDemo = false, StudyPlanResponse(listOf(study, external)), scheduleCsvFile = null)

        assertNull(exportBlock(plan, PlanStorage.Ready, operationBusy = false))
        assertEquals(ExportBlock.NoStudy, exportBlock(plan.copy(current = StudyPlanResponse(listOf(external))), PlanStorage.Ready, false))
        assertEquals(ExportBlock.Recovery, exportBlock(plan.copy(recovery = PlanRecovery.ReloadRequired), PlanStorage.Ready, false))
        assertEquals(ExportBlock.Recovery, exportBlock(plan.copy(recovery = PlanRecovery.Missing), PlanStorage.Ready, false))
        for (storage in listOf(PlanStorage.Loading, PlanStorage.Saving, PlanStorage.SaveFailure, PlanStorage.LoadFailure)) {
            assertEquals(ExportBlock.Storage, exportBlock(plan, storage, false))
        }
        assertEquals(ExportBlock.Busy, exportBlock(plan, PlanStorage.Ready, operationBusy = true))
    }
}
