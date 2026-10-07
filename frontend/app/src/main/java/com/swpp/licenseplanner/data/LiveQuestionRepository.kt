package com.swpp.licenseplanner.data

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * `GET <base>/certifications/{id}/questions` (FLOW-04). Returns the validated set in
 * response order; failures never fall back to bundled questions.
 */
class LiveQuestionRepository(
    private val baseUrl: String,
    private val client: OkHttpClient = createApiHttpClient(),
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : QuestionRepository {
    override suspend fun load(certificationId: String): QuestionOutcome {
        val request = Request.Builder()
            .url(apiUrl(baseUrl, "/certifications/$certificationId/questions"))
            .get()
            .build()
        return when (val outcome = client.executeApi(request, io)) {
            is HttpOutcome.Failed -> QuestionOutcome.Failure(outcome.error)
            is HttpOutcome.Ok -> try {
                QuestionOutcome.Loaded(QuestionAdapter.parse(outcome.body, certificationId))
            } catch (error: InvalidQuestionDataException) {
                QuestionOutcome.Failure(ApiError.InvalidResponse(error.message))
            }
        }
    }
}
