package com.swpp.licenseplanner.data

import com.swpp.licenseplanner.TestFixtures
import com.swpp.licenseplanner.domain.LocalTopics
import com.swpp.licenseplanner.domain.Scoring
import com.swpp.licenseplanner.domain.UNKNOWN_KEY
import org.junit.Assert.assertEquals
import org.junit.Test

/** Demo-only: the bundled fixture mirrors the served two-subject onboarding selection. */
class DemoFixtureScoringTest {
    @Test
    fun demoFixtureMatchesTheServedSelectionAndItsLabels() {
        val demo = DemoQuestionFixture.questions
        assertEquals((1..18).toList(), demo.map { it.id })
        assertEquals(TestFixtures.publicQuestions(), demo)
        assertEquals(LocalTopics.catalogOrder - LocalTopics.UNCLASSIFIED,
            Scoring.topicScores(demo, emptyMap()).map { it.topic })
    }

    @Test
    fun demoFixtureScoresLocally() {
        val demo = DemoQuestionFixture.questions
        val answers = demo.associate { it.id to UNKNOWN_KEY } + (1 to "B") + (13 to "C") + (18 to "B")
        val total = Scoring.topicScores(demo, answers)
        assertEquals(3.0, total.sumOf { it.earned }, 0.0)
        assertEquals(18.0, total.sumOf { it.possible }, 0.0)
        val byTopic = total.associateBy { it.topic }
        assertEquals(1.0, byTopic.getValue(LocalTopics.SYSTEM_PROTECTION).earned, 0.0)
        assertEquals(2.0, byTopic.getValue(LocalTopics.SYSTEM_PROTECTION).possible, 0.0)
        assertEquals(1.0, byTopic.getValue(LocalTopics.MACROS).earned, 0.0)
        assertEquals(0.0, byTopic.getValue(LocalTopics.SYSTEM_USE).earned, 0.0)
    }
}
