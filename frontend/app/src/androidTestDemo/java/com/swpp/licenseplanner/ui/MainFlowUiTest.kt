package com.swpp.licenseplanner.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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
        openSavedPlan()
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
        show("이 계획은 더 이상 조정할 수 없어요")
        assertEquals(beforeReload, host.snapshot())
        assertEquals(saves, host.saveCalls.get())
        assertEquals(1, host.planningCalls.get())
        assertEquals(0, host.adjustmentCalls.get())
        assertEquals(1, host.reloadCalls.get())
        host.capture("missing-plan-save-failed")

        click("목록으로")
        click("새 계획 만들기")
        show("진행 중인 작업이 끝나거나 권한 창을 닫은 뒤 다시 선택해 주세요.")
        host.capture("new-creation-waiting")
        compose.onNodeWithText("임시 저장한 입력이 있어요.", substring = true).assertDoesNotExist()
        assertEquals(beforeReload, host.snapshot())
        assertEquals(saves, host.saveCalls.get())
        click("확인")
        click("계획 1 · $CERTIFICATION_NAME")
        show("이 계획은 더 이상 조정할 수 없어요")

        host.failSaves.set(false)
        host.holdSaves.set(null)
        click("다시 저장하기")
        host.awaitSaved()
        compose.onNodeWithText("새 계획 만들기").assertIsEnabled()
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
        show("필기 시험 준비 계획")
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
        show("보통 언제 공부할 수 있나요?")
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
    fun restoresAdjustsAndReopensSavedPlanWithoutReplayingAdjustment() = withApp { host ->
        generatePlan(host)
        val before = host.snapshot().plans.single()
        val target = PlanGrouping.sessions(before.current.schedules).first()
        host.recreate()
        openSavedPlan()
        compose.onNodeWithText("선택한 일정 조정하기").assertIsNotEnabled()
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
        showWeek(weeks[destinationIndex].monday, destinationIndex, weeks.size)
        assertSession(moved, selectable = true, moved = true)
        compose.onAllNodes(movedMarker()).assertCountEquals(1)
        host.capture("adjusted")
        host.recreate()
        openSavedPlan()
        compose.onNodeWithText("선택한 일정 조정하기").assertIsNotEnabled()
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
        openSavedPlan()
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
        host.recreate()
        openSavedPlan()
        openReport(target, edit = false)
        show(topic)
        host.capture("long-reopened-editor")
        click("다 했어요")
        minutesField().performTextReplacement("20")
        click("기록 저장하기")
        returnTo("실패한 일정 선택", host)
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
        openSavedPlan()
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
        returnTo("실패한 일정 선택", host)
        compose.onNodeWithText(sessionLabel(selected)).assertIsSelected()
        compose.onNodeWithText(sessionLabel(reported)).assertIsNotSelected()
        compose.onNodeWithText("선택한 일정 조정하기").assertIsEnabled()
        show("못 했어요 · 실제 0분")
        val saved = host.snapshot().plans.single()
        assertEquals(mapOf(reported.scheduleId to StudyCheckIn(StudyCheckInStatus.Missed, 0)), saved.checkIns)
        assertEquals(plan.current, saved.current)
        assertEquals(0, host.adjustmentCalls.get())

        host.recreate()
        openSavedPlan()
        show("못 했어요 · 실제 0분")
        compose.onNodeWithContentDescription(reportAction(reported, edit = true)).assertIsEnabled()
        compose.onAllNodes(isSelected()).assertCountEquals(0)
        assertEquals(saved, host.snapshot().plans.single())
        assertEquals(0, host.adjustmentCalls.get())
        host.capture("report-saved")
    }

    @Test
    fun failedReportSaveStaysUnsavedUntilLocalRetryAndFollowsTheMovedSession() = withApp { host ->
        generatePlan(host)
        host.recreate()
        openSavedPlan()
        val before = host.snapshot().plans.single()
        val target = PlanGrouping.sessions(before.current.schedules).first { !it.isExternal }
        openReport(target, edit = false)
        click("다 했어요")
        minutesField().performTextReplacement("20")
        click("기록 저장하기")
        returnTo("실패한 일정 선택", host)
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
        waitForText("실패한 일정 선택")
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
        show("일부 했어요 · 실제 45분")

        host.recreate()
        openSavedPlan()
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
        host.recreate()
        openSavedPlan()
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
        compose.onNodeWithText("시간 직접 옮기기").assertIsNotEnabled()
        host.capture("move-saving")

        host.failSaves.set(true)
        host.holdSaves.set(null)
        pending.complete(Unit)
        waitForText("아직 이 기기에 저장하지 못했어요")
        assertMoveResult(sent, accepted, saved = "아직 이 기기에 저장하지 못했어요")
        show("기기에 계획을 저장하지 못했어요")
        compose.onAllNodesWithText("다시 저장하기").assertCountEquals(1)
        compose.onNodeWithText("이 기기에 저장했어요").assertDoesNotExist()
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

    private fun setWeekdayMinutes(day: Weekday) {
        val field = hasTestTag("study-duration-${day.key}")
        compose.onNode(field).performScrollTo().performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement("60")
        click("확인")
        compose.onNode(field).assertTextEquals("60분")
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

    private fun openSavedPlan() {
        show("저장된 계획 1개가 있어요.")
        click("계획 조정")
        val label = "계획 1 · $CERTIFICATION_NAME"
        compose.onAllNodesWithText(label).assertCountEquals(1)
        click(label)
        show("실패한 일정 선택")
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
            show(session.topic, lazy = true)
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

    private fun time(session: PlanSession) = "${WireDateTime.formatTime(session.start.toLocalTime())}–${WireDateTime.formatTime(session.end.toLocalTime())}"
    private fun monthDay(date: LocalDate) = "${date.monthValue}월 ${date.dayOfMonth}일"

    private fun waitForText(text: String) = compose.waitUntil("Screen shows $text", 10_000) {
        compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }

    private fun show(text: String, lazy: Boolean = false) {
        scrollTo(hasText(text), lazy)
        compose.onNodeWithText(text).assertIsDisplayed()
    }

    private fun click(text: String, substring: Boolean = false, lazy: Boolean = false) {
        if (compose.onAllNodes(hasTestTag("actual-minutes") and isFocused() and isEnabled()).fetchSemanticsNodes().isNotEmpty()) {
            compose.waitUntil("The report keyboard finishes opening before the next tap", 5_000) {
                ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
                    ?.isVisible(WindowInsetsCompat.Type.ime()) == true
            }
            compose.waitForIdle()
        }
        val matcher = hasText(text, substring = substring) and hasClickAction()
        scrollTo(matcher, lazy)
        compose.onNode(matcher).assertIsEnabled().performClick()
    }

    private fun clickDescription(description: String) {
        val matcher = hasContentDescription(description) and hasClickAction()
        scrollTo(matcher)
        compose.onNode(matcher).performClick()
    }

    private fun scrollTo(matcher: SemanticsMatcher, lazy: Boolean = false) {
        if (compose.onNode(matcher).isDisplayed() &&
            compose.onAllNodes(matcher and hasAnyAncestor(hasScrollAction())).fetchSemanticsNodes().isEmpty()) return
        if (lazy || compose.onAllNodes(hasScrollToIndexAction()).fetchSemanticsNodes().isNotEmpty()) {
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(matcher)
        } else compose.onNode(matcher).performScrollTo()
    }

    companion object {
        private val EXAM = LocalDate.of(2027, 1, 11)
    }
}
