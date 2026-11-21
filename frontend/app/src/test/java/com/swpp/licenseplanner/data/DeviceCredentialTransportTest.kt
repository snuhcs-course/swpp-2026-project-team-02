package com.swpp.licenseplanner.data

import androidx.core.util.AtomicFile
import com.swpp.licenseplanner.TestFixtures
import com.swpp.licenseplanner.data.model.ScheduleRow
import com.swpp.licenseplanner.data.model.StudyPlanRequest
import com.swpp.licenseplanner.domain.CERTIFICATION_ID
import com.swpp.licenseplanner.domain.RemainingStudy
import com.swpp.licenseplanner.domain.RemainingStudyFixtures
import com.swpp.licenseplanner.ui.developerMessage
import com.swpp.licenseplanner.ui.requiresReload
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit

/** The shared live client's credential delivery against local mock servers; not backend verification. */
class DeviceCredentialTransportTest {
    @get:Rule val folder = TemporaryFolder()
    private lateinit var server: MockWebServer
    private lateinit var other: MockWebServer
    private lateinit var base: String

    private val generation: StudyPlanRequest =
        (RequestMapper.map(TestFixtures.completeState()) as MappingResult.Ready).request
    private val file = "license_planner/output/study_plan_20261016T180000000000+0900_abcdef12.csv"
    private val rows = listOf(
        ScheduleRow("study-1", "2026/10/05/18/00", "2026/10/05/19/00", "함수와 데이터 관리"),
        ScheduleRow("study-2", "2026/10/07/18/00", "2026/10/07/19/00", "엑셀 기초"),
    )
    private val automatic = AdjustmentInput(generation, file, rows, "study-1")
    private val manual = automatic.copy(manualMove = ManualMove(LocalDateTime.of(2026, 10, 13, 14, 0)))
    private val remainingSource = RemainingStudyFixtures.plan(false)
    private val remaining = RemainingStudyInput(remainingSource, RemainingStudy.calculate(remainingSource),
        RemainingStudyFixtures.now.plusMinutes(1))

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
        other = MockWebServer().apply { start() }
        base = server.url("/api/v1").toString()
    }

    @After fun tearDown() {
        server.shutdown()
        other.shutdown()
    }

    private fun storeIn(dir: File) =
        DeviceTokenStore(AtomicFilePlanStore.AtomicFileBytes(AtomicFile(File(dir, DeviceTokenStore.FILE_NAME))))

    private fun client(tokens: DeviceTokenSource): OkHttpClient = createApiHttpClient().withDeviceCredential(base, tokens)

    private fun failing() = MockResponse().setResponseCode(500).setBody("""{"error":{"code":"INTERNAL","message":"x"}}""")

    private fun take(on: MockWebServer = server): RecordedRequest =
        requireNotNull(on.takeRequest(5, TimeUnit.SECONDS)) { "request did not reach the server" }

    private fun assertCarries(recorded: RecordedRequest, token: String) {
        assertEquals("Bearer $token", recorded.getHeader("Authorization"))
        assertEquals(1, recorded.headers.values("Authorization").size)
        assertFalse(recorded.requestUrl.toString().contains(token))
        assertFalse(recorded.body.readUtf8().contains(token))
    }

    /** Every live request kind, each on its own repository built from [client]. */
    private fun sendEveryKind(client: OkHttpClient): List<ApiError> = runBlocking {
        listOf(
            (LiveQuestionRepository(base, client).load(CERTIFICATION_ID) as QuestionOutcome.Failure).error,
            (LivePlanningRepository(base, client).generate(generation) as PlanOutcome.Failure).error,
            (LiveAdjustmentRepository(base, client).adjust(automatic) as AdjustmentOutcome.Failure).error,
            (LiveAdjustmentRepository(base, client).adjust(manual) as AdjustmentOutcome.Failure).error,
            (LiveRemainingStudyRepository(base, client).replan(remaining) as AdjustmentOutcome.Failure).error,
            (LiveAdjustmentRepository(base, client).reload(file) as ReloadOutcome.Failure).error,
        )
    }

    @Test fun everyRequestKindSendsTheOneSavedTokenOnlyAsABearerHeader() {
        val store = storeIn(folder.root)
        repeat(6) { server.enqueue(failing()) }

        val errors = sendEveryKind(client(store))

        val token = store.token()
        val recorded = List(6) { take() }
        assertEquals(
            listOf("GET", "POST", "POST", "POST", "POST", "GET"),
            recorded.map { it.method },
        )
        assertEquals("/api/v1/certifications/$CERTIFICATION_ID/questions", recorded[0].path)
        assertTrue(recorded[5].path!!.startsWith("/api/v1/study-plans?schedule_csv_file="))
        recorded.forEach { assertCarries(it, token) }
        errors.forEach { assertFalse(it.developerMessage().contains(token)) }
    }

    @Test fun aReopenedStoreSendsTheSameTokenAfterRestart() {
        val first = storeIn(folder.root)
        server.enqueue(failing())
        runBlocking { LiveQuestionRepository(base, client(first)).load(CERTIFICATION_ID) }
        val token = first.token()
        assertCarries(take(), token)

        server.enqueue(MockResponse().setBody("""{"plans":[]}"""))
        val outcome = runBlocking { LiveAdjustmentRepository(base, client(storeIn(folder.root))).reload(file) }

        assertEquals(ReloadOutcome.Missing, outcome)
        assertCarries(take(), token)
    }

    @Test fun anUnavailableTokenSendsNothingFromAnyRequestKind() {
        val tokens = DeviceTokenSource { throw DeviceTokenUnavailableException() }

        val errors = sendEveryKind(client(tokens))

        assertEquals(List(6) { ApiError.DeviceTokenUnavailable }, errors)
        assertEquals(0, server.requestCount)
        assertFalse(ApiError.DeviceTokenUnavailable.requiresReload())
    }

    @Test fun storageFailuresAndMalformedValuesNeverGoOutAsCredentials() {
        val sources = listOf(
            DeviceTokenStore(MemoryPlanBytes().apply { failWrites = true }),
            DeviceTokenStore(MemoryPlanBytes().apply { failReads = true }),
            DeviceTokenSource { error("storage exploded") },
            DeviceTokenSource { "" },
            DeviceTokenSource { "A".repeat(64) },
        )
        for (tokens in sources) {
            val outcome = runBlocking { LiveQuestionRepository(base, client(tokens)).load(CERTIFICATION_ID) }
            assertEquals(QuestionOutcome.Failure(ApiError.DeviceTokenUnavailable), outcome)
        }
        assertEquals(0, server.requestCount)
    }

    @Test fun aSameOriginRedirectKeepsTheToken() {
        val store = storeIn(folder.root)
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/api/v1/moved/questions"))
        server.enqueue(MockResponse().setBody(TestFixtures.publicResponseJson()))

        val outcome = runBlocking { LiveQuestionRepository(base, client(store)).load(CERTIFICATION_ID) }

        assertTrue(outcome is QuestionOutcome.Loaded)
        val token = store.token()
        assertCarries(take(), token)
        val followed = take()
        assertEquals("/api/v1/moved/questions", followed.path)
        assertCarries(followed, token)
    }

    @Test fun aRedirectToAnotherPortSendsNeitherTokenNorBody() {
        val store = storeIn(folder.root)
        other.enqueue(MockResponse().setBody("{}"))
        server.enqueue(MockResponse().setResponseCode(307).setHeader("Location", other.url("/api/v1/study-plans")))
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", other.url("/api/v1/questions")))

        val generated = runBlocking { LivePlanningRepository(base, client(store)).generate(generation) }
        val questions = runBlocking { LiveQuestionRepository(base, client(store)).load(CERTIFICATION_ID) }

        val token = store.token()
        assertCarries(take(), token)
        assertCarries(take(), token)
        assertEquals(0, other.requestCount)
        for (error in listOf((generated as PlanOutcome.Failure).error, (questions as QuestionOutcome.Failure).error)) {
            assertTrue(error is ApiError.UnknownFailure)
            assertNull((error as ApiError.UnknownFailure).status)
            assertFalse(error.developerMessage().contains(token))
            assertFalse(error.developerMessage().contains(other.port.toString()))
        }
    }

    @Test fun aRedirectToAnotherHostIsNotFollowed() {
        val store = storeIn(folder.root)
        val otherHost = if (server.hostName == "127.0.0.1") "localhost" else "127.0.0.1"
        server.enqueue(
            MockResponse().setResponseCode(302).setHeader("Location", "http://$otherHost:${server.port}/api/v1/questions"),
        )
        server.enqueue(MockResponse().setBody(TestFixtures.publicResponseJson()))

        val outcome = runBlocking { LiveQuestionRepository(base, client(store)).load(CERTIFICATION_ID) }

        assertTrue(outcome is QuestionOutcome.Failure)
        assertCarries(take(), store.token())
        assertEquals(1, server.requestCount)
    }
}
