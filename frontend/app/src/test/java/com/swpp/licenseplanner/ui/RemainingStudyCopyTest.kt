package com.swpp.licenseplanner.ui

import com.swpp.licenseplanner.data.ApiError
import com.swpp.licenseplanner.ui.screens.remainingDatedTime
import com.swpp.licenseplanner.ui.screens.remainingSaveText
import org.junit.Assert.*
import org.junit.Test

class RemainingStudyCopyTest {
    @Test fun saveCopyDistinguishesPendingSavedAndFailedWithoutClaimingServerOrCalendarSync() {
        assertEquals("이 기기에 저장하는 중이에요", remainingSaveText(MoveSave.Saving))
        assertEquals("이 기기에 저장했어요", remainingSaveText(MoveSave.Saved))
        assertEquals("아직 이 기기에 저장하지 못했어요.", remainingSaveText(MoveSave.Unsaved))
    }

    @Test fun bothDatesArePresentAcrossMidnightAndYears() {
        assertEquals("2026년 12월 31일 23:30 – 2027년 1월 1일 00:30", remainingDatedTime("2026/12/31/23/30", "2027/01/01/00/30"))
    }

    @Test fun failuresDescribeRulesUnsupportedModeAndUncertaintyWithoutRawDiagnostics() {
        assertTrue(remainingFailureMessage(ApiError.InvalidInput("NOT_ENOUGH_TIME: study-3")).startsWith("현재 배치 규칙"))
        assertTrue(remainingFailureMessage(ApiError.InvalidInput("mode must be automatic or manual")).contains("서버 업데이트"))
        assertFalse(remainingFailureMessage(ApiError.InvalidInput("/private/path token secret")).contains("secret"))
        assertTrue(remainingFailureMessage(ApiError.Connection).contains("최신 계획"))
    }
}
