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
import com.swpp.licenseplanner.data.AvailabilityWire
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
import com.swpp.licenseplanner.data.scheduleRowProblem
import com.swpp.licenseplanner.data.UnavailableCalendarRead
import com.swpp.licenseplanner.data.model.ScheduleRow
import com.swpp.licenseplanner.data.model.StudyPlanRequest
import com.swpp.licenseplanner.data.model.StudyPlanResponse
import com.swpp.licenseplanner.domain.CalendarConflicts
import com.swpp.licenseplanner.domain.WireDateTime
import com.swpp.licenseplanner.domain.CalendarExport
import com.swpp.licenseplanner.domain.AvailabilityWindowTarget
import com.swpp.licenseplanner.domain.CreationDraft
import com.swpp.licenseplanner.domain.CreationStep
import com.swpp.licenseplanner.domain.CurrentLevel
import com.swpp.licenseplanner.domain.DayAvailability
import com.swpp.licenseplanner.domain.ManualMoveRejection
import com.swpp.licenseplanner.domain.PlanGrouping
import com.swpp.licenseplanner.domain.PlanFormState
import com.swpp.licenseplanner.domain.PlanRecovery
import com.swpp.licenseplanner.domain.SessionPlan
import com.swpp.licenseplanner.domain.StudyAvailability
import com.swpp.licenseplanner.domain.StudyCheckIn
import com.swpp.licenseplanner.domain.StudyCheckInStatus
import com.swpp.licenseplanner.domain.Weekday
import com.swpp.licenseplanner.domain.WindowSpec
import com.swpp.licenseplanner.domain.emptyWeek
import com.swpp.licenseplanner.domain.hasInputBeyondDefaults
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

/** What the learner chose for unfinished creation input. */
enum class DraftChoice { Save, Discard }

/** Where creation continues once the chosen draft change is saved on this device. */
enum class DraftContinuation {
    /** Leave creation for home. */
    Home,
    /** Start a new creation from fresh inputs in place of the saved draft. */
    NewCreation,
}

enum class DraftPhase {
    /** The draft change is being written; nothing is known about its durability yet. */
    Writing,
    /** The write failed; the earlier saved data and the shown inputs stay, and only [PlanFormViewModel.retrySave] or staying may follow. */
    Failed,
    /** The draft change is saved; navigation follows once, then reports it handled. */
    Done,
}

/** A save or discard of the creation draft and how far it has got. */
data class DraftDecision(val choice: DraftChoice, val continuation: DraftContinuation, val phase: DraftPhase)

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
    private val today: LocalDate = LocalDate.now(),
    private val planStore: LocalPlanStore,
    private val calendarExportRepository: CalendarExportRepository = UnavailableCalendarExport,
    private val calendarReadRepository: CalendarReadRepository = UnavailableCalendarRead,
    /** The shared server profile; null in demo, which keeps study times on the device. */
    private val availabilityProfileRepository: AvailabilityProfileRepository? = null,
    /**
     * The device's current date, read whenever a report gate is checked. Separate from [today],
     * which is captured once for the creation form.
     */
    private val currentDate: () -> LocalDate = { LocalDate.now() },
) : ViewModel() {
    private val _form = MutableStateFlow(PlanFormState(preparationStart = today))
    val form: StateFlow<PlanFormState> = _form.asStateFlow()

    private val _questionLoad = MutableStateFlow<QuestionLoadState>(QuestionLoadState.Idle)
    val questionLoad: StateFlow<QuestionLoadState> = _questionLoad.asStateFlow()

    private val _generation = MutableStateFlow<GenerationState>(GenerationState.Idle)
    val generation: StateFlow<GenerationState> = _generation.asStateFlow()

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

    /**
     * The creation draft as last saved on this device, or null. It changes only when a write
     * succeeds (or on restore), so it always matches the stored bytes.
     */
    private val _committedDraft = MutableStateFlow<CreationDraft?>(null)
    val committedDraft: StateFlow<CreationDraft?> = _committedDraft.asStateFlow()

    /**
     * The input screen a saved draft reopens. Progress moves it forward and an actual edit moves it
     * to the edited screen; going back through earlier screens leaves it where it was.
     */
    private val _creationStep = MutableStateFlow(CreationStep.CERTIFICATION)
    val creationStep: StateFlow<CreationStep> = _creationStep.asStateFlow()

    /** The question the assessment shows now. */
    private val _questionIndex = MutableStateFlow(0)
    val questionIndex: StateFlow<Int> = _questionIndex.asStateFlow()

    /** The question a saved draft reopens; follows [_creationStep]'s rules, not going back. */
    private var checkpointQuestion = 0

    /** The open draft save or discard, or null. */
    private val _draftDecision = MutableStateFlow<DraftDecision?>(null)
    val draftDecision: StateFlow<DraftDecision?> = _draftDecision.asStateFlow()

    /** One complete immutable envelope and only the operations included in that write. */
    private data class PendingLocalWrite(
        val snapshot: PlanSnapshot,
        val checkIn: CheckInEditor? = null,
        val move: AdjustmentSelection? = null,
        val draft: DraftDecision? = null,
        val replacementId: Int? = null,
        val replacementOrdinal: Int? = null,
    )

    private var pendingWrite: PendingLocalWrite? = null
    private var committedSnapshot = PlanSnapshot.EMPTY

    private val _calendarConflict = MutableStateFlow<CalendarConflictState?>(null)
    val calendarConflict: StateFlow<CalendarConflictState?> = _calendarConflict.asStateFlow()
    private var nextConflictId = 1
    private var lastGenerationReview: CalendarConflictState? = null

    private val generating get() = _generation.value == GenerationState.Loading
    private val adjusting get() = _adjustment.value.state == AdjustmentState.Loading
    private val reloading get() = _adjustment.value.state == AdjustmentState.Reloading
    private val exporting get() = _calendarExport.value != null
    private val readingCalendar get() = _calendarRead.value in activeCalendarReads

    /**
     * An adjustment or reload is in flight, or a calendar export holds its plan; each must finish
     * on the plan it started from.
     */
    private val busy get() = adjusting || reloading || exporting || readingCalendar || _calendarConflict.value != null

    /** Plans may change only once restored and while nothing is waiting to be saved. */
    private val storageReady get() = _storage.value == PlanStorage.Ready

    /**
     * Each plan's calendar export availability by ordinal (null: it may start now). It applies the
     * same rules as [startCalendarExport] and updates whenever plans, storage, generation,
     * adjustment, reload, an export or a calendar read changes.
     */
    val exportBlocks: StateFlow<Map<Int, ExportBlock?>> = combine(
        _plans, _storage, _generation, _adjustment, combine(_calendarExport, _calendarRead, _calendarConflict) { export, read, conflict -> Triple(export, read, conflict) },
    ) { plans, storage, generation, adjustment, (export, read, conflict) ->
        plans.associate { it.ordinal to exportBlockOf(it, storage, generation, adjustment, export, read, conflictBusy = conflict != null) }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, _plans.value.associate { it.ordinal to currentExportBlock(it) })

    init {
        viewModelScope.launch { restore() }
    }

    fun selectCertification(id: String) = edit(CreationStep.CERTIFICATION) { it.copy(certificationId = id) }
    fun setPreparationStart(date: LocalDate) = editDates { it.copy(preparationStart = date) }
    fun setExamDate(date: LocalDate) = editDates { it.copy(examDate = date) }
    fun setLevel(level: CurrentLevel) = edit(CreationStep.LEVEL) { it.copy(level = level) }

    fun answer(questionId: Int, choiceKey: String) = edit(CreationStep.QUESTIONS, _form.value.questions.indexOfFirst { it.id == questionId }) { state ->
        if (state.questions.none { it.id == questionId }) state
        else state.copy(answers = state.answers + (questionId to choiceKey))
    }

    /*
     * Primary-window setters: they change only the day's primary entry and keep its other windows.
     * The availability screen edits identified list entries through [captureWindow] instead. A null
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

    /** Captures the exact list entry, or an append target, without editing the form. */
    fun captureWindow(day: Weekday, index: Int?, week: LocalDate? = null): AvailabilityWindowTarget? {
        if (!canEditWindow()) return null
        val state = _form.value
        val days = if (week == null) state.availability else {
            if (week !in state.studyWeeks) return null
            state.availabilityOverrides[week] ?: return null
        }
        val windows = days[day]?.windows().orEmpty()
        if (index != null && index !in windows.indices) return null
        return AvailabilityWindowTarget(day, week, windows, index, state.shownWeek(_availabilityWeek.value))
    }

    /** Applies only to the captured scope and list. Overlap stays editable in the form. */
    fun applyWindow(target: AvailabilityWindowTarget, start: LocalTime, durationMinutes: Int): Boolean {
        if (!WindowSpec.isValidDuration(durationMinutes) || start.second != 0 || start.nano != 0 || !windowTargetIsCurrent(target)) return false
        val window = WindowSpec(start, durationMinutes)
        editDay(target.day, target.week) { day ->
            if (target.index == null) day.appendWindow(window) else day.replaceWindow(target.index, window) ?: day
        }
        return true
    }

    /** Removing the last window keeps an explicit replacement week present. */
    fun removeWindow(target: AvailabilityWindowTarget): Boolean {
        val index = target.index ?: return false
        if (!windowTargetIsCurrent(target)) return false
        editDay(target.day, target.week) { it.removeWindow(index) ?: it }
        return true
    }

    private fun canEditWindow(): Boolean =
        storageReady && !generating && !busy && !profileBusy && _draftDecision.value == null

    private fun windowTargetIsCurrent(target: AvailabilityWindowTarget): Boolean =
        captureWindow(target.day, target.index, target.week) == target

    fun showAvailabilityWeek(monday: LocalDate): Boolean {
        if (monday !in _form.value.studyWeeks) return false
        _availabilityWeek.value = monday
        reachCreationStep(CreationStep.AVAILABILITY)
        return true
    }

    /**
     * Records that the learner moved forward to [step]. The resume position only moves forward
     * here; going back through earlier screens never calls this.
     */
    fun reachCreationStep(step: CreationStep) {
        if (step.ordinal > _creationStep.value.ordinal) _creationStep.value = step
    }

    /**
     * Shows question [index] of the loaded set. Moving forward also moves the resume position;
     * going back to earlier questions only changes the shown one. Returns whether it changed.
     */
    fun showQuestion(index: Int): Boolean {
        if (index !in _form.value.questions.indices) return false
        _questionIndex.value = index
        when {
            _creationStep.value.ordinal < CreationStep.QUESTIONS.ordinal -> {
                _creationStep.value = CreationStep.QUESTIONS
                checkpointQuestion = index
            }
            _creationStep.value == CreationStep.QUESTIONS && index > checkpointQuestion -> checkpointQuestion = index
        }
        return true
    }

    /** Gives the week its own times, starting from a copy of the base week with every window. */
    fun replaceWeek(monday: LocalDate): Boolean = editWeek(monday) { it.availability }

    /** Makes the week an explicit no-study week; windows starting the previous day still run into it. */
    fun clearWeek(monday: LocalDate): Boolean = editWeek(monday) { emptyWeek() }

    /** Removes the week's replacement so it inherits the base again. */
    fun resetWeek(monday: LocalDate): Boolean = editWeek(monday) { null }

    private fun editWeek(monday: LocalDate, days: (PlanFormState) -> Map<Weekday, DayAvailability>?): Boolean {
        if (generating || monday !in _form.value.studyWeeks) return false
        edit(CreationStep.AVAILABILITY) { state ->
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
        if (generating || busy || profileBusy) return false
        _profile.value = ProfileState.Loading
        viewModelScope.launch {
            _profile.value = when (val outcome = repository.load()) {
                is ProfileOutcome.Loaded -> ProfileState.Preview(outcome.availability)
                is ProfileOutcome.Failure -> ProfileState.Failure(outcome.error, saving = false)
            }
        }
        return true
    }

    /** Replaces the form's base week and week replacements with the previewed profile, every window included. */
    fun acceptProfile() {
        val preview = _profile.value as? ProfileState.Preview ?: return
        if (generating) return
        edit(CreationStep.AVAILABILITY) { state ->
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
        if (generating || busy || profileBusy || form.availabilityError != null) return false
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
        edit(CreationStep.AVAILABILITY) { it.copy(busySchedules = snapshot.schedules) }
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
        edit(CreationStep.AVAILABILITY) { if (it.busySchedules.isEmpty()) it else it.copy(busySchedules = emptyList()) }
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
                    _questionIndex.value = 0
                    checkpointQuestion = 0
                    reachCreationStep(CreationStep.QUESTIONS)
                }
                is QuestionOutcome.Failure -> _questionLoad.value = QuestionLoadState.Failure(outcome.error)
            }
        }
    }

    /** Starts a fresh calendar check for the complete creation input before any planning request. */
    fun generate(): Boolean {
        if (generating || busy || !storageReady || profileBusy || _draftDecision.value != null) return false
        val form = _form.value
        val request = (RequestMapper.map(form) as? MappingResult.Ready)?.request ?: return false
        openGenerationReview(request, form = form)
        return true
    }

    /** Every deliberate network retry obtains a new calendar observation and decision. */
    fun retryGeneration() {
        if (_generation.value !is GenerationState.Failure || !storageReady) return
        val previous = lastGenerationReview ?: return
        if (previous.kind == ConflictKind.Replacement) {
            if (!regenerationSaveReady() || !reviewSourceCurrent(previous)) return
            if (_calendarConflict.value?.phase != ConflictPhase.Failed) return
            _calendarConflict.value = null
        } else if (busy) return
        openGenerationReview(previous.request, previous.form, previous.source, previous.envelope)
    }

    /** Null only when every accepted local edit is committed and this source can be regenerated. */
    fun regenerationBlockReason(ordinal: Int): String? {
        if (!regenerationSaveReady()) return "먼저 이 기기에 저장해 주세요. 저장하지 못한 변경이 있어요."
        val source = plan(ordinal) ?: return "계획을 찾을 수 없어요."
        if (!source.original.canRegenerate()) return "이 계획의 원래 입력으로 다시 만들 수 없어요. 새 계획을 만들어 주세요."
        val ownExport = _calendarExport.value?.source?.planOrdinal == ordinal &&
            _calendarConflict.value?.kind == ConflictKind.Export &&
            _calendarConflict.value?.phase in setOf(ConflictPhase.Review, ConflictPhase.Details, ConflictPhase.Unchecked)
        if (generating || adjusting || reloading || readingCalendar || profileBusy || _questionLoad.value == QuestionLoadState.Loading ||
            (_calendarConflict.value != null && !ownExport) || (exporting && !ownExport)) return "진행 중인 작업을 마친 뒤 다시 시도해 주세요."
        return null
    }

    private fun regenerationSaveReady(): Boolean = storageReady && pendingWrite == null &&
        _checkIn.value?.save !in setOf(CheckInSave.Saving, CheckInSave.Unsaved) &&
        (_adjustment.value.state as? AdjustmentState.Success)?.save !in setOf(MoveSave.Saving, MoveSave.Unsaved) &&
        _draftDecision.value == null &&
        PlanSnapshot(_plans.value, creationDraft = _committedDraft.value) == committedSnapshot

    /** Leaves an export only after the global save-first gate succeeds. No read or POST yet. */
    fun startRegeneration(ordinal: Int): Boolean {
        if (regenerationBlockReason(ordinal) != null) return false
        val source = plan(ordinal) ?: return false
        val review = generationReview(source.original, source = source, envelope = committedSnapshot) ?: return false
        _calendarExport.value = null
        _calendarConflict.value = review.copy(phase = ConflictPhase.ReplacementConsent)
        return true
    }

    fun confirmRegeneration(): Boolean {
        val review = _calendarConflict.value?.takeIf { it.phase == ConflictPhase.ReplacementConsent } ?: return false
        if (!regenerationSaveReady() || !reviewSourceCurrent(review)) return false
        _calendarConflict.value = review.copy(phase = ConflictPhase.RequestingPermission)
        return true
    }

    private fun generationReview(
        request: StudyPlanRequest,
        form: PlanFormState? = null,
        source: SessionPlan? = null,
        envelope: PlanSnapshot? = null,
    ): CalendarConflictState? {
        val start = WireDateTime.parseOrNull(request.preparationStart)?.toLocalDate() ?: return null
        val exam = WireDateTime.parseOrNull(request.examDate)?.toLocalDate() ?: return null
        val availability = AvailabilityWire.context(request) ?: return null
        return CalendarConflictState(
            nextConflictId++, if (source == null) ConflictKind.Creation else ConflictKind.Replacement,
            request, CalendarReadWindow(start, exam), CalendarConflicts.windows(availability, start, exam),
            ConflictPhase.RequestingPermission, form, source, envelope,
        )
    }

    private fun openGenerationReview(
        request: StudyPlanRequest,
        form: PlanFormState? = null,
        source: SessionPlan? = null,
        envelope: PlanSnapshot? = null,
    ) {
        if (source == null) _generation.value = GenerationState.Idle
        _calendarConflict.value = generationReview(request, form, source, envelope)
    }

    fun conflictPermissionPrompted(): Boolean {
        val review = _calendarConflict.value?.takeIf { it.phase == ConflictPhase.RequestingPermission } ?: return false
        _calendarConflict.value = review.copy(phase = ConflictPhase.AwaitingPermission)
        return true
    }

    fun onConflictPermission(granted: Boolean, operationId: Int? = _calendarConflict.value?.id) {
        val review = _calendarConflict.value?.takeIf {
            it.phase in setOf(ConflictPhase.RequestingPermission, ConflictPhase.AwaitingPermission)
        } ?: return
        if (review.id != operationId) return
        if (!reviewSourceCurrent(review)) return cancelCalendarConflict()
        if (granted) readConflict(review, final = false)
        else _calendarConflict.value = review.copy(phase = ConflictPhase.Unchecked, outcome = CalendarReadOutcome.PermissionMissing)
    }

    fun retryCalendarConflict() {
        val review = _calendarConflict.value?.takeIf { it.phase == ConflictPhase.Unchecked } ?: return
        if (!reviewSourceCurrent(review)) return cancelCalendarConflict()
        _calendarConflict.value = review.copy(phase = ConflictPhase.RequestingPermission, outcome = null)
    }

    fun showConflictDetails() {
        _calendarConflict.update { if (it?.phase == ConflictPhase.Review) it.copy(phase = ConflictPhase.Details) else it }
    }

    fun showConflictSummary() {
        _calendarConflict.update { if (it?.phase == ConflictPhase.Details) it.copy(phase = ConflictPhase.Review) else it }
    }

    /** A review/edit dismissal has no continuation and cannot dispatch. */
    fun cancelCalendarConflict() {
        val review = _calendarConflict.value ?: return
        if (review.phase in setOf(ConflictPhase.Generating, ConflictPhase.Saving)) return
        _calendarConflict.value = null
        if (review.kind == ConflictKind.Export) _calendarExport.value = null
    }

    /** Consent belongs to this observation. A final read either dispatches once or replaces review. */
    fun continueCalendarConflict(unchecked: Boolean = false): Boolean {
        val review = _calendarConflict.value ?: return false
        if (review.phase != if (unchecked) ConflictPhase.Unchecked else ConflictPhase.Review) return false
        if (review.kind == ConflictKind.Export && review.outcome == CalendarReadOutcome.PermissionMissing) return false
        if (!reviewSourceCurrent(review) || (review.kind == ConflictKind.Replacement && !regenerationSaveReady())) return false
        if (review.kind == ConflictKind.Export) {
            val export = _calendarExport.value?.takeIf { it.id == review.exportId } ?: return false
            _calendarExport.value = export.copy(phase = review.exportConfirmation ?: return false, checked = review.outcome, unchecked = unchecked)
            _calendarConflict.value = null
        } else readConflict(review, final = true, unchecked = unchecked)
        return true
    }

    private fun reviewSourceCurrent(review: CalendarConflictState): Boolean = when (review.kind) {
        ConflictKind.Creation -> review.form == _form.value
        ConflictKind.Replacement -> review.source == review.source?.ordinal?.let(::plan) &&
            review.envelope == committedSnapshot && regenerationSaveReady()
        ConflictKind.Export -> review.source == review.source?.ordinal?.let(::plan) &&
            _calendarExport.value?.id == review.exportId
    }

    private fun readConflict(review: CalendarConflictState, final: Boolean, unchecked: Boolean = false) {
        if (!reviewSourceCurrent(review)) return
        _calendarConflict.value = review.copy(phase = ConflictPhase.Reading)
        viewModelScope.launch {
            val outcome = calendarReadRepository.read(review.window)
            if (_calendarConflict.value?.id != review.id || !reviewSourceCurrent(review)) return@launch
            val snapshot = (outcome as? CalendarReadOutcome.Loaded)?.snapshot
            val updated = review.copy(
                outcome = outcome,
                conflicts = CalendarConflicts.compare(review.targets, snapshot?.occurrences.orEmpty()),
                phase = if (snapshot == null) ConflictPhase.Unchecked else ConflictPhase.Review,
            )
            _calendarConflict.value = updated
            if (final) {
                if (outcome != review.outcome) return@launch
                if (snapshot == null && !unchecked) return@launch
                if (review.kind == ConflictKind.Export) writeCalendarExport(updated)
                else sendReviewed(updated, unchecked)
            } else if (snapshot != null && updated.conflicts.matches.isEmpty()) {
                if (review.kind == ConflictKind.Export) continueCalendarConflict()
                else readConflict(updated, final = true)
            }
        }
    }

    private fun sendReviewed(review: CalendarConflictState, unchecked: Boolean) {
        if (!reviewSourceCurrent(review) || !storageReady) return
        val request = review.request.copy(busySchedules = if (unchecked) review.request.busySchedules else review.snapshot!!.schedules)
        lastGenerationReview = review.copy(request = request)
        if (review.kind == ConflictKind.Creation) {
            reachCreationStep(CreationStep.AVAILABILITY)
            _calendarConflict.value = null
        } else _calendarConflict.value = review.copy(phase = ConflictPhase.Generating)
        _generation.value = GenerationState.Loading
        viewModelScope.launch {
            when (val outcome = planningRepository.generate(request)) {
                is PlanOutcome.Success -> {
                    if (review.kind == ConflictKind.Replacement) {
                        if (!reviewSourceCurrent(review) || outcome.isDemo != review.source!!.isDemo ||
                            scheduleRowProblem(outcome.response.schedules) != null ||
                            (!outcome.isDemo && (outcome.response.scheduleCsvFile.isNullOrBlank() ||
                                outcome.response.scheduleCsvFile == review.source.scheduleCsvFile))) {
                            _generation.value = GenerationState.Failure(ApiError.InvalidResponse(null))
                            _calendarConflict.update { it?.copy(phase = ConflictPhase.Failed) }
                            return@launch
                        }
                        val replacement = SessionPlan.register(review.source.ordinal, request, outcome.response, review.source.isDemo)
                        val candidate = review.envelope!!.copy(plans = review.envelope.plans.map {
                            if (it.ordinal == replacement.ordinal) replacement else it
                        })
                        _calendarConflict.update { it?.copy(phase = ConflictPhase.Saving) }
                        write(PendingLocalWrite(candidate, replacementId = review.id, replacementOrdinal = review.source.ordinal))
                        _generation.value = GenerationState.Idle
                    } else {
                        val plan = SessionPlan.register(nextOrdinal++, request, outcome.response, outcome.isDemo)
                        _plans.update { it + plan }
                        _generatedPlan.value = plan.ordinal
                        _generation.value = GenerationState.Success(outcome.response, outcome.isDemo)
                        completeCreation(PlanSnapshot(_plans.value, creationDraft = null))
                    }
                }
                is PlanOutcome.DemoShortfall -> {
                    _generation.value = GenerationState.DemoShortfall(outcome.required, outcome.available)
                    if (review.kind == ConflictKind.Replacement) _calendarConflict.update { it?.copy(phase = ConflictPhase.Failed) }
                }
                is PlanOutcome.Failure -> {
                    _generation.value = GenerationState.Failure(outcome.error)
                    if (review.kind == ConflictKind.Replacement) _calendarConflict.update { it?.copy(phase = ConflictPhase.Failed) }
                }
            }
        }
    }

    private fun finishReplacement(id: Int, saved: Boolean) {
        _calendarConflict.update { if (it?.id == id) it.copy(phase = if (saved) ConflictPhase.Replaced else ConflictPhase.Unsaved) else it }
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
        if (_adjustment.value.state !is AdjustmentState.Failure || generating || busy || !storageReady) return
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
     * Whether study row [scheduleId] of plan [ordinal] may be reported: it already has a report,
     * or its scheduled start date is on or before the current date (by date, not time of day).
     * External and unknown rows are false. The current date is read on every call.
     */
    fun canReport(ordinal: Int, scheduleId: String): Boolean {
        val plan = plan(ordinal)?.takeIf { it.isStudy(scheduleId) } ?: return false
        if (plan.checkInFor(scheduleId) != null) return true
        val session = PlanGrouping.sessions(plan.current.schedules).firstOrNull { it.scheduleId == scheduleId } ?: return false
        return !session.start.toLocalDate().isAfter(currentDate())
    }

    /**
     * Opens the report editor for study row [scheduleId] of plan [ordinal], prefilled with its saved
     * report or with no status for an unreported row. External rows, unknown rows, rows that
     * [canReport] refuses (an unreported session on a later date) and plans that are being changed
     * or not yet saved are refused. Opening writes nothing and leaves the adjustment selection and
     * recovery state alone; a plan awaiting reload or missing may still be reported.
     */
    fun startCheckIn(ordinal: Int, scheduleId: String): Boolean {
        if (generating || busy || !storageReady || _checkIn.value?.save == CheckInSave.Saving) return false
        if (!canReport(ordinal, scheduleId)) return false
        val plan = plan(ordinal) ?: return false
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
        val candidate = _plans.value
        val participant = _checkIn.value
        _storage.value = PlanStorage.Saving
        viewModelScope.launch { commit(candidate, checkIn = participant) }
        return true
    }

    /**
     * Closes the editor. Before saving this discards the edit and writes nothing; after a save
     * (confirmed or failed) the shown collection stays as it is. Refused while saving.
     */
    fun closeCheckIn() = _checkIn.update { if (it?.save == CheckInSave.Saving) it else null }

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
        _calendarExport.value = CalendarExportState(nextExportId++, ExportSource(plan.ordinal, plan.isDemo, events.orEmpty(), plan), phase)
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

    /** Reads all current study dates before the copy confirmation. */
    fun reviewCalendarExport() {
        val export = _calendarExport.value ?: return
        val choosing = export.phase as? CalendarExportPhase.Choosing ?: return
        val destination = choosing.selected ?: return
        val confirmation = CalendarExportPhase.Confirming(choosing.calendars, destination)
        val review = exportReview(export, confirmation) ?: return
        _calendarExport.value = export.copy(phase = CalendarExportPhase.Checking)
        readConflict(review, final = false)
    }

    private fun exportReview(export: CalendarExportState, confirmation: CalendarExportPhase.Confirming): CalendarConflictState? {
        val source = export.source.capturedPlan ?: return null
        val targets = CalendarConflicts.sessions(source.current.schedules)
        val start = targets.minOfOrNull { it.interval.start.toLocalDate() } ?: return null
        val end = targets.maxOf { it.interval.end.toLocalDate() }.plusDays(1)
        return CalendarConflictState(
            nextConflictId++, ConflictKind.Export, source.original, CalendarReadWindow(start, end), targets,
            ConflictPhase.Reading, source = source, outcome = export.checked,
            exportConfirmation = confirmation, exportId = export.id,
        )
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
        val confirmation = state.phase
        val review = exportReview(state, confirmation) ?: return false
        _calendarExport.value = state.copy(phase = CalendarExportPhase.Checking)
        readConflict(review, final = true, unchecked = state.unchecked)
        return true
    }

    private fun writeCalendarExport(review: CalendarConflictState) {
        val export = _calendarExport.value?.takeIf { it.id == review.exportId } ?: return
        val source = plan(export.source.planOrdinal) ?: return
        val destination = review.exportConfirmation?.destination ?: return
        if (source != export.source.capturedPlan ||
            exportBlockOf(source, _storage.value, _generation.value, _adjustment.value, export, _calendarRead.value, export.id) != null) return
        if (review.outcome == CalendarReadOutcome.PermissionMissing) return
        _calendarConflict.value = null
        _calendarExport.value = export.copy(phase = CalendarExportPhase.Writing(destination))
        viewModelScope.launch {
            val outcome = calendarExportRepository.export(destination, export.source.events)
            _calendarExport.update { if (it?.id == export.id) it.copy(phase = CalendarExportPhase.Finished(destination, outcome)) else it }
        }
    }

    /** Cancels or dismisses the flow and releases its plan; refused while writing. */
    fun closeCalendarExport() {
        if (_calendarExport.value?.phase is CalendarExportPhase.Writing || _calendarConflict.value?.phase in setOf(ConflictPhase.Generating, ConflictPhase.Saving)) return
        if (_calendarConflict.value?.kind == ConflictKind.Export) _calendarConflict.value = null
        _calendarExport.value = null
    }

    /** [plan]'s export block from the current state; [ownExport] is a confirming flow, which does not block itself. */
    private fun currentExportBlock(plan: SessionPlan, ownExport: Int? = null): ExportBlock? =
        exportBlockOf(plan, _storage.value, _generation.value, _adjustment.value, _calendarExport.value, _calendarRead.value, ownExport, _calendarConflict.value != null)

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
                    commit(_plans.value, move = _adjustment.value)
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

    /**
     * Saves the shown collection again after a failed save; never repeats a request. After a failed
     * draft save or discard it writes that same snapshot again and continues it once.
     */
    fun retrySave() {
        if (_storage.value != PlanStorage.SaveFailure) return
        val pending = pendingWrite ?: return
        _storage.value = PlanStorage.Saving
        settleParticipants(pending, saved = null)
        pending.replacementId?.let { id ->
            _calendarConflict.update { if (it?.id == id) it.copy(phase = ConflictPhase.Saving) else it }
        }
        viewModelScope.launch { performWrite(pending, retry = true) }
    }

    /**
     * Whether leaving creation for home needs the save, discard or stay decision: any input beyond
     * a fresh creation's defaults does. Untouched defaults leave directly.
     */
    fun creationExitNeedsDecision(): Boolean = _form.value.hasInputBeyondDefaults(today)

    /**
     * Saves the current inputs and resume position as this device's only creation draft, then
     * leaves for home once it is saved. Saving what is already saved writes nothing. Refused while
     * anything that changes the inputs or the stored collection runs. Returns whether it started.
     */
    fun saveDraftAndExit(): Boolean {
        if (!canDecideDraft()) return false
        val draft = CreationDraft(_form.value, _creationStep.value, checkpointQuestion, _availabilityWeek.value)
        if (!draft.isValid) return false
        val decision = DraftDecision(DraftChoice.Save, DraftContinuation.Home, DraftPhase.Writing)
        if (draft == _committedDraft.value) {
            _draftDecision.value = decision.copy(phase = DraftPhase.Done)
            return true
        }
        writeDraft(PlanSnapshot(_savedPlans.value, creationDraft = draft), decision)
        return true
    }

    /**
     * Removes the saved draft and, once that is saved, resets the inputs, questions, shown
     * question and week to a fresh creation; saved plans and their reports stay. Then continues
     * to [continuation]. With no saved draft nothing is written. Returns whether it started.
     */
    fun discardDraft(continuation: DraftContinuation = DraftContinuation.Home): Boolean {
        if (!canDecideDraft()) return false
        val decision = DraftDecision(DraftChoice.Discard, continuation, DraftPhase.Writing)
        if (_committedDraft.value == null) {
            resetCreation()
            _draftDecision.value = decision.copy(phase = DraftPhase.Done)
            return true
        }
        writeDraft(PlanSnapshot(_savedPlans.value, creationDraft = null), decision)
        return true
    }

    /**
     * Keeps creation open with its inputs. After a failed draft write this drops that write; the
     * stored collection is unchanged, so plan changes may continue. Refused while writing.
     */
    fun stayInCreation(): Boolean {
        val decision = _draftDecision.value ?: return true
        if (decision.phase != DraftPhase.Failed) return false
        pendingWrite = null
        _draftDecision.value = null
        _storage.value = PlanStorage.Ready
        return true
    }

    /** Reports that navigation followed a saved draft decision, so it is not followed again. */
    fun draftDecisionHandled() {
        if (_draftDecision.value?.phase == DraftPhase.Done) _draftDecision.value = null
    }

    /**
     * Reopens the saved draft as the current inputs, at its screen, question and week (or the first
     * study week when that week no longer is one). Nothing is sent and the loaded questions are kept.
     * Returns the screen to open, or null when there is nothing to resume or something still runs.
     */
    fun resumeDraft(): CreationStep? {
        val draft = _committedDraft.value ?: return null
        if (!canDecideDraft()) return null
        _form.value = draft.form
        _questionLoad.value = if (draft.form.questions.isEmpty()) QuestionLoadState.Idle else QuestionLoadState.Loaded
        _creationStep.value = draft.step
        checkpointQuestion = draft.questionIndex
        _questionIndex.value = draft.questionIndex
        // No saved week still means the first; a week that is no longer selectable falls back to it.
        _availabilityWeek.value = draft.availabilityWeek?.let { draft.shownWeek }
        clearCreationResults()
        // Busy rows were imported with consent before saving; their per-kind counts were not kept.
        _calendarRead.value = if (draft.form.busySchedules.isEmpty()) {
            CalendarReadState.Idle
        } else {
            CalendarReadState.Loaded(draft.form.busySchedules.size, 0, 0)
        }
        return draft.step
    }

    /**
     * A draft decision or resume waits for the stored collection, any generation, adjustment,
     * reload, question load, calendar read or profile request, and any other draft decision.
     */
    private fun canDecideDraft(): Boolean =
        storageReady && !generating && !busy && !profileBusy &&
            _questionLoad.value != QuestionLoadState.Loading && _draftDecision.value == null

    private fun writeDraft(snapshot: PlanSnapshot, decision: DraftDecision) {
        val pending = PendingLocalWrite(snapshot, draft = decision)
        pendingWrite = pending
        _draftDecision.value = decision.copy(phase = DraftPhase.Writing)
        _storage.value = PlanStorage.Saving
        pending.replacementId?.let { id ->
            _calendarConflict.update { if (it?.id == id) it.copy(phase = ConflictPhase.Saving) else it }
        }
        viewModelScope.launch { performWrite(pending) }
    }

    /** Fresh creation inputs; saved plans, their reports and the stored draft are untouched. */
    private fun resetCreation() {
        _form.value = PlanFormState(preparationStart = today)
        _questionLoad.value = QuestionLoadState.Idle
        _creationStep.value = CreationStep.CERTIFICATION
        checkpointQuestion = 0
        _questionIndex.value = 0
        _availabilityWeek.value = null
        clearCreationResults()
        _calendarRead.value = CalendarReadState.Idle
    }

    /** Drops results and notices that describe earlier inputs; never a stored plan. */
    private fun clearCreationResults() {
        _generation.value = GenerationState.Idle
        _generatedPlan.value = null
        calendarReadId += 1
        _profile.value = ProfileState.Idle
    }

    /** The target row as sent and as accepted; null only if a validated response lacked it. */
    private fun sessionMove(input: AdjustmentInput, returned: List<ScheduleRow>): SessionMove? {
        val before = input.schedules.firstOrNull { it.scheduleId == input.scheduleId } ?: return null
        val after = returned.firstOrNull { it.scheduleId == input.scheduleId } ?: return null
        return SessionMove(before, after)
    }

    private suspend fun restore() {
        when (val result = planStore.load()) {
            is PlanStoreLoad.Loaded -> {
                committedSnapshot = result.snapshot
                _plans.value = result.snapshot.plans
                _savedPlans.value = result.snapshot.plans
                nextOrdinal = result.snapshot.nextOrdinal
                _committedDraft.value = result.snapshot.creationDraft
                _storage.value = PlanStorage.Ready
            }
            is PlanStoreLoad.Failed -> _storage.value = PlanStorage.LoadFailure
        }
    }

    /**
     * Saves [plans] as the whole collection with the saved draft unchanged; [inFlightAdjustment]
     * marks a live request about to be sent. Callers hold the operation locks, so saves never
     * overlap. Returns whether it was saved.
     */
    private suspend fun commit(
        plans: List<SessionPlan>,
        inFlightAdjustment: Int? = null,
        checkIn: CheckInEditor? = null,
        move: AdjustmentSelection? = null,
    ): Boolean = write(PendingLocalWrite(PlanSnapshot(plans, inFlightAdjustment, _committedDraft.value), checkIn, move))

    private suspend fun completeCreation(snapshot: PlanSnapshot): Boolean = write(PendingLocalWrite(snapshot))

    private suspend fun write(pending: PendingLocalWrite): Boolean {
        pendingWrite = pending
        return performWrite(pending)
    }

    private fun settleParticipants(pending: PendingLocalWrite, saved: Boolean?) {
        pending.checkIn?.let { captured ->
            _checkIn.update { current ->
                if (current?.copy(save = captured.save) == captured) {
                    current.copy(save = when (saved) {
                        null -> CheckInSave.Saving
                        true -> CheckInSave.Saved
                        false -> CheckInSave.Unsaved
                    })
                } else current
            }
        }
        pending.move?.let { captured ->
            _adjustment.update { current ->
                val result = current.state as? AdjustmentState.Success
                val owner = captured.state as? AdjustmentState.Success
                if (result != null && owner != null && result.move === owner.move &&
                    current.copy(state = owner) == captured) {
                    current.copy(state = result.copy(save = when (saved) {
                        null -> MoveSave.Saving
                        true -> MoveSave.Saved
                        false -> MoveSave.Unsaved
                    }))
                } else current
            }
        }
        pending.draft?.let { captured ->
            if (_draftDecision.value?.copy(phase = captured.phase) == captured) {
                _draftDecision.value = captured.copy(phase = when (saved) {
                    null -> DraftPhase.Writing
                    true -> DraftPhase.Done
                    false -> DraftPhase.Failed
                })
            }
        }
    }

    private suspend fun performWrite(pending: PendingLocalWrite, retry: Boolean = false): Boolean {
        _storage.value = PlanStorage.Saving
        settleParticipants(pending, saved = null)
        val saved = planStore.save(pending.snapshot)
        if (saved) {
            val restoredPreflight = retry && pending.snapshot.inFlightAdjustmentOrdinal != null
            committedSnapshot = if (restoredPreflight) pending.snapshot.copy(inFlightAdjustmentOrdinal = null) else pending.snapshot
            _savedPlans.value = pending.snapshot.plans
            if (restoredPreflight) _plans.value = pending.snapshot.plans
            _committedDraft.value = pending.snapshot.creationDraft
            if (pending.replacementId != null) {
                _plans.value = pending.snapshot.plans
                if (_adjustment.value.planOrdinal == pending.replacementOrdinal) {
                    _adjustment.value = AdjustmentSelection(planOrdinal = pending.replacementOrdinal)
                    pendingAdjustment = null
                }
                if (_checkIn.value?.planOrdinal == pending.replacementOrdinal) _checkIn.value = null
            }
            if (pending.draft?.choice == DraftChoice.Discard) resetCreation()
        }
        settleParticipants(pending, saved)
        pending.replacementId?.let { finishReplacement(it, saved) }
        if (saved && pendingWrite === pending) pendingWrite = null
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

    /**
     * Form edits are ignored during generation and clear any earlier result, never the stored plans.
     * A change made on [step] moves the resume position there ([question] for an answer).
     */
    private fun edit(step: CreationStep, question: Int = checkpointQuestion, transform: (PlanFormState) -> PlanFormState) {
        if (generating || _calendarConflict.value?.kind == ConflictKind.Replacement) return
        val before = _form.value
        _form.update(transform)
        if (_form.value != before) {
            if (_calendarConflict.value?.kind == ConflictKind.Creation) _calendarConflict.value = null
            _creationStep.value = step
            if (step == CreationStep.QUESTIONS) checkpointQuestion = question
            // A saved notice describes the earlier draft.
            if (_profile.value == ProfileState.Saved) _profile.value = ProfileState.Idle
            _generation.value = GenerationState.Idle
            _generatedPlan.value = null
            }
    }

    private fun editDates(transform: (PlanFormState) -> PlanFormState) {
        val before = _form.value
        val changed = transform(before)
        if (changed == before) return
        edit(CreationStep.DATES) { changed.copy(busySchedules = emptyList()) }
        if (_form.value == before) return
        calendarReadId += 1
        _calendarRead.value = CalendarReadState.Idle
    }

    private fun editDay(day: Weekday, week: LocalDate?, transform: (DayAvailability) -> DayAvailability) = edit(CreationStep.AVAILABILITY) { state ->
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
    conflictBusy: Boolean = false,
): ExportBlock? {
    val operationBusy = conflictBusy || generation == GenerationState.Loading ||
        adjustment.state == AdjustmentState.Loading || adjustment.state == AdjustmentState.Reloading ||
        read in activeCalendarReads || (export != null && export.id != ownExport)
    return exportBlock(plan, storage, operationBusy)
}
