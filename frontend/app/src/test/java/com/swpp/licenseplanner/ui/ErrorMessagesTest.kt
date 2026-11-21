package com.swpp.licenseplanner.ui

import com.swpp.licenseplanner.data.ApiError
import com.swpp.licenseplanner.data.errorForStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** User-facing error wording and which adjustment failures need a reload. */
class ErrorMessagesTest {
    private val uncertain = "조정 결과를 확인할 수 없어요. 서버에 반영되었을 수 있으니 최신 계획을 불러와 확인해 주세요."
    private val route = "API 경로를 찾을 수 없어요. API 주소와 서버 버전을 확인해 주세요. (HTTP 404)"
    private val fallback = "원인을 알 수 없는 오류가 발생했어요. 세부 메시지를 확인해 주세요."

    private fun invalid(message: String?) = ApiError.InvalidInput(message)

    @Test fun onlyUncertainUnknownFailuresRequireReload() {
        for (status in listOf(400, 401)) {
            val error = ApiError.UnknownFailure(status, "diagnostic")
            assertFalse(error.requiresReload())
            assertEquals(fallback, error.adjustmentMessage())
        }
        for (status in listOf(409, null)) {
            val error = ApiError.UnknownFailure(status, "diagnostic")
            assertTrue(error.requiresReload())
            assertTrue(error.adjustmentMessage().contains("최신 계획"))
        }
    }

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
        val errors = listOf(ApiError.Connection, ApiError.InvalidResponse("raw parser detail"), ApiError.RequestTimeout("HTTP 408"))
        for (error in errors) {
            assertEquals(error.toString(), uncertain, error.adjustmentMessage())
            assertTrue(error.requiresReload())
        }
        val unknown = ApiError.UnknownFailure(409, "conflict")
        assertEquals(
            "원인을 알 수 없는 오류가 발생했어요. 세부 메시지를 확인해 주세요.\n조정 결과가 서버에 반영되었을 수 있어 최신 계획을 불러와 확인해 주세요.",
            unknown.adjustmentMessage(),
        )
        assertTrue(unknown.requiresReload())
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
            ApiError.Configuration,
            ApiError.PlanNotFound,
        )
        for (error in confirmed) assertFalse(error.toString(), error.requiresReload())
        assertEquals(fallback, confirmed[0].adjustmentMessage())
        assertEquals(fallback, confirmed[1].adjustmentMessage())
        assertEquals("서버가 아직 준비되지 않았어요. 잠시 후 다시 시도해 주세요.", confirmed[2].adjustmentMessage())
        assertEquals("서버에서 요청을 처리하지 못했어요. 잠시 후 다시 시도해 주세요.", confirmed[4].adjustmentMessage())
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
            "화면에는 이전 계획이 남아 있어요. 네트워크에 연결되지 않았거나 서버가 응답하지 않아요. 연결 상태를 확인한 뒤 다시 시도해 주세요.",
            ApiError.Connection.reloadMessage(),
        )
        assertEquals(
            "화면에는 이전 계획이 남아 있어요. 서버 응답을 읽을 수 없어요. 다시 시도해 주세요.",
            ApiError.InvalidResponse("duplicate schedule_id").reloadMessage(),
        )
    }

    @Test
    fun recognizedDateProblemsAreExplainedWithTheFormEditAction() {
        val examDate = "시험일을 확인할 수 없어요.\n'입력 수정하기'에서 입력한 시험일을 확인해 주세요."
        for (raw in listOf(
            "exam_date is invalid", "exam_date is required", "exam_date must use YYYY/MM/DD/HH/MM format",
            "exam_date: datetime must use exact YYYY/MM/DD/HH/MM format",
        )) {
            assertEquals(raw, examDate, invalid(raw).userMessage())
        }
        assertEquals(
            "준비 시작일을 확인할 수 없어요.\n'입력 수정하기'에서 입력한 준비 시작일을 확인해 주세요.",
            invalid("preparation_start: unconverted data remains: /Users/x/backend/api_service.py").userMessage(),
        )
        assertEquals(
            "준비 시작일은 시험일보다 빨라야 해요.\n'입력 수정하기'에서 두 날짜를 확인해 주세요.",
            invalid("preparation_start must be before exam_date").userMessage(),
        )
    }

    @Test
    fun recognizedWindowProblemsNameTheWeekdayInKorean() {
        val missing = "공부할 수 있는 시간이 등록되지 않았어요.\n'입력 수정하기'에서 공부할 요일과 시간을 추가해 주세요."
        assertEquals(missing, invalid("weekly_availability is required. Enter at least one weekday and available time window.").userMessage())
        assertEquals(missing, invalid("weekly_availability must contain at least one study window").userMessage())
        assertEquals(
            "수요일 공부 시간은 시작 시각이 끝 시각보다 빨라야 해요.\n'입력 수정하기'에서 수요일 시간을 고쳐 주세요.",
            invalid("wednesday window 1 start must be before end").userMessage(),
        )
        assertEquals(
            "일요일 공부 시간이 서로 겹쳐요.\n'입력 수정하기'에서 일요일 시간을 고쳐 주세요.",
            invalid("sunday availability windows cannot overlap").userMessage(),
        )
        assertEquals(
            "월요일 공부 시간의 시각 형식이 올바르지 않아요.\n'입력 수정하기'에서 월요일 시간을 고쳐 주세요.",
            invalid("monday window 2 times must use HH:MM").userMessage(),
        )
    }

    @Test
    fun capacityFactsAreKeptOnlyFromCurrentTemplates() {
        val advice = "공부 가능 시간을 늘리거나, 준비를 더 일찍 시작하거나, 시험일을 늦춰 주세요."
        val exact = invalid("NOT_ENOUGH_TIME: 준비 시작부터 시험일까지 공부할 수 있는 시간이 1분이라 진단된 학습 주제 3개에 주제마다 최소 1분씩 배정할 수 없습니다. $advice")
        assertEquals(
            "시험일 전까지 공부할 시간이 부족해요.\n준비 기간에 공부할 수 있는 시간이 1분이라 진단된 학습 주제 3개에 주제마다 최소 1분씩 배정할 수 없어요.\n" +
                "$advice '입력 수정하기'에서 바꿀 수 있어요.",
            exact.userMessage(),
        )
        val lowerBound = invalid("NOT_ENOUGH_TIME: 준비 시작부터 시험일까지 공부할 수 있는 시간이 0분이라 학습 주제 1개에도 최소 1분을 배정할 수 없습니다. $advice")
        val shown = lowerBound.userMessage()
        assertEquals(
            "시험일 전까지 공부할 시간이 부족해요.\n준비 기간에 공부할 수 있는 시간이 0분이라 학습 주제를 하나도 배정할 수 없어요.\n" +
                "$advice '입력 수정하기'에서 바꿀 수 있어요.",
            shown,
        )
        assertFalse(shown.contains("1개"))
        val generic = "시험일 전까지 공부할 시간이 부족해요.\n$advice '입력 수정하기'에서 바꿀 수 있어요."
        for (raw in listOf(
            "NOT_ENOUGH_TIME:",
            "NOT_ENOUGH_TIME: only 5 minutes for /Users/x/plan.csv",
            // Facts that contradict the template's own claim are not trusted.
            "NOT_ENOUGH_TIME: 준비 시작부터 시험일까지 공부할 수 있는 시간이 9분이라 진단된 학습 주제 3개에 주제마다 최소 1분씩 배정할 수 없습니다. $advice",
            "NOT_ENOUGH_TIME: 준비 시작부터 시험일까지 공부할 수 있는 시간이 2분이라 학습 주제 1개에도 최소 1분을 배정할 수 없습니다. $advice",
        )) {
            assertEquals(raw, generic, invalid(raw).userMessage())
            assertFalse(invalid(raw).userMessage().contains("NOT_ENOUGH_TIME"))
        }
    }

    @Test
    fun assessmentPayloadProblemsAreNotBlamedOnTheAnswer() {
        val shown = "답안 정보를 서버에서 처리하지 못했어요. 선택한 답이 틀렸다는 뜻은 아니에요."
        for (raw in listOf(
            "assessment_results is required", "assessment_results.results must contain at least one item",
            "assessment result problem_id must be positive", "Duplicate assessment result for problem_id: 3",
            "user_answer and correct_answer must be choice keys for problem_id: 2", "is_correct must be a boolean for problem_id: 1",
        )) {
            assertEquals(raw, shown, invalid(raw).userMessage())
        }
    }

    @Test
    fun unknownTechnicalAndLookalikeTextGetsTheNeutralFallback() {
        for (raw in listOf(
            null, "", "   ", "schedule_id must name a study row", "unknown weekday names: funday",
            "weekly_availability must be a JSON object", "{\"detail\":\"bad\"}",
            "Traceback (most recent call last):\n  File \"/srv/backend/api_service.py\"",
            // Similar wording that is not an exact backend family must not acquire its cause.
            "exam_date is invalid because of /Users/x/.env", "the exam_date is invalid", "exam_date is invalid\nmore",
            "preparation_start must be before exam_date or later", "funday window 1 start must be before end",
            "monday window x start must be before end", "assessment_results are fine", "Duplicate assessment result",
            "NOT_ENOUGH_TIME without the marker colon",
        )) {
            assertEquals(raw.toString(), fallback, invalid(raw).userMessage())
        }
    }

    @Test
    fun adjustmentAndReloadAdvicePointToANewPlanNotTheForm() {
        val windows = invalid("weekly_availability must contain at least one study window")
        val adjusted = windows.adjustmentMessage()
        assertEquals(
            "공부할 수 있는 시간이 등록되지 않았어요.\n이 계획에 저장된 입력은 바꿀 수 없어요. 목록으로 돌아가 공부할 요일과 시간을 넣어 새 계획을 만들어 주세요.",
            adjusted,
        )
        assertFalse(adjusted.contains("입력 수정하기"))
        assertEquals(
            "화면에는 이전 계획이 남아 있어요. 화요일 공부 시간이 서로 겹쳐요.\n" +
                "이 계획에 저장된 입력은 바꿀 수 없어요. 목록으로 돌아가 화요일 시간을 올바르게 넣어 새 계획을 만들어 주세요.",
            invalid("tuesday availability windows cannot overlap").reloadMessage(),
        )
        assertEquals("화면에는 이전 계획이 남아 있어요. $fallback", invalid("schedule_csv_file must be given at most once").reloadMessage())
        val capacity = invalid("NOT_ENOUGH_TIME:").adjustmentMessage()
        assertEquals("시험일 전까지 공부할 시간이 부족해요.\n목록으로 돌아가 공부 시간이나 날짜를 바꿔 새 계획을 만들어 주세요.", capacity)
        for (error in listOf(windows, invalid("x"))) assertFalse(error.requiresReload())
    }

    @Test
    fun savedPlanWithoutStoredExamDateAsksForANewPlan() {
        val known = "This saved plan has no stored exam date. Create a new plan before adjusting study sessions."
        val guidance = "이 계획에는 시험일 정보가 저장되어 있지 않아요.\n홈의 '계획 생성'에서 새 계획을 만든 뒤 그 계획에서 조정해 주세요."
        val error = invalid(known)
        assertEquals(guidance, error.adjustmentMessage())
        assertEquals("화면에는 이전 계획이 남아 있어요. $guidance", error.reloadMessage())
        assertFalse(error.requiresReload())
        for (raw in listOf(
            "$known license_planner/output/study_plan_x.csv",
            "$known\nTraceback (most recent call last)",
            "This saved plan has no stored exam date.",
            "this saved plan has no stored exam date. create a new plan before adjusting study sessions.",
            "This saved plan has no stored exam date. Create a new plan before adjusting study session.",
        )) {
            assertEquals(raw, fallback, invalid(raw).adjustmentMessage())
        }
    }

    @Test
    fun storedExamDateMismatchUsesTheDateExplanation() {
        assertEquals(
            "시험일을 확인할 수 없어요.\n이 계획에 저장된 입력은 바꿀 수 없어요. 목록으로 돌아가 시험일을 확인해 새 계획을 만들어 주세요.",
            invalid("exam_date: must match the selected plan's stored exam date").adjustmentMessage(),
        )
    }

    /** Generation and question loading keep their earlier wording. */
    @Test
    fun generalMessagesAreUnchanged() {
        assertEquals("API 주소 또는 서버 경로를 확인해 주세요.", ApiError.Configuration.userMessage())
        assertEquals("서버에서 요청을 처리하지 못했어요. 잠시 후 다시 시도해 주세요.", ApiError.ServerFailure(409).userMessage())
        assertEquals("네트워크에 연결되지 않았거나 서버가 응답하지 않아요. 연결 상태를 확인한 뒤 다시 시도해 주세요.", ApiError.Connection.userMessage())
        assertEquals("서버 응답을 읽을 수 없어요. 다시 시도해 주세요.", ApiError.InvalidResponse("x").userMessage())
    }

    private val identity = "이 기기의 서버 요청을 확인할 수 없어요. 앱이 최신 버전인지 확인해 주세요."
    private val quota = "요청이 너무 많아요. 잠시 후 다시 시도해 주세요."
    private fun envelope(code: String) = """{"error":{"code":"$code","message":"server detail"}}"""

    @Test fun onlyTheExactIdentityAndQuotaPairsAreRecognizedOnEveryRoute() {
        for (planErrors in listOf(false, true)) {
            assertEquals(ApiError.DeviceTokenRejected, errorForStatus(401, envelope("INVALID_DEVICE_TOKEN"), planErrors))
            assertEquals(ApiError.RateLimited, errorForStatus(429, envelope("RATE_LIMITED"), planErrors))
            for ((status, body) in listOf(
                401 to envelope("RATE_LIMITED"), 429 to envelope("INVALID_DEVICE_TOKEN"), 401 to envelope("invalid_device_token"),
                401 to "not json", 429 to "", 429 to """{"error":{"message":"Too many requests."}}""",
                403 to envelope("INVALID_DEVICE_TOKEN"), 503 to envelope("RATE_LIMITED"),
            )) {
                val error = errorForStatus(status, body, planErrors)
                assertFalse(error == ApiError.DeviceTokenRejected || error == ApiError.RateLimited)
                assertFalse(error.userMessage() == identity || error.userMessage() == quota)
            }
        }
    }

    @Test fun identityAndQuotaRejectionsUseFixedKoreanCopyOnEverySurface() {
        for ((error, copy) in listOf(ApiError.DeviceTokenRejected to identity, ApiError.RateLimited to quota)) {
            assertEquals(copy, error.userMessage())
            assertEquals(copy, error.adjustmentMessage())
            assertEquals("화면에는 이전 계획이 남아 있어요. $copy", error.reloadMessage())
            assertEquals(copy, remainingFailureMessage(error))
            assertFalse(error.requiresReload())
            assertFalse(error.developerMessage().contains("server detail"))
        }
        assertEquals("HTTP 401: INVALID_DEVICE_TOKEN", ApiError.DeviceTokenRejected.developerMessage())
        assertEquals("HTTP 429: RATE_LIMITED", ApiError.RateLimited.developerMessage())
    }

    @Test fun anUnavailableDeviceCredentialIsADefiniteSetupFailure() {
        val error = ApiError.DeviceTokenUnavailable
        assertTrue(error.userMessage().contains("저장된 계획은 그대로 볼 수 있어요"))
        assertEquals(error.userMessage(), error.adjustmentMessage())
        assertEquals(error.userMessage(), remainingFailureMessage(error))
        assertFalse(error.requiresReload())
    }
}
