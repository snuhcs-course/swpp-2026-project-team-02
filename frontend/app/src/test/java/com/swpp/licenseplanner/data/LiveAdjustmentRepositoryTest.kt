package com.swpp.licenseplanner.data

import com.swpp.licenseplanner.TestFixtures
import com.swpp.licenseplanner.data.model.ScheduleRow
import com.swpp.licenseplanner.data.model.StudyPlanRequest
import com.swpp.licenseplanner.domain.DayAvailability
import com.swpp.licenseplanner.domain.Weekday
import com.swpp.licenseplanner.domain.defaultAvailability
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalTime
import java.util.concurrent.TimeUnit

/** Transport against a local mock server; not backend verification. */
class LiveAdjustmentRepositoryTest {
    private lateinit var server: MockWebServer

    private fun requestFor(availability: Map<Weekday, DayAvailability>): StudyPlanRequest =
        (RequestMapper.map(TestFixtures.completeState(availability = availability)) as MappingResult.Ready).request

    /** Plan A was created with Monday windows; the form later moved to B's Wednesday windows. */
    private val originalA = requestFor(defaultAvailability() + (Weekday.MONDAY to DayAvailability(true, LocalTime.of(18, 0), 120)))
    private val formB = requestFor(defaultAvailability() + (Weekday.WEDNESDAY to DayAvailability(true, LocalTime.of(9, 0), 60)))

    private val file = "license_planner/output/study_plan_20261016T180000000000+0900_abcdef12.csv"
    private val rows = listOf(
        ScheduleRow("study-1", "2026/10/05/18/00", "2026/10/05/19/00", "함수와 데이터 관리"),
        ScheduleRow("study-2", "2026/10/07/18/00", "2026/10/07/19/00", "새 서버 토픽, \"따옴표\""),
        ScheduleRow("external-1", "2026/10/08/18/00", "2026/10/08/19/00", ""),
    )
    private val input = AdjustmentInput(originalA, file, rows, "study-1")

    @Before fun setUp() { server = MockWebServer().apply { start() } }
    @After fun tearDown() { server.shutdown() }

    private fun repository(client: OkHttpClient = createApiHttpClient(), base: String = server.url("/api/v1/").toString()) =
        LiveAdjustmentRepository(base, client)

    private fun adjust(repo: LiveAdjustmentRepository = repository(), with: AdjustmentInput = input) =
        runBlocking { repo.adjust(with) }

    private fun body(
        rowsJson: String = """
            {"schedule_id":"study-1","start_date":"2026/10/12/18/00","end_date":"2026/10/12/19/00","topic":"함수와 데이터 관리"},
            {"schedule_id":"study-2","start_date":"2026/10/07/18/00","end_date":"2026/10/07/19/00","topic":"새 서버 토픽, \"따옴표\""},
            {"schedule_id":"external-1","start_date":"2026/10/08/18/00","end_date":"2026/10/08/19/00","topic":""}
        """.trimIndent(),
        action: String = "rescheduled_study",
        target: String = "study-1",
        extra: String = "",
    ) = """{"schedules":[$rowsJson],"schedule_csv":"schedule_id,start_date,end_date,topic\n","action":"$action",
           "target_schedule_id":"$target","agent_summary":"study-1을 옮겼어요","tool_calls":[{"name":"t"}],"debug":1$extra}"""

    @Test
    fun postsFourFieldBodyWithOriginalAvailability() {
        server.enqueue(MockResponse().setBody(body()))
        adjust()
        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/api/v1/schedules/adjust", recorded.path)
        assertEquals("application/json; charset=utf-8", recorded.getHeader("Content-Type"))
        val sent = ApiJson.parseToJsonElement(recorded.body.readUtf8()).jsonObject
        val expected = ApiJson.parseToJsonElement("""
            {"schedule_csv_file":"$file","schedule_id":"study-1",
             "schedules":[
              {"schedule_id":"study-1","start_date":"2026/10/05/18/00","end_date":"2026/10/05/19/00","topic":"함수와 데이터 관리"},
              {"schedule_id":"study-2","start_date":"2026/10/07/18/00","end_date":"2026/10/07/19/00","topic":"새 서버 토픽, \"따옴표\""},
              {"schedule_id":"external-1","start_date":"2026/10/08/18/00","end_date":"2026/10/08/19/00","topic":""}],
             "weekly_availability":{"monday":[{"start":"18:00","end":"20:00"}]}}
        """).jsonObject
        assertEquals(expected, sent)
        // A's windows, not the edited form's.
        assertTrue(formB.weeklyAvailability != originalA.weeklyAvailability)
        assertEquals(setOf("schedule_csv_file", "schedule_id", "schedules", "weekly_availability"), sent.keys)
    }

    @Test
    fun baseUrlWithoutTrailingSlashJoinsOnce() {
        server.enqueue(MockResponse().setBody(body()))
        adjust(repository(base = server.url("/api/v1").toString()))
        assertEquals("/api/v1/schedules/adjust", server.takeRequest().path)
    }

    @Test
    fun successKeepsRequestedIdentityWhenResponseOmitsIt() {
        server.enqueue(MockResponse().setBody(body()))
        val success = adjust() as AdjustmentOutcome.Success
        assertEquals(file, success.response.scheduleCsvFile)
        assertEquals("2026/10/12/18/00", success.response.schedules.first().startDate)
        assertEquals(rows[1], success.response.schedules[1]) // unknown topic unchanged
        assertEquals("study-1을 옮겼어요", success.response.agentSummary)
    }

    @Test
    fun successAcceptsMatchingIdentity() {
        server.enqueue(MockResponse().setBody(body(extra = ""","schedule_csv_file":"$file"""")))
        assertEquals(file, (adjust() as AdjustmentOutcome.Success).response.scheduleCsvFile)
    }

    @Test
    fun invalidResponsesAreRejected() {
        val study2 = """{"schedule_id":"study-2","start_date":"2026/10/07/18/00","end_date":"2026/10/07/19/00","topic":"x"}"""
        val cases = mapOf(
            "malformed" to "{\"schedules\": [oops",
            "missing action" to """{"schedules":[$study2],"schedule_csv":"","target_schedule_id":"study-1"}""",
            "cancelled_external" to body(action = "cancelled_external"),
            "unknown action" to body(action = "moved_somewhere"),
            "other target" to body(target = "study-2"),
            "other file" to body(extra = ""","schedule_csv_file":"license_planner/output/other.csv""""),
            "blank file" to body(extra = ""","schedule_csv_file":"""""),
            "missing target row" to body(rowsJson = study2),
            "duplicate id" to body(rowsJson = """
                {"schedule_id":"study-1","start_date":"2026/10/12/18/00","end_date":"2026/10/12/19/00","topic":"a"},
                {"schedule_id":"study-1","start_date":"2026/10/13/18/00","end_date":"2026/10/13/19/00","topic":"a"}"""),
            "blank id" to body(rowsJson = """
                {"schedule_id":"study-1","start_date":"2026/10/12/18/00","end_date":"2026/10/12/19/00","topic":"a"},
                {"schedule_id":"","start_date":"2026/10/13/18/00","end_date":"2026/10/13/19/00","topic":"a"}"""),
            "invalid date" to body(rowsJson = """
                {"schedule_id":"study-1","start_date":"2026-10-12 18:00","end_date":"2026/10/12/19/00","topic":"a"}"""),
            "end before start" to body(rowsJson = """
                {"schedule_id":"study-1","start_date":"2026/10/12/19/00","end_date":"2026/10/12/19/00","topic":"a"}"""),
        )
        for ((name, response) in cases) {
            server.enqueue(MockResponse().setBody(response))
            val outcome = adjust()
            assertTrue("$name: $outcome", (outcome as? AdjustmentOutcome.Failure)?.error is ApiError.InvalidResponse)
        }
        assertEquals(cases.size, server.requestCount)
    }

    @Test
    fun statusMappingWithoutRetry() {
        server.enqueue(MockResponse().setResponseCode(422)
            .setBody("""{"error":{"code":"INVALID_INPUT","message":"schedule_id must name a study row"}}"""))
        assertEquals(AdjustmentOutcome.Failure(ApiError.InvalidInput("schedule_id must name a study row")), adjust())
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"error":{"code":"NOT_FOUND","message":"API route not found"}}"""))
        assertEquals(AdjustmentOutcome.Failure(ApiError.Configuration), adjust())
        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"error":{"code":"INTERNAL_ERROR","message":"x"}}"""))
        assertEquals(AdjustmentOutcome.Failure(ApiError.ServerFailure(500)), adjust())
        server.enqueue(MockResponse().setResponseCode(502).setBody("""{"error":{"code":"UPSTREAM_ERROR","message":"Agent did not apply a schedule adjustment"}}"""))
        assertEquals(AdjustmentOutcome.Failure(ApiError.ServerFailure(502)), adjust())
        server.enqueue(MockResponse().setResponseCode(503).setBody("not json"))
        assertEquals(AdjustmentOutcome.Failure(ApiError.ServerUnavailable(null)), adjust())
        assertEquals(5, server.requestCount)
    }

    /**
     * One call sends once and reports [first]'s failure even with another response queued; only an
     * explicit retry sends the unchanged snapshot again.
     */
    private fun assertNotReplayed(first: MockResponse, expected: ApiError) {
        server.enqueue(first)
        server.enqueue(MockResponse().setResponseCode(422).setBody("""{"error":{"code":"INVALID_INPUT","message":"second"}}"""))
        assertEquals(AdjustmentOutcome.Failure(expected), adjust())
        assertEquals(1, server.requestCount)
        assertEquals(AdjustmentOutcome.Failure(ApiError.InvalidInput("second")), adjust())
        assertEquals(2, server.requestCount)
        assertEquals(server.takeRequest().body.readUtf8(), server.takeRequest().body.readUtf8())
    }

    @Test
    fun requestTimeoutIsNotReplayed() = assertNotReplayed(
        MockResponse().setResponseCode(408).setHeader("Retry-After", "0"),
        ApiError.ServerFailure(408),
    )

    @Test
    fun unavailableWithZeroRetryAfterIsNotReplayed() = assertNotReplayed(
        MockResponse().setResponseCode(503).setHeader("Retry-After", "0")
            .setBody("""{"error":{"code":"SERVICE_UNAVAILABLE","message":"busy"}}"""),
        ApiError.ServerUnavailable("busy"),
    )

    @Test
    fun disconnectAfterSendingIsNotReplayed() = assertNotReplayed(
        MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST),
        ApiError.Connection,
    )

    private fun failureFor(status: Int, body: String): ApiError {
        server.enqueue(MockResponse().setResponseCode(status).setBody(body))
        return (adjust() as AdjustmentOutcome.Failure).error
    }

    @Test
    fun storedPlanCodesMapOnlyForTheirExactStatus() {
        assertEquals(ApiError.PlanNotFound, failureFor(404, """{"error":{"code":"PLAN_NOT_FOUND","message":"$file not found"}}"""))
        assertEquals(ApiError.PlanConflict, failureFor(409, """{"error":{"code":"PLAN_CONFLICT","message":"plan changed"}}"""))
        // Unknown fields around a recognized code are tolerated.
        assertEquals(ApiError.PlanConflict, failureFor(409, """{"error":{"code":"PLAN_CONFLICT","detail":1},"trace":"x"}"""))
        // A code with the wrong status keeps that status's generic category.
        assertEquals(ApiError.ServerFailure(409), failureFor(409, """{"error":{"code":"PLAN_NOT_FOUND","message":"x"}}"""))
        assertEquals(ApiError.Configuration, failureFor(404, """{"error":{"code":"PLAN_CONFLICT","message":"x"}}"""))
        assertEquals(ApiError.InvalidInput("x"), failureFor(422, """{"error":{"code":"PLAN_CONFLICT","message":"x"}}"""))
        assertEquals(ApiError.ServerFailure(500), failureFor(500, """{"error":{"code":"PLAN_NOT_FOUND","message":"x"}}"""))
        assertEquals(7, server.requestCount)
    }

    @Test
    fun otherNotFoundBodiesAreRouteErrors() {
        val bodies = listOf(
            """{"error":{"code":"NOT_FOUND","message":"API route not found"}}""",
            """{"error":{"code":"plan_not_found","message":"x"}}""",
            """{"error":{"code":"","message":"x"}}""",
            """{"error":{"message":"plan file not found"}}""",
            """{"error":null}""",
            """{}""",
            "<html>Not Found</html>",
            "",
        )
        for (body in bodies) assertEquals(body, ApiError.Configuration, failureFor(404, body))
        assertEquals(bodies.size, server.requestCount)
    }

    @Test
    fun otherConflictBodiesStayGenericConflicts() {
        val bodies = listOf(
            """{"error":{"code":"CONFLICT","message":"plan file changed"}}""",
            """{"error":{"code":" PLAN_CONFLICT","message":"x"}}""",
            """{"error":{"message":"x"}}""",
            """{"error":"PLAN_CONFLICT"}""",
            "not json",
            "",
        )
        for (body in bodies) assertEquals(body, ApiError.ServerFailure(409), failureFor(409, body))
        assertEquals(bodies.size, server.requestCount)
    }

    /** Each recovery status reports its failure from a single POST, even with another response queued. */
    @Test
    fun recoveryStatusesAreNotReplayed() {
        for ((response, expected) in listOf(
            MockResponse().setResponseCode(404).setBody("""{"error":{"code":"PLAN_NOT_FOUND","message":"x"}}""") to ApiError.PlanNotFound,
            MockResponse().setResponseCode(409).setBody("""{"error":{"code":"PLAN_CONFLICT","message":"x"}}""") to ApiError.PlanConflict,
            MockResponse().setResponseCode(409).setHeader("Retry-After", "0") to ApiError.ServerFailure(409),
        )) {
            server.enqueue(response)
            server.enqueue(MockResponse().setBody(body()))
            val before = server.requestCount
            assertEquals(AdjustmentOutcome.Failure(expected), adjust())
            assertEquals(before + 1, server.requestCount)
            assertEquals("POST", server.takeRequest().method)
            // Drain the unused success so the next case starts clean.
            assertTrue(adjust() is AdjustmentOutcome.Success)
            server.takeRequest()
        }
    }

    @Test
    fun missingIdentitySendsNothing() {
        for (missing in listOf(null, "", "   ")) {
            val outcome = adjust(with = input.copy(scheduleCsvFile = missing))
            assertTrue((outcome as AdjustmentOutcome.Failure).error is ApiError.InvalidInput)
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun refusedConnectionIsConnectionError() {
        val base = server.url("/api/v1/").toString()
        server.shutdown()
        assertEquals(AdjustmentOutcome.Failure(ApiError.Connection), adjust(repository(base = base)))
    }

    @Test
    fun timeoutIsConnectionError() {
        server.enqueue(MockResponse().setBody(body()).setHeadersDelay(2, TimeUnit.SECONDS))
        val shortClient = createApiHttpClient().newBuilder()
            .readTimeout(200, TimeUnit.MILLISECONDS).callTimeout(300, TimeUnit.MILLISECONDS).build()
        assertEquals(AdjustmentOutcome.Failure(ApiError.Connection), adjust(repository(client = shortClient)))
        assertEquals(1, server.requestCount)
    }

    // --- Reading one plan back ---

    private val fileB = "license_planner/output/study_plan_20261016T190000000000+0900_bbbbbbbb.csv"
    private val rowsJsonA = """
        {"schedule_id":"study-1","start_date":"2026/10/12/18/00","end_date":"2026/10/12/19/00","topic":"함수와 데이터 관리","extra":1},
        {"schedule_id":"study-2","start_date":"2026/10/07/18/00","end_date":"2026/10/07/19/00","topic":"\uc0c8 \"따옴표\", 토픽"},
        {"schedule_id":"external-1","start_date":"2026/10/08/18/00","end_date":"2026/10/08/19/00","topic":""}
    """.trimIndent()
    private val rowsJsonB = """
        {"schedule_id":"study-1","start_date":"2026/10/20/18/00","end_date":"2026/10/20/19/00","topic":"B"}
    """.trimIndent()

    private fun item(file: String, rowsJson: String) =
        """{"schedule_csv_file":"$file","schedule_csv_url":"/api/v1/study-plans/x.csv","schedules":[$rowsJson],"future":{}}"""

    private fun reload(listJson: String?, identity: String? = file, repo: LiveAdjustmentRepository = repository()): ReloadOutcome {
        if (listJson != null) server.enqueue(MockResponse().setBody(listJson))
        return runBlocking { repo.reload(identity) }
    }

    @Test
    fun reloadGetsThePlanListWithoutBody() {
        reload("""{"plans":[${item(file, rowsJsonA)}]}""")
        val recorded = server.takeRequest()
        assertEquals("GET", recorded.method)
        assertEquals("/api/v1/study-plans", recorded.path)
        assertEquals(0L, recorded.bodySize)
        reload("""{"plans":[]}""", repo = repository(base = server.url("/api/v1").toString()))
        assertEquals("/api/v1/study-plans", server.takeRequest().path)
    }

    @Test
    fun reloadKeepsOnlyTheExactIdentityAmongSeveralPlans() {
        val similar = file.removePrefix("license_planner/output/")
        val list = """{"plans":[${item(fileB, rowsJsonB)},${item(similar, rowsJsonB)},${item("$file.bak", rowsJsonB)},
            ${item(file, rowsJsonA)},${item("license_planner/output/other.csv", "")}],"count":5}"""
        val loaded = reload(list) as ReloadOutcome.Loaded
        assertEquals(
            listOf(
                ScheduleRow("study-1", "2026/10/12/18/00", "2026/10/12/19/00", "함수와 데이터 관리"),
                ScheduleRow("study-2", "2026/10/07/18/00", "2026/10/07/19/00", "새 \"따옴표\", 토픽"),
                ScheduleRow("external-1", "2026/10/08/18/00", "2026/10/08/19/00", ""),
            ),
            loaded.schedules,
        )
        assertEquals(1, server.requestCount)
    }

    @Test
    fun reloadAcceptsEmptyAndExternalOnlyPlans() {
        assertEquals(ReloadOutcome.Loaded(emptyList()), reload("""{"plans":[${item(file, "")}]}"""))
        val external = """{"schedule_id":"external-1","start_date":"2026/10/08/18/00","end_date":"2026/10/08/19/00","topic":"회의"}"""
        assertEquals(
            ReloadOutcome.Loaded(listOf(ScheduleRow("external-1", "2026/10/08/18/00", "2026/10/08/19/00", "회의"))),
            reload("""{"plans":[${item(file, external)}]}"""),
        )
    }

    @Test
    fun reloadWithoutTheExactIdentityIsMissing() {
        assertEquals(ReloadOutcome.Missing, reload("""{"plans":[]}"""))
        assertEquals(ReloadOutcome.Missing, reload("""{"plans":[${item(fileB, rowsJsonA)},${item(file.uppercase(), rowsJsonA)}]}"""))
        assertEquals(ReloadOutcome.Missing, reload("""{"plans":[${item("$file ", rowsJsonA)}]}"""))
    }

    /** Only the matched plan's rows are checked; an unrelated stored plan cannot block the reload. */
    @Test
    fun reloadIgnoresProblemsInOtherPlansRows() {
        val bad = """{"schedule_id":"study-1","start_date":"bad","end_date":"bad","topic":""}"""
        val list = """{"plans":[${item(fileB, "$bad,$bad")},${item(file, rowsJsonA)}]}"""
        assertTrue(reload(list) is ReloadOutcome.Loaded)
    }

    @Test
    fun invalidReadbackIsAFailure() {
        val row1 = """{"schedule_id":"study-1","start_date":"2026/10/12/18/00","end_date":"2026/10/12/19/00","topic":"a"}"""
        val cases = mapOf(
            "malformed" to "{\"plans\": [oops",
            "not an object" to "[]",
            "missing plans" to """{"items":[]}""",
            "null plans" to """{"plans":null}""",
            "missing schedules" to """{"plans":[{"schedule_csv_file":"$file"}]}""",
            "null schedules" to """{"plans":[{"schedule_csv_file":"$file","schedules":null}]}""",
            "missing identity" to """{"plans":[{"schedules":[]}]}""",
            "duplicate match" to """{"plans":[${item(file, rowsJsonA)},${item(file, rowsJsonA)}]}""",
            "blank id" to """{"plans":[${item(file, row1.replace("study-1", ""))}]}""",
            "duplicate id" to """{"plans":[${item(file, "$row1,$row1")}]}""",
            "invalid date" to """{"plans":[${item(file, row1.replace("2026/10/12/18/00", "2026-10-12 18:00"))}]}""",
            "end not after start" to """{"plans":[${item(file, row1.replace("2026/10/12/19/00", "2026/10/12/18/00"))}]}""",
        )
        for ((name, body) in cases) {
            val outcome = reload(body)
            assertTrue("$name: $outcome", (outcome as? ReloadOutcome.Failure)?.error is ApiError.InvalidResponse)
        }
        assertEquals(cases.size, server.requestCount)
    }

    /** HTTP and transport failures never read as an absent plan; a route 404 stays a route error. */
    @Test
    fun reloadHttpFailuresAreNotMissing() {
        val cases = listOf(
            MockResponse().setResponseCode(404).setBody("""{"error":{"code":"NOT_FOUND","message":"API route not found"}}""") to ApiError.Configuration,
            MockResponse().setResponseCode(404).setBody("""{"error":{"code":"PLAN_NOT_FOUND","message":"x"}}""") to ApiError.Configuration,
            MockResponse().setResponseCode(500).setBody("""{"error":{"code":"STORAGE_ERROR","message":"bad stored plan"}}""") to ApiError.ServerFailure(500),
            MockResponse().setResponseCode(503).setBody("not json") to ApiError.ServerUnavailable(null),
        )
        for ((response, expected) in cases) {
            server.enqueue(response)
            assertEquals(ReloadOutcome.Failure(expected), reload(null))
        }
        assertEquals(cases.size, server.requestCount)
    }

    /** OkHttp may recover a dropped GET at transport level; either way it is a connection error. */
    @Test
    fun reloadDisconnectIsConnectionError() {
        repeat(2) { server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)) }
        assertEquals(ReloadOutcome.Failure(ApiError.Connection), reload(null))
        assertTrue(server.requestCount in 1..2)
        assertEquals("GET", server.takeRequest().method)
    }

    @Test
    fun reloadRefusedAndTimedOutAreConnectionErrors() {
        server.enqueue(MockResponse().setBody("""{"plans":[]}""").setHeadersDelay(2, TimeUnit.SECONDS))
        val shortClient = createApiHttpClient().newBuilder()
            .readTimeout(200, TimeUnit.MILLISECONDS).callTimeout(300, TimeUnit.MILLISECONDS).build()
        assertEquals(ReloadOutcome.Failure(ApiError.Connection), reload(null, repo = repository(client = shortClient)))
        val base = server.url("/api/v1/").toString()
        server.shutdown()
        assertEquals(ReloadOutcome.Failure(ApiError.Connection), reload(null, repo = repository(base = base)))
    }

    @Test
    fun reloadWithoutIdentitySendsNothing() {
        for (missing in listOf(null, "", "   ")) {
            val outcome = reload(null, identity = missing)
            assertTrue((outcome as ReloadOutcome.Failure).error is ApiError.InvalidInput)
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun reloadNeverPosts() {
        reload("""{"plans":[${item(file, rowsJsonA)}]}""")
        reload("""{"plans":[]}""")
        server.enqueue(MockResponse().setResponseCode(409).setBody("""{"error":{"code":"PLAN_CONFLICT","message":"x"}}"""))
        reload(null)
        assertEquals(listOf("GET", "GET", "GET"), List(server.requestCount) { server.takeRequest().method })
    }
}
