package com.swpp.licenseplanner.data

import com.swpp.licenseplanner.TestFixtures
import com.swpp.licenseplanner.domain.LocalTopics
import com.swpp.licenseplanner.domain.Scoring
import com.swpp.licenseplanner.domain.UNKNOWN_KEY
import org.junit.Assert.assertEquals
import org.junit.Test

/** Demo-only: the bundled fixture keeps the pinned bank's five questions. */
class DemoFixtureScoringTest {
    @Test
    fun demoFixtureKeepsFiveIdsMatchingThePublicBank() {
        val demo = DemoQuestionFixture.questions
        assertEquals(listOf(1, 2, 3, 4, 5), demo.map { it.id })
        assertEquals(TestFixtures.publicQuestions(), demo)
        assertEquals(setOf(LocalTopics.SPREADSHEET_BASICS, LocalTopics.FUNCTIONS_AND_DATA, LocalTopics.CHARTS_AND_ANALYSIS),
            demo.map { LocalTopics.labelFor(it.id) }.toSet())
    }

    @Test
    fun demoFixtureScoresLocally() {
        val demo = DemoQuestionFixture.questions
        val answers = demo.associate { it.id to UNKNOWN_KEY } + (1 to "B")
        val total = Scoring.topicScores(demo, answers)
        assertEquals(1.0, total.sumOf { it.earned }, 0.0)
        assertEquals(5.0, total.sumOf { it.possible }, 0.0)
    }
}
