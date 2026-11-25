package com.swpp.licenseplanner.domain

import com.swpp.licenseplanner.data.model.ScheduleRow
import java.time.LocalDate

const val CERTIFICATION_ID = "computer_specialist_level_2"
const val CERTIFICATION_NAME = "컴퓨터활용능력 2급"

/** Everything the input flow collects. Immutable; the ViewModel replaces it. */
data class PlanFormState(
    val certificationId: String? = null,
    val preparationStart: LocalDate,
    val examDate: LocalDate? = null,
    val level: CurrentLevel? = null,
    /** The successfully loaded question set, kept for the whole form flow. */
    val questions: List<Question> = emptyList(),
    /** Selected choice key by question ID. */
    val answers: Map<Int, String> = emptyMap(),
    /** The recurring base week, including windows the editor does not show. */
    val availability: Map<Weekday, DayAvailability> = defaultAvailability(),
    /**
     * Whole-week replacements keyed by Monday. A week with no windows is an explicit no-study
     * week; a missing key uses [availability].
     */
    val availabilityOverrides: Map<LocalDate, Map<Weekday, DayAvailability>> = emptyMap(),
    /** Expanded device-calendar occurrences for the current preparation range. */
    val busySchedules: List<ScheduleRow> = emptyList(),
) {
    val datesError: String? get() = Validation.datesError(preparationStart, examDate)
    val preparationDays: Long?
        get() = examDate?.takeIf { datesError == null }?.let { Validation.preparationDays(preparationStart, it) }
    val allAnswered: Boolean get() = Scoring.allAnswered(questions, answers)
    val availabilityError: String? get() = Validation.availabilityError(availability, availabilityOverrides)

    /** The full selected context as resolver windows. */
    val studyAvailability: StudyAvailability
        get() = StudyAvailability(availability.toWeekWindows(), availabilityOverrides.mapValues { it.value.toWeekWindows() })

    /**
     * Mondays of the weeks in which study windows may start: from the preparation start up to the
     * day before the exam. Empty while the dates are incomplete or invalid. Only these weeks can be
     * replaced, emptied or reset; replacements for other weeks are kept but not shown.
     */
    val studyWeeks: List<LocalDate>
        get() {
            val exam = examDate?.takeIf { datesError == null } ?: return emptyList()
            val last = StudyAvailability.mondayOf(exam.minusDays(1))
            return generateSequence(StudyAvailability.mondayOf(preparationStart)) { it.plusWeeks(1) }
                .takeWhile { !it.isAfter(last) }
                .toList()
        }

    /** [selected] when it is a study week, otherwise the first study week (null without one). */
    fun shownWeek(selected: LocalDate?): LocalDate? = studyWeeks.let { weeks -> selected?.takeIf { it in weeks } ?: weeks.firstOrNull() }

    /** How the week starting [monday] gets its windows. */
    fun weekSource(monday: LocalDate): WeekSource = when (val days = availabilityOverrides[monday]) {
        null -> WeekSource.BASE
        else -> if (days.toWeekWindows().isEmpty()) WeekSource.EMPTY else WeekSource.REPLACED
    }

    /**
     * Usable portions of the previous Sunday's windows that run into [monday], clipped to the
     * preparation period. They belong to the previous week even when this week is replaced.
     */
    fun incomingWindows(monday: LocalDate): List<StudyInterval> {
        val exam = examDate?.takeIf { datesError == null } ?: return emptyList()
        val weekStart = monday.atStartOfDay()
        val lower = maxOf(weekStart, preparationStart.atStartOfDay())
        val upper = exam.atStartOfDay()
        return studyAvailability.windowsFor(monday)
            .filter { it.start.isBefore(weekStart) }
            .map { StudyInterval(maxOf(it.start, lower), minOf(it.end, upper)) }
            .filter { it.start.isBefore(it.end) }
    }
}

/** Base inheritance (no replacement key), a replaced week, or an explicit no-study week. */
enum class WeekSource { BASE, REPLACED, EMPTY }
