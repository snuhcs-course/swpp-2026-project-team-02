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
        assertEquals("컴퓨터 시스템 활용", LocalTopics.labelFor(6))
        assertEquals("인터넷 자료 활용", LocalTopics.labelFor(9))
        assertEquals("컴퓨터 시스템 보호", LocalTopics.labelFor(13))
        assertEquals("스프레드시트 기본", LocalTopics.labelFor(15))
        assertEquals("페이지 설정과 인쇄", LocalTopics.labelFor(17))
        assertEquals("매크로 활용", LocalTopics.labelFor(18))
        assertEquals("미분류 예시 문항", LocalTopics.labelFor(19))
        assertEquals("미분류 예시 문항", LocalTopics.labelFor(42))
    }

    @Test
    fun topicTotalsForBundledBank() {
        // 컴퓨터 일반 answered UNKNOWN; 1 of 2 points in 함수와 데이터 관리 and a wrong chart answer.
        val answers = questions.associate { it.id to it.correctAnswer } +
            (6..14).associateWith { UNKNOWN_KEY } + mapOf(3 to UNKNOWN_KEY, 4 to "B")
        val scores = Scoring.topicScores(questions, answers)
        assertEquals(LocalTopics.catalogOrder.dropLast(1), scores.map { it.topic })
        val byTopic = scores.associateBy { it.topic }
        assertEquals(TopicScore("컴퓨터 시스템 활용", 0.0, 3.0), byTopic["컴퓨터 시스템 활용"])
        assertEquals(TopicScore("인터넷 자료 활용", 0.0, 4.0), byTopic["인터넷 자료 활용"])
        assertEquals(TopicScore("컴퓨터 시스템 보호", 0.0, 2.0), byTopic["컴퓨터 시스템 보호"])
        assertEquals(TopicScore("스프레드시트 기본", 4.0, 4.0), byTopic["스프레드시트 기본"])
        assertEquals(TopicScore("함수와 데이터 관리", 1.0, 2.0), byTopic["함수와 데이터 관리"])
        assertEquals(TopicScore("차트와 분석", 0.0, 1.0), byTopic["차트와 분석"])
        assertEquals(TopicScore("페이지 설정과 인쇄", 1.0, 1.0), byTopic["페이지 설정과 인쇄"])
        assertEquals(TopicScore("매크로 활용", 1.0, 1.0), byTopic["매크로 활용"])
        assertEquals(18.0, scores.sumOf { it.possible }, 0.0)
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
        val lastId = questions.last().id
        assertFalse(Scoring.allAnswered(questions, partial + (lastId to "Z")))
        assertTrue(Scoring.allAnswered(questions, partial + (lastId to UNKNOWN_KEY)))
        assertFalse(Scoring.allAnswered(emptyList(), emptyMap()))
    }
}
