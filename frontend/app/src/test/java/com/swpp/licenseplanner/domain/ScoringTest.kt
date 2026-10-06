package com.swpp.licenseplanner.domain

import com.swpp.licenseplanner.TestFixtures
import com.swpp.licenseplanner.data.QuestionAdapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScoringTest {
    private val questions = TestFixtures.publicQuestions()
    private val q1 = questions.first { it.id == 1 }

    @Test
    fun correctAnswerEarnsReturnedPossiblePoints() {
        assertTrue(Scoring.isCorrect(q1, "B"))
        assertEquals(1.0, Scoring.earned(q1, "B"), 0.0)
    }

    @Test
    fun wrongAndUnknownAnswersEarnZero() {
        assertFalse(Scoring.isCorrect(q1, "A"))
        assertEquals(0.0, Scoring.earned(q1, "A"), 0.0)
        assertFalse(Scoring.isCorrect(q1, UNKNOWN_KEY))
        assertEquals(0.0, Scoring.earned(q1, UNKNOWN_KEY), 0.0)
        assertEquals(0.0, Scoring.earned(q1, null), 0.0)
    }

    @Test
    fun unknownChoiceIsAppendedWithReservedLabel() {
        val choices = q1.choicesWithUnknown()
        assertEquals(listOf("A", "B", "C", "D", UNKNOWN_KEY), choices.keys.toList())
        assertEquals(UNKNOWN_LABEL, choices[UNKNOWN_KEY])
        assertEquals(listOf("A", "B", "C", "D"), q1.answerChoices.keys.toList())
    }

    @Test
    fun knownLabelsAreKeptAndUnmappedIdsAreUnclassified() {
        assertEquals("스프레드시트 기본", LocalTopics.labelFor(1))
        assertEquals("함수와 데이터 관리", LocalTopics.labelFor(2))
        assertEquals("함수와 데이터 관리", LocalTopics.labelFor(3))
        assertEquals("차트와 분석", LocalTopics.labelFor(4))
        assertEquals("스프레드시트 기본", LocalTopics.labelFor(5))
        assertEquals("미분류 예시 문항", LocalTopics.labelFor(42))
    }

    @Test
    fun topicTotalsForPinnedBank() {
        // FLOW-05 scenario: 1 of 2 points in 함수와 데이터 관리.
        val answers = mapOf(1 to "B", 2 to "B", 3 to UNKNOWN_KEY, 4 to "B", 5 to "C")
        val scores = Scoring.topicScores(questions, answers).associateBy { it.topic }
        assertEquals(TopicScore("스프레드시트 기본", 2.0, 2.0), scores["스프레드시트 기본"])
        assertEquals(TopicScore("함수와 데이터 관리", 1.0, 2.0), scores["함수와 데이터 관리"])
        assertEquals(TopicScore("차트와 분석", 0.0, 1.0), scores["차트와 분석"])
    }

    @Test
    fun threeQuestionSetWithNewIdAndScoreTwo() {
        val loaded = QuestionAdapter.parse(TestFixtures.threeQuestionJson(), CERTIFICATION_ID)
        assertEquals(listOf(2, 42, 4), loaded.map { it.id })
        val answers = mapOf(2 to UNKNOWN_KEY, 42 to "C", 4 to "A")
        assertTrue(Scoring.allAnswered(loaded, answers))
        val scores = Scoring.topicScores(loaded, answers)
        // Catalog order, unclassified last; the unmapped question's 2/2 is included.
        assertEquals(
            listOf(
                TopicScore("함수와 데이터 관리", 0.0, 1.0),
                TopicScore("차트와 분석", 1.0, 1.0),
                TopicScore("미분류 예시 문항", 2.0, 2.0),
            ),
            scores,
        )
    }

    @Test
    fun allAnsweredRequiresAValidKeyForEveryQuestion() {
        val partial = questions.dropLast(1).associate { it.id to it.correctAnswer }
        assertFalse(Scoring.allAnswered(questions, partial))
        assertFalse(Scoring.allAnswered(questions, partial + (5 to "Z")))
        assertTrue(Scoring.allAnswered(questions, partial + (5 to UNKNOWN_KEY)))
        assertFalse(Scoring.allAnswered(emptyList(), emptyMap()))
    }
}
