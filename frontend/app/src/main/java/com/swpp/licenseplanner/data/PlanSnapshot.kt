package com.swpp.licenseplanner.data

import com.swpp.licenseplanner.data.model.QuestionBankResponse
import com.swpp.licenseplanner.data.model.QuestionDto
import com.swpp.licenseplanner.data.model.ScheduleRow
import com.swpp.licenseplanner.data.model.StudyPlanRequest
import com.swpp.licenseplanner.data.model.StudyPlanResponse
import com.swpp.licenseplanner.domain.CERTIFICATION_ID
import com.swpp.licenseplanner.domain.CreationDraft
import com.swpp.licenseplanner.domain.CreationStep
import com.swpp.licenseplanner.domain.CurrentLevel
import com.swpp.licenseplanner.domain.DayAvailability
import com.swpp.licenseplanner.domain.PlanFormState
import com.swpp.licenseplanner.domain.PlanRecovery
import com.swpp.licenseplanner.domain.Question
import com.swpp.licenseplanner.domain.SessionPlan
import com.swpp.licenseplanner.domain.StudyCheckIn
import com.swpp.licenseplanner.domain.StudyCheckInStatus
import com.swpp.licenseplanner.domain.Weekday
import com.swpp.licenseplanner.domain.WindowSpec
import com.swpp.licenseplanner.domain.WireDateTime
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.security.MessageDigest
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.ResolverStyle

/**
 * The generated-plan collection as saved on the device, oldest first.
 * [inFlightAdjustmentOrdinal] names the plan whose live adjustment may have been sent without a
 * recorded result; restoring it requires a reload of that plan. [creationDraft] is the one
 * explicitly saved unfinished creation of this storage scope, kept in the same file so a generated
 * plan and the removal of its draft are committed together.
 */
data class PlanSnapshot(
    val plans: List<SessionPlan>,
    val inFlightAdjustmentOrdinal: Int? = null,
    val creationDraft: CreationDraft? = null,
) {
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
 * JSON form of a [PlanSnapshot]. Saving always writes version 4: an optional `creation_draft`
 * next to the entries; each entry carries its study reports (`check_ins`), and creation requests
 * may carry `start` + `duration_minutes` windows and week replacements. Versions 3 (no draft),
 * 2 (also no reports) and 1 (also only same-day `start` + `end` windows, no replacements) are still
 * read, without a draft and without rewriting the file; each creation request is kept exactly as it
 * was accepted. Decoding checks the version, the scope, every entry and the draft; anything it
 * cannot trust is a failure, never an empty or repaired collection. A report whose study row is no
 * longer shown is kept as saved. A draft is checked only for shapes the input screens can hold, not
 * for readiness to generate.
 */
internal object PlanSnapshotCodec {
    const val VERSION = 4
    const val CHECK_IN_VERSION = 3
    const val NO_CHECK_IN_VERSION = 2
    const val LEGACY_VERSION = 1
    private val SUPPORTED_VERSIONS = setOf(LEGACY_VERSION, NO_CHECK_IN_VERSION, CHECK_IN_VERSION, VERSION)

    // Unlike the API JSON, unknown keys are rejected: this file is only ever written by this codec.
    private val json = Json {
        encodeDefaults = true
        explicitNulls = false
    }


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
                    checkIns = plan.checkIns.mapValues { (_, checkIn) -> StoredCheckIn.of(checkIn) },
                )
            },
            inFlightAdjustmentOrdinal = snapshot.inFlightAdjustmentOrdinal,
            creationDraft = snapshot.creationDraft?.let { StoredDraft.of(it) },
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
            if (version !in SUPPORTED_VERSIONS) return PlanStoreLoad.Failed(PlanLoadFailure.Unsupported)
            json.decodeFromJsonElement(Envelope.serializer(), root)
        } catch (_: IllegalArgumentException) {
            // SerializationException is an IllegalArgumentException; so is a non-object root.
            return PlanStoreLoad.Failed(PlanLoadFailure.Malformed)
        }
        if (envelope.scope != scope || !envelope.isValid()) return PlanStoreLoad.Failed(PlanLoadFailure.Invalid)
        val draft = when {
            envelope.creationDraft == null -> null
            envelope.version < VERSION -> return PlanStoreLoad.Failed(PlanLoadFailure.Invalid)
            else -> envelope.creationDraft.domainOrNull() ?: return PlanStoreLoad.Failed(PlanLoadFailure.Invalid)
        }
        val plans = envelope.entries.map { entry ->
            val recovery = if (entry.ordinal == envelope.inFlightAdjustmentOrdinal) PlanRecovery.ReloadRequired else entry.recovery.domain
            val checkIns = entry.checkIns.orEmpty().mapValues { (_, stored) -> stored.domain }
            val plan = SessionPlan(entry.ordinal, entry.original, entry.isDemo, entry.current, entry.scheduleCsvFile, recovery, checkIns = checkIns)
            plan.copy(recoveryScheduleId = entry.recoveryScheduleId?.takeIf { recovery == PlanRecovery.ReloadRequired && plan.isStudy(it) })
        }
        return PlanStoreLoad.Loaded(PlanSnapshot(plans, creationDraft = draft))
    }

    private fun Envelope.isValid(): Boolean {
        val ordinals = entries.map { it.ordinal }
        if (ordinals.any { it <= 0 } || ordinals.toSet().size != ordinals.size) return false
        val demo = scope == PlanStorageScope.DEMO
        if (entries.any { it.isDemo != demo || !it.isValid(version) }) return false
        val unresolved = inFlightAdjustmentOrdinal ?: return true
        val entry = entries.firstOrNull { it.ordinal == unresolved } ?: return false
        return !entry.isDemo && entry.scheduleCsvFile != null
    }

    private fun Entry.isValid(version: Int): Boolean {
        val identityOk = if (isDemo) {
            scheduleCsvFile == null && recovery == StoredRecovery.Ready
        } else {
            scheduleCsvFile?.isNotBlank() ?: (recovery == StoredRecovery.Ready)
        }
        val legacyShapeOk = version != LEGACY_VERSION || (
            original.availabilityOverrides.isEmpty() && current.availabilitySnapshot == null &&
                original.weeklyAvailability.values.flatten().all { it.durationMinutes == null && it.end != null }
            )
        val checkInsOk = if (version < CHECK_IN_VERSION) {
            checkIns == null
        } else {
            checkIns != null && checkIns.all { (id, stored) -> SessionPlan.isStudyId(id) && stored.domain.isValid }
        }
        return identityOk && legacyShapeOk && checkInsOk && scheduleRowProblem(current.schedules) == null && original.isValid()
    }

    /** Dates and the complete availability context of an accepted creation request, which later adjustments resend. */
    private fun StudyPlanRequest.isValid(): Boolean {
        val start = WireDateTime.parseOrNull(preparationStart) ?: return false
        val exam = WireDateTime.parseOrNull(examDate) ?: return false
        if (!exam.isAfter(start)) return false
        val context = AvailabilityWire.context(this) ?: return false
        return context.hasWindows()
    }

    @Serializable
    private data class Envelope(
        val version: Int,
        val scope: String,
        val entries: List<Entry>,
        @SerialName("in_flight_adjustment_ordinal") val inFlightAdjustmentOrdinal: Int? = null,
        /** Only from version 4 on. */
        @SerialName("creation_draft") val creationDraft: StoredDraft? = null,
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
        /** Required from version 3 on and absent before it. */
        @SerialName("check_ins") val checkIns: Map<String, StoredCheckIn>? = null,
    )

    @Serializable
    private data class StoredCheckIn(
        val status: StoredCheckInStatus,
        @SerialName("actual_minutes") val actualMinutes: Int,
    ) {
        val domain: StudyCheckIn get() = StudyCheckIn(status.domain, actualMinutes)

        companion object {
            fun of(checkIn: StudyCheckIn) = StoredCheckIn(StoredCheckInStatus.of(checkIn.status), checkIn.actualMinutes)
        }
    }

    /** Saved names stay fixed even if the domain enum is renamed. */
    @Serializable
    private enum class StoredCheckInStatus(val domain: StudyCheckInStatus) {
        @SerialName("done") Done(StudyCheckInStatus.Done),
        @SerialName("partly") Partly(StudyCheckInStatus.Partly),
        @SerialName("missed") Missed(StudyCheckInStatus.Missed),
        ;

        companion object {
            fun of(status: StudyCheckInStatus) = entries.single { it.domain == status }
        }
    }

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

    private val draftDate = DateTimeFormatter.ofPattern("uuuu-MM-dd").withResolverStyle(ResolverStyle.STRICT)
    private val draftTime = DateTimeFormatter.ofPattern("HH:mm").withResolverStyle(ResolverStyle.STRICT)

    private fun dateOrNull(value: String): LocalDate? = try {
        LocalDate.parse(value, draftDate)
    } catch (_: DateTimeParseException) {
        null
    }

    private fun timeOrNull(value: String): LocalTime? = try {
        LocalTime.parse(value, draftTime)
    } catch (_: DateTimeParseException) {
        null
    }

    /**
     * Unfinished creation input in its own stored shape, independent of the generation request:
     * any field may still be missing and the windows need not be ready for a plan.
     */
    @Serializable
    private data class StoredDraft(
        val step: StoredStep,
        @SerialName("question_index") val questionIndex: Int,
        @SerialName("availability_week") val availabilityWeek: String? = null,
        @SerialName("certification_id") val certificationId: String? = null,
        @SerialName("preparation_start") val preparationStart: String,
        @SerialName("exam_date") val examDate: String? = null,
        val level: StoredLevel? = null,
        val questions: List<StoredQuestion>,
        /** Selected choice key by decimal question ID. */
        val answers: Map<String, String>,
        val availability: Map<String, StoredDay>,
        /** Replacement weeks by Monday; every week lists all seven days, possibly none enabled. */
        @SerialName("availability_overrides") val availabilityOverrides: Map<String, Map<String, StoredDay>>,
        @SerialName("busy_schedules") val busySchedules: List<ScheduleRow>,
    ) {
        /** The draft, or null when any stored value is not one the input screens could hold. */
        fun domainOrNull(): CreationDraft? {
            val start = dateOrNull(preparationStart) ?: return null
            val exam = examDate?.let { dateOrNull(it) ?: return null }
            val week = availabilityWeek?.let { dateOrNull(it) ?: return null }
            val loaded = if (questions.isEmpty()) {
                emptyList()
            } else {
                val bank = QuestionBankResponse(CERTIFICATION_ID, null, questions.map { it.dto })
                try {
                    QuestionAdapter.validate(bank, CERTIFICATION_ID)
                } catch (_: InvalidQuestionDataException) {
                    return null
                }
            }
            val answerMap = answers.entries.associate { (key, choice) ->
                val id = key.toIntOrNull()?.takeIf { it.toString() == key } ?: return null
                id to choice
            }
            val base = days(availability) ?: return null
            val overrides = availabilityOverrides.entries.associate { (key, days) ->
                (dateOrNull(key) ?: return null) to (days(days) ?: return null)
            }
            if (scheduleRowProblem(busySchedules) != null) return null
            val form = PlanFormState(
                certificationId = certificationId,
                preparationStart = start,
                examDate = exam,
                level = level?.domain,
                questions = loaded,
                answers = answerMap,
                availability = base,
                availabilityOverrides = overrides,
                busySchedules = busySchedules,
            )
            return CreationDraft(form, step.domain, questionIndex, week).takeIf { it.isValid }
        }

        private fun days(raw: Map<String, StoredDay>): Map<Weekday, DayAvailability>? {
            val byKey = Weekday.entries.associateBy { it.key }
            if (raw.keys != byKey.keys) return null
            return raw.entries.associate { (key, day) -> byKey.getValue(key) to (day.domainOrNull() ?: return null) }
        }

        companion object {
            fun of(draft: CreationDraft): StoredDraft {
                val form = draft.form
                return StoredDraft(
                    step = StoredStep.of(draft.step),
                    questionIndex = draft.questionIndex,
                    availabilityWeek = draft.availabilityWeek?.format(draftDate),
                    certificationId = form.certificationId,
                    preparationStart = form.preparationStart.format(draftDate),
                    examDate = form.examDate?.format(draftDate),
                    level = form.level?.let { StoredLevel.of(it) },
                    questions = form.questions.map { StoredQuestion.of(it) },
                    answers = form.answers.entries.sortedBy { it.key }.associate { (id, choice) -> id.toString() to choice },
                    availability = days(form.availability),
                    availabilityOverrides = form.availabilityOverrides.toSortedMap()
                        .map { (monday, days) -> monday.format(draftDate) to days(days) }.toMap(),
                    busySchedules = form.busySchedules,
                )
            }

            private fun days(days: Map<Weekday, DayAvailability>): Map<String, StoredDay> =
                Weekday.entries.mapNotNull { day -> days[day]?.let { day.key to StoredDay.of(it) } }.toMap()
        }
    }

    /** One editor day: the visible primary window, on or off, plus the windows the editor keeps hidden. */
    @Serializable
    private data class StoredDay(
        val enabled: Boolean,
        val start: String,
        @SerialName("duration_minutes") val durationMinutes: Int,
        val extras: List<StoredWindow>,
    ) {
        fun domainOrNull(): DayAvailability? {
            val time = timeOrNull(start) ?: return null
            if (!WindowSpec.isValidDuration(durationMinutes)) return null
            return DayAvailability(enabled, time, durationMinutes, extras.map { it.domainOrNull() ?: return null })
        }

        companion object {
            fun of(day: DayAvailability) = StoredDay(
                day.enabled,
                day.start.format(draftTime),
                day.durationMinutes,
                day.extras.map { StoredWindow(it.start.format(draftTime), it.durationMinutes) },
            )
        }
    }

    @Serializable
    private data class StoredWindow(val start: String, @SerialName("duration_minutes") val durationMinutes: Int) {
        fun domainOrNull(): WindowSpec? {
            val time = timeOrNull(start) ?: return null
            return WindowSpec(time, durationMinutes).takeIf { WindowSpec.isValidDuration(durationMinutes) }
        }
    }

    /** A loaded example question exactly as it was shown, choice order included. */
    @Serializable
    private data class StoredQuestion(
        val id: Int,
        val prompt: String,
        val choices: Map<String, String>,
        @SerialName("correct_answer") val correctAnswer: String,
        @SerialName("possible_score") val possibleScore: Double,
    ) {
        val dto: QuestionDto get() = QuestionDto(id, prompt, choices, correctAnswer, possibleScore)

        companion object {
            fun of(question: Question) =
                StoredQuestion(question.id, question.prompt, question.choices, question.correctAnswer, question.possibleScore)
        }
    }

    /** Saved names stay fixed even if the domain enum is renamed. */
    @Serializable
    private enum class StoredStep(val domain: CreationStep) {
        @SerialName("certification") Certification(CreationStep.CERTIFICATION),
        @SerialName("dates") Dates(CreationStep.DATES),
        @SerialName("level") Level(CreationStep.LEVEL),
        @SerialName("questions") Questions(CreationStep.QUESTIONS),
        @SerialName("results") Results(CreationStep.RESULTS),
        @SerialName("availability") Availability(CreationStep.AVAILABILITY),
        ;

        companion object {
            fun of(step: CreationStep) = entries.single { it.domain == step }
        }
    }

    /** Saved names stay fixed even if the domain enum is renamed. */
    @Serializable
    private enum class StoredLevel(val domain: CurrentLevel) {
        @SerialName("beginner") Beginner(CurrentLevel.BEGINNER),
        @SerialName("intermediate") Intermediate(CurrentLevel.INTERMEDIATE),
        @SerialName("advanced") Advanced(CurrentLevel.ADVANCED),
        ;

        companion object {
            fun of(level: CurrentLevel) = entries.single { it.domain == level }
        }
    }
}
