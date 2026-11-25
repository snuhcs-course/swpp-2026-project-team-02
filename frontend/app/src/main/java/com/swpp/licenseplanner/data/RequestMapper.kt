package com.swpp.licenseplanner.data

import com.swpp.licenseplanner.data.model.AssessmentResult
import com.swpp.licenseplanner.data.model.AssessmentResults
import com.swpp.licenseplanner.data.model.AvailabilitySnapshot
import com.swpp.licenseplanner.data.model.StudyPlanRequest
import com.swpp.licenseplanner.data.model.TimeWindow
import com.swpp.licenseplanner.domain.PlanFormState
import com.swpp.licenseplanner.domain.Scoring
import com.swpp.licenseplanner.domain.StudyAvailability
import com.swpp.licenseplanner.domain.WeekWindows
import com.swpp.licenseplanner.domain.Weekday
import com.swpp.licenseplanner.domain.WindowSpec
import com.swpp.licenseplanner.domain.WireDateTime
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.ResolverStyle

sealed interface MappingResult {
    data class Ready(val request: StudyPlanRequest) : MappingResult
    /** The form is incomplete; generation stays blocked. */
    data class Incomplete(val reason: String) : MappingResult
}

/** Converts the form into the target `POST /study-plans` request. Pure. */
object RequestMapper {
    fun map(state: PlanFormState): MappingResult {
        val certificationId = state.certificationId ?: return MappingResult.Incomplete("certification not selected")
        val examDate = state.examDate ?: return MappingResult.Incomplete("exam date missing")
        if (state.datesError != null) return MappingResult.Incomplete("dates are invalid")
        val level = state.level ?: return MappingResult.Incomplete("level not selected")
        if (state.questions.isEmpty()) return MappingResult.Incomplete("questions not loaded")
        if (!state.allAnswered) return MappingResult.Incomplete("assessment incomplete")
        if (state.availabilityError != null) return MappingResult.Incomplete("availability invalid")

        val context = state.studyAvailability

        val results = state.questions.map { question ->
            val answer = state.answers.getValue(question.id)
            AssessmentResult(
                problemId = question.id,
                question = question.prompt,
                choices = question.choicesWithUnknown(),
                correctAnswer = question.correctAnswer,
                userAnswer = answer,
                isCorrect = Scoring.isCorrect(question, answer),
                possibleScore = question.possibleScore,
            )
        }

        return MappingResult.Ready(
            StudyPlanRequest(
                certificationId = certificationId,
                preparationStart = WireDateTime.formatDate(state.preparationStart),
                examDate = WireDateTime.formatDate(examDate),
                selfAssessment = level.selfAssessment,
                assessmentResults = AssessmentResults(certificationId, results),
                studyDaysPerWeek = context.weekdaysWithWindows().size,
                weeklyAvailability = AvailabilityWire.week(context.base),
                busySchedules = state.busySchedules,
                availabilityOverrides = AvailabilityWire.overrides(context.overrides),
            ),
        )
    }
}

/**
 * Converts study windows between the wire and the shared resolver. Requests use the canonical
 * `start` + `duration_minutes` form; decoding also reads the same-day `start` + `end` form and a
 * returned derived `end`, and rejects anything the backend would reject.
 */
object AvailabilityWire {
    private val clock = DateTimeFormatter.ofPattern("HH:mm").withResolverStyle(ResolverStyle.STRICT)
    private val mondayKey = DateTimeFormatter.ofPattern("uuuu/MM/dd").withResolverStyle(ResolverStyle.STRICT)
    private val weekdayByKey = Weekday.entries.associateBy { it.key }

    /** Weekday key → canonical windows; weekdays without windows are omitted. */
    fun week(week: WeekWindows): Map<String, List<TimeWindow>> =
        Weekday.entries.mapNotNull { day ->
            week[day]?.takeIf { it.isNotEmpty() }?.let { windows ->
                day.key to windows.sortedWith(compareBy({ it.start }, { it.durationMinutes }))
                    .map { TimeWindow(start = WireDateTime.formatTime(it.start), durationMinutes = it.durationMinutes) }
            }
        }.toMap()

    /** Monday key → canonical week; an empty week stays `{}`. */
    fun overrides(overrides: Map<LocalDate, WeekWindows>): Map<String, Map<String, List<TimeWindow>>> =
        overrides.toSortedMap().map { (monday, week) -> monday.format(mondayKey) to week(week) }.toMap()

    fun mondayKey(monday: LocalDate): String = monday.format(mondayKey)

    /** The validated context of a request, or null when any window, key or overlap is invalid. */
    fun context(
        weekly: Map<String, List<TimeWindow>>,
        overrides: Map<String, Map<String, List<TimeWindow>>>,
    ): StudyAvailability? {
        val base = weekOrNull(weekly) ?: return null
        val replaced = overrides.entries.associate { (key, value) ->
            (mondayOrNull(key) ?: return null) to (weekOrNull(value) ?: return null)
        }
        return StudyAvailability(base, replaced).takeIf { it.firstOverlap() == null }
    }

    fun context(snapshot: AvailabilitySnapshot): StudyAvailability? =
        context(snapshot.weeklyAvailability, snapshot.availabilityOverrides)

    /** A request's own context; its wire maps were produced by [week] and [overrides]. */
    fun context(request: StudyPlanRequest): StudyAvailability? =
        context(request.weeklyAvailability, request.availabilityOverrides)

    private fun weekOrNull(raw: Map<String, List<TimeWindow>>): WeekWindows? {
        val week = mutableMapOf<Weekday, List<WindowSpec>>()
        for ((key, windows) in raw) {
            val day = weekdayByKey[key] ?: return null
            val specs = windows.map { windowOrNull(it) ?: return null }
            if (specs.isNotEmpty()) week[day] = specs.sortedWith(compareBy({ it.start }, { it.durationMinutes }))
        }
        return week
    }

    /** `start`+`duration_minutes` (1–1439), optionally with an agreeing `end`, or same-day `start`<`end`. */
    fun windowOrNull(window: TimeWindow): WindowSpec? {
        val start = timeOrNull(window.start) ?: return null
        val end = window.end?.let { timeOrNull(it) ?: return null }
        val duration = window.durationMinutes
        if (duration != null) {
            if (!WindowSpec.isValidDuration(duration)) return null
            val spec = WindowSpec(start, duration)
            return spec.takeIf { end == null || spec.end == end }
        }
        if (end == null || !end.isAfter(start)) return null
        return WindowSpec(start, (end.toSecondOfDay() - start.toSecondOfDay()) / 60)
    }

    private fun timeOrNull(value: String): LocalTime? = try {
        LocalTime.parse(value, clock)
    } catch (_: DateTimeParseException) {
        null
    }

    private fun mondayOrNull(key: String): LocalDate? = try {
        LocalDate.parse(key, mondayKey).takeIf { it.dayOfWeek == DayOfWeek.MONDAY }
    } catch (_: DateTimeParseException) {
        null
    }
}
