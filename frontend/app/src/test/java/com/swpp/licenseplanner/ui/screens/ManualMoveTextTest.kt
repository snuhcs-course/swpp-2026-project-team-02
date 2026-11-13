package com.swpp.licenseplanner.ui.screens

import com.swpp.licenseplanner.TestFixtures
import com.swpp.licenseplanner.data.ApiError
import com.swpp.licenseplanner.data.MappingResult
import com.swpp.licenseplanner.data.RequestMapper
import com.swpp.licenseplanner.data.model.ScheduleRow
import com.swpp.licenseplanner.data.model.StudyPlanResponse
import com.swpp.licenseplanner.domain.ManualMoveRejection
import com.swpp.licenseplanner.domain.PlanRecovery
import com.swpp.licenseplanner.domain.SessionPlan
import com.swpp.licenseplanner.ui.AdjustmentSelection
import com.swpp.licenseplanner.ui.AdjustmentState
import com.swpp.licenseplanner.ui.ManualDraft
import com.swpp.licenseplanner.ui.message
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

/** Which screen a chosen-time draft shows, where refusals appear and how intervals read. */
class ManualMoveTextTest {
    private val request = (RequestMapper.map(TestFixtures.completeState()) as MappingResult.Ready).request
    private val rows = listOf(
        ScheduleRow("study-1", "2026/11/25/18/00", "2026/11/25/19/00", "함수와 데이터 관리"),
        ScheduleRow("external-1", "2026/11/24/18/00", "2026/11/24/19/00", ""),
    )
    private val live = SessionPlan.register(1, request, StudyPlanResponse(rows, scheduleCsvFile = "plan.csv"), isDemo = false)
    private val chosen = LocalDateTime.of(2026, 11, 24, 14, 0)
    private val drafting = AdjustmentSelection(1, "study-1", manual = ManualDraft(chosen))

    private fun mode(plan: SessionPlan, selection: AdjustmentSelection) = adjustmentMode(plan, selection.state)

    @Test
    fun anOpenDraftOnAReadyStudySessionShowsTheEditor() {
        val session = manualSession(live, drafting, mode(live, drafting))
        assertEquals("study-1", session?.scheduleId)
        assertEquals(60L, session?.durationMinutes)
        // Without a draft, the adjustment screen stays.
        val plain = drafting.copy(manual = null)
        assertNull(manualSession(live, plain, mode(live, plain)))
    }

    @Test
    fun recoveryGatesAndExternalRowsNeverShowTheEditor() {
        for (recovery in listOf(PlanRecovery.ReloadRequired, PlanRecovery.Missing)) {
            val gated = live.copy(recovery = recovery)
            assertNull(manualSession(gated, drafting, mode(gated, drafting)))
        }
        val noIdentity = live.copy(scheduleCsvFile = null)
        assertNull(manualSession(noIdentity, drafting, mode(noIdentity, drafting)))
        val external = drafting.copy(failedScheduleId = "external-1")
        assertNull(manualSession(live, external, mode(live, external)))
    }

    @Test
    fun destinationIsMissingUntilATimeOtherThanTheCurrentOneIsChosen() {
        val session = manualSession(live, drafting, mode(live, drafting))!!
        assertEquals(chosen, manualDestination(drafting, session))
        assertNull(manualDestination(drafting.copy(manual = ManualDraft()), session))
        assertNull(manualDestination(drafting.copy(manual = ManualDraft(session.start)), session))
    }

    @Test
    fun refusalsPointToTheFieldTheyConcern() {
        assertEquals(ManualField.Date, ManualMoveRejection.InPast.field())
        assertEquals(ManualField.Date, ManualMoveRejection.BeforePreparationStart.field())
        assertEquals(ManualField.Start, ManualMoveRejection.ExamBoundExceeded.field())
        assertEquals(ManualField.Start, ManualMoveRejection.StudySessionOverlap.field())
        assertEquals(ManualField.Start, ManualMoveRejection.ExternalEventOverlap.field())
        // The window refusal asks for confirmation instead of showing an error.
        assertNull(ManualMoveRejection.OutsideStudyWindows.field())
    }

    @Test
    fun conflictMessagesNameTheirSourceAndAskForAnotherTime() {
        assertEquals("다른 공부 일정과 겹쳐요. 다른 시간을 골라 주세요.", ManualMoveRejection.StudySessionOverlap.message())
        assertEquals("캘린더에서 가져온 일정과 겹쳐요. 다른 시간을 골라 주세요.", ManualMoveRejection.ExternalEventOverlap.message())
        assertEquals("이미 지난 시간이에요. 앞으로의 시간을 골라 주세요.", ManualMoveRejection.InPast.message())
        assertEquals("준비 시작일 전이에요. 준비 기간 안에서 날짜를 골라 주세요.", ManualMoveRejection.BeforePreparationStart.message())
        assertEquals("공부가 시험일을 넘겨요. 더 이른 시간을 골라 주세요.", ManualMoveRejection.ExamBoundExceeded.message())
    }

    @Test
    fun onlyTheWindowRefusalOfTheSameDraftAsksForConfirmation() {
        val outside = drafting.copy(state = AdjustmentState.Failure(ApiError.ManualMoveRejected(ManualMoveRejection.OutsideStudyWindows)))
        assertEquals(true, outside.canConfirmOutsideAvailability)
        // Already confirmed, in flight, or a different refusal: no second confirmation.
        assertEquals(false, outside.copy(manual = ManualDraft(chosen, confirmOutsideAvailability = true)).canConfirmOutsideAvailability)
        assertEquals(false, outside.copy(state = AdjustmentState.Loading).canConfirmOutsideAvailability)
        val overlap = drafting.copy(state = AdjustmentState.Failure(ApiError.ManualMoveRejected(ManualMoveRejection.ExternalEventOverlap)))
        assertEquals(false, overlap.canConfirmOutsideAvailability)
    }

    @Test
    fun dateFieldAndIntervalsReadLikeTheDesign() {
        assertEquals("2026-11-24 (화)", manualDateText(LocalDate.of(2026, 11, 24)))
        assertEquals(
            "11월 25일(수) 18:00–19:00",
            manualIntervalText(LocalDateTime.of(2026, 11, 25, 18, 0), LocalDateTime.of(2026, 11, 25, 19, 0)),
        )
        assertEquals(
            "11월 29일(일) 23:30–다음 날 00:30",
            manualIntervalText(LocalDateTime.of(2026, 11, 29, 23, 30), LocalDateTime.of(2026, 11, 30, 0, 30)),
        )
        // Ending exactly at midnight is still the next day.
        assertEquals(
            "11월 30일(월) 23:00–다음 날 00:00",
            manualIntervalText(LocalDateTime.of(2026, 11, 30, 23, 0), LocalDateTime.of(2026, 12, 1, 0, 0)),
        )
    }
}
