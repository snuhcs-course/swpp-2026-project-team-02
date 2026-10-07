package com.swpp.licenseplanner.data

import com.swpp.licenseplanner.data.model.ScheduleRow
import com.swpp.licenseplanner.data.model.StudyPlanRequest
import com.swpp.licenseplanner.data.model.StudyPlanResponse
import com.swpp.licenseplanner.domain.CurrentLevel
import com.swpp.licenseplanner.domain.LocalTopics
import com.swpp.licenseplanner.domain.Weekday
import com.swpp.licenseplanner.domain.WireDateTime
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.math.ceil

sealed interface DemoResult {
    data class Plan(val response: StudyPlanResponse) : DemoResult
    data class Shortfall(val required: Int, val available: Int) : DemoResult
}

/**
 * Deterministic local demonstration planner (design D8, GEN-05/06). Its workload
 * rules are demonstration assumptions; they do not mirror the backend's Gemini
 * allocation and never use removed backend readiness rules.
 */
object DemoPlanner {
    private const val BASE_SESSIONS_PER_TOPIC = 2
    private const val WEAK_TOPIC_EXTRA_SESSIONS = 3
    private const val WEAK_TOPIC_PERCENT = 60.0
    private const val SESSION_MINUTES = 60L
    const val CSV_HEADER = "schedule_id,start_date,end_date,topic"

    private data class TopicTotal(val topic: String, val possible: Double, val earned: Double) {
        val percent: Double get() = earned / possible * 100
    }

    fun plan(request: StudyPlanRequest): DemoResult {
        val topics = topicTotals(request)
        val required = requiredSessions(request, topics)
        val slots = slots(request)
        if (slots.size < required) return DemoResult.Shortfall(required, slots.size)

        val ordered = topics.sortedWith(compareBy<TopicTotal> { it.percent }
            .thenBy { LocalTopics.catalogOrder.indexOf(it.topic) })
        val rows = slots.take(required).mapIndexed { index, (start, end) ->
            ScheduleRow(
                scheduleId = "study-${index + 1}",
                startDate = WireDateTime.format(start),
                endDate = WireDateTime.format(end),
                topic = ordered[index % ordered.size].topic,
            )
        }
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

    private fun requiredSessions(request: StudyPlanRequest, topics: List<TopicTotal>): Int {
        val base = topics.sumOf { topic ->
            BASE_SESSIONS_PER_TOPIC + if (topic.percent < WEAK_TOPIC_PERCENT) WEAK_TOPIC_EXTRA_SESSIONS else 0
        }
        val possible = topics.sumOf { it.possible }
        val overall = if (possible > 0) topics.sumOf { it.earned } / possible * 100 else 0.0
        val scoreLevel = when {
            overall >= 80 -> CurrentLevel.ADVANCED
            overall >= 60 -> CurrentLevel.INTERMEDIATE
            else -> CurrentLevel.BEGINNER
        }
        val chosenLevel = CurrentLevel.fromSelfAssessment(request.selfAssessment) ?: CurrentLevel.BEGINNER
        // The stricter (lower) of the chosen and score levels; enum order is beginner < advanced.
        val level = minOf(chosenLevel, scoreLevel)
        val multiplier = when (level) {
            CurrentLevel.BEGINNER -> 2.0
            CurrentLevel.INTERMEDIATE -> 1.0
            CurrentLevel.ADVANCED -> 0.5
        }
        return maxOf(ceil(base * multiplier).toInt(), topics.size)
    }

    /** One session per enabled day at the window start, from preparation start up to (excluding) the exam date. */
    private fun slots(request: StudyPlanRequest): List<Pair<LocalDateTime, LocalDateTime>> {
        val first = WireDateTime.parseOrNull(request.preparationStart)?.toLocalDate() ?: return emptyList()
        val exam = WireDateTime.parseOrNull(request.examDate)?.toLocalDate() ?: return emptyList()
        val windows = Weekday.entries.associateWith { day ->
            request.weeklyAvailability[day.key]?.firstOrNull()
        }
        val result = mutableListOf<Pair<LocalDateTime, LocalDateTime>>()
        var date: LocalDate = first
        while (date.isBefore(exam)) {
            val window = windows[Weekday.entries[date.dayOfWeek.value - 1]]
            if (window != null) {
                val start = date.atTime(LocalTime.parse(window.start))
                val windowEnd = date.atTime(LocalTime.parse(window.end))
                val end = minOf(start.plusMinutes(SESSION_MINUTES), windowEnd)
                if (end.isAfter(start)) result += start to end
            }
            date = date.plusDays(1)
        }
        return result
    }
}

/** Demo-flavor planning: local, labeled, never a backend or AI result. */
class DemoPlanningRepository : PlanningRepository {
    override suspend fun generate(request: StudyPlanRequest): PlanOutcome = when (val result = DemoPlanner.plan(request)) {
        is DemoResult.Plan -> PlanOutcome.Success(result.response, isDemo = true)
        is DemoResult.Shortfall -> PlanOutcome.DemoShortfall(result.required, result.available)
    }
}
