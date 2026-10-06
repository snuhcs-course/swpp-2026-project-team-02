package com.swpp.licenseplanner.data.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

// Wire models for the target assessment contract (design D5). Local topic labels and
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

@Serializable
data class TimeWindow(val start: String, val end: String)

/** One returned schedule row; `topic` stays exactly as returned. */
@Serializable
data class ScheduleRow(
    @SerialName("schedule_id") val scheduleId: String,
    @SerialName("start_date") val startDate: String,
    @SerialName("end_date") val endDate: String,
    val topic: String = "",
)

/** `POST /study-plans` response; `tool_calls`, CSV file/URL and future fields are ignored. */
@Serializable
data class StudyPlanResponse(
    val schedules: List<ScheduleRow>,
    @SerialName("schedule_csv") val scheduleCsv: String = "",
    @SerialName("agent_summary") val agentSummary: String? = null,
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
