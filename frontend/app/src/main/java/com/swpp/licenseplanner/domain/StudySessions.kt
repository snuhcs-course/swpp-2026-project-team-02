package com.swpp.licenseplanner.domain

import java.time.Duration
import java.time.LocalDateTime

/**
 * Continuous study sessions formed by back-to-back study rows, matching the server's rule.
 *
 * A session is a maximal run of study rows in which each row starts no later than the previous
 * one ends. External rows and calendar events are not study, so they end a run like any gap.
 */
object StudySessions {
    /** Shortest session the app may plan, place or suggest. */
    const val MIN_MINUTES = 30L

    /** Every maximal run of study rows in [sessions], in time order. */
    fun runs(sessions: List<PlanSession>): List<StudyInterval> {
        val runs = mutableListOf<StudyInterval>()
        for (session in sessions.filterNot { it.isExternal }.sortedWith(compareBy({ it.start }, { it.end }))) {
            val last = runs.lastOrNull()
            if (last != null && session.start <= last.end) {
                runs[runs.lastIndex] = StudyInterval(last.start, maxOf(last.end, session.end))
            } else {
                runs += StudyInterval(session.start, session.end)
            }
        }
        return runs
    }

    /** The run of [sessions] that contains [row], or null when [row] is not a study row of [sessions]. */
    fun runContaining(sessions: List<PlanSession>, row: PlanSession): StudyInterval? =
        runs(sessions).firstOrNull { it.start <= row.start && row.end <= it.end }

    /**
     * Whether [candidate] keeps app-chosen placements in long-enough sessions: every run holding a
     * row in [placedIds] lasts at least [MIN_MINUTES]. When [futureFrom] is given, a run starting
     * at or after it must also not have been cut below the minimum from a run of [original] that
     * met it. Runs that were already short, and earlier runs, are not checked.
     */
    fun keepsSessions(
        candidate: List<PlanSession>,
        placedIds: Set<String>,
        original: List<PlanSession>,
        futureFrom: LocalDateTime?,
    ): Boolean {
        val placed = candidate.filter { it.scheduleId in placedIds }
        val originalRuns by lazy { runs(original) }
        return runs(candidate).all { run ->
            if (run.minutes >= MIN_MINUTES) return@all true
            if (placed.any { run.start <= it.start && it.end <= run.end }) return@all false
            futureFrom == null || run.start < futureFrom || originalRuns.none {
                it.start <= run.start && run.end <= it.end && it.minutes >= MIN_MINUTES
            }
        }
    }

    /** The earliest end among [sessions] strictly after [after]; later free time can only start at such an end. */
    fun nextEnd(after: LocalDateTime, sessions: List<PlanSession>): LocalDateTime? =
        sessions.map { it.end }.filter { it > after }.minOrNull()

    private val StudyInterval.minutes: Long get() = Duration.between(start, end).toMinutes()
}
