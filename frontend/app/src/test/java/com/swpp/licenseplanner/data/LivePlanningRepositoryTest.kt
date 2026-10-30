package com.swpp.licenseplanner.data

import com.swpp.licenseplanner.TestFixtures
import com.swpp.licenseplanner.data.model.StudyPlanRequest
import com.swpp.licenseplanner.ui.userMessage
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/** Transport against a local mock server; not target-backend verification. */
class LivePlanningRepositoryTest {
    private lateinit var server: MockWebServer
    private val request: StudyPlanRequest =
        (RequestMapper.map(TestFixtures.completeState()) as MappingResult.Ready).request

    @Before fun setUp() { server = MockWebServer().apply { start() } }
    @After fun tearDown() { server.shutdown() }

    private fun repository(client: OkHttpClient = createApiHttpClient(), base: String = server.url("/api/v1/").toString()) =
        LivePlanningRepository(base, client)

    private fun generate(repo: LivePlanningRepository = repository()) = runBlocking { repo.generate(request) }

    private val successBody = """
        {"schedules":[{"schedule_id":"study-1","start_date":"2026/10/05/18/00","end_date":"2026/10/05/18/25","topic":"함수와 데이터 관리"},
                      {"schedule_id":"study-2","start_date":"2026/10/05/18/30","end_date":"2026/10/05/19/50","topic":"차트와 분석"}],
         "schedule_csv":"schedule_id,start_date,end_date,topic\n","schedule_csv_file":"license_planner/output/x.csv",
         "schedule_csv_url":"/api/v1/study-plans/x.csv","agent_summary":"AI 요약","tool_calls":[{"name":"t"}],"new_field":true}
    """.trimIndent()

    @Test
    fun postsTargetAssessmentBodyToStudyPlansPath() {
        server.enqueue(MockResponse().setBody(successBody))
        val outcome = generate()
        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/api/v1/study-plans", recorded.path)
        assertEquals("application/json; charset=utf-8", recorded.getHeader("Content-Type"))
        val body = ApiJson.parseToJsonElement(recorded.body.readUtf8()).jsonObject
        assertEquals(ApiJson.encodeToJsonElement(StudyPlanRequest.serializer(), request), body)
        assertTrue("assessment_results" in body)
        assertNull(body["problem_results"])
        val success = outcome as PlanOutcome.Success
        assertFalse(success.isDemo)
        assertEquals(2, success.response.schedules.size)
        assertEquals("AI 요약", success.response.agentSummary)
        assertEquals("license_planner/output/x.csv", success.response.scheduleCsvFile)
    }

    /** Missing, null or blank identity still displays the plan; nothing is derived from the URL. */
    @Test
    fun missingIdentityStillParses() {
        val rows = """[{"schedule_id":"study-1","start_date":"2026/10/05/18/00","end_date":"2026/10/05/19/00","topic":"t"}]"""
        val cases = mapOf(
            """{"schedules":$rows,"schedule_csv":"","schedule_csv_url":"/api/v1/study-plans/x.csv"}""" to null,
            """{"schedules":$rows,"schedule_csv":"","schedule_csv_file":null}""" to null,
            """{"schedules":$rows,"schedule_csv":"","schedule_csv_file":"  "}""" to "  ",
        )
        for ((body, identity) in cases) {
            server.enqueue(MockResponse().setBody(body))
            val success = generate() as PlanOutcome.Success
            assertEquals(1, success.response.schedules.size)
            assertEquals(identity, success.response.scheduleCsvFile)
        }
    }

    @Test
    fun baseUrlWithoutTrailingSlashJoinsOnce() {
        server.enqueue(MockResponse().setBody(successBody))
        generate(repository(base = server.url("/api/v1").toString()))
        assertEquals("/api/v1/study-plans", server.takeRequest().path)
    }

    @Test
    fun status422KeepsServerDetailAndShowsKoreanExplanation() {
        server.enqueue(MockResponse().setResponseCode(422)
            .setBody("""{"error":{"code":"INVALID_INPUT","message":"exam_date is invalid"}}"""))
        val outcome = generate()
        assertEquals(PlanOutcome.Failure(ApiError.InvalidInput("exam_date is invalid")), outcome)
        assertEquals(1, server.requestCount) // no legacy-payload retry
        val shown = (outcome as PlanOutcome.Failure).error.userMessage()
        assertEquals("시험일을 확인할 수 없어요.\n'입력 수정하기'에서 입력한 시험일을 확인해 주세요.", shown)
        assertFalse(shown.contains("exam_date"))
    }

    @Test
    fun status422CapacityDetailReachesThePresentationWithoutTheMarker() {
        val detail = "준비 시작부터 시험일까지 공부할 수 있는 시간이 1분이라 진단된 학습 주제 3개에 주제마다 최소 1분씩 배정할 수 없습니다. " +
            "공부 가능 시간을 늘리거나, 준비를 더 일찍 시작하거나, 시험일을 늦춰 주세요."
        server.enqueue(MockResponse().setResponseCode(422)
            .setBody("""{"error":{"code":"INVALID_INPUT","message":"NOT_ENOUGH_TIME: $detail"}}"""))
        val error = (generate() as PlanOutcome.Failure).error
        assertEquals(ApiError.InvalidInput("NOT_ENOUGH_TIME: $detail"), error)
        val shown = error.userMessage()
        assertTrue(shown.contains("1분이라 진단된 학습 주제 3개에"))
        assertFalse(shown.contains("NOT_ENOUGH_TIME"))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun statusMapping() {
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"error":{"code":"NOT_FOUND","message":"API route not found"}}"""))
        assertEquals(PlanOutcome.Failure(ApiError.Configuration), generate())
        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"error":{"code":"INTERNAL_ERROR","message":"x"}}"""))
        assertEquals(PlanOutcome.Failure(ApiError.ServerFailure(500)), generate())
        server.enqueue(MockResponse().setResponseCode(502).setBody("""{"error":{"code":"UPSTREAM_ERROR","message":"Agent did not generate a schedule"}}"""))
        assertEquals(PlanOutcome.Failure(ApiError.ServerFailure(502)), generate())
        server.enqueue(MockResponse().setResponseCode(503).setBody("not json"))
        assertEquals(PlanOutcome.Failure(ApiError.ServerUnavailable(null)), generate())
        assertEquals(4, server.requestCount)
    }

    /** Stored-plan codes belong to adjustment; generation keeps its generic categories. */
    @Test
    fun planCodesKeepGenericCategories() {
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"error":{"code":"PLAN_NOT_FOUND","message":"x"}}"""))
        assertEquals(PlanOutcome.Failure(ApiError.Configuration), generate())
        server.enqueue(MockResponse().setResponseCode(409).setBody("""{"error":{"code":"PLAN_CONFLICT","message":"x"}}"""))
        assertEquals(PlanOutcome.Failure(ApiError.ServerFailure(409)), generate())
        assertEquals(2, server.requestCount)
    }

    @Test
    fun malformedBodyIsInvalidResponse() {
        server.enqueue(MockResponse().setBody("{\"schedules\": [oops"))
        assertTrue((generate() as PlanOutcome.Failure).error is ApiError.InvalidResponse)
        server.enqueue(MockResponse().setBody("""{"schedules":[{"schedule_id":"study-1","start_date":"2026-10-05","end_date":"2026/10/05/19/00"}]}"""))
        assertTrue((generate() as PlanOutcome.Failure).error is ApiError.InvalidResponse)
    }

    @Test
    fun refusedConnectionIsConnectionError() {
        val base = server.url("/api/v1/").toString()
        server.shutdown()
        assertEquals(PlanOutcome.Failure(ApiError.Connection), generate(repository(base = base)))
    }

    @Test
    fun timeoutIsConnectionError() {
        server.enqueue(MockResponse().setBody(successBody).setHeadersDelay(2, TimeUnit.SECONDS))
        val shortClient = createApiHttpClient().newBuilder()
            .readTimeout(200, TimeUnit.MILLISECONDS).callTimeout(300, TimeUnit.MILLISECONDS).build()
        assertEquals(PlanOutcome.Failure(ApiError.Connection), generate(repository(client = shortClient)))
    }

    @Test
    fun configuredTimeoutsMatchDesign() {
        val client = createApiHttpClient()
        assertEquals(10_000, client.connectTimeoutMillis)
        assertEquals(120_000, client.readTimeoutMillis)
        assertEquals(120_000, client.callTimeoutMillis)
    }
}
