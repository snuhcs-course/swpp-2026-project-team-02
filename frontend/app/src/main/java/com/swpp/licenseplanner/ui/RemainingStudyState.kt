package com.swpp.licenseplanner.ui

import com.swpp.licenseplanner.data.ApiError
import com.swpp.licenseplanner.data.RemainingStudyInput
import com.swpp.licenseplanner.data.model.ScheduleRow
import com.swpp.licenseplanner.domain.FreeTimeSuggestion
import com.swpp.licenseplanner.domain.PlacedSession
import com.swpp.licenseplanner.domain.SessionPlan

enum class FreeTimePhase { RequestingPermission, AwaitingPermission, Reading, Results, PermissionDenied, Unavailable, NoTime }

data class FreeTimeSearch(val id: Int, val source: SessionPlan, val targetId: String,
                          val phase: FreeTimePhase = FreeTimePhase.RequestingPermission,
                          val suggestions: List<FreeTimeSuggestion> = emptyList(), val selected: Int? = null)

sealed interface RemainingStudyState {
    data class Review(val input: RemainingStudyInput) : RemainingStudyState
    data class Loading(val input: RemainingStudyInput) : RemainingStudyState
    data class CalendarSearch(val id: Int, val input: RemainingStudyInput,
                              val phase: FreeTimePhase = FreeTimePhase.RequestingPermission,
                              val placements: List<PlacedSession> = emptyList(), val changed: Boolean = false,
                              val suggestions: List<FreeTimeSuggestion> = emptyList(), val selected: Int? = null) : RemainingStudyState
    data class Success(val input: RemainingStudyInput, val rows: List<ScheduleRow>, val save: MoveSave = MoveSave.Saving) : RemainingStudyState
    data class Failure(val input: RemainingStudyInput, val error: ApiError? = null, val noFit: Boolean = false) : RemainingStudyState
    data class Invalid(val message: String) : RemainingStudyState
}

internal val RemainingStudyState?.holdsSource: Boolean get() = this is RemainingStudyState.Review ||
    this is RemainingStudyState.CalendarSearch ||
    this is RemainingStudyState.Loading || (this is RemainingStudyState.Success && save == MoveSave.Saving)

internal fun remainingFailureMessage(error: ApiError?): String = when (error) {
    is ApiError.ServerFailure, is ApiError.ServerUnavailable ->
        "배치 결과를 확인할 수 없어요. 서버에 반영되었을 수 있으니 최신 계획을 불러와 확인해 주세요."
    is ApiError.InvalidInput -> when {
        error.message?.contains("NOT_ENOUGH_TIME") == true -> "남은 공부를 모두 배치할 시간이 없어요"
        error.message?.startsWith("mode must be") == true ||
            error.message?.contains("remaining_sessions item") == true ||
            error.message == "confirm_outside_availability is not used when mode is remaining" -> "서버 업데이트가 필요해요"
        else -> "원래 계획 정보를 확인할 수 없어요. 새 계획을 만들거나 계획을 확인한 뒤 다시 시도해 주세요."
    }
    is ApiError.ManualMoveRejected -> "시간을 다시 확인해야 해요"
    else -> error?.adjustmentMessage() ?: "남은 공부를 모두 배치할 시간이 없어요"
}
