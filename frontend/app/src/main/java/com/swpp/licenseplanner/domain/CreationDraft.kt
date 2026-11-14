package com.swpp.licenseplanner.domain

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

/** The input screens a saved creation can reopen, in flow order. */
enum class CreationStep { CERTIFICATION, DATES, LEVEL, QUESTIONS, RESULTS, AVAILABILITY }

/**
 * Unfinished plan creation as the learner explicitly saved it: the committed form inputs, the
 * screen to reopen, the question shown there and the selected availability week. Only input is
 * kept; an exam date still being edited, requests, results and calendar work are never part of it.
 *
 * The form may be incomplete or fail normal input validation (no exam date, dates out of order,
 * no study day); those stay editable after resuming. [isValid] only rejects shapes the input
 * screens could never hold.
 */
data class CreationDraft(
    val form: PlanFormState,
    val step: CreationStep,
    /** Position in [PlanFormState.questions]; 0 while no questions are loaded. */
    val questionIndex: Int = 0,
    /** Monday of the availability week last shown; null for the first study week. */
    val availabilityWeek: LocalDate? = null,
) {
    val isValid: Boolean
        get() {
            if (form.certificationId != null && form.certificationId != CERTIFICATION_ID) return false
            val questions = form.questions
            if (questions.map { it.id }.toSet().size != questions.size) return false
            val byId = questions.associateBy { it.id }
            val answersOk = form.answers.all { (id, key) ->
                val question = byId[id] ?: return@all false
                key == UNKNOWN_KEY || key in question.answerChoices
            }
            if (!answersOk) return false
            val indexOk = if (questions.isEmpty()) questionIndex == 0 else questionIndex in questions.indices
            if (!indexOk) return false
            if (availabilityWeek != null && availabilityWeek.dayOfWeek != DayOfWeek.MONDAY) return false
            if (!form.availability.isEditorWeek()) return false
            return form.availabilityOverrides.all { (monday, days) -> monday.dayOfWeek == DayOfWeek.MONDAY && days.isEditorWeek() }
        }

    /** The week to show after resuming: the saved one while it is still a study week, else the first. */
    val shownWeek: LocalDate? get() = form.shownWeek(availabilityWeek)

    private fun Map<Weekday, DayAvailability>.isEditorWeek(): Boolean =
        keys == Weekday.entries.toSet() && values.all { day ->
            day.start.isWholeMinute() && WindowSpec.isValidDuration(day.durationMinutes) &&
                day.extras.all { it.start.isWholeMinute() && WindowSpec.isValidDuration(it.durationMinutes) }
        }

    private fun LocalTime.isWholeMinute(): Boolean = second == 0 && nano == 0
}

/** Whether [this] holds anything beyond the defaults of a creation started on [today]. */
fun PlanFormState.hasInputBeyondDefaults(today: LocalDate): Boolean = this != PlanFormState(preparationStart = today)
