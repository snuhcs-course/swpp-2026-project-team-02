package com.swpp.licenseplanner.domain

import com.swpp.licenseplanner.data.model.ScheduleRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier
import java.time.LocalDate
import java.time.LocalTime

class CreationDraftTest {
    private val today = LocalDate.of(2026, 11, 18) // Wednesday
    private val fresh = PlanFormState(preparationStart = today)

    private val questions = listOf(
        Question(3, "합계 함수는?", linkedMapOf("A" to "=COUNT", "B" to "=SUM"), "B", 1.0),
        Question(7, "셀 참조는?", linkedMapOf("1" to "상대", "2" to "절대", "3" to "혼합"), "2", 1.5),
    )

    private val evening = DayAvailability(true, LocalTime.of(18, 0), 90, listOf(WindowSpec(LocalTime.of(7, 0), 30)))
    private val hiddenOnly = DayAvailability(false, LocalTime.of(20, 30), 120, listOf(WindowSpec(LocalTime.of(23, 30), 90)))
    private val base = defaultAvailability() + (Weekday.MONDAY to evening) + (Weekday.SUNDAY to hiddenOnly)

    /** Answers part of the quiz, edits a replaced week and keeps an explicitly empty one. */
    private val inProgress = fresh.copy(
        certificationId = CERTIFICATION_ID,
        examDate = LocalDate.of(2026, 12, 15),
        level = CurrentLevel.INTERMEDIATE,
        questions = questions,
        answers = mapOf(3 to "B", 7 to UNKNOWN_KEY),
        availability = base,
        availabilityOverrides = mapOf(
            LocalDate.of(2026, 11, 23) to defaultAvailability() + (Weekday.TUESDAY to DayAvailability(true, LocalTime.of(21, 0), 60)),
            LocalDate.of(2026, 11, 30) to emptyWeek(),
        ),
        busySchedules = listOf(ScheduleRow("41", "2026/11/24/09/00", "2026/11/24/12/00", "바쁜 일정")),
    )

    @Test fun everyInputStepIsACheckpointEvenBeforeAnythingIsEntered() {
        assertEquals(
            listOf("CERTIFICATION", "DATES", "LEVEL", "QUESTIONS", "RESULTS", "AVAILABILITY"),
            CreationStep.entries.map { it.name },
        )
        for (step in CreationStep.entries) {
            assertTrue(step.name, CreationDraft(fresh, step).isValid)
            assertTrue(step.name, CreationDraft(inProgress, step, questionIndex = 1, availabilityWeek = LocalDate.of(2026, 11, 23)).isValid)
        }
    }

    @Test fun untouchedDefaultsHoldNoInputButAnyEditDoes() {
        assertFalse(fresh.hasInputBeyondDefaults(today))
        assertTrue(fresh.hasInputBeyondDefaults(today.plusDays(1)))
        val edits = mapOf(
            "certification" to fresh.copy(certificationId = CERTIFICATION_ID),
            "start" to fresh.copy(preparationStart = today.plusDays(2)),
            "exam" to fresh.copy(examDate = today.plusDays(30)),
            "level" to fresh.copy(level = CurrentLevel.BEGINNER),
            "questions" to fresh.copy(questions = questions),
            "answer" to fresh.copy(questions = questions, answers = mapOf(3 to "A")),
            "base day" to fresh.copy(availability = defaultAvailability() + (Weekday.FRIDAY to DayAvailability(enabled = true))),
            "empty week" to fresh.copy(availabilityOverrides = mapOf(LocalDate.of(2026, 11, 16) to emptyWeek())),
            "busy rows" to fresh.copy(busySchedules = inProgress.busySchedules),
        )
        for ((case, edited) in edits) assertTrue(case, edited.hasInputBeyondDefaults(today))
    }

    @Test fun incompleteOrNotYetValidInputStaysAValidDraft() {
        val forms = mapOf(
            "no exam date" to inProgress.copy(examDate = null),
            "exam before start" to inProgress.copy(examDate = today.minusDays(3)),
            "same-day exam" to inProgress.copy(examDate = today),
            "no study day" to inProgress.copy(availability = defaultAvailability(), availabilityOverrides = emptyMap()),
            "overlapping windows" to inProgress.copy(
                availability = defaultAvailability() +
                    (Weekday.MONDAY to DayAvailability(true, LocalTime.of(18, 0), 120, listOf(WindowSpec(LocalTime.of(18, 30), 30)))),
            ),
            "replacement outside the study weeks" to inProgress.copy(availabilityOverrides = mapOf(LocalDate.of(2027, 3, 1) to emptyWeek())),
            "questions without answers" to inProgress.copy(answers = emptyMap()),
            "no level" to inProgress.copy(level = null),
        )
        for ((case, form) in forms) assertTrue(case, CreationDraft(form, CreationStep.AVAILABILITY).isValid)
        assertTrue(inProgress.copy(examDate = today.minusDays(3)).datesError != null)
        assertTrue(inProgress.copy(availability = defaultAvailability(), availabilityOverrides = emptyMap()).availabilityError != null)
    }

    @Test fun theDraftKeepsQuestionsAnswersWindowsAndBusyRowsAsGiven() {
        val draft = CreationDraft(inProgress, CreationStep.QUESTIONS, questionIndex = 1)
        assertEquals(questions, draft.form.questions)
        assertEquals(listOf("1", "2", "3"), draft.form.questions[1].choices.keys.toList())
        assertEquals(mapOf(3 to "B", 7 to UNKNOWN_KEY), draft.form.answers)
        assertEquals(listOf(WindowSpec(LocalTime.of(23, 30), 90)), draft.form.availability.getValue(Weekday.SUNDAY).windows())
        assertEquals(WeekSource.REPLACED, draft.form.weekSource(LocalDate.of(2026, 11, 23)))
        assertEquals(WeekSource.EMPTY, draft.form.weekSource(LocalDate.of(2026, 11, 30)))
        assertEquals(inProgress.busySchedules, draft.form.busySchedules)
    }

    @Test fun shapesTheInputScreensCannotHoldAreInvalid() {
        val draft = CreationDraft(inProgress, CreationStep.QUESTIONS, questionIndex = 1, availabilityWeek = LocalDate.of(2026, 11, 23))
        assertTrue(draft.isValid)
        val noSunday = inProgress.availability - Weekday.SUNDAY
        val invalid = mapOf(
            "other certification" to draft.copy(form = inProgress.copy(certificationId = "word_processor")),
            "duplicate question" to draft.copy(form = inProgress.copy(questions = questions + questions[0])),
            "answer without question" to draft.copy(form = inProgress.copy(answers = mapOf(4 to "A"))),
            "answer outside choices" to draft.copy(form = inProgress.copy(answers = mapOf(3 to "C"))),
            "cursor past the end" to draft.copy(questionIndex = 2),
            "negative cursor" to draft.copy(questionIndex = -1),
            "cursor without questions" to draft.copy(form = inProgress.copy(questions = emptyList(), answers = emptyMap())),
            "week not a Monday" to draft.copy(availabilityWeek = LocalDate.of(2026, 11, 24)),
            "missing weekday" to draft.copy(form = inProgress.copy(availability = noSunday)),
            "zero duration" to draft.copy(form = inProgress.copy(availability = base + (Weekday.MONDAY to evening.copy(durationMinutes = 0)))),
            "too long hidden window" to draft.copy(
                form = inProgress.copy(availability = base + (Weekday.MONDAY to evening.copy(extras = listOf(WindowSpec(LocalTime.of(7, 0), 1440))))),
            ),
            "seconds in a start" to draft.copy(form = inProgress.copy(availability = base + (Weekday.MONDAY to evening.copy(start = LocalTime.of(18, 0, 30))))),
            "replacement not on a Monday" to draft.copy(form = inProgress.copy(availabilityOverrides = mapOf(LocalDate.of(2026, 11, 25) to emptyWeek()))),
            "replacement missing a weekday" to draft.copy(form = inProgress.copy(availabilityOverrides = mapOf(LocalDate.of(2026, 11, 23) to noSunday))),
        )
        for ((case, value) in invalid) assertFalse(case, value.isValid)
    }

    @Test fun resumedWeekFallsBackToTheFirstStudyWeekOnlyWhenNoLongerSelectable() {
        val draft = CreationDraft(inProgress, CreationStep.AVAILABILITY, availabilityWeek = LocalDate.of(2026, 11, 30))
        assertEquals(LocalDate.of(2026, 11, 30), draft.shownWeek)
        assertEquals(LocalDate.of(2026, 11, 16), draft.copy(availabilityWeek = LocalDate.of(2027, 1, 4)).shownWeek)
        assertEquals(LocalDate.of(2026, 11, 16), draft.copy(availabilityWeek = null).shownWeek)
        assertNull(draft.copy(form = inProgress.copy(examDate = null)).shownWeek)
    }

    @Test fun theDraftHoldsOnlyCommittedInputAndPositions() {
        fun fields(type: Class<*>) = type.declaredFields.filterNot { Modifier.isStatic(it.modifiers) || it.isSynthetic }.map { it.name }.toSet()
        assertEquals(setOf("form", "step", "questionIndex", "availabilityWeek"), fields(CreationDraft::class.java))
        assertEquals(
            setOf(
                "certificationId", "preparationStart", "examDate", "level", "questions", "answers",
                "availability", "availabilityOverrides", "busySchedules",
            ),
            fields(PlanFormState::class.java),
        )
    }
}
