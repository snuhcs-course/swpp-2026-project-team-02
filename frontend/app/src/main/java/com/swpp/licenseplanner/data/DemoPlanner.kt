package com.swpp.licenseplanner.data

import com.swpp.licenseplanner.data.model.ScheduleRow
import com.swpp.licenseplanner.data.model.StudyPlanRequest
import com.swpp.licenseplanner.data.model.StudyPlanResponse
import com.swpp.licenseplanner.domain.CurrentLevel
import com.swpp.licenseplanner.domain.LocalTopics
import com.swpp.licenseplanner.domain.WireDateTime
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.math.ceil

sealed interface DemoResult {
    data class Plan(val response: StudyPlanResponse) : DemoResult
    data class Shortfall(val required: Int, val available: Int) : DemoResult
}

/**
 * Deterministic local demonstration planner. Its workload
 * rules are demonstration assumptions; they do not mirror the backend's model
 * allocation, estimate necessary exam preparation, or use removed backend readiness rules.
 *
 * Each represented topic gets its own quota: one base session plus one extra when its
 * score is below 60%, each component multiplied by the effective level's multiplier and
 * rounded up separately, so even an advanced learner keeps a weak topic's extra session.
 */
object DemoPlanner {
    private const val BASE_SESSIONS_PER_TOPIC = 1
    private const val WEAK_TOPIC_EXTRA_SESSIONS = 1
    private const val WEAK_TOPIC_PERCENT = 60.0
    private const val SESSION_MINUTES = 60L
    const val CSV_HEADER = "schedule_id,start_date,end_date,topic"

    private data class TopicTotal(val topic: String, val possible: Double, val earned: Double) {
        val percent: Double get() = earned / possible * 100
    }

    fun plan(request: StudyPlanRequest): DemoResult {
        val topics = topicTotals(request)
        val multiplier = levelMultiplier(request, topics)
        val ordered = topics.sortedWith(compareBy<TopicTotal> { it.percent }
            .thenBy { LocalTopics.catalogOrder.indexOf(it.topic) })
        val assignments = assignTopics(ordered.map { it.topic to quota(it, multiplier) })
        val required = assignments.size
        val busy = busyRows(request)
        val slots = slots(request, busy.map { it.second })
        if (slots.size < required) return DemoResult.Shortfall(required, slots.size)

        val study = slots.take(required).mapIndexed { index, (start, end) ->
            ScheduleRow(
                scheduleId = "study-${index + 1}",
                startDate = WireDateTime.format(start),
                endDate = WireDateTime.format(end),
                topic = assignments[index],
            ) to (start to end)
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

    /** A topic's own session count: the rounded base (at least one) plus its rounded weak-topic extra. */
    private fun quota(topic: TopicTotal, multiplier: Double): Int {
        val base = maxOf(1, ceil(BASE_SESSIONS_PER_TOPIC * multiplier).toInt())
        val extra = if (topic.percent < WEAK_TOPIC_PERCENT) ceil(WEAK_TOPIC_EXTRA_SESSIONS * multiplier).toInt() else 0
        return base + extra
    }

    /**
     * Topic per session in rounds over [quotas] in their given order (weakest first), skipping
     * a topic once its own quota is filled, so extra sessions stay with the topics that earned them.
     */
    private fun assignTopics(quotas: List<Pair<String, Int>>): List<String> {
        val remaining = quotas.map { it.second }.toIntArray()
        val result = mutableListOf<String>()
        while (remaining.any { it > 0 }) {
            quotas.forEachIndexed { index, (topic, _) ->
                if (remaining[index] > 0) {
                    result += topic
                    remaining[index]--
                }
            }
        }
        return result
    }

    private fun levelMultiplier(request: StudyPlanRequest, topics: List<TopicTotal>): Double {
        val possible = topics.sumOf { it.possible }
        val overall = if (possible > 0) topics.sumOf { it.earned } / possible * 100 else 0.0
        val scoreLevel = when {
            overall >= 80 -> CurrentLevel.ADVANCED
            overall >= 60 -> CurrentLevel.INTERMEDIATE
            else -> CurrentLevel.BEGINNER
        }
        val chosenLevel = CurrentLevel.fromSelfAssessment(request.selfAssessment) ?: CurrentLevel.BEGINNER
        // The stricter (lower) of the chosen and score levels; enum order is beginner < advanced.
        return when (minOf(chosenLevel, scoreLevel)) {
            CurrentLevel.BEGINNER -> 2.0
            CurrentLevel.INTERMEDIATE -> 1.0
            CurrentLevel.ADVANCED -> 0.5
        }
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
     * One session per enabled day in its first window, from preparation start up to (excluding) the
     * exam date: the earliest start where the whole session fits outside every busy interval.
     */
    private fun slots(
        request: StudyPlanRequest,
        busy: List<Pair<LocalDateTime, LocalDateTime>>,
    ): List<Pair<LocalDateTime, LocalDateTime>> {
        val first = WireDateTime.parseOrNull(request.preparationStart)?.toLocalDate() ?: return emptyList()
        val examAt = WireDateTime.parseOrNull(request.examDate) ?: return emptyList()
        val exam = examAt.toLocalDate()
        val context = AvailabilityWire.context(request) ?: return emptyList()
        val result = mutableListOf<Pair<LocalDateTime, LocalDateTime>>()
        var date: LocalDate = first
        while (date.isBefore(exam)) {
            // The date's own week decides its first window, which may run past midnight.
            val window = context.windowsStarting(date).minByOrNull { it.start }
            if (window != null) {
                val interval = window.intervalOn(date)
                val windowStart = interval.start
                val windowEnd = minOf(interval.end, examAt)
                val duration = Duration.between(windowStart, minOf(windowStart.plusMinutes(SESSION_MINUTES), windowEnd))
                if (!duration.isNegative && !duration.isZero) {
                    freeStart(windowStart, windowEnd, duration, busy)?.let { start -> result += start to start.plus(duration) }
                }
            }
            date = date.plusDays(1)
        }
        return result
    }

    /** Earliest start in [windowStart, windowEnd) leaving [duration] free of busy intervals; touching ends do not overlap. */
    private fun freeStart(
        windowStart: LocalDateTime,
        windowEnd: LocalDateTime,
        duration: Duration,
        busy: List<Pair<LocalDateTime, LocalDateTime>>,
    ): LocalDateTime? {
        var candidate = windowStart
        for ((busyStart, busyEnd) in busy.filter { it.first < windowEnd && windowStart < it.second }.sortedBy { it.first }) {
            if (!candidate.plus(duration).isAfter(busyStart)) break
            if (busyEnd.isAfter(candidate)) candidate = busyEnd
        }
        return candidate.takeIf { !it.plus(duration).isAfter(windowEnd) }
    }
}

/** Demo-flavor planning: local, labeled, never a backend or AI result. */
class DemoPlanningRepository : PlanningRepository {
    override suspend fun generate(request: StudyPlanRequest): PlanOutcome = when (val result = DemoPlanner.plan(request)) {
        is DemoResult.Plan -> PlanOutcome.Success(result.response, isDemo = true)
        is DemoResult.Shortfall -> PlanOutcome.DemoShortfall(result.required, result.available)
    }
}
