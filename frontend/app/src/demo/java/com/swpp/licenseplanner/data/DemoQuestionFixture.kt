package com.swpp.licenseplanner.data

import com.swpp.licenseplanner.domain.Question

/**
 * Demo-only example questions copied from the pinned bank
 * (`origin/JunwooCho` 2b6e2f5b, backend/assessment_questionnaire/questions.json):
 * prompts, choices, correct keys, and scores only. Authored examples, not official
 * exam questions or a validated diagnostic. Live builds never include this file.
 */
object DemoQuestionFixture {
    val questions: List<Question> = listOf(
        Question(
            id = 1,
            prompt = "셀 주소 A1을 수식에서 고정 참조로 사용하려면 어떻게 표시하나요?",
            choices = linkedMapOf("A" to "A1", "B" to "\$A\$1", "C" to "A\$", "D" to "#A1"),
            correctAnswer = "B",
            possibleScore = 1.0,
        ),
        Question(
            id = 2,
            prompt = "A1부터 A5까지의 합계를 구하는 수식으로 알맞은 것은 무엇인가요?",
            choices = linkedMapOf("A" to "=COUNT(A1:A5)", "B" to "=SUM(A1:A5)", "C" to "=MAX(A1:A5)", "D" to "=AVERAGE(A1:A5)"),
            correctAnswer = "B",
            possibleScore = 1.0,
        ),
        Question(
            id = 3,
            prompt = "A1의 값이 60 이상이면 '통과', 아니면 '재시험'을 표시하는 수식은 무엇인가요?",
            choices = linkedMapOf("A" to "=IF(A1>=60,\"통과\",\"재시험\")", "B" to "=SUM(A1>=60,\"통과\",\"재시험\")", "C" to "=COUNTIF(A1,60,\"통과\")", "D" to "=AVERAGE(A1>=60)"),
            correctAnswer = "A",
            possibleScore = 1.0,
        ),
        Question(
            id = 4,
            prompt = "전체에 대한 각 항목의 비율을 비교할 때 일반적으로 알맞은 차트는 무엇인가요?",
            choices = linkedMapOf("A" to "원형 차트", "B" to "분산형 차트", "C" to "주식형 차트", "D" to "표면형 차트"),
            correctAnswer = "A",
            possibleScore = 1.0,
        ),
        Question(
            id = 5,
            prompt = "자동 필터를 적용했을 때 조건에 맞지 않는 행은 어떻게 되나요?",
            choices = linkedMapOf("A" to "영구 삭제됩니다", "B" to "다른 워크시트로 이동합니다", "C" to "화면에서 일시적으로 숨겨집니다", "D" to "셀 수식이 값으로 바뀝니다"),
            correctAnswer = "C",
            possibleScore = 1.0,
        ),
    )
}
