package com.swpp.licenseplanner.domain

import com.swpp.licenseplanner.data.model.ScheduleRow
import java.math.BigDecimal
import java.time.Duration
import java.time.LocalDateTime
import java.util.Locale
import java.util.PriorityQueue

/** One answered reassessment item, reduced to what topic scoring needs. */
data class TopicAnswer(val topic: String, val possibleScore: Double, val isCorrect: Boolean)

/** Why a re-split cannot be produced; each has a matching backend error code. */
enum class ReassessmentFailure(val code: String) {
    NothingToRevise("INVALID_INPUT"),
    MissingPlanContext("INVALID_INPUT"),
    NotEnoughTime("NOT_ENOUGH_TIME"),
    PreviewExpired("REASSESSMENT_PREVIEW_EXPIRED"),
}

sealed interface ResplitResult {
    /** The whole plan after the re-split: unchanged rows plus revised pieces, in CSV order. */
    data class Revised(val rows: List<ScheduleRow>) : ResplitResult
    data class Failed(val failure: ReassessmentFailure) : ResplitResult
}

/** Future unreported minutes of one topic before and after the re-split. */
data class TopicMinutes(val topic: String, val before: Int, val after: Int)

/** One future session whose topic changes, with the pieces that replace it in time order. */
data class RevisedSession(val original: ScheduleRow, val pieces: List<ScheduleRow>)

data class ReassessmentPreview(
    val rows: List<ScheduleRow>,
    val topics: List<TopicMinutes>,
    val sessions: List<RevisedSession>,
    val previewToken: String? = null,
    val rationale: String? = null,
) {
    val hasChanges: Boolean get() = sessions.isNotEmpty()
    val totalMinutes: Int get() = topics.sumOf { it.after }
}

/**
 * Local Demo re-splitting (Live obtains an agent proposal). Shared helpers below also validate
 * and summarize Live rows. Re-splits future unreported study rows by scores, keeping every
 * row's time. Rows that start at or after `replanFrom` and are not kept are revisable. Their total
 * minutes are budgeted like plan generation (one minute per topic, the rest by `2 - score/100`,
 * largest remainder). If exact packing creates short pieces, targets are rounded to balanced
 * cells of at least 15 minutes inside each original row, reserving a cell per topic and keeping
 * weak-topic order. Existing shorter rows stay indivisible. A row that
 * keeps its single original topic keeps its ID;
 * every other revisable row is replaced by pieces numbered from the first new study ID.
 */
object Reassessment {
    private val studyId = Regex("study-([0-9]+)")
    private val hundred = BigDecimal(100)
    private val two = BigDecimal(2)

    /** Same interval-constrained target rounding as Python's _session_budgets. */
    private fun sessionBudgets(durations: List<Int>, budget: List<Pair<String, Int>>): List<List<Pair<String, Int>>>? {
        var topicIndex = 0
        var left = budget[0].second
        val exact = durations.map { duration ->
            var remaining = duration
            val pieces = mutableListOf<Pair<String, Int>>()
            while (remaining > 0) {
                val take = minOf(left, remaining)
                pieces += budget[topicIndex].first to take
                remaining -= take
                left -= take
                if (left == 0 && ++topicIndex < budget.size) left = budget[topicIndex].second
            }
            pieces
        }
        if (durations.zip(exact).all { (duration, pieces) -> pieces.all { it.second >= minOf(15, duration) } }) return exact
        val counts = durations.map { maxOf(1, it / 15) }
        val cells = durations.flatMapIndexed { index, duration ->
            val count = counts[index]
            List(count) { duration / count + if (it < duration % count) 1 else 0 }
        }
        if (cells.size < budget.size) return null
        val total = cells.sum().toLong()
        val allocated = IntArray(budget.size) { 1 }
        val queue = PriorityQueue(compareBy<Pair<Long, Int>> { it.first }.thenBy { it.second })
        budget.forEachIndexed { index, (_, minutes) -> queue += total - minutes.toLong() * cells.size to index }
        repeat(cells.size - budget.size) {
            val (deficit, index) = queue.remove()
            allocated[index]++
            queue += deficit + total to index
        }
        val assigned = budget.flatMapIndexed { index, (topic, _) ->
            List(allocated[index]) { topic }
        }
        val realized = assigned.zip(cells).groupBy({ it.first }, { it.second }).mapValues { it.value.sum() }
        val rank = budget.mapIndexed { index, (topic, _) -> topic to index }.toMap()
        val actualOrder = realized.keys.sortedWith(compareByDescending<String> { realized.getValue(it) }
            .thenBy { rank.getValue(it) })
        val renamed = actualOrder.zip(budget.map { it.first }).toMap()
        val labels = assigned.map { renamed.getValue(it) }
        var offset = 0
        return counts.map { count ->
            val pieces = mutableListOf<Pair<String, Int>>()
            for (index in offset until offset + count) {
                val topic = labels[index]
                if (pieces.lastOrNull()?.first == topic) {
                    pieces[pieces.lastIndex] = topic to pieces.last().second + cells[index]
                } else pieces += topic to cells[index]
            }
            offset += count
            pieces
        }
    }

    /** Points per topic, weakest first, like the backend's topic summary. */
    fun scores(answers: List<TopicAnswer>): List<TopicScore> = answers.groupBy { it.topic }.map { (topic, items) ->
        val possible = items.sumOf { it.possibleScore }
        val earned = items.filter { it.isCorrect }.sumOf { it.possibleScore }
        TopicScore(topic, earned, possible)
    }.sortedWith(compareBy<TopicScore> { it.percent }.thenBy { it.topic.lowercase(Locale.ROOT) })

    /** Minutes per topic in budget order (weakest first), or null when [total] cannot give each topic a minute. */
    fun budget(scores: List<TopicScore>, total: Int): List<Pair<String, Int>>? {
        if (scores.isEmpty() || total < scores.size) return null
        // The backend reads each float score through its shortest decimal form and then works exactly.
        val exact = scores.map { it.topic to BigDecimal.valueOf(it.percent) }
        val ordered = exact.sortedWith(compareBy<Pair<String, BigDecimal>> { it.second }
            .thenBy { it.first.lowercase(Locale.ROOT) }.thenBy { it.first })
        val rest = BigDecimal(total - ordered.size)
        val weights = ordered.map { two - it.second.divide(hundred) }
        val sum = weights.fold(BigDecimal.ZERO, BigDecimal::add)
        val numerators = weights.map { rest * it }
        val minutes = numerators.map { it.divideToIntegralValue(sum).toInt() }.toMutableList()
        // Quotas share the denominator `sum`, so remainders compare by numerator.
        val remainders = numerators.mapIndexed { index, numerator -> numerator - BigDecimal(minutes[index]) * sum }
        val left = total - ordered.size - minutes.sum()
        ordered.indices.sortedWith(compareByDescending<Int> { remainders[it] }.thenBy { it })
            .take(left).forEach { minutes[it]++ }
        return ordered.mapIndexed { index, (topic, _) -> topic to minutes[index] + 1 }
    }

    /** Reported study rows that start at or after [replanFrom]; they stay as they are. */
    fun keepIds(plan: SessionPlan, replanFrom: LocalDateTime): Set<String> =
        plan.current.schedules.filter { row ->
            SessionPlan.isStudyId(row.scheduleId) && plan.checkInFor(row.scheduleId) != null &&
                !parse(row.startDate).isBefore(replanFrom)
        }.map { it.scheduleId }.toSet()

    /** Whether any unreported study row starts at or after [replanFrom], so a re-split could change it. */
    fun hasRevisable(plan: SessionPlan, replanFrom: LocalDateTime): Boolean = runCatching {
        revisable(plan.current.schedules, keepIds(plan, replanFrom), replanFrom).isNotEmpty()
    }.getOrDefault(false)

    /** The first study ID above every current study row and every retained report or conflict key. */
    fun firstNewStudyId(plan: SessionPlan): String {
        val keys = plan.current.schedules.map { it.scheduleId }.filter(SessionPlan::isStudyId) +
            plan.checkIns.keys + plan.keptConflicts.keys
        val highest = keys.mapNotNull { studyId.matchEntire(it)?.groupValues?.get(1)?.toLongOrNull() }.maxOrNull() ?: 0L
        return "study-${Math.incrementExact(highest)}"
    }

    fun resplit(
        rows: List<ScheduleRow>,
        keep: Set<String>,
        answers: List<TopicAnswer>,
        replanFrom: LocalDateTime,
        now: LocalDateTime,
        firstNewStudyId: String,
    ): ResplitResult {
        val revisable = revisable(rows, keep, replanFrom)
        if (revisable.any { parse(it.startDate).isBefore(now) }) return ResplitResult.Failed(ReassessmentFailure.PreviewExpired)
        if (revisable.isEmpty()) return ResplitResult.Failed(ReassessmentFailure.NothingToRevise)
        val budget = budget(scores(answers), revisable.sumOf { minutes(it) })
            ?: return ResplitResult.Failed(ReassessmentFailure.NotEnoughTime)
        val allocation = sessionBudgets(revisable.map { minutes(it) }, budget)
            ?: return ResplitResult.Failed(ReassessmentFailure.NotEnoughTime)
        var next = requireNotNull(studyId.matchEntire(firstNewStudyId)?.groupValues?.get(1)?.toLong()) {
            "새 공부 일정 번호를 처리할 수 없어요."
        }
        val revised = mutableListOf<ScheduleRow>()
        for ((row, assigned) in revisable.zip(allocation)) {
            var cursor = parse(row.startDate)
            val pieces = mutableListOf<Triple<String, LocalDateTime, LocalDateTime>>()
            for ((topic, take) in assigned) {
                pieces += Triple(topic, cursor, cursor.plusMinutes(take.toLong()))
                cursor = cursor.plusMinutes(take.toLong())
            }
            if (pieces.size == 1 && pieces[0].first == row.topic) {
                revised += row
            } else {
                pieces.forEach { (name, start, pieceEnd) ->
                    revised += ScheduleRow("study-${next++}", WireDateTime.format(start), WireDateTime.format(pieceEnd), name)
                }
            }
        }
        val revisableIds = revisable.map { it.scheduleId }.toSet()
        return ResplitResult.Revised(sorted(rows.filterNot { it.scheduleId in revisableIds } + revised))
    }

    /** Before/after minutes per topic and every changed session, from a current plan and its re-split. */
    fun preview(current: List<ScheduleRow>, keep: Set<String>, replanFrom: LocalDateTime, revised: List<ScheduleRow>): ReassessmentPreview {
        val revisable = revisable(current, keep, replanFrom)
        val retained = revised.toSet()
        val currentIds = current.map { it.scheduleId }.toSet()
        val pieces = revised.filter { it.scheduleId !in currentIds }
        val replaced = revisable.filter { it !in retained }
        val after = revisable.filter { it in retained } + pieces
        val before = revisable.groupBy { it.topic }.mapValues { (_, rows) -> rows.sumOf { minutes(it) } }
        val afterTotals = after.groupBy { it.topic }.mapValues { (_, rows) -> rows.sumOf { minutes(it) } }
        val topics = (before.keys + afterTotals.keys).sortedWith(compareBy<String> {
            LocalTopics.catalogOrder.indexOf(it).let { index -> if (index < 0) Int.MAX_VALUE else index }
        }.thenBy { it }).map { TopicMinutes(it, before[it] ?: 0, afterTotals[it] ?: 0) }
        val sessions = replaced.map { row ->
            val start = parse(row.startDate)
            val end = parse(row.endDate)
            RevisedSession(row, sorted(pieces.filter { !parse(it.startDate).isBefore(start) && !parse(it.endDate).isAfter(end) }))
        }
        return ReassessmentPreview(sorted(revised), topics, sessions)
    }

    /**
     * Whether [revised] is a well-formed re-split of [current]: non-revisable rows are unchanged, each
     * revisable row is either kept exactly or fully covered by new pieces without gaps or overlaps, and
     * every new piece has a fresh study ID from [firstNewStudyId] upward.
     */
    fun isValidRevision(
        current: List<ScheduleRow>,
        keep: Set<String>,
        replanFrom: LocalDateTime,
        firstNewStudyId: String,
        revised: List<ScheduleRow>,
    ): Boolean = runCatching {
        if (revised.map { it.scheduleId }.toSet().size != revised.size) return false
        val floor = studyId.matchEntire(firstNewStudyId)?.groupValues?.get(1)?.toLong() ?: return false
        val revisable = revisable(current, keep, replanFrom)
        val revisableIds = revisable.map { it.scheduleId }.toSet()
        val fixed = current.filterNot { it.scheduleId in revisableIds }
        val byId = revised.associateBy { it.scheduleId }
        if (fixed.any { byId[it.scheduleId] != it }) return false
        if (revisable.any { row -> byId[row.scheduleId].let { it != null && it != row } }) return false
        val currentIds = current.map { it.scheduleId }.toSet()
        val pieces = revised.filter { it.scheduleId !in currentIds }
        if (pieces.any { piece ->
                val number = studyId.matchEntire(piece.scheduleId)?.groupValues?.get(1)?.toLongOrNull()
                number == null || number < floor || piece.topic.isBlank() || minutes(piece) < 1
            }
        ) return false
        if (fixed.size + revisable.count { it.scheduleId in byId } + pieces.size != revised.size) return false
        val replaced = revisable.filter { it.scheduleId !in byId }
        var covered = 0
        for (row in replaced) {
            val start = parse(row.startDate)
            val end = parse(row.endDate)
            val inside = pieces.filter { !parse(it.startDate).isBefore(start) && !parse(it.endDate).isAfter(end) }
                .sortedBy { parse(it.startDate) }
            var cursor = start
            for (piece in inside) {
                if (parse(piece.startDate) != cursor) return false
                cursor = parse(piece.endDate)
            }
            if (cursor != end) return false
            covered += inside.size
        }
        covered == pieces.size
    }.getOrDefault(false)

    private fun revisable(rows: List<ScheduleRow>, keep: Set<String>, replanFrom: LocalDateTime): List<ScheduleRow> =
        rows.filter { SessionPlan.isStudyId(it.scheduleId) && it.scheduleId !in keep && !parse(it.startDate).isBefore(replanFrom) }
            .sortedWith(compareBy<ScheduleRow> { parse(it.startDate) }.thenBy { it.scheduleId })

    private fun sorted(rows: List<ScheduleRow>): List<ScheduleRow> =
        rows.sortedWith(compareBy<ScheduleRow> { it.startDate }.thenBy { it.endDate }.thenBy { it.scheduleId })

    private fun minutes(row: ScheduleRow): Int = Duration.between(parse(row.startDate), parse(row.endDate)).toMinutes().toInt()

    private fun parse(value: String): LocalDateTime =
        requireNotNull(WireDateTime.parseOrNull(value)) { "일정 시간을 읽을 수 없어요." }
}
