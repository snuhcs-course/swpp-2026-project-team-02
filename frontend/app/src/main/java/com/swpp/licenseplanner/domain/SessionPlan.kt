package com.swpp.licenseplanner.domain

import com.swpp.licenseplanner.data.model.StudyPlanRequest
import com.swpp.licenseplanner.data.model.StudyPlanResponse
import kotlinx.serialization.Serializable

/**
 * One successful generation kept for the app process. [ordinal] is the
 * local key for lists and navigation; it is never sent to a server. [original] is the immutable
 * creation request, so later form edits never change this plan's windows or exam date.
 * [scheduleCsvFile] is the exact nonblank server identity from generation (live only).
 */
@Serializable
data class SessionPlan(
    val ordinal: Int,
    val original: StudyPlanRequest,
    val isDemo: Boolean,
    val current: StudyPlanResponse,
    val scheduleCsvFile: String?,
) {
    val studyCount: Int get() = current.schedules.count { !it.scheduleId.startsWith(EXTERNAL_PREFIX) }

    /** A live plan without a server identity can be viewed but not adjusted. */
    val hasIdentity: Boolean get() = isDemo || scheduleCsvFile != null

    fun isStudy(scheduleId: String): Boolean =
        !scheduleId.startsWith(EXTERNAL_PREFIX) && current.schedules.any { it.scheduleId == scheduleId }

    companion object {
        private const val EXTERNAL_PREFIX = "external-"

        fun register(ordinal: Int, request: StudyPlanRequest, response: StudyPlanResponse, isDemo: Boolean) = SessionPlan(
            ordinal = ordinal,
            original = request,
            isDemo = isDemo,
            current = response,
            scheduleCsvFile = if (isDemo) null else response.scheduleCsvFile?.takeIf { it.isNotBlank() },
        )
    }
}
