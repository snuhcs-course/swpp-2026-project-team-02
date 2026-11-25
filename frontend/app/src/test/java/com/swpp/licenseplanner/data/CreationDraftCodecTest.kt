package com.swpp.licenseplanner.data

import com.swpp.licenseplanner.data.model.AssessmentResult
import com.swpp.licenseplanner.data.model.AssessmentResults
import com.swpp.licenseplanner.data.model.ScheduleRow
import com.swpp.licenseplanner.data.model.StudyPlanRequest
import com.swpp.licenseplanner.data.model.StudyPlanResponse
import com.swpp.licenseplanner.data.model.TimeWindow
import com.swpp.licenseplanner.domain.CERTIFICATION_ID
import com.swpp.licenseplanner.domain.CreationDraft
import com.swpp.licenseplanner.domain.CreationStep
import com.swpp.licenseplanner.domain.CurrentLevel
import com.swpp.licenseplanner.domain.DayAvailability
import com.swpp.licenseplanner.domain.PlanFormState
import com.swpp.licenseplanner.domain.PlanRecovery
import com.swpp.licenseplanner.domain.Question
import com.swpp.licenseplanner.domain.SessionPlan
import com.swpp.licenseplanner.domain.StudyCheckIn
import com.swpp.licenseplanner.domain.StudyCheckInStatus
import com.swpp.licenseplanner.domain.UNKNOWN_KEY
import com.swpp.licenseplanner.domain.Weekday
import com.swpp.licenseplanner.domain.WindowSpec
import com.swpp.licenseplanner.domain.defaultAvailability
import com.swpp.licenseplanner.domain.emptyWeek
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class CreationDraftCodecTest {
    private val live = PlanStorageScope.of(demo = false, apiBaseUrl = "http://10.0.2.2:8765/api/v1")
    private val demo = PlanStorageScope.of(demo = true, apiBaseUrl = "")

    private val request = StudyPlanRequest(
        certificationId = CERTIFICATION_ID,
        preparationStart = "2026/10/05/00/00",
        examDate = "2026/11/16/00/00",
        selfAssessment = "intermediate",
        assessmentResults = AssessmentResults(CERTIFICATION_ID, listOf(AssessmentResult(1, "합계 함수는?", mapOf("A" to "=COUNT", "B" to "=SUM"), "B", "B", true, 1.0))),
        studyDaysPerWeek = 1,
        weeklyAvailability = mapOf("monday" to listOf(TimeWindow("18:00", durationMinutes = 60))),
    )
    private val rows = listOf(
        ScheduleRow("study-1", "2026/10/05/18/00", "2026/10/05/19/00", "함수"),
        ScheduleRow("study-2", "2026/10/12/18/00", "2026/10/12/19/00", "차트"),
    )

    /** Plan 1 has reports; plan 2 shares its row IDs and awaits a reload. */
    private val plans = listOf(
        SessionPlan(1, request, false, StudyPlanResponse(rows, "csv", null, "a.csv"), "a.csv",
            checkIns = mapOf("study-1" to StudyCheckIn(StudyCheckInStatus.Done, 50))),
        SessionPlan(2, request, false, StudyPlanResponse(rows, "csv", null, "b.csv"), "b.csv",
            checkIns = mapOf("study-1" to StudyCheckIn(StudyCheckInStatus.Missed, 0))),
    )

    private val questions = listOf(
        Question(3, "합계 함수는?", linkedMapOf("B" to "=SUM", "A" to "=COUNT"), "B", 1.0),
        Question(7, "셀 참조는?", linkedMapOf("1" to "상대", "2" to "절대", "3" to "혼합"), "2", 1.5),
    )
    private val start = LocalDate.of(2026, 11, 18)
    private val form = PlanFormState(
        certificationId = CERTIFICATION_ID,
        preparationStart = start,
        examDate = LocalDate.of(2026, 12, 15),
        level = CurrentLevel.ADVANCED,
        questions = questions,
        answers = mapOf(7 to UNKNOWN_KEY),
        availability = defaultAvailability() +
            (Weekday.MONDAY to DayAvailability(true, LocalTime.of(23, 0), 90, listOf(WindowSpec(LocalTime.of(7, 30), 30)))) +
            (Weekday.SUNDAY to DayAvailability(false, LocalTime.of(20, 30), 120, listOf(WindowSpec(LocalTime.of(10, 0), 45)))),
        availabilityOverrides = mapOf(
            LocalDate.of(2026, 11, 23) to defaultAvailability() + (Weekday.TUESDAY to DayAvailability(true, LocalTime.of(21, 0), 60)),
            LocalDate.of(2026, 11, 30) to emptyWeek(),
        ),
        busySchedules = listOf(ScheduleRow("41", "2026/11/24/09/00", "2026/11/24/12/00", "바쁜 일정")),
    )
    private val draft = CreationDraft(form, CreationStep.QUESTIONS, questionIndex = 1, availabilityWeek = LocalDate.of(2026, 11, 23))

    private fun encoded(snapshot: PlanSnapshot, scope: String = live) = PlanSnapshotCodec.encode(snapshot, scope)
    private fun decode(text: String, scope: String = live) = PlanSnapshotCodec.decode(text, scope)
    private fun roundTrip(snapshot: PlanSnapshot, scope: String = live) = decode(encoded(snapshot, scope), scope)

    private fun JsonElement.with(key: String, value: JsonElement): JsonObject = JsonObject(jsonObject + (key to value))

    private fun editDraft(text: String, change: (JsonObject) -> JsonElement): String {
        val root = Json.parseToJsonElement(text).jsonObject
        return root.with("creation_draft", change(root.getValue("creation_draft").jsonObject)).toString()
    }

    private fun JsonObject.edit(key: String, change: (JsonElement) -> JsonElement): JsonObject = with(key, change(getValue(key)))

    @Test fun plansReportsReloadGateAndDraftAreSavedAndRestoredTogether() = runTest {
        val snapshot = PlanSnapshot(plans, inFlightAdjustmentOrdinal = 2, creationDraft = draft)
        val bytes = MemoryPlanBytes()
        val store = BytesPlanStore(bytes, live, StandardTestDispatcher(testScheduler))
        store.load()
        assertTrue(store.save(snapshot))
        val saved = Json.parseToJsonElement(bytes.committed!!.decodeToString()).jsonObject
        assertEquals(JsonPrimitive(5), saved["version"])
        assertTrue("creation_draft" in saved)

        val restored = (BytesPlanStore(bytes, live, StandardTestDispatcher(testScheduler)).load() as PlanStoreLoad.Loaded).snapshot
        assertEquals(draft, restored.creationDraft)
        assertEquals(PlanRecovery.ReloadRequired, restored.plans[1].recovery)
        assertNull(restored.inFlightAdjustmentOrdinal)
        assertEquals(snapshot.plans.map { it.checkIns }, restored.plans.map { it.checkIns })
        assertEquals(listOf("B", "A"), restored.creationDraft!!.form.questions[0].choices.keys.toList())
        assertEquals(1, restored.creationDraft!!.questionIndex)
        assertEquals(LocalDate.of(2026, 11, 23), restored.creationDraft!!.availabilityWeek)
        assertEquals(form.availability.getValue(Weekday.SUNDAY), restored.creationDraft!!.form.availability.getValue(Weekday.SUNDAY))
    }

    @Test fun aCollectionWithoutDraftWritesNoDraftField() {
        val text = encoded(PlanSnapshot(plans))
        assertFalse("creation_draft" in Json.parseToJsonElement(text).jsonObject)
        assertEquals(PlanStoreLoad.Loaded(PlanSnapshot(plans)), decode(text))
        assertEquals(PlanStoreLoad.Loaded(PlanSnapshot(emptyList(), creationDraft = draft)), roundTrip(PlanSnapshot(emptyList(), creationDraft = draft)))
    }

    @Test fun incompleteAndNotYetValidInputIsRestoredAsEditableInput() {
        val fresh = PlanFormState(preparationStart = start)
        val drafts = mapOf(
            "fresh defaults" to CreationDraft(fresh, CreationStep.CERTIFICATION),
            "dates without exam" to CreationDraft(fresh.copy(certificationId = CERTIFICATION_ID), CreationStep.DATES),
            "exam before start" to draft.copy(form = form.copy(examDate = start.minusDays(5)), step = CreationStep.DATES),
            "no level" to draft.copy(form = form.copy(level = null), step = CreationStep.LEVEL),
            "questions not loaded" to CreationDraft(form.copy(questions = emptyList(), answers = emptyMap()), CreationStep.QUESTIONS),
            "all answered" to draft.copy(form = form.copy(answers = mapOf(3 to "A", 7 to "3")), step = CreationStep.RESULTS),
            "no study day anywhere" to draft.copy(
                form = form.copy(availability = defaultAvailability(), availabilityOverrides = mapOf(LocalDate.of(2026, 11, 23) to emptyWeek())),
                step = CreationStep.AVAILABILITY,
            ),
            "week no longer selectable" to draft.copy(availabilityWeek = LocalDate.of(2027, 1, 4), step = CreationStep.AVAILABILITY),
        )
        for ((case, value) in drafts) {
            assertTrue(case, value.isValid)
            assertEquals(case, PlanStoreLoad.Loaded(PlanSnapshot(plans, creationDraft = value)), roundTrip(PlanSnapshot(plans, creationDraft = value)))
        }
        assertTrue(form.copy(examDate = start.minusDays(5)).datesError != null)
        assertTrue(form.copy(availability = defaultAvailability(), availabilityOverrides = emptyMap()).availabilityError != null)
    }

    @Test fun aDemoDraftStaysInTheDemoScope() {
        val demoPlan = SessionPlan(1, request, true, StudyPlanResponse(rows, "csv", null, null), null)
        val text = encoded(PlanSnapshot(listOf(demoPlan), creationDraft = draft), demo)
        assertEquals(PlanStoreLoad.Loaded(PlanSnapshot(listOf(demoPlan), creationDraft = draft)), decode(text, demo))
        assertEquals(PlanStoreLoad.Failed(PlanLoadFailure.Invalid), decode(text, live))
    }

    @Test fun versionFourPreservesTheCompleteDraftAndReports() {
        val snapshot = PlanSnapshot(plans, creationDraft = draft)
        val root = Json.parseToJsonElement(encoded(snapshot)).jsonObject
        val legacy = root.with("version", JsonPrimitive(4)).with("entries",
            JsonArray(root.getValue("entries").jsonArray.map { JsonObject(it.jsonObject - "kept_conflicts") })).toString()
        assertEquals(PlanStoreLoad.Loaded(snapshot), decode(legacy))
        val kept = snapshot.copy(plans = plans.map { it.copy(keptConflicts = mapOf("study-1" to setOf("a".repeat(64)))) })
        assertEquals(PlanStoreLoad.Loaded(kept), roundTrip(kept))
    }

    @Test fun earlierVersionsCarryingADraftAreInvalid() {
        val text = encoded(PlanSnapshot(emptyList(), creationDraft = draft))
        for (version in 1..3) {
            val earlier = Json.parseToJsonElement(text).with("version", JsonPrimitive(version)).toString()
            assertEquals("version $version", PlanStoreLoad.Failed(PlanLoadFailure.Invalid), decode(earlier))
        }
    }

    @Test fun storedDraftValuesTheScreensCannotHoldAreRejected() {
        val text = encoded(PlanSnapshot(plans, creationDraft = draft))
        fun day(key: String, change: (JsonObject) -> JsonObject) = editDraft(text) { d ->
            d.edit("availability") { days -> days.jsonObject.edit(key) { change(it.jsonObject) } }
        }
        fun question(index: Int, change: (JsonObject) -> JsonObject) = editDraft(text) { d ->
            d.edit("questions") { list -> JsonArray(list.jsonArray.mapIndexed { i, q -> if (i == index) change(q.jsonObject) else q }) }
        }
        val invalid = mapOf(
            "impossible date" to editDraft(text) { it.with("exam_date", JsonPrimitive("2026-02-30")) },
            "wire date format" to editDraft(text) { it.with("preparation_start", JsonPrimitive("2026/11/18")) },
            "week not a Monday" to editDraft(text) { it.with("availability_week", JsonPrimitive("2026-11-24")) },
            "cursor past the end" to editDraft(text) { it.with("question_index", JsonPrimitive(2)) },
            "negative cursor" to editDraft(text) { it.with("question_index", JsonPrimitive(-1)) },
            "other certification" to editDraft(text) { it.with("certification_id", JsonPrimitive("word_processor")) },
            "duplicate question id" to question(1) { it.with("id", JsonPrimitive(3)) },
            "nonpositive question id" to question(0) { it.with("id", JsonPrimitive(0)) },
            "single choice" to question(0) { it.with("choices", JsonObject(mapOf("B" to JsonPrimitive("=SUM")))).with("correct_answer", JsonPrimitive("B")) },
            "correct answer not a choice" to question(0) { it.with("correct_answer", JsonPrimitive("Z")) },
            "unknown as correct answer" to question(0) { it.with("correct_answer", JsonPrimitive(UNKNOWN_KEY)) },
            "zero score" to question(0) { it.with("possible_score", JsonPrimitive(0.0)) },
            "answer to an unloaded question" to editDraft(text) { it.with("answers", JsonObject(mapOf("4" to JsonPrimitive("A")))) },
            "answer outside the choices" to editDraft(text) { it.with("answers", JsonObject(mapOf("3" to JsonPrimitive("C")))) },
            "padded answer ID" to editDraft(text) { it.with("answers", JsonObject(mapOf("03" to JsonPrimitive("A")))) },
            "nonnumeric answer ID" to editDraft(text) { it.with("answers", JsonObject(mapOf("x" to JsonPrimitive("A")))) },
            "missing weekday" to editDraft(text) { d -> d.edit("availability") { JsonObject(it.jsonObject - "sunday") } },
            "unknown weekday" to editDraft(text) { d -> d.edit("availability") { days -> JsonObject(days.jsonObject - "sunday" + ("sun" to days.jsonObject.getValue("sunday"))) } },
            "impossible start" to day("monday") { it.with("start", JsonPrimitive("25:00")) },
            "start with seconds" to day("monday") { it.with("start", JsonPrimitive("18:00:00")) },
            "zero duration" to day("monday") { it.with("duration_minutes", JsonPrimitive(0)) },
            "too long extra window" to day("monday") { d ->
                d.with("extras", JsonArray(listOf(JsonObject(mapOf("start" to JsonPrimitive("07:30"), "duration_minutes" to JsonPrimitive(1440))))))
            },
            "replacement not on a Monday" to editDraft(text) { d ->
                d.edit("availability_overrides") { weeks -> JsonObject(mapOf("2026-11-25" to weeks.jsonObject.getValue("2026-11-30"))) }
            },
            "replacement with partial week" to editDraft(text) { d ->
                d.edit("availability_overrides") { weeks -> JsonObject(mapOf("2026-11-30" to JsonObject(emptyMap()))) }
            },
            "busy row ending before it starts" to editDraft(text) { d ->
                d.with("busy_schedules", JsonArray(listOf(JsonObject(mapOf(
                    "schedule_id" to JsonPrimitive("41"), "start_date" to JsonPrimitive("2026/11/24/12/00"),
                    "end_date" to JsonPrimitive("2026/11/24/09/00"), "topic" to JsonPrimitive(""),
                )))))
            },
        )
        for ((case, corrupted) in invalid) {
            assertEquals(case, PlanStoreLoad.Failed(PlanLoadFailure.Invalid), decode(corrupted))
        }

        val malformed = mapOf(
            "unknown step" to editDraft(text) { it.with("step", JsonPrimitive("generate")) },
            "unknown level" to editDraft(text) { it.with("level", JsonPrimitive("expert")) },
            "unknown draft field" to editDraft(text) { it.with("request", JsonPrimitive("x")) },
            "missing step" to editDraft(text) { JsonObject(it - "step") },
            "cursor as text" to editDraft(text) { it.with("question_index", JsonPrimitive("one")) },
            "draft as list" to editDraft(text) { JsonArray(emptyList()) },
        )
        for ((case, corrupted) in malformed) {
            assertEquals(case, PlanStoreLoad.Failed(PlanLoadFailure.Malformed), decode(corrupted))
        }
        assertEquals(PlanStoreLoad.Failed(PlanLoadFailure.Invalid), decode(text, PlanStorageScope.of(false, "http://other/api/v1")))
    }

    @Test fun splitWindowsAndEmptyWeeksKeepIndependentPlansReportsAndRecovery() = runTest {
        val split = defaultAvailability() +
            (Weekday.MONDAY to DayAvailability.of(listOf(WindowSpec(LocalTime.of(8, 0), 60), WindowSpec(LocalTime.of(19, 0), 120)))) +
            (Weekday.SUNDAY to DayAvailability.of(listOf(WindowSpec(LocalTime.of(23, 30), 60))))
        val monday = LocalDate.of(2026, 11, 23)
        val empty = monday.plusWeeks(1)
        val input = draft.copy(form = form.copy(
            availability = split,
            availabilityOverrides = mapOf(
                monday to split + (Weekday.MONDAY to DayAvailability.of(listOf(WindowSpec(LocalTime.of(9, 0), 60), WindowSpec(LocalTime.of(19, 0), 120)))),
                empty to emptyWeek(),
            ),
        ), availabilityWeek = monday)
        val snapshot = PlanSnapshot(plans, 2, input)
        val bytes = MemoryPlanBytes()
        val store = BytesPlanStore(bytes, live, StandardTestDispatcher(testScheduler))
        store.load()
        assertTrue(store.save(snapshot))
        val committed = bytes.committed!!.copyOf()
        val restored = (BytesPlanStore(bytes, live, StandardTestDispatcher(testScheduler)).load() as PlanStoreLoad.Loaded).snapshot
        assertEquals(input, restored.creationDraft)
        assertEquals(plans.map { it.original }, restored.plans.map { it.original })
        assertEquals(plans.map { it.current }, restored.plans.map { it.current })
        assertEquals(plans.map { it.checkIns }, restored.plans.map { it.checkIns })
        assertEquals(PlanRecovery.ReloadRequired, restored.plans[1].recovery)
        assertTrue(committed.contentEquals(bytes.committed!!))
        assertEquals(emptyWeek(), restored.creationDraft!!.form.availabilityOverrides[empty])
    }

}
