package com.swpp.licenseplanner.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.swpp.licenseplanner.data.AdjustmentInput
import com.swpp.licenseplanner.data.AdjustmentOutcome
import com.swpp.licenseplanner.data.AdjustmentRepository
import com.swpp.licenseplanner.data.ApiError
import com.swpp.licenseplanner.data.FlavorRepositories
import com.swpp.licenseplanner.data.MappingResult
import com.swpp.licenseplanner.data.PlanOutcome
import com.swpp.licenseplanner.data.PlanningRepository
import com.swpp.licenseplanner.data.QuestionOutcome
import com.swpp.licenseplanner.data.QuestionRepository
import com.swpp.licenseplanner.data.RequestMapper
import com.swpp.licenseplanner.data.model.StudyPlanRequest
import com.swpp.licenseplanner.data.model.StudyPlanResponse
import com.swpp.licenseplanner.domain.CurrentLevel
import com.swpp.licenseplanner.domain.DayAvailability
import com.swpp.licenseplanner.domain.PlanFormState
import com.swpp.licenseplanner.domain.SessionPlan
import com.swpp.licenseplanner.domain.Weekday
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime

/** Example-question loading (design D4): loaded once per form flow, retried only after failure. */
sealed interface QuestionLoadState {
    data object Idle : QuestionLoadState
    data object Loading : QuestionLoadState
    data object Loaded : QuestionLoadState
    data class Failure(val error: ApiError) : QuestionLoadState
}

/** Plan generation lifecycle (design D4, GEN-03/04/06). */
sealed interface GenerationState {
    data object Idle : GenerationState
    data object Loading : GenerationState
    data class Success(val response: StudyPlanResponse, val isDemo: Boolean) : GenerationState
    data class Failure(val error: ApiError) : GenerationState
    data class DemoShortfall(val required: Int, val available: Int) : GenerationState
}

/** Failed-session adjustment lifecycle (design D1/D4, REPLAN-05/07). */
sealed interface AdjustmentState {
    data object Idle : AdjustmentState
    data object Loading : AdjustmentState
    /** The selected plan was replaced; [targetScheduleId] is the moved row. */
    data class Success(val targetScheduleId: String) : AdjustmentState
    data class Failure(val error: ApiError) : AdjustmentState
    /** Demo-only: no permitted interval fits before the exam. */
    data object DemoNoSlot : AdjustmentState
}

/** The plan and failed study row chosen for adjustment, with its outcome (REPLAN-03/08). */
data class AdjustmentSelection(
    val planOrdinal: Int? = null,
    val failedScheduleId: String? = null,
    val state: AdjustmentState = AdjustmentState.Idle,
)

/**
 * Activity-scoped state for the whole input flow (FLOW-07) and the session's generated plans
 * (design D1). Survives back navigation and configuration changes, not process death;
 * repositories come from the build flavor, with no runtime fallback.
 */
class PlanFormViewModel(
    private val questionRepository: QuestionRepository,
    private val planningRepository: PlanningRepository,
    private val adjustmentRepository: AdjustmentRepository,
    today: LocalDate = LocalDate.now(),
) : ViewModel() {
    private val _form = MutableStateFlow(PlanFormState(preparationStart = today))
    val form: StateFlow<PlanFormState> = _form.asStateFlow()

    private val _questionLoad = MutableStateFlow<QuestionLoadState>(QuestionLoadState.Idle)
    val questionLoad: StateFlow<QuestionLoadState> = _questionLoad.asStateFlow()

    private val _generation = MutableStateFlow<GenerationState>(GenerationState.Idle)
    val generation: StateFlow<GenerationState> = _generation.asStateFlow()

    /** The request sent by the latest generate(); retry resends exactly this. */
    private var lastRequest: StudyPlanRequest? = null

    /** Every successful generation in this process, oldest first; never removed (REPLAN-02). */
    private val _plans = MutableStateFlow<List<SessionPlan>>(emptyList())
    val plans: StateFlow<List<SessionPlan>> = _plans.asStateFlow()
    private var nextOrdinal = 1

    /** Ordinal of the plan shown by the generated-result screen; null after form edits. */
    private val _generatedPlan = MutableStateFlow<Int?>(null)
    val generatedPlan: StateFlow<Int?> = _generatedPlan.asStateFlow()

    private val _adjustment = MutableStateFlow(AdjustmentSelection())
    val adjustment: StateFlow<AdjustmentSelection> = _adjustment.asStateFlow()

    /** Plan ordinal and input of the latest adjustment; retry resends exactly this. */
    private var pendingAdjustment: Pair<Int, AdjustmentInput>? = null

    private val generating get() = _generation.value == GenerationState.Loading
    private val adjusting get() = _adjustment.value.state == AdjustmentState.Loading

    fun selectCertification(id: String) = edit { it.copy(certificationId = id) }
    fun setPreparationStart(date: LocalDate) = edit { it.copy(preparationStart = date) }
    fun setExamDate(date: LocalDate) = edit { it.copy(examDate = date) }
    fun setLevel(level: CurrentLevel) = edit { it.copy(level = level) }

    fun answer(questionId: Int, choiceKey: String) = edit { state ->
        if (state.questions.none { it.id == questionId }) state
        else state.copy(answers = state.answers + (questionId to choiceKey))
    }

    fun setDayEnabled(day: Weekday, enabled: Boolean) = editDay(day) { it.copy(enabled = enabled) }
    fun setDayStart(day: Weekday, start: LocalTime) = editDay(day) { it.copy(start = start) }

    /** Steps the duration by [DayAvailability.STEP_MINUTES] within the allowed range. */
    fun stepDuration(day: Weekday, steps: Int) = editDay(day) {
        val minutes = (it.durationMinutes + steps * DayAvailability.STEP_MINUTES)
            .coerceIn(DayAvailability.MIN_DURATION_MINUTES, DayAvailability.MAX_DURATION_MINUTES)
        it.copy(durationMinutes = minutes)
    }

    /**
     * Loads the example questions on first entry to the assessment. Ignored while loading
     * or after a successful load, so returning to the questions never refetches.
     */
    fun loadQuestions() {
        val state = _questionLoad.value
        if (state == QuestionLoadState.Loading || state == QuestionLoadState.Loaded) return
        val certificationId = _form.value.certificationId ?: return
        _questionLoad.value = QuestionLoadState.Loading
        viewModelScope.launch {
            when (val outcome = questionRepository.load(certificationId)) {
                is QuestionOutcome.Loaded -> {
                    _form.update { it.copy(questions = outcome.questions, answers = emptyMap()) }
                    _questionLoad.value = QuestionLoadState.Loaded
                }
                is QuestionOutcome.Failure -> _questionLoad.value = QuestionLoadState.Failure(outcome.error)
            }
        }
    }

    /**
     * Builds the request once and sends it; a second call while loading does nothing (GEN-03).
     * Returns whether a request was sent. A local demo result may already be set on return.
     */
    fun generate(): Boolean {
        if (generating || adjusting) return false
        val request = (RequestMapper.map(_form.value) as? MappingResult.Ready)?.request ?: return false
        send(request)
        return true
    }

    /** Resends the identical request after a failure; inputs are untouched (GEN-04). */
    fun retryGeneration() {
        if (_generation.value !is GenerationState.Failure || adjusting) return
        send(lastRequest ?: return)
    }

    private fun send(request: StudyPlanRequest) {
        lastRequest = request
        _generation.value = GenerationState.Loading
        viewModelScope.launch {
            _generation.value = when (val outcome = planningRepository.generate(request)) {
                is PlanOutcome.Success -> {
                    // Registered here, once per success; never from a screen (design D1).
                    val plan = SessionPlan.register(nextOrdinal++, request, outcome.response, outcome.isDemo)
                    _plans.update { it + plan }
                    _generatedPlan.value = plan.ordinal
                    GenerationState.Success(outcome.response, outcome.isDemo)
                }
                is PlanOutcome.DemoShortfall -> GenerationState.DemoShortfall(outcome.required, outcome.available)
                is PlanOutcome.Failure -> GenerationState.Failure(outcome.error)
            }
        }
    }

    /**
     * Opens [ordinal] for adjustment. A different plan starts with no selection or outcome;
     * the same plan keeps both. Refused while an adjustment is in flight (REPLAN-05/08).
     */
    fun selectPlan(ordinal: Int): Boolean {
        if (adjusting || _plans.value.none { it.ordinal == ordinal }) return false
        if (_adjustment.value.planOrdinal != ordinal) {
            _adjustment.value = AdjustmentSelection(planOrdinal = ordinal)
            pendingAdjustment = null
        }
        return true
    }

    /** Marks one study row of the selected plan as failed, replacing any earlier choice (REPLAN-03). */
    fun selectFailedSession(scheduleId: String) {
        val selection = _adjustment.value
        if (adjusting || selection.failedScheduleId == scheduleId) return
        if (selectedPlan()?.isStudy(scheduleId) != true) return
        _adjustment.value = selection.copy(failedScheduleId = scheduleId, state = AdjustmentState.Idle)
        pendingAdjustment = null
    }

    /**
     * Sends the selected plan's identity, failed row, current rows and original windows; a second
     * call while loading does nothing. Returns whether a request was sent (REPLAN-04/05).
     */
    fun submitAdjustment(): Boolean {
        if (generating || adjusting) return false
        val plan = selectedPlan()?.takeIf { it.hasIdentity } ?: return false
        val target = _adjustment.value.failedScheduleId?.takeIf { plan.isStudy(it) } ?: return false
        sendAdjustment(plan.ordinal, AdjustmentInput(plan.original, plan.scheduleCsvFile, plan.current.schedules, target))
        return true
    }

    /** Resends the identical snapshot after a failure; the plan and selection are untouched (REPLAN-05). */
    fun retryAdjustment() {
        if (_adjustment.value.state !is AdjustmentState.Failure || generating) return
        val (ordinal, input) = pendingAdjustment ?: return
        sendAdjustment(ordinal, input)
    }

    private fun selectedPlan(): SessionPlan? = _adjustment.value.planOrdinal?.let { ordinal ->
        _plans.value.firstOrNull { it.ordinal == ordinal }
    }

    private fun sendAdjustment(ordinal: Int, input: AdjustmentInput) {
        pendingAdjustment = ordinal to input
        _adjustment.update { it.copy(state = AdjustmentState.Loading) }
        viewModelScope.launch {
            when (val outcome = adjustmentRepository.adjust(input)) {
                is AdjustmentOutcome.Success -> {
                    // Replace only the captured plan; keep its ordinal, identity and creation request (design D4).
                    _plans.update { plans ->
                        plans.map { plan ->
                            if (plan.ordinal != ordinal) plan
                            else plan.copy(current = outcome.response.copy(scheduleCsvFile = plan.current.scheduleCsvFile))
                        }
                    }
                    pendingAdjustment = null
                    _adjustment.update { it.copy(failedScheduleId = null, state = AdjustmentState.Success(input.scheduleId)) }
                }
                AdjustmentOutcome.DemoNoSlot -> _adjustment.update { it.copy(state = AdjustmentState.DemoNoSlot) }
                is AdjustmentOutcome.Failure -> _adjustment.update { it.copy(state = AdjustmentState.Failure(outcome.error)) }
            }
        }
    }

    /** Form edits are ignored during generation and clear any earlier result, never the stored plans. */
    private fun edit(transform: (PlanFormState) -> PlanFormState) {
        if (generating) return
        val before = _form.value
        _form.update(transform)
        if (_form.value != before) {
            _generation.value = GenerationState.Idle
            _generatedPlan.value = null
            lastRequest = null
        }
    }

    private fun editDay(day: Weekday, transform: (DayAvailability) -> DayAvailability) = edit { state ->
        val current = state.availability[day] ?: DayAvailability()
        state.copy(availability = state.availability + (day to transform(current)))
    }

    companion object {
        /** Uses the flavor's repositories (design D2). */
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                PlanFormViewModel(
                    FlavorRepositories.questionRepository(),
                    FlavorRepositories.planningRepository(),
                    FlavorRepositories.adjustmentRepository(),
                )
            }
        }
    }
}
