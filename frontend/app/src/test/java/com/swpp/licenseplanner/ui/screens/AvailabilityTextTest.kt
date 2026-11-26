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

    @Test fun adjustmentLabelsResolveDurationAndOvernightWindows() {
        val friday = LocalDate.of(2026, 11, 20)
        val context = StudyAvailability(mapOf(
            Weekday.MONDAY to listOf(window(18, 0, 120)),
            Weekday.FRIDAY to listOf(window(23, 30, 60)),
        ))
        assertEquals("공부 가능 18:00–20:00", dateAvailabilityLabel(context, friday.minusDays(4)))
        assertEquals("공부 가능 23:30–다음 날 00:30", dateAvailabilityLabel(context, friday))
        assertEquals("공부 가능 시간 없음", dateAvailabilityLabel(context, friday.plusDays(1)))
    }

    @Test fun adjustmentLabelsFollowOverridesAndShowOriginalWeeks() {
        val monday = LocalDate.of(2026, 11, 16)
        val context = StudyAvailability(
            mapOf(Weekday.WEDNESDAY to listOf(window(19, 0, 60))),
            mapOf(monday to mapOf(Weekday.WEDNESDAY to listOf(window(7, 0, 30)))),
        )
        assertEquals("공부 가능 07:00–07:30", dateAvailabilityLabel(context, monday.plusDays(2)))
        assertEquals(listOf("공부 가능 시간: 수 19:00–20:00", "11월 16일 주 · 시간 바꿈: 수 07:00–07:30"), originalAvailabilityLabels(context))
        assertEquals("공부 가능 시간 정보가 없어요.", dateAvailabilityLabel(null, monday))
        assertEquals(listOf("공부 가능 시간 정보가 없어요."), originalAvailabilityLabels(null))
    }

    @Test fun endLabelMarksTheNextDay() {
        assertEquals("– 20:00", endLabel(window(19, 0, 60)))
        assertEquals("– 다음 날 00:30", endLabel(window(23, 0, 90)))
        assertEquals("– 다음 날 00:00", endLabel(window(23, 0, 60)))
        assertEquals("– 23:59", endLabel(window(0, 0, 1439)))
        assertEquals("– 다음 날 00:00", endLabel(window(0, 1, 1439)))
    }

    @Test fun minutesBoxNamesTheWeekdayWindowAndTimes() {
        assertEquals("월요일 시간대 2, 19:00–21:00, 120분, 수정", windowEditDescription(Weekday.MONDAY, 1, window(19, 0, 120)))
        assertEquals("일요일 시간대 1, 23:30–다음 날 00:30, 60분, 수정", windowEditDescription(Weekday.SUNDAY, 0, window(23, 30, 60)))
    }

    @Test fun startAndEndTimesResolveSameDayAndOvernightDurations() {
        assertEquals(120, studyWindowMinutes(LocalTime.of(18, 0), LocalTime.of(20, 0)))
        assertEquals(90, studyWindowMinutes(LocalTime.of(23, 0), LocalTime.of(0, 30)))
        assertEquals(60, studyWindowMinutes(LocalTime.of(23, 0), LocalTime.MIDNIGHT))
        assertEquals(1, studyWindowMinutes(LocalTime.of(23, 59), LocalTime.MIDNIGHT))
        assertEquals(1439, studyWindowMinutes(LocalTime.of(0, 1), LocalTime.MIDNIGHT))
        assertNull(studyWindowMinutes(LocalTime.of(18, 0), LocalTime.of(18, 0)))
    }

    @Test fun typedTimesNeedAnHourAndMinuteInTheTwentyFourHourRange() {
        assertEquals(LocalTime.of(19, 30), typedTime("19", "30"))
        assertEquals(LocalTime.of(7, 5), typedTime("7", "05"))
        assertEquals(LocalTime.MIDNIGHT, typedTime("00", "00"))
        assertEquals(LocalTime.of(23, 59), typedTime("23", "59"))
        assertNull(typedTime("", "30"))
        assertNull(typedTime("19", ""))
        assertNull(typedTime("24", "00"))
        assertNull(typedTime("25", "00"))
        assertNull(typedTime("19", "60"))
    }

    @Test fun summariesCountEveryWindowAndWeekKinds() {
        val base = mapOf(
            Weekday.MONDAY to listOf(window(8, 0, 60), window(23, 0, 90)),
            Weekday.WEDNESDAY to listOf(window(19, 0, 60)),
            Weekday.SUNDAY to listOf(window(23, 30, 60)),
        )
        assertEquals(WeekTotals(days = 3, minutes = 270), weekTotals(base))

        val empty = LocalDate.of(2026, 11, 30)
        val replaced = LocalDate.of(2026, 12, 7)
        val twoWindows = mapOf(Weekday.TUESDAY to listOf(window(7, 0, 30), window(20, 0, 60)))
        assertEquals("없음", overridesLabel(emptyMap()))
        assertEquals("11월 30일 주 · 공부 안 함", overridesLabel(mapOf(empty to emptyMap())))
        assertEquals("12월 7일 주 · 시간 바꿈", overridesLabel(mapOf(replaced to twoWindows)))
        assertEquals("2개 주", overridesLabel(mapOf(empty to emptyMap(), replaced to twoWindows)))
    }

    @Test fun conflictNamesTheDaysAndOverlappingTime() {
        val spill = StudyAvailability(mapOf(Weekday.MONDAY to listOf(window(23, 0, 90)), Weekday.TUESDAY to listOf(window(0, 15, 60))))
        val text = conflictText(spill)!!
        assertEquals("월요일과 화요일 공부 시간이 겹쳐요.", text.title)
        assertEquals("화요일 00:15–00:30이 겹쳐요. 시작 시간이나 종료 시간을 바꿔 주세요.", text.body)

        // A replacement week colliding with the previous week's Sunday spill names that week.
        val monday = LocalDate.of(2026, 11, 30)
        val week = StudyAvailability(
            mapOf(Weekday.SUNDAY to listOf(window(23, 30, 60))),
            mapOf(monday to mapOf(Weekday.MONDAY to listOf(window(0, 0, 45)))),
        )
        assertEquals(ConflictText("일요일과 월요일 공부 시간이 겹쳐요.", "11월 30일 주 월요일 00:00–00:30이 겹쳐요. 시작 시간이나 종료 시간을 바꿔 주세요."), conflictText(week))

        val sameDay = StudyAvailability(mapOf(Weekday.FRIDAY to listOf(window(23, 0, 120), window(23, 30, 30))))
        assertEquals("금요일 공부 시간이 겹쳐요.", conflictText(sameDay)!!.title)
        assertEquals("금요일 23:30–다음 날 00:00이 겹쳐요. 시작 시간이나 종료 시간을 바꿔 주세요.", conflictText(sameDay)!!.body)

        val adjacent = StudyAvailability(mapOf(Weekday.MONDAY to listOf(window(23, 0, 90)), Weekday.TUESDAY to listOf(window(0, 30, 60))))
        assertNull(conflictText(adjacent))
    }

    @Test fun incomingTextShowsBothEndpoints() {
        val monday = LocalDate.of(2026, 11, 30)
        val interval = StudyInterval(monday.atStartOfDay(), monday.atTime(0, 30))
        assertEquals("11월 30일(월) 00:00–00:30 공부 시간이 지난주 설정에서 이어져요.", incomingText(interval))
    }

    @Test fun overlappingWeekdaysAreNamedInOrderWithOneParticle() {
        assertEquals("수요일과 겹쳐요", weekdayOverlapTitle(listOf(Weekday.WEDNESDAY)))
        assertEquals("월요일, 금요일과 겹쳐요", weekdayOverlapTitle(listOf(Weekday.MONDAY, Weekday.FRIDAY)))
    }

    @Test fun inputEditNoteUsesTheParticleForTheOrdinal() {
        assertEquals("계획 1은 그대로 두고 새 계획을 만들어요.", inputEditNote(1))
        assertEquals("계획 2는 그대로 두고 새 계획을 만들어요.", inputEditNote(2))
        assertEquals("계획 10은 그대로 두고 새 계획을 만들어요.", inputEditNote(10))
        assertEquals("계획 19는 그대로 두고 새 계획을 만들어요.", inputEditNote(19))
    }
}
