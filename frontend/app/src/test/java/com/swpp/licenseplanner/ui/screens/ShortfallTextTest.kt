package com.swpp.licenseplanner.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ShortfallTextTest {
    @Test
    fun shortfallNamesTopicCountAndFreeMinutes() {
        val text = shortfallText(required = 8, available = 5)
        assertEquals("공부 주제 8개에 비어 있는 시간이 5분뿐이에요", text.title)
        assertEquals(
            "데모 계획은 주제마다 최소 1분이 필요해요. 시험일 전까지 입력한 공부 시간 중 캘린더 일정과 겹치지 않는 시간은 5분이에요.",
            text.body,
        )
        assertFalse(text.title.contains("회"))
        assertFalse(text.body.contains("회"))
    }
}
