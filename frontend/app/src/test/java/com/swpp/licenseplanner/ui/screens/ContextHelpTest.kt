package com.swpp.licenseplanner.ui.screens

import com.swpp.licenseplanner.BuildConfig
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextHelpTest {
    @Test
    fun startHelpDescribesThePlannerForThisBuild() {
        val body = ContextHelpTopic.START.body
        if (BuildConfig.DEMO_MODE) {
            assertTrue(body.contains("이 기기에서"))
            assertFalse(body.contains("서버에서 처리해요"))
        } else {
            assertTrue(body.contains("서버에서 처리해요"))
            assertFalse(body.contains("이 기기에서"))
        }
    }

    @Test
    fun adjustmentHelpNamesManualMoveAndRemainingStudyReplanning() {
        val body = ContextHelpTopic.ADJUSTMENT.body
        assertTrue(body.contains("시간 직접 옮기기"))
        assertTrue(body.contains("남은 공부 다시 계획하기"))
    }
}
