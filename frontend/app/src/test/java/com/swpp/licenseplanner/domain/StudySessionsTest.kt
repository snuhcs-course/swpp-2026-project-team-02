package com.swpp.licenseplanner.domain

import java.io.File
import java.time.LocalDateTime
import org.junit.Assert.*
import org.junit.Test

class StudySessionsTest {
    private fun at(value: String) = LocalDateTime.parse(value)
    private fun session(id: String, start: String, minutes: Long) =
        PlanSession(id, at(start), at(start).plusMinutes(minutes), "주제")

    @Test fun backToBackStudyFormsOneSessionAndExternalRowsBreakIt() {
        val rows = listOf(session("study-1", "2026-11-17T18:00", 20), session("study-2", "2026-11-17T18:20", 15),
            session("external-1", "2026-11-17T18:35", 10), session("study-3", "2026-11-17T18:45", 30))
        assertEquals(listOf(StudyInterval(at("2026-11-17T18:00"), at("2026-11-17T18:35")),
            StudyInterval(at("2026-11-17T18:45"), at("2026-11-17T19:15"))), StudySessions.runs(rows))
    }

    @Test fun placedRowMustSitInALongEnoughSession() {
        val placed = session("study-9", "2026-11-17T18:00", 20)
        assertFalse(StudySessions.keepsSessions(listOf(placed), setOf("study-9"), emptyList(), null))
        val joined = listOf(placed, session("study-1", "2026-11-17T18:20", 10))
        assertTrue(StudySessions.keepsSessions(joined, setOf("study-9"), emptyList(), null))
    }

    @Test fun onlyLaterSessionsCutFromALongEnoughOneAreProtected() {
        val original = listOf(session("study-1", "2026-11-17T18:00", 20), session("study-2", "2026-11-17T18:20", 15))
        val cut = listOf(original[1])
        assertFalse(StudySessions.keepsSessions(cut, emptySet(), original, at("2026-11-17T18:00")))
        assertTrue(StudySessions.keepsSessions(cut, emptySet(), original, at("2026-11-17T18:21")))
        assertTrue(StudySessions.keepsSessions(cut, emptySet(), original, null))
        // A session that was already short does not block.
        assertTrue(StudySessions.keepsSessions(cut, emptySet(), cut, at("2026-11-17T18:00")))
    }

    @Test fun minimumMatchesTheServerPlannerAndAdjuster() {
        val backend = File(requireNotNull(System.getProperty("user.dir"))).resolve("../../backend")
        val constant = Regex("""^MIN_STUDY_SESSION_MINUTES = (\d+)""", RegexOption.MULTILINE)
        for (path in listOf("license_planner/scheduler.py", "schedule_adjuster/study_sessions.py")) {
            val value = constant.find(backend.resolve(path).readText())?.groupValues?.get(1)
            assertEquals(path, StudySessions.MIN_MINUTES.toString(), value)
        }
    }
}
