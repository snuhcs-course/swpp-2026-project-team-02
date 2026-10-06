package com.swpp.licenseplanner.data

import com.swpp.licenseplanner.TestFixtures
import com.swpp.licenseplanner.data.model.StudyPlanRequest
import com.swpp.licenseplanner.data.model.StudyPlanResponse
import com.swpp.licenseplanner.domain.CERTIFICATION_ID
import com.swpp.licenseplanner.domain.CurrentLevel
import com.swpp.licenseplanner.domain.UNKNOWN_KEY
import com.swpp.licenseplanner.domain.WireDateTime
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDateTime

class RequestMapperTest {
    private fun ready(result: MappingResult): StudyPlanRequest = (result as MappingResult.Ready).request
    private fun toJson(request: StudyPlanRequest): JsonObject =
        ApiJson.parseToJsonElement(ApiJson.encodeToString(StudyPlanRequest.serializer(), request)).jsonObject

    @Test
    fun gen01DatesWindowsAndCounts() {
        val json = toJson(ready(RequestMapper.map(TestFixtures.completeState())))
        assertEquals("computer_specialist_level_2", json["certification_id"]!!.jsonPrimitive.content)
        assertEquals("2026/10/05/00/00", json["preparation_start"]!!.jsonPrimitive.content)
        assertEquals("2026/11/16/00/00", json["exam_date"]!!.jsonPrimitive.content)
        assertEquals(CurrentLevel.BEGINNER.selfAssessment, json["self_assessment"]!!.jsonPrimitive.content)
        assertEquals(3, json["study_days_per_week"]!!.jsonPrimitive.int)
        val windows = json["weekly_availability"]!!.jsonObject
        assertEquals(listOf("monday", "wednesday", "saturday"), windows.keys.toList())
        assertEquals("""[{"start":"18:00","end":"20:00"}]""", windows["monday"].toString())
        assertEquals("""[{"start":"18:00","end":"19:00"}]""", windows["wednesday"].toString())
        assertEquals("""[{"start":"10:00","end":"13:00"}]""", windows["saturday"].toString())
        assertEquals(JsonArray(emptyList()), json["busy_schedules"])
    }

    @Test
    fun everyLoadedQuestionIsMappedWithUnknownChoiceAndNoLocalFields() {
        val questions = TestFixtures.publicQuestions()
        val answers = questions.associate { it.id to it.correctAnswer } + mapOf(3 to UNKNOWN_KEY, 5 to UNKNOWN_KEY)
        val json = toJson(ready(RequestMapper.map(TestFixtures.completeState(questions, answers))))
        val assessment = json["assessment_results"]!!.jsonObject
        assertEquals(CERTIFICATION_ID, assessment["certification_id"]!!.jsonPrimitive.content)
        val results = assessment["results"]!!.jsonArray.map { it.jsonObject }
        assertEquals(questions.map { it.id }, results.map { it["problem_id"]!!.jsonPrimitive.int })
        for ((question, result) in questions.zip(results)) {
            assertEquals(question.prompt, result["question"]!!.jsonPrimitive.content)
            assertEquals(question.correctAnswer, result["correct_answer"]!!.jsonPrimitive.content)
            assertEquals("1", result["possible_score"].toString())
            val choices = result["choices"]!!.jsonObject.mapValues { it.value.jsonPrimitive.content }
            assertEquals(question.choices + (UNKNOWN_KEY to "모르겠음"), choices)
            assertEquals(
                setOf("problem_id", "question", "choices", "correct_answer", "user_answer", "is_correct", "possible_score"),
                result.keys,
            )
        }
        // Two UNKNOWN answers: user_answer UNKNOWN, is_correct false.
        for (id in listOf(3, 5)) {
            val result = results.first { it["problem_id"]!!.jsonPrimitive.int == id }
            assertEquals(UNKNOWN_KEY, result["user_answer"]!!.jsonPrimitive.content)
            assertFalse(result["is_correct"]!!.jsonPrimitive.boolean)
        }
        // Correct answer B for question 1.
        val first = results.first()
        assertEquals("B", first["user_answer"]!!.jsonPrimitive.content)
        assertTrue(first["is_correct"]!!.jsonPrimitive.boolean)
        for (legacy in listOf("problem_results", "problem_results_csv", "assessment_answers")) assertNull(json[legacy])
    }

    @Test
    fun threeQuestionResponseWithUnmappedIdAndScoreTwo() {
        val loaded = QuestionAdapter.parse(TestFixtures.threeQuestionJson(), CERTIFICATION_ID)
        val answers = mapOf(2 to UNKNOWN_KEY, 42 to "C", 4 to UNKNOWN_KEY)
        val json = toJson(ready(RequestMapper.map(TestFixtures.completeState(loaded, answers))))
        val results = json["assessment_results"]!!.jsonObject["results"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf(2, 42, 4), results.map { it["problem_id"]!!.jsonPrimitive.int })
        val unmapped = results[1]
        assertEquals("새 예시 문항", unmapped["question"]!!.jsonPrimitive.content)
        assertEquals("2", unmapped["possible_score"].toString())
        assertTrue(unmapped["is_correct"]!!.jsonPrimitive.boolean)
        assertNull(unmapped["topic"])
        assertNull(unmapped["earned_score"])
    }

    @Test
    fun incompleteAssessmentBlocksMapping() {
        val questions = TestFixtures.publicQuestions()
        val partial = questions.drop(1).associate { it.id to it.correctAnswer }
        assertTrue(RequestMapper.map(TestFixtures.completeState(questions, partial)) is MappingResult.Incomplete)
        assertTrue(RequestMapper.map(TestFixtures.completeState(emptyList(), emptyMap())) is MappingResult.Incomplete)
        val noDays = TestFixtures.completeState(availability = com.swpp.licenseplanner.domain.defaultAvailability())
        assertTrue(RequestMapper.map(noDays) is MappingResult.Incomplete)
    }

    @Test
    fun responseParsingToleratesToolCallsCsvMetadataAndUnknownFields() {
        val body = """
            {"schedules":[{"schedule_id":"study-1","start_date":"2026/10/05/18/00","end_date":"2026/10/05/19/00","topic":"함수와 데이터 관리","extra":1}],
             "schedule_csv":"schedule_id,start_date,end_date,topic\n",
             "schedule_csv_file":"license_planner/output/study_plan_x.csv",
             "schedule_csv_url":"/api/v1/study-plans/study_plan_x.csv",
             "agent_summary":"요약","tool_calls":[{"name":"build_schedule","args":{}}],"future_field":{"a":[1,2]}}
        """.trimIndent()
        val response = ApiJson.decodeFromString(StudyPlanResponse.serializer(), body)
        assertEquals(1, response.schedules.size)
        assertEquals("함수와 데이터 관리", response.schedules[0].topic)
        assertEquals("요약", response.agentSummary)
    }

    @Test
    fun wireDateTimeRoundTrips() {
        val value = LocalDateTime.of(2026, 10, 5, 18, 30)
        assertEquals("2026/10/05/18/30", WireDateTime.format(value))
        assertEquals(value, WireDateTime.parseOrNull("2026/10/05/18/30"))
        assertNull(WireDateTime.parseOrNull("2026-10-05T18:30"))
        assertNull(WireDateTime.parseOrNull("2026/02/30/10/00"))
    }

    /**
     * Exports the actual serialized request built from the public-response fixture, with
     * two UNKNOWN answers, for frontend/contract_checks/verify_request.py (task 4.3).
     */
    @Test
    fun exportPublicFixtureRequestForContractCheck() {
        val questions = TestFixtures.publicQuestions()
        val answers = questions.associate { it.id to it.correctAnswer } +
            mapOf(questions[1].id to UNKNOWN_KEY, questions[3].id to UNKNOWN_KEY) +
            (questions[2].id to questions[2].answerChoices.keys.first { it != questions[2].correctAnswer })
        val request = ready(RequestMapper.map(TestFixtures.completeState(questions, answers)))
        val text = ApiJson.encodeToString(StudyPlanRequest.serializer(), request)
        val dir = System.getProperty("licensePlanner.contractCheckDir") ?: "build/contract-check"
        File(dir).apply { mkdirs() }.resolve("study-plan-request.json").writeText(text + "\n")
        assertEquals(2, toJson(request)["assessment_results"]!!.jsonObject["results"]!!.jsonArray
            .count { it.jsonObject["user_answer"]!!.jsonPrimitive.content == UNKNOWN_KEY })
    }
}
