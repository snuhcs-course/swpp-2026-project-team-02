package com.swpp.licenseplanner.data

import com.swpp.licenseplanner.data.model.AssessmentResult
import com.swpp.licenseplanner.data.model.AssessmentResults
import com.swpp.licenseplanner.data.model.ScheduleRow
import com.swpp.licenseplanner.data.model.StudyPlanRequest
import com.swpp.licenseplanner.data.model.StudyPlanResponse
import com.swpp.licenseplanner.data.model.TimeWindow
import com.swpp.licenseplanner.domain.CERTIFICATION_ID
import com.swpp.licenseplanner.domain.PlanRecovery
import com.swpp.licenseplanner.domain.SessionPlan
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanSnapshotTest {
    private val urlX = "http://10.0.2.2:8765/api/v1"
    private val liveX = PlanStorageScope.of(demo = false, apiBaseUrl = urlX)
    private val demo = PlanStorageScope.of(demo = true, apiBaseUrl = "")

    private val mondayWindows = mapOf("monday" to listOf(TimeWindow("18:00", "20:00")))
    private val wednesdayWindows = mapOf("wednesday" to listOf(TimeWindow("18:00", "19:00")))

    private fun request(windows: Map<String, List<TimeWindow>>) = StudyPlanRequest(
        certificationId = CERTIFICATION_ID,
        preparationStart = "2026/10/05/00/00",
        examDate = "2026/11/16/00/00",
        selfAssessment = "intermediate",
        assessmentResults = AssessmentResults(
            CERTIFICATION_ID,
            listOf(
                AssessmentResult(1, "합계 함수는?", mapOf("A" to "=COUNT", "B" to "=SUM"), "B", "B", true, 1.0),
                AssessmentResult(2, "새 문항", mapOf("A" to "가", "B" to "나"), "A", "unknown", false, 1.5),
            ),
        ),
        studyDaysPerWeek = 1,
        weeklyAvailability = windows,
    )

    private val fileA = "license_planner/output/study_plan_a.csv"
    private val rowsA = listOf(
        ScheduleRow("study-1", "2026/10/05/18/00", "2026/10/05/19/00", "함수"),
        ScheduleRow("study-2", "2026/10/12/18/00", "2026/10/12/19/00", "아주 긴 주제 이름이 그대로 저장되고 복원되는지 확인하는 차트 작성 연습"),
        ScheduleRow("external-1", "2026/10/06/18/00", "2026/10/06/19/00", ""),
    )
    private val rowsB = listOf(
        ScheduleRow("study-1", "2026/10/07/18/00", "2026/10/07/19/00", "셀 참조"),
        ScheduleRow("study-2", "2026/10/14/18/00", "2026/10/14/19/00", "셀 참조"),
    )

    /** A awaits a reload of study-2; B shares A's file and IDs; C is missing; D has no identity or rows. */
    private val planA = SessionPlan(1, request(mondayWindows), false, StudyPlanResponse(rowsA, "csv-a", "요약 A", fileA), fileA, PlanRecovery.ReloadRequired, "study-2")
    private val planB = SessionPlan(2, request(wednesdayWindows), false, StudyPlanResponse(rowsB, "", null, fileA), fileA)
    private val planC = SessionPlan(4, request(mondayWindows), false, StudyPlanResponse(rowsB, "csv-c", null, "c.csv"), "c.csv", PlanRecovery.Missing)
    private val planD = SessionPlan(5, request(mondayWindows), false, StudyPlanResponse(emptyList()), null)
    private val live = PlanSnapshot(listOf(planA, planB, planC, planD))

    private val demoPlans = PlanSnapshot(
        listOf(
            SessionPlan(1, request(mondayWindows), true, StudyPlanResponse(rowsA, "csv", null, null), null),
            SessionPlan(2, request(wednesdayWindows), true, StudyPlanResponse(rowsB, "csv", null, null), null),
        ),
    )

    private fun decode(text: String, scope: String = liveX) = PlanSnapshotCodec.decode(text, scope)
    private fun encoded(snapshot: PlanSnapshot = live, scope: String = liveX) = PlanSnapshotCodec.encode(snapshot, scope)

    private fun TestScope.store(bytes: MemoryPlanBytes, scope: String = liveX) =
        BytesPlanStore(bytes, scope, StandardTestDispatcher(testScheduler))

    // --- JSON editing helpers for corrupted fixtures ---

    private fun JsonElement.with(key: String, value: JsonElement): JsonObject = JsonObject(jsonObject + (key to value))

    private fun edit(text: String, change: (JsonObject) -> JsonObject): String =
        change(Json.parseToJsonElement(text).jsonObject).toString()

    private fun editEntry(text: String, index: Int, change: (JsonObject) -> JsonObject): String = edit(text) { root ->
        val entries = root.getValue("entries").jsonArray.toMutableList()
        entries[index] = change(entries[index].jsonObject)
        root.with("entries", JsonArray(entries))
    }

    private fun JsonObject.withRow(index: Int, key: String, value: String): JsonObject {
        val current = getValue("current")
        val rows = current.jsonObject.getValue("schedules").jsonArray.toMutableList()
        rows[index] = rows[index].with(key, JsonPrimitive(value))
        return with("current", current.with("schedules", JsonArray(rows)))
    }

    private fun JsonObject.withOriginal(key: String, value: JsonElement): JsonObject =
        with("original", getValue("original").with(key, value))

    private fun windows(vararg pairs: Pair<String, List<Pair<String, String>>>): JsonElement = JsonObject(
        pairs.associate { (day, list) ->
            day to JsonArray(list.map { (start, end) -> JsonObject(mapOf("start" to JsonPrimitive(start), "end" to JsonPrimitive(end))) })
        },
    )

    // --- Round trip and ordinals ---

    @Test fun roundTripKeepsEveryEntryWithItsOriginalContext() {
        val restored = (decode(encoded()) as PlanStoreLoad.Loaded).snapshot
        assertEquals(live, restored)
        assertEquals(listOf(1, 2, 4, 5), restored.plans.map { it.ordinal })
        assertEquals(mondayWindows, restored.plans[0].original.weeklyAvailability)
        assertEquals(wednesdayWindows, restored.plans[1].original.weeklyAvailability)
        assertEquals(listOf(fileA, fileA, "c.csv", null), restored.plans.map { it.scheduleCsvFile })
        assertEquals(1.5, restored.plans[0].original.assessmentResults.results[1].possibleScore, 0.0)
        assertEquals(planA.current, restored.plans[0].current)

        assertEquals(demoPlans, (decode(encoded(demoPlans, demo), demo) as PlanStoreLoad.Loaded).snapshot)
    }

    @Test fun nextOrdinalFollowsTheLargestSavedOrdinal() {
        assertEquals(1, PlanSnapshot.EMPTY.nextOrdinal)
        assertEquals(6, live.nextOrdinal)
        assertEquals(3, demoPlans.nextOrdinal)
    }

    // --- Scope ---

    @Test fun scopesSeparateFlavorsAndBackendUrls() {
        assertEquals(liveX, PlanStorageScope.of(false, "$urlX/"))
        val liveY = PlanStorageScope.of(false, "http://192.168.0.7:8000/api/v1")
        val scopes = listOf(demo, liveX, liveY)
        assertEquals(3, scopes.toSet().size)
        assertEquals(3, scopes.map(PlanStorageScope::fileName).toSet().size)
        assertEquals(PlanStorageScope.fileName(liveX), PlanStorageScope.fileName(PlanStorageScope.of(false, "$urlX/")))
        for (scope in scopes) {
            val name = PlanStorageScope.fileName(scope)
            assertTrue(name, Regex("plans-[0-9a-f]{64}\\.json").matches(name))
        }

        val savedX = encoded()
        assertTrue(decode(savedX, liveX) is PlanStoreLoad.Loaded)
        assertEquals(PlanStoreLoad.Failed(PlanLoadFailure.Invalid), decode(savedX, liveY))
        assertEquals(PlanStoreLoad.Failed(PlanLoadFailure.Invalid), decode(savedX, demo))
        assertEquals(PlanStoreLoad.Failed(PlanLoadFailure.Invalid), decode(encoded(demoPlans, demo), liveX))
    }

    // --- Unresolved adjustment and retained targets ---

    @Test fun unresolvedAdjustmentRestoresAReloadGateWithItsTarget() {
        val pending = planB.copy(recovery = PlanRecovery.ReloadRequired, recoveryScheduleId = "study-1")
        for (stored in listOf(pending, planB.copy(recoveryScheduleId = "study-1"))) {
            val snapshot = PlanSnapshot(listOf(planA, stored, planC), inFlightAdjustmentOrdinal = 2)
            val restored = (decode(encoded(snapshot)) as PlanStoreLoad.Loaded).snapshot
            assertNull(restored.inFlightAdjustmentOrdinal)
            assertEquals(listOf(planA, pending, planC), restored.plans)
        }
    }

    @Test fun staleTargetIsDroppedWhileTheGateStays() {
        for (target in listOf("external-1", "study-9")) {
            val restored = (decode(encoded(PlanSnapshot(listOf(planA.copy(recoveryScheduleId = target), planB.copy(recoveryScheduleId = "study-1"))))) as PlanStoreLoad.Loaded).snapshot
            assertEquals(PlanRecovery.ReloadRequired, restored.plans[0].recovery)
            assertNull(restored.plans[0].recoveryScheduleId)
            assertEquals(planB, restored.plans[1]) // a ready plan carries no target
        }
        val unresolved = PlanSnapshot(listOf(planB.copy(recoveryScheduleId = "study-9")), inFlightAdjustmentOrdinal = 2)
        val restored = (decode(encoded(unresolved)) as PlanStoreLoad.Loaded).snapshot.plans.single()
        assertEquals(PlanRecovery.ReloadRequired, restored.recovery)
        assertNull(restored.recoveryScheduleId)
    }

    // --- Rejected data ---

    @Test fun invalidEssentialDataIsAFailureNotAFallback() {
        val text = encoded()
        val invalid = mapOf(
            "duplicate ordinal" to editEntry(text, 1) { it.with("ordinal", JsonPrimitive(1)) },
            "zero ordinal" to editEntry(text, 0) { it.with("ordinal", JsonPrimitive(0)) },
            "demo entry in live scope" to editEntry(text, 3) { it.with("is_demo", JsonPrimitive(true)) },
            "blank identity" to editEntry(text, 1) { it.with("schedule_csv_file", JsonPrimitive(" ")) },
            "gate without identity" to editEntry(text, 3) { it.with("recovery", JsonPrimitive("reload_required")) },
            "end before start" to editEntry(text, 0) { it.withRow(0, "end_date", "2026/10/05/17/00") },
            "unparseable row time" to editEntry(text, 0) { it.withRow(1, "start_date", "2026-10-12 18:00") },
            "duplicate row id" to editEntry(text, 1) { it.withRow(1, "schedule_id", "study-1") },
            "blank row id" to editEntry(text, 1) { it.withRow(0, "schedule_id", "") },
            "exam before preparation" to editEntry(text, 0) { it.withOriginal("exam_date", JsonPrimitive("2026/10/01/00/00")) },
            "unparseable exam date" to editEntry(text, 0) { it.withOriginal("exam_date", JsonPrimitive("2026/11/31/00/00")) },
            "unknown weekday" to editEntry(text, 0) { it.withOriginal("weekly_availability", windows("funday" to listOf("18:00" to "20:00"))) },
            "window ends before start" to editEntry(text, 0) { it.withOriginal("weekly_availability", windows("monday" to listOf("18:00" to "17:00"))) },
            "window time format" to editEntry(text, 0) { it.withOriginal("weekly_availability", windows("monday" to listOf("18:00" to "24:00"))) },
            "no windows" to editEntry(text, 0) { it.withOriginal("weekly_availability", windows("monday" to emptyList())) },
            "unresolved plan absent" to edit(text) { it.with("in_flight_adjustment_ordinal", JsonPrimitive(99)) },
            "unresolved plan without identity" to edit(text) { it.with("in_flight_adjustment_ordinal", JsonPrimitive(5)) },
        )
        for ((case, corrupted) in invalid) {
            assertEquals(case, PlanStoreLoad.Failed(PlanLoadFailure.Invalid), decode(corrupted))
        }

        val demoText = encoded(demoPlans, demo)
        val invalidDemo = mapOf(
            "demo identity" to editEntry(demoText, 0) { it.with("schedule_csv_file", JsonPrimitive(fileA)) },
            "demo gate" to editEntry(demoText, 0) { it.with("recovery", JsonPrimitive("missing")) },
            "live entry in demo scope" to editEntry(demoText, 1) { it.with("is_demo", JsonPrimitive(false)) },
            "unresolved demo plan" to edit(demoText) { it.with("in_flight_adjustment_ordinal", JsonPrimitive(1)) },
        )
        for ((case, corrupted) in invalidDemo) {
            assertEquals(case, PlanStoreLoad.Failed(PlanLoadFailure.Invalid), decode(corrupted, demo))
        }

        val malformed = mapOf(
            "unknown recovery" to editEntry(text, 0) { it.with("recovery", JsonPrimitive("pending")) },
            "missing rows" to editEntry(text, 0) { it.with("current", JsonObject(emptyMap())) },
            "unknown field" to edit(text) { it.with("plan_id", JsonPrimitive("x")) },
            "null entries" to edit(text) { it.with("entries", JsonNull) },
            "missing version" to edit(text) { JsonObject(it - "version") },
            "array root" to "[]",
            "not json" to "plans",
            "truncated" to text.dropLast(20),
        )
        for ((case, corrupted) in malformed) {
            assertEquals(case, PlanStoreLoad.Failed(PlanLoadFailure.Malformed), decode(corrupted))
        }
        assertEquals(PlanStoreLoad.Failed(PlanLoadFailure.Unsupported), decode(edit(text) { it.with("version", JsonPrimitive(2)) }))
    }

    // --- Store over committed bytes ---

    @Test fun missingFileIsAnEmptyCollection() = runTest {
        val bytes = MemoryPlanBytes()
        assertEquals(PlanStoreLoad.Loaded(PlanSnapshot.EMPTY), store(bytes).load())
        assertEquals(0, bytes.writes)
    }

    @Test fun unusableSavedDataKeepsItsBytesAndRefusesSaving() = runTest {
        val text = encoded()
        val cases = listOf(
            Triple("unreadable", text.encodeToByteArray(), PlanLoadFailure.Unreadable),
            Triple("malformed", "{\"version\":1,".encodeToByteArray(), PlanLoadFailure.Malformed),
            Triple("invalid utf-8", byteArrayOf(0x7b, 0xff.toByte(), 0x7d), PlanLoadFailure.Malformed),
            Triple("future version", edit(text) { it.with("version", JsonPrimitive(2)) }.encodeToByteArray(), PlanLoadFailure.Unsupported),
            Triple("other scope", encoded(scope = PlanStorageScope.of(false, "http://other/api/v1")).encodeToByteArray(), PlanLoadFailure.Invalid),
        )
        for ((case, saved, reason) in cases) {
            val bytes = MemoryPlanBytes(saved.copyOf()).apply { failReads = case == "unreadable" }
            val store = store(bytes)
            assertEquals(case, PlanStoreLoad.Failed(reason), store.load())
            assertFalse(case, store.save(PlanSnapshot.EMPTY))
            assertFalse(case, store.save(live))
            assertEquals(case, 0, bytes.writes)
            assertArrayEquals(case, saved, bytes.committed)
        }
    }

    @Test fun savesReplaceTheWholeSnapshotAndFailedWritesKeepTheEarlierOne() = runTest {
        val bytes = MemoryPlanBytes()
        val first = store(bytes)
        assertFalse(first.save(live)) // never before a successful load
        assertEquals(0, bytes.writes)
        first.load()
        assertTrue(first.save(live))
        val committed = bytes.committed

        val restarted = store(bytes)
        assertEquals(PlanStoreLoad.Loaded(live), restarted.load())
        bytes.failWrites = true
        assertFalse(restarted.save(PlanSnapshot(listOf(planB))))
        assertArrayEquals(committed, bytes.committed)
        assertEquals(PlanStoreLoad.Loaded(live), store(bytes).load())

        bytes.failWrites = false
        assertTrue(restarted.save(PlanSnapshot(listOf(planB))))
        assertEquals(PlanStoreLoad.Loaded(PlanSnapshot(listOf(planB))), store(bytes).load())
        assertNotEquals(committed?.toList(), bytes.committed?.toList())
    }
}
