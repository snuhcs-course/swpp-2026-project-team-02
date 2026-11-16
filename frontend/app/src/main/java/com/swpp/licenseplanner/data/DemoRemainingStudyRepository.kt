package com.swpp.licenseplanner.data

import com.swpp.licenseplanner.data.model.StudyPlanResponse
import com.swpp.licenseplanner.domain.RemainingDestinations
import com.swpp.licenseplanner.domain.ManualMoveRejection
import com.swpp.licenseplanner.domain.WireDateTime
import com.swpp.licenseplanner.domain.RemainingStudy
import java.time.LocalDateTime
import java.time.ZoneId

/** Local deterministic batch; no calendar, backend or provider dependency. */
class DemoRemainingStudyRepository(
    private val now: () -> LocalDateTime = { LocalDateTime.now(ZoneId.of("Asia/Seoul")).withSecond(0).withNano(0) },
) : RemainingStudyRepository {
    override suspend fun replan(input: RemainingStudyInput): AdjustmentOutcome {
        if (!input.source.isDemo || input.work.instructions.isEmpty() ||
            runCatching { RemainingStudy.calculate(input.source) }.getOrNull() != input.work) {
            return AdjustmentOutcome.Failure(ApiError.InvalidInput(null))
        }
        val rows = if (input.destinations != null) {
            val result = RemainingDestinations.validateDestinations(input.source.current.schedules, input.work.instructions,
                input.destinations, AvailabilityWire.context(input.source.original) ?: return AdjustmentOutcome.Failure(ApiError.InvalidInput(null)),
                requireNotNull(WireDateTime.parseOrNull(input.source.original.preparationStart)),
                requireNotNull(WireDateTime.parseOrNull(input.source.original.examDate)), input.replanFrom, now(), input.confirmOutside)
            result.code?.let { code ->
                return AdjustmentOutcome.Failure(ManualMoveRejection.ofCode(code)?.let { ApiError.ManualMoveRejected(it) }
                    ?: ApiError.InvalidInput(null))
            }
            result.rows
        } else RemainingStudy.place(input.source, input.work, input.replanFrom, now()) ?: return AdjustmentOutcome.DemoNoSlot
        val response = StudyPlanResponse(rows, remainingCsv(rows))
        val problem = remainingResponseProblem(input, response)
        return if (problem == null) AdjustmentOutcome.Success(response)
        else AdjustmentOutcome.Failure(ApiError.InvalidResponse(problem))
    }
}
