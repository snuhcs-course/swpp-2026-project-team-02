package com.swpp.licenseplanner.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.view.KeyEvent
import androidx.test.platform.app.InstrumentationRegistry
import com.swpp.licenseplanner.data.model.ScheduleRow
import com.swpp.licenseplanner.data.ReloadOutcome
import com.swpp.licenseplanner.data.PlanStorageScope
import com.swpp.licenseplanner.domain.CERTIFICATION_NAME
import com.swpp.licenseplanner.domain.CurrentLevel
import com.swpp.licenseplanner.domain.CreationStep
import com.swpp.licenseplanner.domain.CreationDraft
import com.swpp.licenseplanner.domain.WindowSpec
import com.swpp.licenseplanner.domain.PlanGrouping
import com.swpp.licenseplanner.domain.PlanRecovery
import com.swpp.licenseplanner.domain.PlanSession
import com.swpp.licenseplanner.domain.Question
import com.swpp.licenseplanner.domain.StudyCheckIn
import com.swpp.licenseplanner.domain.StudyCheckInStatus
import com.swpp.licenseplanner.domain.UNKNOWN_LABEL
import com.swpp.licenseplanner.domain.Weekday
import com.swpp.licenseplanner.domain.WireDateTime
import com.swpp.licenseplanner.ui.screens.NOT_YET_REPORTABLE
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainFlowUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val testName = TestName()

    private fun conflictingCalendar(row: ScheduleRow, key: String = "occurrence"): com.swpp.licenseplanner.data.CalendarReadOutcome {
        val start = WireDateTime.parseOrNull(row.startDate)!!
        val end = WireDateTime.parseOrNull(row.endDate)!!
        val busy = row.copy(scheduleId = "71", topic = "외부 일정")
        return com.swpp.licenseplanner.data.CalendarReadOutcome.Loaded(com.swpp.licenseplanner.data.CalendarBusySnapshot(
            listOf(busy), 0, 0, listOf(com.swpp.licenseplanner.data.CalendarOccurrence(key, null, start, end, false)),
        ))
    }

    @Test
    fun contextualHelpOpensFromTheTopRightOnEachScreen() = withApp { host ->
        checkCornerHelp("시작 안내", "계획 시작하기")
        click("내 계획")
        checkCornerHelp("저장 안내", "저장된 계획")
        clickDescription("뒤로")

        startQuestions(host)
        answerAll(host)
        click("공부 시간 정하기")
        checkCornerHelp("공부 시간 안내", "공부 시간과 캘린더 읽기")
        compose.runOnIdle {
            for (day in Weekday.entries.take(5)) host.viewModel.setDuration(day, 120)
        }
        click("계획 만들기")
        waitForText("내 계획")
        host.awaitSaved()
        checkCornerHelp("내보내기 안내", "캘린더에 일정 추가")
        click("처음으로", lazy = true)
        openPlanView()
        adjustShownPlan()
        checkCornerHelp("조정 안내", "놓친 일정 조정하기")
        compose.onNodeWithText("캘린더에 일정 추가").assertDoesNotExist()
    }

    private fun checkCornerHelp(description: String, vararg headings: String) {
        val help = compose.onNode(hasContentDescription(description) and hasText("안내") and hasClickAction())
        val screen = compose.onRoot().getUnclippedBoundsInRoot()
        val bounds = help.assertIsDisplayed().getUnclippedBoundsInRoot()
        assertTrue("$description should sit at the right edge", screen.right - bounds.right <= 24.dp)
        assertTrue("$description should sit near the top", bounds.top - screen.top <= 64.dp)
        assertTrue("$description should stay compact", bounds.right - bounds.left <= 96.dp)
        help.performClick()
        for (heading in headings) {
            val matcher = hasText(heading) and hasAnyAncestor(isDialog())
            scrollTo(matcher)
            compose.onNode(matcher).assertIsDisplayed()
        }
        click("닫기")
    }

    @Test
    fun savedPlanDetailsExposeContextualActionsAndCalendarRecovery() = withApp { host ->
        val plan = seedFullPlanForFreeTime(host)
        assertPlanViewActions(plan, inputsHeld = false)
        host.capture("plan-view-actions")
        adjustShownPlan()
        assertNoPlanActionsOnAdjustment(plan)
        val before = host.snapshot()
        val planning = host.planningCalls.get()
        compose.onNodeWithText("선택한 일정 조정하기").assertDoesNotExist()
        compose.onNodeWithText("시간 직접 옮기기").assertDoesNotExist()
        compose.onNodeWithText("이 계획 다시 만들기").assertDoesNotExist()
        host.capture("plan-detail-unselected")
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("확인 완료 · 겹치는 일정 없음", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("캘린더 시간 확인").assertDoesNotExist()
        compose.onNodeWithText("캘린더 권한 허용").assertDoesNotExist()
        compose.onNodeWithText("다시 확인").assertDoesNotExist()

        host.calendarOutcome = com.swpp.licenseplanner.data.CalendarReadOutcome.PermissionMissing
        compose.runOnIdle { host.viewModel.leaveForeground(); host.viewModel.enterForeground(true) }
        waitForText("캘린더 권한 허용")
        compose.onNodeWithText("다시 확인").assertDoesNotExist()

        host.calendarOutcome = com.swpp.licenseplanner.data.CalendarReadOutcome.Unavailable
        compose.runOnIdle { host.viewModel.leaveForeground(); host.viewModel.enterForeground(true) }
        waitForText("다시 확인")
        compose.onNodeWithText("캘린더 권한 허용").assertDoesNotExist()
        val reads = host.calendarReads.get()
        host.calendarOutcome = com.swpp.licenseplanner.data.CalendarReadOutcome.Loaded(
            com.swpp.licenseplanner.data.CalendarBusySnapshot(emptyList(), 0, 0),
        )
        click("다시 확인")
        compose.waitUntil(10_000) {
            host.calendarReads.get() == reads + 1 && host.viewModel.noticeCheckFeedback.value != null
        }
        show("확인 완료 · 겹치는 일정 없음")
        click("닫기")
        compose.onNodeWithText("다시 확인").assertDoesNotExist()
        assertTrue(host.viewModel.notices.value.getValue(1).checkedAt != null)
        host.capture("calendar-refresh-complete")
        val session = PlanGrouping.sessions(before.plans.single().current.schedules).first { !it.isExternal }
        clickSession(session)
        compose.runOnIdle { assertEquals(session.scheduleId, host.viewModel.adjustment.value.failedScheduleId) }
        compose.onNodeWithText(sessionLabel(session)).assertIsSelected()
        host.capture("plan-detail-selected")
        compose.onNodeWithText("선택한 일정 조정하기").assertIsEnabled()
        compose.onNodeWithText("시간 직접 옮기기").assertIsEnabled()
        assertNoPlanActionsOnAdjustment(plan)
        compose.onNodeWithText(sessionLabel(session)).assertIsSelected()
        assertEquals(before, host.snapshot())
        assertEquals(planning, host.planningCalls.get())
        assertEquals(0, host.adjustmentCalls.get())
        assertEquals(0, host.calendarWrites.get())
    }

    @Test
    fun newAndReopenedPlansShowTheSameHeadingAndActions() = withApp { host ->
        generatePlan(host)
        val plan = host.snapshot().plans.single()
        val weeks = PlanGrouping.weeks(plan.current.schedules)
        val session = PlanGrouping.sessions(plan.current.schedules).first { !it.isExternal }
        fun assertPlanView() {
            awaitPlanView()
            show("계획 1 · $CERTIFICATION_NAME")
            compose.onNodeWithText("필기 시험 준비 · 준비 ", substring = true).assertIsDisplayed()
            showWeek(weeks.first().monday, 0, weeks.size)
            compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)).assertCountEquals(0)
            assertSession(session, selectable = false)
            scrollTo(hasContentDescription(reportAction(session, edit = false)), lazy = true)
            compose.onNodeWithContentDescription(reportAction(session, edit = false)).assertIsEnabled()
            assertPlanViewActions(plan, inputsHeld = true)
        }
        assertPlanView()
        host.capture("plan-view-new")
        pressBack()
        waitForText("무엇을 할까요?")
        show("저장된 계획 1개가 있어요.")
        openPlanView(1)
        assertPlanView()
        host.capture("plan-view-reopened")
        clickDescription("뒤로")
        awaitPlanList()
        pressBack()
        waitForText("무엇을 할까요?")
        assertEquals(listOf(plan), host.snapshot().plans)
        assertEquals(1, host.planningCalls.get())
        assertEquals(0, host.adjustmentCalls.get())
    }

    @Test
    fun scheduleAdjustmentOpensTheViewedPlanAndBackReturnsToItsWeek() = withApp { host ->
        generatePlan(host)
        val plan = host.snapshot().plans.single()
        host.seedSnapshot(com.swpp.licenseplanner.data.PlanSnapshot(listOf(plan, plan.copy(ordinal = 2))))
        host.recreate()
        val weeks = PlanGrouping.weeks(plan.current.schedules)
        openPlanView(2)
        clickDescription("다음 주")
        showWeek(weeks[1].monday, 1, weeks.size)
        adjustShownPlan()
        show("계획 2 · $CERTIFICATION_NAME")
        compose.runOnIdle { assertEquals(2, host.viewModel.adjustment.value.planOrdinal) }
        assertNoPlanActionsOnAdjustment(plan)
        host.capture("adjustment-from-plan-view")
        pressBack()
        awaitPlanView()
        show("계획 2 · $CERTIFICATION_NAME")
        showWeek(weeks[1].monday, 1, weeks.size)
        clickDescription("뒤로")
        awaitPlanList()
        pressBack()
        waitForText("무엇을 할까요?")
        assertEquals(0, host.adjustmentCalls.get())
    }

    @Test
    fun editingInputsAddsANewPlanAndLeavesTheViewedPlanUnchanged() = withApp { host ->
        generatePlan(host)
        val first = host.snapshot().plans.single()
        click(EDIT_INPUTS, lazy = true)
        show("매주 기본 시간")
        compose.onNode(hasTestTag("study-duration-${Weekday.MONDAY.key}")).assertTextEquals("18:00 – 19:00 · 60분")
        setWeekdayMinutes(Weekday.SATURDAY)
        click("계획 만들기")
        awaitPlanView()
        host.awaitSaved()
        show("계획 2 · $CERTIFICATION_NAME")
        show(EDIT_INPUTS, lazy = true)
        val saved = host.snapshot()
        assertEquals(listOf(1, 2), saved.plans.map { it.ordinal })
        assertEquals(first, saved.plans[0])
        assertEquals(2, host.planningCalls.get())
        host.capture("plan-view-from-edited-inputs")
        pressBack()
        waitForText("무엇을 할까요?")
        show("저장된 계획 2개가 있어요.")
        host.recreate()
        for (ordinal in listOf(1, 2)) {
            openPlanView(ordinal)
            show("처음으로", lazy = true)
            compose.onNodeWithText(EDIT_INPUTS).assertDoesNotExist()
            pressBack()
            awaitPlanList()
            pressBack()
            waitForText("무엇을 할까요?")
        }
        assertEquals(saved, host.snapshot())
        assertEquals(2, host.planningCalls.get())
    }

    @Test
    fun foregroundKeepSurvivesRestoreAndChangedOccurrenceAndStudyIntervalWarnAgain() = withApp { host ->
        generatePlan(host)
        val original = host.snapshot().plans.single()
        val row = original.current.schedules.first { it.scheduleId.startsWith("study-") }
        val report = StudyCheckIn(StudyCheckInStatus.Done, 20)
        val a = original.copy(checkIns = mapOf(row.scheduleId to report))
        val start = WireDateTime.parseOrNull(row.startDate)!!
        val bRow = row.copy(startDate = WireDateTime.format(start.plusHours(2)), endDate = WireDateTime.format(start.plusHours(3)))
        val b = original.copy(ordinal = 2, current = original.current.copy(schedules = listOf(bRow)))
        val draft = CreationDraft(host.viewModel.form.value, CreationStep.QUESTIONS, 2)
        val seeded = com.swpp.licenseplanner.data.PlanSnapshot(listOf(a, b), creationDraft = draft)
        host.seedSnapshot(seeded)
        host.calendarOutcome = conflictingCalendar(row)
        host.recreate()
        waitForText("계획 1 · 캘린더와 겹치는 공부 1개")
        val calls = host.adjustmentCalls.get()
        val planning = host.planningCalls.get()
        openPlanView(1)
        show("캘린더와 겹치는 공부 시간")
        host.capture("current-conflict-notice")
        click("그대로 두기")
        host.awaitSaved()
        show("그대로 두기를 기기에 저장했어요.")
        val kept = host.snapshot()
        assertEquals(b, kept.plans[1])
        assertEquals(draft, kept.creationDraft)
        assertEquals(a.checkIns, kept.plans[0].checkIns)
        assertTrue(kept.plans[0].keptConflicts.getValue(row.scheduleId).isNotEmpty())
        assertEquals(calls, host.adjustmentCalls.get())
        assertEquals(planning, host.planningCalls.get())
        assertEquals(0, host.calendarWrites.get())
        host.recreate()
        waitForText("계획 1 · 캘린더와 겹치는 공부 0개")
        host.calendarOutcome = conflictingCalendar(row, "edited-occurrence")
        compose.runOnIdle { host.viewModel.leaveForeground(); host.viewModel.enterForeground(true) }
        waitForText("계획 1 · 캘린더와 겹치는 공부 1개")
        host.calendarOutcome = conflictingCalendar(row)
        val changed = kept.copy(plans = kept.plans.map { plan -> if (plan.ordinal != 1) plan else plan.copy(current = plan.current.copy(
            schedules = plan.current.schedules.map { if (it.scheduleId == row.scheduleId) it.copy(
                startDate = WireDateTime.format(start.plusMinutes(1)), endDate = WireDateTime.format(start.plusHours(1).plusMinutes(1))) else it })) })
        host.seedSnapshot(changed)
        host.recreate()
        waitForText("계획 1 · 캘린더와 겹치는 공부 1개")
        assertEquals(kept.plans[0].keptConflicts, host.snapshot().plans[0].keptConflicts)
        assertEquals(calls, host.adjustmentCalls.get())
    }

    @Test
    fun unsavedKeepOnAnotherPlanBlocksRegenerationUntilLocalRetryWithoutResumingIt() = withApp { host ->
        generatePlan(host)
        val original = host.snapshot().plans.single()
        val row = original.current.schedules.first { it.scheduleId.startsWith("study-") }
        val draft = CreationDraft(host.viewModel.form.value, CreationStep.QUESTIONS, 2)
        host.seedSnapshot(com.swpp.licenseplanner.data.PlanSnapshot(listOf(original, original.copy(ordinal = 2)), creationDraft = draft))
        host.calendarOutcome = conflictingCalendar(row)
        host.recreate()
        waitForText("계획 2 · 캘린더와 겹치는 공부 1개")
        openPlanView(2)
        host.failSaves.set(true)
        click("그대로 두기")
        compose.waitUntil(10_000) { host.viewModel.storage.value == PlanStorage.SaveFailure }
        show("그대로 두기 · 저장 안 됨. 화면의 다시 저장하기를 눌러 주세요.")
        pressBack()
        awaitPlanList()
        click("계획 1 · $CERTIFICATION_NAME")
        awaitPlanView()
        show("이 계획 다시 만들기", lazy = true)
        compose.onNodeWithText("이 계획 다시 만들기").assertIsNotEnabled()
        val reads = host.calendarReads.get()
        val planning = host.planningCalls.get()
        compose.runOnIdle {
            assertFalse(host.viewModel.startRegeneration(1))
            assertFalse(host.viewModel.keepCurrentConflict(1, row.scheduleId))
            host.viewModel.closeKeepFeedback()
        }
        val export = "캘린더에 ${original.studyCount}개 일정 추가"
        show("기기에 계획을 저장하지 못했어요", lazy = true)
        compose.onNodeWithText("다시 저장하기").assertIsEnabled()
        host.capture("plan-view-save-failed-alert")
        show("계획을 기기에 저장한 뒤 내보낼 수 있어요", lazy = true)
        compose.onNodeWithText(export).assertIsNotEnabled()
        host.capture("plan-view-save-failed-actions")
        clickPlanAction("처음으로")
        show("기기에 계획을 저장하지 못했어요")
        compose.onNodeWithText("다시 저장하기").assertIsEnabled()
        compose.onNodeWithText("계획 생성").assertIsNotEnabled()
        host.capture("home-save-failed")
        openPlanView(1)
        click("다시 저장하기")
        compose.waitUntil(10_000) { host.viewModel.storage.value == PlanStorage.SaveFailure }
        host.failSaves.set(false)
        click("다시 저장하기")
        host.awaitSaved()
        show(export, lazy = true)
        compose.onNodeWithText(export).assertIsEnabled()
        host.capture("plan-view-export-after-save-retry")
        assertEquals(reads, host.calendarReads.get())
        assertEquals(planning, host.planningCalls.get())
        assertEquals(draft, host.snapshot().creationDraft)
        assertNull(host.viewModel.calendarConflict.value)
        assertNull(host.viewModel.keepFeedback.value)
        assertTrue(host.snapshot().plans[1].keptConflicts.isNotEmpty())
        assertEquals(0, host.adjustmentCalls.get())
        assertEquals(0, host.calendarWrites.get())
    }

    @Test
    fun proposedManualTimeNeedsExternalAndWindowConsentAndItsCombinedSaveRetriesOnlyLocally() = withApp { host ->
        generatePlan(host)
        val original = host.snapshot().plans.single()
        val row = original.current.schedules.first { it.scheduleId.startsWith("study-") }
        val start = WireDateTime.parseOrNull(row.startDate)!!.plusDays(1).withHour(14)
        val duration = Duration.between(WireDateTime.parseOrNull(row.startDate), WireDateTime.parseOrNull(row.endDate))
        val proposed = row.copy(startDate = WireDateTime.format(start), endDate = WireDateTime.format(start.plus(duration)))
        val external = proposed.copy(scheduleId = "external-991", topic = "")
        val report = StudyCheckIn(StudyCheckInStatus.Done, 20)
        val a = original.copy(current = original.current.copy(schedules = original.current.schedules + external), checkIns = mapOf(row.scheduleId to report))
        val b = original.copy(ordinal = 2)
        val draft = CreationDraft(host.viewModel.form.value, CreationStep.QUESTIONS, 2)
        host.seedSnapshot(com.swpp.licenseplanner.data.PlanSnapshot(listOf(a, b), creationDraft = draft))
        host.calendarOutcome = conflictingCalendar(proposed)
        host.recreate()
        openPlanView(1)
        adjustShownPlan()
        compose.runOnIdle {
            assertTrue(host.viewModel.startCheckIn(2, row.scheduleId))
            host.viewModel.chooseCheckInStatus(StudyCheckInStatus.Done)
            host.viewModel.setCheckInMinutes("35")
            assertTrue(host.viewModel.saveCheckIn())
        }
        host.awaitSaved()
        val savedB = host.snapshot().plans[1]
        val savedReportFeedback = host.viewModel.checkIn.value
        compose.runOnIdle {
            host.viewModel.selectFailedSession(row.scheduleId)
            assertTrue(host.viewModel.startManualMove())
            host.viewModel.setManualStart(start)
        }
        click("이 시간으로 옮기기")
        waitForText("겹쳐도 이 시간으로 옮기기")
        assertEquals(0, host.adjustmentCalls.get())
        host.capture("manual-overlap-review")
        click("다른 시간 선택")
        assertEquals(0, host.adjustmentCalls.get())
        click("이 시간으로 옮기기")
        click("겹쳐도 이 시간으로 옮기기")
        waitForText("설정한 공부 시간 밖이에요.")
        assertEquals(1, host.adjustmentCalls.get())
        assertTrue(host.adjustmentInputs.single().manualMove!!.confirmExternalOverlap)
        assertFalse(host.adjustmentInputs.single().manualMove!!.confirmOutsideAvailability)
        host.failSaves.set(true)
        click("이번 한 번만 옮기기")
        compose.waitUntil(10_000) { host.viewModel.storage.value == PlanStorage.SaveFailure }
        assertEquals(2, host.adjustmentCalls.get())
        assertTrue(host.adjustmentInputs.last().manualMove!!.confirmExternalOverlap)
        assertTrue(host.adjustmentInputs.last().manualMove!!.confirmOutsideAvailability)
        val reads = host.calendarReads.get()
        assertEquals(savedReportFeedback, host.viewModel.checkIn.value)
        host.capture("manual-keep-unsaved")
        host.failSaves.set(false)
        click("다시 저장하기")
        host.awaitSaved()
        assertEquals(reads, host.calendarReads.get())
        assertEquals(2, host.adjustmentCalls.get())
        val saved = host.snapshot()
        assertEquals(proposed, saved.plans[0].current.schedules.single { it.scheduleId == row.scheduleId })
        assertEquals(report, saved.plans[0].checkIns[row.scheduleId])
        assertEquals(savedB, saved.plans[1])
        assertEquals(savedReportFeedback, host.viewModel.checkIn.value)
        assertEquals(draft, saved.creationDraft)
        assertEquals(MoveSave.Saved, host.viewModel.keepFeedback.value!!.save)
        assertEquals(0, host.calendarWrites.get())
        host.calendarOutcome = com.swpp.licenseplanner.data.CalendarReadOutcome.Loaded(com.swpp.licenseplanner.data.CalendarBusySnapshot(emptyList(), 0, 0))
        host.recreate()
        assertEquals(saved, host.snapshot())
        assertEquals(2, host.adjustmentCalls.get())
    }

    @Test
    fun cancellingAnInFlightCalendarCheckKeepsInputsAndRequiresANewGenerationAction() = withApp { host ->
        startQuestions(host)
        answerAll(host)
        click("공부 시간 정하기")
        setWeekdayMinutes(Weekday.entries.first { it.dayOfWeek.value == 1 })
        val form = host.viewModel.form.value
        val pendingRead = CompletableDeferred<Unit>()
        host.holdCalendarReads.set(pendingRead)
        try {
            click("계획 만들기")
            waitForText("캘린더 시간을 확인하는 중이에요")
            compose.waitUntil(10_000) { host.calendarReads.get() == 1 }
            click("취소")
            waitForText("매주 기본 시간")
            assertEquals(form, host.viewModel.form.value)
            compose.runOnIdle { pendingRead.complete(Unit) }
            compose.waitForIdle()
            assertNull(host.viewModel.calendarConflict.value)
            assertEquals(0, host.planningCalls.get())
            assertTrue(host.snapshot().plans.isEmpty())
        } finally {
            host.holdCalendarReads.set(null)
            pendingRead.complete(Unit)
        }

        for (day in Weekday.entries.filter { it.dayOfWeek.value in 2..5 }) setWeekdayMinutes(day)
        click("계획 만들기")
        waitForText("내 계획")
        host.awaitSaved()
        assertEquals(3, host.calendarReads.get())
        assertEquals(1, host.planningCalls.get())
        assertEquals(1, host.snapshot().plans.size)
    }

    @Test
    fun importedConflictsGenerateWithoutAnotherReviewAndStayOutsideStudySessions() = withApp { host ->
        startQuestions(host)
        answerAll(host)
        click("공부 시간 정하기")
        for (day in Weekday.entries.filter { it.dayOfWeek.value <= 5 }) setWeekdayMinutes(day)
        val row = ScheduleRow("71", "2026/11/16/18/00", "2026/11/16/19/00", "외부 일정")
        host.calendarOutcome = conflictingCalendar(row)
        click("캘린더 일정 불러오기")
        compose.waitUntil(10_000) { host.viewModel.calendarRead.value is CalendarReadState.Loaded }
        show("반영한 일정")
        show("1개")
        click("계획 만들기")
        waitForText("내 계획")
        host.awaitSaved()
        compose.onAllNodesWithText("공부 시간과 캘린더가 겹쳐요").assertCountEquals(0)
        assertNull(host.viewModel.calendarConflict.value)
        assertEquals(1, host.planningCalls.get())
        assertEquals(listOf(row), host.planningRequests.single().busySchedules)
        assertTrue(host.calendarReads.get() >= 3)
        val start = WireDateTime.parseOrNull(row.startDate)!!
        val end = WireDateTime.parseOrNull(row.endDate)!!
        val sessions = PlanGrouping.sessions(host.snapshot().plans.single().current.schedules).filter { !it.isExternal }
        assertTrue(sessions.isNotEmpty())
        assertTrue(sessions.none { it.start < end && start < it.end })
        assertEquals(0, host.calendarWrites.get())
        host.capture("imported-conflict-plan")
    }

    @Test
    fun freshGenerationConflictReviewCancelAndChangedFinalObservationSendOnlyOnePlan() = withApp { host ->
        startQuestions(host)
        answerAll(host)
        click("공부 시간 정하기")
        for (day in Weekday.entries.filter { it.dayOfWeek.value <= 5 }) setWeekdayMinutes(day)
        val form = host.viewModel.form.value
        val row = ScheduleRow("71", "2026/11/16/18/00", "2026/11/16/19/00", "외부 일정")
        host.calendarOutcome = conflictingCalendar(row)
        click("계획 만들기")
        waitForText("공부 시간과 캘린더가 겹쳐요")
        assertEquals(0, host.planningCalls.get())
        assertEquals(0, host.calendarWrites.get())
        host.capture("generation-conflict-summary")
        click("하나씩 확인")
        show("설정한 공부 시간")
        assertTrue(host.viewModel.calendarConflict.value!!.conflicts.matches.all { it.target.scheduleId == null })
        host.capture("generation-conflict-details")
        click("공부 시간 수정")
        waitForText("매주 기본 시간")
        assertEquals(form, host.viewModel.form.value)
        assertEquals(0, host.planningCalls.get())
        click("계획 만들기")
        waitForText("공부 시간과 캘린더가 겹쳐요")
        host.calendarOutcome = conflictingCalendar(row, "edited-with-the-same-count")
        click("겹치지 않게 계획 만들기")
        compose.waitUntil(10_000) { host.viewModel.calendarConflict.value?.phase == ConflictPhase.Review }
        assertEquals(0, host.planningCalls.get())
        click("겹치지 않게 계획 만들기")
        waitForText("내 계획")
        host.awaitSaved()
        assertEquals(1, host.planningCalls.get())
        assertEquals(listOf(row), host.planningRequests.single().busySchedules)
        assertEquals(0, host.calendarWrites.get())
    }

    @Test
    fun exportAnywayRechecksChangedDataAndStillRequiresTheFinalCopyConfirmation() = withApp { host ->
        generatePlan(host)
        val snapshot = host.snapshot()
        val plan = snapshot.plans.single()
        host.calendarOutcome = conflictingCalendar(plan.current.schedules.first { it.scheduleId.startsWith("study-") })
        click("캘린더에 ${plan.studyCount}개 일정 추가", lazy = true)
        waitForText("캘린더 선택")
        click(host.destination.label)
        click("다음")
        waitForText("공부 시간과 캘린더가 겹쳐요")
        click("하나씩 확인")
        show("이 공부 시간 직접 옮기기")
        assertEquals(0, host.calendarWrites.get())
        click("요약으로")
        host.capture("export-conflict-summary")
        click("겹쳐도 내보내기")
        show("캘린더에 일정 추가")
        compose.onNodeWithText("다시 내보내면 일정이 중복될 수 있어요.", substring = true).performScrollTo().assertIsDisplayed()
        host.capture("export-final-confirmation")
        assertEquals(0, host.calendarWrites.get())
        host.calendarOutcome = conflictingCalendar(plan.current.schedules.first { it.scheduleId.startsWith("study-") }, "changed-final")
        click("${plan.studyCount}개 일정 추가")
        waitForText("공부 시간과 캘린더가 겹쳐요")
        assertEquals(0, host.calendarWrites.get())
        click("겹쳐도 내보내기")
        click("${plan.studyCount}개 일정 추가")
        waitForText("${plan.studyCount}개 일정을 ${host.destination.label}에 추가했어요")
        assertEquals(plan.studyCount, host.calendarWrites.get())
        assertEquals(1, host.planningCalls.get())
        assertEquals(snapshot, host.snapshot())
        click("확인")
        click("캘린더에 ${plan.studyCount}개 일정 추가", lazy = true)
        waitForText("캘린더 선택")
        click(host.destination.label)
        click("다음")
        waitForText("공부 시간과 캘린더가 겹쳐요")
        click("겹치지 않게 다시 만들기")
        show("이 계획을 다시 만들까요?")
        assertNull(host.viewModel.calendarExport.value)
        click("취소")
        assertEquals(1, host.planningCalls.get())
        assertEquals(plan.studyCount, host.calendarWrites.get())
    }

    @Test
    fun unavailableCalendarShowsUncheckedChoiceAndKeepsTheEarlierImportedSnapshot() = withApp { host ->
        startQuestions(host)
        answerAll(host)
        click("공부 시간 정하기")
        for (day in Weekday.entries.filter { it.dayOfWeek.value <= 5 }) setWeekdayMinutes(day)
        val row = ScheduleRow("71", "2026/11/16/18/00", "2026/11/16/19/00", "외부 일정")
        host.calendarOutcome = conflictingCalendar(row)
        click("캘린더 일정 불러오기")
        compose.waitUntil(10_000) { host.viewModel.calendarRead.value is CalendarReadState.Loaded }
        host.calendarOutcome = com.swpp.licenseplanner.data.CalendarReadOutcome.Unavailable
        click("계획 만들기")
        waitForText("캘린더 확인 안 됨")
        show("이전에 가져온 외부 일정 1개를 유지해요. 최신 정보가 아닐 수 있어요.")
        host.capture("calendar-unchecked-stale-import")
        assertEquals(0, host.planningCalls.get())
        click("확인 없이 계속")
        waitForText("내 계획")
        host.awaitSaved()
        assertEquals(listOf(row), host.planningRequests.single().busySchedules)
    }

    @Test
    fun replacementWaitsForAnotherPlansReportRetryAndPreservesIndependentDraftThroughFailureAndRestore() = withApp { host ->
        generatePlan(host)
        addEmptyWindowInLastWeek(host)
        val initial = host.snapshot().plans.single()
        val id = initial.current.schedules.first { it.scheduleId.startsWith("study-") }.scheduleId
        val report = StudyCheckIn(StudyCheckInStatus.Done, 20)
        val draft = CreationDraft(host.viewModel.form.value.copy(level = CurrentLevel.ADVANCED), CreationStep.QUESTIONS, 2)
        val seeded = com.swpp.licenseplanner.data.PlanSnapshot(listOf(initial.copy(checkIns = mapOf(id to report)), initial.copy(ordinal = 2)), creationDraft = draft)
        host.seedSnapshot(seeded)
        host.recreate()
        openPlanView(2)
        val session = PlanGrouping.sessions(initial.current.schedules).first { it.scheduleId == id }
        openReport(session, edit = false)
        click("못 했어요")
        host.failSaves.set(true)
        click("기록 저장하기")
        waitForText("공부 기록을 저장하지 못했어요")
        pressBack()
        awaitPlanView()
        pressBack()
        awaitPlanList()
        click("계획 1 · $CERTIFICATION_NAME")
        awaitPlanView()
        show("이 계획 다시 만들기", lazy = true)
        compose.onNodeWithText("이 계획 다시 만들기").assertIsNotEnabled()
        val reads = host.calendarReads.get()
        val calls = host.planningCalls.get()
        compose.runOnIdle {
            assertFalse(host.viewModel.startRegeneration(1))
            assertFalse(host.viewModel.confirmRegeneration())
            host.viewModel.retryGeneration()
        }
        assertEquals(reads, host.calendarReads.get())
        assertEquals(calls, host.planningCalls.get())
        assertEquals(seeded, host.snapshot())
        click("다시 저장하기")
        compose.waitUntil(10_000) { host.viewModel.storage.value == PlanStorage.SaveFailure }
        host.failSaves.set(false)
        click("다시 저장하기")
        host.awaitSaved()
        assertEquals(reads, host.calendarReads.get())
        assertEquals(calls, host.planningCalls.get())
        assertNull(host.viewModel.calendarConflict.value)
        val withReport = host.snapshot()
        assertEquals(draft, withReport.creationDraft)
        // Keep an unrelated saved movement result visible behind A's replacement.
        compose.runOnIdle {
            assertTrue(host.viewModel.selectPlan(2))
            host.viewModel.selectFailedSession(id)
            assertTrue(host.viewModel.submitAdjustment())
        }
        compose.waitUntil(10_000) { (host.viewModel.adjustment.value.state as? AdjustmentState.Success)?.save == MoveSave.Saved }
        host.awaitSaved()
        val feedback = host.viewModel.adjustment.value
        assertEquals(MoveSave.Saved, (feedback.state as AdjustmentState.Success).save)
        val committedB = host.snapshot().plans[1]
        host.failSaves.set(true)
        compose.runOnIdle { assertTrue(host.viewModel.startRegeneration(1)) }
        click("바꾸고 다시 만들기")
        waitForText("새 계획을 저장하지 못했어요")
        assertEquals(initial.checkIns + (id to report), host.snapshot().plans[0].checkIns)
        assertEquals(feedback, host.viewModel.adjustment.value)
        host.capture("replacement-save-failed")
        click("닫기")
        val hold = CompletableDeferred<Unit>()
        host.holdSaves.set(hold)
        click("다시 저장하기")
        compose.waitUntil(10_000) { host.viewModel.storage.value == PlanStorage.Saving }
        assertEquals(feedback, host.viewModel.adjustment.value)
        hold.complete(Unit)
        compose.waitUntil(10_000) { host.viewModel.storage.value == PlanStorage.SaveFailure }
        assertEquals(feedback, host.viewModel.adjustment.value)
        assertNull(host.viewModel.calendarConflict.value)
        host.failSaves.set(false)
        host.holdSaves.set(null)
        click("다시 저장하기")
        host.awaitSaved()
        val replacement = host.snapshot()
        assertEquals(listOf(1, 2), replacement.plans.map { it.ordinal })
        assertTrue(replacement.plans[0].checkIns.isEmpty())
        assertEquals(committedB, replacement.plans[1])
        assertEquals(draft, replacement.creationDraft)
        assertEquals(feedback, host.viewModel.adjustment.value)
        assertEquals(calls + 1, host.planningCalls.get())
        assertEquals(0, host.calendarWrites.get())
        host.recreate()
        assertEquals(replacement, host.snapshot())
        assertEquals(replacement.plans, host.viewModel.plans.value)
        assertEquals(draft, host.viewModel.committedDraft.value)
        assertEquals(calls + 1, host.planningCalls.get())
    }

    @Test
    fun multipleWindowsCanBeAddedReorderedAndRemovedWithoutLosingOtherEntries() = withApp { host ->
        startQuestions(host)
        answerAll(host)
        click("공부 시간 정하기")
        host.capture("availability-initial")
        clickDescription("공부 시간 안내")
        show("공부 시간과 캘린더 읽기")
        host.capture("availability-help")
        click("닫기")
        clickDescription("월요일 시간대 추가")
        assertTrue(host.viewModel.form.value.availability.getValue(Weekday.MONDAY).windows().isEmpty())
        click("확인")
        val first = host.viewModel.form.value
        clickDescription("월요일 시간대 추가")
        setWindowTime("study-window-end", 18)
        compose.onNodeWithText("확인").assertIsNotEnabled()
        show("시작 시간과 종료 시간을 다르게 정해 주세요.")
        assertEquals(first, host.viewModel.form.value)
        host.capture("window-equal-times")
        click("취소")
        assertEquals(first, host.viewModel.form.value)
        clickDescription("월요일 시간대 추가")
        setWindowTime("study-window-end", 20)
        click("확인")
        show("월요일 공부 시간이 겹쳐요.")
        compose.onNodeWithText("계획 만들기").assertIsNotEnabled()
        assertEquals(0, host.planningCalls.get())
        host.capture("window-overlap")
        compose.onNodeWithTag("study-duration-monday-1").performScrollTo().performClick()
        setWindowTime("study-window-start", 17)
        setWindowTime("study-window-end", 18)
        click("확인")
        assertEquals(listOf(LocalTime.of(17, 0), LocalTime.of(18, 0)), host.viewModel.form.value.availability.getValue(Weekday.MONDAY).windows().map { it.start })
        compose.onNodeWithText("계획 만들기").assertIsEnabled()
        clickDescription("월요일 시간대 추가")
        setWindowTime("study-window-start", 23)
        setWindowTime("study-window-end", 1)
        click("확인")
        show("23:00 – 다음 날 01:00 · 120분")
        compose.onNodeWithTag("study-duration-monday-2").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("월요일 시간대 3 삭제").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("study-duration-monday-1").performScrollTo()
            .assert(hasContentDescription("월요일 시간대 2, 18:00–19:00, 60분, 수정")).assertHasClickAction()
        compose.onNodeWithTag("study-duration-monday-2").performScrollTo()
            .assert(hasContentDescription("월요일 시간대 3, 23:00–다음 날 01:00, 120분, 수정")).assertHasClickAction()
        host.capture("three-windows-overnight")
        val third = host.viewModel.form.value.availability.getValue(Weekday.MONDAY).windows().last()
        clickDescription("월요일 시간대 2 삭제")
        assertEquals(listOf(LocalTime.of(17, 0), LocalTime.of(23, 0)), host.viewModel.form.value.availability.getValue(Weekday.MONDAY).windows().map { it.start })
        clickDescription("월요일 시간대 1 삭제")
        assertEquals(listOf(third), host.viewModel.form.value.availability.getValue(Weekday.MONDAY).windows())
        clickDescription("월요일 시간대 1 삭제")
        assertTrue(host.viewModel.form.value.availability.getValue(Weekday.MONDAY).windows().isEmpty())
        compose.onNodeWithText("계획 만들기").assertIsNotEnabled()
        assertEquals(0, host.planningCalls.get())
    }

    @Test
    fun windowEditorsKeepTheirWeekAndCancelBeforeChangingScope() = withApp { host ->
        startQuestions(host)
        answerAll(host)
        click("공부 시간 정하기")
        setWeekdayMinutes(Weekday.MONDAY)
        val base = host.viewModel.form.value.availability
        clickDescription("월요일 시간대 추가")
        setWindowTime("study-window-end", 19, 30)
        pressBack()
        assertEquals(base, host.viewModel.form.value.availability)
        clickDescription("다음 주")
        val second = host.viewModel.availabilityWeek.value!!
        click("이번 주 시간 바꾸기")
        compose.onNodeWithTag("study-duration-monday").performScrollTo().performClick()
        setWindowTime("study-window-end", 20)
        click("확인")
        assertEquals(base, host.viewModel.form.value.availability)
        assertEquals(120, host.viewModel.form.value.availabilityOverrides.getValue(second).getValue(Weekday.MONDAY).windows().single().durationMinutes)
        clickDescription("월요일 시간대 추가")
        val captured = host.viewModel.form.value
        compose.runOnIdle { host.viewModel.showAvailabilityWeek(second.plusWeeks(1)) }
        compose.onNodeWithText("공부 시간 입력").assertDoesNotExist()
        assertEquals(captured, host.viewModel.form.value)
        clickDescription("이전 주")
        clickDescription("월요일 시간대 1 삭제")
        assertTrue(host.viewModel.form.value.availabilityOverrides.getValue(second).values.all { it.windows().isEmpty() })
        show("이번 주 공부 안 함")
        clickDescription("이전 주")
        assertEquals(base, host.viewModel.form.value.availability)
        compose.onNodeWithTag("study-duration-monday").performScrollTo().assertTextEquals("18:00 – 19:00 · 60분")
        host.capture("window-week-scope")
        assertEquals(0, host.planningCalls.get())
    }

    private fun setWindowTime(field: String, hour: Int, minute: Int = 0) {
        compose.onNodeWithTag(field).performClick()
        val dialLabel = SemanticsMatcher.keyNotDefined(SemanticsProperties.Text)
        compose.onNodeWithContentDescription("Select hour", useUnmergedTree = true).performClick()
        compose.onNode(hasContentDescription("$hour hours") and dialLabel, useUnmergedTree = true).performClick()
        compose.onNodeWithContentDescription("Select minutes", useUnmergedTree = true).performClick()
        compose.onNode(hasContentDescription("$minute minutes") and dialLabel, useUnmergedTree = true).performClick()
        compose.onNodeWithTag("study-clock-confirm").performClick()
    }

    @Test
    fun newCreationWaitsForTheMissingPlanSaveAndItsLocalRetry() = withApp { host ->
        generatePlan(host)
        val saved = host.snapshot()
        val plan = saved.plans.single()
        val beforeReload = saved.copy(plans = listOf(plan.copy(
            isDemo = false,
            scheduleCsvFile = "owned-plan.csv",
            recovery = PlanRecovery.ReloadRequired,
            recoveryScheduleId = plan.current.schedules.first { it.scheduleId.startsWith("study-") }.scheduleId,
        )))
        host.storageScope = PlanStorageScope.of(demo = false, apiBaseUrl = "http://test.invalid/api/v1")
        host.seedSnapshot(beforeReload)
        host.recreate()
        openSavedPlanAdjustment()
        host.reloadOutcome = ReloadOutcome.Missing
        host.failSaves.set(true)
        val pending = CompletableDeferred<Unit>()
        host.holdSaves.set(pending)
        click("최신 계획 불러오기")
        compose.waitUntil("The missing-plan result waits for its local save", 10_000) {
            host.viewModel.storage.value == PlanStorage.Saving
        }
        compose.onNodeWithText("새 계획 만들기").assertIsNotEnabled().performClick()
        assertEquals(beforeReload, host.snapshot())
        val saves = host.saveCalls.get()
        pending.complete(Unit)
        waitForText("기기에 계획을 저장하지 못했어요")
        compose.onNodeWithText("새 계획 만들기").assertIsNotEnabled().performClick()
        compose.onNodeWithText("목록으로").assertIsEnabled()
        assertCreateAboveList()
        show("이 계획은 더 이상 조정할 수 없어요")
        assertEquals(beforeReload, host.snapshot())
        assertEquals(saves, host.saveCalls.get())
        assertEquals(1, host.planningCalls.get())
        assertEquals(0, host.adjustmentCalls.get())
        assertEquals(1, host.reloadCalls.get())
        host.capture("missing-plan-save-failed")

        click("목록으로")
        awaitPlanList()
        compose.onNodeWithText("새 계획 만들기").assertIsNotEnabled().performClick()
        show(PLAN_LIST_HEADING)
        host.capture("list-create-blocked-by-save-failure")
        assertEquals(beforeReload, host.snapshot())
        assertEquals(saves, host.saveCalls.get())
        click("계획 1 · $CERTIFICATION_NAME")
        awaitPlanView()
        show("이 계획은 더 이상 조정할 수 없어요")
        adjustShownPlan()
        show("이 계획은 더 이상 조정할 수 없어요")

        host.failSaves.set(false)
        host.holdSaves.set(null)
        click("다시 저장하기")
        host.awaitSaved()
        compose.onNodeWithText("새 계획 만들기").assertIsEnabled()
        assertCreateAboveList()
        assertEquals(PlanRecovery.Missing, host.snapshot().plans.single().recovery)
        assertEquals(saves + 1, host.saveCalls.get())
        assertEquals(1, host.reloadCalls.get())
        click("새 계획 만들기")
        waitForText("어떤 시험을 준비하나요?")
        assertEquals(1, host.planningCalls.get())
        assertEquals(0, host.adjustmentCalls.get())
    }

    @Test
    fun creationExitOffersTheSameChoicesForBothBackActionsAndLeavesDefaultsDirectly() = withApp { host ->
        click("계획 생성", substring = true)
        clickDescription("뒤로")
        show("무엇을 할까요?")
        compose.onNodeWithText("이어서 만들기").assertDoesNotExist()
        click("계획 생성", substring = true)
        click(CERTIFICATION_NAME)
        pressBack()
        show("입력한 내용을 어떻게 할까요?")
        click("계속 작성하기")
        show("어떤 시험을 준비하나요?")
        clickDescription("뒤로")
        show("입력한 내용을 어떻게 할까요?")
        pressBack()
        show("어떤 시험을 준비하나요?")
        assertTrue(host.snapshot().plans.isEmpty())
        assertNull(host.snapshot().creationDraft)
        clickDescription("뒤로")
        click("입력 버리고 나가기")
        show("무엇을 할까요?")
        assertNull(host.viewModel.form.value.certificationId)
        assertEquals(0, host.planningCalls.get())
        assertEquals(0, host.questionCalls.get())
    }

    @Test
    fun savedQuizResumesTheSameQuestionAfterAFailedWriteAndLocalRetry() = withApp { host ->
        startQuestions(host)
        click(UNKNOWN_LABEL)
        click("다음")
        click(UNKNOWN_LABEL)
        click("다음")
        click(UNKNOWN_LABEL)
        val expected = host.viewModel.form.value
        val question = expected.questions[2]
        leaveQuizForCertification()
        pressBack()
        val gate = CompletableDeferred<Unit>()
        host.failSaves.set(true)
        host.holdSaves.set(gate)
        click("임시 저장하고 나가기")
        waitForText("기기에 저장하는 중이에요")
        pressBack()
        show("기기에 저장하는 중이에요")
        assertNull(host.snapshot().creationDraft)
        gate.complete(Unit)
        waitForText("임시 저장하지 못했어요")
        assertEquals(expected, host.viewModel.form.value)
        assertNull(host.snapshot().creationDraft)
        host.failSaves.set(false)
        host.holdSaves.set(null)
        click("다시 저장하기")
        waitForText("무엇을 할까요?")
        show("아직 만든 계획이 없어요.")
        show("이어서 만들기")
        val draft = host.snapshot().creationDraft!!
        assertEquals(CreationStep.QUESTIONS, draft.step)
        assertEquals(2, draft.questionIndex)
        assertEquals(expected, draft.form)
        host.capture("draft-home")
        host.recreate()
        click("계획 생성", substring = true)
        show("새 계획을 만들까요?")
        click("취소")
        show("이어서 만들기")
        click("이어서 만들기")
        show(question.prompt)
        show("${expected.questions.size}문항 중 3번째")
        compose.onNodeWithText(UNKNOWN_LABEL).assertIsSelected()
        assertEquals(expected, host.viewModel.form.value)
        host.capture("draft-quiz-resumed")
        pressBack()
        show("${expected.questions.size}문항 중 2번째")
        compose.onNodeWithText("입력한 내용을 어떻게 할까요?").assertDoesNotExist()
        assertEquals(1, host.questionCalls.get())
        assertEquals(0, host.planningCalls.get())
        assertEquals(0, host.adjustmentCalls.get())
        clickDescription("닫기")
        val level = CurrentLevel.entries.first { it != expected.level }
        click(level.label)
        clickDescription("뒤로")
        clickDescription("뒤로")
        clickDescription("뒤로")
        click("임시 저장하고 나가기")
        waitForText("무엇을 할까요?")
        click("이어서 만들기")
        show("엑셀은 어느 정도 다뤄 봤나요?")
        assertEquals(level, host.viewModel.form.value.level)
        assertEquals(CreationStep.LEVEL, host.snapshot().creationDraft!!.step)
        assertEquals(1, host.questionCalls.get())
    }

    @Test
    fun replacingASavedDraftRequiresDiscardAndKeepsItWhenTheWriteFails() = withApp { host ->
        click("계획 생성", substring = true)
        click(CERTIFICATION_NAME)
        clickDescription("뒤로")
        click("임시 저장하고 나가기")
        waitForText("무엇을 할까요?")
        val saved = host.snapshot()
        host.failSaves.set(true)
        click("계획 생성", substring = true)
        click("입력 버리고 새로 만들기")
        waitForText("입력을 버리지 못했어요")
        compose.onAllNodesWithText("다시 저장하기").assertCountEquals(1)
        assertEquals(saved, host.snapshot())
        host.capture("draft-discard-failed")
        click("취소")
        show("이어서 만들기")
        host.failSaves.set(false)
        click("계획 생성", substring = true)
        click("입력 버리고 새로 만들기")
        waitForText("어떤 시험을 준비하나요?")
        assertNull(host.snapshot().creationDraft)
        assertNull(host.viewModel.form.value.certificationId)
        compose.onNodeWithText("다음").assertIsNotEnabled()
        pressBack()
        show("무엇을 할까요?")
        compose.onNodeWithText("이어서 만들기").assertDoesNotExist()
        assertEquals(0, host.planningCalls.get())
    }

    @Test
    fun availabilityDraftResumesItsReplacedWeekAndDisappearsWhenThePlanIsSaved() = withApp { host ->
        startQuestions(host)
        answerAll(host)
        click("공부 시간 정하기")
        val first = LocalDate.of(2026, 11, 16)
        val replaced = first.plusWeeks(1)
        compose.runOnIdle {
            for (day in Weekday.entries.take(5)) host.viewModel.setDuration(day, 120)
            host.viewModel.clearWeek(first)
            host.viewModel.replaceWeek(replaced)
            host.viewModel.setDayStart(Weekday.TUESDAY, LocalTime.of(19, 0), replaced)
            host.viewModel.showAvailabilityWeek(replaced)
        }
        val form = host.viewModel.form.value
        val monday = form.availability.getValue(Weekday.MONDAY)
        host.seedDraft(CreationDraft(
            form.copy(
                availability = form.availability + (Weekday.MONDAY to monday.copy(extras = listOf(WindowSpec(LocalTime.of(6, 0), 30)))),
                busySchedules = listOf(ScheduleRow("external-busy", "2026/11/16/08/00", "2026/11/16/09/00", "개인 일정")),
            ),
            CreationStep.AVAILABILITY,
            host.viewModel.questionIndex.value,
            replaced,
        ))
        host.recreate()
        click("이어서 만들기")
        compose.runOnIdle { host.viewModel.setDayStart(Weekday.TUESDAY, LocalTime.of(20, 0), replaced) }
        val expected = host.viewModel.form.value
        pressBack()
        show("예시 문항 결과")
        pressBack()
        leaveQuizForCertification()
        clickDescription("뒤로")
        click("임시 저장하고 나가기")
        waitForText("무엇을 할까요?")
        assertEquals(CreationStep.AVAILABILITY, host.snapshot().creationDraft!!.step)
        host.recreate()
        click("이어서 만들기")
        show("이번 주 공부 시간")
        assertEquals(expected, host.viewModel.form.value)
        assertEquals(replaced, host.viewModel.availabilityWeek.value)
        assertEquals(1, host.questionCalls.get())
        assertEquals(0, host.planningCalls.get())
        host.capture("draft-availability-resumed")
        click("계획 만들기")
        waitForText("내 계획")
        host.awaitSaved()
        assertEquals(1, host.snapshot().plans.size)
        assertNull(host.snapshot().creationDraft)
        click("처음으로", lazy = true)
        show("저장된 계획 1개가 있어요.")
        compose.onNodeWithText("이어서 만들기").assertDoesNotExist()
        host.recreate()
        show("저장된 계획 1개가 있어요.")
        compose.onNodeWithText("이어서 만들기").assertDoesNotExist()
        assertEquals(1, host.planningCalls.get())
    }

    private fun leaveQuizForCertification() {
        clickDescription("닫기")
        clickDescription("뒤로")
        clickDescription("뒤로")
        show("어떤 시험을 준비하나요?")
        compose.onNodeWithText("입력한 내용을 어떻게 할까요?").assertDoesNotExist()
    }

    private fun pressBack() {
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.waitForIdle()
    }

    @Test
    fun createsWrittenDemoPlanThroughEveryQuestionAndBrowsesWeeks() = withApp { host ->
        generatePlan(host)
        val saved = host.snapshot().plans.single()
        val weeks = PlanGrouping.weeks(saved.current.schedules)
        assertTrue("Generated plan has more than one browsable week", weeks.size > 1)
        show("계획 1 · $CERTIFICATION_NAME")
        show("로컬 시연 계산")
        show("데모 플래너가 기기 안에서 만든 계획이에요. 백엔드나 AI 결과가 아니에요.")
        assertSession(PlanGrouping.sessions(saved.current.schedules).first(), selectable = false)
        showWeek(weeks.first().monday, 0, weeks.size)
        compose.onNodeWithContentDescription("이전 주").assertIsNotEnabled()
        clickDescription("다음 주")
        showWeek(weeks[1].monday, 1, weeks.size)
        clickDescription("이전 주")
        showWeek(weeks.first().monday, 0, weeks.size)
        for (index in 1 until weeks.size) clickDescription("다음 주")
        showWeek(weeks.last().monday, weeks.lastIndex, weeks.size)
        compose.onNodeWithContentDescription("다음 주").assertIsNotEnabled()
        for (index in 1 until weeks.size) clickDescription("이전 주")
        showWeek(weeks.first().monday, 0, weeks.size)
        host.capture("creation")
        click("처음으로", lazy = true)
        show("무엇을 할까요?")
        show("저장된 계획 1개가 있어요.")
    }

    @Test
    fun blocksIncompleteInputsUntilTheyAreCorrected() = withApp { host ->
        click("계획 생성", substring = true)
        compose.onNodeWithText("다음").assertIsNotEnabled().performClick()
        show("어떤 시험을 준비하나요?")
        click(CERTIFICATION_NAME)
        compose.onNodeWithText("다음").assertIsEnabled()
        click("다음")
        pickExam(LocalDate.of(2026, 11, 16))
        assertDateGate()
        pickExam(LocalDate.of(2026, 11, 15))
        assertDateGate()
        pickExam(EXAM)
        compose.onNodeWithText("다음").assertIsEnabled()
        click("다음")
        chooseBeginner()
        awaitQuestions(host)
        compose.onNodeWithText("다음").assertIsNotEnabled().performClick()
        show("${host.viewModel.form.value.questions.size}문항 중 1번째")
        click(UNKNOWN_LABEL)
        compose.onNodeWithText("다음").assertIsEnabled()
        answerAll(host)
        click("공부 시간 정하기")
        compose.onNodeWithText("계획 만들기").assertIsNotEnabled().performClick()
        show("매주 기본 시간")
        show("시작과 종료 시간을 정해 주세요. 캘린더와 겹치는 시간을 제외하고 모두 계획에 써요.")
        show("공부할 요일을 하나 이상 선택해 주세요.")
        setWeekdayMinutes(Weekday.MONDAY)
        compose.onNodeWithText("계획 만들기").assertIsEnabled()
        compose.onNodeWithText("내 계획").assertDoesNotExist()
    }

    @Test
    fun retainsOnlyUnknownSelectionAfterForwardAndSystemBack() = withApp { host ->
        startQuestions(host)
        val questions = host.viewModel.form.value.questions
        val first = questions.first()
        val normal = first.answerChoices.entries.first().value
        click(normal)
        compose.onNodeWithText(normal).assertIsSelected()
        click(UNKNOWN_LABEL)
        assertUnknownOnly(normal)
        click("다음")
        show(questions[1].prompt)
        show("${questions.size}문항 중 2번째")
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        show(first.prompt)
        show("${questions.size}문항 중 1번째")
        assertUnknownOnly(normal)
        click("다음")
        show(questions[1].prompt)
        show("${questions.size}문항 중 2번째")
    }

    @Test
    fun filledDemoPlanShowsNoFitGuidanceAndStillMovesBySelectedTime() = withApp { host ->
        startQuestions(host)
        answerAll(host)
        click("공부 시간 정하기")
        show("매주 기본 시간")
        show("시작과 종료 시간을 정해 주세요. 캘린더와 겹치는 시간을 제외하고 모두 계획에 써요.")
        host.capture("availability-heading")
        for (day in Weekday.entries.filter { it.dayOfWeek.value <= 5 }) setWeekdayMinutes(day)
        click("계획 만들기")
        waitForText("내 계획")
        host.awaitSaved()
        val before = host.snapshot().plans.single()
        val sessions = PlanGrouping.sessions(before.current.schedules).filter { !it.isExternal }
        assertEquals("Every entered minute is planned", windowMinutes(before.original), sessions.sumOf { it.durationMinutes })
        host.capture("filled-plan")

        host.recreate()
        openSavedPlanAdjustment()
        val target = sessions.first()
        clickSession(target)
        click("선택한 일정 조정하기")
        waitForText("옮길 수 있는 시간이 없어요")
        show("옮길 수 있는 시간이 없어요")
        show("이 계획은 입력한 공부 시간을 모두 쓰고 있어서 시험 전까지 비어 있는 시간이 없어요. 기존 계획은 그대로예요. " +
            "'시간 직접 옮기기'에서 다른 시간을 골라 옮길 수 있어요. 캘린더에서 비어 있는 시간을 찾아 이번 한 번만 옮길 수도 있어요.")
        compose.onNodeWithText("선택한 일정 조정하기").assertDoesNotExist()
        compose.onNodeWithText("다시 시도").assertDoesNotExist()
        assertEquals(1, host.adjustmentCalls.get())
        host.awaitSaved()
        assertEquals(before.current.schedules, host.snapshot().plans.single().current.schedules)
        host.capture("automatic-no-fit")

        click("시간 직접 옮기기")
        val destination = target.start.withHour(14)
        compose.runOnIdle { host.viewModel.setManualStart(destination) }
        click("이 시간으로 옮기기")
        compose.waitUntil("The outside-window destination needs confirmation", 10_000) {
            host.viewModel.adjustment.value.canConfirmOutsideAvailability
        }
        compose.waitForIdle()
        show("이번 한 번만 옮기기")
        host.capture("outside-window-confirm")
        click("이번 한 번만 옮기기")
        waitForText("계획이 조정됐어요")
        host.awaitSaved()
        val after = host.snapshot().plans.single()
        assertOnlyTargetTimesChanged(before.current.schedules, after.current.schedules, target.scheduleId)
        assertEquals(WireDateTime.format(destination), after.current.schedules.single { it.scheduleId == target.scheduleId }.startDate)
        show("계획이 조정됐어요")
        host.capture("manual-move-result")
    }

    @Test
    fun restoresAdjustsAndReopensSavedPlanWithoutReplayingAdjustment() = withApp { host ->
        generatePlan(host)
        addEmptyWindowInLastWeek(host)
        val before = host.snapshot().plans.single()
        val target = PlanGrouping.sessions(before.current.schedules).first()
        host.recreate()
        openSavedPlanAdjustment()
        compose.onNodeWithText("선택한 일정 조정하기").assertDoesNotExist()
        clickSession(target)
        compose.onNodeWithText(sessionLabel(target)).assertIsSelected()
        compose.onNodeWithText("선택한 일정 조정하기").assertIsEnabled()
        click("선택한 일정 조정하기")
        waitForText("계획이 조정됐어요")
        show("계획이 조정됐어요")
        host.awaitSaved()
        val after = host.snapshot().plans.single()
        assertEquals(before.ordinal, after.ordinal)
        assertEquals(before.original, after.original)
        assertEquals(1, host.adjustmentCalls.get())
        val moved = PlanGrouping.sessions(after.current.schedules).single { it.scheduleId == target.scheduleId }
        assertNotEquals(target.start, moved.start)
        assertEquals(target.topic, moved.topic)
        assertEquals(target.durationMinutes, moved.durationMinutes)
        assertOnlyTargetTimesChanged(before.current.schedules, after.current.schedules, target.scheduleId)
        assertMoveResult(
            before.current.schedules.single { it.scheduleId == target.scheduleId },
            after.current.schedules.single { it.scheduleId == target.scheduleId },
            saved = "이 기기에 저장했어요",
        )
        val weeks = PlanGrouping.weeks(after.current.schedules)
        val destinationIndex = weeks.indexOfFirst { week -> week.days.any { day -> day.sessions.any { it.scheduleId == target.scheduleId } } }
        revealMovedSession()
        showWeek(weeks[destinationIndex].monday, destinationIndex, weeks.size)
        assertSession(moved, selectable = true, moved = true)
        compose.onAllNodes(movedMarker()).assertCountEquals(1)
        host.capture("adjusted")
        host.recreate()
        openSavedPlanAdjustment()
        compose.onNodeWithText("선택한 일정 조정하기").assertDoesNotExist()
        compose.onNodeWithText("계획이 조정됐어요").assertDoesNotExist()
        compose.onNodeWithText("이 기기에 저장했어요").assertDoesNotExist()
        compose.onAllNodes(movedMarker()).assertCountEquals(0)
        for (index in 0 until destinationIndex) clickDescription("다음 주")
        showWeek(weeks[destinationIndex].monday, destinationIndex, weeks.size)
        assertSession(moved, selectable = true)
        assertEquals(after, host.snapshot().plans.single())
        assertEquals("Restoring and opening never submits another adjustment", 1, host.adjustmentCalls.get())
    }

    @Test
    fun outsideWindowQuestionScrollsIntoViewBeforeConfirming() = withApp { host ->
        generatePlan(host)
        val before = host.snapshot().plans.single()
        val target = PlanGrouping.sessions(before.current.schedules).first()
        host.recreate()
        openSavedPlanAdjustment()
        clickSession(target)
        click("시간 직접 옮기기")
        val destination = target.start.withHour(14)
        compose.runOnIdle { host.viewModel.setManualStart(destination) }
        click("이 시간으로 옮기기")
        compose.waitUntil("The outside-window destination needs confirmation", 10_000) {
            host.viewModel.adjustment.value.canConfirmOutsideAvailability
        }
        compose.waitForIdle()
        val question = "11월 16일(월) 14:00–15:00에 이번 일정만 옮길까요? 설정한 공부 시간은 그대로예요."
        compose.onNodeWithText(question).assertIsDisplayed()
        compose.onNodeWithText("이번 한 번만 옮기기").assertIsEnabled()
        assertEquals(1, host.adjustmentCalls.get())
        host.awaitSaved()
        assertEquals(before.current.schedules, host.snapshot().plans.single().current.schedules)
        host.capture("outside-window-question")
        click("시간 다시 고르기")
        click("이 시간으로 옮기기")
        compose.waitUntil("The same time is checked afresh", 10_000) {
            host.viewModel.adjustment.value.canConfirmOutsideAvailability && host.adjustmentCalls.get() == 2
        }
        compose.waitForIdle()
        compose.onNodeWithText(question).assertIsDisplayed()
        click("이번 한 번만 옮기기")
        waitForText("계획이 조정됐어요")
        host.awaitSaved()
        assertEquals(3, host.adjustmentCalls.get())
        val after = host.snapshot().plans.single()
        assertOnlyTargetTimesChanged(before.current.schedules, after.current.schedules, target.scheduleId)
        assertEquals(WireDateTime.format(destination), after.current.schedules.single { it.scheduleId == target.scheduleId }.startDate)
        assertMoveResult(
            before.current.schedules.single { it.scheduleId == target.scheduleId },
            after.current.schedules.single { it.scheduleId == target.scheduleId },
            saved = "이 기기에 저장했어요",
        )
        show("바뀐 시간: 2026년 11월 16일(월) 14:00–15:00")
    }

    @Test
    fun incomingOvernightTimeIsShownInFirstEmptyAndReplacedWeeks() = withApp { host ->
        startQuestions(host)
        answerAll(host)
        click("공부 시간 정하기")
        val first = LocalDate.of(2026, 11, 16)
        val replaced = first.plusWeeks(1)
        compose.runOnIdle {
            host.viewModel.setDayEnabled(Weekday.SUNDAY, true)
            host.viewModel.setDayStart(Weekday.SUNDAY, LocalTime.of(23, 0))
            host.viewModel.setDuration(Weekday.SUNDAY, 90)
            host.viewModel.clearWeek(first)
        }
        show("이번 주 공부 안 함")
        show("지난주에서 이어지는 시간")
        show("11월 16일(월) 00:00–00:30 공부 시간이 지난주 설정에서 이어져요.")
        host.capture("first-week-incoming")
        click("기본 시간으로 되돌리기")
        clickDescription("다음 주")
        click("이번 주 시간 바꾸기")
        compose.runOnIdle {
            host.viewModel.setDayEnabled(Weekday.SUNDAY, false, replaced)
            host.viewModel.setDayEnabled(Weekday.TUESDAY, true, replaced)
        }
        show("이번 주 공부 시간")
        show("지난주에서 이어지는 시간")
        show("11월 23일(월) 00:00–00:30 공부 시간이 지난주 설정에서 이어져요.")
        host.capture("replaced-week-incoming")
    }

    @Test
    fun longReturnedTopicsKeepReportsAndEditorActionsReachable() = withApp { host ->
        val topic = "스프레드시트의 복잡한 함수와 데이터 관리 및 조건부 서식을 함께 연습하고 " +
            "여러 시트의 자료를 연결하여 검토하는 긴 학습 단원 이름 — 마지막 부분까지 확인"
        host.returnedTopic = topic
        generatePlan(host)
        val target = PlanGrouping.sessions(host.snapshot().plans.single().current.schedules).first { !it.isExternal }
        assertEquals(topic, target.topic)
        show(topic, lazy = true)
        host.capture("long-generated-row")
        openReport(target, edit = false)
        show(topic)
        host.capture("long-generated-editor")
        click("일부 했어요")
        minutesField().performScrollTo().performClick().performTextReplacement("0")
        compose.waitUntil("Minutes keyboard is visible", 5_000) {
            ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
                ?.isVisible(WindowInsetsCompat.Type.ime()) == true
        }
        host.capture("long-minutes-ime")
        click("기록 저장하기")
        show("실제로 공부한 시간을 1분 이상의 정수(분)로 적어 주세요.")
        host.capture("long-error-ime")
        scrollTo(hasText("취소") and hasClickAction())
        host.capture("long-actions-ime")
        click("취소")
        returnTo("내 계획", host)
        assertTrue(host.snapshot().plans.single().checkIns.isEmpty())
        addEmptyWindowInLastWeek(host)
        host.recreate()
        openSavedPlanAdjustment()
        openReport(target, edit = false)
        show(topic)
        host.capture("long-reopened-editor")
        click("다 했어요")
        minutesField().performTextReplacement("20")
        click("기록 저장하기")
        returnTo(ADJUSTMENT_TITLE, host)
        show("다 했어요 · 실제 20분")
        assertEquals(StudyCheckIn(StudyCheckInStatus.Done, 20), reportOf(host, target))
        host.capture("long-reopened-row")
        clickSession(target)
        click("선택한 일정 조정하기")
        waitForText("계획이 조정됐어요")
        host.awaitSaved()
        val move = (host.viewModel.adjustment.value.state as AdjustmentState.Success).move!!
        assertMoveResult(move.before, move.after, "이 기기에 저장했어요")
        host.capture("long-move-result")
        assertEquals(1, host.planningCalls.get())
        assertEquals(1, host.adjustmentCalls.get())
    }

    @Test
    fun reportMinutesAndActionsRemainReachableWithTheKeyboardOpen() = withApp { host ->
        generatePlan(host)
        val target = PlanGrouping.sessions(host.snapshot().plans.single().current.schedules).first { !it.isExternal }
        openReport(target, edit = false)
        click("일부 했어요")
        minutesField().performScrollTo().performClick()
        compose.waitUntil("The minutes keyboard opens", 5_000) {
            ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
                ?.isVisible(WindowInsetsCompat.Type.ime()) == true
        }
        minutesField().assertIsDisplayed().assertIsFocused()
        val field = minutesField().getUnclippedBoundsInRoot()
        val viewport = compose.onNode(hasScrollAction()).getUnclippedBoundsInRoot()
        assertTrue("The whole minutes field fits above the keyboard", field.top >= viewport.top && field.bottom <= viewport.bottom)
        minutesField().performTextReplacement("0")
        host.capture("report-minutes-keyboard")
        click("기록 저장하기")
        show("실제로 공부한 시간을 1분 이상의 정수(분)로 적어 주세요.")
        assertTrue("Validation stays reachable with the keyboard open",
            ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
                ?.isVisible(WindowInsetsCompat.Type.ime()) == true)
        host.capture("report-error-keyboard")
        compose.onNodeWithText("취소").performScrollTo().assertIsDisplayed()
        val cancel = compose.onNodeWithText("취소").getUnclippedBoundsInRoot()
        assertTrue("The whole cancel action fits above the keyboard", cancel.top >= viewport.top && cancel.bottom <= viewport.bottom)
        host.capture("report-actions-keyboard")
        click("취소")
        returnTo("내 계획", host)
        assertTrue(host.snapshot().plans.single().checkIns.isEmpty())

        openReport(target, edit = false)
        click("일부 했어요")
        minutesField().performScrollTo().performClick().performTextReplacement("45")
        click("기록 저장하기")
        returnTo("내 계획", host)
        assertEquals(StudyCheckIn(StudyCheckInStatus.Partly, 45), reportOf(host, target))
        assertEquals(1, host.planningCalls.get())
        assertEquals(0, host.adjustmentCalls.get())
    }

    @Test
    fun reportsGeneratedSessionWithEditableMinutesOvertimeValidationAndCancel() = withApp { host ->
        generatePlan(host)
        val plan = host.snapshot().plans.single()
        val weeks = PlanGrouping.weeks(plan.current.schedules)
        assertTrue("Generated plan has more than one browsable week", weeks.size > 1)
        val target = weeks[1].days.flatMap { it.sessions }.first { !it.isExternal }
        clickDescription("다음 주")
        showWeek(weeks[1].monday, 1, weeks.size)
        openReport(target, edit = false)
        show(target.topic)
        show(time(target))
        compose.onAllNodes(isSelected()).assertCountEquals(0)
        minutesField().assertIsNotEnabled()
        click("기록 저장하기")
        show("다 했어요, 일부 했어요, 못 했어요 중 하나를 골라 주세요.")
        assertTrue(host.snapshot().plans.single().checkIns.isEmpty())

        click("다 했어요")
        compose.onNodeWithText("다 했어요").assertIsSelected()
        minutesField().assertIsEnabled().assertTextEquals(target.durationMinutes.toString())
        minutesField().performTextReplacement("20")
        click("기록 저장하기")
        returnTo("내 계획", host)
        showWeek(weeks[1].monday, 1, weeks.size)
        show("다 했어요 · 실제 20분", lazy = true)
        assertEquals(StudyCheckIn(StudyCheckInStatus.Done, 20), reportOf(host, target))

        openReport(target, edit = true)
        compose.onNodeWithText("다 했어요").assertIsSelected()
        minutesField().assertTextEquals("20")
        click("일부 했어요")
        minutesField().performTextReplacement("abc")
        click("기록 수정하기")
        show("실제로 공부한 시간을 1분 이상의 정수(분)로 적어 주세요.")
        assertEquals(StudyCheckIn(StudyCheckInStatus.Done, 20), reportOf(host, target))
        val overtime = (target.durationMinutes + 15).toInt()
        minutesField().performTextReplacement(overtime.toString())
        click("기록 수정하기")
        returnTo("내 계획", host)
        showWeek(weeks[1].monday, 1, weeks.size)
        show("일부 했어요 · 실제 ${overtime}분", lazy = true)
        assertEquals(StudyCheckIn(StudyCheckInStatus.Partly, overtime), reportOf(host, target))

        val committed = host.snapshot()
        openReport(target, edit = true)
        click("못 했어요")
        minutesField().assertIsNotEnabled()
        click("취소")
        returnTo("내 계획", host)
        showWeek(weeks[1].monday, 1, weeks.size)
        show("일부 했어요 · 실제 ${overtime}분", lazy = true)
        assertEquals(committed, host.snapshot())
        assertEquals(plan.current, host.snapshot().plans.single().current)
        assertEquals("Reporting sends no planning request", 1, host.planningCalls.get())
        assertEquals("Reporting sends no adjustment request", 0, host.adjustmentCalls.get())
        host.capture("report-generated")
    }

    @Test
    fun reportsReopenedSavedSessionAsMissedWithoutChangingTheAdjustmentSelection() = withApp { host ->
        generatePlan(host)
        host.recreate()
        openSavedPlanAdjustment()
        val plan = host.snapshot().plans.single()
        val firstWeek = PlanGrouping.weeks(plan.current.schedules).first()
        val study = firstWeek.days.flatMap { it.sessions }.filterNot { it.isExternal }
        assertTrue("The first week has two study sessions", study.size >= 2)
        val selected = study[0]
        val reported = study[1]
        compose.onAllNodes(hasContentDescription(" 공부 기록하기", substring = true)).assertCountEquals(study.size)
        clickSession(selected)
        compose.onNodeWithText(sessionLabel(selected)).assertIsSelected()

        openReport(reported, edit = false)
        compose.onAllNodes(isSelected()).assertCountEquals(0)
        click("못 했어요")
        minutesField().assertIsNotEnabled().assertTextEquals("0")
        show("못 했어요는 0분으로 기록돼요.")
        click("기록 저장하기")
        returnTo(ADJUSTMENT_TITLE, host)
        compose.onNodeWithText(sessionLabel(selected)).assertIsSelected()
        compose.onNodeWithText(sessionLabel(reported)).assertIsNotSelected()
        compose.onNodeWithText("선택한 일정 조정하기").assertIsEnabled()
        show("못 했어요 · 실제 0분")
        val saved = host.snapshot().plans.single()
        assertEquals(mapOf(reported.scheduleId to StudyCheckIn(StudyCheckInStatus.Missed, 0)), saved.checkIns)
        assertEquals(plan.current, saved.current)
        assertEquals(0, host.adjustmentCalls.get())

        host.recreate()
        openSavedPlanAdjustment()
        show("못 했어요 · 실제 0분")
        compose.onNodeWithContentDescription(reportAction(reported, edit = true)).assertIsEnabled()
        compose.onAllNodes(isSelected()).assertCountEquals(0)
        assertEquals(saved, host.snapshot().plans.single())
        assertEquals(0, host.adjustmentCalls.get())
        host.capture("report-saved")
    }

    @Test
    fun sessionsAfterTheDeviceDateOfferNoReportUntilTheirDay() = withApp { host ->
        val first = LocalDate.of(2026, 11, 16)
        host.deviceDate = first
        generatePlan(host)
        val plan = host.snapshot().plans.single()
        val weeks = PlanGrouping.weeks(plan.current.schedules)
        assertTrue("Generated plan has more than one browsable week", weeks.size > 1)
        val study = weeks.first().days.flatMap { it.sessions }.filterNot { it.isExternal }
        val today = study.filter { !it.start.toLocalDate().isAfter(first) }
        val later = study.filter { it.start.toLocalDate().isAfter(first) }
        assertTrue("The first day has a study session", today.isNotEmpty())
        assertTrue("The first week has a later study session", later.isNotEmpty())
        val next = later.first()

        // The new plan: the first day's session offers reporting, a later one shows only the note.
        showWeek(weeks[0].monday, 0, weeks.size)
        scrollTo(hasContentDescription(reportAction(today.first(), edit = false)), lazy = true)
        compose.onNodeWithContentDescription(reportAction(today.first(), edit = false)).assertIsEnabled()
        show(monthDay(next.start.toLocalDate()), lazy = true)
        compose.onAllNodes(hasContentDescription(reportAction(next, edit = false))).assertCountEquals(0)
        compose.onAllNodesWithText(NOT_YET_REPORTABLE).fetchSemanticsNodes().also { assertTrue(it.isNotEmpty()) }
        host.capture("report-future-row")

        // The adjustment row list of the saved plan.
        host.recreate()
        openSavedPlanAdjustment()
        compose.onAllNodes(hasContentDescription(" 공부 기록하기", substring = true)).assertCountEquals(today.size)
        compose.onAllNodesWithText(NOT_YET_REPORTABLE).assertCountEquals(later.size)
        compose.onAllNodes(hasContentDescription(reportAction(next, edit = false))).assertCountEquals(0)
        assertTrue(host.snapshot().plans.single().checkIns.isEmpty())

        // The device date reaches the later session while the app stays open.
        host.deviceDate = next.start.toLocalDate()
        clickDescription("다음 주")
        showWeek(weeks[1].monday, 1, weeks.size)
        clickDescription("이전 주")
        showWeek(weeks[0].monday, 0, weeks.size)
        compose.onNodeWithContentDescription(reportAction(next, edit = false)).assertIsEnabled()
        openReport(next, edit = false)
        click("취소")
        returnTo(ADJUSTMENT_TITLE, host)
        assertEquals(plan, host.snapshot().plans.single())
        assertEquals(0, host.adjustmentCalls.get())
    }

    @Test
    fun failedReportSaveStaysUnsavedUntilLocalRetryAndFollowsTheMovedSession() = withApp { host ->
        generatePlan(host)
        addEmptyWindowInLastWeek(host)
        host.recreate()
        openSavedPlanAdjustment()
        val before = host.snapshot().plans.single()
        val target = PlanGrouping.sessions(before.current.schedules).first { !it.isExternal }
        openReport(target, edit = false)
        click("다 했어요")
        minutesField().performTextReplacement("20")
        click("기록 저장하기")
        returnTo(ADJUSTMENT_TITLE, host)
        val done = StudyCheckIn(StudyCheckInStatus.Done, 20)
        assertEquals(done, reportOf(host, target))

        host.failSaves.set(true)
        openReport(target, edit = true)
        click("일부 했어요")
        minutesField().performTextReplacement("45")
        click("기록 수정하기")
        waitForText("공부 기록을 저장하지 못했어요")
        show("저장된 기록: 다 했어요 · 실제 20분")
        compose.onNodeWithText("일부 했어요").assertIsSelected().assertIsNotEnabled()
        compose.onNodeWithText("다시 저장하기").assertIsEnabled()
        assertEquals(done, reportOf(host, target))
        host.capture("report-save-failed")
        click("계획으로 돌아가기")
        waitForText(ADJUSTMENT_TITLE)
        show("일부 했어요 · 실제 45분")
        show("저장 안 됨")
        show("기기에 계획을 저장하지 못했어요")
        compose.onNodeWithContentDescription(reportAction(target, edit = true)).assertIsNotEnabled()
        assertEquals(done, reportOf(host, target))

        host.failSaves.set(false)
        click("다시 저장하기")
        host.awaitSaved()
        compose.onNodeWithText("저장 안 됨").assertDoesNotExist()
        val partly = StudyCheckIn(StudyCheckInStatus.Partly, 45)
        assertEquals(partly, reportOf(host, target))
        assertEquals("Retrying a report save sends no request", 0, host.adjustmentCalls.get())
        assertEquals(1, host.planningCalls.get())

        clickSession(target)
        click("선택한 일정 조정하기")
        waitForText("계획이 조정됐어요")
        host.awaitSaved()
        val after = host.snapshot().plans.single()
        val moved = PlanGrouping.sessions(after.current.schedules).single { it.scheduleId == target.scheduleId }
        assertNotEquals(target.start, moved.start)
        assertEquals(mapOf(target.scheduleId to partly), after.checkIns)
        assertEquals(1, host.adjustmentCalls.get())
        revealMovedSession()
        show("일부 했어요 · 실제 45분")

        host.recreate()
        openSavedPlanAdjustment()
        val weeks = PlanGrouping.weeks(after.current.schedules)
        val destination = weeks.indexOfFirst { week -> week.days.any { day -> day.sessions.any { it.scheduleId == target.scheduleId } } }
        for (index in 0 until destination) clickDescription("다음 주")
        showWeek(weeks[destination].monday, destination, weeks.size)
        show("일부 했어요 · 실제 45분")
        compose.onNodeWithContentDescription(reportAction(moved, edit = true)).assertIsEnabled()
        assertEquals(after, host.snapshot().plans.single())
        assertEquals("Reopening never replays the adjustment", 1, host.adjustmentCalls.get())
    }

    @Test
    fun moveResultStaysWhileItsSaveIsPendingFailsAndIsRetriedLocally() = withApp { host ->
        generatePlan(host)
        addEmptyWindowInLastWeek(host)
        host.recreate()
        openSavedPlanAdjustment()
        val before = host.snapshot().plans.single()
        val target = PlanGrouping.sessions(before.current.schedules).first { !it.isExternal }
        val sent = before.current.schedules.single { it.scheduleId == target.scheduleId }
        clickSession(target)
        val pending = CompletableDeferred<Unit>()
        host.holdSaves.set(pending)
        click("선택한 일정 조정하기")
        waitForText("기기에 저장하는 중이에요")
        val accepted = checkNotNull((host.viewModel.adjustment.value.state as AdjustmentState.Success).move).after
        assertMoveResult(sent, accepted, saved = "기기에 저장하는 중이에요")
        compose.onNodeWithText("이 기기에 저장했어요").assertDoesNotExist()
        compose.onNodeWithText("아직 이 기기에 저장하지 못했어요").assertDoesNotExist()
        compose.onNodeWithText("다시 저장하기").assertDoesNotExist()
        compose.onNodeWithText("시간 직접 옮기기").assertDoesNotExist()
        host.capture("move-saving")

        host.failSaves.set(true)
        host.holdSaves.set(null)
        pending.complete(Unit)
        waitForText("아직 이 기기에 저장하지 못했어요")
        assertMoveResult(sent, accepted, saved = "아직 이 기기에 저장하지 못했어요")
        show("기기에 계획을 저장하지 못했어요")
        compose.onAllNodesWithText("다시 저장하기").assertCountEquals(1)
        compose.onNodeWithText("이 기기에 저장했어요").assertDoesNotExist()
        revealMovedSession()
        compose.onAllNodes(movedMarker()).assertCountEquals(1)
        assertEquals(before.current.schedules, host.snapshot().plans.single().current.schedules)
        host.capture("move-unsaved")

        host.failSaves.set(false)
        click("다시 저장하기")
        host.awaitSaved()
        waitForText("이 기기에 저장했어요")
        assertMoveResult(sent, accepted, saved = "이 기기에 저장했어요")
        compose.onNodeWithText("다시 저장하기").assertDoesNotExist()
        compose.onNodeWithText("기기에 계획을 저장하지 못했어요").assertDoesNotExist()
        val after = host.snapshot().plans.single()
        assertEquals(accepted, after.current.schedules.single { it.scheduleId == target.scheduleId })
        assertOnlyTargetTimesChanged(before.current.schedules, after.current.schedules, target.scheduleId)
        assertEquals("Retrying the save sends no adjustment", 1, host.adjustmentCalls.get())
        assertEquals("Retrying the save sends no generation", 1, host.planningCalls.get())
        host.capture("move-saved")
    }

    private fun seedRemainingPlans(host: DemoUiHost, zero: Boolean = false, noFit: Boolean = false): com.swpp.licenseplanner.data.PlanSnapshot {
        val original = com.swpp.licenseplanner.data.model.StudyPlanRequest(
            com.swpp.licenseplanner.domain.CERTIFICATION_ID, "2026/11/09/00/00",
            if (noFit) "2026/11/16/18/30" else "2026/11/30/00/00", "beginner",
            com.swpp.licenseplanner.data.model.AssessmentResults(com.swpp.licenseplanner.domain.CERTIFICATION_ID, emptyList()),
            7, Weekday.entries.associate { it.key to listOf(com.swpp.licenseplanner.data.model.TimeWindow("18:00", durationMinutes = 240)) })
        val rows = (1..4).map { id -> ScheduleRow("study-$id", "2026/11/${11 + id}/18/00", "2026/11/${11 + id}/19/00", "공부 주제 $id") }
        val reports = if (zero) rows.associate { it.scheduleId to StudyCheckIn(StudyCheckInStatus.Done, 20) } else mapOf(
            "study-1" to StudyCheckIn(StudyCheckInStatus.Done, 20), "study-2" to StudyCheckIn(StudyCheckInStatus.Partly, 20),
            "study-3" to StudyCheckIn(StudyCheckInStatus.Missed, 0))
        val a = com.swpp.licenseplanner.domain.SessionPlan.register(1, original,
            com.swpp.licenseplanner.data.model.StudyPlanResponse(rows), true).copy(checkIns = reports,
            keptConflicts = mapOf("study-40" to setOf("a".repeat(64))))
        val b = a.copy(ordinal = 2, checkIns = mapOf("study-1" to StudyCheckIn(StudyCheckInStatus.Partly, 5)),
            keptConflicts = mapOf("study-60" to setOf("b".repeat(64))))
        val draft = CreationDraft(com.swpp.licenseplanner.domain.PlanFormState(preparationStart = LocalDate.of(2026, 11, 16)), CreationStep.CERTIFICATION)
        val snapshot = com.swpp.licenseplanner.data.PlanSnapshot(listOf(a, b), creationDraft = draft)
        host.seedSnapshot(snapshot); host.recreate()
        openPlanView(1)
        return snapshot
    }

    /** 「계획 보기」 on a placed result shows the plan view at the week of its earliest placed session. */
    private fun showPlacedPlan(host: DemoUiHost) {
        val result = host.viewModel.remainingStudy.value as RemainingStudyState.Success
        val ids = result.input.work.instructions.map { it.replacementScheduleId }.toSet()
        val earliest = result.rows.filter { it.scheduleId in ids }.minBy { checkNotNull(WireDateTime.parseOrNull(it.startDate)) }
        click("계획 보기")
        awaitPlanView()
        show("계획 ${result.input.source.ordinal} · $CERTIFICATION_NAME")
        val weeks = PlanGrouping.weeks(host.snapshot().plans.single { it.ordinal == result.input.source.ordinal }.current.schedules)
        val index = weeks.indexOfFirst { week -> week.days.any { day -> day.sessions.any { it.scheduleId == earliest.scheduleId } } }
        showWeek(weeks[index].monday, index, weeks.size)
        compose.onNodeWithText("계획 보기").assertDoesNotExist()
    }

    @Test fun remainingReviewCancelAndRepeatedReplanPreserveEffortIdentityAndOtherPlans() = withApp { host ->
        val original = seedRemainingPlans(host)
        val reads = host.calendarReads.get()
        clickPlanAction("남은 공부 다시 계획하기")
        show("남은 시간 160분")
        show("예정 60분 · 실제 20분 · 남은 시간 40분")
        host.capture("remaining-review")
        click("취소")
        assertEquals(original, host.snapshot()); assertEquals(0, host.remainingCalls.get())
        clickPlanAction("남은 공부 다시 계획하기"); click("남은 공부 다시 배치하기")
        host.awaitSaved(); show("남은 공부 160분을 배치했어요")
        host.capture("remaining-saved")
        val accepted = host.snapshot()
        assertEquals(original.plans[1], accepted.plans[1]); assertEquals(original.creationDraft, accepted.creationDraft)
        assertEquals(original.plans[0].checkIns, accepted.plans[0].checkIns)
        assertEquals(original.plans[0].keptConflicts, accepted.plans[0].keptConflicts)
        assertEquals(original.plans[0].current.schedules.first(), accepted.plans[0].current.schedules.first { it.scheduleId == "study-1" })
        assertEquals(setOf("study-1", "study-41", "study-42", "study-4"), accepted.plans[0].current.schedules.map { it.scheduleId }.toSet())
        assertNull(accepted.plans[0].checkInFor("study-41"))
        assertEquals(reads, host.calendarReads.get()); assertEquals(0, host.calendarWrites.get())
        showPlacedPlan(host)
        pressBack(); awaitPlanList()
        click("계획 1 · $CERTIFICATION_NAME")
        clickPlanAction("남은 공부 다시 계획하기"); show("남은 시간 160분")
        click("남은 공부 다시 배치하기"); host.awaitSaved()
        assertEquals(accepted.plans[0].current.schedules.map { it.scheduleId }.toSet(), host.snapshot().plans[0].current.schedules.map { it.scheduleId }.toSet())
        assertEquals(2, host.remainingCalls.get())
        host.recreate(); openPlanView(1)
        assertEquals(accepted.plans[1], host.snapshot().plans[1]); assertEquals(original.creationDraft, host.snapshot().creationDraft)
        assertNull(host.viewModel.adjustment.value.failedScheduleId); assertEquals(2, host.remainingCalls.get())
    }

    @Test fun remainingSaveFailureRetriesLocallyAndEmptyOrNoFitHasAReturnPath() = withApp { host ->
        val original = seedRemainingPlans(host)
        clickPlanAction("남은 공부 다시 계획하기")
        host.failSaves.set(true)
        click("남은 공부 다시 배치하기")
        waitForText("3개 일정 · 아직 이 기기에 저장하지 못했어요.")
        assertEquals(original, host.snapshot())
        host.capture("remaining-unsaved")
        host.failSaves.set(false); click("다시 저장하기"); host.awaitSaved()
        assertEquals(1, host.remainingCalls.get()); show("3개 일정 · 이 기기에 저장했어요")
        seedRemainingPlans(host, zero = true)
        clickPlanAction("남은 공부 다시 계획하기"); show("다시 배치할 남은 공부가 없어요")
        host.capture("remaining-empty"); click("계획으로 돌아가기")
        awaitPlanView(); show("계획 1 · $CERTIFICATION_NAME")
        pressBack(); awaitPlanList()
        assertEquals(1, host.remainingCalls.get())
        val noFit = seedRemainingPlans(host, noFit = true)
        clickPlanAction("남은 공부 다시 계획하기"); click("남은 공부 다시 배치하기")
        show("남은 공부를 모두 배치할 시간이 없어요")
        host.capture("remaining-no-fit"); click("계획으로 돌아가기")
        awaitPlanView(); show("계획 1 · $CERTIFICATION_NAME")
        pressBack(); awaitPlanList()
        assertEquals(noFit, host.snapshot()); assertEquals(2, host.remainingCalls.get())
    }

    private fun seedFullPlanForFreeTime(host: DemoUiHost): com.swpp.licenseplanner.domain.SessionPlan {
        val request = com.swpp.licenseplanner.data.model.StudyPlanRequest(
            com.swpp.licenseplanner.domain.CERTIFICATION_ID, "2026/11/09/00/00", "2026/11/23/00/00", "beginner",
            com.swpp.licenseplanner.data.model.AssessmentResults(com.swpp.licenseplanner.domain.CERTIFICATION_ID, emptyList()), 2,
            mapOf("monday" to listOf(com.swpp.licenseplanner.data.model.TimeWindow("19:00", durationMinutes = 90)),
                "saturday" to listOf(com.swpp.licenseplanner.data.model.TimeWindow("10:00", durationMinutes = 120))))
        val rows = listOf(
            ScheduleRow("study-1", "2026/11/09/19/00", "2026/11/09/20/30", "놓친 공부"),
            ScheduleRow("study-2", "2026/11/14/10/00", "2026/11/14/12/00", "다른 공부 2"),
            ScheduleRow("study-3", "2026/11/16/19/00", "2026/11/16/20/30", "다른 공부 3"),
            ScheduleRow("study-4", "2026/11/21/10/00", "2026/11/21/12/00", "다른 공부 4"))
        val plan = com.swpp.licenseplanner.domain.SessionPlan.register(1, request,
            com.swpp.licenseplanner.data.model.StudyPlanResponse(rows), true)
        host.deviceMinute = LocalDate.of(2026, 11, 17).atTime(9, 5)
        host.seedSnapshot(com.swpp.licenseplanner.data.PlanSnapshot(listOf(plan))); host.recreate()
        openPlanView(1)
        return plan
    }

    private fun openSingleFreeTime(host: DemoUiHost): com.swpp.licenseplanner.domain.SessionPlan {
        val plan = seedFullPlanForFreeTime(host)
        adjustShownPlan()
        clickSession(PlanGrouping.sessions(plan.current.schedules).first())
        click("선택한 일정 조정하기"); show("옮길 수 있는 시간이 없어요")
        host.capture("calendar-no-fit")
        click("캘린더에서 빈 시간 찾기")
        show(ADJUSTMENT_TITLE)
        return plan
    }

    @Test fun planViewKeepsExportForAnUnadjustablePlanAndLeadsWithCreationForAnEmptyPlan() = withApp { host ->
        val plan = seedFullPlanForFreeTime(host)
        val unadjustable = plan.copy(isDemo = false)
        host.storageScope = PlanStorageScope.of(demo = false, apiBaseUrl = "http://test.invalid/api/v1")
        host.seedSnapshot(com.swpp.licenseplanner.data.PlanSnapshot(listOf(unadjustable)))
        host.recreate()

        openPlanView(1)
        val export = "캘린더에 ${plan.studyCount}개 일정 추가"
        show(export, lazy = true)
        compose.onNodeWithText(export).assertIsEnabled()
        show("남은 공부 다시 계획하기", lazy = true)
        compose.onNodeWithText("남은 공부 다시 계획하기").assertIsNotEnabled()
        show("최신 계획을 확인하거나 새 계획을 만들어 주세요.", lazy = true)
        host.capture("plan-view-remaining-study-blocked")

        val empty = plan.copy(current = plan.current.copy(schedules = emptyList()))
        host.storageScope = PlanStorageScope.DEMO
        host.seedSnapshot(com.swpp.licenseplanner.data.PlanSnapshot(listOf(empty)))
        host.recreate()
        openPlanView()
        show("돌려받은 공부 일정이 없어요")
        host.capture("plan-view-empty-message")
        show("내보낼 공부 일정이 없어요", lazy = true)
        compose.onNodeWithText("캘린더에 0개 일정 추가").assertIsNotEnabled()
        show("새 계획 만들기", lazy = true)
        compose.onNodeWithText("새 계획 만들기").assertIsEnabled()
        host.capture("plan-view-empty-actions")
    }

    @Test fun calendarFreeTimeMovesOneSessionAndKeepsEnteredWindows() = withApp { host ->
        val before = openSingleFreeTime(host)
        waitForText("캘린더에서 빈 시간")
        compose.waitUntil("Calendar results finish laying out", 10_000) {
            host.viewModel.freeTimeSearch.value?.phase == FreeTimePhase.Results &&
                compose.onAllNodesWithText("90분 · 입력한 공부 시간 밖 · 이번 한 번만", substring = true).onFirst().isDisplayed()
        }
        compose.onNodeWithText("이번 한 번만 옮기기").assertIsNotEnabled()
        click("11월 17일 (화) 10:00–11:30", substring = true)
        host.capture("calendar-suggestions")
        click("이번 한 번만 옮기기"); host.awaitSaved(); waitForText("이 기기에 저장했어요")
        val after = host.snapshot().plans.single()
        assertEquals(before.original, after.original)
        assertOnlyTargetTimesChanged(before.current.schedules, after.current.schedules, "study-1")
        assertEquals("2026/11/17/10/00", after.current.schedules.single { it.scheduleId == "study-1" }.startDate)
        assertEquals(2, host.adjustmentCalls.get()); assertEquals(0, host.calendarWrites.get())
        host.capture("calendar-moved")
        host.recreate(); openPlanView(1)
        assertEquals(after.current.schedules, host.snapshot().plans.single().current.schedules)
    }

    @Test fun calendarFreeTimeDeniedPermissionKeepsManualChoiceAvailable() = withApp { host ->
        host.calendarOutcome = com.swpp.licenseplanner.data.CalendarReadOutcome.PermissionMissing
        val before = openSingleFreeTime(host)
        waitForText("캘린더를 확인하지 못했어요"); host.capture("calendar-denied")
        click("시간 직접 옮기기"); show("시작 시간"); show(ADJUSTMENT_TITLE)
        assertEquals(before, host.snapshot().plans.single()); assertEquals(1, host.adjustmentCalls.get())
    }

    @Test fun calendarFreeTimeWithNoSlotKeepsThePlanAndManualChoice() = withApp { host ->
        val start = LocalDate.of(2026, 11, 9).atStartOfDay()
        host.calendarOutcome = com.swpp.licenseplanner.data.CalendarReadOutcome.Loaded(com.swpp.licenseplanner.data.CalendarBusySnapshot(
            emptyList(), 0, 1, listOf(com.swpp.licenseplanner.data.CalendarOccurrence("busy-all-days", null, start, start.plusDays(15), true))))
        val before = openSingleFreeTime(host)
        waitForText("시험 전까지 빈 시간이 없어요"); host.capture("calendar-no-time")
        click("시간 직접 옮기기"); show("시작 시간")
        assertEquals(before, host.snapshot().plans.single()); assertEquals(1, host.adjustmentCalls.get())
    }

    @Test fun remainingCalendarFreeTimePreviewConfirmsOneCompleteLocalBatch() = withApp { host ->
        val before = seedFullPlanForFreeTime(host)
        clickPlanAction("남은 공부 다시 계획하기"); click("남은 공부 다시 배치하기")
        show("남은 공부를 모두 배치할 시간이 없어요"); host.capture("remaining-calendar-no-fit")
        click("캘린더에서 빈 시간 찾기"); waitForText("캘린더 빈 시간으로 다시 계획")
        show("남은 시간 420분 · 일정 4개"); host.capture("remaining-calendar-preview")
        // Footer actions stay on screen however long the placement list is.
        compose.onNodeWithText("이번 한 번만 이 시간으로 다시 계획하기").assertIsDisplayed()
        compose.onNodeWithText("계획으로 돌아가기").assertIsDisplayed()
        click("이번 한 번만 이 시간으로 다시 계획하기"); host.awaitSaved()
        waitForText("4개 일정 · 이 기기에 저장했어요")
        assertEquals(2, host.remainingCalls.get()); assertEquals(0, host.calendarWrites.get())
        val after = host.snapshot().plans.single()
        assertEquals(before.original, after.original); assertEquals(4, after.current.schedules.size)
        assertEquals(before.current.schedules.map { it.topic }.toSet(), after.current.schedules.map { it.topic }.toSet())
        host.capture("remaining-calendar-result")
        showPlacedPlan(host)
        pressBack(); awaitPlanList()
    }

    @Test fun remainingCalendarFreeTimeCanRetryAnUnreadableCalendarAndReturnWhenNoTimeFits() = withApp { host ->
        val before = seedFullPlanForFreeTime(host)
        clickPlanAction("남은 공부 다시 계획하기")
        click("남은 공부 다시 배치하기")
        show("남은 공부를 모두 배치할 시간이 없어요")
        host.calendarOutcome = com.swpp.licenseplanner.data.CalendarReadOutcome.Unavailable
        click("캘린더에서 빈 시간 찾기")
        waitForText("캘린더를 읽지 못했어요")
        compose.onNodeWithText("다시 찾기").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("계획으로 돌아가기").assertIsDisplayed().assertIsEnabled()
        host.capture("remaining-calendar-unreadable")

        val start = LocalDate.of(2026, 11, 9).atStartOfDay()
        host.calendarOutcome = com.swpp.licenseplanner.data.CalendarReadOutcome.Loaded(com.swpp.licenseplanner.data.CalendarBusySnapshot(
            emptyList(), 0, 1, listOf(com.swpp.licenseplanner.data.CalendarOccurrence("busy-all-days", null, start, start.plusDays(15), true))))
        click("다시 찾기")
        waitForText("남은 공부를 모두 배치할 빈 시간이 없어요")
        compose.onNodeWithText("다시 찾기").assertDoesNotExist()
        compose.onNodeWithText("이번 한 번만 이 시간으로 다시 계획하기").assertDoesNotExist()
        compose.onNodeWithText("계획으로 돌아가기").assertIsDisplayed().assertIsEnabled()
        host.capture("remaining-calendar-no-time")
        click("계획으로 돌아가기")
        awaitPlanView()
        assertEquals(before, host.snapshot().plans.single())
        assertEquals(0, host.calendarWrites.get())
    }

    private fun withApp(block: (DemoUiHost) -> Unit) {
        val host = DemoUiHost(compose, testName.methodName)
        try {
            host.start()
            block(host)
        } catch (failure: Throwable) {
            try { host.capture("failure") } catch (diagnostic: Throwable) { failure.addSuppressed(diagnostic) }
            throw failure
        } finally {
            host.close()
        }
    }

    @Test
    fun cancellingGenerationKeepsTheSavedDraftAndAllInputs() = withApp { host ->
        startQuestions(host)
        answerAll(host)
        click("공부 시간 정하기")
        compose.runOnIdle {
            for (day in Weekday.entries.take(5)) host.viewModel.setDuration(day, 120)
        }
        val draft = CreationDraft(host.viewModel.form.value, CreationStep.AVAILABILITY, host.viewModel.questionIndex.value)
        host.seedDraft(draft)
        host.recreate()
        click("이어서 만들기")
        val inputs = host.viewModel.form.value
        val bytes = host.snapshotBytes()
        val gate = CompletableDeferred<Unit>()
        host.holdPlanning.set(gate)
        click("계획 만들기")
        compose.waitUntil(10_000) { host.viewModel.generation.value == GenerationState.Loading }
        waitForText("계획을 만드는 중이에요")
        pressBack()
        waitForText("계획 생성을 취소할까요?")
        show("계획 생성을 취소할까요?")
        host.capture("generation-cancel-dialog")
        click("뒤로 돌아가기 취소")
        compose.waitUntil(10_000) { compose.onAllNodesWithText("계획 생성을 취소할까요?").fetchSemanticsNodes().isEmpty() }
        assertEquals(GenerationState.Loading, host.viewModel.generation.value)
        pressBack()
        waitForText("계획 생성을 취소할까요?")
        click("계획 생성 취소")
        waitForText("매주 기본 시간")
        show("매주 기본 시간")
        gate.complete(Unit)
        compose.waitForIdle()
        assertEquals(inputs, host.viewModel.form.value)
        assertEquals(draft, host.viewModel.committedDraft.value)
        assertArrayEquals(bytes, host.snapshotBytes())
        assertEquals(draft, host.snapshot().creationDraft)
        assertTrue(host.snapshot().plans.isEmpty())
        assertEquals(0, host.planningCalls.get())
        host.capture("generation-cancel-kept-inputs")
    }

    @Test
    fun overnightAndOverrideGraphsFollowThePlanThroughALaterWeekMove() = withApp { host ->
        startQuestions(host)
        answerAll(host)
        click("공부 시간 정하기")
        val first = LocalDate.of(2026, 11, 16)
        val second = first.plusWeeks(1)
        compose.runOnIdle {
            host.viewModel.setDuration(Weekday.MONDAY, 90)
            host.viewModel.setDayStart(Weekday.MONDAY, LocalTime.of(23, 30))
            host.viewModel.setDuration(Weekday.WEDNESDAY, 240)
            host.viewModel.setDuration(Weekday.FRIDAY, 240)
            for (day in listOf(Weekday.THURSDAY, Weekday.SATURDAY, Weekday.SUNDAY)) host.viewModel.setDuration(day, 60)
            host.viewModel.clearWeek(second)
            host.viewModel.setDuration(Weekday.WEDNESDAY, 240, second)
            host.viewModel.setDayStart(Weekday.WEDNESDAY, LocalTime.of(7, 0), second)
            host.viewModel.showAvailabilityWeek(first)
        }
        show("23:30 – 다음 날 01:00 · 90분")
        click("계획 만들기")
        waitForText("내 계획")
        host.awaitSaved()
        val before = host.snapshot().plans.single()
        fun assertGraph(date: LocalDate, available: Long) {
            val label = "${date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.KOREAN)} ${monthDay(date)}"
            val planned = PlanGrouping.sessions(before.current.schedules).filter { !it.isExternal && it.start.toLocalDate() == date }
                .sumOf { it.durationMinutes }
            val value = "배정 ${PlanGrouping.formatDuration(planned)} / 가용 ${PlanGrouping.formatDuration(available)}"
            val node = hasText(value) and hasParent(hasAnyChild(hasText(label)))
            scrollTo(node)
            compose.onNode(node).assertIsDisplayed()
        }
        assertGraph(first, 30)
        assertGraph(first.plusDays(1), 60)
        host.capture("plan-overnight-graph")
        clickDescription("다음 주")
        assertGraph(second.plusDays(2), 240)
        compose.onAllNodesWithText("월 ${monthDay(second)}").assertCountEquals(0)
        host.capture("plan-override-graph")
        click("처음으로", lazy = true)
        openSavedPlanAdjustment()
        assertGraph(first, 30)
        assertGraph(first.plusDays(1), 60)
        show("공부 가능 23:30–다음 날 01:00")
        compose.onAllNodes(hasText("null", substring = true)).assertCountEquals(0)
        host.capture("adjustment-overnight-graph-label")
        clickDescription("다음 주")
        assertGraph(second.plusDays(2), 240)
        show("공부 가능 07:00–11:00")
        compose.onAllNodes(hasText("null", substring = true)).assertCountEquals(0)
        host.capture("adjustment-override-graph-label")
        clickDescription("이전 주")
        val target = PlanGrouping.sessions(before.current.schedules).first { !it.isExternal }
        clickSession(target)
        click("시간 직접 옮기기")
        val destination = second.plusDays(2).atTime(14, 0)
        compose.runOnIdle { host.viewModel.setManualStart(destination) }
        click("이 시간으로 옮기기")
        compose.waitUntil(10_000) { host.viewModel.adjustment.value.canConfirmOutsideAvailability }
        click("이번 한 번만 옮기기")
        waitForText("계획이 조정됐어요")
        host.awaitSaved()
        val after = host.snapshot().plans.single()
        assertOnlyTargetTimesChanged(before.current.schedules, after.current.schedules, target.scheduleId)
        val moved = after.current.schedules.single { it.scheduleId == target.scheduleId }
        assertEquals(WireDateTime.format(destination), moved.startDate)
        assertMoveResult(before.current.schedules.single { it.scheduleId == target.scheduleId }, moved, "이 기기에 저장했어요")
        host.capture("later-week-move-result")
        val weeks = PlanGrouping.weeks(after.current.schedules)
        showWeek(first, 0, weeks.size)
        revealMovedSession()
        showWeek(second, 1, weeks.size)
        assertSession(PlanGrouping.sessions(after.current.schedules).single { it.scheduleId == target.scheduleId }, selectable = true, moved = true)
        host.capture("later-week-destination-link")
        assertEquals(2, host.adjustmentCalls.get())
        assertEquals(0, host.calendarWrites.get())
    }

    private fun startQuestions(host: DemoUiHost) {
        show("아직 만든 계획이 없어요.")
        click("계획 생성", substring = true)
        click(CERTIFICATION_NAME)
        click("다음")
        show("필기 시험 준비")
        show("이 앱은 필기 시험 준비만 다뤄요. 실기 준비는 포함하지 않아요.")
        pickDate("준비 시작일", LocalDate.of(2026, 11, 16))
        pickExam(EXAM)
        show("준비 기간 56일")
        click("다음")
        chooseBeginner()
        awaitQuestions(host)
    }

    private fun chooseBeginner() {
        click(CurrentLevel.BEGINNER.label)
        click("예시 문항 풀기")
    }

    private fun awaitQuestions(host: DemoUiHost) {
        compose.waitUntil("Bundled onboarding questions load", 10_000) {
            host.viewModel.questionLoad.value == QuestionLoadState.Loaded
        }
        assertEquals("Current configured onboarding count", 18, host.viewModel.form.value.questions.size)
    }

    private fun answerAll(host: DemoUiHost) {
        val questions: List<Question> = host.viewModel.form.value.questions
        assertTrue(questions.isNotEmpty())
        for ((index, question) in questions.withIndex()) {
            show(question.prompt)
            show("${questions.size}문항 중 ${index + 1}번째")
            show("예시로 만든 문항이에요. 실제 기출이나 검증된 진단이 아니에요. 모르면 ‘모르겠음’을 골라 주세요. 0점으로 계산돼요.")
            click(UNKNOWN_LABEL)
            compose.onNodeWithText(UNKNOWN_LABEL).assertIsSelected()
            compose.onAllNodes(isSelected()).assertCountEquals(1)
            click(if (index == questions.lastIndex) "결과 보기" else "다음")
        }
        show("예시 문항 결과")
        show("${questions.size}문항 중 0문항 정답. ‘모르겠음’은 0점이에요.")
    }

    private fun generatePlan(host: DemoUiHost) {
        startQuestions(host)
        answerAll(host)
        click("공부 시간 정하기")
        for (day in Weekday.entries.filter { it.dayOfWeek.value <= 5 }) setWeekdayMinutes(day)
        compose.onNodeWithText("계획 만들기").assertIsEnabled()
        click("계획 만들기")
        waitForText("내 계획")
        host.awaitSaved()
        assertTrue(host.snapshot().plans.single().current.schedules.isNotEmpty())
    }

    /**
     * Demo plans fill every entered window, so an automatic move needs a window the plan left
     * empty: the saved plan's last week gains a Saturday window that no session uses, and the
     * move therefore lands in a later week than the source session.
     */
    private fun addEmptyWindowInLastWeek(host: DemoUiHost) {
        val snapshot = host.snapshot()
        val plan = snapshot.plans.single()
        val request = plan.original
        val lastMonday = checkNotNull(WireDateTime.parseOrNull(request.examDate)).toLocalDate().minusDays(1)
            .with(java.time.DayOfWeek.MONDAY)
        val lastWeek = request.weeklyAvailability +
            (Weekday.SATURDAY.key to listOf(com.swpp.licenseplanner.data.model.TimeWindow("10:00", durationMinutes = 120)))
        val overrides = request.availabilityOverrides + (lastMonday.format(DateTimeFormatter.ofPattern("yyyy/MM/dd")) to lastWeek)
        val studyDays = (listOf(request.weeklyAvailability) + overrides.values)
            .flatMap { week -> week.filterValues { it.isNotEmpty() }.keys }.toSet().size
        val original = request.copy(availabilityOverrides = overrides, studyDaysPerWeek = studyDays)
        host.seedSnapshot(snapshot.copy(plans = listOf(plan.copy(original = original))))
    }

    /** Minutes of the request's windows between preparation start and the exam. */
    private fun windowMinutes(original: com.swpp.licenseplanner.data.model.StudyPlanRequest): Long {
        val availability = checkNotNull(com.swpp.licenseplanner.data.AvailabilityWire.context(original))
        val start = checkNotNull(WireDateTime.parseOrNull(original.preparationStart))
        val exam = checkNotNull(WireDateTime.parseOrNull(original.examDate))
        return generateSequence(start.toLocalDate().minusDays(1)) { it.plusDays(1) }
            .takeWhile { !it.isAfter(exam.toLocalDate()) }
            .flatMap { origin -> availability.windowsStarting(origin).map { it.intervalOn(origin) } }
            .sumOf { maxOf(0L, Duration.between(maxOf(it.start, start), minOf(it.end, exam)).toMinutes()) }
    }

    private fun setWeekdayMinutes(day: Weekday) {
        val field = hasTestTag("study-duration-${day.key}")
        if (compose.onAllNodes(field).fetchSemanticsNodes().isEmpty()) clickDescription("${day.label}요일 시간대 추가")
        else compose.onNode(field).performScrollTo().performClick()
        setWindowTime("study-window-start", 18)
        setWindowTime("study-window-end", 19)
        click("확인")
        compose.onNode(field).assertTextEquals("18:00 – 19:00 · 60분")
    }

    private fun pickExam(date: LocalDate) = pickDate("필기 시험일", date)

    private fun pickDate(label: String, date: LocalDate) {
        val field = hasTestTag("date-$label")
        compose.onNode(field).performScrollTo().performClick()
        compose.onNodeWithContentDescription("Switch to text input mode").performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement(date.format(DateTimeFormatter.ofPattern("MMddyyyy", Locale.US)))
        click("확인")
        compose.onNode(field and hasText(date.toString())).assertExists()
    }

    private fun assertDateGate() {
        show("시험일은 준비 시작일보다 뒤여야 해요.")
        compose.onNodeWithText("다음").assertIsNotEnabled().performClick()
        show("필기 시험 준비")
    }

    private fun assertUnknownOnly(normal: String) {
        compose.onNodeWithText(UNKNOWN_LABEL).assertIsSelected()
        compose.onNodeWithText(normal).assertIsNotSelected()
        compose.onAllNodes(isSelected()).assertCountEquals(1)
    }

    private fun clickPlanAction(label: String) {
        awaitPlanView()
        click(label, lazy = true)
    }

    /** The plan view's top bar title; Home's 「내 계획」 is a button instead. */
    private fun planViewTitle() = hasText("내 계획") and !hasClickAction()

    private fun awaitPlanView() = compose.waitUntil("The plan view is shown", 10_000) {
        compose.onAllNodes(planViewTitle()).fetchSemanticsNodes().isNotEmpty() &&
            compose.onAllNodesWithText(PLAN_LIST_HEADING).fetchSemanticsNodes().isEmpty()
    }

    /** On a plan that can no longer be adjusted, starting over is offered above returning to the list. */
    private fun assertCreateAboveList() {
        val create = compose.onNodeWithText("새 계획 만들기").getBoundsInRoot().top
        val list = compose.onNodeWithText("목록으로").getBoundsInRoot().top
        assertTrue("새 계획 만들기 is above 목록으로", create < list)
    }

    private fun awaitPlanList() {
        waitForText(PLAN_LIST_HEADING)
        compose.onAllNodes(planViewTitle()).assertCountEquals(0)
    }

    /** Home 「내 계획」, then the plan's row in 「저장된 계획」. */
    private fun openPlanView(ordinal: Int = 1) {
        click("내 계획")
        awaitPlanList()
        click("계획 $ordinal · $CERTIFICATION_NAME")
        awaitPlanView()
    }

    /** The shown plan view's 「일정 조정하기」. */
    private fun adjustShownPlan() {
        click("일정 조정하기", lazy = true)
        waitForText(ADJUSTMENT_TITLE)
        compose.onAllNodes(planViewTitle()).assertCountEquals(0)
    }

    private fun openSavedPlanAdjustment() {
        show("저장된 계획 1개가 있어요.")
        click("내 계획")
        awaitPlanList()
        val label = "계획 1 · $CERTIFICATION_NAME"
        compose.onAllNodesWithText(label).assertCountEquals(1)
        click(label)
        awaitPlanView()
        adjustShownPlan()
    }

    private fun assertNoPlanActionsOnAdjustment(plan: com.swpp.licenseplanner.domain.SessionPlan) {
        show(ADJUSTMENT_TITLE)
        for (label in listOf("계획 메뉴", "남은 공부 다시 계획하기", "이 계획 다시 만들기", "캘린더에 ${plan.studyCount}개 일정 추가")) {
            compose.onAllNodesWithText(label).assertCountEquals(0)
        }
    }

    /** Shows each plan-view action; the input action only while the plan's inputs are held. */
    private fun assertPlanViewActions(plan: com.swpp.licenseplanner.domain.SessionPlan, inputsHeld: Boolean) {
        for (label in listOf("캘린더에 ${plan.studyCount}개 일정 추가", "남은 공부 다시 계획하기", "이 계획 다시 만들기", "일정 조정하기")) {
            show(label, lazy = true)
        }
        if (inputsHeld) show(EDIT_INPUTS, lazy = true)
        show("처음으로", lazy = true)
        if (!inputsHeld) compose.onNodeWithText(EDIT_INPUTS).assertDoesNotExist()
        compose.onNodeWithText("새 계획 만들기").assertDoesNotExist()
    }

    private fun assertOnlyTargetTimesChanged(before: List<ScheduleRow>, after: List<ScheduleRow>, id: String) {
        assertEquals(before.map { it.scheduleId }.toSet(), after.map { it.scheduleId }.toSet())
        assertEquals(before.size, after.size)
        val changed = after.single { it.scheduleId == id }
        val original = before.single { it.scheduleId == id }
        assertEquals(original.copy(startDate = changed.startDate, endDate = changed.endDate), changed)
        assertNotEquals(original.startDate, changed.startDate)
        assertEquals(Duration.between(WireDateTime.parseOrNull(original.startDate), WireDateTime.parseOrNull(original.endDate)),
            Duration.between(WireDateTime.parseOrNull(changed.startDate), WireDateTime.parseOrNull(changed.endDate)))
        assertEquals(before.filter { it.scheduleId != id }.associateBy { it.scheduleId },
            after.filter { it.scheduleId != id }.associateBy { it.scheduleId })
    }

    private fun sessionLabel(session: PlanSession, moved: Boolean = false): String {
        val date = session.start.toLocalDate()
        return "${monthDay(date)} ${date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.KOREAN)} ${time(session)} ${session.topic}" +
            if (moved) " 옮긴 일정" else ""
    }

    private fun clickSession(session: PlanSession) = click(sessionLabel(session))

    private fun reportAction(session: PlanSession, edit: Boolean) =
        "${sessionLabel(session)} ${if (edit) "기록 수정하기" else "공부 기록하기"}"

    private fun openReport(session: PlanSession, edit: Boolean) {
        clickDescription(reportAction(session, edit))
        waitForText("공부는 어땠나요?")
        show("공부 기록")
    }

    private fun returnTo(title: String, host: DemoUiHost) {
        waitForText(title)
        host.awaitSaved()
        compose.onNodeWithText("공부는 어땠나요?").assertDoesNotExist()
    }

    private fun minutesField() = compose.onNode(hasTestTag("actual-minutes"))

    private fun reportOf(host: DemoUiHost, session: PlanSession): StudyCheckIn? =
        host.snapshot().plans.single().checkIns[session.scheduleId]

    private fun movedMarker() = hasText("옮긴 일정", substring = true) and
        SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)

    private fun assertSession(session: PlanSession, selectable: Boolean, moved: Boolean = false) {
        assertTrue(session.topic.isNotBlank())
        assertTrue(session.durationMinutes > 0)
        if (selectable) {
            show(sessionLabel(session, moved))
        } else {
            // A filled plan repeats a topic on later days; days are listed in order, so the first copy is this session's.
            val topic = hasText(session.topic)
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(topic)
            compose.onAllNodes(topic).onFirst().assertIsDisplayed()
            compose.onAllNodesWithText(time(session)).fetchSemanticsNodes().also { assertTrue(it.isNotEmpty()) }
            show(monthDay(session.start.toLocalDate()), lazy = true)
        }
    }

    private fun assertMoveResult(sent: ScheduleRow, accepted: ScheduleRow, saved: String) {
        val lines = listOf("계획이 조정됐어요", accepted.topic, "원래 시간: ${resultInterval(sent)}", "바뀐 시간: ${resultInterval(accepted)}", saved)
        for (line in lines) {
            val inResult = hasText(line) and hasParent(hasTestTag("move-result"))
            scrollTo(inResult)
            compose.onNode(inResult).assertIsDisplayed()
        }
    }

    private fun resultInterval(row: ScheduleRow): String {
        val start = checkNotNull(WireDateTime.parseOrNull(row.startDate))
        val end = checkNotNull(WireDateTime.parseOrNull(row.endDate))
        val endDay = if (end.toLocalDate() == start.toLocalDate()) "" else "${resultDay(end.toLocalDate())} "
        return "${resultDay(start.toLocalDate())} ${WireDateTime.formatTime(start.toLocalTime())}–$endDay${WireDateTime.formatTime(end.toLocalTime())}"
    }

    private fun resultDay(date: LocalDate) =
        "${date.year}년 ${monthDay(date)}(${date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.KOREAN)})"

    private fun showWeek(monday: LocalDate, index: Int, count: Int) {
        show("${index + 1} / ${count}주")
        val sunday = monday.plusDays(6)
        val end = if (sunday.month == monday.month) "${sunday.dayOfMonth}일" else monthDay(sunday)
        show("${monthDay(monday)} – $end")
    }

    private fun revealMovedSession() {
        // HE navigation retains the source week until its destination link is selected.
        show("조정 전 위치 · 임시")
        click("이동 위치:", substring = true)
        compose.waitUntil("The destination week shows the moved study session", 10_000) {
            compose.onAllNodes(movedMarker()).fetchSemanticsNodes().size == 1
        }
    }

    private fun time(session: PlanSession) = "${WireDateTime.formatTime(session.start.toLocalTime())}–${WireDateTime.formatTime(session.end.toLocalTime())}"
    private fun monthDay(date: LocalDate) = "${date.monthValue}월 ${date.dayOfMonth}일"

    private fun waitForText(text: String) = compose.waitUntil("Screen shows $text", 10_000) {
        compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }

    private fun dialogFirst(matcher: SemanticsMatcher): SemanticsMatcher {
        val dialog = matcher and hasAnyAncestor(isDialog())
        return if (compose.onAllNodes(dialog).fetchSemanticsNodes().isNotEmpty()) dialog else matcher
    }

    private fun show(text: String, lazy: Boolean = false) {
        val matcher = dialogFirst(hasText(text))
        scrollTo(matcher, lazy)
        compose.onNode(matcher).assertIsDisplayed()
    }

    private fun click(text: String, substring: Boolean = false, lazy: Boolean = false) {
        if (compose.onAllNodes(hasTestTag("actual-minutes") and isFocused() and isEnabled()).fetchSemanticsNodes().isNotEmpty()) {
            compose.waitUntil("The report keyboard finishes opening before the next tap", 5_000) {
                ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
                    ?.isVisible(WindowInsetsCompat.Type.ime()) == true
            }
            compose.waitForIdle()
        }
        val matcher = dialogFirst(hasText(text, substring = substring) and hasClickAction())
        scrollTo(matcher, lazy)
        compose.onNode(matcher).assertIsEnabled().performClick()
    }

    private fun clickDescription(description: String) {
        val matcher = hasContentDescription(description) and hasClickAction()
        scrollTo(matcher)
        compose.onNode(matcher).performClick()
    }

    private fun scrollTo(matcher: SemanticsMatcher, lazy: Boolean = false) {
        if (compose.onAllNodes(matcher and hasAnyAncestor(isDialog())).fetchSemanticsNodes().isNotEmpty()) {
            if (!compose.onNode(matcher).isDisplayed()) compose.onNode(matcher).performScrollTo()
            return
        }
        if (compose.onNode(matcher).isDisplayed() &&
            compose.onAllNodes(matcher and hasAnyAncestor(hasScrollAction())).fetchSemanticsNodes().isEmpty()) return
        if (lazy || compose.onAllNodes(hasScrollToIndexAction()).fetchSemanticsNodes().isNotEmpty()) {
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(matcher)
        } else compose.onNode(matcher).performScrollTo()
    }

    companion object {
        private val EXAM = LocalDate.of(2027, 1, 11)
        private const val PLAN_LIST_HEADING = "어떤 계획을 볼까요?"
        private const val ADJUSTMENT_TITLE = "일정 조정"
        private const val EDIT_INPUTS = "입력 바꿔 새 계획 만들기"
    }
}
