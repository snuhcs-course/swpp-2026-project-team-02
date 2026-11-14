package com.swpp.licenseplanner.ui.screens

import com.swpp.licenseplanner.data.model.ScheduleRow
import com.swpp.licenseplanner.ui.MoveSave
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** Old and new intervals and local save wording of an accepted move. */
class MoveResultTextTest {
    private fun row(start: String, end: String) = ScheduleRow("study-1", start, end, "차트와 분석")

    @Test
    fun sameDayIntervalNamesYearDateWeekdayAndBothTimes() {
        assertEquals(
            "2026년 11월 18일(수) 19:00–19:30",
            moveIntervalText(row("2026/11/18/19/00", "2026/11/18/19/30")),
        )
    }

    @Test
    fun overnightIntervalAcrossAYearNamesBothFullDates() {
        assertEquals(
            "2026년 12월 31일(목) 23:30–2027년 1월 1일(금) 00:30",
            moveIntervalText(row("2026/12/31/23/30", "2027/01/01/00/30")),
        )
    }

    @Test
    fun unreadableRowKeepsItsWireText() {
        assertEquals("2026/11/18 – 2026/11/18/19/30", moveIntervalText(row("2026/11/18", "2026/11/18/19/30")))
    }

    @Test
    fun onlyASettledSaveSaysSavedOnThisDevice() {
        assertEquals("기기에 저장하는 중이에요", moveSaveText(MoveSave.Saving))
        assertEquals("이 기기에 저장했어요", moveSaveText(MoveSave.Saved))
        assertEquals("아직 이 기기에 저장하지 못했어요", moveSaveText(MoveSave.Unsaved))
        for (save in listOf(MoveSave.Saving, MoveSave.Unsaved)) assertFalse(moveSaveText(save).contains("저장했어요"))
    }
}
