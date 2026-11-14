package com.swpp.licenseplanner.ui

import com.swpp.licenseplanner.domain.PlanGrouping
import com.swpp.licenseplanner.domain.StudyCheckIn
import com.swpp.licenseplanner.domain.StudyCheckInStatus
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
import com.swpp.licenseplanner.data.AvailabilityProfileRepository
import com.swpp.licenseplanner.data.AndroidCalendarExportRepository
import com.swpp.licenseplanner.data.AndroidCalendarReadRepository
import com.swpp.licenseplanner.data.AtomicFilePlanStore
import com.swpp.licenseplanner.data.CalendarBusySnapshot
import com.swpp.licenseplanner.data.CalendarDestination
import com.swpp.licenseplanner.data.CalendarExportRepository
import com.swpp.licenseplanner.data.CalendarListOutcome
import com.swpp.licenseplanner.data.CalendarReadOutcome
import com.swpp.licenseplanner.data.CalendarReadRepository
import com.swpp.licenseplanner.data.CalendarReadWindow
import com.swpp.licenseplanner.data.CalendarWriteOutcome
import com.swpp.licenseplanner.data.ExportNotStarted
import com.swpp.licenseplanner.data.FlavorRepositories
import com.swpp.licenseplanner.data.LocalPlanStore
import com.swpp.licenseplanner.data.ManualMove
import com.swpp.licenseplanner.data.MappingResult
import com.swpp.licenseplanner.data.PlanOutcome
import com.swpp.licenseplanner.data.PlanSnapshot
import com.swpp.licenseplanner.data.PlanStorageScope
import com.swpp.licenseplanner.data.PlanStoreLoad
import com.swpp.licenseplanner.data.PlanningRepository
import com.swpp.licenseplanner.data.ProfileOutcome
import com.swpp.licenseplanner.data.QuestionOutcome
import com.swpp.licenseplanner.data.QuestionRepository
import com.swpp.licenseplanner.data.ReloadOutcome
import com.swpp.licenseplanner.data.RequestMapper
import com.swpp.licenseplanner.data.UnavailableCalendarExport
import com.swpp.licenseplanner.data.UnavailableCalendarRead
import com.swpp.licenseplanner.data.model.ScheduleRow
import com.swpp.licenseplanner.data.model.StudyPlanRequest
import com.swpp.licenseplanner.data.model.StudyPlanResponse
import com.swpp.licenseplanner.domain.CalendarExport
import com.swpp.licenseplanner.domain.CurrentLevel
import com.swpp.licenseplanner.domain.DayAvailability
import com.swpp.licenseplanner.domain.ManualMoveRejection
import com.swpp.licenseplanner.domain.PlanFormState
import com.swpp.licenseplanner.domain.PlanRecovery
import com.swpp.licenseplanner.domain.SessionPlan
import com.swpp.licenseplanner.domain.StudyAvailability
import com.swpp.licenseplanner.domain.Weekday
import com.swpp.licenseplanner.domain.WindowSpec
import com.swpp.licenseplanner.domain.emptyWeek
import com.swpp.licenseplanner.domain.toEditorDays
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
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

/** Reading busy occurrences from the device calendar for the current preparation period. */
sealed interface CalendarReadState {
    data object Idle : CalendarReadState
    data object RequestingPermission : CalendarReadState
    data object AwaitingPermission : CalendarReadState
    data object Loading : CalendarReadState
    data class Loaded(
        val count: Int,
        val recurringOccurrences: Int,
        val allDayOccurrences: Int,
    ) : CalendarReadState
    data object Empty : CalendarReadState
    data object PermissionDenied : CalendarReadState
    data object Unavailable : CalendarReadState
}

/** Failed-session adjustment lifecycle. */
sealed interface AdjustmentState {
    data object Idle : AdjustmentState
    data object Loading : AdjustmentState
    /**
     * The selected plan was replaced; [targetScheduleId] is the moved row. [move] is the row as it
     * was sent and as accepted, and [save] tells whether this outcome is saved on this device.
     * Both exist only while this result is shown; they are never saved or rebuilt from a summary.
     */
    data class Success(
        val targetScheduleId: String,
        val move: SessionMove? = null,
        val save: MoveSave = MoveSave.Saving,
    ) : AdjustmentState {
        init {
            require(move == null || move.after.scheduleId == targetScheduleId)
        }
    }
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
 * One accepted move of a study row: [before] is the row from the plan's rows as they were sent,
 * so an earlier move is the starting point, and [after] is the same row from the accepted
 * response, whose topic is authoritative.
 */
data class SessionMove(val before: ScheduleRow, val after: ScheduleRow) {
    init {
        require(before.scheduleId == after.scheduleId)
    }
}

/** Whether the plan changed by a shown move is part of the saved collection. */
enum class MoveSave {
    /** The changed plan is being written; nothing is known about its durability yet. */
    Saving,
    /** The changed plan is part of the collection saved on this device. */
    Saved,
    /** The changed plan is shown but its save failed; only a local retry may follow. */
    Unsaved,
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
    /** An open chosen-time move for [failedScheduleId]; null when none is being edited. */
    val manual: ManualDraft? = null,
) {
    /** Only a refusal for being outside the study windows may be confirmed, and only for that same destination. */
    val canConfirmOutsideAvailability: Boolean
        get() = manual?.newStart != null && !manual.confirmOutsideAvailability &&
            (state as? AdjustmentState.Failure)?.error == ApiError.ManualMoveRejected(ManualMoveRejection.OutsideStudyWindows)

    /** The server or demo policy refused the current chosen time. */
    val manualRejected: Boolean
        get() = (state as? AdjustmentState.Failure)?.error is ApiError.ManualMoveRejected
}

/**
 * The chosen start being reviewed. [confirmOutsideAvailability] is the one-time window exception
 * for this exact destination; choosing another time clears it.
 */
data class ManualDraft(val newStart: LocalDateTime? = null, val confirmOutsideAvailability: Boolean = false)

/** Why the report being edited cannot be saved as entered. */
enum class CheckInInputError {
    /** No status has been chosen yet. */
    StatusRequired,
    /** Done or partly needs a positive whole number of minutes that fits an [Int]. */
    InvalidMinutes,
}

/** Where the explicit save of an edited report stands. */
enum class CheckInSave {
    /** Nothing has been written; cancelling keeps the earlier report. */
    Editing,
    Saving,
    /** The edited report is part of the saved collection. */
    Saved,
    /** The edited report is shown but the save failed; only a local retry may follow. */
    Unsaved,
}

/**
 * The report editor for one study row of one plan, captured when it opened. [committed] is the
 * saved report it started from (null: unreported) and [status] is null until the learner chooses
 * one. [plannedMinutes] is the scheduled duration, only a suggestion for done. [minutesEdited]
 * records that the minutes are the learner's own (or a saved report's), so choosing a status no
 * longer replaces them.
 */
data class CheckInEditor(
    val planOrdinal: Int,
    val scheduleId: String,
    val plannedMinutes: Int,
    val committed: StudyCheckIn?,
    val status: StudyCheckInStatus?,
    val minutesText: String,
    val minutesEdited: Boolean,
    val error: CheckInInputError? = null,
    val save: CheckInSave = CheckInSave.Editing,
) {
    /** Minutes can be typed only for done or partly, before saving. */
    val minutesEnabled: Boolean get() = save == CheckInSave.Editing && status != null && status != StudyCheckInStatus.Missed

    /** The report the current input would save, or null when it cannot be saved. */
    val candidate: StudyCheckIn? get() = status?.let { StudyCheckIn.fromInput(it, minutesText) }
}

/**
 * Whether plan [ordinal]'s report for [scheduleId] is shown in [shown] but differs from the saved
 * collection [saved], so it must not be presented as saved.
 */
fun isCheckInUnsaved(shown: List<SessionPlan>, saved: List<SessionPlan>, ordinal: Int, scheduleId: String): Boolean {
    val report = shown.firstOrNull { it.ordinal == ordinal }?.checkInFor(scheduleId)
    return report != saved.firstOrNull { it.ordinal == ordinal }?.checkInFor(scheduleId)
}

/** Explicit load or save of the server's shared study-time profile (live only). */
sealed interface ProfileState {
    data object Idle : ProfileState
    data object Loading : ProfileState
    /** A loaded profile waiting for the learner to accept it; the form is unchanged until then. */
    data class Preview(val availability: StudyAvailability) : ProfileState
    data object Saving : ProfileState
    data object Saved : ProfileState
    /** The draft is kept; [saving] says which explicit action failed. */
    data class Failure(val error: ApiError, val saving: Boolean) : ProfileState
}

/**
 * Activity-scoped state for the whole input flow and the generated plans.
 * Form inputs survive back navigation and configuration changes, not process death; the plans
 * are restored from [planStore] and saved there after each accepted change. Repositories come
 * from the build flavor, with no runtime fallback; [calendarExportRepository] writes to the device
 * calendar and [calendarReadRepository] reads only busy times in both flavors.
 */
class PlanFormViewModel(
    private val questionRepository: QuestionRepository,
    private val planningRepository: PlanningRepository,
    private val adjustmentRepository: AdjustmentRepository,
    today: LocalDate = LocalDate.now(),
    private val planStore: LocalPlanStore,
    private val calendarExportRepository: CalendarExportRepository = UnavailableCalendarExport,
    private val calendarReadRepository: CalendarReadRepository = UnavailableCalendarRead,
    /** The shared server profile; null in demo, which keeps study times on the device. */
    private val availabilityProfileRepository: AvailabilityProfileRepository? = null,
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

    /** The open calendar export flow, or null. It is never saved, so a restart never resumes it. */
    private val _calendarExport = MutableStateFlow<CalendarExportState?>(null)
    val calendarExport: StateFlow<CalendarExportState?> = _calendarExport.asStateFlow()
    private var nextExportId = 1

    private val _profile = MutableStateFlow<ProfileState>(ProfileState.Idle)
    val profile: StateFlow<ProfileState> = _profile.asStateFlow()

    /** Whether this build can load or save the server profile at all. */
    val profileAvailable: Boolean get() = availabilityProfileRepository != null

    private val _availabilityWeek = MutableStateFlow<LocalDate?>(null)

    /** The week the availability editor last showed; [PlanFormState.shownWeek] resolves it. */
    val availabilityWeek: StateFlow<LocalDate?> = _availabilityWeek.asStateFlow()

    private val _calendarRead = MutableStateFlow<CalendarReadState>(CalendarReadState.Idle)
    val calendarRead: StateFlow<CalendarReadState> = _calendarRead.asStateFlow()
    private var calendarReadId = 0

    /** The open study report editor, or null. It is never saved, so a restart never resumes it. */
    private val _checkIn = MutableStateFlow<CheckInEditor?>(null)
    val checkIn: StateFlow<CheckInEditor?> = _checkIn.asStateFlow()

    private val generating get() = _generation.value == GenerationState.Loading
    private val adjusting get() = _adjustment.value.state == AdjustmentState.Loading
    private val reloading get() = _adjustment.value.state == AdjustmentState.Reloading
    private val exporting get() = _calendarExport.value != null
    private val readingCalendar get() = _calendarRead.value in activeCalendarReads

    /**
     * An adjustment or reload is in flight, or a calendar export holds its plan; each must finish
     * on the plan it started from.
     */
    private val busy get() = adjusting || reloading || exporting || readingCalendar

    /** Plans may change only once restored and while nothing is waiting to be saved. */
    private val storageReady get() = _storage.value == PlanStorage.Ready

    /**
     * Each plan's calendar export availability by ordinal (null: it may start now). It applies the
     * same rules as [startCalendarExport] and updates whenever plans, storage, generation,
     * adjustment, reload, an export or a calendar read changes.
     */
    val exportBlocks: StateFlow<Map<Int, ExportBlock?>> = combine(
        _plans, _storage, _generation, _adjustment, combine(_calendarExport, _calendarRead, ::Pair),
    ) { plans, storage, generation, adjustment, (export, read) ->
        plans.associate { it.ordinal to exportBlockOf(it, storage, generation, adjustment, export, read) }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, _plans.value.associate { it.ordinal to currentExportBlock(it) })

    init {
        viewModelScope.launch { restore() }
    }

    fun selectCertification(id: String) = edit { it.copy(certificationId = id) }
    fun setPreparationStart(date: LocalDate) = editDates { it.copy(preparationStart = date) }
    fun setExamDate(date: LocalDate) = editDates { it.copy(examDate = date) }
    fun setLevel(level: CurrentLevel) = edit { it.copy(level = level) }

    fun answer(questionId: Int, choiceKey: String) = edit { state ->
        if (state.questions.none { it.id == questionId }) state
        else state.copy(answers = state.answers + (questionId to choiceKey))
    }

    /*
     * Day edits change the visible primary window only; unshown windows of that day stay. A null
     * [week] edits the recurring base; a Monday edits that week's replacement, if it has one.
     */
    fun setDayEnabled(day: Weekday, enabled: Boolean, week: LocalDate? = null) = editDay(day, week) { it.copy(enabled = enabled) }
    fun setDayStart(day: Weekday, start: LocalTime, week: LocalDate? = null) = editDay(day, week) { it.copy(start = start) }

    /** Steps the duration by [DayAvailability.STEP_MINUTES] within the allowed range. */
    fun stepDuration(day: Weekday, steps: Int, week: LocalDate? = null) = editDay(day, week) {
        val minutes = (it.durationMinutes + steps * DayAvailability.STEP_MINUTES)
            .coerceIn(DayAvailability.MIN_DURATION_MINUTES, DayAvailability.MAX_DURATION_MINUTES)
        it.copy(durationMinutes = minutes)
    }

    /** Sets an entered duration and turns the day on; a value outside 1–1439 minutes is refused. */
    fun setDuration(day: Weekday, minutes: Int, week: LocalDate? = null): Boolean {
        if (generating || !WindowSpec.isValidDuration(minutes)) return false
        editDay(day, week) { it.copy(enabled = true, durationMinutes = minutes) }
        return true
    }

    fun showAvailabilityWeek(monday: LocalDate): Boolean {
        if (monday !in _form.value.studyWeeks) return false
        _availabilityWeek.value = monday
        return true
    }

    /** Gives the week its own times, starting from a copy of the base week (unshown windows included). */
    fun replaceWeek(monday: LocalDate): Boolean = editWeek(monday) { it.availability }

    /** Makes the week an explicit no-study week; windows starting the previous day still run into it. */
    fun clearWeek(monday: LocalDate): Boolean = editWeek(monday) { emptyWeek() }

    /** Removes the week's replacement so it inherits the base again. */
    fun resetWeek(monday: LocalDate): Boolean = editWeek(monday) { null }

    private fun editWeek(monday: LocalDate, days: (PlanFormState) -> Map<Weekday, DayAvailability>?): Boolean {
        if (generating || monday !in _form.value.studyWeeks) return false
        edit { state ->
            val replacement = days(state)
            state.copy(
                availabilityOverrides = if (replacement == null) state.availabilityOverrides - monday
                else state.availabilityOverrides + (monday to replacement),
            )
        }
        _availabilityWeek.value = monday
        return true
    }

    /**
     * Reads the server profile on explicit request and shows it as a preview; the form changes only
     * after [acceptProfile]. Returns whether a request was sent.
     */
    fun loadProfile(): Boolean {
        val repository = availabilityProfileRepository ?: return false
        if (generating || profileBusy) return false
        _profile.value = ProfileState.Loading
        viewModelScope.launch {
            _profile.value = when (val outcome = repository.load()) {
                is ProfileOutcome.Loaded -> ProfileState.Preview(outcome.availability)
                is ProfileOutcome.Failure -> ProfileState.Failure(outcome.error, saving = false)
            }
        }
        return true
    }

    /** Replaces the form's base week and week replacements with the previewed profile, unshown windows included. */
    fun acceptProfile() {
        val preview = _profile.value as? ProfileState.Preview ?: return
        if (generating) return
        edit { state ->
            state.copy(
                availability = preview.availability.base.toEditorDays(),
                availabilityOverrides = preview.availability.overrides.mapValues { it.value.toEditorDays() },
            )
        }
        _profile.value = ProfileState.Idle
    }

    /** Closes a preview, failure or saved notice without touching the form. */
    fun dismissProfile() {
        if (!profileBusy) _profile.value = ProfileState.Idle
    }

    /**
     * Saves the complete current context (every window, shown or not, and every week replacement)
     * as the shared profile on explicit request. Nothing is sent while the context is invalid.
     */
    fun saveProfile(): Boolean {
        val repository = availabilityProfileRepository ?: return false
        val form = _form.value
        if (generating || profileBusy || form.availabilityError != null) return false
        _profile.value = ProfileState.Saving
        viewModelScope.launch {
            _profile.value = when (val outcome = repository.save(form.studyAvailability)) {
                is ProfileOutcome.Loaded -> ProfileState.Saved
                is ProfileOutcome.Failure -> ProfileState.Failure(outcome.error, saving = true)
            }
        }
        return true
    }

    private val profileBusy get() = _profile.value == ProfileState.Loading || _profile.value == ProfileState.Saving

    /** Starts an explicit device-calendar import. No calendar is read before this action. */
    fun startCalendarRead(): Boolean {
        val examDate = _form.value.examDate ?: return false
        if (generating || busy || !examDate.isAfter(_form.value.preparationStart)) return false
        clearBusySchedules()
        calendarReadId += 1
        _calendarRead.value = CalendarReadState.RequestingPermission
        return true
    }

    /** Marks the Android permission prompt as launched, preventing a duplicate prompt on recomposition. */
    fun calendarReadPermissionPrompted(): Boolean {
        if (_calendarRead.value != CalendarReadState.RequestingPermission) return false
        _calendarRead.value = CalendarReadState.AwaitingPermission
        return true
    }

    /** Continues the import after Android resolves READ_CALENDAR permission. */
    fun onCalendarReadPermission(granted: Boolean) {
        if (_calendarRead.value !in setOf(CalendarReadState.RequestingPermission, CalendarReadState.AwaitingPermission)) return
        if (!granted) {
            clearBusySchedules()
            _calendarRead.value = CalendarReadState.PermissionDenied
            return
        }
        val form = _form.value
        val examDate = form.examDate
        if (examDate == null || !examDate.isAfter(form.preparationStart)) {
            _calendarRead.value = CalendarReadState.Unavailable
            return
        }
        val requestId = calendarReadId
        val window = CalendarReadWindow(form.preparationStart, examDate)
        _calendarRead.value = CalendarReadState.Loading
        viewModelScope.launch {
            val outcome = calendarReadRepository.read(window)
            if (requestId != calendarReadId || _form.value.preparationStart != window.start || _form.value.examDate != window.endExclusive) {
                return@launch
            }
            when (outcome) {
                is CalendarReadOutcome.Loaded -> acceptBusySchedules(outcome.snapshot)
                CalendarReadOutcome.PermissionMissing -> {
                    clearBusySchedules()
                    _calendarRead.value = CalendarReadState.PermissionDenied
                }
                CalendarReadOutcome.Unavailable -> {
                    clearBusySchedules()
                    _calendarRead.value = CalendarReadState.Unavailable
                }
            }
        }
    }

    private fun acceptBusySchedules(snapshot: CalendarBusySnapshot) {
        edit { it.copy(busySchedules = snapshot.schedules) }
        _calendarRead.value = if (snapshot.schedules.isEmpty()) {
            CalendarReadState.Empty
        } else {
            CalendarReadState.Loaded(
                snapshot.schedules.size,
                snapshot.recurringOccurrences,
                snapshot.allDayOccurrences,
            )
        }
    }

    private fun clearBusySchedules() {
        edit { if (it.busySchedules.isEmpty()) it else it.copy(busySchedules = emptyList()) }
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
     * both except a shown move, and every plan keeps its [SessionPlan.recovery].
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
        } else if (_adjustment.value.state is AdjustmentState.Success) {
            // A shown move describes the last visit only; reopening the plan starts without it.
            _adjustment.update { it.copy(state = AdjustmentState.Idle) }
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
        _adjustment.value = selection.copy(failedScheduleId = scheduleId, state = AdjustmentState.Idle, manual = null)
        pendingAdjustment = null
    }

    /** Opens a chosen-time move for the selected study row. Nothing is sent until [submitManualMove]. */
    fun startManualMove(): Boolean {
        if (generating || busy || !storageReady) return false
        val plan = selectedPlan()?.takeIf { it.canAdjust } ?: return false
        _adjustment.value.failedScheduleId?.takeIf { plan.isStudy(it) } ?: return false
        _adjustment.update { it.copy(state = AdjustmentState.Idle, manual = ManualDraft()) }
        pendingAdjustment = null
        return true
    }

    /** Chooses the destination start; any earlier outside-window confirmation no longer applies. */
    fun setManualStart(start: LocalDateTime) {
        if (busy) return
        val draft = _adjustment.value.manual ?: return
        if (draft.newStart == start) return
        _adjustment.update { it.copy(state = AdjustmentState.Idle, manual = ManualDraft(start)) }
        pendingAdjustment = null
    }

    /** Discards the draft without sending anything. */
    fun cancelManualMove() {
        if (busy) return
        _adjustment.update { it.copy(state = AdjustmentState.Idle, manual = null) }
    }

    /**
     * Declines the one-time window exception and returns to editing with the chosen time kept.
     * Nothing is sent; sending the same time again is checked by the server from the start.
     */
    fun reviseManualMove() {
        if (busy || !_adjustment.value.canConfirmOutsideAvailability) return
        _adjustment.update { it.copy(state = AdjustmentState.Idle) }
        pendingAdjustment = null
    }

    /**
     * Sends the reviewed destination once. Returns whether a request was sent. A destination the
     * server refused is not sent again; choosing another time makes it sendable.
     */
    fun submitManualMove(): Boolean =
        !_adjustment.value.manualRejected && sendManual(confirmOutsideAvailability = false)

    /**
     * After an outside-study-windows refusal of this exact destination, sends it again with the
     * one-time exception. Bounds and overlaps are still checked by the server.
     */
    fun confirmOutsideAvailability(): Boolean =
        _adjustment.value.canConfirmOutsideAvailability && sendManual(confirmOutsideAvailability = true)

    private fun sendManual(confirmOutsideAvailability: Boolean): Boolean {
        if (generating || busy || !storageReady) return false
        val start = _adjustment.value.manual?.newStart ?: return false
        val plan = selectedPlan()?.takeIf { it.canAdjust } ?: return false
        val target = _adjustment.value.failedScheduleId?.takeIf { plan.isStudy(it) } ?: return false
        _adjustment.update { it.copy(manual = ManualDraft(start, confirmOutsideAvailability)) }
        sendAdjustment(
            plan.ordinal,
            AdjustmentInput(plan.original, plan.scheduleCsvFile, plan.current.schedules, target, ManualMove(start, confirmOutsideAvailability)),
        )
        return true
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
        if (_adjustment.value.state !is AdjustmentState.Failure || generating || exporting || !storageReady) return
        if (_adjustment.value.manualRejected) return
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

    /**
     * Opens the report editor for study row [scheduleId] of plan [ordinal], prefilled with its saved
     * report or with no status for an unreported row. External rows, unknown rows and plans that are
     * being changed or not yet saved are refused. Opening writes nothing and leaves the adjustment
     * selection and recovery state alone; a plan awaiting reload or missing may still be reported.
     */
    fun startCheckIn(ordinal: Int, scheduleId: String): Boolean {
        if (generating || busy || !storageReady || _checkIn.value?.save == CheckInSave.Saving) return false
        val plan = plan(ordinal)?.takeIf { it.isStudy(scheduleId) } ?: return false
        val planned = PlanGrouping.sessions(plan.current.schedules)
            .firstOrNull { it.scheduleId == scheduleId }?.durationMinutes ?: return false
        val committed = plan.checkInFor(scheduleId)
        val minutes = committed?.actualMinutes?.takeIf { committed.status != StudyCheckInStatus.Missed }
        _checkIn.value = CheckInEditor(
            planOrdinal = ordinal,
            scheduleId = scheduleId,
            plannedMinutes = planned.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt(),
            committed = committed,
            status = committed?.status,
            minutesText = minutes?.toString().orEmpty(),
            minutesEdited = minutes != null,
        )
        return true
    }

    /**
     * Chooses the status being edited. Done suggests the scheduled minutes and partly asks for
     * them, unless the learner already entered minutes; missed keeps them aside and saves zero.
     */
    fun chooseCheckInStatus(status: StudyCheckInStatus) = _checkIn.update { editor ->
        if (editor == null || editor.save != CheckInSave.Editing) return@update editor
        val minutes = when {
            editor.minutesEdited || status == StudyCheckInStatus.Missed -> editor.minutesText
            status == StudyCheckInStatus.Done -> editor.plannedMinutes.takeIf { it > 0 }?.toString().orEmpty()
            else -> ""
        }
        editor.copy(status = status, minutesText = minutes, error = null)
    }

    /** Replaces the typed actual minutes for done or partly; the text is validated only on save. */
    fun setCheckInMinutes(text: String) = _checkIn.update { editor ->
        if (editor == null || !editor.minutesEnabled) editor else editor.copy(minutesText = text, minutesEdited = true, error = null)
    }

    /**
     * Saves the edited report once, replacing only this plan's report for that row, through the
     * whole-collection save. Invalid input shows its error and keeps the saved report; a row that
     * left the plan, a second tap and any running plan change are refused. Nothing is sent to a
     * server. On failure the edit stays shown as unsaved and [retrySave] saves it locally.
     * Returns whether a save started.
     */
    fun saveCheckIn(): Boolean {
        val editor = _checkIn.value?.takeIf { it.save == CheckInSave.Editing } ?: return false
        if (generating || busy || !storageReady) return false
        if (editor.status == null) {
            _checkIn.value = editor.copy(error = CheckInInputError.StatusRequired)
            return false
        }
        val report = editor.candidate ?: run {
            _checkIn.value = editor.copy(error = CheckInInputError.InvalidMinutes)
            return false
        }
        val updated = plan(editor.planOrdinal)?.withCheckIn(editor.scheduleId, report) ?: return false
        updatePlan(editor.planOrdinal) { updated }
        _checkIn.value = editor.copy(save = CheckInSave.Saving, error = null)
        viewModelScope.launch {
            val saved = commit(_plans.value)
            finishCheckInSave(saved)
        }
        return true
    }

    /**
     * Closes the editor. Before saving this discards the edit and writes nothing; after a save
     * (confirmed or failed) the shown collection stays as it is. Refused while saving.
     */
    fun closeCheckIn() = _checkIn.update { if (it?.save == CheckInSave.Saving) it else null }

    private fun finishCheckInSave(saved: Boolean) = _checkIn.update {
        if (it?.save == CheckInSave.Saving) it.copy(save = if (saved) CheckInSave.Saved else CheckInSave.Unsaved) else it
    }

    /**
     * Opens a calendar export of plan [ordinal]'s current study rows, captured now. Refused unless the
     * plan has study rows, is saved, needs no reload and nothing else runs. Writes nothing; while the
     * flow is open the plan is held against generation, adjustment, reload and plan switching.
     */
    fun startCalendarExport(ordinal: Int): Boolean {
        val plan = plan(ordinal) ?: return false
        if (currentExportBlock(plan) != null) return false
        val events = CalendarExport.events(plan.current.schedules, plan.ordinal, plan.isDemo)
        val phase = if (events == null) CalendarExportPhase.InvalidRows else CalendarExportPhase.RequestingPermission
        _calendarExport.value = CalendarExportState(nextExportId++, ExportSource(plan.ordinal, plan.isDemo, events.orEmpty()), phase)
        return true
    }

    /** Marks the system permission prompt as shown; true only once per request, so it is never shown twice. */
    fun calendarPermissionPrompted(): Boolean {
        val state = _calendarExport.value?.takeIf { it.phase == CalendarExportPhase.RequestingPermission } ?: return false
        _calendarExport.value = state.copy(phase = CalendarExportPhase.AwaitingPermission)
        return true
    }

    /** Access granted leads to the calendar list, never to a write; denial ends the flow without a write. */
    fun onCalendarPermission(granted: Boolean) {
        val state = _calendarExport.value ?: return
        if (state.phase != CalendarExportPhase.RequestingPermission && state.phase != CalendarExportPhase.AwaitingPermission) return
        if (!granted) {
            _calendarExport.value = state.copy(phase = CalendarExportPhase.PermissionDenied)
            return
        }
        _calendarExport.value = state.copy(phase = CalendarExportPhase.LoadingCalendars)
        viewModelScope.launch {
            val phase = when (val outcome = calendarExportRepository.calendars()) {
                is CalendarListOutcome.Loaded ->
                    if (outcome.calendars.isEmpty()) CalendarExportPhase.NoCalendars else CalendarExportPhase.Choosing(outcome.calendars)
                CalendarListOutcome.PermissionMissing -> CalendarExportPhase.PermissionDenied
                CalendarListOutcome.Unavailable -> CalendarExportPhase.CalendarsUnavailable
            }
            _calendarExport.update { if (it?.id == state.id && it.phase == CalendarExportPhase.LoadingCalendars) it.copy(phase = phase) else it }
        }
    }

    fun chooseCalendar(destination: CalendarDestination) = _calendarExport.update { state ->
        val phase = state?.phase as? CalendarExportPhase.Choosing
        if (phase == null || destination !in phase.calendars) state else state.copy(phase = phase.copy(selected = destination))
    }

    /** Shows the confirmation for the chosen calendar; nothing is written yet. */
    fun reviewCalendarExport() = _calendarExport.update { state ->
        val phase = state?.phase as? CalendarExportPhase.Choosing
        val destination = phase?.selected ?: return@update state
        state.copy(phase = CalendarExportPhase.Confirming(phase.calendars, destination))
    }

    fun chooseAnotherCalendar() = _calendarExport.update { state ->
        val phase = state?.phase as? CalendarExportPhase.Confirming ?: return@update state
        state.copy(phase = CalendarExportPhase.Choosing(phase.calendars, phase.destination))
    }

    /**
     * Writes the captured events to the confirmed calendar, once: the flow leaves confirmation before
     * the write starts, so a second call sends nothing. If the plan changed or may no longer be
     * exported, nothing is written. Returns whether the write started.
     */
    fun confirmCalendarExport(): Boolean {
        val state = _calendarExport.value ?: return false
        val destination = (state.phase as? CalendarExportPhase.Confirming)?.destination ?: return false
        val source = state.source
        val plan = plan(source.planOrdinal)
        val eligible = plan != null && currentExportBlock(plan, ownExport = state.id) == null &&
            CalendarExport.events(plan.current.schedules, plan.ordinal, plan.isDemo) == source.events
        if (!eligible) {
            val outcome = CalendarWriteOutcome.NotStarted(ExportNotStarted.PlanUnavailable)
            _calendarExport.value = state.copy(phase = CalendarExportPhase.Finished(destination, outcome))
            return false
        }
        _calendarExport.value = state.copy(phase = CalendarExportPhase.Writing(destination))
        viewModelScope.launch {
            val outcome = calendarExportRepository.export(destination, source.events)
            _calendarExport.update { if (it?.id == state.id) it.copy(phase = CalendarExportPhase.Finished(destination, outcome)) else it }
        }
        return true
    }

    /** Cancels or dismisses the flow and releases its plan; refused while writing. */
    fun closeCalendarExport() = _calendarExport.update { if (it?.phase is CalendarExportPhase.Writing) it else null }

    /** [plan]'s export block from the current state; [ownExport] is a confirming flow, which does not block itself. */
    private fun currentExportBlock(plan: SessionPlan, ownExport: Int? = null): ExportBlock? =
        exportBlockOf(plan, _storage.value, _generation.value, _adjustment.value, _calendarExport.value, _calendarRead.value, ownExport)

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
                // Nothing was sent; a chosen time keeps its draft but must be confirmed again if needed.
                _adjustment.update { it.copy(state = AdjustmentState.Idle, manual = it.manual?.copy(confirmOutsideAvailability = false)) }
                return@launch
            }
            when (val outcome = adjustmentRepository.adjust(input)) {
                is AdjustmentOutcome.Success -> {
                    // Replace only the captured plan; keep its ordinal, identity and creation request.
                    updatePlan(ordinal) { it.copy(current = outcome.response.copy(scheduleCsvFile = it.current.scheduleCsvFile)) }
                    pendingAdjustment = null
                    val success = AdjustmentState.Success(input.scheduleId, sessionMove(input, outcome.response.schedules))
                    _adjustment.update { it.copy(failedScheduleId = null, state = success, manual = null) }
                    finishMoveSave(commit(_plans.value))
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
                    // An uncertain or gone plan needs a fresh reviewed choice after reload, never a replay.
                    _adjustment.update {
                        it.copy(state = AdjustmentState.Failure(outcome.error), manual = it.manual.takeIf { recovery == PlanRecovery.Ready })
                    }
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
        _checkIn.update { if (it?.save == CheckInSave.Unsaved) it.copy(save = CheckInSave.Saving) else it }
        updateMoveSave(from = MoveSave.Unsaved, to = MoveSave.Saving)
        viewModelScope.launch {
            val saved = commit(_plans.value)
            finishCheckInSave(saved)
            finishMoveSave(saved)
        }
    }

    /** The target row as sent and as accepted; null only if a validated response lacked it. */
    private fun sessionMove(input: AdjustmentInput, returned: List<ScheduleRow>): SessionMove? {
        val before = input.schedules.firstOrNull { it.scheduleId == input.scheduleId } ?: return null
        val after = returned.firstOrNull { it.scheduleId == input.scheduleId } ?: return null
        return SessionMove(before, after)
    }

    /**
     * Settles a shown move whose save was running. Saves never overlap and plan changes wait for
     * them, so a move still saving is the one this save wrote; a cleared result stays cleared.
     */
    private fun finishMoveSave(saved: Boolean) =
        updateMoveSave(from = MoveSave.Saving, to = if (saved) MoveSave.Saved else MoveSave.Unsaved)

    private fun updateMoveSave(from: MoveSave, to: MoveSave) = _adjustment.update { selection ->
        val success = selection.state as? AdjustmentState.Success
        if (success?.save == from) selection.copy(state = success.copy(save = to)) else selection
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
            // A saved notice describes the earlier draft.
            if (_profile.value == ProfileState.Saved) _profile.value = ProfileState.Idle
            _generation.value = GenerationState.Idle
            _generatedPlan.value = null
            lastRequest = null
        }
    }

    private fun editDates(transform: (PlanFormState) -> PlanFormState) {
        val before = _form.value
        val changed = transform(before)
        if (changed == before) return
        edit { changed.copy(busySchedules = emptyList()) }
        if (_form.value == before) return
        calendarReadId += 1
        _calendarRead.value = CalendarReadState.Idle
    }

    private fun editDay(day: Weekday, week: LocalDate?, transform: (DayAvailability) -> DayAvailability) = edit { state ->
        if (week == null) {
            val current = state.availability[day] ?: DayAvailability()
            state.copy(availability = state.availability + (day to transform(current)))
        } else {
            val days = state.availabilityOverrides[week]?.takeIf { week in state.studyWeeks } ?: return@edit state
            val current = days[day] ?: DayAvailability()
            state.copy(availabilityOverrides = state.availabilityOverrides + (week to days + (day to transform(current))))
        }
    }

    companion object {
        /** Uses the flavor's repositories, this build's plan storage scope and the device calendar. */
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
                    calendarExportRepository = AndroidCalendarExportRepository.create(application),
                    calendarReadRepository = AndroidCalendarReadRepository.create(application),
                    availabilityProfileRepository = FlavorRepositories.availabilityProfileRepository(),
                )
            }
        }
    }
}

/** Calendar read phases that hold the device calendar; no export may start during them. */
private val activeCalendarReads = setOf(
    CalendarReadState.RequestingPermission,
    CalendarReadState.AwaitingPermission,
    CalendarReadState.Loading,
)

/**
 * The one export rule for both plan screens, start and confirmation: generation, adjustment,
 * reload, an active calendar read and any export flow other than [ownExport] keep [plan] busy.
 */
private fun exportBlockOf(
    plan: SessionPlan,
    storage: PlanStorage,
    generation: GenerationState,
    adjustment: AdjustmentSelection,
    export: CalendarExportState?,
    read: CalendarReadState,
    ownExport: Int? = null,
): ExportBlock? {
    val operationBusy = generation == GenerationState.Loading ||
        adjustment.state == AdjustmentState.Loading || adjustment.state == AdjustmentState.Reloading ||
        read in activeCalendarReads || (export != null && export.id != ownExport)
    return exportBlock(plan, storage, operationBusy)
}
