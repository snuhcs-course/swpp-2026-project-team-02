package com.swpp.licenseplanner.data

import android.content.Context
import com.swpp.licenseplanner.domain.SessionPlan
import kotlinx.serialization.builtins.ListSerializer

/** Stores generated plans on the device so they remain available after process restarts. */
interface SessionPlanPersistence {
    fun load(): List<SessionPlan>
    fun save(plans: List<SessionPlan>)
}

class SharedPreferencesSessionPlanPersistence(context: Context) : SessionPlanPersistence {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    override fun load(): List<SessionPlan> = runCatching {
        preferences.getString(PLANS_KEY, null)?.let {
            ApiJson.decodeFromString(ListSerializer(SessionPlan.serializer()), it)
        }.orEmpty()
    }.getOrDefault(emptyList())

    override fun save(plans: List<SessionPlan>) {
        preferences.edit()
            .putString(PLANS_KEY, ApiJson.encodeToString(ListSerializer(SessionPlan.serializer()), plans))
            .apply()
    }

    private companion object {
        const val PREFERENCES = "saved_study_plans"
        const val PLANS_KEY = "plans"
    }
}
