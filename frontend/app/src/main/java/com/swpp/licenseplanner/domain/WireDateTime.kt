package com.swpp.licenseplanner.domain

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.ResolverStyle

/** The API's `YYYY/MM/DD/HH/MM` timestamps and `HH:MM` windows, used in both directions. */
object WireDateTime {
    private val dateTime = DateTimeFormatter.ofPattern("uuuu/MM/dd/HH/mm").withResolverStyle(ResolverStyle.STRICT)
    private val time = DateTimeFormatter.ofPattern("HH:mm").withResolverStyle(ResolverStyle.STRICT)

    fun format(value: LocalDateTime): String = dateTime.format(value)

    /** Request dates are local midnight (`/00/00`). */
    fun formatDate(value: LocalDate): String = format(value.atStartOfDay())

    fun formatTime(value: LocalTime): String = time.format(value)

    fun parseOrNull(value: String): LocalDateTime? = try {
        LocalDateTime.parse(value, dateTime)
    } catch (_: DateTimeParseException) {
        null
    }
}
