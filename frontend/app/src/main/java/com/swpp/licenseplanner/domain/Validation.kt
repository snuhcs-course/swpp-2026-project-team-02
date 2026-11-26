package com.swpp.licenseplanner.domain

import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/** A displayed window; [endsNextDay] marks an end on the following date. */
data class StudyWindow(val start: LocalTime, val end: LocalTime, val endsNextDay: Boolean = false)

/** The chosen preparation period, its dates, and the optional days from today to the exam. */
data class PeriodText(val period: String, val range: String, val untilExam: String?)

/** Date and availability rules for the input flow. */
object Validation {
    private val clock = DateTimeFormatter.ofPattern("HH:mm")

    /** Days between preparation start and exam date. */
    fun preparationDays(start: LocalDate, exam: LocalDate): Long = ChronoUnit.DAYS.between(start, exam)

    /** Days from [today] to [exam], or null unless the exam is after today. */
    fun daysUntilExam(today: LocalDate, exam: LocalDate): Long? = ChronoUnit.DAYS.between(today, exam).takeIf { it > 0 }

    /**
     * The date step's period text, or null when the dates are invalid. [PeriodText.untilExam]
     * is shown only when preparation does not start today and the exam is still ahead.
     */
    fun periodText(start: LocalDate, exam: LocalDate?, today: LocalDate): PeriodText? {
        if (exam == null || datesError(start, exam) != null) return null
        val untilExam = daysUntilExam(today, exam)?.takeIf { start != today }
        return PeriodText(
            period = "선택한 준비 기간 ${preparationDays(start, exam)}일",
            range = "${monthDay(start)}부터 ${monthDay(exam)} 시험 전까지",
            untilExam = untilExam?.let { "오늘부터 시험까지 ${it}일" },
        )
    }

    private fun monthDay(date: LocalDate) = "${date.monthValue}월 ${date.dayOfMonth}일"

    /** Null when valid; otherwise a user-facing message. */
    fun datesError(start: LocalDate?, exam: LocalDate?): String? = when {
        start == null -> "준비 시작일을 선택해 주세요."
        exam == null -> "시험일을 선택해 주세요."
        !exam.isAfter(start) -> "시험일은 준비 시작일보다 뒤여야 해요."
        else -> null
    }

    /** The primary window of an enabled day, or null when its duration is outside 1–1439 minutes. */
    fun window(day: DayAvailability): StudyWindow? {
        if (!WindowSpec.isValidDuration(day.durationMinutes)) return null
        val spec = WindowSpec(day.start, day.durationMinutes)
        return StudyWindow(spec.start, spec.end, spec.endsNextDay)
    }

    fun windowError(day: DayAvailability): String? =
        if (day.enabled && window(day) == null) "공부 시간은 1분부터 1439분까지 정할 수 있어요." else null

    /**
     * Null when the full context (base, replacement weeks and every window) can be
     * submitted; otherwise the reason it cannot.
     */
    fun availabilityError(
        availability: Map<Weekday, DayAvailability>,
        overrides: Map<LocalDate, Map<Weekday, DayAvailability>> = emptyMap(),
    ): String? {
        val days = availability.values + overrides.values.flatMap { it.values }
        days.firstNotNullOfOrNull { windowError(it) }?.let { return it }
        if (days.flatMap { it.extras }.any { !WindowSpec.isValidDuration(it.durationMinutes) }) {
            return "공부 시간은 1분부터 1439분까지 정할 수 있어요."
        }
        val context = StudyAvailability(availability.toWeekWindows(), overrides.mapValues { it.value.toWeekWindows() })
        if (!context.hasWindows()) return "공부할 요일을 하나 이상 선택해 주세요."
        return context.firstOverlap()?.let { (first, second) ->
            "공부 시간이 서로 겹쳐요: ${label(first)} 시작 시간과 ${label(second)} 시작 시간을 고쳐 주세요."
        }
    }

    private fun label(interval: StudyInterval): String =
        "${Weekday.of(interval.start.dayOfWeek).label}요일 ${interval.start.format(clock)}"
}
