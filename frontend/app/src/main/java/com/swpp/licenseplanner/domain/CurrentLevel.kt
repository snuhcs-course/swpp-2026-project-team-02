package com.swpp.licenseplanner.domain

/** FLOW-03: the three level choices and their fixed self-assessment sentences (design D6). */
enum class CurrentLevel(val label: String, val selfAssessment: String) {
    BEGINNER("처음 공부해요", "엑셀을 처음 공부하는 초보입니다."),
    INTERMEDIATE("기본 기능은 알아요", "기본 기능은 알지만 함수와 차트는 연습이 필요합니다."),
    ADVANCED("익숙해요", "엑셀 기능에 익숙합니다."),
    ;

    companion object {
        fun fromSelfAssessment(sentence: String?): CurrentLevel? =
            entries.firstOrNull { it.selfAssessment == sentence }
    }
}
