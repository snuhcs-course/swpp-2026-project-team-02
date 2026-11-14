package com.swpp.licenseplanner.data

import androidx.core.util.AtomicFile
import com.swpp.licenseplanner.data.model.AssessmentResults
import com.swpp.licenseplanner.data.model.ScheduleRow
import com.swpp.licenseplanner.data.model.StudyPlanRequest
import com.swpp.licenseplanner.data.model.StudyPlanResponse
import com.swpp.licenseplanner.data.model.TimeWindow
import com.swpp.licenseplanner.domain.CERTIFICATION_ID
import com.swpp.licenseplanner.domain.CreationDraft
import com.swpp.licenseplanner.domain.CreationStep
import com.swpp.licenseplanner.domain.DayAvailability
import com.swpp.licenseplanner.domain.PlanFormState
import com.swpp.licenseplanner.domain.Question
import com.swpp.licenseplanner.domain.PlanRecovery
import com.swpp.licenseplanner.domain.SessionPlan
import com.swpp.licenseplanner.domain.StudyCheckIn
import com.swpp.licenseplanner.domain.StudyCheckInStatus
import com.swpp.licenseplanner.domain.Weekday
import com.swpp.licenseplanner.domain.defaultAvailability
import com.swpp.licenseplanner.domain.emptyWeek
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.LocalDate
import java.time.LocalTime

class AtomicFilePlanStoreTest {
    @get:Rule val folder = TemporaryFolder()

    private val scope = PlanStorageScope.of(demo = true, apiBaseUrl = "")

    @Test fun missingSnapshotIsAnEmptyCollection() = runTest {
        val file = File(folder.root, PlanStorageScope.fileName(scope))
        val store = BytesPlanStore(AtomicFilePlanStore.AtomicFileBytes(AtomicFile(file)), scope, StandardTestDispatcher(testScheduler))

        assertEquals(PlanStoreLoad.Loaded(PlanSnapshot.EMPTY), store.load())
    }

    @Test fun existingSnapshotThatCannotBeOpenedFailsAndIsNotOverwritten() = runTest {
        val file = File(folder.root, PlanStorageScope.fileName(scope))
        val saved = PlanSnapshotCodec.encode(PlanSnapshot.EMPTY, scope).encodeToByteArray()
        file.writeBytes(saved)
        file.setReadable(false, false)
        assumeFalse("permissions are not enforced for this user", file.canRead())
        val store = BytesPlanStore(AtomicFilePlanStore.AtomicFileBytes(AtomicFile(file)), scope, StandardTestDispatcher(testScheduler))

        try {
            assertEquals(PlanStoreLoad.Failed(PlanLoadFailure.Unreadable), store.load())
            assertFalse(store.save(PlanSnapshot.EMPTY))
        } finally {
            file.setReadable(true, false)
        }
        assertArrayEquals(saved, file.readBytes())
    }

    // --- Study reports in whole-collection saves ---

    private val urlX = "http://10.0.2.2:8765/api/v1"
    private val liveX = PlanStorageScope.of(demo = false, apiBaseUrl = urlX)
    private val liveY = PlanStorageScope.of(demo = false, apiBaseUrl = "http://192.168.0.7:8000/api/v1")

    private val request = StudyPlanRequest(
        certificationId = CERTIFICATION_ID,
        preparationStart = "2026/10/05/00/00",
        examDate = "2026/11/16/00/00",
        selfAssessment = "intermediate",
        assessmentResults = AssessmentResults(CERTIFICATION_ID, emptyList()),
        studyDaysPerWeek = 1,
        weeklyAvailability = mapOf("monday" to listOf(TimeWindow("18:00", "19:00"))),
    )
    private val rows = listOf(
        ScheduleRow("study-1", "2026/10/05/18/00", "2026/10/05/18/30", "함수"),
        ScheduleRow("study-2", "2026/10/12/18/00", "2026/10/12/19/00", "차트"),
        ScheduleRow("external-1", "2026/10/06/18/00", "2026/10/06/19/00", ""),
    )

    private fun plan(ordinal: Int, demo: Boolean, vararg reports: Pair<String, StudyCheckIn>): SessionPlan {
        val file = if (demo) null else "plan-$ordinal.csv"
        return SessionPlan(ordinal, request, demo, StudyPlanResponse(rows, "csv", null, file), file, checkIns = mapOf(*reports))
    }

    private val partly45 = StudyCheckIn(StudyCheckInStatus.Partly, 45)
    private val done20 = StudyCheckIn(StudyCheckInStatus.Done, 20)
    private val missed = StudyCheckIn(StudyCheckInStatus.Missed, 0)

    /** Two plans sharing study IDs; the first awaits a reload and keeps a report for a row it no longer shows. */
    private val earlier = PlanSnapshot(
        listOf(
            plan(1, demo = false, "study-1" to partly45, "study-9" to done20).copy(recovery = PlanRecovery.ReloadRequired, recoveryScheduleId = "study-2"),
            plan(2, demo = false, "study-1" to missed),
        ),
    )
    private val later = PlanSnapshot(
        listOf(
            earlier.plans[0],
            plan(2, demo = false, "study-1" to done20, "study-2" to partly45),
            plan(3, demo = false),
        ),
    )

    private fun atomic(scope: String) = AtomicFile(File(folder.root, PlanStorageScope.fileName(scope)))

    private fun TestScope.store(scope: String, file: AtomicFile = atomic(scope)) =
        BytesPlanStore(AtomicFilePlanStore.AtomicFileBytes(file), scope, StandardTestDispatcher(testScheduler))

    private fun encoded(snapshot: PlanSnapshot, scope: String = liveX) = PlanSnapshotCodec.encode(snapshot, scope).encodeToByteArray()

    @Test fun reportsAreReplacedWithTheWholeCollection() = runTest {
        val first = store(liveX)
        assertEquals(PlanStoreLoad.Loaded(PlanSnapshot.EMPTY), first.load())
        assertTrue(first.save(earlier))
        assertEquals(PlanStoreLoad.Loaded(earlier), store(liveX).load())

        assertTrue(first.save(later))
        assertArrayEquals(encoded(later), atomic(liveX).baseFile.readBytes())
        val restored = (store(liveX).load() as PlanStoreLoad.Loaded).snapshot
        assertEquals(later, restored)
        assertEquals(partly45, restored.plans[0].checkInFor("study-1"))
        assertEquals(done20, restored.plans[1].checkInFor("study-1"))
        assertEquals(done20, restored.plans[0].checkIns["study-9"])
        assertEquals(PlanRecovery.ReloadRequired, restored.plans[0].recovery)
        assertFalse(File(atomic(liveX).baseFile.path + ".new").exists())
    }

    @Test fun failedWriteKeepsThePreviousCommittedBytes() = runTest {
        val first = store(liveX)
        first.load()
        assertTrue(first.save(earlier))
        val committed = atomic(liveX).baseFile.readBytes()

        // The temporary file cannot be created, so the write fails before anything is replaced.
        val blocker = File(atomic(liveX).baseFile.path + ".new")
        assertTrue(blocker.mkdir())
        assertFalse(first.save(later))
        assertArrayEquals(committed, atomic(liveX).baseFile.readBytes())
        assertTrue(blocker.delete())
        assertEquals(PlanStoreLoad.Loaded(earlier), store(liveX).load())

        assertTrue(first.save(later))
        assertEquals(PlanStoreLoad.Loaded(later), store(liveX).load())
    }

    @Test fun interruptedWriteRestoresThePreviousCollection() = runTest {
        val first = store(liveX)
        first.load()
        assertTrue(first.save(earlier))
        val newer = encoded(later)

        // Stopped partway through writing the temporary file, before it was committed.
        val file = atomic(liveX)
        file.startWrite().use { it.write(newer, 0, newer.size / 2) }
        assertEquals(PlanStoreLoad.Loaded(earlier), store(liveX).load())
        assertFalse(File(file.baseFile.path + ".new").exists())

        // An earlier AtomicFile moved the committed file aside first; that backup wins over partial bytes.
        val backup = File(file.baseFile.path + ".bak")
        assertTrue(file.baseFile.renameTo(backup))
        file.baseFile.writeBytes(newer.copyOf(newer.size / 2))
        assertEquals(PlanStoreLoad.Loaded(earlier), store(liveX).load())
        assertFalse(backup.exists())

        val restarted = store(liveX)
        restarted.load()
        assertTrue(restarted.save(later))
        assertEquals(PlanStoreLoad.Loaded(later), store(liveX).load())
    }

    @Test fun interruptedFirstWriteLeavesNoCollection() = runTest {
        val newer = encoded(earlier)
        atomic(liveX).startWrite().use { it.write(newer, 0, newer.size / 2) }
        assertEquals(PlanStoreLoad.Loaded(PlanSnapshot.EMPTY), store(liveX).load())
    }

    @Test fun unusableSavedDataIsNotOverwrittenWithReports() = runTest {
        val unsupported = String(encoded(earlier)).replaceFirst("\"version\":4", "\"version\":5").encodeToByteArray()
        val cases = mapOf(
            "unsupported version" to (unsupported to PlanLoadFailure.Unsupported),
            "malformed" to ("{\"version\":3,".encodeToByteArray() to PlanLoadFailure.Malformed),
            "other target" to (encoded(earlier, liveY) to PlanLoadFailure.Invalid),
        )
        for ((case, value) in cases) {
            val (saved, reason) = value
            val file = atomic(liveX)
            file.baseFile.writeBytes(saved)
            val store = store(liveX, file)
            assertEquals(case, PlanStoreLoad.Failed(reason), store.load())
            assertFalse(case, store.save(later))
            assertArrayEquals(case, saved, file.baseFile.readBytes())
            assertFalse(case, File(file.baseFile.path + ".new").exists())
        }
    }

    @Test fun demoAndEachApiTargetKeepTheirOwnReports() = runTest {
        val demoScope = PlanStorageScope.of(demo = true, apiBaseUrl = "")
        val demoPlans = PlanSnapshot(listOf(plan(1, demo = true, "study-1" to missed)))
        val targetY = PlanSnapshot(listOf(plan(1, demo = false, "study-2" to done20)))
        val saved = mapOf(demoScope to demoPlans, liveX to earlier, liveY to targetY)
        for ((scope, snapshot) in saved) {
            val store = store(scope)
            store.load()
            assertTrue(scope, store.save(snapshot))
        }
        assertEquals(3, folder.root.listFiles()!!.size)

        val updated = store(liveX)
        updated.load()
        assertTrue(updated.save(later))
        for ((scope, snapshot) in saved + (liveX to later)) {
            assertEquals(scope, PlanStoreLoad.Loaded(snapshot), store(scope).load())
        }
        assertEquals(PlanStoreLoad.Loaded(PlanSnapshot.EMPTY), store(PlanStorageScope.of(false, "http://other/api/v1")).load())
    }

    // --- Creation drafts in whole-collection saves ---

    private val quizDraft = CreationDraft(
        PlanFormState(
            certificationId = CERTIFICATION_ID,
            preparationStart = LocalDate.of(2026, 11, 18),
            examDate = LocalDate.of(2026, 12, 15),
            questions = listOf(Question(3, "합계 함수는?", linkedMapOf("A" to "=COUNT", "B" to "=SUM"), "B", 1.0)),
            answers = mapOf(3 to "A"),
        ),
        CreationStep.QUESTIONS,
    )
    private val availabilityDraft = CreationDraft(
        quizDraft.form.copy(
            availability = defaultAvailability() + (Weekday.MONDAY to DayAvailability(true, LocalTime.of(19, 0), 90)),
            availabilityOverrides = mapOf(LocalDate.of(2026, 11, 30) to emptyWeek()),
        ),
        CreationStep.AVAILABILITY,
        availabilityWeek = LocalDate.of(2026, 11, 30),
    )

    @Test fun aDraftIsAddedReplacedAndRemovedWithoutChangingPlansOrReports() = runTest {
        val first = store(liveX)
        first.load()
        assertTrue(first.save(earlier))
        val withoutDraft = atomic(liveX).baseFile.readBytes()

        for (draft in listOf(quizDraft, availabilityDraft)) {
            assertTrue(first.save(earlier.copy(creationDraft = draft)))
            val restored = (store(liveX).load() as PlanStoreLoad.Loaded).snapshot
            assertEquals(draft, restored.creationDraft)
            assertEquals(earlier.plans, restored.plans)
        }

        assertTrue(first.save(earlier.copy(creationDraft = null)))
        assertArrayEquals(withoutDraft, atomic(liveX).baseFile.readBytes())
        assertEquals(PlanStoreLoad.Loaded(earlier), store(liveX).load())
    }

    @Test fun aNewPlanAndTheRemovalOfItsDraftAreCommittedTogether() = runTest {
        val drafted = earlier.copy(creationDraft = availabilityDraft)
        val completed = later.copy(creationDraft = null)
        val first = store(liveX)
        first.load()
        assertTrue(first.save(drafted))
        val committed = atomic(liveX).baseFile.readBytes()

        // A failed write leaves the earlier plans and the draft exactly as committed.
        val blocker = File(atomic(liveX).baseFile.path + ".new")
        assertTrue(blocker.mkdir())
        assertFalse(first.save(completed))
        assertArrayEquals(committed, atomic(liveX).baseFile.readBytes())
        assertTrue(blocker.delete())
        assertEquals(PlanStoreLoad.Loaded(drafted), store(liveX).load())

        // An interrupted write never yields the new plan with the draft still present, or the reverse.
        val newer = encoded(completed)
        atomic(liveX).startWrite().use { it.write(newer, 0, newer.size / 2) }
        assertEquals(PlanStoreLoad.Loaded(drafted), store(liveX).load())

        assertTrue(first.save(completed))
        val restored = (store(liveX).load() as PlanStoreLoad.Loaded).snapshot
        assertEquals(completed, restored)
        assertEquals(null, restored.creationDraft)
        assertEquals(3, restored.plans.size)
    }

    @Test fun unusableSavedDataWithADraftIsNotOverwritten() = runTest {
        val text = String(encoded(earlier.copy(creationDraft = quizDraft)))
        val cases = mapOf(
            "unknown step" to (text.replaceFirst("\"step\":\"questions\"", "\"step\":\"generate\"") to PlanLoadFailure.Malformed),
            "cursor past the end" to (text.replaceFirst("\"question_index\":0", "\"question_index\":4") to PlanLoadFailure.Invalid),
            "draft in version 3" to (text.replaceFirst("\"version\":4", "\"version\":3") to PlanLoadFailure.Invalid),
            "unsupported version" to (text.replaceFirst("\"version\":4", "\"version\":5") to PlanLoadFailure.Unsupported),
        )
        for ((case, value) in cases) {
            val (savedText, reason) = value
            val saved = savedText.encodeToByteArray()
            assertFalse(case, saved.contentEquals(text.encodeToByteArray()))
            val file = atomic(liveX)
            file.baseFile.writeBytes(saved)
            val store = store(liveX, file)
            assertEquals(case, PlanStoreLoad.Failed(reason), store.load())
            assertFalse(case, store.save(later))
            assertFalse(case, store.save(earlier.copy(creationDraft = null)))
            assertArrayEquals(case, saved, file.baseFile.readBytes())
            assertFalse(case, File(file.baseFile.path + ".new").exists())
        }
    }

    @Test fun loadingAnEarlierVersionDoesNotRewriteIt() = runTest {
        val legacy = String(encoded(earlier)).replaceFirst("\"version\":4", "\"version\":3").encodeToByteArray()
        val file = atomic(liveX)
        file.baseFile.writeBytes(legacy)
        assertTrue(file.baseFile.setLastModified(1_000_000L))

        val loaded = store(liveX).load() as PlanStoreLoad.Loaded
        assertEquals(earlier, loaded.snapshot)
        assertEquals(null, loaded.snapshot.creationDraft)
        assertArrayEquals(legacy, file.baseFile.readBytes())
        assertEquals(1_000_000L, file.baseFile.lastModified())
        assertFalse(File(file.baseFile.path + ".new").exists())
    }

    @Test fun demoAndEachApiTargetKeepTheirOwnDraft() = runTest {
        val demoScope = PlanStorageScope.of(demo = true, apiBaseUrl = "")
        val saved = mapOf(
            demoScope to PlanSnapshot(listOf(plan(1, demo = true)), creationDraft = quizDraft),
            liveX to earlier.copy(creationDraft = availabilityDraft),
            liveY to PlanSnapshot(listOf(plan(1, demo = false))),
        )
        for ((scope, snapshot) in saved) {
            val store = store(scope)
            store.load()
            assertTrue(scope, store.save(snapshot))
        }
        val cleared = store(liveX)
        cleared.load()
        assertTrue(cleared.save(earlier))
        for ((scope, snapshot) in saved + (liveX to earlier)) {
            assertEquals(scope, PlanStoreLoad.Loaded(snapshot), store(scope).load())
        }
        assertEquals(PlanStoreLoad.Loaded(PlanSnapshot.EMPTY), store(PlanStorageScope.of(false, "http://other/api/v1")).load())
    }
}
