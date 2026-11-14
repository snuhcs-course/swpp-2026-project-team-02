package com.swpp.licenseplanner.domain

/** How a learner reports one study session. */
enum class StudyCheckInStatus { Done, Partly, Missed }

/**
 * The learner's current report for one study session of one plan. [actualMinutes] is the effort
 * actually spent and is independent of the scheduled duration: done and partly need a positive
 * whole number of minutes (more than planned is allowed), missed is always zero. A report is never
 * inferred from elapsed time or an adjustment selection, and says nothing about mastery.
 */
data class StudyCheckIn(val status: StudyCheckInStatus, val actualMinutes: Int) {
    /** Whether the status and minutes belong together; stored reports are checked the same way. */
    val isValid: Boolean get() = isValid(status, actualMinutes)

    companion object {
        private val WHOLE_MINUTES = Regex("[0-9]+")

        fun isValid(status: StudyCheckInStatus, actualMinutes: Int): Boolean = when (status) {
            StudyCheckInStatus.Done, StudyCheckInStatus.Partly -> actualMinutes > 0
            StudyCheckInStatus.Missed -> actualMinutes == 0
        }

        /**
         * Minutes typed by the learner: decimal digits only, positive and representable as an [Int].
         * Blank, signed, fractional, nonnumeric and overflowing text is null rather than repaired.
         */
        fun parseActualMinutes(text: String): Int? {
            if (!WHOLE_MINUTES.matches(text)) return null
            return text.toIntOrNull()?.takeIf { it > 0 }
        }

        /**
         * The report an editor would save, or null when the input cannot be saved. Choosing missed
         * records zero and ignores any typed minutes.
         */
        fun fromInput(status: StudyCheckInStatus, minutesText: String): StudyCheckIn? = when (status) {
            StudyCheckInStatus.Missed -> StudyCheckIn(status, 0)
            StudyCheckInStatus.Done, StudyCheckInStatus.Partly -> parseActualMinutes(minutesText)?.let { StudyCheckIn(status, it) }
        }
    }
}
