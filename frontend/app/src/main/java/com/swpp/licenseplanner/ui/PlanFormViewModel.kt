package com.swpp.licenseplanner.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.swpp.licenseplanner.BuildConfig
import com.swpp.licenseplanner.data.AdjustmentInput
import com.swpp.licenseplanner.data.AdjustmentOutcome
import com.swpp.licenseplanner.data.AdjustmentRepository
import com.swpp.licenseplanner.data.ApiError
import com.swpp.licenseplanner.data.AtomicFilePlanStore
import com.swpp.licenseplanner.data.FlavorRepositories
import com.swpp.licenseplanner.data.LocalPlanStore
import com.swpp.licenseplanner.data.MappingResult
import com.swpp.licenseplanner.data.PlanOutcome
import com.swpp.licenseplanner.data.PlanSnapshot
import com.swpp.licenseplanner.data.PlanStorageScope
import com.swpp.licenseplanner.data.PlanStoreLoad
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

/**
 * The device-local plan collection: restored once at start, then saved after every accepted change.
 * Plan-changing actions wait for [Ready]; viewing works in every state.
 */
enum class PlanStorage {
    /** Reading the saved collection; nothing is known about saved plans yet. */
    Loading,
    Ready,
    /** The saved collection could not be read; it is kept as it was for a local retry. */
    LoadFailure,
    Saving,
    /** The shown collection is newer than the saved one; only a local save may follow. */
    SaveFailure,
}

/** The plan and failed study row chosen for adjustment, with its outcome. */
data class AdjustmentSelection(
    val planOrdinal: Int? = null,
    val failedScheduleId: String? = null,
    val state: AdjustmentState = AdjustmentState.Idle,
)

/**
 * Activity-scoped state for the whole input flow and the generated plans.
 * Form inputs survive back navigation and configuration changes, not process death; the plans
 * are restored from [planStore] and saved there after each accepted change. Repositories come
 * from the build flavor, with no runtime fallback.
 */
class PlanFormViewModel(
    private val questionRepository: QuestionRepository,
    private val planningRepository: PlanningRepository,
    private val adjustmentRepository: AdjustmentRepository,
    today: LocalDate = LocalDate.now(),
    private val planStore: LocalPlanStore,
) : ViewModel() {
    private val _form = MutableStateFlow(PlanFormState(preparationStart = today))
    val form: StateFlow<PlanFormState> = _form.asStateFlow()

    private val _questionLoad = MutableStateFlow<QuestionLoadState>(QuestionLoadState.Idle)
    val questionLoad: StateFlow<QuestionLoadState> = _questionLoad.asStateFlow()

    private val _generation = MutableStateFlow<GenerationState>(GenerationState.Idle)
    val generation: StateFlow<GenerationState> = _generation.asStateFlow()

    /** The request sent by the latest generate(); retry resends exactly this. */
    private var lastRequest: StudyPlanRequest? = null

    /** Every successful generation saved on this device, oldest first; never removed. */
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

    private val _storage = MutableStateFlow(PlanStorage.Loading)
    val storage: StateFlow<PlanStorage> = _storage.asStateFlow()

    /** The collection as last saved; it differs from [plans] only while a save is pending or failed. */
    private val _savedPlans = MutableStateFlow<List<SessionPlan>>(emptyList())
    val savedPlans: StateFlow<List<SessionPlan>> = _savedPlans.asStateFlow()

    private val generating get() = _generation.value == GenerationState.Loading
    private val adjusting get() = _adjustment.value.state == AdjustmentState.Loading
    private val reloading get() = _adjustment.value.state == AdjustmentState.Reloading

    /** An adjustment or reload is in flight; it must finish on the plan it started from. */
    private val busy get() = adjusting || reloading

    /** Plans may change only once restored and while nothing is waiting to be saved. */
    private val storageReady get() = _storage.value == PlanStorage.Ready

    init {
        viewModelScope.launch { restore() }
    }

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
        if (generating || busy || !storageReady) return false
        val request = (RequestMapper.map(_form.value) as? MappingResult.Ready)?.request ?: return false
        send(request)
        return true
    }

    /** Resends the identical request after a failure; inputs are untouched. */
    fun retryGeneration() {
        if (_generation.value !is GenerationState.Failure || busy || !storageReady) return
        send(lastRequest ?: return)
    }

    private fun send(request: StudyPlanRequest) {
        lastRequest = request
        _generation.value = GenerationState.Loading
        viewModelScope.launch {
            when (val outcome = planningRepository.generate(request)) {
                is PlanOutcome.Success -> {
                    // Registered here, once per success; never from a screen. If saving fails, the
                    // same entry stays in memory and only saving is retried.
                    val plan = SessionPlan.register(nextOrdinal++, request, outcome.response, outcome.isDemo)
                    _plans.update { it + plan }
                    _generatedPlan.value = plan.ordinal
                    _generation.value = GenerationState.Success(outcome.response, outcome.isDemo)
                    commit(_plans.value)
                }
                is PlanOutcome.DemoShortfall -> _generation.value = GenerationState.DemoShortfall(outcome.required, outcome.available)
                is PlanOutcome.Failure -> _generation.value = GenerationState.Failure(outcome.error)
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
        if (generating || busy || !storageReady || _adjustment.value.state == AdjustmentState.DemoNoSlot) return false
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
        if (_adjustment.value.state !is AdjustmentState.Failure || generating || !storageReady) return
        val (ordinal, input) = pendingAdjustment ?: return
        if (plan(ordinal)?.recovery != PlanRecovery.Ready) return
        sendAdjustment(ordinal, input)
    }

    /**
     * Reads the selected plan's latest saved rows when it needs a reload; a second call while
     * reading does nothing. Never resends the adjustment. Returns whether a request was sent.
     */
    fun reloadPlan(): Boolean {
        if (generating || busy || !storageReady) return false
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
                    commit(_plans.value)
                }
                ReloadOutcome.Missing -> {
                    updatePlan(ordinal) { it.copy(recovery = PlanRecovery.Missing, recoveryScheduleId = null) }
                    _adjustment.update { it.copy(state = AdjustmentState.Idle) }
                    commit(_plans.value)
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

    /**
     * A live request is sent only after a snapshot that treats it as unresolved has been saved,
     * so a process that ends before the result is saved restores a reload requirement, never a
     * resend. If that save fails nothing is sent, and the selection waits for a new submission.
     */
    private fun sendAdjustment(ordinal: Int, input: AdjustmentInput) {
        pendingAdjustment = ordinal to input
        _adjustment.update { it.copy(state = AdjustmentState.Loading) }
        viewModelScope.launch {
            val live = input.scheduleCsvFile != null
            if (live && !commit(unresolved(ordinal, input.scheduleId), inFlightAdjustment = ordinal)) {
                pendingAdjustment = null
                _adjustment.update { it.copy(state = AdjustmentState.Idle) }
                return@launch
            }
            when (val outcome = adjustmentRepository.adjust(input)) {
                is AdjustmentOutcome.Success -> {
                    // Replace only the captured plan; keep its ordinal, identity and creation request.
                    updatePlan(ordinal) { it.copy(current = outcome.response.copy(scheduleCsvFile = it.current.scheduleCsvFile)) }
                    pendingAdjustment = null
                    _adjustment.update { it.copy(failedScheduleId = null, state = AdjustmentState.Success(input.scheduleId)) }
                    commit(_plans.value)
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
                    // Replaces the unresolved snapshot with the confirmed state.
                    if (live) commit(_plans.value)
                }
            }
        }
    }

    /** The collection as it must be restored if [ordinal]'s request may have changed the server. */
    private fun unresolved(ordinal: Int, scheduleId: String): List<SessionPlan> = _plans.value.map {
        if (it.ordinal == ordinal) it.copy(recovery = PlanRecovery.ReloadRequired, recoveryScheduleId = scheduleId) else it
    }

    /** Reads the saved collection again after a failed restore; local storage only. */
    fun retryLoad() {
        if (_storage.value != PlanStorage.LoadFailure) return
        _storage.value = PlanStorage.Loading
        viewModelScope.launch { restore() }
    }

    /** Saves the shown collection again after a failed save; never repeats a request. */
    fun retrySave() {
        if (_storage.value != PlanStorage.SaveFailure) return
        _storage.value = PlanStorage.Saving
        viewModelScope.launch { commit(_plans.value) }
    }

    private suspend fun restore() {
        when (val result = planStore.load()) {
            is PlanStoreLoad.Loaded -> {
                _plans.value = result.snapshot.plans
                _savedPlans.value = result.snapshot.plans
                nextOrdinal = result.snapshot.nextOrdinal
                _storage.value = PlanStorage.Ready
            }
            is PlanStoreLoad.Failed -> _storage.value = PlanStorage.LoadFailure
        }
    }

    /**
     * Saves [plans] as the whole collection; [inFlightAdjustment] marks a live request about to be
     * sent. Callers hold the operation locks, so saves never overlap. Returns whether it was saved.
     */
    private suspend fun commit(plans: List<SessionPlan>, inFlightAdjustment: Int? = null): Boolean {
        _storage.value = PlanStorage.Saving
        val saved = planStore.save(PlanSnapshot(plans, inFlightAdjustment))
        if (saved) _savedPlans.value = plans
        _storage.value = if (saved) PlanStorage.Ready else PlanStorage.SaveFailure
        return saved
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
        /** Uses the flavor's repositories and this build's plan storage scope. */
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val application = checkNotNull(this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY])
                PlanFormViewModel(
                    FlavorRepositories.questionRepository(),
                    FlavorRepositories.planningRepository(),
                    FlavorRepositories.adjustmentRepository(),
                    planStore = AtomicFilePlanStore.open(
                        application,
                        PlanStorageScope.of(BuildConfig.DEMO_MODE, BuildConfig.API_BASE_URL),
                    ),
                )
            }
        }
    }
}
