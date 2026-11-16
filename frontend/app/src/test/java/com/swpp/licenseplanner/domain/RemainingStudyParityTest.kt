package com.swpp.licenseplanner.domain

import com.swpp.licenseplanner.data.ApiJson
import com.swpp.licenseplanner.data.model.AssessmentResults
import com.swpp.licenseplanner.data.model.RemainingSession
import com.swpp.licenseplanner.data.model.ScheduleRow
import com.swpp.licenseplanner.data.model.StudyPlanRequest
import com.swpp.licenseplanner.data.model.StudyPlanResponse
import com.swpp.licenseplanner.data.model.TimeWindow
import java.io.File
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class RemainingStudyParityTest {
    @Test
    fun sharedRemainingStudyCases() {
        val fixture = File(requireNotNull(System.getProperty("user.dir")))
            .resolve("../../backend/tests/schedule_adjuster_tests/fixtures/remaining_study_parity.json")
        val cases = ApiJson.parseToJsonElement(fixture.readText()).jsonObject.getValue("cases").jsonArray
        assertEquals(8, cases.size)
        for (element in cases) {
            val case = element.jsonObject
            val name = case.getValue("name").jsonPrimitive.content
            val rows = ApiJson.decodeFromJsonElement<List<ScheduleRow>>(case.getValue("rows"))
            val weekly = ApiJson.decodeFromJsonElement<Map<String, List<TimeWindow>>>(case.getValue("weekly_availability"))
            val overrides = ApiJson.decodeFromJsonElement<Map<String, Map<String, List<TimeWindow>>>>(
                case.getValue("availability_overrides"),
            )
            val request = StudyPlanRequest(
                CERTIFICATION_ID,
                case.getValue("preparation_start").jsonPrimitive.content,
                case.getValue("exam_date").jsonPrimitive.content,
                "beginner",
                AssessmentResults(CERTIFICATION_ID, emptyList()),
                weekly.count { it.value.isNotEmpty() },
                weekly,
                availabilityOverrides = overrides,
            )
            val reports = case.getValue("check_ins").jsonObject.mapValues { (_, value) ->
                val report = value.jsonObject
                StudyCheckIn(
                    StudyCheckInStatus.valueOf(report.getValue("status").jsonPrimitive.content),
                    report.getValue("actual_minutes").jsonPrimitive.int,
                )
            }
            val plan = SessionPlan.register(1, request, StudyPlanResponse(rows), true).copy(checkIns = reports)
            val work = RemainingStudy.calculate(plan)
            val instructions = ApiJson.decodeFromJsonElement<List<RemainingSession>>(case.getValue("remaining_sessions"))
            assertEquals(name, instructions.associateBy { it.scheduleId }, work.instructions.associateBy { it.scheduleId })
            val placed = RemainingStudy.place(
                plan,
                work,
                requireNotNull(WireDateTime.parseOrNull(case.getValue("replan_from").jsonPrimitive.content)),
                requireNotNull(WireDateTime.parseOrNull(case.getValue("now").jsonPrimitive.content)),
            )
            if ("expected_failure" in case) {
                assertNull(name, placed)
            } else {
                val expected = ApiJson.decodeFromJsonElement<List<ScheduleRow>>(case.getValue("expected_rows"))
                assertNotNull(name, placed)
                assertEquals(name, expected.associateBy { it.scheduleId }, placed!!.associateBy { it.scheduleId })
            }
            assertEquals(name, rows, plan.current.schedules)
            assertEquals(name, reports, plan.checkIns)
        }
    }
    @Test
    fun sharedConfirmedDestinationCases() {
        val fixture = File(requireNotNull(System.getProperty("user.dir")))
            .resolve("../../backend/tests/schedule_adjuster_tests/fixtures/remaining_study_parity.json")
        val cases = ApiJson.parseToJsonElement(fixture.readText()).jsonObject.getValue("destination_cases").jsonArray
        assertEquals(10, cases.size)
        for (element in cases) {
            val case = element.jsonObject
            val name = case.getValue("name").jsonPrimitive.content
            val rows = ApiJson.decodeFromJsonElement<List<ScheduleRow>>(case.getValue("rows"))
            fun time(key: String) = requireNotNull(WireDateTime.parseOrNull(case.getValue(key).jsonPrimitive.content))
            val weekly = ApiJson.decodeFromJsonElement<Map<String, List<TimeWindow>>>(case.getValue("weekly_availability"))
            val overrides = ApiJson.decodeFromJsonElement<Map<String, Map<String, List<TimeWindow>>>>(case.getValue("availability_overrides"))
            val request = StudyPlanRequest(CERTIFICATION_ID, case.getValue("preparation_start").jsonPrimitive.content,
                case.getValue("exam_date").jsonPrimitive.content, "beginner", AssessmentResults(CERTIFICATION_ID, emptyList()),
                weekly.count { it.value.isNotEmpty() }, weekly, availabilityOverrides = overrides)
            val items = case.getValue("remaining_sessions").jsonArray.map { it.jsonObject }
            val instructions = items.map { RemainingSession(it.getValue("schedule_id").jsonPrimitive.content,
                it.getValue("remaining_minutes").jsonPrimitive.int, it.getValue("replacement_schedule_id").jsonPrimitive.content) }
            val destinations = items.associate { it.getValue("schedule_id").jsonPrimitive.content to
                requireNotNull(WireDateTime.parseOrNull(it.getValue("new_start_date").jsonPrimitive.content)) }
            val result = RemainingDestinations.validateDestinations(rows, instructions, destinations,
                requireNotNull(com.swpp.licenseplanner.data.AvailabilityWire.context(request)), time("preparation_start"),
                time("exam_date"), time("replan_from"), time("now"), case.getValue("confirm_outside_availability").jsonPrimitive.boolean)
            assertEquals(name, case["expected_code"]?.jsonPrimitive?.content, result.code)
            if (result.code == null) {
                val expected = ApiJson.decodeFromJsonElement<List<ScheduleRow>>(case.getValue("expected_rows"))
                assertEquals(name, expected.associateBy { it.scheduleId }, result.rows.associateBy { it.scheduleId })
            } else assertTrue(name, result.rows.isEmpty())
        }
    }

}
