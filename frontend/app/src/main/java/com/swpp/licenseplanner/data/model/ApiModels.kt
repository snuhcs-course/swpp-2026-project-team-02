package com.swpp.licenseplanner.data.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

// Wire models for the target assessment contract. Local topic labels and
// local earned scores are deliberately absent: they are never submitted.

/** `GET /certifications/{id}/questions` response. */
@Serializable
data class QuestionBankResponse(
    @SerialName("certification_id") val certificationId: String,
    @SerialName("certification_name") val certificationName: String? = null,
    val questions: List<QuestionDto>,
)

@Serializable
data class QuestionDto(
    val id: Int,
    val prompt: String,
    val choices: Map<String, String>,
    @SerialName("correct_answer") val correctAnswer: String,
    @SerialName("possible_score") val possibleScore: Double,
)

/** `POST /study-plans` request. */
@Serializable
data class StudyPlanRequest(
    @SerialName("certification_id") val certificationId: String,
    @SerialName("preparation_start") val preparationStart: String,
    @SerialName("exam_date") val examDate: String,
    @SerialName("self_assessment") val selfAssessment: String,
    @SerialName("assessment_results") val assessmentResults: AssessmentResults,
    @SerialName("study_days_per_week") val studyDaysPerWeek: Int,
    @SerialName("weekly_availability") val weeklyAvailability: Map<String, List<TimeWindow>>,
    @SerialName("busy_schedules") val busySchedules: List<ScheduleRow> = emptyList(),
    /** Monday `YYYY/MM/DD` → replacement week; always sent, `{}` when there is none. */
    @SerialName("availability_overrides") val availabilityOverrides: Map<String, Map<String, List<TimeWindow>>> = emptyMap(),
)

@Serializable
data class AssessmentResults(
    @SerialName("certification_id") val certificationId: String,
    val results: List<AssessmentResult>,
)

@Serializable
data class AssessmentResult(
    @SerialName("problem_id") val problemId: Int,
    val question: String,
    val choices: Map<String, String>,
    @SerialName("correct_answer") val correctAnswer: String,
    @SerialName("user_answer") val userAnswer: String,
    @SerialName("is_correct") val isCorrect: Boolean,
    @Serializable(with = ScoreSerializer::class)
    @SerialName("possible_score") val possibleScore: Double,
)

/**
 * One study window. Requests send `start` + `duration_minutes`; responses also carry a derived
 * `end`. Older saved plans and servers use the same-day `start` + `end` form.
 */
@Serializable
data class TimeWindow(
    val start: String,
    val end: String? = null,
    @SerialName("duration_minutes") val durationMinutes: Int? = null,
)

/** A plan's retained base week and full week-replacement map, as returned by the server. */
@Serializable
data class AvailabilitySnapshot(
    @SerialName("weekly_availability") val weeklyAvailability: Map<String, List<TimeWindow>>,
    @SerialName("availability_overrides") val availabilityOverrides: Map<String, Map<String, List<TimeWindow>>>,
)

/** One returned schedule row; `topic` stays exactly as returned. */
@Serializable
data class ScheduleRow(
    @SerialName("schedule_id") val scheduleId: String,
    @SerialName("start_date") val startDate: String,
    @SerialName("end_date") val endDate: String,
    val topic: String = "",
)

/**
 * `POST /study-plans` response; `tool_calls`, the CSV URL and future fields are ignored.
 * [scheduleCsvFile] is the opaque server-issued plan identity used for adjustment;
 * it may be absent from older servers.
 */
@Serializable
data class StudyPlanResponse(
    val schedules: List<ScheduleRow>,
    @SerialName("schedule_csv") val scheduleCsv: String = "",
    @SerialName("agent_summary") val agentSummary: String? = null,
    @SerialName("schedule_csv_file") val scheduleCsvFile: String? = null,
    @SerialName("availability_snapshot") val availabilitySnapshot: AvailabilitySnapshot? = null,
)

/**
 * `POST /schedules/adjust` request: the selected plan's server file,
 * the failed study row, the plan's full current rows, and its original creation windows and exam time.
 */
@Serializable
data class ScheduleAdjustmentRequest(
    @SerialName("schedule_csv_file") val scheduleCsvFile: String,
    @SerialName("schedule_id") val scheduleId: String,
    val schedules: List<ScheduleRow>,
    @SerialName("weekly_availability") val weeklyAvailability: Map<String, List<TimeWindow>>,
    @SerialName("exam_date") val examDate: String,
    @SerialName("availability_overrides") val availabilityOverrides: Map<String, Map<String, List<TimeWindow>>> = emptyMap(),
    /** Null for the automatic move; `"manual"` with [newStartDate] for a chosen time. */
    val mode: String? = null,
    @SerialName("new_start_date") val newStartDate: String? = null,
    @SerialName("confirm_outside_availability") val confirmOutsideAvailability: Boolean? = null,
    @SerialName("confirm_external_overlap") val confirmExternalOverlap: Boolean? = null,
    @SerialName("preparation_start") val preparationStart: String? = null,
)

/** `POST /schedules/adjust` response; `tool_calls` and future fields are ignored. */
@Serializable
data class ScheduleAdjustmentResponse(
    val schedules: List<ScheduleRow>,
    @SerialName("schedule_csv") val scheduleCsv: String,
    val action: String,
    @SerialName("target_schedule_id") val targetScheduleId: String,
    @SerialName("agent_summary") val agentSummary: String? = null,
    @SerialName("schedule_csv_file") val scheduleCsvFile: String? = null,
    @SerialName("availability_snapshot") val availabilitySnapshot: AvailabilitySnapshot? = null,
)

/**
 * `GET /study-plans` response. Both lists are required, so a missing property is
 * invalid rather than an empty store; the download URL and future fields are ignored.
 */
@Serializable
data class StudyPlanListResponse(val plans: List<StoredStudyPlan>)

@Serializable
data class StoredStudyPlan(
    @SerialName("schedule_csv_file") val scheduleCsvFile: String,
    val schedules: List<ScheduleRow>,
)

@Serializable
data class ApiErrorEnvelope(val error: ApiErrorBody? = null)

@Serializable
data class ApiErrorBody(val code: String? = null, val message: String? = null)

/** Writes whole-number scores as integers (`1`, not `1.0`) so returned scores round-trip unchanged. */
object ScoreSerializer : KSerializer<Double> {
    override val descriptor = PrimitiveSerialDescriptor("PossibleScore", PrimitiveKind.DOUBLE)

    override fun serialize(encoder: Encoder, value: Double) {
        if (value % 1.0 == 0.0 && kotlin.math.abs(value) < 1e15) encoder.encodeLong(value.toLong())
        else encoder.encodeDouble(value)
    }

    override fun deserialize(decoder: Decoder): Double = decoder.decodeDouble()
}
