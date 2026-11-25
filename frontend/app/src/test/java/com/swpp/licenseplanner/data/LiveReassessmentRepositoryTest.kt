package com.swpp.licenseplanner.data

import com.swpp.licenseplanner.data.model.*
import com.swpp.licenseplanner.domain.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.time.LocalDateTime

internal object ReassessmentFixtures {
    val replanFrom: LocalDateTime = LocalDateTime.of(2026, 11, 17, 12, 0)
    val rows = listOf(
        ScheduleRow("study-1", "2026/11/16/18/00", "2026/11/16/19/00", LocalTopics.SPREADSHEET_BASICS),
        ScheduleRow("study-2", "2026/11/17/18/00", "2026/11/17/19/00", LocalTopics.MACROS),
        ScheduleRow("study-3", "2026/11/18/18/00", "2026/11/18/19/30", LocalTopics.SPREADSHEET_BASICS),
        ScheduleRow("study-4", "2026/11/19/18/00", "2026/11/19/20/00", LocalTopics.SPREADSHEET_BASICS),
    )
    val questions = listOf(
        item(19, LocalTopics.SPREADSHEET_BASICS), item(20, LocalTopics.SPREADSHEET_BASICS),
        item(21, LocalTopics.CHARTS_AND_ANALYSIS), item(22, LocalTopics.MACROS),
    )
    val answers = mapOf(19 to "A", 20 to "A", 21 to UNKNOWN_KEY, 22 to "B")

    fun item(id: Int, topic: String) = ReassessmentQuestion(
        Question(id, "예시 문항 $id", linkedMapOf("A" to "정답", "B" to "오답"), "A", 1.0), topic, "A가 정답인 이유 $id")

    fun plan(demo: Boolean): SessionPlan = SessionPlan.register(1, RemainingStudyFixtures.original,
        StudyPlanResponse(rows, scheduleCsvFile = if (demo) null else "plans/a.csv"), demo).copy(
        checkIns = mapOf("study-2" to StudyCheckIn(StudyCheckInStatus.Partly, 20), "study-7" to StudyCheckIn(StudyCheckInStatus.Done, 30)))

    fun input(demo: Boolean, questions: List<ReassessmentQuestion> = this.questions) =
        ReassessmentInput(plan(demo), questions, answers, replanFrom)

    fun revised(input: ReassessmentInput): List<ScheduleRow> = (Reassessment.resplit(input.source.current.schedules, input.keep,
        input.topicAnswers, input.replanFrom, input.replanFrom, input.firstNewStudyId) as ResplitResult.Revised).rows
}

class LiveReassessmentRepositoryTest {
    private lateinit var server: MockWebServer
    private val fixture = ReassessmentFixtures
    private val input = fixture.input(demo = false)
    private val revised = fixture.revised(input)
    private val snapshot = AvailabilitySnapshot(input.source.original.weeklyAvailability, input.source.original.availabilityOverrides)
    private fun response(action: String, rows: List<ScheduleRow> = revised) =
        ReassessmentResponse(rows, remainingCsv(rows), action, "plans/a.csv", snapshot)

    @Before fun start() { server = MockWebServer().apply { start() } }
    @After fun stop() { server.shutdown() }
    private fun repository() = LiveReassessmentRepository(server.url("/api/v1").toString())
    private fun enqueue(body: ReassessmentResponse) =
        server.enqueue(MockResponse().setBody(ApiJson.encodeToString(ReassessmentResponse.serializer(), body)))
    private fun enqueueError(status: Int, code: String, message: String) =
        server.enqueue(MockResponse().setResponseCode(status).setBody("""{"error":{"code":"$code","message":"$message"}}"""))
    private fun preview() = runBlocking { repository().preview(input) }
    private fun commit(previewed: List<ScheduleRow> = revised) = runBlocking { repository().commit(input, previewed) }

    @Test fun loadsQuestionsWithTopicAndExplanationInServedOrder() {
        server.enqueue(MockResponse().setBody("""{"certification_id":"$CERTIFICATION_ID","questions":[
            {"id":22,"prompt":"p22","choices":{"A":"a","B":"b"},"correct_answer":"A","possible_score":1,"topic":"매크로 활용","explanation":"e22"},
            {"id":19,"prompt":"p19","choices":{"A":"a","B":"b"},"correct_answer":"B","possible_score":1,"topic":"스프레드시트 기본","explanation":"e19"}]}"""))
        val loaded = runBlocking { repository().questions(CERTIFICATION_ID) } as ReassessmentQuestionsOutcome.Loaded
        assertEquals(listOf(22, 19), loaded.questions.map { it.question.id })
        assertEquals(listOf("매크로 활용", "스프레드시트 기본"), loaded.questions.map { it.topic })
        assertEquals("e19", loaded.questions[1].explanation)
        assertEquals("/api/v1/certifications/$CERTIFICATION_ID/reassessment-questions", server.takeRequest().path)
    }

    @Test fun missingRouteNeedsAServerUpdateAndOtherFailuresStayFailures() {
        enqueueError(404, "NOT_FOUND", "API route not found")
        assertEquals(ReassessmentQuestionsOutcome.ServerNeedsUpdate, runBlocking { repository().questions(CERTIFICATION_ID) })
        enqueueError(404, "NOT_FOUND", "Unsupported certification_id: $CERTIFICATION_ID")
        assertEquals(ReassessmentQuestionsOutcome.Failure(ApiError.UnknownFailure(status = 404, rawDiagnostic = null)),
            runBlocking { repository().questions(CERTIFICATION_ID) })
        server.enqueue(MockResponse().setResponseCode(500))
        val failed = runBlocking { repository().questions(CERTIFICATION_ID) } as ReassessmentQuestionsOutcome.Failure
        assertTrue(failed.error is ApiError.ServerFailure)
        server.enqueue(MockResponse().setBody("""{"certification_id":"$CERTIFICATION_ID","questions":[
            {"id":19,"prompt":"p","choices":{"A":"a","B":"b"},"correct_answer":"A","possible_score":1,"topic":"스프레드시트 기본"}]}"""))
        val missing = runBlocking { repository().questions(CERTIFICATION_ID) } as ReassessmentQuestionsOutcome.Failure
        assertTrue(missing.error is ApiError.InvalidResponse)
    }

    @Test fun previewSendsTheCapturedPlanKeptRowsFreshIdAndGenerationShapedResults() {
        enqueue(response("reassessment_preview"))
        val outcome = preview() as ReassessmentOutcome.Previewed
        assertEquals(revised, outcome.preview.rows)
        assertTrue(outcome.preview.hasChanges)
        val request = server.takeRequest()
        assertEquals("/api/v1/schedules/adjust", request.path)
        val sent = ApiJson.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals(setOf("mode", "commit", "schedule_csv_file", "schedules", "weekly_availability", "availability_overrides",
            "preparation_start", "exam_date", "replan_from", "keep_schedule_ids", "first_new_study_id", "assessment_results"), sent.keys)
        val dto = ApiJson.decodeFromJsonElement(ReassessmentRequest.serializer(), sent)
        assertEquals("reassessment", dto.mode)
        assertFalse(dto.commit)
        assertEquals(listOf("study-2"), dto.keepScheduleIds)
        assertEquals("study-8", dto.firstNewStudyId)
        assertEquals("2026/11/17/12/00", dto.replanFrom)
        assertEquals(fixture.rows, dto.schedules)
        val results = sent.getValue("assessment_results").jsonObject.getValue("results").jsonArray.map { it.jsonObject }
        assertEquals(listOf("A", "A", UNKNOWN_KEY, "B"), results.map { it.getValue("user_answer").jsonPrimitive.content })
        assertEquals(listOf("true", "true", "false", "false"), results.map { it.getValue("is_correct").jsonPrimitive.content })
        assertFalse(sent.toString().contains("topic\":\"${LocalTopics.CHARTS_AND_ANALYSIS}\",\"explanation"))
    }

    @Test fun commitSendsOneRequestAndAcceptsOnlyThePreviewedRows() {
        enqueue(response("reassessed"))
        val committed = commit() as ReassessmentOutcome.Committed
        assertEquals(revised, committed.response.schedules)
        val sent = ApiJson.decodeFromString(ReassessmentRequest.serializer(), server.takeRequest().body.readUtf8())
        assertTrue(sent.commit)
        assertEquals(revised, sent.previewedSchedules)
        assertEquals(1, server.requestCount)
    }

    @Test fun malformedOrDifferentResultsAreInvalidResponses() {
        val protected = revised.map { if (it.scheduleId == "study-2") it.copy(topic = LocalTopics.SPREADSHEET_BASICS) else it }
        val past = revised.map { if (it.scheduleId == "study-1") it.copy(topic = LocalTopics.MACROS) else it }
        val gap = revised.filterNot { it.scheduleId == "study-9" }
        val cases = listOf(response("reassessment_preview"), response("reassessed", protected), response("reassessed", past),
            response("reassessed", gap), response("reassessed").copy(scheduleCsvFile = "plans/b.csv"),
            response("reassessed").copy(scheduleCsv = "broken"))
        for (body in cases) {
            enqueue(body)
            val outcome = commit()
            assertTrue("$body -> $outcome", (outcome as? ReassessmentOutcome.Failure)?.error is ApiError.InvalidResponse)
        }
        enqueue(response("reassessed"))
        val different = revised.map { if (it.scheduleId == "study-8") it.copy(topic = LocalTopics.CHARTS_AND_ANALYSIS) else it }
        assertNotEquals(revised, different)
        assertTrue((commit(different) as ReassessmentOutcome.Failure).error is ApiError.InvalidResponse)
    }

    @Test fun rejectionsMapToTheirOutcomesWithoutFallback() {
        enqueueError(422, "REASSESSMENT_PREVIEW_EXPIRED", "a revisable session has started")
        assertEquals(ReassessmentOutcome.Rejected(ReassessmentFailure.PreviewExpired), commit())
        enqueueError(422, "INVALID_INPUT", "NOT_ENOUGH_TIME: total minutes must cover every assessed topic")
        assertEquals(ReassessmentOutcome.Rejected(ReassessmentFailure.NotEnoughTime), preview())
        enqueueError(422, "INVALID_INPUT", "mode must be automatic, manual or remaining")
        assertEquals(ReassessmentOutcome.ServerNeedsUpdate, preview())
        enqueueError(409, "PLAN_CONFLICT", "changed")
        assertEquals(ReassessmentOutcome.Failure(ApiError.PlanConflict), preview())
        enqueueError(429, "RATE_LIMITED", "slow down")
        assertEquals(ReassessmentOutcome.Failure(ApiError.RateLimited), preview())
        assertEquals(5, server.requestCount)
    }

    @Test fun noFutureUnreportedSessionsUsesTheNothingLeftOutcome() {
        enqueueError(422, "INVALID_INPUT", "NOTHING_TO_REVISE: no future unreported study sessions")
        assertEquals(ReassessmentOutcome.Rejected(ReassessmentFailure.NothingToRevise), preview())
    }

    @Test fun aServerPlanWithoutStoredStudyContextNeedsAReplacement() {
        enqueueError(422, "INVALID_INPUT", "This saved plan has no stored preparation period and study times. " +
            "Create a new plan before re-planning remaining sessions.")
        assertEquals(ReassessmentOutcome.Rejected(ReassessmentFailure.MissingPlanContext), preview())
    }

    @Test fun planWithoutServerIdentitySendsNothing() {
        val outcome = runBlocking { repository().preview(fixture.input(demo = true)) } as ReassessmentOutcome.Failure
        assertTrue(outcome.error is ApiError.InvalidInput)
        assertEquals(0, server.requestCount)
    }
}
