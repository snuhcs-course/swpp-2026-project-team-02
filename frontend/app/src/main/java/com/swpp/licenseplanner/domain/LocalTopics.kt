package com.swpp.licenseplanner.domain

/**
 * Local example labels keyed by the onboarding selection's question IDs.
 * These are display/demo assumptions, not server metadata, and are never submitted.
 * Review this mapping if the selected questions change.
 */
object LocalTopics {
    const val SYSTEM_USE = "컴퓨터 시스템 활용"
    const val INTERNET_DATA = "인터넷 자료 활용"
    const val SYSTEM_PROTECTION = "컴퓨터 시스템 보호"
    const val SPREADSHEET_BASICS = "스프레드시트 기본"
    const val FUNCTIONS_AND_DATA = "함수와 데이터 관리"
    const val CHARTS_AND_ANALYSIS = "차트와 분석"
    const val PAGE_SETUP_AND_PRINTING = "페이지 설정과 인쇄"
    const val MACROS = "매크로 활용"
    const val UNCLASSIFIED = "미분류 예시 문항"

    private val byQuestionId = mapOf(
        1 to SPREADSHEET_BASICS,
        2 to FUNCTIONS_AND_DATA,
        3 to FUNCTIONS_AND_DATA,
        4 to CHARTS_AND_ANALYSIS,
        5 to SPREADSHEET_BASICS,
        6 to SYSTEM_USE,
        7 to SYSTEM_USE,
        8 to SYSTEM_USE,
        9 to INTERNET_DATA,
        10 to INTERNET_DATA,
        11 to INTERNET_DATA,
        12 to INTERNET_DATA,
        13 to SYSTEM_PROTECTION,
        14 to SYSTEM_PROTECTION,
        15 to SPREADSHEET_BASICS,
        16 to SPREADSHEET_BASICS,
        17 to PAGE_SETUP_AND_PRINTING,
        18 to MACROS,
    )

    /** Display order: 컴퓨터 일반 labels, 스프레드시트 일반 labels, then the unclassified label. */
    val catalogOrder = listOf(
        SYSTEM_USE, INTERNET_DATA, SYSTEM_PROTECTION,
        SPREADSHEET_BASICS, FUNCTIONS_AND_DATA, CHARTS_AND_ANALYSIS, PAGE_SETUP_AND_PRINTING, MACROS,
        UNCLASSIFIED,
    )

    fun labelFor(questionId: Int): String = byQuestionId[questionId] ?: UNCLASSIFIED
}
