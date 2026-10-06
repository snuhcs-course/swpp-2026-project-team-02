package com.swpp.licenseplanner.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
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

/**
 * Activity-scoped state for the whole input flow (FLOW-07). Survives back navigation and
 * configuration changes; repositories come from the build flavor, with no runtime fallback.
 */
class PlanFormViewModel(
    private val questionRepository: QuestionRepository,
    private val planningRepository: PlanningRepository,
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
        if (_generation.value == GenerationState.Loading) return false
        val request = (RequestMapper.map(_form.value) as? MappingResult.Ready)?.request ?: return false
        send(request)
        return true
    }

    /** Resends the identical request after a failure; inputs are untouched (GEN-04). */
    fun retryGeneration() {
        if (_generation.value !is GenerationState.Failure) return
        send(lastRequest ?: return)
    }

    private fun send(request: StudyPlanRequest) {
        lastRequest = request
        _generation.value = GenerationState.Loading
        viewModelScope.launch {
            _generation.value = when (val outcome = planningRepository.generate(request)) {
                is PlanOutcome.Success -> GenerationState.Success(outcome.response, outcome.isDemo)
                is PlanOutcome.DemoShortfall -> GenerationState.DemoShortfall(outcome.required, outcome.available)
                is PlanOutcome.Failure -> GenerationState.Failure(outcome.error)
            }
        }
    }

    /** Form edits are ignored during generation and clear any earlier result. */
    private fun edit(transform: (PlanFormState) -> PlanFormState) {
        if (_generation.value == GenerationState.Loading) return
        val before = _form.value
        _form.update(transform)
        if (_form.value != before) {
            _generation.value = GenerationState.Idle
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
                PlanFormViewModel(FlavorRepositories.questionRepository(), FlavorRepositories.planningRepository())
            }
        }
    }
}
