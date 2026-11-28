package com.swpp.licenseplanner.ui

import androidx.core.util.AtomicFile
import androidx.lifecycle.viewModelScope
import com.swpp.licenseplanner.data.*
import com.swpp.licenseplanner.data.model.*
import com.swpp.licenseplanner.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit

/** Replay the captured real-generation HTTP responses through app state and actual atomic storage.
 * The provider/API calls were executed by run_generated_plan_reassessment.py; this test replays
 * their responses offline. It does not claim an emulator or a new provider call.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GeneratedPlanReassessmentTest {
    @get:Rule val temporary = TemporaryFolder()
    private val dispatcher = StandardTestDispatcher()
    @Before fun before() = Dispatchers.setMain(dispatcher)
    @After fun after() = Dispatchers.resetMain()

    @Test fun bothCapturedAnswerProfilesPreserveReportsAndSurviveAppStateRecreation() =
        replayCapture("generated_plan_reassessment.json")

    @Test fun lowStudyReportsAndAgentChosenReviewsSurviveAppStateRecreation() =
        replayCapture("generated_plan_low_study_reassessment.json")

    private fun replayCapture(filename: String) = runTest(dispatcher) {
        val fixture = ApiJson.parseToJsonElement(File(requireNotNull(System.getProperty("user.dir")))
            .resolve("../../backend/tests/schedule_adjuster_tests/fixtures/$filename").readText()).jsonObject
        val rawRequest = fixture.getValue("creation_request").jsonObject
        // These optional API fields have required Android DTO fields; use the API defaults.
        val request = ApiJson.decodeFromJsonElement<StudyPlanRequest>(JsonObject(rawRequest + mapOf(
            "self_assessment" to JsonPrimitive(""), "study_days_per_week" to JsonPrimitive(5))))
        val original = ApiJson.decodeFromJsonElement<StudyPlanResponse>(fixture.getValue("generated"))
        val clock = requireNotNull(WireDateTime.parseOrNull(fixture.getValue("replan_from").jsonPrimitive.content))
        val reports = fixture.getValue("check_ins").jsonObject.mapValues { (_, item) ->
            val record = item.jsonObject
            StudyCheckIn(StudyCheckInStatus.valueOf(record.getValue("status").jsonPrimitive.content),
                record.getValue("actualMinutes").jsonPrimitive.int)
        }
        if (filename == "generated_plan_reassessment.json") {
            assertTrue(reports.values.any { it.status == StudyCheckInStatus.Done })
        }
        assertTrue(reports.values.any { it.status == StudyCheckInStatus.Partly })
        for (element in fixture.getValue("profiles").jsonArray) {
            val profile = element.jsonObject
            val label = profile.getValue("label").jsonPrimitive.content
            MockWebServer().use { server ->
                server.start()
                val base = server.url("/api/v1").toString().trimEnd('/')
                val scope = PlanStorageScope.of(false, base)
                val source = SessionPlan.register(1, request, original, false).copy(checkIns = reports)
                val file = File(temporary.root, "profile-$label.json")
                fun store() = BytesPlanStore(AtomicFilePlanStore.AtomicFileBytes(AtomicFile(file)), scope, dispatcher)
                val disk = store()
                assertTrue(disk.load() is PlanStoreLoad.Loaded)
                assertTrue(disk.save(PlanSnapshot(listOf(source))))
                val repository = LiveReassessmentRepository(base, io = Dispatchers.Unconfined)
                val questionRepository = object : QuestionRepository {
                    override suspend fun load(certificationId: String): QuestionOutcome = error("unexpected onboarding request")
                }
                val planning = object : PlanningRepository {
                    override suspend fun generate(request: StudyPlanRequest): PlanOutcome = error("unexpected generation")
                }
                val adjustment = object : AdjustmentRepository {
                    override suspend fun adjust(input: AdjustmentInput): AdjustmentOutcome = error("unexpected move")
                    override suspend fun reload(scheduleCsvFile: String?): ReloadOutcome = error("unexpected reload")
                }
                fun open(storage: LocalPlanStore) = PlanFormViewModel(questionRepository, planning, adjustment,
                    today = clock.toLocalDate(), planStore = storage, currentDate = { clock.toLocalDate() },
                    currentMinute = { clock }, reassessmentRepository = repository)
                val vm = open(disk)
                advanceUntilIdle()
                server.enqueue(MockResponse().setBody(JsonObject(mapOf("certification_id" to JsonPrimitive(request.certificationId),
                    "questions" to fixture.getValue("questions"))).toString()))
                assertTrue(vm.startReassessment(1))
                // Network callbacks run on OkHttp's threads, so wait with real time while driving the test dispatcher.
                suspend fun settleUntil(ready: () -> Boolean) {
                    repeat(200) {
                        advanceUntilIdle()
                        if (ready()) return
                        withContext(Dispatchers.IO) { Thread.sleep(10) }
                    }
                    fail("Timed out waiting for captured HTTP response: ${vm.reassessment.value}")
                }
                settleUntil { vm.reassessment.value is ReassessmentState.Quiz }
                profile.getValue("answers").jsonObject.forEach { (id, answer) ->
                    vm.answerReassessment(id.toInt(), answer.jsonPrimitive.content)
                }
                assertTrue(vm.finishReassessmentQuiz())
                val bytesBeforePreview = file.readBytes()
                server.enqueue(MockResponse().setBody(profile.getValue("preview").toString()))
                assertTrue(vm.previewReassessment())
                settleUntil { vm.reassessment.value is ReassessmentState.Preview }
                assertArrayEquals(bytesBeforePreview, file.readBytes())
                assertEquals(reports, vm.plans.value.single().checkIns)
                val expected = ApiJson.decodeFromJsonElement<StudyPlanResponse>(profile.getValue("saved"))
                assertEquals(expected.schedules, (vm.reassessment.value as ReassessmentState.Preview).preview.rows)
                server.enqueue(MockResponse().setBody(profile.getValue("saved").toString()))
                assertTrue(vm.confirmReassessment())
                settleUntil { (vm.reassessment.value as? ReassessmentState.Done)?.save == MoveSave.Saved }
                assertEquals(expected.schedules, vm.plans.value.single().current.schedules)
                assertEquals(reports, vm.plans.value.single().checkIns)
                assertEquals(PlanRecovery.Ready, vm.plans.value.single().recovery)
                val get = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
                assertEquals("GET", get.method)
                val previewRequest = ApiJson.parseToJsonElement(requireNotNull(server.takeRequest(2, TimeUnit.SECONDS)).body.readUtf8()).jsonObject
                assertFalse(previewRequest.containsKey("previewed_schedules"))
                val savedRequest = ApiJson.parseToJsonElement(requireNotNull(server.takeRequest(2, TimeUnit.SECONDS)).body.readUtf8()).jsonObject
                assertTrue(savedRequest.getValue("commit").jsonPrimitive.boolean)
                assertEquals(profile.getValue("preview").jsonObject.getValue("preview_token"), savedRequest.getValue("preview_token"))
                assertEquals(reports.keys, savedRequest.getValue("study_reports").jsonObject.keys)
                assertEquals(expected.schedules,
                    ApiJson.decodeFromJsonElement<List<ScheduleRow>>(savedRequest.getValue("previewed_schedules")))
                vm.viewModelScope.cancel()
                val reopened = open(store())
                advanceUntilIdle()
                assertEquals(reports, reopened.plans.value.single().checkIns)
                assertEquals(expected.schedules, reopened.plans.value.single().current.schedules)
                for (id in reports.keys) {
                    assertEquals(original.schedules.single { it.scheduleId == id },
                        reopened.plans.value.single().current.schedules.single { it.scheduleId == id })
                }
                assertNull(reopened.reassessment.value)
                reopened.viewModelScope.cancel()
            }
        }
    }
}
