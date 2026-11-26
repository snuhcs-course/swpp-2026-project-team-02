package com.swpp.licenseplanner.domain

import com.swpp.licenseplanner.data.ApiJson
import com.swpp.licenseplanner.data.model.ScheduleRow
import java.io.File
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ReassessmentParityTest {
    @Test
    fun sharedReassessmentCases() {
        val fixture = File(requireNotNull(System.getProperty("user.dir")))
            .resolve("../../backend/tests/schedule_adjuster_tests/fixtures/reassessment_parity.json")
        val cases = ApiJson.parseToJsonElement(fixture.readText()).jsonObject.getValue("cases").jsonArray
        assertEquals(6, cases.size)
        for (element in cases) {
            val case = element.jsonObject
            val name = case.getValue("name").jsonPrimitive.content
            fun time(key: String) = requireNotNull(WireDateTime.parseOrNull(case.getValue(key).jsonPrimitive.content))
            val rows = ApiJson.decodeFromJsonElement<List<ScheduleRow>>(case.getValue("rows"))
            val keep = case.getValue("keep_schedule_ids").jsonArray.map { it.jsonPrimitive.content }.toSet()
            val answers = case.getValue("results").jsonArray.map {
                val item = it.jsonObject
                TopicAnswer(item.getValue("topic").jsonPrimitive.content, item.getValue("possible_score").jsonPrimitive.double,
                    item.getValue("is_correct").jsonPrimitive.boolean)
            }
            val firstNew = case.getValue("first_new_study_id").jsonPrimitive.content
            val result = Reassessment.resplit(rows, keep, answers, time("replan_from"), time("now"), firstNew)
            val code = case["expected_code"]?.jsonPrimitive?.content
            if (code != null) {
                assertEquals(name, code, (result as ResplitResult.Failed).failure.code)
            } else {
                val expected = ApiJson.decodeFromJsonElement<List<ScheduleRow>>(case.getValue("expected_rows"))
                val revised = (result as ResplitResult.Revised).rows
                assertEquals(name, expected, revised)
                assertTrue(name, Reassessment.isValidRevision(rows, keep, time("replan_from"), firstNew, revised))
                assertEquals(name, result, Reassessment.resplit(rows, keep, answers, time("replan_from"), time("now"), firstNew))
            }
        }
    }
}
