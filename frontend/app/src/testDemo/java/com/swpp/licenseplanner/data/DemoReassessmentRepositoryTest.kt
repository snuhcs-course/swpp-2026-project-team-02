package com.swpp.licenseplanner.data

import com.swpp.licenseplanner.data.model.ScheduleRow
import com.swpp.licenseplanner.domain.*
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDateTime

class DemoReassessmentRepositoryTest {
    private val fixture = ReassessmentFixtures
    private val questions = DemoReassessmentFixture.questions
    private val answers = questions.associate { it.question.id to it.question.correctAnswer } + mapOf(19 to UNKNOWN_KEY, 25 to UNKNOWN_KEY)
    private fun input(demo: Boolean = true, replanFrom: LocalDateTime = fixture.replanFrom) =
        ReassessmentInput(fixture.plan(demo), questions, answers, replanFrom)
    private fun repository(now: LocalDateTime = fixture.replanFrom) = DemoReassessmentRepository(questions) { now }

    @Test fun bundledSetMatchesTheBankItemsWithTheirTopicsAndExplanations() {
        val bank = File(requireNotNull(System.getProperty("user.dir")))
            .resolve("../../backend/assessment_questionnaire/questions.json")
        val stored = ApiJson.parseToJsonElement(bank.readText()).jsonObject.getValue("questions").jsonArray
            .map { it.jsonObject }.filter { it.getValue("id").jsonPrimitive.int in 19..36 }
        assertEquals((19..36).toList(), questions.map { it.question.id })
        for ((item, record) in questions.zip(stored)) {
            assertEquals(record.getValue("prompt").jsonPrimitive.content, item.question.prompt)
            assertEquals(record.getValue("choices").jsonObject.mapValues { it.value.jsonPrimitive.content }, item.question.choices)
            assertEquals(record.getValue("correct_answer").jsonPrimitive.content, item.question.correctAnswer)
            assertEquals(record.getValue("topic").jsonPrimitive.content, item.topic)
            assertEquals(record.getValue("explanation").jsonPrimitive.content, item.explanation)
        }
        assertEquals(LocalTopics.catalogOrder.toSet() - LocalTopics.UNCLASSIFIED, questions.map { it.topic }.toSet())
        val onboarding = DemoQuestionFixture.questions.map { it.prompt }.toSet()
        assertTrue(questions.none { it.question.prompt in onboarding })
    }

    @Test fun previewChangesNothingAndCommitReturnsThePreviewedRows() = runBlocking {
        val input = input()
        val before = input.source
        val preview = (repository().preview(input) as ReassessmentOutcome.Previewed).preview
        assertTrue(preview.hasChanges)
        assertEquals(before, input.source)
        assertEquals(input.source.current.schedules.first { it.scheduleId == "study-2" },
            preview.rows.first { it.scheduleId == "study-2" })
        val committed = repository().commit(input, preview.rows) as ReassessmentOutcome.Committed
        assertEquals(preview.rows, committed.response.schedules)
        assertEquals(preview.rows, ReassessmentCsv.rows(committed.response.scheduleCsv))
    }

    @Test fun commitAfterARevisableSessionStartedIsExpired() = runBlocking {
        val preview = (repository().preview(input()) as ReassessmentOutcome.Previewed).preview
        val late = repository(LocalDateTime.of(2026, 11, 18, 18, 1))
        assertEquals(ReassessmentOutcome.Rejected(ReassessmentFailure.PreviewExpired), late.commit(input(), preview.rows))
    }

    @Test fun commitThatWouldDifferFromThePreviewIsInvalid() = runBlocking {
        val preview = (repository().preview(input()) as ReassessmentOutcome.Previewed).preview
        val altered = preview.rows.map { if (it.scheduleId == "study-8") it.copy(topic = it.topic + " 변경") else it }
        assertTrue((repository().commit(input(), altered) as ReassessmentOutcome.Failure).error is ApiError.InvalidResponse)
    }

    @Test fun nothingLeftAndLivePlansAreRejectedLocally() = runBlocking {
        val afterEverything = input(replanFrom = LocalDateTime.of(2026, 11, 20, 0, 0))
        assertEquals(ReassessmentOutcome.Rejected(ReassessmentFailure.NothingToRevise),
            repository(afterEverything.replanFrom).preview(afterEverything))
        assertTrue((repository().preview(input(demo = false)) as ReassessmentOutcome.Failure).error is ApiError.InvalidInput)
    }

    private object ReassessmentCsv {
        fun rows(csv: String): List<ScheduleRow>? = parseRemainingCsv(csv)
    }
}
