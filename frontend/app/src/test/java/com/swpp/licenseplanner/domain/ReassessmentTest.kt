package com.swpp.licenseplanner.domain

import com.swpp.licenseplanner.data.model.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDateTime

class ReassessmentTest {
    private val replanFrom = LocalDateTime.of(2026, 12, 2, 12, 0)
    private val rows = listOf(
        ScheduleRow("study-1", "2026/12/01/19/00", "2026/12/01/20/00", LocalTopics.SPREADSHEET_BASICS),
        ScheduleRow("external-1", "2026/12/02/18/00", "2026/12/02/19/00", "아르바이트"),
        ScheduleRow("study-2", "2026/12/02/19/00", "2026/12/02/20/00", LocalTopics.MACROS),
        ScheduleRow("study-3", "2026/12/03/19/00", "2026/12/03/20/00", LocalTopics.CHARTS_AND_ANALYSIS),
        ScheduleRow("study-4", "2026/12/04/19/00", "2026/12/04/20/30", LocalTopics.SPREADSHEET_BASICS),
    )
    private val answers = listOf(
        TopicAnswer(LocalTopics.CHARTS_AND_ANALYSIS, 1.0, false), TopicAnswer(LocalTopics.CHARTS_AND_ANALYSIS, 1.0, false),
        TopicAnswer(LocalTopics.MACROS, 1.0, true), TopicAnswer(LocalTopics.MACROS, 1.0, false),
        TopicAnswer(LocalTopics.SPREADSHEET_BASICS, 1.0, true), TopicAnswer(LocalTopics.SPREADSHEET_BASICS, 1.0, true),
    )
    private val keep = setOf("study-2")

    private fun revised() =
        (Reassessment.resplit(rows, keep, answers, replanFrom, replanFrom, "study-5") as ResplitResult.Revised).rows

    @Test fun budgetGivesWeakerTopicsAtLeastAsManyMinutesAndKeepsTheTotal() {
        val budget = requireNotNull(Reassessment.budget(Reassessment.scores(answers), 150))
        assertEquals(listOf(LocalTopics.CHARTS_AND_ANALYSIS to 66, LocalTopics.MACROS to 50, LocalTopics.SPREADSHEET_BASICS to 34), budget)
        assertNull(Reassessment.budget(Reassessment.scores(answers), 2))
    }

    @Test fun previewListsTopicMinutesAndOnlyTheChangedSession() {
        val preview = Reassessment.preview(rows, keep, replanFrom, revised())
        assertEquals(listOf(
            TopicMinutes(LocalTopics.SPREADSHEET_BASICS, 90, 34),
            TopicMinutes(LocalTopics.CHARTS_AND_ANALYSIS, 60, 66),
            TopicMinutes(LocalTopics.MACROS, 0, 50),
        ), preview.topics)
        assertEquals(150, preview.totalMinutes)
        assertEquals(1, preview.sessions.size)
        assertEquals("study-4", preview.sessions.single().original.scheduleId)
        assertEquals(listOf("study-5", "study-6", "study-7"), preview.sessions.single().pieces.map { it.scheduleId })
        assertTrue(preview.hasChanges)
    }

    @Test fun previewWithoutTopicChangesHasNoSessions() {
        val same = listOf(rows[3], rows[4].copy(endDate = "2026/12/04/19/30"))
        val twoTopics = answers.filter { it.topic != LocalTopics.MACROS }
        val result = Reassessment.resplit(same, emptySet(), twoTopics, replanFrom, replanFrom, "study-5") as ResplitResult.Revised
        assertEquals(same, result.rows)
        assertFalse(Reassessment.preview(same, emptySet(), replanFrom, result.rows).hasChanges)
    }

    @Test fun keptRowsAreReportedFutureRowsAndNewIdsAvoidRetainedKeys() {
        val plan = SessionPlan.register(1, RemainingStudyFixtures.original, StudyPlanResponse(rows), true).copy(
            checkIns = mapOf("study-2" to StudyCheckIn(StudyCheckInStatus.Partly, 20),
                "study-1" to StudyCheckIn(StudyCheckInStatus.Done, 60),
                "study-15" to StudyCheckIn(StudyCheckInStatus.Done, 30)))
        assertEquals(setOf("study-2"), Reassessment.keepIds(plan, replanFrom))
        assertEquals("study-16", Reassessment.firstNewStudyId(plan))
        assertEquals("study-5", Reassessment.firstNewStudyId(plan.copy(checkIns = emptyMap())))
    }

    @Test fun startedRevisableRowExpiresThePreview() {
        val result = Reassessment.resplit(rows, keep, answers, replanFrom, LocalDateTime.of(2026, 12, 3, 19, 1), "study-5")
        assertEquals(ResplitResult.Failed(ReassessmentFailure.PreviewExpired), result)
    }

    @Test fun validationRejectsChangedProtectedRowsGapsAndReusedIds() {
        val good = revised()
        assertTrue(Reassessment.isValidRevision(rows, keep, replanFrom, "study-5", good))
        fun invalid(changed: List<ScheduleRow>) = assertFalse(Reassessment.isValidRevision(rows, keep, replanFrom, "study-5", changed))
        invalid(good.map { if (it.scheduleId == "study-2") it.copy(topic = LocalTopics.CHARTS_AND_ANALYSIS) else it })
        invalid(good.filterNot { it.scheduleId == "study-6" })
        invalid(good.map { if (it.scheduleId == "study-7") it.copy(startDate = "2026/12/04/19/57") else it })
        invalid(good.map { if (it.scheduleId == "study-5") it.copy(scheduleId = "study-4") else it })
        invalid(good.map { if (it.scheduleId == "study-3") it.copy(topic = LocalTopics.MACROS) else it })
        invalid(good.filterNot { it.scheduleId == "external-1" })
        assertFalse(Reassessment.isValidRevision(rows, keep, replanFrom, "study-6", good))
    }
}
