package com.swpp.licenseplanner.data

import com.swpp.licenseplanner.domain.Question

sealed interface QuestionOutcome {
    data class Loaded(val questions: List<Question>) : QuestionOutcome
    data class Failure(val error: ApiError) : QuestionOutcome
}

/** Example-question source; live GET or the demo fixture, chosen by flavor (design D6). */
interface QuestionRepository {
    suspend fun load(certificationId: String): QuestionOutcome
}
