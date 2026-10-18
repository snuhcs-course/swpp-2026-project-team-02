package com.swpp.licenseplanner.data

import com.swpp.licenseplanner.TestFixtures
import com.swpp.licenseplanner.data.model.StudyPlanListResponse
import com.swpp.licenseplanner.domain.CERTIFICATION_ID
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in real Python HTTP + Kotlin repository test. Run against api_server.py --demo. */
class HttpBackendIntegrationTest {
    @Test fun generateAdjustReloadAndRejectStaleSnapshot() = runBlocking {
        val base = System.getenv("LICENSE_PLANNER_TEST_API")
        assumeTrue("Set LICENSE_PLANNER_TEST_API to the demo server URL", !base.isNullOrBlank())
        val url = requireNotNull(base)
        val questions = LiveQuestionRepository(url).load(CERTIFICATION_ID) as QuestionOutcome.Loaded
        val request = (RequestMapper.map(TestFixtures.completeState(questions = questions.questions)) as MappingResult.Ready).request
        val generated = LivePlanningRepository(url).generate(request) as PlanOutcome.Success
        val plan = generated.response
        assertFalse(plan.scheduleCsvFile.isNullOrBlank())
        val target = plan.schedules.first { it.scheduleId.startsWith("study-") }
        val input = AdjustmentInput(request, plan.scheduleCsvFile, plan.schedules, target.scheduleId)
        val repository = LiveAdjustmentRepository(url)
        val adjusted = repository.adjust(input) as AdjustmentOutcome.Success
        assertEquals(plan.scheduleCsvFile, adjusted.response.scheduleCsvFile)
        assertNotEquals(target, adjusted.response.schedules.first { it.scheduleId == target.scheduleId })

        val client = createApiHttpClient()
        val listing = client.executeApi(Request.Builder().url(apiUrl(url, "/study-plans")).build()) as HttpOutcome.Ok
        val saved = ApiJson.decodeFromString(StudyPlanListResponse.serializer(), listing.body)
            .plans.single { it.scheduleCsvFile == plan.scheduleCsvFile }
        assertEquals(adjusted.response.schedules, saved.schedules)
        val csv = client.executeApi(Request.Builder().url(apiUrl(url, "/study-plans/" + plan.scheduleCsvFile!!.substringAfterLast('/'))).build()) as HttpOutcome.Ok
        assertEquals(adjusted.response.scheduleCsv, csv.body)

        val stale = repository.adjust(input) as AdjustmentOutcome.Failure
        assertEquals(ApiError.ServerFailure(409), stale.error)
        val refreshed = repository.adjust(input) as AdjustmentOutcome.Refreshed
        assertEquals(saved.schedules, refreshed.response.schedules)
    }
}
