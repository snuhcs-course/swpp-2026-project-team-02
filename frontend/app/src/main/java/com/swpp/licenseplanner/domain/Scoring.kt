package com.swpp.licenseplanner.domain

/** Local earned/possible points for one example label. */
data class TopicScore(val topic: String, val earned: Double, val possible: Double) {
    val percent: Double get() = if (possible > 0) earned / possible * 100 else 0.0
}

sealed interface QuestionResult {
    data object Correct : QuestionResult
    data class Wrong(val chosen: String, val correct: String) : QuestionResult
    data class Unknown(val correct: String) : QuestionResult
}

/** Local scoring of example answers; not the backend's weighted statistics. */
object Scoring {
    /** A normal answer is correct when it equals the correct key; UNKNOWN is never correct. */
    fun isCorrect(question: Question, answer: String?): Boolean =
        answer != null && answer != UNKNOWN_KEY && answer == question.correctAnswer

    fun earned(question: Question, answer: String?): Double =
        if (isCorrect(question, answer)) question.possibleScore else 0.0

    fun questionResult(question: Question, answer: String): QuestionResult {
        if (isCorrect(question, answer)) return QuestionResult.Correct
        val choices = question.answerChoices
        val correct = "${question.correctAnswer}. ${choices.getValue(question.correctAnswer)}"
        return if (answer == UNKNOWN_KEY) QuestionResult.Unknown(correct)
        else QuestionResult.Wrong("$answer. ${choices.getValue(answer)}", correct)
    }

    /** Earned/possible totals by local label, in [LocalTopics.catalogOrder]. */
    fun topicScores(questions: List<Question>, answers: Map<Int, String>): List<TopicScore> {
        val totals = LinkedHashMap<String, DoubleArray>()
        for (question in questions) {
            val sums = totals.getOrPut(LocalTopics.labelFor(question.id)) { DoubleArray(2) }
            sums[0] += earned(question, answers[question.id])
            sums[1] += question.possibleScore
        }
        return totals.entries
            .sortedBy { LocalTopics.catalogOrder.indexOf(it.key) }
            .map { TopicScore(it.key, it.value[0], it.value[1]) }
    }

    fun allAnswered(questions: List<Question>, answers: Map<Int, String>): Boolean =
        questions.isNotEmpty() && questions.all { question ->
            answers[question.id]?.let { it in question.choicesWithUnknown() } == true
        }
}

/** Formats points without a trailing ".0" for whole numbers. */
fun formatPoints(value: Double): String =
    if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()
