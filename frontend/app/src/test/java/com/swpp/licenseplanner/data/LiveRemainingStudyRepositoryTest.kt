package com.swpp.licenseplanner.data

import com.swpp.licenseplanner.data.model.*
import com.swpp.licenseplanner.domain.RemainingStudy
import com.swpp.licenseplanner.domain.RemainingStudyFixtures
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class LiveRemainingStudyRepositoryTest {
    private lateinit var server: MockWebServer
    private val fixture = RemainingStudyFixtures
    private val source = fixture.plan(false).copy(keptConflicts = mapOf("study-50" to setOf("a".repeat(64))))
    private val input = RemainingStudyInput(source, RemainingStudy.calculate(source), fixture.now.plusMinutes(1))
    private val placed = RemainingStudy.place(source, input.work, input.replanFrom, fixture.now)!!
    private val valid = RemainingStudyResponse(placed, remainingCsv(placed), "replanned_remaining", source.scheduleCsvFile!!,
        AvailabilitySnapshot(source.original.weeklyAvailability, source.original.availabilityOverrides))

    @Before fun start() { server = MockWebServer().apply { start() } }
    @After fun stop() { server.shutdown() }
    private fun call() = runBlocking { LiveRemainingStudyRepository(server.url("/api/v1").toString()).replan(input) }
    private fun enqueue(response: RemainingStudyResponse = valid) = server.enqueue(MockResponse().setBody(
        ApiJson.encodeToString(RemainingStudyResponse.serializer(), response)))

    @Test fun sendsOnlyCapturedBatchFieldsAndAcceptsTheCompletePartition() {
        enqueue()
        val outcome = call() as AdjustmentOutcome.Success
        assertEquals(placed, outcome.response.schedules)
        val request = server.takeRequest()
        assertEquals("/api/v1/schedules/adjust", request.path)
        val sent = ApiJson.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals(setOf("mode", "schedule_csv_file", "schedules", "weekly_availability", "availability_overrides",
            "preparation_start", "exam_date", "replan_from", "remaining_sessions"), sent.keys)
        val dto = ApiJson.decodeFromJsonElement(RemainingStudyRequest.serializer(), sent)
        assertEquals("remaining", dto.mode)
        assertEquals(input.work.instructions, dto.remainingSessions)
        assertEquals(listOf("study-51", "study-52", "study-4"), dto.remainingSessions.map { it.replacementScheduleId })
        assertEquals(source.current.schedules, dto.schedules)
        assertEquals(source.original.preparationStart, dto.preparationStart)
    }

    @Test fun malformedSuccessIdentityPartitionTopicMinutesWindowAndCsvRequireReload() {
        val index = placed.indexOfFirst { it.scheduleId == "study-51" }
        fun alter(row: ScheduleRow) = valid.copy(schedules = placed.toMutableList().also { it[index] = row })
        val row = placed[index]
        val invalid = listOf(valid.copy(action = "rescheduled_study"), valid.copy(scheduleCsvFile = "plans/b.csv"),
            valid.copy(schedules = placed.drop(1)), valid.copy(scheduleCsv = "broken"),
            valid.copy(schedules = placed.map { if (it.scheduleId == "study-1") it.copy(topic = "changed") else it }),
            alter(row.copy(topic = "changed")), alter(row.copy(endDate = row.startDate)),
            alter(row.copy(scheduleId = "study-50")), alter(row.copy(scheduleId = "study-2")),
            alter(row.copy(startDate = "2026/11/16/01/00", endDate = "2026/11/16/01/40")),
            alter(row.copy(startDate = placed.last().startDate, endDate = placed.last().endDate)))
        for (response in invalid) {
            enqueue(response)
            assertTrue(response.toString(), (call() as AdjustmentOutcome.Failure).error is ApiError.InvalidResponse)
        }
        server.enqueue(MockResponse().setBody("{}"))
        assertTrue((call() as AdjustmentOutcome.Failure).error is ApiError.InvalidResponse)
        assertEquals(invalid.size + 1, server.requestCount)
    }

    @Test fun unsupportedModeIsADefiniteRejectionWithoutFallback() {
        server.enqueue(MockResponse().setResponseCode(422).setBody("""{"error":{"code":"INVALID_INPUT","message":"mode must be automatic or manual"}}"""))
        assertTrue((call() as AdjustmentOutcome.Failure).error is ApiError.InvalidInput)
        assertEquals(1, server.requestCount)
    }

    @Test fun redirectsAndRetryAfterNeverReplayTheMutation() {
        for (status in listOf(301, 302, 307, 308, 408, 503)) {
            server.enqueue(MockResponse().setResponseCode(status).setHeader("Location", server.url("/api/v1/schedules/adjust"))
                .setHeader("Retry-After", "0"))
            val before = server.requestCount
            assertTrue(call() is AdjustmentOutcome.Failure)
            assertEquals(before + 1, server.requestCount)
            assertNotNull(server.takeRequest(1, TimeUnit.SECONDS))
        }
    }

    @Test fun existingProtectedOverlapsAreAllowedAndCsvQuotedTopicsRoundTrip() {
        val protected = source.current.schedules.first().copy(scheduleId = "external-2")
        val captured = source.copy(current = source.current.copy(schedules = source.current.schedules + protected))
        val request = input.copy(source = captured, work = RemainingStudy.calculate(captured))
        val rows = RemainingStudy.place(captured, request.work, request.replanFrom, fixture.now)!!
        enqueue(valid.copy(schedules = rows, scheduleCsv = remainingCsv(rows)))
        val outcome = runBlocking { LiveRemainingStudyRepository(server.url("/api/v1").toString()).replan(request) }
        assertTrue(outcome is AdjustmentOutcome.Success)
        assertEquals(1, server.requestCount)
    }
}
