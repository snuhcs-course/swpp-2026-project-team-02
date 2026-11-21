package com.swpp.licenseplanner.data

import com.swpp.licenseplanner.data.model.ScheduleRow
import com.swpp.licenseplanner.data.model.StudyPlanRequest
import com.swpp.licenseplanner.data.model.StudyPlanResponse
import com.swpp.licenseplanner.domain.LocalTopics
import com.swpp.licenseplanner.domain.StudySessions
import com.swpp.licenseplanner.domain.WireDateTime
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.math.floor

sealed interface DemoResult {
    data class Plan(val response: StudyPlanResponse) : DemoResult
    data class Shortfall(val required: Int, val available: Int) : DemoResult
}

/**
 * Deterministic local demonstration planner. Like a live plan, it fills every free minute of the
 * entered windows inside the preparation period, outside imported busy rows, that lies in continuous
 * free time of at least [StudySessions.MIN_MINUTES], measured across touching windows and midnight;
 * shorter free time stays empty, so every study session meets that minimum. It splits those
 * minutes in the backend's budget proportions: one reserved minute per topic, the rest by weight
 * `2 - score/100` with largest-remainder rounding (ties: lower score, then catalog order).
 *
 * It then walks the free minutes in time order, weakest topic first; a topic is split only where a
 * free segment or its budget ends. It does not copy the backend's topic-order search or boundary
 * smoothing, so its rows can differ from a live plan's.
 */
object DemoPlanner {
    const val CSV_HEADER = "schedule_id,start_date,end_date,topic"

    private data class TopicTotal(val topic: String, val possible: Double, val earned: Double) {
        val percent: Double get() = earned / possible * 100
    }

    fun plan(request: StudyPlanRequest): DemoResult {
        val topics = topicTotals(request).sortedWith(compareBy<TopicTotal> { it.percent }
            .thenBy { LocalTopics.catalogOrder.indexOf(it.topic) })
        val busy = busyRows(request)
        val segments = freeSegments(request, busy.map { it.second })
        val available = segments.sumOf { (start, end) -> Duration.between(start, end).toMinutes() }.toInt()
        // Each topic needs at least one one-minute session, so M free minutes hold at most M sessions.
        if (available < topics.size) return DemoResult.Shortfall(topics.size, available)

        val pieces = place(segments, topics.map { it.topic }.zip(budget(topics, available)))
        val study = pieces.mapIndexed { index, (topic, interval) ->
            ScheduleRow(
                scheduleId = "study-${index + 1}",
                startDate = WireDateTime.format(interval.first),
                endDate = WireDateTime.format(interval.second),
                topic = topic,
            ) to interval
        }
        // Chronological like the backend CSV: start, then end, then ID.
        val rows = (busy + study)
            .sortedWith(compareBy({ it.second.first }, { it.second.second }, { it.first.scheduleId }))
            .map { it.first }
        val csv = buildString {
            append(CSV_HEADER).append('\n')
            rows.forEach { row ->
                append(listOf(row.scheduleId, row.startDate, row.endDate, row.topic).joinToString(",") { csvField(it) })
                append('\n')
            }
        }
        return DemoResult.Plan(StudyPlanResponse(schedules = rows, scheduleCsv = csv, agentSummary = ""))
    }

    /** Quotes a field the way Python's csv writer does (minimal quoting). */
    private fun csvField(value: String): String =
        if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + value.replace("\"", "\"\"") + "\"" else value

    /** Points per local example label; UNKNOWN is already incorrect. */
    private fun topicTotals(request: StudyPlanRequest): List<TopicTotal> =
        request.assessmentResults.results
            .groupBy { LocalTopics.labelFor(it.problemId) }
            .map { (topic, results) ->
                TopicTotal(
                    topic = topic,
                    possible = results.sumOf { it.possibleScore },
                    earned = results.filter { it.isCorrect }.sumOf { it.possibleScore },
                )
            }

    /**
     * Minutes per topic in [ordered]'s order (weakest first) summing to [total]: one reserved minute
     * each, the rest by weight `2 - score/100`, leftover minutes to the largest remainders with ties
     * kept in [ordered]'s order.
     */
    private fun budget(ordered: List<TopicTotal>, total: Int): List<Int> {
        if (ordered.isEmpty()) return emptyList()
        val rest = total - ordered.size
        val weights = ordered.map { 2.0 - it.percent / 100 }
        val quotas = weights.map { rest * it / weights.sum() }
        val minutes = quotas.map { floor(it).toInt() }.toMutableList()
        val left = rest - minutes.sum()
        ordered.indices.sortedWith(compareByDescending<Int> { quotas[it] - minutes[it] }.thenBy { it })
            .take(left).forEach { minutes[it]++ }
        return minutes.map { it + 1 }
    }

    /** Consecutive minutes of each topic in order through the time-ordered [segments]; one row per piece. */
    private fun place(
        segments: List<Pair<LocalDateTime, LocalDateTime>>,
        budgets: List<Pair<String, Int>>,
    ): List<Pair<String, Pair<LocalDateTime, LocalDateTime>>> {
        val result = mutableListOf<Pair<String, Pair<LocalDateTime, LocalDateTime>>>()
        var topic = 0
        var left = budgets.firstOrNull()?.second?.toLong() ?: 0L
        for ((segmentStart, segmentEnd) in segments) {
            var cursor = segmentStart
            while (cursor < segmentEnd && topic < budgets.size) {
                val end = cursor.plusMinutes(minOf(left, Duration.between(cursor, segmentEnd).toMinutes()))
                result += budgets[topic].first to (cursor to end)
                left -= Duration.between(cursor, end).toMinutes()
                cursor = end
                if (left == 0L) {
                    topic++
                    left = budgets.getOrNull(topic)?.second?.toLong() ?: 0L
                }
            }
        }
        return result
    }

    /** Imported busy rows as `external-<id>` rows with their parsed half-open intervals. */
    private fun busyRows(request: StudyPlanRequest): List<Pair<ScheduleRow, Pair<LocalDateTime, LocalDateTime>>> =
        request.busySchedules.mapNotNull { row ->
            val id = row.scheduleId.toLongOrNull()?.takeIf { it > 0 } ?: return@mapNotNull null
            val start = WireDateTime.parseOrNull(row.startDate) ?: return@mapNotNull null
            val end = WireDateTime.parseOrNull(row.endDate)?.takeIf { it.isAfter(start) } ?: return@mapNotNull null
            row.copy(scheduleId = "external-$id") to (start to end)
        }

    /**
     * Free time in time order: every effective window starting on a date from preparation start up
     * to (excluding) the exam date, clipped to the preparation period and cut by busy intervals.
     * Touching ends do not overlap; only positive whole-minute pieces remain. Pieces that touch form
     * one continuous stretch, and a stretch shorter than [StudySessions.MIN_MINUTES] is left out.
     */
    private fun freeSegments(
        request: StudyPlanRequest,
        busy: List<Pair<LocalDateTime, LocalDateTime>>,
    ): List<Pair<LocalDateTime, LocalDateTime>> {
        val preparationAt = WireDateTime.parseOrNull(request.preparationStart) ?: return emptyList()
        val examAt = WireDateTime.parseOrNull(request.examDate) ?: return emptyList()
        val context = AvailabilityWire.context(request) ?: return emptyList()
        val result = mutableListOf<Pair<LocalDateTime, LocalDateTime>>()
        var date: LocalDate = preparationAt.toLocalDate()
        while (date.isBefore(examAt.toLocalDate())) {
            // The date's own week decides its windows, which may run past midnight.
            for (window in context.windowsStarting(date)) {
                val interval = window.intervalOn(date)
                result += withoutBusy(maxOf(interval.start, preparationAt), minOf(interval.end, examAt), busy)
            }
            date = date.plusDays(1)
        }
        return usable(result.sortedBy { it.first })
    }

    /** The time-ordered [pieces] whose continuous stretch lasts at least [StudySessions.MIN_MINUTES]. */
    private fun usable(pieces: List<Pair<LocalDateTime, LocalDateTime>>): List<Pair<LocalDateTime, LocalDateTime>> {
        val stretches = mutableListOf<MutableList<Pair<LocalDateTime, LocalDateTime>>>()
        for (piece in pieces) {
            val last = stretches.lastOrNull()
            if (last != null && piece.first <= last.maxOf { it.second }) last += piece else stretches += mutableListOf(piece)
        }
        return stretches.filter { stretch ->
            Duration.between(stretch.first().first, stretch.maxOf { it.second }).toMinutes() >= StudySessions.MIN_MINUTES
        }.flatten()
    }

    private fun withoutBusy(
        from: LocalDateTime,
        until: LocalDateTime,
        busy: List<Pair<LocalDateTime, LocalDateTime>>,
    ): List<Pair<LocalDateTime, LocalDateTime>> {
        val pieces = mutableListOf<Pair<LocalDateTime, LocalDateTime>>()
        var cursor = from
        for ((busyStart, busyEnd) in busy.filter { it.first < until && from < it.second }.sortedBy { it.first }) {
            if (busyStart > cursor) pieces += cursor to busyStart
            if (busyEnd > cursor) cursor = busyEnd
        }
        if (cursor < until) pieces += cursor to until
        return pieces.filter { (start, end) -> Duration.between(start, end).toMinutes() > 0 }
    }
}

/** Demo-flavor planning: local, labeled, never a backend or AI result. */
class DemoPlanningRepository : PlanningRepository {
    override suspend fun generate(request: StudyPlanRequest): PlanOutcome {
        return when (val result = DemoPlanner.plan(request)) {
            is DemoResult.Plan -> PlanOutcome.Success(result.response, isDemo = true)
            is DemoResult.Shortfall -> PlanOutcome.DemoShortfall(result.required, result.available)
        }
    }
}
