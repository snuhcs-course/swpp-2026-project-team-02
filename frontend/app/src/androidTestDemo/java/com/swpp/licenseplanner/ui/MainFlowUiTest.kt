package com.swpp.licenseplanner.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.swpp.licenseplanner.data.model.ScheduleRow
import com.swpp.licenseplanner.domain.CERTIFICATION_NAME
import com.swpp.licenseplanner.domain.CurrentLevel
import com.swpp.licenseplanner.domain.PlanGrouping
import com.swpp.licenseplanner.domain.PlanSession
import com.swpp.licenseplanner.domain.Question
import com.swpp.licenseplanner.domain.UNKNOWN_LABEL
import com.swpp.licenseplanner.domain.Weekday
import com.swpp.licenseplanner.domain.WireDateTime
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
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
        if (compose.onNode(matcher).isDisplayed()) return
        if (lazy || compose.onAllNodes(hasScrollToIndexAction()).fetchSemanticsNodes().isNotEmpty()) {
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(matcher)
        } else compose.onNode(matcher).performScrollTo()
    }

    companion object {
        private val EXAM = LocalDate.of(2027, 1, 11)
    }
}
