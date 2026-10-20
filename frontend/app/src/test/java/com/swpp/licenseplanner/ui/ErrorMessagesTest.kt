package com.swpp.licenseplanner.ui

import com.swpp.licenseplanner.data.ApiError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** User-facing error wording and which adjustment failures need a reload. */
class ErrorMessagesTest {
    private val uncertain = "조정 결과를 확인할 수 없어요. 서버에 반영되었을 수 있으니 최신 계획을 불러와 확인해 주세요."
    private val route = "API 경로를 찾을 수 없어요. API 주소와 서버 버전을 확인해 주세요. (HTTP 404)"

    @Test
    fun adjustmentMessagesDistinguishMissingChangedAndRouteErrors() {
        assertEquals(
            "서버에서 이 계획을 찾을 수 없어요. 화면에는 이전 계획이 남아 있어요. 목록에서 다른 계획을 고르거나 새 계획을 만들어 주세요.",
            ApiError.PlanNotFound.adjustmentMessage(),
        )
        assertEquals("서버의 계획이 변경되었어요. 최신 계획을 불러온 뒤 일정을 다시 선택해 주세요.", ApiError.PlanConflict.adjustmentMessage())
        assertEquals(route, ApiError.Configuration.adjustmentMessage())
    }

    @Test
    fun uncertainResultsUseOneMessageThatClaimsNoOutcome() {
        val errors = listOf(ApiError.ServerFailure(409), ApiError.Connection, ApiError.InvalidResponse("raw parser detail"))
        for (error in errors) {
            assertEquals(error.toString(), uncertain, error.adjustmentMessage())
            assertTrue(error.requiresReload())
        }
        assertTrue(ApiError.PlanConflict.requiresReload())
    }

    @Test
    fun confirmedFailuresKeepTheirMessagesAndAllowRetry() {
        val confirmed = listOf(
            ApiError.InvalidInput("schedule_id must name a study row"),
            ApiError.InvalidInput(null),
            ApiError.ServerUnavailable("busy"),
            ApiError.ServerFailure(500),
            ApiError.ServerFailure(502),
            ApiError.ServerFailure(408),
            ApiError.Configuration,
            ApiError.PlanNotFound,
        )
        for (error in confirmed) assertFalse(error.toString(), error.requiresReload())
        assertEquals("schedule_id must name a study row", confirmed[0].adjustmentMessage())
        assertEquals("입력값을 확인해 주세요.", confirmed[1].adjustmentMessage())
        assertEquals("서버가 아직 준비되지 않았어요. 잠시 후 다시 시도해 주세요. (HTTP 503)", confirmed[2].adjustmentMessage())
        assertEquals("서버에서 요청을 처리하지 못했어요. 다시 시도해 주세요. (HTTP 502)", confirmed[4].adjustmentMessage())
    }

    @Test
    fun messagesNeverShowServerDetailsForRecoveryErrors() {
        val detail = "license_planner/output/study_plan_x.csv"
        for (error in listOf(ApiError.PlanNotFound, ApiError.PlanConflict, ApiError.InvalidResponse(detail), ApiError.ServerFailure(409))) {
            assertFalse(error.adjustmentMessage().contains(detail))
            assertFalse(error.reloadMessage().contains(detail))
        }
    }

    @Test
    fun reloadFailureKeepsTheEarlierPlanAndNamesTheCause() {
        assertEquals("화면에는 이전 계획이 남아 있어요. $route", ApiError.Configuration.reloadMessage())
        assertEquals(
            "화면에는 이전 계획이 남아 있어요. 서버에 연결할 수 없어요. 네트워크와 서버 상태를 확인한 뒤 다시 시도해 주세요.",
            ApiError.Connection.reloadMessage(),
        )
        assertEquals(
            "화면에는 이전 계획이 남아 있어요. 서버 응답을 읽을 수 없어요. 다시 시도해 주세요.",
            ApiError.InvalidResponse("duplicate schedule_id").reloadMessage(),
        )
    }

    /** Generation and question loading keep their earlier wording. */
    @Test
    fun generalMessagesAreUnchanged() {
        assertEquals("연결 또는 설정 문제가 있어요. API 주소를 확인해 주세요. (HTTP 404)", ApiError.Configuration.userMessage())
        assertEquals("서버에서 요청을 처리하지 못했어요. 다시 시도해 주세요. (HTTP 409)", ApiError.ServerFailure(409).userMessage())
        assertEquals("서버에 연결할 수 없어요. 네트워크와 서버 상태를 확인한 뒤 다시 시도해 주세요.", ApiError.Connection.userMessage())
        assertEquals("서버 응답을 읽을 수 없어요. 다시 시도해 주세요.", ApiError.InvalidResponse("x").userMessage())
    }
}
