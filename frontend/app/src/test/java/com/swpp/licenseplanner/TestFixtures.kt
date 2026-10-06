package com.swpp.licenseplanner

import com.swpp.licenseplanner.data.QuestionAdapter
import com.swpp.licenseplanner.domain.CERTIFICATION_ID
import com.swpp.licenseplanner.domain.CurrentLevel
import com.swpp.licenseplanner.domain.DayAvailability
import com.swpp.licenseplanner.domain.PlanFormState
import com.swpp.licenseplanner.domain.Question
import com.swpp.licenseplanner.domain.Weekday
import com.swpp.licenseplanner.domain.defaultAvailability
import java.time.LocalDate
import java.time.LocalTime

/** Shared test data. The public response fixture mirrors the pinned bank's public fields. */
object TestFixtures {
    fun publicResponseJson(): String =
        checkNotNull(javaClass.classLoader?.getResource("questions-response.json")) { "missing fixture" }.readText()

    fun publicQuestions(): List<Question> = QuestionAdapter.parse(publicResponseJson(), CERTIFICATION_ID)

    /** A valid three-question set: two known IDs and one new, unmapped ID worth 2 points. */
    fun threeQuestionJson(): String = """
        {"certification_id":"computer_specialist_level_2","certification_name":"컴퓨터활용능력 2급",
         "questions":[
          {"id":2,"prompt":"합계 함수는?","choices":{"A":"=COUNT(A1:A5)","B":"=SUM(A1:A5)"},"correct_answer":"B","possible_score":1},
          {"id":42,"prompt":"새 예시 문항","choices":{"A":"가","B":"나","C":"다"},"correct_answer":"C","possible_score":2},
          {"id":4,"prompt":"비율 비교 차트는?","choices":{"A":"원형 차트","B":"분산형 차트"},"correct_answer":"A","possible_score":1}
         ]}
    """.trimIndent()

    /** GEN-01 scenario: Mon 18:00+2h, Wed 18:00+1h, Sat 10:00+3h; 2026-10-05 → 2026-11-16. */
    fun gen01Availability(): Map<Weekday, DayAvailability> = defaultAvailability() + mapOf(
        Weekday.MONDAY to DayAvailability(true, LocalTime.of(18, 0), 120),
        Weekday.WEDNESDAY to DayAvailability(true, LocalTime.of(18, 0), 60),
        Weekday.SATURDAY to DayAvailability(true, LocalTime.of(10, 0), 180),
    )

    fun completeState(
        questions: List<Question> = publicQuestions(),
        answers: Map<Int, String> = questions.associate { it.id to it.correctAnswer },
        level: CurrentLevel = CurrentLevel.BEGINNER,
        availability: Map<Weekday, DayAvailability> = gen01Availability(),
        start: LocalDate = LocalDate.of(2026, 10, 5),
        exam: LocalDate = LocalDate.of(2026, 11, 16),
    ) = PlanFormState(
        certificationId = CERTIFICATION_ID,
        preparationStart = start,
        examDate = exam,
        level = level,
        questions = questions,
        answers = answers,
        availability = availability,
    )
}
