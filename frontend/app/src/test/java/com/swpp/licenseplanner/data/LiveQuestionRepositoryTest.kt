package com.swpp.licenseplanner.data

import com.swpp.licenseplanner.TestFixtures
import com.swpp.licenseplanner.domain.CERTIFICATION_ID
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/** FLOW-04 question GET transport and validation against a local mock server. */
class LiveQuestionRepositoryTest {
    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer().apply { start() } }
    @After fun tearDown() { server.shutdown() }

    private fun load(body: String? = null, code: Int = 200, base: String = server.url("/api/v1/").toString(),
                     repo: LiveQuestionRepository = LiveQuestionRepository(base)): QuestionOutcome {
        if (body != null) server.enqueue(MockResponse().setResponseCode(code).setBody(body))
        return runBlocking { repo.load(CERTIFICATION_ID) }
    }

    private fun assertInvalid(body: String) {
        val outcome = load(body)
        assertTrue("expected invalid for $body but was $outcome",
            outcome is QuestionOutcome.Failure && outcome.error is ApiError.InvalidResponse)
    }

    private fun bank(questions: String, cert: String = CERTIFICATION_ID) =
        """{"certification_id":"$cert","certification_name":"컴퓨터활용능력 2급","questions":[$questions]}"""

    private fun q(id: String = "1", prompt: String = "\"질문\"", choices: String = """{"A":"가","B":"나"}""",
                  correct: String = "\"B\"", score: String = "1") =
        """{"id":$id,"prompt":$prompt,"choices":$choices,"correct_answer":$correct,"possible_score":$score}"""

    @Test
    fun getsExactPathWithoutBodyAndParsesPinnedResponse() {
        val outcome = load(TestFixtures.publicResponseJson()) as QuestionOutcome.Loaded
        val recorded = server.takeRequest()
        assertEquals("GET", recorded.method)
        assertEquals("/api/v1/certifications/computer_specialist_level_2/questions", recorded.path)
        assertEquals(0L, recorded.bodySize)
        assertEquals(listOf(1, 2, 3, 4, 5), outcome.questions.map { it.id })
        assertEquals(TestFixtures.publicQuestions(), outcome.questions)
    }

    @Test
    fun validThreeQuestionSetKeepsOrderIdsAndScores() {
        val outcome = load(TestFixtures.threeQuestionJson()) as QuestionOutcome.Loaded
        assertEquals(listOf(2, 42, 4), outcome.questions.map { it.id })
        assertEquals(listOf(1.0, 2.0, 1.0), outcome.questions.map { it.possibleScore })
    }

    @Test
    fun unknownFieldsAndMatchingReservedUnknownAreTolerated() {
        val body = """{"certification_id":"$CERTIFICATION_ID","extra":{"x":1},"questions":[
            {"id":7,"prompt":"p","choices":{"A":"가","UNKNOWN":"모르겠음"},"correct_answer":"A","possible_score":1.5,"hint":"h"}]}"""
        val outcome = load(body) as QuestionOutcome.Loaded
        assertEquals(1.5, outcome.questions.single().possibleScore, 0.0)
    }

    @Test
    fun invalidDataIsRejected() {
        assertInvalid(bank(q(), cert = "other_certification"))
        assertInvalid(bank(""))
        assertInvalid(bank(q(id = "1") + "," + q(id = "1")))
        assertInvalid(bank(q(id = "0")))
        assertInvalid(bank(q(id = "-3")))
        assertInvalid(bank(q(id = "\"1\"")))
        assertInvalid(bank(q(id = "1.5")))
        assertInvalid(bank(q(prompt = "\"   \"")))
        assertInvalid(bank(q(choices = "{}")))
        assertInvalid(bank(q(choices = """{"A":1,"B":"나"}""")))
        assertInvalid(bank(q(choices = """{"A":"","B":"나"}""")))
        assertInvalid(bank(q(choices = "[\"가\",\"나\"]")))
        assertInvalid(bank(q(correct = "\"C\"")))
        assertInvalid(bank(q(correct = "null")))
        assertInvalid(bank(q(score = "0")))
        assertInvalid(bank(q(score = "-1")))
        assertInvalid(bank(q(choices = """{"A":"가","UNKNOWN":"잘 모름"}""", correct = "\"A\"")))
        assertInvalid(bank(q(choices = """{"A":"가","UNKNOWN":"모르겠음"}""", correct = "\"UNKNOWN\"")))
        assertInvalid("{not json")
        assertInvalid("""{"certification_id":"$CERTIFICATION_ID"}""")
    }

    @Test
    fun httpErrorsMapAndNeverFallBack() {
        assertEquals(QuestionOutcome.Failure(ApiError.Configuration), load("""{"error":{"code":"NOT_FOUND","message":"x"}}""", 404))
        assertEquals(QuestionOutcome.Failure(ApiError.ServerFailure(500)), load("""{"error":{"code":"INTERNAL_ERROR","message":"x"}}""", 500))
    }

    @Test
    fun refusedConnectionAndTimeoutAreConnectionErrors() {
        server.enqueue(MockResponse().setBody(TestFixtures.publicResponseJson()).setHeadersDelay(2, TimeUnit.SECONDS))
        val shortClient = createApiHttpClient().newBuilder()
            .readTimeout(200, TimeUnit.MILLISECONDS).callTimeout(300, TimeUnit.MILLISECONDS).build()
        val base = server.url("/api/v1/").toString()
        assertEquals(QuestionOutcome.Failure(ApiError.Connection), load(repo = LiveQuestionRepository(base, shortClient)))
        server.shutdown()
        assertEquals(QuestionOutcome.Failure(ApiError.Connection), load(base = base))
    }
}
