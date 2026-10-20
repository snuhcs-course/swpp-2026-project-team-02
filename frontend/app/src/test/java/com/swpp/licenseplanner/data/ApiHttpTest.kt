package com.swpp.licenseplanner.data

import com.swpp.licenseplanner.TestFixtures
import com.swpp.licenseplanner.data.model.ScheduleRow
import com.swpp.licenseplanner.data.model.StudyPlanRequest
import com.swpp.licenseplanner.domain.CERTIFICATION_ID
import com.swpp.licenseplanner.domain.DayAvailability
import com.swpp.licenseplanner.domain.Weekday
import com.swpp.licenseplanner.domain.defaultAvailability
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.Socket
import java.time.LocalTime
import java.util.concurrent.TimeUnit

/**
 * Successive requests on one shared client against a server that answers HTTP/1.0 with a
 * Content-Length and closes the socket after every response without a `Connection` header,
 * as Python's default `BaseHTTPRequestHandler` does.
 */
class ApiHttpTest {
    private lateinit var server: MockWebServer
    private val client = createApiHttpClient()
    private lateinit var base: String

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
        base = server.url("/api/v1/").toString()
    }

    @After fun tearDown() {
        server.shutdown()
        client.connectionPool.evictAll()
    }

    private fun closing(status: String, body: String) = MockResponse()
        .setStatus("HTTP/1.0 $status")
        .setHeader("Content-Type", "application/json; charset=utf-8")
        .setBody(body)
        .setSocketPolicy(SocketPolicy.DISCONNECT_AT_END)

    private fun takeRequest(): RecordedRequest =
        requireNotNull(server.takeRequest(5, TimeUnit.SECONDS)) { "request did not reach the server" }

    /** Each exchange arrived as the first request on its own connection and asked to close it. */
    private fun assertFreshConnection(recorded: RecordedRequest) {
        assertEquals(0, recorded.sequenceNumber)
        assertEquals("close", recorded.getHeader("Connection"))
    }

    private fun json(recorded: RecordedRequest): JsonObject = ApiJson.parseToJsonElement(recorded.body.readUtf8()).jsonObject

    private fun requestFor(availability: Map<Weekday, DayAvailability>): StudyPlanRequest =
        (RequestMapper.map(TestFixtures.completeState(availability = availability)) as MappingResult.Ready).request

    private val originalA = requestFor(defaultAvailability() + (Weekday.MONDAY to DayAvailability(true, LocalTime.of(18, 0), 120)))
    private val file = "license_planner/output/study_plan_20261016T180000000000+0900_abcdef12.csv"
    private val rows = listOf(
        ScheduleRow("study-1", "2026/10/05/18/00", "2026/10/05/19/00", "함수와 데이터 관리"),
        ScheduleRow("study-2", "2026/10/07/18/00", "2026/10/07/19/00", "차트와 분석"),
        ScheduleRow("external-1", "2026/10/08/18/00", "2026/10/08/19/00", ""),
    )
    private val rowsJson = """
        {"schedule_id":"study-1","start_date":"2026/10/05/18/00","end_date":"2026/10/05/19/00","topic":"함수와 데이터 관리"},
        {"schedule_id":"study-2","start_date":"2026/10/07/18/00","end_date":"2026/10/07/19/00","topic":"차트와 분석"},
        {"schedule_id":"external-1","start_date":"2026/10/08/18/00","end_date":"2026/10/08/19/00","topic":""}
    """.trimIndent()
    private val movedRowsJson = rowsJson.replace("2026/10/05/18/00\",\"end_date\":\"2026/10/05/19/00", "2026/10/12/18/00\",\"end_date\":\"2026/10/12/19/00")

    private fun adjusted(target: String = "study-1", rowsJson: String = movedRowsJson) =
        """{"schedules":[$rowsJson],"schedule_csv":"","action":"rescheduled_study","target_schedule_id":"$target",
           "schedule_csv_file":"$file","agent_summary":"옮겼어요"}"""

    @Test
    fun fixtureClosesEachResponseWithoutAnnouncingIt() {
        server.enqueue(closing("404 Not Found", """{"error":{"code":"NOT_FOUND","message":"API route not found"}}"""))
        val raw = Socket(server.hostName, server.port).use { socket ->
            socket.soTimeout = 5_000
            socket.getOutputStream().write("GET /api/v1/x HTTP/1.1\r\nHost: ${server.hostName}\r\n\r\n".toByteArray())
            socket.getInputStream().readBytes().decodeToString() // returns only once the server closes
        }
        val head = raw.substringBefore("\r\n\r\n").lines()
        assertEquals("HTTP/1.0 404 Not Found", head.first())
        assertTrue(head.any { it.startsWith("Content-Length:", ignoreCase = true) })
        assertFalse(head.any { it.startsWith("Connection:", ignoreCase = true) })
    }

    @Test
    fun explicitRouteErrorRetryReachesTheServerImmediately() {
        val repo = LiveAdjustmentRepository(base, client)
        val input = AdjustmentInput(originalA, file, rows, "study-1")
        val notFound = """{"error":{"code":"NOT_FOUND","message":"API route not found"}}"""
        repeat(3) { server.enqueue(closing("404 Not Found", notFound)) }

        val outcomes = List(3) { runBlocking { repo.adjust(input) } }

        assertEquals(List(3) { AdjustmentOutcome.Failure(ApiError.Configuration) }, outcomes)
        assertEquals(3, server.requestCount)
        val recorded = List(3) { takeRequest() }
        for (request in recorded) {
            assertEquals("POST", request.method)
            assertEquals("/api/v1/schedules/adjust", request.path)
            assertFreshConnection(request)
        }
        val bodies = recorded.map { json(it) }
        assertEquals(1, bodies.toSet().size)
    }

    @Test
    fun freshAdjustmentRightAfterReadbackIsDelivered() {
        val repo = LiveAdjustmentRepository(base, client)
        server.enqueue(closing("409 Conflict", """{"error":{"code":"PLAN_CONFLICT","message":"plan changed"}}"""))
        val refreshedJson = rowsJson.replace("2026/10/07/18/00\",\"end_date\":\"2026/10/07/19/00", "2026/10/22/18/00\",\"end_date\":\"2026/10/22/19/00")
        val otherPlan = """{"schedule_csv_file":"license_planner/output/other.csv","schedules":[$rowsJson]}"""
        server.enqueue(closing("200 OK", """{"plans":[$otherPlan,{"schedule_csv_file":"$file","schedules":[$refreshedJson]}]}"""))
        server.enqueue(closing("200 OK", adjusted(rowsJson = refreshedJson.replace("2026/10/05/18/00\",\"end_date\":\"2026/10/05/19/00", "2026/10/12/18/00\",\"end_date\":\"2026/10/12/19/00"))))

        assertEquals(AdjustmentOutcome.Failure(ApiError.PlanConflict), runBlocking { repo.adjust(AdjustmentInput(originalA, file, rows, "study-1")) })
        val refreshed = (runBlocking { repo.reload(file) } as ReloadOutcome.Loaded).schedules
        assertEquals("2026/10/22/18/00", refreshed.single { it.scheduleId == "study-2" }.startDate)
        val success = runBlocking { repo.adjust(AdjustmentInput(originalA, file, refreshed, "study-1")) } as AdjustmentOutcome.Success
        assertEquals("2026/10/12/18/00", success.response.schedules.first().startDate)

        assertEquals(3, server.requestCount)
        val recorded = List(3) { takeRequest() }
        assertEquals(listOf("POST", "GET", "POST"), recorded.map { it.method })
        recorded.forEach(::assertFreshConnection)
        val sent = json(recorded[2])
        val expected = ApiJson.parseToJsonElement("""
            {"schedule_csv_file":"$file","schedule_id":"study-1","schedules":[$refreshedJson],
             "weekly_availability":{"monday":[{"start":"18:00","end":"20:00"}]}}
        """).jsonObject
        assertEquals(expected, sent)
    }

    @Test
    fun adjustmentRightAfterQuestionsAndGenerationIsDelivered() {
        val generated = """{"schedules":[$rowsJson],"schedule_csv":"","schedule_csv_file":"$file","agent_summary":"요약"}"""
        server.enqueue(closing("200 OK", TestFixtures.publicResponseJson()))
        server.enqueue(closing("200 OK", generated))
        server.enqueue(closing("200 OK", adjusted()))
        server.enqueue(closing("200 OK", adjusted(target = "study-2", rowsJson = movedRowsJson.replace(
            "2026/10/07/18/00\",\"end_date\":\"2026/10/07/19/00", "2026/10/14/18/00\",\"end_date\":\"2026/10/14/19/00"))))

        val questions = runBlocking { LiveQuestionRepository(base, client).load(CERTIFICATION_ID) }
        assertTrue(questions is QuestionOutcome.Loaded)
        val plan = (runBlocking { LivePlanningRepository(base, client).generate(originalA) } as PlanOutcome.Success).response
        val adjustments = LiveAdjustmentRepository(base, client)
        val first = runBlocking { adjustments.adjust(AdjustmentInput(originalA, plan.scheduleCsvFile, plan.schedules, "study-1")) }
        val firstRows = (first as AdjustmentOutcome.Success).response.schedules
        val second = runBlocking { adjustments.adjust(AdjustmentInput(originalA, file, firstRows, "study-2")) }
        assertEquals("2026/10/14/18/00", (second as AdjustmentOutcome.Success).response.schedules[1].startDate)

        assertEquals(4, server.requestCount)
        val recorded = List(4) { takeRequest() }
        assertEquals(
            listOf(
                "GET /api/v1/certifications/$CERTIFICATION_ID/questions",
                "POST /api/v1/study-plans",
                "POST /api/v1/schedules/adjust",
                "POST /api/v1/schedules/adjust",
            ),
            recorded.map { "${it.method} ${it.path}" },
        )
        recorded.forEach(::assertFreshConnection)
        assertEquals(ApiJson.encodeToJsonElement(StudyPlanRequest.serializer(), originalA), json(recorded[1]))
        // The second adjustment carries the rows returned by the first.
        assertEquals(ApiJson.parseToJsonElement("[$movedRowsJson]"), json(recorded[3])["schedules"])
    }
}
