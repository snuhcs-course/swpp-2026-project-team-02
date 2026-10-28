package com.swpp.licenseplanner.data

import com.swpp.licenseplanner.data.model.StudyPlanRequest
import com.swpp.licenseplanner.data.model.StudyPlanResponse
import com.swpp.licenseplanner.domain.PlanRecovery
import com.swpp.licenseplanner.domain.SessionPlan
import com.swpp.licenseplanner.domain.Weekday
import com.swpp.licenseplanner.domain.WireDateTime
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.security.MessageDigest
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.ResolverStyle

/**
 * The generated-plan collection as saved on the device, oldest first.
 * [inFlightAdjustmentOrdinal] names the plan whose live adjustment may have been sent without a
 * recorded result; restoring it requires a reload of that plan.
 */
data class PlanSnapshot(val plans: List<SessionPlan>, val inFlightAdjustmentOrdinal: Int? = null) {
    /** One more than any saved ordinal, so a later generation never reuses one. */
    val nextOrdinal: Int get() = (plans.maxOfOrNull { it.ordinal } ?: 0) + 1

    companion object {
        val EMPTY = PlanSnapshot(emptyList())
    }
}

/**
 * Which collection a build reads: the demo flavor, or the live flavor for one public API base URL.
 * Another backend URL gets its own collection; switching back restores the earlier one.
 */
object PlanStorageScope {
    const val DEMO = "demo"

    fun of(demo: Boolean, apiBaseUrl: String): String = if (demo) DEMO else "live " + apiBaseUrl.trimEnd('/') + "/"

    /** A file name derived from [scope], so no URL appears in the path. */
    fun fileName(scope: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(scope.encodeToByteArray())
        return "plans-" + digest.joinToString("") { "%02x".format(it) } + ".json"
    }
}

/**
 * Version-1 JSON form of a [PlanSnapshot]. Decoding checks the version, the scope and every entry;
 * anything it cannot trust is a failure, never an empty or repaired collection.
 */
internal object PlanSnapshotCodec {
    const val VERSION = 1

    // Unlike the API JSON, unknown keys are rejected: this file is only ever written by this codec.
    private val json = Json {
        encodeDefaults = true
        explicitNulls = false
    }

    private val windowTime = DateTimeFormatter.ofPattern("HH:mm").withResolverStyle(ResolverStyle.STRICT)
    private val weekdayKeys = Weekday.entries.map { it.key }.toSet()

    fun encode(snapshot: PlanSnapshot, scope: String): String = json.encodeToString(
        Envelope.serializer(),
        Envelope(
            version = VERSION,
            scope = scope,
            entries = snapshot.plans.map { plan ->
                Entry(
                    ordinal = plan.ordinal,
                    original = plan.original,
                    isDemo = plan.isDemo,
                    current = plan.current,
                    scheduleCsvFile = plan.scheduleCsvFile,
                    recovery = StoredRecovery.of(plan.recovery),
                    recoveryScheduleId = plan.recoveryScheduleId,
                )
            },
            inFlightAdjustmentOrdinal = snapshot.inFlightAdjustmentOrdinal,
        ),
    )

    /**
     * The restored collection with any unresolved adjustment turned into a reload requirement on
     * its plan, so the result carries no in-flight marker. A retained failed session that is no
     * longer a study row of its plan is dropped; the plan's recovery state stays.
     */
    fun decode(text: String, scope: String): PlanStoreLoad {
        val envelope = try {
            val root = json.parseToJsonElement(text).jsonObject
            val version = root["version"]?.jsonPrimitive?.intOrNull ?: return PlanStoreLoad.Failed(PlanLoadFailure.Malformed)
            if (version != VERSION) return PlanStoreLoad.Failed(PlanLoadFailure.Unsupported)
            json.decodeFromJsonElement(Envelope.serializer(), root)
        } catch (_: IllegalArgumentException) {
            // SerializationException is an IllegalArgumentException; so is a non-object root.
            return PlanStoreLoad.Failed(PlanLoadFailure.Malformed)
        }
        if (envelope.scope != scope || !envelope.isValid()) return PlanStoreLoad.Failed(PlanLoadFailure.Invalid)
        val plans = envelope.entries.map { entry ->
            val recovery = if (entry.ordinal == envelope.inFlightAdjustmentOrdinal) PlanRecovery.ReloadRequired else entry.recovery.domain
            val plan = SessionPlan(entry.ordinal, entry.original, entry.isDemo, entry.current, entry.scheduleCsvFile, recovery)
            plan.copy(recoveryScheduleId = entry.recoveryScheduleId?.takeIf { recovery == PlanRecovery.ReloadRequired && plan.isStudy(it) })
        }
        return PlanStoreLoad.Loaded(PlanSnapshot(plans))
    }

    private fun Envelope.isValid(): Boolean {
        val ordinals = entries.map { it.ordinal }
        if (ordinals.any { it <= 0 } || ordinals.toSet().size != ordinals.size) return false
        val demo = scope == PlanStorageScope.DEMO
        if (entries.any { it.isDemo != demo || !it.isValid() }) return false
        val unresolved = inFlightAdjustmentOrdinal ?: return true
        val entry = entries.firstOrNull { it.ordinal == unresolved } ?: return false
        return !entry.isDemo && entry.scheduleCsvFile != null
    }

    private fun Entry.isValid(): Boolean {
        val identityOk = if (isDemo) {
            scheduleCsvFile == null && recovery == StoredRecovery.Ready
        } else {
            scheduleCsvFile?.isNotBlank() ?: (recovery == StoredRecovery.Ready)
        }
        return identityOk && scheduleRowProblem(current.schedules) == null && original.isValid()
    }

    /** Dates and windows of an accepted creation request, which later adjustments resend. */
    private fun StudyPlanRequest.isValid(): Boolean {
        val start = WireDateTime.parseOrNull(preparationStart) ?: return false
        val exam = WireDateTime.parseOrNull(examDate) ?: return false
        if (!exam.isAfter(start) || weeklyAvailability.keys.any { it !in weekdayKeys }) return false
        val windows = weeklyAvailability.values.flatten()
        return windows.isNotEmpty() && windows.all { window ->
            val from = parseTime(window.start) ?: return false
            val to = parseTime(window.end) ?: return false
            to.isAfter(from)
        }
    }

    private fun parseTime(value: String): LocalTime? = try {
        LocalTime.parse(value, windowTime)
    } catch (_: DateTimeParseException) {
        null
    }

    @Serializable
    private data class Envelope(
        val version: Int,
        val scope: String,
        val entries: List<Entry>,
        @SerialName("in_flight_adjustment_ordinal") val inFlightAdjustmentOrdinal: Int? = null,
    )

    @Serializable
    private data class Entry(
        val ordinal: Int,
        val original: StudyPlanRequest,
        @SerialName("is_demo") val isDemo: Boolean,
        val current: StudyPlanResponse,
        @SerialName("schedule_csv_file") val scheduleCsvFile: String? = null,
        val recovery: StoredRecovery,
        @SerialName("recovery_schedule_id") val recoveryScheduleId: String? = null,
    )

    /** Saved names stay fixed even if the domain enum is renamed. */
    @Serializable
    private enum class StoredRecovery(val domain: PlanRecovery) {
        @SerialName("ready") Ready(PlanRecovery.Ready),
        @SerialName("reload_required") ReloadRequired(PlanRecovery.ReloadRequired),
        @SerialName("missing") Missing(PlanRecovery.Missing),
        ;

        companion object {
            fun of(recovery: PlanRecovery) = entries.single { it.domain == recovery }
        }
    }
}
