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
import com.swpp.licenseplanner.data.ReloadOutcome
import com.swpp.licenseplanner.data.RequestMapper
import com.swpp.licenseplanner.data.model.StudyPlanRequest
import com.swpp.licenseplanner.data.model.StudyPlanResponse
import com.swpp.licenseplanner.domain.CurrentLevel
import com.swpp.licenseplanner.domain.DayAvailability
import com.swpp.licenseplanner.domain.PlanFormState
import com.swpp.licenseplanner.domain.PlanRecovery
import com.swpp.licenseplanner.domain.SessionPlan
import com.swpp.licenseplanner.domain.Weekday
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime

/** Example-question loading: loaded once per form flow, retried only after failure. */
sealed interface QuestionLoadState {
    data object Idle : QuestionLoadState
    data object Loading : QuestionLoadState
    data object Loaded : QuestionLoadState
    data class Failure(val error: ApiError) : QuestionLoadState
}

/** Plan generation lifecycle. */
sealed interface GenerationState {
    data object Idle : GenerationState
    data object Loading : GenerationState
    data class Success(val response: StudyPlanResponse, val isDemo: Boolean) : GenerationState
    data class Failure(val error: ApiError) : GenerationState
    data class DemoShortfall(val required: Int, val available: Int) : GenerationState
}

/** Failed-session adjustment lifecycle. */
sealed interface AdjustmentState {
    data object Idle : AdjustmentState
    data object Loading : AdjustmentState
    /** The selected plan was replaced; [targetScheduleId] is the moved row. */
    data class Success(val targetScheduleId: String) : AdjustmentState
    data class Failure(val error: ApiError) : AdjustmentState
    /** Demo-only: no permitted interval fits before the exam. */
    data object DemoNoSlot : AdjustmentState
    /** Reading the selected plan's latest saved rows. */
    data object Reloading : AdjustmentState
    /** The reload failed; the plan still needs one before it can be adjusted. */
    data class ReloadFailure(val error: ApiError) : AdjustmentState
    /** The latest saved rows replaced the plan; a fresh selection is needed. */
    data object Reloaded : AdjustmentState
}

/** The plan and failed study row chosen for adjustment, with its outcome. */
data class AdjustmentSelection(
    val planOrdinal: Int? = null,
    val failedScheduleId: String? = null,
    val state: AdjustmentState = AdjustmentState.Idle,
)

/**
 * Activity-scoped state for the whole input flow and the session's generated plans.
 * Survives back navigation and configuration changes, not process death;
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

    /** Every successful generation in this process, oldest first; never removed. */
    private val _plans = MutableStateFlow<List<SessionPlan>>(emptyList())
    val plans: StateFlow<List<SessionPlan>> = _plans.asStateFlow()
    private var nextOrdinal = 1

    /** Ordinal of the plan shown by the generated-result screen; null after form edits. */
    private val _generatedPlan = MutableStateFlow<Int?>(null)
    val generatedPlan: StateFlow<Int?> = _generatedPlan.asStateFlow()

    private val _adjustment = MutableStateFlow(AdjustmentSelection())
    val adjustment: StateFlow<AdjustmentSelection> = _adjustment.asStateFlow()

    /**
     * Plan ordinal and input of the latest adjustment; retry resends exactly this. Dropped once
     * the server's copy is uncertain, so a possibly applied or stale mutation is never resent.
     */
    private var pendingAdjustment: Pair<Int, AdjustmentInput>? = null

    private val generating get() = _generation.value == GenerationState.Loading
    private val adjusting get() = _adjustment.value.state == AdjustmentState.Loading
    private val reloading get() = _adjustment.value.state == AdjustmentState.Reloading

    /** An adjustment or reload is in flight; it must finish on the plan it started from. */
    private val busy get() = adjusting || reloading

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
     * Builds the request once and sends it; a second call while loading does nothing.
     * Returns whether a request was sent. A local demo result may already be set on return.
     */
    fun generate(): Boolean {
        if (generating || busy) return false
        val request = (RequestMapper.map(_form.value) as? MappingResult.Ready)?.request ?: return false
        send(request)
        return true
    }

    /** Resends the identical request after a failure; inputs are untouched. */
    fun retryGeneration() {
        if (_generation.value !is GenerationState.Failure || busy) return
        send(lastRequest ?: return)
    }

    private fun send(request: StudyPlanRequest) {
        lastRequest = request
        _generation.value = GenerationState.Loading
        viewModelScope.launch {
            _generation.value = when (val outcome = planningRepository.generate(request)) {
                is PlanOutcome.Success -> {
                    // Registered here, once per success; never from a screen.
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
     * Opens [ordinal] for adjustment. A different plan starts with no outcome and no selection,
     * except that a plan awaiting reload shows its own failed session again; the same plan keeps
     * both, and every plan keeps its [SessionPlan.recovery].
     * Refused while an adjustment or reload is in flight.
     */
    fun selectPlan(ordinal: Int): Boolean {
        if (busy) return false
        val plan = plan(ordinal) ?: return false
        if (_adjustment.value.planOrdinal != ordinal) {
            val retained = plan.recoveryScheduleId
                ?.takeIf { plan.recovery == PlanRecovery.ReloadRequired && plan.isStudy(it) }
            _adjustment.value = AdjustmentSelection(planOrdinal = ordinal, failedScheduleId = retained)
            pendingAdjustment = null
        }
        return true
    }

    /**
     * Marks one study row of the selected plan as failed, replacing any earlier choice.
     * Ignored while the plan needs a reload or is missing, so the recovery state stays.
     */
    fun selectFailedSession(scheduleId: String) {
        val selection = _adjustment.value
        if (busy || selection.failedScheduleId == scheduleId) return
        val plan = selectedPlan() ?: return
        if (plan.recovery != PlanRecovery.Ready || !plan.isStudy(scheduleId)) return
        _adjustment.value = selection.copy(failedScheduleId = scheduleId, state = AdjustmentState.Idle)
        pendingAdjustment = null
    }

    /**
     * Sends the selected plan's identity, failed row, current rows and original windows; a second
     * call while loading does nothing. After a demo no-slot result the same selection would give the
     * same result, so nothing is sent until another session is chosen. Returns whether a request was sent.
     */
    fun submitAdjustment(): Boolean {
        if (generating || busy || _adjustment.value.state == AdjustmentState.DemoNoSlot) return false
        val plan = selectedPlan()?.takeIf { it.canAdjust } ?: return false
        val target = _adjustment.value.failedScheduleId?.takeIf { plan.isStudy(it) } ?: return false
        sendAdjustment(plan.ordinal, AdjustmentInput(plan.original, plan.scheduleCsvFile, plan.current.schedules, target))
        return true
    }

    /**
     * Resends the identical snapshot after a confirmed failure; the plan and selection are untouched.
     * Does nothing for a plan that needs a reload or is missing.
     */
    fun retryAdjustment() {
        if (_adjustment.value.state !is AdjustmentState.Failure || generating) return
        val (ordinal, input) = pendingAdjustment ?: return
        if (plan(ordinal)?.recovery != PlanRecovery.Ready) return
        sendAdjustment(ordinal, input)
    }

    /**
     * Reads the selected plan's latest saved rows when it needs a reload; a second call while
     * reading does nothing. Never resends the adjustment. Returns whether a request was sent.
     */
    fun reloadPlan(): Boolean {
        if (generating || busy) return false
        val plan = selectedPlan()?.takeIf { it.recovery == PlanRecovery.ReloadRequired } ?: return false
        val ordinal = plan.ordinal
        _adjustment.update { it.copy(state = AdjustmentState.Reloading) }
        viewModelScope.launch {
            when (val outcome = adjustmentRepository.reload(plan.scheduleCsvFile)) {
                is ReloadOutcome.Loaded -> {
                    // The list has no CSV text or summary for these rows, so the old ones are dropped.
                    updatePlan(ordinal) {
                        it.copy(
                            current = it.current.copy(schedules = outcome.schedules, scheduleCsv = "", agentSummary = null),
                            recovery = PlanRecovery.Ready,
                            recoveryScheduleId = null,
                        )
                    }
                    pendingAdjustment = null
                    _adjustment.update { it.copy(failedScheduleId = null, state = AdjustmentState.Reloaded) }
                }
                ReloadOutcome.Missing -> {
                    updatePlan(ordinal) { it.copy(recovery = PlanRecovery.Missing, recoveryScheduleId = null) }
                    _adjustment.update { it.copy(state = AdjustmentState.Idle) }
                }
                is ReloadOutcome.Failure -> _adjustment.update { it.copy(state = AdjustmentState.ReloadFailure(outcome.error)) }
            }
        }
        return true
    }

    private fun selectedPlan(): SessionPlan? = _adjustment.value.planOrdinal?.let(::plan)

    private fun plan(ordinal: Int): SessionPlan? = _plans.value.firstOrNull { it.ordinal == ordinal }

    /** Changes only the entry [ordinal]; every other plan stays as it is. */
    private fun updatePlan(ordinal: Int, transform: (SessionPlan) -> SessionPlan) =
        _plans.update { plans -> plans.map { if (it.ordinal == ordinal) transform(it) else it } }

    private fun sendAdjustment(ordinal: Int, input: AdjustmentInput) {
        pendingAdjustment = ordinal to input
        _adjustment.update { it.copy(state = AdjustmentState.Loading) }
        viewModelScope.launch {
            when (val outcome = adjustmentRepository.adjust(input)) {
                is AdjustmentOutcome.Success -> {
                    // Replace only the captured plan; keep its ordinal, identity and creation request.
                    updatePlan(ordinal) { it.copy(current = outcome.response.copy(scheduleCsvFile = it.current.scheduleCsvFile)) }
                    pendingAdjustment = null
                    _adjustment.update { it.copy(failedScheduleId = null, state = AdjustmentState.Success(input.scheduleId)) }
                }
                AdjustmentOutcome.DemoNoSlot -> _adjustment.update { it.copy(state = AdjustmentState.DemoNoSlot) }
                is AdjustmentOutcome.Failure -> {
                    val recovery = recoveryAfter(outcome.error, input)
                    if (recovery != PlanRecovery.Ready) {
                        pendingAdjustment = null
                        // The failed session is kept with the gate so returning to this plan shows it again.
                        val target = input.scheduleId.takeIf { recovery == PlanRecovery.ReloadRequired }
                        updatePlan(ordinal) { it.copy(recovery = recovery, recoveryScheduleId = target) }
                    }
                    _adjustment.update { it.copy(state = AdjustmentState.Failure(outcome.error)) }
                }
            }
        }
    }

    /** A live failure that leaves the server's copy unknown or gone blocks further adjustment of that plan. */
    private fun recoveryAfter(error: ApiError, input: AdjustmentInput): PlanRecovery = when {
        input.scheduleCsvFile == null -> PlanRecovery.Ready
        error == ApiError.PlanNotFound -> PlanRecovery.Missing
        error.requiresReload() -> PlanRecovery.ReloadRequired
        else -> PlanRecovery.Ready
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
        /** Uses the flavor's repositories. */
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
