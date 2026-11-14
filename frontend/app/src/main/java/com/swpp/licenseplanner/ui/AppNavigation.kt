package com.swpp.licenseplanner.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.swpp.licenseplanner.data.CalendarAccess
import com.swpp.licenseplanner.domain.CalendarExport
import com.swpp.licenseplanner.domain.CreationStep
import com.swpp.licenseplanner.domain.SessionPlan
import com.swpp.licenseplanner.ui.components.CalendarExportDialog
import com.swpp.licenseplanner.ui.components.CreationExitDialog
import com.swpp.licenseplanner.ui.screens.AvailabilityActions
import com.swpp.licenseplanner.ui.screens.CheckInRows
import com.swpp.licenseplanner.ui.screens.AvailabilityScreen
import com.swpp.licenseplanner.ui.screens.CertificationScreen
import com.swpp.licenseplanner.ui.screens.DatesScreen
import com.swpp.licenseplanner.ui.screens.ExamDateEditScreen
import com.swpp.licenseplanner.ui.screens.GenerateScreen
import com.swpp.licenseplanner.ui.screens.HomeScreen
import com.swpp.licenseplanner.ui.screens.LevelScreen
import com.swpp.licenseplanner.ui.screens.PlanAdjustmentScreen
import com.swpp.licenseplanner.ui.screens.PlanListScreen
import com.swpp.licenseplanner.ui.screens.PlanScreen
import com.swpp.licenseplanner.ui.screens.ProfileActions
import com.swpp.licenseplanner.ui.screens.QuestionsScreen
import com.swpp.licenseplanner.ui.screens.ResultsScreen
import com.swpp.licenseplanner.ui.screens.ShortfallScreen
import com.swpp.licenseplanner.ui.screens.StudyCheckInScreen

/** One route per step, plus home, plan list and adjustment. */
private object Routes {
    const val HOME = "home"
    const val PLAN_LIST = "plan_list"
    const val ADJUST = "adjust"
    const val CERTIFICATION = "certification"
    const val DATES = "dates"
    const val LEVEL = "level"
    const val QUESTIONS = "questions"
    const val RESULTS = "results"
    const val AVAILABILITY = "availability"
    const val GENERATE = "generate"
    const val PLAN = "plan"
    const val SHORTFALL = "shortfall"
    const val EDIT_EXAM_DATE = "edit-exam-date"
    const val CHECK_IN = "check-in"
}

private fun NavHostController.go(route: String) = navigate(route) { launchSingleTop = true }

/** Replaces the transient generate step with its result, so back returns to availability. */
private fun NavHostController.showResult(route: String) = navigate(route) {
    popUpTo(Routes.GENERATE) { inclusive = true }
    launchSingleTop = true
}

/** Home and list sit at the bottom of the stack, so back from either flow returns home. */
private fun NavHostController.fromHome(route: String) = navigate(route) {
    popUpTo(Routes.HOME)
    launchSingleTop = true
}

/** Rebuild only ordinary input destinations; requests and result routes never resume. */
private fun NavHostController.resumeCreation(step: CreationStep) {
    fromHome(Routes.CERTIFICATION)
    val routes = listOf(Routes.CERTIFICATION, Routes.DATES, Routes.LEVEL, Routes.QUESTIONS, Routes.RESULTS, Routes.AVAILABILITY)
    for (route in routes.drop(1).take(step.ordinal)) go(route)
}

private fun openAppSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        // The dialog already says where to allow access.
    }
}

/**
 * Report entries for [plan]'s study rows: the shown report, whether it differs from the saved
 * collection, and the action that opens the editor on its own route above the originating screen.
 */
private fun checkInRows(
    viewModel: PlanFormViewModel,
    nav: NavHostController,
    plan: SessionPlan,
    plans: List<SessionPlan>,
    savedPlans: List<SessionPlan>,
    storage: PlanStorage,
) = CheckInRows(
    reportFor = { plan.checkInFor(it) },
    isUnsaved = { isCheckInUnsaved(plans, savedPlans, plan.ordinal, it) },
    enabled = storage == PlanStorage.Ready,
    onOpen = { scheduleId -> if (viewModel.startCheckIn(plan.ordinal, scheduleId)) nav.go(Routes.CHECK_IN) },
)

/** Study sessions a plan's export would add. */
private fun exportCount(plan: SessionPlan) = CalendarExport.studyRows(plan.current.schedules).size

/**
 * The whole app flow; [viewModel] is activity-scoped, so state survives back and rotation, and
 * its plans are restored from and saved on the device. The calendar export dialog sits above
 * every screen; the permission prompt is shown only after an explicit export, once per request.
 */
@Composable
fun AppNavigation(viewModel: PlanFormViewModel) {
    val nav = rememberNavController()
    val form by viewModel.form.collectAsStateWithLifecycle()
    val questionLoad by viewModel.questionLoad.collectAsStateWithLifecycle()
    val storage by viewModel.storage.collectAsStateWithLifecycle()
    val calendarExport by viewModel.calendarExport.collectAsStateWithLifecycle()
    val calendarRead by viewModel.calendarRead.collectAsStateWithLifecycle()
    val exportBlocks by viewModel.exportBlocks.collectAsStateWithLifecycle()
    val committedDraft by viewModel.committedDraft.collectAsStateWithLifecycle()
    val draftDecision by viewModel.draftDecision.collectAsStateWithLifecycle()
    // A draft write leaves the plan collection unchanged and owns its feedback in the dialog.
    val planStorage = if (draftDecision == null) storage else PlanStorage.Ready
    val questionIndex by viewModel.questionIndex.collectAsStateWithLifecycle()
    var draftPrompt by rememberSaveable { mutableStateOf<String?>(null) }
    var draftWaiting by rememberSaveable { mutableStateOf(false) }
    var creationWaiting by rememberSaveable { mutableStateOf(false) }

    fun resumeDraft() {
        val step = viewModel.resumeDraft()
        if (step == null) draftWaiting = true
        else {
            draftPrompt = null
            draftWaiting = false
            nav.resumeCreation(step)
        }
    }
    fun createPlan() {
        draftWaiting = false
        if (committedDraft != null) draftPrompt = "new"
        else if (!viewModel.discardDraft(DraftContinuation.NewCreation)) creationWaiting = true
    }
    fun leaveCreation() {
        if (viewModel.creationExitNeedsDecision()) {
            draftWaiting = false
            draftPrompt = "exit"
        } else nav.popBackStack()
    }
    LaunchedEffect(draftDecision) {
        val decision = draftDecision ?: return@LaunchedEffect
        if (decision.phase != DraftPhase.Done) return@LaunchedEffect
        draftPrompt = null
        draftWaiting = false
        if (decision.continuation == DraftContinuation.NewCreation) nav.fromHome(Routes.CERTIFICATION)
        else nav.popBackStack(Routes.HOME, inclusive = false)
        viewModel.draftDecisionHandled()
    }
    if (draftPrompt != null || draftDecision != null) {
        val startingNew = draftPrompt == "new" || draftDecision?.continuation == DraftContinuation.NewCreation
        CreationExitDialog(
            startingNew = startingNew,
            decision = draftDecision,
            waiting = draftWaiting,
            onSave = { draftWaiting = !viewModel.saveDraftAndExit() },
            onDiscard = { draftWaiting = !viewModel.discardDraft(if (startingNew) DraftContinuation.NewCreation else DraftContinuation.Home) },
            onResume = ::resumeDraft,
            onRetry = viewModel::retrySave,
            onStay = {
                if (viewModel.stayInCreation()) {
                    draftPrompt = null
                    draftWaiting = false
                }
            },
        )
    }
    if (creationWaiting) {
        AlertDialog(
            onDismissRequest = { creationWaiting = false },
            text = { Text("진행 중인 작업이 끝나거나 권한 창을 닫은 뒤 다시 선택해 주세요.") },
            confirmButton = { TextButton(onClick = { creationWaiting = false }) { Text("확인") } },
        )
    }

    val context = LocalContext.current
    // The answer arrives here also after rotation; access is read again rather than taken from the result.
    val exportPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        viewModel.onCalendarPermission(CalendarAccess.EXPORT.isGranted(context))
    }
    val readPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        viewModel.onCalendarReadPermission(CalendarAccess.READ.isGranted(context))
    }
    val requestingPermission = calendarExport?.phase == CalendarExportPhase.RequestingPermission
    LaunchedEffect(requestingPermission) {
        if (!requestingPermission) return@LaunchedEffect
        if (CalendarAccess.EXPORT.isGranted(context)) viewModel.onCalendarPermission(true)
        else if (viewModel.calendarPermissionPrompted()) exportPermissionLauncher.launch(CalendarAccess.EXPORT.permissions.toTypedArray())
    }
    val requestingReadPermission = calendarRead == CalendarReadState.RequestingPermission
    LaunchedEffect(requestingReadPermission) {
        if (!requestingReadPermission) return@LaunchedEffect
        if (CalendarAccess.READ.isGranted(context)) viewModel.onCalendarReadPermission(true)
        else if (viewModel.calendarReadPermissionPrompted()) readPermissionLauncher.launch(CalendarAccess.READ.permissions.single())
    }
    calendarExport?.let { state ->
        CalendarExportDialog(
            state = state,
            onChoose = viewModel::chooseCalendar,
            onReview = viewModel::reviewCalendarExport,
            onChooseAnother = viewModel::chooseAnotherCalendar,
            onConfirm = { viewModel.confirmCalendarExport() },
            onOpenSettings = { openAppSettings(context) },
            onClose = viewModel::closeCalendarExport,
        )
    }

    NavHost(navController = nav, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            val plans by viewModel.plans.collectAsStateWithLifecycle()
            val savedPlans by viewModel.savedPlans.collectAsStateWithLifecycle()
            HomeScreen(
                plans = plans,
                savedPlans = savedPlans,
                storage = planStorage,
                hasDraft = committedDraft != null,
                onResume = ::resumeDraft,
                onCreate = ::createPlan,
                onAdjust = { nav.fromHome(Routes.PLAN_LIST) },
                onRetryLoad = viewModel::retryLoad,
                onRetrySave = viewModel::retrySave,
            )
        }
        composable(Routes.PLAN_LIST) {
            val plans by viewModel.plans.collectAsStateWithLifecycle()
            val savedPlans by viewModel.savedPlans.collectAsStateWithLifecycle()
            PlanListScreen(
                plans = plans,
                savedPlans = savedPlans,
                storage = planStorage,
                onOpen = { ordinal -> if (viewModel.selectPlan(ordinal)) nav.go(Routes.ADJUST) },
                onCreate = ::createPlan,
                onBack = { nav.popBackStack() },
                onRetryLoad = viewModel::retryLoad,
                onRetrySave = viewModel::retrySave,
            )
        }
        composable(Routes.ADJUST) {
            val plans by viewModel.plans.collectAsStateWithLifecycle()
            val savedPlans by viewModel.savedPlans.collectAsStateWithLifecycle()
            val selection by viewModel.adjustment.collectAsStateWithLifecycle()
            val plan = plans.firstOrNull { it.ordinal == selection.planOrdinal }
            if (plan == null) {
                LaunchedEffect(Unit) { nav.popBackStack() }
            } else {
                PlanAdjustmentScreen(
                    plan = plan,
                    selection = selection,
                    storage = planStorage,
                    onSelect = viewModel::selectFailedSession,
                    onSubmit = { viewModel.submitAdjustment() },
                    onRetry = viewModel::retryAdjustment,
                    onReload = { viewModel.reloadPlan() },
                    onBack = { nav.popBackStack() },
                    onCreate = ::createPlan,
                    onRetrySave = viewModel::retrySave,
                    exportCount = exportCount(plan),
                    exportBlock = exportBlocks.blockFor(plan.ordinal),
                    onExport = { viewModel.startCalendarExport(plan.ordinal) },
                    onManual = { viewModel.startManualMove() },
                    onManualPick = viewModel::setManualStart,
                    onManualSubmit = { viewModel.submitManualMove() },
                    onManualConfirm = { viewModel.confirmOutsideAvailability() },
                    onManualRevise = viewModel::reviseManualMove,
                    onManualCancel = viewModel::cancelManualMove,
                    checkIns = checkInRows(viewModel, nav, plan, plans, savedPlans, storage),
                )
            }
        }
        // The top-bar back, like system back, returns home from the first input step.
        composable(Routes.CERTIFICATION) {
            BackHandler(onBack = ::leaveCreation)
            CertificationScreen(form, viewModel::selectCertification, onBack = ::leaveCreation) {
                viewModel.reachCreationStep(CreationStep.DATES)
                nav.go(Routes.DATES)
            }
        }
        composable(Routes.DATES) {
            DatesScreen(
                form = form,
                onStartChange = viewModel::setPreparationStart,
                onExamChange = viewModel::setExamDate,
                onBack = { nav.popBackStack() },
                onContinue = { viewModel.reachCreationStep(CreationStep.LEVEL); nav.go(Routes.LEVEL) },
            )
        }
        composable(Routes.LEVEL) {
            LevelScreen(form, viewModel::setLevel, onBack = { nav.popBackStack() }) {
                viewModel.showQuestion(0)
                viewModel.reachCreationStep(CreationStep.QUESTIONS)
                nav.go(Routes.QUESTIONS)
            }
        }
        composable(Routes.QUESTIONS) {
            QuestionsScreen(
                form = form,
                loadState = questionLoad,
                index = questionIndex,
                onShowQuestion = { viewModel.showQuestion(it) },
                onLoad = viewModel::loadQuestions,
                onAnswer = viewModel::answer,
                onExit = { nav.popBackStack(Routes.LEVEL, inclusive = false) },
                onFinished = { viewModel.reachCreationStep(CreationStep.RESULTS); nav.go(Routes.RESULTS) },
            )
        }
        composable(Routes.RESULTS) {
            ResultsScreen(form, onBack = { nav.popBackStack() }) {
                viewModel.reachCreationStep(CreationStep.AVAILABILITY)
                nav.go(Routes.AVAILABILITY)
            }
        }
        composable(Routes.AVAILABILITY) {
            val generation by viewModel.generation.collectAsStateWithLifecycle()
            val availabilityWeek by viewModel.availabilityWeek.collectAsStateWithLifecycle()
            val profile by viewModel.profile.collectAsStateWithLifecycle()
            AvailabilityScreen(
                form = form,
                selectedWeek = availabilityWeek,
                generating = generation == GenerationState.Loading,
                plansSaved = storage == PlanStorage.Ready,
                calendarRead = calendarRead,
                profile = profile.takeIf { viewModel.profileAvailable },
                actions = AvailabilityActions(
                    onEnable = viewModel::setDayEnabled,
                    onStep = viewModel::stepDuration,
                    onStart = viewModel::setDayStart,
                    onDuration = { day, minutes, week -> viewModel.setDuration(day, minutes, week) },
                    onShowWeek = { viewModel.showAvailabilityWeek(it) },
                    onReplaceWeek = { viewModel.replaceWeek(it) },
                    onClearWeek = { viewModel.clearWeek(it) },
                    onResetWeek = { viewModel.resetWeek(it) },
                ),
                profileActions = ProfileActions(
                    onLoad = { viewModel.loadProfile() },
                    onSave = { viewModel.saveProfile() },
                    onAccept = viewModel::acceptProfile,
                    onDismiss = viewModel::dismissProfile,
                ),
                onBack = { nav.popBackStack() },
                onGenerate = { if (viewModel.generate()) nav.go(Routes.GENERATE) },
                onReadCalendar = { viewModel.startCalendarRead() },
                onOpenCalendarSettings = { openAppSettings(context) },
            )
        }
        // Each result destination collects the flow itself, so its first frame sees the
        // current value (a host-level collector could still hold the previous result).
        // A new plan is shown once it is saved; a failed save stays here with its own retry.
        composable(Routes.GENERATE) {
            val generation by viewModel.generation.collectAsStateWithLifecycle()
            LaunchedEffect(generation, storage) {
                when (generation) {
                    is GenerationState.Success -> if (storage == PlanStorage.Ready) nav.showResult(Routes.PLAN)
                    is GenerationState.DemoShortfall -> nav.showResult(Routes.SHORTFALL)
                    else -> Unit
                }
            }
            GenerateScreen(
                state = generation,
                storage = storage,
                onRetry = viewModel::retryGeneration,
                onEdit = { nav.popBackStack(Routes.AVAILABILITY, inclusive = false) },
                onRetrySave = viewModel::retrySave,
                onShowPlan = { nav.showResult(Routes.PLAN) },
            )
        }
        // Reads the stored plan, so an adjustment made from the list is shown here too.
        composable(Routes.PLAN) {
            val plans by viewModel.plans.collectAsStateWithLifecycle()
            val savedPlans by viewModel.savedPlans.collectAsStateWithLifecycle()
            val generatedPlan by viewModel.generatedPlan.collectAsStateWithLifecycle()
            val plan = plans.firstOrNull { it.ordinal == generatedPlan }
            if (plan == null) {
                LaunchedEffect(Unit) { nav.popBackStack(Routes.AVAILABILITY, inclusive = false) }
            } else {
                PlanScreen(
                    response = plan.current,
                    isDemo = plan.isDemo,
                    exportCount = exportCount(plan),
                    exportBlock = exportBlocks.blockFor(plan.ordinal),
                    onExport = { viewModel.startCalendarExport(plan.ordinal) },
                    onEdit = { nav.popBackStack(Routes.AVAILABILITY, inclusive = false) },
                    onHome = { nav.popBackStack(Routes.HOME, inclusive = false) },
                    onAdjust = { nav.fromHome(Routes.PLAN_LIST) },
                    checkIns = checkInRows(viewModel, nav, plan, plans, savedPlans, storage),
                    storage = storage,
                    onRetrySave = viewModel::retrySave,
                )
            }
        }
        // The report editor sits above the plan or saved-plan screen it was opened from, so closing
        // it returns to the same week with the adjustment selection untouched. A confirmed save
        // closes it; popping is idempotent, so a repeated empty frame cannot leave that screen.
        composable(Routes.CHECK_IN) {
            val editor by viewModel.checkIn.collectAsStateWithLifecycle()
            val plans by viewModel.plans.collectAsStateWithLifecycle()
            val current = editor
            val plan = current?.let { open -> plans.firstOrNull { it.ordinal == open.planOrdinal } }
            LaunchedEffect(current?.save) {
                if (current?.save == CheckInSave.Saved) viewModel.closeCheckIn()
            }
            if (current == null || plan == null) {
                LaunchedEffect(Unit) {
                    viewModel.closeCheckIn()
                    nav.popBackStack(Routes.CHECK_IN, inclusive = true)
                }
            } else {
                StudyCheckInScreen(
                    plan = plan,
                    editor = current,
                    onStatus = viewModel::chooseCheckInStatus,
                    onMinutes = viewModel::setCheckInMinutes,
                    onSave = { viewModel.saveCheckIn() },
                    onRetrySave = viewModel::retrySave,
                    onClose = viewModel::closeCheckIn,
                )
            }
        }
        composable(Routes.SHORTFALL) {
            val generation by viewModel.generation.collectAsStateWithLifecycle()
            val shortfall = generation as? GenerationState.DemoShortfall
            if (shortfall == null) {
                LaunchedEffect(Unit) { nav.popBackStack(Routes.AVAILABILITY, inclusive = false) }
            } else {
                ShortfallScreen(
                    required = shortfall.required,
                    available = shortfall.available,
                    onEditDates = { nav.go(Routes.EDIT_EXAM_DATE) },
                    onEditAvailability = { nav.popBackStack(Routes.AVAILABILITY, inclusive = false) },
                )
            }
        }
        // A changed date commits and recalculates once,
        // replacing the old warning and this editor; back or an unchanged date returns to the warning.
        // A tap that lands after the editor started leaving is ignored, so it cannot pop the new result.
        composable(Routes.EDIT_EXAM_DATE) { entry ->
            ExamDateEditScreen(form, onBack = { nav.popBackStack() }) { date ->
                when {
                    !entry.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) -> Unit
                    date == form.examDate -> nav.popBackStack()
                    else -> {
                        viewModel.setExamDate(date)
                        if (viewModel.generate()) {
                            nav.navigate(Routes.GENERATE) {
                                popUpTo(Routes.SHORTFALL) { inclusive = true }
                                launchSingleTop = true
                            }
                        }
                    }
                }
            }
        }
    }
}
