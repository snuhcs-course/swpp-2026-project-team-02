package com.swpp.licenseplanner.domain

/**
 * Local example labels keyed by the pinned bank's question IDs (design D6).
 * These are display/demo assumptions, not server metadata, and are never submitted.
 * Review this mapping if the bank's content changes.
 */
object LocalTopics {
    const val SPREADSHEET_BASICS = "스프레드시트 기본"
    const val FUNCTIONS_AND_DATA = "함수와 데이터 관리"
    const val CHARTS_AND_ANALYSIS = "차트와 분석"
    const val UNCLASSIFIED = "미분류 예시 문항"

    private val byQuestionId = mapOf(
        1 to SPREADSHEET_BASICS,
        2 to FUNCTIONS_AND_DATA,
        3 to FUNCTIONS_AND_DATA,
        4 to CHARTS_AND_ANALYSIS,
        5 to SPREADSHEET_BASICS,
    )

    /** Display order: the example catalog, then the unclassified label. */
    val catalogOrder = listOf(SPREADSHEET_BASICS, FUNCTIONS_AND_DATA, CHARTS_AND_ANALYSIS, UNCLASSIFIED)

    fun labelFor(questionId: Int): String = byQuestionId[questionId] ?: UNCLASSIFIED
}
