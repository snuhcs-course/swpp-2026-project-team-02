package com.swpp.licenseplanner.ui

import com.swpp.licenseplanner.data.ApiError
import com.swpp.licenseplanner.data.ReassessmentInput
import com.swpp.licenseplanner.data.ReassessmentQuestion
import com.swpp.licenseplanner.data.model.ScheduleRow
import com.swpp.licenseplanner.domain.LocalTopics
import com.swpp.licenseplanner.domain.ReassessmentPreview
import com.swpp.licenseplanner.domain.Scoring
import com.swpp.licenseplanner.domain.SessionPlan
import kotlin.math.roundToInt

/** The plan-view action is disabled with this note when no future unreported study could change. */
internal const val REASSESSMENT_NOTHING_LEFT = "바꿀 수 있는 남은 공부가 없어요"

/** Shown instead of a preview or update when the plan changed or a save is owed; nothing was sent. */
internal const val REASSESSMENT_PLAN_CHANGED = "계획이 바뀌었거나 저장할 변경이 있어요. 계획으로 돌아가 다시 진단해 주세요."

/**
 * One reassessment started from [source]'s plan view. Answers and results live only here, so
 * leaving the flow discards them; nothing is written while this state is shown.
 */
sealed interface ReassessmentState {
    val source: SessionPlan

    /** [attempt] tells a late question load from a newer one for the same plan. */
    data class Loading(override val source: SessionPlan, val attempt: Int) : ReassessmentState
    data class LoadFailure(override val source: SessionPlan, val error: ApiError) : ReassessmentState
    /** The server has no reassessment questions yet; no other questions stand in for them. */
    data class ServerNeedsUpdate(override val source: SessionPlan) : ReassessmentState
    data class Quiz(
        override val source: SessionPlan,
        val questions: List<ReassessmentQuestion>,
        val answers: Map<Int, String> = emptyMap(),
        val index: Int = 0,
    ) : ReassessmentState {
        val allAnswered: Boolean get() = Scoring.allAnswered(questions.map { it.question }, answers)
        /** Position of the first question without a valid answer, or null when every one is answered. */
        val firstUnanswered: Int? get() = questions.indexOfFirst {
            !Scoring.allAnswered(listOf(it.question), answers)
        }.takeIf { it >= 0 }
    }
    data class Results(
        override val source: SessionPlan,
        val questions: List<ReassessmentQuestion>,
        val answers: Map<Int, String>,
    ) : ReassessmentState

    /** A preview request is out; it cannot change anything, and a late answer after leaving is dropped. */
    data class Previewing(val input: ReassessmentInput, val attempt: Int) : ReassessmentState {
        override val source: SessionPlan get() = input.source
    }

    /** The changes to confirm; without a changed session only returning is offered. */
    data class Preview(val input: ReassessmentInput, val preview: ReassessmentPreview) : ReassessmentState {
        override val source: SessionPlan get() = input.source
    }

    /** The one confirmed update is out; the plan holds its recovery gate until the outcome is known. */
    data class Committing(val input: ReassessmentInput, val preview: ReassessmentPreview) : ReassessmentState {
        override val source: SessionPlan get() = input.source
    }

    /** The plan now has the previewed rows; [save] tells whether they are part of the saved collection. */
    data class Done(val input: ReassessmentInput, val preview: ReassessmentPreview, val save: MoveSave = MoveSave.Saving) :
        ReassessmentState {
        override val source: SessionPlan get() = input.source
    }

    /**
     * The preview or update stopped without changing the plan's rows. Only [ReassessmentStop.CommitFailed]
     * may leave the server's copy unknown; the plan then needs an explicit reload.
     */
    data class Stopped(val input: ReassessmentInput, val reason: ReassessmentStop, val error: ApiError? = null) : ReassessmentState {
        override val source: SessionPlan get() = input.source
        /** Another preview with the same answers may be asked for. */
        val canPreviewAgain: Boolean get() = reason == ReassessmentStop.Expired || reason == ReassessmentStop.PreviewFailed
    }

    /** The plan changed, or a save is owed, after the results were shown; nothing was sent. */
    data class Invalid(override val source: SessionPlan, val message: String) : ReassessmentState
}

enum class ReassessmentStop { NothingLeft, NotEnoughTime, Expired, PreviewFailed, CommitFailed }

/** Whether leaving now could abandon a request or a save in progress; those steps cannot be left. */
internal val ReassessmentState?.holdsCommit: Boolean
    get() = this is ReassessmentState.Committing || (this is ReassessmentState.Done && save == MoveSave.Saving)

/** Every new row of a preview in time order, each with the topic it replaces. */
internal fun changedPieces(preview: ReassessmentPreview): List<Pair<String, ScheduleRow>> =
    preview.sessions.flatMap { session -> session.pieces.map { session.original.topic to it } }

/** The first new row, so returning to the plan can show its week. */
internal fun firstChangedScheduleId(preview: ReassessmentPreview): String? = changedPieces(preview).firstOrNull()?.second?.scheduleId

/** One topic's points in this reassessment beside the plan's first diagnostic, in whole percent. */
data class TopicComparison(val topic: String, val earned: Double, val possible: Double, val firstPercent: Int?, val newPercent: Int)

/**
 * Topics in served question order. The first diagnostic is the plan's own creation answers,
 * labeled by their onboarding topics; a topic it did not cover has no first percent.
 */
internal fun topicComparisons(state: ReassessmentState.Results): List<TopicComparison> {
    val first = state.source.original.assessmentResults.results.groupBy { LocalTopics.labelFor(it.problemId) }
        .mapValues { (_, items) ->
            val possible = items.sumOf { it.possibleScore }
            if (possible > 0) percent(items.filter { it.isCorrect }.sumOf { it.possibleScore }, possible) else null
        }
    return state.questions.groupBy { it.topic }.map { (topic, items) ->
        val possible = items.sumOf { it.question.possibleScore }
        val earned = items.filter { Scoring.isCorrect(it.question, state.answers[it.question.id]) }.sumOf { it.question.possibleScore }
        TopicComparison(topic, earned, possible, first[topic], if (possible > 0) percent(earned, possible) else 0)
    }
}

private fun percent(earned: Double, possible: Double): Int = (earned / possible * 100).roundToInt()
