package com.swpp.licenseplanner.domain

import com.swpp.licenseplanner.data.model.ScheduleRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.util.TimeZone

class CalendarExportTest {
    private val longTopic = "엑셀 함수(VLOOKUP·INDEX/MATCH) 복습: \"조건부 서식\", 차트 & 피벗 테이블 — 기출 유형 정리!"

    private val rows = listOf(
        ScheduleRow("study-2", "2026/11/11/18/00", "2026/11/11/19/20", longTopic),
        ScheduleRow("external-1", "2026/11/04/12/00", "2026/11/04/13/00", "점심 약속"),
        ScheduleRow("study-1", "2026/11/04/18/00", "2026/11/04/18/25", "셀 참조"),
        ScheduleRow("review-1", "2026/11/05/18/00", "2026/11/05/19/00", "알 수 없는 행"),
        // Earlier than today and in an earlier week; still exported.
        ScheduleRow("study-3", "2026/10/19/18/00", "2026/10/19/19/00", "함수"),
    )

    private fun events(rows: List<ScheduleRow>, isDemo: Boolean = false) = CalendarExport.events(rows, planOrdinal = 2, isDemo = isDemo)

    private fun millis(text: String) = Instant.parse(text).toEpochMilli()

    @Test
    fun onlyStudyRowsAcrossAllWeeksBecomeEventsInTimeOrder() {
        val result = checkNotNull(events(rows))
        assertEquals(listOf("함수", "셀 참조", longTopic), result.map { it.title })
        assertEquals(3, CalendarExport.studyRows(rows).size)
    }

    @Test
    fun durationsAndTopicsAreKeptExactly() {
        val result = checkNotNull(events(rows))
        val minutes = result.map { Duration.ofMillis(it.endMillis - it.startMillis).toMinutes() }
        assertEquals(listOf(60L, 25L, 80L), minutes)
        assertEquals(longTopic, result.last().title)
        assertTrue(result.all { it.timeZone == "Asia/Seoul" })
    }

    @Test
    fun koreanTimeIsUsedWhateverTheDeviceZone() {
        val original = TimeZone.getDefault()
        try {
            for (zone in listOf("America/Los_Angeles", "UTC", "Europe/Berlin", "Asia/Seoul")) {
                TimeZone.setDefault(TimeZone.getTimeZone(zone))
                val event = checkNotNull(events(listOf(ScheduleRow("study-1", "2026/11/04/18/00", "2026/11/04/19/30", "셀 참조")))).single()
                assertEquals(zone, millis("2026-11-04T09:00:00Z"), event.startMillis)
                assertEquals(zone, millis("2026-11-04T10:30:00Z"), event.endMillis)
            }
        } finally {
            TimeZone.setDefault(original)
        }
    }

    @Test
    fun anyInvalidStudyRowPreventsTheWholeExport() {
        val valid = ScheduleRow("study-1", "2026/11/04/18/00", "2026/11/04/19/00", "셀 참조")
        val invalid = listOf(
            ScheduleRow("study-2", "2026/11/04", "2026/11/04/19/00", "날짜만"),
            ScheduleRow("study-2", "2026/02/30/18/00", "2026/02/30/19/00", "없는 날"),
            ScheduleRow("study-2", "2026/11/04/19/00", "2026/11/04/19/00", "길이 0"),
            ScheduleRow("study-2", "2026/11/04/19/00", "2026/11/04/18/00", "거꾸로"),
            valid.copy(topic = "같은 ID"),
        )
        for (row in invalid) assertNull(row.topic, events(listOf(valid, row)))
    }

    @Test
    fun invalidNonStudyRowsDoNotBlockExport() {
        val result = events(listOf(
            ScheduleRow("study-1", "2026/11/04/18/00", "2026/11/04/19/00", "셀 참조"),
            ScheduleRow("external-1", "bad", "bad", ""),
            ScheduleRow("external-1", "2026/11/04/18/00", "2026/11/04/19/00", ""),
        ))
        assertEquals(1, checkNotNull(result).size)
    }

    @Test
    fun descriptionNamesThePlanAndMarksOnlyDemoPlans() {
        val live = checkNotNull(events(rows)).first().description
        val demo = checkNotNull(events(rows, isDemo = true)).first().description
        assertTrue(live.contains("계획 2"))
        assertTrue(live.contains("필기"))
        assertTrue(live.contains("바뀌지 않아요"))
        assertFalse(live.contains("로컬 시연 계산"))
        assertTrue(demo.contains("로컬 시연 계산"))
    }

    @Test
    fun noStudyRowsGiveNoEvents() {
        assertEquals(emptyList<CalendarEvent>(), events(listOf(ScheduleRow("external-1", "2026/11/04/18/00", "2026/11/04/19/00", ""))))
    }
}
