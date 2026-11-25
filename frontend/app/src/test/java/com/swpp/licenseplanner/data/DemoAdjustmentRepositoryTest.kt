package com.swpp.licenseplanner.data

import com.swpp.licenseplanner.data.model.AssessmentResults
import com.swpp.licenseplanner.data.model.ScheduleRow
import com.swpp.licenseplanner.data.model.StudyPlanRequest
import com.swpp.licenseplanner.data.model.TimeWindow
import com.swpp.licenseplanner.domain.CERTIFICATION_ID
import com.swpp.licenseplanner.domain.ManualMoveRejection
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime

/** Demo plans exist only in the app: no saved plan to read back, and moves are local calculations. */
class DemoAdjustmentRepositoryTest {
    @Test
    fun reloadReadsNothing() {
        val repository = DemoAdjustmentRepository()
        for (identity in listOf(null, "license_planner/output/study_plan_a.csv")) {
            assertEquals(ReloadOutcome.Failure(ApiError.InvalidInput(null)), runBlocking { repository.reload(identity) })
        }
    }

    @Test
    fun manualMovesAreDecidedLocallyWithTheInjectedClock() {
        val original = StudyPlanRequest(
            CERTIFICATION_ID, "2026/10/19/00/00", "2026/12/14/00/00", "intermediate",
            AssessmentResults(CERTIFICATION_ID, emptyList()), 1,
            mapOf("tuesday" to listOf(TimeWindow("18:00", durationMinutes = 60))),
        )
        val rows = listOf(ScheduleRow("study-1", "2026/10/19/18/00", "2026/10/19/19/00", "함수"))
        val repository = DemoAdjustmentRepository(now = { LocalDateTime.of(2026, 10, 19, 12, 0) })
        fun adjust(start: LocalDateTime, confirm: Boolean) =
            runBlocking { repository.adjust(AdjustmentInput(original, null, rows, "study-1", ManualMove(start, confirm))) }

        val outside = LocalDateTime.of(2026, 10, 20, 14, 0)
        assertEquals(AdjustmentOutcome.Failure(ApiError.ManualMoveRejected(ManualMoveRejection.OutsideStudyWindows)), adjust(outside, false))
        val success = adjust(outside, true) as AdjustmentOutcome.Success
        assertEquals("2026/10/20/15/00", success.response.schedules.single().endDate)
        assertEquals(
            AdjustmentOutcome.Failure(ApiError.ManualMoveRejected(ManualMoveRejection.InPast)),
            adjust(LocalDateTime.of(2026, 10, 19, 11, 0), true),
        )
    }
}
