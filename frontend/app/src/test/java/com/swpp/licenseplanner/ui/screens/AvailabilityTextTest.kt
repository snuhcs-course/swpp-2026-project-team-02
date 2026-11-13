package com.swpp.licenseplanner.ui.screens

import com.swpp.licenseplanner.domain.StudyAvailability
import com.swpp.licenseplanner.domain.StudyInterval
import com.swpp.licenseplanner.domain.Weekday
import com.swpp.licenseplanner.domain.WindowSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class AvailabilityTextTest {
    private fun window(hour: Int, minute: Int, duration: Int) = WindowSpec(LocalTime.of(hour, minute), duration)

    @Test fun endLabelMarksTheNextDay() {
        assertEquals("– 20:00", endLabel(window(19, 0, 60)))
        assertEquals("– 다음 날 00:30", endLabel(window(23, 0, 90)))
        assertEquals("– 다음 날 00:00", endLabel(window(23, 0, 60)))
        assertEquals("– 23:59", endLabel(window(0, 0, 1439)))
        assertEquals("– 다음 날 00:00", endLabel(window(0, 1, 1439)))
    }

    @Test fun durationEntryAcceptsOnlyTheSupportedRange() {
        assertEquals(1, parseDurationMinutes("1"))
        assertEquals(90, parseDurationMinutes("90"))
        assertEquals(1439, parseDurationMinutes("1439"))
        for (text in listOf("", "0", "1440", "9999", "-5", "1.5")) assertNull(text, parseDurationMinutes(text))
    }

    @Test fun summariesCountUnshownWindowsAndWeekKinds() {
        val base = mapOf(
            Weekday.MONDAY to listOf(window(8, 0, 60), window(23, 0, 90)),
            Weekday.WEDNESDAY to listOf(window(19, 0, 60)),
            Weekday.SUNDAY to listOf(window(23, 30, 60)),
        )
        assertEquals(WeekTotals(days = 3, minutes = 270), weekTotals(base))
        assertEquals("주 3일 · 4시간 30분", baseLabel(base))
        assertEquals("없음", baseLabel(emptyMap()))

        val empty = LocalDate.of(2026, 11, 30)
        val replaced = LocalDate.of(2026, 12, 7)
        val twoWindows = mapOf(Weekday.TUESDAY to listOf(window(7, 0, 30), window(20, 0, 60)))
        assertEquals("없음", overridesLabel(emptyMap()))
        assertEquals("11월 30일 주 · 공부 안 함", overridesLabel(mapOf(empty to emptyMap())))
        assertEquals("12월 7일 주 · 시간 바꿈", overridesLabel(mapOf(replaced to twoWindows)))
        assertEquals("2개 주", overridesLabel(mapOf(empty to emptyMap(), replaced to twoWindows)))
        assertEquals(2, retainedWindowCount(StudyAvailability(base, mapOf(empty to emptyMap(), replaced to twoWindows))))
    }

    @Test fun conflictNamesTheDaysAndOverlappingTime() {
        val spill = StudyAvailability(mapOf(Weekday.MONDAY to listOf(window(23, 0, 90)), Weekday.TUESDAY to listOf(window(0, 15, 60))))
        val text = conflictText(spill)!!
        assertEquals("월요일과 화요일 공부 시간이 겹쳐요.", text.title)
        assertEquals("화요일 00:15–00:30이 겹쳐요. 시작 시간이나 공부할 분을 바꿔 주세요.", text.body)

        // A replacement week colliding with the previous week's Sunday spill names that week.
        val monday = LocalDate.of(2026, 11, 30)
        val week = StudyAvailability(
            mapOf(Weekday.SUNDAY to listOf(window(23, 30, 60))),
            mapOf(monday to mapOf(Weekday.MONDAY to listOf(window(0, 0, 45)))),
        )
        assertEquals(ConflictText("일요일과 월요일 공부 시간이 겹쳐요.", "11월 30일 주 월요일 00:00–00:30이 겹쳐요. 시작 시간이나 공부할 분을 바꿔 주세요."), conflictText(week))

        val sameDay = StudyAvailability(mapOf(Weekday.FRIDAY to listOf(window(23, 0, 120), window(23, 30, 30))))
        assertEquals("금요일 공부 시간이 겹쳐요.", conflictText(sameDay)!!.title)
        assertEquals("금요일 23:30–다음 날 00:00이 겹쳐요. 시작 시간이나 공부할 분을 바꿔 주세요.", conflictText(sameDay)!!.body)

        val adjacent = StudyAvailability(mapOf(Weekday.MONDAY to listOf(window(23, 0, 90)), Weekday.TUESDAY to listOf(window(0, 30, 60))))
        assertNull(conflictText(adjacent))
    }

    @Test fun incomingTextShowsBothEndpoints() {
        val interval = StudyInterval(LocalDate.of(2026, 11, 29).atTime(23, 30), LocalDate.of(2026, 11, 30).atTime(0, 30))
        assertEquals("11월 29일(일) 23:30에 시작한 공부 시간이 11월 30일(월) 00:30까지 이어져요.", incomingText(interval))
    }
}
