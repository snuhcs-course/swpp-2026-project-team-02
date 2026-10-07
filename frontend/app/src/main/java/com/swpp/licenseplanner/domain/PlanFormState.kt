package com.swpp.licenseplanner.domain

import java.time.LocalDate

const val CERTIFICATION_ID = "computer_specialist_level_2"
const val CERTIFICATION_NAME = "컴퓨터활용능력 2급"

/** Everything the input flow collects (design D4). Immutable; the ViewModel replaces it. */
data class PlanFormState(
    val certificationId: String? = null,
    val preparationStart: LocalDate,
    val examDate: LocalDate? = null,
    val level: CurrentLevel? = null,
    /** The successfully loaded question set, kept for the whole form flow. */
    val questions: List<Question> = emptyList(),
    /** Selected choice key by question ID. */
    val answers: Map<Int, String> = emptyMap(),
    val availability: Map<Weekday, DayAvailability> = defaultAvailability(),
) {
    val datesError: String? get() = Validation.datesError(preparationStart, examDate)
    val preparationDays: Long?
        get() = examDate?.takeIf { datesError == null }?.let { Validation.preparationDays(preparationStart, it) }
    val allAnswered: Boolean get() = Scoring.allAnswered(questions, answers)
    val availabilityError: String? get() = Validation.availabilityError(availability)
}
