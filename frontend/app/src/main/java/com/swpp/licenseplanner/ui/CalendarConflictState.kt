package com.swpp.licenseplanner.ui

import com.swpp.licenseplanner.data.AvailabilityWire
import com.swpp.licenseplanner.data.CalendarBusySnapshot
import com.swpp.licenseplanner.data.CalendarReadOutcome
import com.swpp.licenseplanner.data.CalendarReadWindow
import com.swpp.licenseplanner.data.InvalidQuestionDataException
import com.swpp.licenseplanner.data.PlanSnapshot
import com.swpp.licenseplanner.data.QuestionAdapter
import com.swpp.licenseplanner.data.model.QuestionBankResponse
import com.swpp.licenseplanner.data.model.QuestionDto
import com.swpp.licenseplanner.data.model.StudyPlanRequest
import com.swpp.licenseplanner.domain.CERTIFICATION_ID
import com.swpp.licenseplanner.domain.CalendarConflictResult
import com.swpp.licenseplanner.domain.ConflictTarget
import com.swpp.licenseplanner.domain.PlanFormState
import com.swpp.licenseplanner.domain.SessionPlan
import com.swpp.licenseplanner.domain.UNKNOWN_KEY
import com.swpp.licenseplanner.domain.WireDateTime

enum class ConflictKind { Creation, Replacement, Export }
enum class ConflictPhase { ReplacementConsent, RequestingPermission, AwaitingPermission, Reading, Review, Details, Unchecked, Generating, Saving, Unsaved, Replaced, Failed }

/** One explicit action's captured inputs and timing-only observation. Never persisted. */
data class CalendarConflictState(
    val id: Int,
    val kind: ConflictKind,
    val request: StudyPlanRequest,
    val window: CalendarReadWindow,
    val targets: List<ConflictTarget>,
    val phase: ConflictPhase,
    val form: PlanFormState? = null,
    val source: SessionPlan? = null,
    val envelope: PlanSnapshot? = null,
    val outcome: CalendarReadOutcome? = null,
    val conflicts: CalendarConflictResult = CalendarConflictResult(emptyList()),
    val exportConfirmation: CalendarExportPhase.Confirming? = null,
    val exportId: Int? = null,
) {
    val snapshot: CalendarBusySnapshot? get() = (outcome as? CalendarReadOutcome.Loaded)?.snapshot
    val running: Boolean get() = phase in setOf(ConflictPhase.Reading, ConflictPhase.Generating, ConflictPhase.Saving)
}

/** Validate retained assessment data without reconstructing or fetching a different assessment. */
internal fun StudyPlanRequest.canRegenerate(): Boolean {
    val start = WireDateTime.parseOrNull(preparationStart) ?: return false
    val exam = WireDateTime.parseOrNull(examDate) ?: return false
    val context = AvailabilityWire.context(this) ?: return false
    if (certificationId != CERTIFICATION_ID || assessmentResults.certificationId != certificationId ||
        exam <= start || !context.hasWindows() || studyDaysPerWeek != context.weekdaysWithWindows().size) return false
    val results = assessmentResults.results
    return try {
        QuestionAdapter.validate(QuestionBankResponse(certificationId, questions = results.map {
            QuestionDto(it.problemId, it.question, it.choices, it.correctAnswer, it.possibleScore)
        }), certificationId)
        results.all { it.userAnswer in it.choices && it.isCorrect == (it.userAnswer != UNKNOWN_KEY && it.userAnswer == it.correctAnswer) }
    } catch (_: InvalidQuestionDataException) {
        false
    }
}
