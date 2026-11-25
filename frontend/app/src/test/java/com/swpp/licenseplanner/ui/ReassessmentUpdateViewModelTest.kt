package com.swpp.licenseplanner.ui

import androidx.lifecycle.viewModelScope
import com.swpp.licenseplanner.TestFixtures
import com.swpp.licenseplanner.data.*
import com.swpp.licenseplanner.data.model.*
import com.swpp.licenseplanner.domain.*
import com.swpp.licenseplanner.ui.screens.reassessmentStopText
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.time.LocalDateTime

@OptIn(ExperimentalCoroutinesApi::class)
class ReassessmentUpdateViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val start = RemainingStudyFixtures.now
    private var clock: LocalDateTime = start
    private val scope = PlanStorageScope.of(false, "http://test/api/v1")

    private fun row(id: Int, day: Int, hour: Int = 18, minutes: Int = 60): ScheduleRow {
        val from = LocalDateTime.of(2026, 11, day, hour, 0)
        return ScheduleRow("study-$id", WireDateTime.format(from), WireDateTime.format(from.plusMinutes(minutes.toLong())),
            LocalTopics.SPREADSHEET_BASICS)
    }

    /**
     * Plan 1: a past reported row, a row later today already reported partly, and three unreported
     * rows in the third preparation week. A kept conflict is retained under study-15.
     */
    private fun livePlan(rows: List<ScheduleRow> = listOf(row(1, 12), row(2, 16), row(3, 24), row(4, 25), row(5, 26)), demo: Boolean = false) =
        SessionPlan.register(1, RemainingStudyFixtures.original, StudyPlanResponse(rows, scheduleCsvFile = if (demo) null else "plans/a.csv"), demo)
            .copy(checkIns = mapOf("study-1" to StudyCheckIn(StudyCheckInStatus.Done, 60), "study-2" to StudyCheckIn(StudyCheckInStatus.Partly, 20)),
                keptConflicts = mapOf("study-15" to setOf("a".repeat(64)), "study-3" to setOf("b".repeat(64))))

    private val a = livePlan()
    private val b = a.copy(ordinal = 2, scheduleCsvFile = "plans/b.csv", current = a.current.copy(scheduleCsvFile = "plans/b.csv"))

    private val topics = listOf(
        LocalTopics.SPREADSHEET_BASICS, LocalTopics.SPREADSHEET_BASICS, LocalTopics.SPREADSHEET_BASICS, LocalTopics.SPREADSHEET_BASICS,
        LocalTopics.INTERNET_DATA, LocalTopics.INTERNET_DATA, LocalTopics.INTERNET_DATA, LocalTopics.INTERNET_DATA,
        LocalTopics.SYSTEM_USE, LocalTopics.SYSTEM_USE, LocalTopics.SYSTEM_USE,
        LocalTopics.FUNCTIONS_AND_DATA, LocalTopics.FUNCTIONS_AND_DATA,
        LocalTopics.SYSTEM_PROTECTION, LocalTopics.SYSTEM_PROTECTION,
        LocalTopics.CHARTS_AND_ANALYSIS, LocalTopics.PAGE_SETUP_AND_PRINTING, LocalTopics.MACROS,
    )
    private val items = topics.mapIndexed { index, topic ->
        val id = 19 + index
        ReassessmentQuestion(Question(id, "다시 진단 문항 $id", mapOf("A" to "가", "B" to "나", "C" to "다", "D" to "라"), "B", 1.0),
            topic, "문항 $id 해설")
    }

    private inner class Store(val disk: MemoryPlanBytes, planScope: String) : LocalPlanStore {
        val real = BytesPlanStore(disk, planScope, dispatcher)
        var count = 0
        val fail = mutableSetOf<Int>()
        override suspend fun load() = real.load()
        override suspend fun save(snapshot: PlanSnapshot): Boolean {
            if (++count in fail) return false
            return real.save(snapshot)
        }
    }

    /** Server stand-in that re-splits with the shared rules at its own clock. */
    private inner class Server : ReassessmentRepository {
        var now: LocalDateTime = start
        val previews = mutableListOf<ReassessmentInput>()
        val commits = mutableListOf<Pair<ReassessmentInput, List<ScheduleRow>>>()
        val previewFailures = mutableListOf<ReassessmentOutcome>()
        var commitFailure: ReassessmentOutcome? = null
        var gate: CompletableDeferred<Unit>? = null
        var alter: (List<ScheduleRow>) -> List<ScheduleRow> = { it }

        override suspend fun questions(certificationId: String) = ReassessmentQuestionsOutcome.Loaded(items)

        override suspend fun preview(input: ReassessmentInput): ReassessmentOutcome {
            previews += input
            previewFailures.removeFirstOrNull()?.let { return it }
            return when (val result = resplit(input)) {
                is ResplitResult.Failed -> ReassessmentOutcome.Rejected(result.failure)
                is ResplitResult.Revised -> ReassessmentOutcome.Previewed(
                    Reassessment.preview(input.source.current.schedules, input.keep, input.replanFrom, result.rows))
            }
        }

        override suspend fun commit(input: ReassessmentInput, previewed: List<ScheduleRow>): ReassessmentOutcome {
            commits += input to previewed
            gate?.await()
            commitFailure?.let { return it }
            return when (val result = resplit(input)) {
                is ResplitResult.Failed -> ReassessmentOutcome.Rejected(result.failure)
                is ResplitResult.Revised -> {
                    val rows = alter(result.rows)
                    ReassessmentOutcome.Committed(StudyPlanResponse(rows, remainingCsv(rows), scheduleCsvFile = input.source.scheduleCsvFile))
                }
            }
        }

        private fun resplit(input: ReassessmentInput) = Reassessment.resplit(input.source.current.schedules, input.keep,
            input.topicAnswers, input.replanFrom, now, input.firstNewStudyId)
    }

    private class Other : AdjustmentRepository {
        var reloads = 0
        var rows: List<ScheduleRow> = emptyList()
        override suspend fun adjust(input: AdjustmentInput): AdjustmentOutcome = error("unexpected adjustment")
        override suspend fun reload(scheduleCsvFile: String?): ReloadOutcome { reloads++; return ReloadOutcome.Loaded(rows) }
    }

    private class Calendar : CalendarReadRepository, CalendarExportRepository {
        var uses = 0
        override suspend fun read(window: CalendarReadWindow): CalendarReadOutcome { uses++; return CalendarReadOutcome.Unavailable }
        override suspend fun calendars(): CalendarListOutcome { uses++; return CalendarListOutcome.Unavailable }
        override suspend fun export(destination: CalendarDestination, events: List<CalendarEvent>): CalendarWriteOutcome {
            uses++; error("unexpected calendar write")
        }
    }

    private data class Opened(val vm: PlanFormViewModel, val store: Store, val server: Server, val other: Other, val calendar: Calendar)

    @Before fun before() = Dispatchers.setMain(dispatcher)
    @After fun after() = Dispatchers.resetMain()

    private fun TestScope.open(snapshot: PlanSnapshot = PlanSnapshot(listOf(a, b)), disk: MemoryPlanBytes? = null,
                               repository: ReassessmentRepository? = null): Opened {
        val planScope = if (snapshot.plans.first().isDemo) PlanStorageScope.DEMO else scope
        val bytes = disk ?: MemoryPlanBytes(PlanSnapshotCodec.encode(snapshot, planScope).encodeToByteArray())
        val store = Store(bytes, planScope)
        val server = Server()
        val other = Other()
        val calendar = Calendar()
        val vm = PlanFormViewModel(object : QuestionRepository {
            override suspend fun load(certificationId: String) = QuestionOutcome.Loaded(TestFixtures.publicQuestions())
        }, object : PlanningRepository {
            override suspend fun generate(request: StudyPlanRequest): PlanOutcome = error("unexpected generation")
        }, other, today = start.toLocalDate(), planStore = store, calendarReadRepository = calendar, calendarExportRepository = calendar,
            currentDate = { clock.toLocalDate() }, currentMinute = { clock }, reassessmentRepository = repository ?: server)
        advanceUntilIdle()
        assertEquals(PlanStorage.Ready, vm.storage.value)
        return Opened(vm, store, server, other, calendar)
    }

    private suspend fun restored(o: Opened): PlanSnapshot = (o.store.load() as PlanStoreLoad.Loaded).snapshot

    /** Opens the quiz for plan 1, answers 0–11 right, 12–15 wrong and 16–17 모르겠음, and shows results. */
    private fun TestScope.results(o: Opened) {
        assertTrue(o.vm.startReassessment(1))
        advanceUntilIdle()
        items.forEachIndexed { index, item ->
            o.vm.answerReassessment(item.question.id, when {
                index < 12 -> "B"
                index < 16 -> "A"
                else -> UNKNOWN_KEY
            })
        }
        assertTrue(o.vm.finishReassessmentQuiz())
    }

    private fun TestScope.preview(o: Opened): ReassessmentState.Preview {
        results(o)
        assertTrue(o.vm.previewReassessment())
        advanceUntilIdle()
        return o.vm.reassessment.value as ReassessmentState.Preview
    }

    private fun newIds(o: Opened, plan: SessionPlan = a) =
        o.vm.plans.value[0].current.schedules.map { it.scheduleId } - plan.current.schedules.map { it.scheduleId }.toSet()

    @Test fun previewListsTopicMinutesAndEveryChangedSessionWithoutChangingAnything() = runTest(dispatcher) {
        val o = open()
        val bytes = o.store.disk.committed!!.copyOf()
        val shown = preview(o)
        val input = o.server.previews.single()
        assertEquals(start, input.replanFrom)
        assertEquals(items, input.questions)
        assertEquals(setOf("study-2"), input.keep)
        val preview = shown.preview
        assertTrue(preview.hasChanges)
        assertEquals(180, preview.topics.sumOf { it.before })
        assertEquals(180, preview.totalMinutes)
        assertEquals(180, preview.topics.single { it.topic == LocalTopics.SPREADSHEET_BASICS }.before)
        assertEquals(topics.toSet(), preview.topics.map { it.topic }.toSet())
        assertTrue(preview.topics.all { it.after >= 1 })
        // Every unreported future row changes; each new piece keeps a time inside the row it replaces.
        assertEquals(listOf("study-3", "study-4", "study-5"), preview.sessions.map { it.original.scheduleId })
        val pieces = changedPieces(preview)
        assertTrue(pieces.all { (before, _) -> before == LocalTopics.SPREADSHEET_BASICS })
        assertEquals("11월 24일 화요일 · 18:00–" + WireDateTime.formatTime(WireDateTime.parseOrNull(pieces[0].second.endDate)!!.toLocalTime()),
            com.swpp.licenseplanner.ui.screens.reassessmentSessionTime(pieces[0].second))
        // Nothing was written or sent beyond the one preview, and no recovery gate was set.
        assertEquals(listOf(a, b), o.vm.plans.value)
        assertArrayEquals(bytes, o.store.disk.committed)
        assertEquals(0, o.store.count)
        assertEquals(0, o.server.commits.size)
        assertEquals(0, o.calendar.uses)
    }

    @Test fun previewWithNoChangeOffersOnlyReturnAndNeverCommits() = runTest(dispatcher) {
        val first = open()
        val revised = preview(first).preview.rows
        val settled = a.copy(current = a.current.copy(schedules = revised))
        val o = open(PlanSnapshot(listOf(settled, b)))
        val shown = preview(o)
        assertFalse(shown.preview.hasChanges)
        assertTrue(changedPieces(shown.preview).isEmpty())
        assertFalse(o.vm.confirmReassessment())
        assertTrue(o.vm.closeReassessment())
        assertEquals(0, o.server.commits.size)
        assertEquals(0, o.store.count)
        assertEquals(listOf(settled, b), o.vm.plans.value)
    }

    @Test fun tooLittleFutureTimeIsExplainedAndThePlanIsUnchanged() = runTest(dispatcher) {
        val short = livePlan(listOf(row(1, 12), row(2, 16), row(3, 24, minutes = 5)))
        val o = open(PlanSnapshot(listOf(short)))
        results(o)
        assertTrue(o.vm.previewReassessment())
        advanceUntilIdle()
        val stopped = o.vm.reassessment.value as ReassessmentState.Stopped
        assertEquals(ReassessmentStop.NotEnoughTime, stopped.reason)
        assertFalse(stopped.canPreviewAgain)
        assertEquals("다시 나눌 남은 시간이 부족해요", reassessmentStopText(stopped).first)
        assertFalse(o.vm.previewReassessment())
        assertFalse(o.vm.confirmReassessment())
        assertEquals(listOf(short), o.vm.plans.value)
        assertEquals(0, o.store.count)
        assertEquals(0, o.server.commits.size)
    }

    @Test fun missingServerContextAsksForANewPlanWithoutOfferingRetry() = runTest(dispatcher) {
        val o = open()
        o.server.previewFailures += ReassessmentOutcome.Rejected(ReassessmentFailure.MissingPlanContext)
        results(o)
        assertTrue(o.vm.previewReassessment())
        advanceUntilIdle()

        val stopped = o.vm.reassessment.value as ReassessmentState.Stopped
        assertEquals(ReassessmentStop.MissingPlanContext, stopped.reason)
        assertTrue(reassessmentStopText(stopped).second.contains("새 계획을 만들어 주세요"))
        assertFalse(o.vm.previewReassessment())
        assertEquals(listOf(a, b), o.vm.plans.value)
        assertEquals(0, o.store.count)
    }

    @Test fun failedPreviewUsesAdjustmentWordingKeepsTheAnswersAndChangesNothing() = runTest(dispatcher) {
        for (error in listOf(ApiError.Connection, ApiError.PlanConflict, ApiError.PlanNotFound, ApiError.RateLimited,
            ApiError.ServerFailure(500))) {
            val o = open()
            o.server.previewFailures += ReassessmentOutcome.Failure(error)
            results(o)
            assertTrue(o.vm.previewReassessment())
            advanceUntilIdle()
            val stopped = o.vm.reassessment.value as ReassessmentState.Stopped
            assertEquals(ReassessmentStop.PreviewFailed, stopped.reason)
            assertEquals(error, stopped.error)
            assertTrue(stopped.canPreviewAgain)
            assertTrue(reassessmentStopText(stopped).second.startsWith(error.previewMessage()))
            assertEquals(listOf(a, b), o.vm.plans.value)
            assertEquals(0, o.store.count)
            // One more attempt with the same answers.
            assertTrue(o.vm.previewReassessment())
            advanceUntilIdle()
            assertTrue(o.vm.reassessment.value is ReassessmentState.Preview)
            assertEquals(o.server.previews[0].answers, o.server.previews[1].answers)
            assertEquals(0, o.server.commits.size)
        }
        assertEquals(ApiError.PlanConflict.adjustmentMessage(), ApiError.PlanConflict.previewMessage())
        assertEquals(ApiError.RateLimited.adjustmentMessage(), ApiError.RateLimited.previewMessage())
        assertFalse(ApiError.Connection.previewMessage().contains("반영되었을 수"))
    }

    @Test fun confirmingSendsOneCommitUnderTheRecoveryGateAndSavesThePreviewedRows() = runTest(dispatcher) {
        val o = open()
        o.server.gate = CompletableDeferred()
        val shown = preview(o)
        assertTrue(o.vm.confirmReassessment())
        runCurrent()
        assertTrue(o.vm.reassessment.value is ReassessmentState.Committing)
        // The device copy marks the plan as possibly changed on the server until the outcome is known.
        val gated = restored(o)
        assertEquals(PlanRecovery.ReloadRequired, gated.plans[0].recovery)
        assertEquals(a.current, gated.plans[0].current)
        // The in-flight update cannot be repeated, left or overtaken by another plan operation.
        assertFalse(o.vm.confirmReassessment())
        assertFalse(o.vm.closeReassessment())
        assertFalse(o.vm.startRemainingStudy(2))
        assertFalse(o.vm.startReassessment(2))
        o.server.gate!!.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, o.server.commits.size)
        assertEquals(shown.input, o.server.commits.single().first)
        assertEquals(shown.preview.rows, o.server.commits.single().second)
        val done = o.vm.reassessment.value as ReassessmentState.Done
        assertEquals(MoveSave.Saved, done.save)
        assertEquals(shown.preview, done.preview)
        val saved = restored(o)
        assertEquals(PlanRecovery.Ready, saved.plans[0].recovery)
        assertEquals(PlanRecovery.Ready, saved.plans[0].recovery)
        assertEquals(shown.preview.rows.associateBy { it.scheduleId }, saved.plans[0].current.schedules.associateBy { it.scheduleId })
        assertEquals(saved.plans, o.vm.plans.value)
        assertEquals(b, saved.plans[1])
        // Returning shows the week of the first changed session, here the third preparation week.
        val focus = firstChangedScheduleId(done.preview)!!
        assertTrue(saved.plans[0].current.schedules.single { it.scheduleId == focus }.startDate.startsWith("2026/11/24/18/00"))
        assertTrue(o.vm.closeReassessment())
        assertNull(o.vm.reassessment.value)
        assertEquals(1, o.server.commits.size)
        assertEquals(1, o.server.previews.size)
        assertEquals(0, o.calendar.uses)
    }

    @Test fun choosingNotToChangeSendsNothingAndKeepsThePlan() = runTest(dispatcher) {
        val o = open()
        preview(o)
        assertTrue(o.vm.closeReassessment())
        advanceUntilIdle()
        assertNull(o.vm.reassessment.value)
        assertFalse(o.vm.confirmReassessment())
        assertEquals(0, o.server.commits.size)
        assertEquals(0, o.store.count)
        assertEquals(listOf(a, b), o.vm.plans.value)
        assertNull(o.vm.reassessmentBlockReason(1))
    }

    @Test fun expiredPreviewSavesNothingAndPreviewsAgainOnlyWhenAsked() = runTest(dispatcher) {
        val o = open()
        val shown = preview(o)
        // A revisable session starts before the learner confirms.
        clock = LocalDateTime.of(2026, 11, 24, 18, 30)
        o.server.now = clock
        assertTrue(o.vm.confirmReassessment())
        advanceUntilIdle()
        val stopped = o.vm.reassessment.value as ReassessmentState.Stopped
        assertEquals(ReassessmentStop.Expired, stopped.reason)
        assertTrue(stopped.canPreviewAgain)
        assertEquals(shown.input.answers, stopped.input.answers)
        assertEquals(1, o.server.commits.size)
        assertEquals(1, o.server.previews.size)
        assertEquals(listOf(a, b), o.vm.plans.value)
        val saved = restored(o)
        assertEquals(listOf(a, b), saved.plans)
        assertEquals(PlanRecovery.Ready, saved.plans[0].recovery)
        assertTrue(o.vm.previewReassessment())
        advanceUntilIdle()
        assertEquals(2, o.server.previews.size)
        assertEquals(shown.input.answers, o.server.previews[1].answers)
        assertEquals(clock, o.server.previews[1].replanFrom)
        val again = o.vm.reassessment.value as ReassessmentState.Preview
        assertEquals(listOf("study-4", "study-5"), again.preview.sessions.map { it.original.scheduleId })
        assertEquals(1, o.server.commits.size)
    }

    @Test fun resultThatDiffersFromThePreviewIsUncertainAndNeedsAnExplicitReload() = runTest(dispatcher) {
        val alterations = listOf<(List<ScheduleRow>) -> List<ScheduleRow>>(
            { rows -> rows.map { if (it.scheduleId == "study-16") it.copy(topic = if (it.topic == LocalTopics.MACROS) LocalTopics.CHARTS_AND_ANALYSIS else LocalTopics.MACROS) else it } },
            { rows -> rows.map { if (it.scheduleId == "study-2") it.copy(topic = LocalTopics.MACROS) else it } },
            { rows -> rows.filterNot { it.scheduleId == "study-16" } },
        )
        for (alter in alterations) {
            val o = open()
            o.server.alter = alter
            preview(o)
            assertTrue(o.vm.confirmReassessment())
            advanceUntilIdle()
            val stopped = o.vm.reassessment.value as ReassessmentState.Stopped
            assertEquals(ReassessmentStop.CommitFailed, stopped.reason)
            assertTrue(stopped.error is ApiError.InvalidResponse)
            assertFalse(stopped.canPreviewAgain)
            assertEquals(a.current, o.vm.plans.value[0].current)
            assertEquals(PlanRecovery.ReloadRequired, o.vm.plans.value[0].recovery)
            assertEquals(PlanRecovery.ReloadRequired, restored(o).plans[0].recovery)
            assertFalse(o.vm.previewReassessment())
            assertTrue(o.vm.closeReassessment())
            assertNotNull(o.vm.reassessmentBlockReason(1))
            assertFalse(o.vm.startReassessment(1))
            assertFalse(o.vm.startRemainingStudy(1))
            o.other.rows = a.current.schedules
            assertTrue(o.vm.selectPlan(1))
            assertTrue(o.vm.reloadPlan())
            advanceUntilIdle()
            assertEquals(PlanRecovery.Ready, restored(o).plans[0].recovery)
            assertEquals(1, o.server.commits.size)
            assertTrue(o.vm.startReassessment(1))
        }
    }

    @Test fun definiteRejectionOfTheUpdateClearsTheGateWithoutChangingThePlan() = runTest(dispatcher) {
        for (outcome in listOf(ReassessmentOutcome.Rejected(ReassessmentFailure.NotEnoughTime), ReassessmentOutcome.ServerNeedsUpdate,
            ReassessmentOutcome.Failure(ApiError.RateLimited))) {
            val o = open()
            o.server.commitFailure = outcome
            preview(o)
            assertTrue(o.vm.confirmReassessment())
            advanceUntilIdle()
            assertEquals(listOf(a, b), o.vm.plans.value)
            val saved = restored(o)
            assertEquals(listOf(a, b), saved.plans)
            assertEquals(PlanRecovery.Ready, saved.plans[0].recovery)
            assertTrue(o.vm.closeReassessment())
            assertNull(o.vm.reassessmentBlockReason(1))
        }
    }

    @Test fun failedSaveStaysUnsavedUntilLocalRetryAndAFreshProcessRestoresTheUpdate() = runTest(dispatcher) {
        val o = open()
        o.store.fail += 2
        val shown = preview(o)
        assertTrue(o.vm.confirmReassessment())
        advanceUntilIdle()
        assertEquals(MoveSave.Unsaved, (o.vm.reassessment.value as ReassessmentState.Done).save)
        assertEquals(PlanStorage.SaveFailure, o.vm.storage.value)
        // The updated rows are shown; the device still holds the recovery gate.
        assertEquals(shown.preview.rows, o.vm.plans.value[0].current.schedules)
        assertEquals(PlanRecovery.ReloadRequired, restored(o).plans[0].recovery)
        assertFalse(o.vm.startReassessment(2))
        assertFalse(o.vm.startRemainingStudy(2))
        o.vm.retrySave()
        advanceUntilIdle()
        assertEquals(MoveSave.Saved, (o.vm.reassessment.value as ReassessmentState.Done).save)
        assertEquals(1, o.server.commits.size)
        assertEquals(1, o.server.previews.size)
        val saved = restored(o)
        assertEquals(PlanRecovery.Ready, saved.plans[0].recovery)
        assertEquals(a.checkIns, saved.plans[0].checkIns)
        assertEquals(a.keptConflicts, saved.plans[0].keptConflicts)
        assertTrue(o.vm.closeReassessment())

        val fresh = open(disk = o.store.disk)
        assertEquals(saved.plans, fresh.vm.plans.value)
        assertEquals(shown.preview.rows.associateBy { it.scheduleId }, fresh.vm.plans.value[0].current.schedules.associateBy { it.scheduleId })
        assertEquals(PlanRecovery.Ready, fresh.vm.plans.value[0].recovery)
        assertNull(fresh.vm.reassessment.value)
        assertEquals(0, fresh.server.commits.size + fresh.server.previews.size)
        assertEquals(0, fresh.store.count)
    }

    @Test fun failedGateSaveSendsNothingAndNeedsAFreshPreview() = runTest(dispatcher) {
        val o = open()
        o.store.fail += 1
        preview(o)
        assertTrue(o.vm.confirmReassessment())
        advanceUntilIdle()
        assertTrue(o.vm.reassessment.value is ReassessmentState.Invalid)
        assertEquals(0, o.server.commits.size)
        assertEquals(listOf(a, b), restored(o).plans)
        o.vm.retrySave()
        advanceUntilIdle()
        assertEquals(PlanRecovery.Ready, restored(o).plans[0].recovery)
        assertFalse(o.vm.confirmReassessment())
        assertTrue(o.vm.closeReassessment())
        assertEquals(0, o.server.commits.size)
    }

    @Test fun lostResponseBlocksAResendAfterRestartAndReloadAdoptsTheServerRows() = runTest(dispatcher) {
        val o = open()
        o.server.gate = CompletableDeferred()
        val shown = preview(o)
        assertTrue(o.vm.confirmReassessment())
        runCurrent()
        assertEquals(1, o.server.commits.size)
        o.vm.viewModelScope.cancel()

        val fresh = open(disk = o.store.disk)
        assertEquals(PlanRecovery.ReloadRequired, fresh.vm.plans.value[0].recovery)
        assertFalse(fresh.vm.startReassessment(1))
        assertEquals(0, fresh.server.commits.size + fresh.server.previews.size)
        fresh.other.rows = shown.preview.rows
        assertTrue(fresh.vm.selectPlan(1))
        assertTrue(fresh.vm.reloadPlan())
        advanceUntilIdle()
        assertEquals(1, fresh.other.reloads)
        val saved = restored(fresh)
        assertEquals(PlanRecovery.Ready, saved.plans[0].recovery)
        assertEquals(PlanRecovery.Ready, saved.plans[0].recovery)
        assertEquals(shown.preview.rows, saved.plans[0].current.schedules)
        assertEquals(a.checkIns, saved.plans[0].checkIns)
        assertEquals(0, fresh.server.commits.size)
    }

    @Test fun reportedFutureRowIsProtectedAndKeepsItsIdTimeTopicAndReport() = runTest(dispatcher) {
        val o = open()
        preview(o)
        assertTrue(o.vm.confirmReassessment())
        advanceUntilIdle()
        assertEquals(setOf("study-2"), o.server.commits.single().first.keep)
        val plan = restored(o).plans[0]
        assertEquals(row(2, 16), plan.current.schedules.single { it.scheduleId == "study-2" })
        assertEquals(row(1, 12), plan.current.schedules.single { it.scheduleId == "study-1" })
        assertEquals(StudyCheckIn(StudyCheckInStatus.Partly, 20), plan.checkInFor("study-2"))
        assertEquals(a.checkIns, plan.checkIns)
    }

    @Test fun newPiecesGetFreshIdsAboveRetainedKeysAndInheritNothing() = runTest(dispatcher) {
        val o = open()
        preview(o)
        assertTrue(o.vm.confirmReassessment())
        advanceUntilIdle()
        assertEquals("study-16", o.server.commits.single().first.firstNewStudyId)
        val fresh = newIds(o)
        assertTrue(fresh.isNotEmpty())
        assertTrue(fresh.all { it.removePrefix("study-").toInt() >= 16 })
        val plan = restored(o).plans[0]
        assertTrue(fresh.none { it in plan.checkIns || it in plan.keptConflicts })
        // Reports and acknowledgments stay under their original keys, even for replaced rows.
        assertEquals(a.keptConflicts, plan.keptConflicts)
        assertEquals(a.checkIns, plan.checkIns)
    }

    @Test fun confirmedUpdateNeverReadsOrWritesTheCalendar() = runTest(dispatcher) {
        val o = open()
        preview(o)
        assertTrue(o.vm.confirmReassessment())
        advanceUntilIdle()
        assertTrue(o.vm.reassessment.value is ReassessmentState.Done)
        assertTrue(o.vm.closeReassessment())
        advanceUntilIdle()
        assertEquals(0, o.calendar.uses)
        assertTrue(com.swpp.licenseplanner.ui.screens.REASSESSMENT_CALENDAR_NOTE.contains("예전 주제 그대로"))
    }

    @Test fun demoUpdateIsComputedAndSavedLocallyWithoutARecoveryGate() = runTest(dispatcher) {
        val demo = livePlan(demo = true)
        val o = open(PlanSnapshot(listOf(demo)), repository = DemoReassessmentRepository(items) { clock })
        results(o)
        assertTrue(o.vm.previewReassessment())
        advanceUntilIdle()
        val shown = o.vm.reassessment.value as ReassessmentState.Preview
        assertTrue(o.vm.confirmReassessment())
        advanceUntilIdle()
        assertEquals(MoveSave.Saved, (o.vm.reassessment.value as ReassessmentState.Done).save)
        assertEquals(1, o.store.count)
        val saved = restored(o)
        assertEquals(PlanRecovery.Ready, saved.plans[0].recovery)
        assertEquals(shown.preview.rows.associateBy { it.scheduleId }, saved.plans[0].current.schedules.associateBy { it.scheduleId })
        assertEquals(demo.checkIns, saved.plans[0].checkIns)
        assertEquals(0, o.calendar.uses)
    }
}
