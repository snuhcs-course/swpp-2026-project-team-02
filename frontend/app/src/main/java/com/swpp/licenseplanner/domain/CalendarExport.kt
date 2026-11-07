package com.swpp.licenseplanner.domain

import com.swpp.licenseplanner.data.model.ScheduleRow
import java.time.ZoneId

/** One study session as a timed, non-recurring device-calendar event. Times are epoch milliseconds. */
data class CalendarEvent(
    val title: String,
    val startMillis: Long,
    val endMillis: Long,
    val timeZone: String,
    val description: String,
)

/**
 * One-way copy of a plan's study rows into calendar events. The wire timestamps carry no zone;
 * export reads them as Korean time, whatever the device or calendar zone is.
 */
object CalendarExport {
    const val TIME_ZONE = "Asia/Seoul"
    private val zone: ZoneId = ZoneId.of(TIME_ZONE)
    private const val STUDY_PREFIX = "study-"

    /** Only explicit study rows are exported; existing schedules and unknown rows are left out. */
    fun studyRows(rows: List<ScheduleRow>): List<ScheduleRow> = rows.filter { it.scheduleId.startsWith(STUDY_PREFIX) }

    /**
     * One event per study row across all weeks, earliest first, or null when any study row has a
     * duplicate ID, an unparseable timestamp or an end not after its start: then nothing is exported.
     */
    fun events(rows: List<ScheduleRow>, planOrdinal: Int, isDemo: Boolean): List<CalendarEvent>? {
        val study = studyRows(rows)
        if (study.map { it.scheduleId }.toSet().size != study.size) return null
        val description = description(planOrdinal, isDemo)
        val timed = study.map { row ->
            val start = WireDateTime.parseOrNull(row.startDate) ?: return null
            val end = WireDateTime.parseOrNull(row.endDate) ?: return null
            if (!end.isAfter(start)) return null
            Triple(row.topic, start.atZone(zone).toInstant().toEpochMilli(), end.atZone(zone).toInstant().toEpochMilli())
        }
        return timed.sortedWith(compareBy<Triple<String, Long, Long>> { it.second }.thenBy { it.third })
            .map { (topic, start, end) -> CalendarEvent(topic, start, end, TIME_ZONE, description) }
    }

    private const val DESCRIPTION_FIRST_LINE = "License Planner에서 내보낸 공부 일정이에요."
    private const val DESCRIPTION_PLAN_PREFIX = "계획 "
    private const val DESCRIPTION_PLAN_SUFFIX = " · $CERTIFICATION_NAME 필기"
    private const val DESCRIPTION_COPY_WARNING = "앱에서 계획을 조정해도 이 일정은 바뀌지 않아요."
    private const val DESCRIPTION_DEMO_LINE = "로컬 시연 계산: 데모 플래너가 기기 안에서 만든 일정이에요."
    private val exportedPlanLine = Regex(
        Regex.escape(DESCRIPTION_PLAN_PREFIX) + "[1-9][0-9]*" + Regex.escape(DESCRIPTION_PLAN_SUFFIX),
    )

    fun description(planOrdinal: Int, isDemo: Boolean): String = buildString {
        append(DESCRIPTION_FIRST_LINE).append('\n')
        append(DESCRIPTION_PLAN_PREFIX).append(planOrdinal).append(DESCRIPTION_PLAN_SUFFIX).append('\n')
        append(DESCRIPTION_COPY_WARNING)
        if (isDemo) append('\n').append(DESCRIPTION_DEMO_LINE)
    }

    /**
     * True only for the complete description this app writes on export (live or demo, any positive
     * plan number). An edited, shortened or merely similar text is not recognized.
     */
    fun isExportedDescription(text: String?): Boolean {
        val lines = text?.replace("\r\n", "\n")?.split('\n') ?: return false
        if (lines.size !in 3..4) return false
        return lines[0] == DESCRIPTION_FIRST_LINE &&
            exportedPlanLine.matches(lines[1]) &&
            lines[2] == DESCRIPTION_COPY_WARNING &&
            (lines.size == 3 || lines[3] == DESCRIPTION_DEMO_LINE)
    }
}
