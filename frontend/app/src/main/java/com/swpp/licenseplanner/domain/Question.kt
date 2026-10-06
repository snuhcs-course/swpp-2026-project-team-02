package com.swpp.licenseplanner.domain

/** Reserved choice key for the explicit "모르겠음" answer (design D5/D6). */
const val UNKNOWN_KEY = "UNKNOWN"
const val UNKNOWN_LABEL = "모르겠음"

/**
 * One loaded example question. [choices] keeps the returned order and keys; the
 * local UNKNOWN choice is appended only by [choicesWithUnknown].
 */
data class Question(
    val id: Int,
    val prompt: String,
    val choices: Map<String, String>,
    val correctAnswer: String,
    val possibleScore: Double,
) {
    /** Answer options shown as cards; UNKNOWN has its own button. */
    val answerChoices: Map<String, String>
        get() = choices.filterKeys { it != UNKNOWN_KEY }

    /** Submitted choice map: the returned choices plus `UNKNOWN: 모르겠음`. */
    fun choicesWithUnknown(): Map<String, String> =
        LinkedHashMap(choices).apply { put(UNKNOWN_KEY, UNKNOWN_LABEL) }
}
