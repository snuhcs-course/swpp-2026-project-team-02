package com.swpp.licenseplanner.data

import com.swpp.licenseplanner.domain.StudyAvailability
import com.swpp.licenseplanner.domain.Weekday
import com.swpp.licenseplanner.domain.WindowSpec
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

/** Transport against a local mock server; not the shared production profile. */
class AvailabilityProfileRepositoryTest {
    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer().apply { start() } }
    @After fun tearDown() { server.shutdown() }

    private fun repository() = LiveAvailabilityProfileRepository(server.url("/api/v1/").toString())

    private val context = StudyAvailability(
        base = mapOf(Weekday.MONDAY to listOf(WindowSpec(LocalTime.of(8, 0), 60), WindowSpec(LocalTime.of(23, 0), 90))),
        overrides = mapOf(LocalDate.of(2026, 11, 23) to emptyMap()),
    )
    private val echo = """{"weekly_availability":{"monday":[{"start":"08:00","duration_minutes":60,"end":"09:00"},
        {"start":"23:00","duration_minutes":90,"end":"00:30"}],"tuesday":[],"wednesday":[],"thursday":[],"friday":[],
        "saturday":[],"sunday":[]},"availability_overrides":{"2026/11/23":{"monday":[],"tuesday":[],"wednesday":[],
        "thursday":[],"friday":[],"saturday":[],"sunday":[]}}}"""

    @Test
    fun loadReadsBothStoredWindowFormsWithoutWriting() {
        server.enqueue(MockResponse().setBody("""{"weekly_availability":{"monday":[{"start":"18:00","end":"20:00"}]},"availability_overrides":{}}"""))
        val loaded = runBlocking { repository().load() } as ProfileOutcome.Loaded
        assertEquals(listOf(WindowSpec(LocalTime.of(18, 0), 120)), loaded.availability.base[Weekday.MONDAY])
        val request = server.takeRequest()
        assertEquals("GET" to "/api/v1/availability", request.method to request.path)
    }

    @Test
    fun saveSendsTheCompleteContextAndChecksTheEcho() {
        server.enqueue(MockResponse().setBody(echo))
        assertEquals(ProfileOutcome.Loaded(context), runBlocking { repository().save(context) })
        val request = server.takeRequest()
        assertEquals("PUT", request.method)
        assertEquals(
            ApiJson.parseToJsonElement("""{"weekly_availability":{"monday":[{"start":"08:00","duration_minutes":60},
                {"start":"23:00","duration_minutes":90}]},"availability_overrides":{"2026/11/23":{}}}"""),
            ApiJson.parseToJsonElement(request.body.readUtf8()),
        )
        server.enqueue(MockResponse().setBody(echo.replace("\"2026/11/23\"", "\"2026/11/30\"")))
        assertTrue((runBlocking { repository().save(context) } as ProfileOutcome.Failure).error is ApiError.InvalidResponse)
    }

    @Test
    fun invalidOrFailedResponsesAreFailures() {
        val overlapping = """{"weekly_availability":{"monday":[{"start":"23:00","duration_minutes":90}],
            "tuesday":[{"start":"00:15","duration_minutes":60}]},"availability_overrides":{}}"""
        server.enqueue(MockResponse().setBody(overlapping))
        assertTrue((runBlocking { repository().load() } as ProfileOutcome.Failure).error is ApiError.InvalidResponse)
        server.enqueue(MockResponse().setResponseCode(422).setBody("""{"error":{"code":"INVALID_INPUT","message":"overlap"}}"""))
        assertEquals(ProfileOutcome.Failure(ApiError.InvalidInput("overlap")), runBlocking { repository().save(context) })
        server.enqueue(MockResponse().setResponseCode(500))
        assertEquals(ProfileOutcome.Failure(ApiError.ServerFailure(500)), runBlocking { repository().load() })
    }

    @Test fun splitAndOvernightWindowsRoundTripWithChangedAndEmptyWeeks() {
        val profile = StudyAvailability(
            base = mapOf(
                Weekday.MONDAY to listOf(WindowSpec(LocalTime.of(8, 0), 60), WindowSpec(LocalTime.of(19, 0), 120)),
                Weekday.SUNDAY to listOf(WindowSpec(LocalTime.of(23, 30), 60)),
            ),
            overrides = mapOf(
                LocalDate.of(2026, 11, 23) to mapOf(Weekday.MONDAY to listOf(WindowSpec(LocalTime.of(9, 0), 60), WindowSpec(LocalTime.of(19, 0), 120))),
                LocalDate.of(2026, 11, 30) to emptyMap(),
            ),
        )
        val response = """{"weekly_availability":{"monday":[{"start":"08:00","duration_minutes":60},{"start":"19:00","duration_minutes":120}],
            "sunday":[{"start":"23:30","duration_minutes":60}]},"availability_overrides":{
            "2026/11/23":{"monday":[{"start":"09:00","duration_minutes":60},{"start":"19:00","duration_minutes":120}]},"2026/11/30":{}}}"""
        server.enqueue(MockResponse().setBody(response))
        assertEquals(ProfileOutcome.Loaded(profile), runBlocking { repository().load() })
        assertEquals("GET", server.takeRequest().method)
        server.enqueue(MockResponse().setBody(response))
        assertEquals(ProfileOutcome.Loaded(profile), runBlocking { repository().save(profile) })
        val saved = server.takeRequest()
        assertEquals("PUT", saved.method)
        assertEquals(ApiJson.parseToJsonElement(response), ApiJson.parseToJsonElement(saved.body.readUtf8()))
    }

}
