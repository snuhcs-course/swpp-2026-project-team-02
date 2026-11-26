package com.swpp.licenseplanner.data

import com.swpp.licenseplanner.BuildConfig
import okhttp3.OkHttpClient

/**
 * Live flavor wiring: HTTP repositories against the public API base URL; no demo fallback.
 * All of them share one client that sends the installation's device credential from [DeviceTokenSource].
 */
object FlavorRepositories {
    init {
        check(!BuildConfig.DEMO_MODE) { "live repositories in a demo build" }
    }

    private var client: OkHttpClient? = null

    @Synchronized
    private fun client(tokens: DeviceTokenSource): OkHttpClient = client
        ?: createApiHttpClient().withDeviceCredential(BuildConfig.API_BASE_URL, tokens).also { client = it }

    fun questionRepository(tokens: DeviceTokenSource): QuestionRepository = LiveQuestionRepository(BuildConfig.API_BASE_URL, client(tokens))
    fun planningRepository(tokens: DeviceTokenSource): PlanningRepository = LivePlanningRepository(BuildConfig.API_BASE_URL, client(tokens))
    fun adjustmentRepository(tokens: DeviceTokenSource): AdjustmentRepository = LiveAdjustmentRepository(BuildConfig.API_BASE_URL, client(tokens))
    fun remainingStudyRepository(tokens: DeviceTokenSource): RemainingStudyRepository =
        LiveRemainingStudyRepository(BuildConfig.API_BASE_URL, client(tokens))
    fun reassessmentRepository(tokens: DeviceTokenSource): ReassessmentRepository =
        LiveReassessmentRepository(BuildConfig.API_BASE_URL, client(tokens))
}
