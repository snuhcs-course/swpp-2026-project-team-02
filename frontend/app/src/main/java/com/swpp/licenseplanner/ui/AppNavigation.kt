package com.swpp.licenseplanner.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.remember
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NamedNavArgument
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.swpp.licenseplanner.data.CalendarAccess
import com.swpp.licenseplanner.data.CalendarReadOutcome
import com.swpp.licenseplanner.domain.CalendarExport
import com.swpp.licenseplanner.domain.CreationStep
import com.swpp.licenseplanner.domain.SessionPlan
import com.swpp.licenseplanner.ui.components.CalendarConflictDialog
import com.swpp.licenseplanner.ui.components.CalendarNoticeRecovery
import com.swpp.licenseplanner.ui.components.ManualConflictDialog
import com.swpp.licenseplanner.ui.components.PlanConflictNotices
import com.swpp.licenseplanner.ui.components.Note
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
import com.swpp.licenseplanner.ui.screens.RemainingStudyScreen
import com.swpp.licenseplanner.ui.screens.ReassessmentScreen
import com.swpp.licenseplanner.ui.screens.PlanScreen
import com.swpp.licenseplanner.ui.screens.QuestionsScreen
import com.swpp.licenseplanner.ui.screens.ResultsScreen
import com.swpp.licenseplanner.ui.screens.ShortfallScreen
import com.swpp.licenseplanner.ui.screens.StudyCheckInScreen

/** One route per step, plus home, plan list, one plan view per plan ordinal, and adjustment. */
import kotlinx.coroutines.flow.first

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
    const val PLAN_ORDINAL = "ordinal"
    const val PLAN = "plan/{$PLAN_ORDINAL}"
    fun plan(ordinal: Int) = "plan/$ordinal"
    const val SHORTFALL = "shortfall"
    const val EDIT_EXAM_DATE = "edit-exam-date"
    const val CHECK_IN = "check-in"
    const val REMAINING_STUDY = "remaining-study"
    const val REASSESSMENT = "reassessment"
}

private fun NavHostController.go(route: String) = navigate(route) { launchSingleTop = true }

private fun NavHostController.canGoBack(entry: NavBackStackEntry?): Boolean =
    entry != null && currentBackStackEntry === entry && entry.destination.route != Routes.HOME &&
        entry.lifecycle.currentState == Lifecycle.State.RESUMED

private fun NavHostController.goBack(entry: NavBackStackEntry?, route: String? = null) {
    if (!canGoBack(entry)) return
    if (route == null) popBackStack() else popBackStack(route, inclusive = false)
}

private suspend fun NavHostController.goBackAfterResume(entry: NavBackStackEntry, route: String? = null) {
    entry.lifecycle.currentStateFlow.first { it == Lifecycle.State.RESUMED || it == Lifecycle.State.DESTROYED }
    goBack(entry, route)
}

/** Screen-specific handlers are composed inside this one and take precedence. */
private fun NavGraphBuilder.screen(
    nav: NavHostController,
    route: String,
    arguments: List<NamedNavArgument> = emptyList(),
    content: @Composable (NavBackStackEntry) -> Unit,
) = composable(route, arguments) { entry ->
    BackHandler { nav.goBack(entry) }
    content(entry)
}

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
    canOpen = { viewModel.canReport(plan.ordinal, it) },
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
    val currentEntry by nav.currentBackStackEntryAsState()
    val form by viewModel.form.collectAsStateWithLifecycle()
    val questionLoad by viewModel.questionLoad.collectAsStateWithLifecycle()
    val remainingStudy by viewModel.remainingStudy.collectAsStateWithLifecycle()
    val reassessment by viewModel.reassessment.collectAsStateWithLifecycle()
    val freeTimeSearch by viewModel.freeTimeSearch.collectAsStateWithLifecycle()
    var remainingFocus by rememberSaveable { mutableStateOf<String?>(null) }
    val storage by viewModel.storage.collectAsStateWithLifecycle()
    val deletion by viewModel.planDeletion.collectAsStateWithLifecycle()
    LaunchedEffect(deletion) {
        if (deletion?.status == PlanDeletionStatus.Deleted) nav.fromHome(Routes.PLAN_LIST)
    }
    val calendarExport by viewModel.calendarExport.collectAsStateWithLifecycle()
    val calendarRead by viewModel.calendarRead.collectAsStateWithLifecycle()
    val calendarConflict by viewModel.calendarConflict.collectAsStateWithLifecycle()
    val notices by viewModel.notices.collectAsStateWithLifecycle()
    val noticeCheckFeedback by viewModel.noticeCheckFeedback.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(noticeCheckFeedback) {
        noticeCheckFeedback?.let { snackbar.showSnackbar(it.message, actionLabel = "닫기") }
    }
    val keepFeedback by viewModel.keepFeedback.collectAsStateWithLifecycle()
    val noticePermission by viewModel.noticePermission.collectAsStateWithLifecycle()
    val manualReview by viewModel.manualReview.collectAsStateWithLifecycle()
    val generationForConflict by viewModel.generation.collectAsStateWithLifecycle()
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
    fun leaveCreation(entry: NavBackStackEntry) {
        if (!nav.canGoBack(entry)) return
        if (viewModel.creationExitNeedsDecision()) {
            draftWaiting = false
            draftPrompt = "exit"
        } else nav.goBack(entry)
    }
    LaunchedEffect(draftDecision) {
        val decision = draftDecision ?: return@LaunchedEffect
        if (decision.phase != DraftPhase.Done) return@LaunchedEffect
        draftPrompt = null
        draftWaiting = false
        if (decision.continuation == DraftContinuation.NewCreation) nav.fromHome(Routes.CERTIFICATION)
        else nav.currentBackStackEntry?.let { nav.goBackAfterResume(it, Routes.HOME) }
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
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, context, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> viewModel.enterForeground(CalendarAccess.READ.isGranted(context))
                Lifecycle.Event.ON_STOP -> if ((context as? android.app.Activity)?.isChangingConfigurations != true) viewModel.leaveForeground()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) viewModel.enterForeground(CalendarAccess.READ.isGranted(context))
        onDispose { lifecycle.removeObserver(observer) }
    }
    val selectedNotices: @Composable (Int) -> Unit = { ordinal ->
        PlanConflictNotices(notices[ordinal], keepFeedback, storage == PlanStorage.Ready,
            onCheck = { viewModel.requestNoticeCheck() },
            onKeep = { viewModel.keepCurrentConflict(ordinal, it) },
            onMove = { id ->
                if (viewModel.selectPlan(ordinal)) {
                    viewModel.selectFailedSession(id)
                    if (viewModel.startManualMove()) nav.go(Routes.ADJUST)
                }
            })
    }
    val noticeCounts: @Composable () -> Unit = {
        for ((ordinal, notice) in notices.toSortedMap()) {
            val count = notice.conflicts.matches.map { it.target.scheduleId }.distinct().size
            Note("계획 $ordinal · " + when (notice.phase) {
                NoticePhase.Checked -> "캘린더와 겹치는 공부 ${count}개"
                NoticePhase.Checking -> "캘린더 확인 중"
                NoticePhase.Stale -> "이전 캘린더 확인 결과"
                NoticePhase.Unchecked -> "캘린더 확인 안 됨"
            })
            if (notice.phase == NoticePhase.Checked) Note(notice.checkedSummary())
        }
        val failures = notices.values.mapNotNull { it.recovery }
        val recovery = failures.firstOrNull { it == CalendarReadOutcome.PermissionMissing } ?: failures.firstOrNull()
        CalendarNoticeRecovery(recovery, storage == PlanStorage.Ready) { viewModel.requestNoticeCheck() }
    }
    val noticePermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        viewModel.onNoticePermission(CalendarAccess.READ.isGranted(context))
    }
    LaunchedEffect(noticePermission) {
        if (noticePermission != ConflictPhase.RequestingPermission) return@LaunchedEffect
        if (CalendarAccess.READ.isGranted(context)) viewModel.onNoticePermission(true)
        else {
            viewModel.noticePermissionPrompted()
            noticePermissionLauncher.launch(CalendarAccess.READ.permissions.single())
        }
    }
    var manualPermissionOwner by rememberSaveable { mutableStateOf<Int?>(null) }
    val manualPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        viewModel.onManualPermission(CalendarAccess.READ.isGranted(context), manualPermissionOwner)
    }
    LaunchedEffect(manualReview?.id, manualReview?.phase) {
        if (manualReview?.phase != ConflictPhase.RequestingPermission) return@LaunchedEffect
        if (CalendarAccess.READ.isGranted(context)) viewModel.onManualPermission(true, manualReview?.id)
        else {
            manualPermissionOwner = manualReview?.id
            viewModel.manualPermissionPrompted()
            manualPermissionLauncher.launch(CalendarAccess.READ.permissions.single())
        }
    }
    var freeTimePermissionOwner by rememberSaveable { mutableStateOf<Int?>(null) }
    val freeTimePermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        viewModel.onFreeTimePermission(CalendarAccess.READ.isGranted(context), freeTimePermissionOwner)
    }
    LaunchedEffect(freeTimeSearch?.id, freeTimeSearch?.phase) {
        if (freeTimeSearch?.phase != FreeTimePhase.RequestingPermission) return@LaunchedEffect
        if (CalendarAccess.READ.isGranted(context)) viewModel.onFreeTimePermission(true, freeTimeSearch?.id)
        else {
            freeTimePermissionOwner = freeTimeSearch?.id
            viewModel.freeTimePermissionPrompted()
            freeTimePermissionLauncher.launch(CalendarAccess.READ.permissions.single())
        }
    }
    val remainingSearch = remainingStudy as? RemainingStudyState.CalendarSearch
    var remainingPermissionOwner by rememberSaveable { mutableStateOf<Int?>(null) }
    val remainingPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        viewModel.onRemainingFreeTimePermission(CalendarAccess.READ.isGranted(context), remainingPermissionOwner)
    }
    LaunchedEffect(remainingSearch?.id, remainingSearch?.phase) {
        if (remainingSearch?.phase != FreeTimePhase.RequestingPermission) return@LaunchedEffect
        if (CalendarAccess.READ.isGranted(context)) viewModel.onRemainingFreeTimePermission(true, remainingSearch?.id)
        else {
            remainingPermissionOwner = remainingSearch?.id
            viewModel.remainingFreeTimePermissionPrompted()
            remainingPermissionLauncher.launch(CalendarAccess.READ.permissions.single())
        }
    }
    manualReview?.let { state -> ManualConflictDialog(state,
        onConfirm = { viewModel.confirmManualExternalOverlap() }, onCancel = viewModel::cancelManualReview,
        onUnchecked = { viewModel.continueManualUnchecked() }, onRetry = viewModel::retryManualRead) }
    // The answer arrives here also after rotation; access is read again rather than taken from the result.
    val exportPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        viewModel.onCalendarPermission(CalendarAccess.EXPORT.isGranted(context))
    }
    val readPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        viewModel.onCalendarReadPermission(CalendarAccess.READ.isGranted(context))
    }
    var conflictPermissionOwner by rememberSaveable { mutableStateOf<Int?>(null) }
    val conflictPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        viewModel.onConflictPermission(CalendarAccess.READ.isGranted(context), conflictPermissionOwner)
    }
    LaunchedEffect(calendarConflict?.id, calendarConflict?.phase) {
        val review = calendarConflict?.takeIf { it.phase == ConflictPhase.RequestingPermission } ?: return@LaunchedEffect
        if (CalendarAccess.READ.isGranted(context)) viewModel.onConflictPermission(true, review.id)
        else if (review.kind != ConflictKind.Export) {
            if (!viewModel.continueGenerationWithoutCalendar(review.id)) viewModel.onConflictPermission(false, review.id)
        }
        else if (viewModel.conflictPermissionPrompted()) {
            conflictPermissionOwner = review.id
            conflictPermissionLauncher.launch(CalendarAccess.READ.permissions.single())
        }
    }
    calendarConflict?.let { state ->
        val reviewEntry = currentEntry
        fun closeReview() {
            if (state.kind == ConflictKind.Creation && !nav.canGoBack(reviewEntry)) return
            viewModel.cancelCalendarConflict()
            if (state.kind == ConflictKind.Creation && viewModel.calendarConflict.value == null) {
                nav.goBack(reviewEntry, Routes.AVAILABILITY)
            }
        }
        CalendarConflictDialog(
            state = state,
            generation = generationForConflict,
            onContinue = { viewModel.continueCalendarConflict(it) },
            onDetails = viewModel::showConflictDetails,
            onSummary = viewModel::showConflictSummary,
            onEdit = ::closeReview,
            onRegenerate = { state.source?.let { viewModel.startRegeneration(it.ordinal) } },
            regenerationReason = state.source?.let { viewModel.regenerationBlockReason(it.ordinal) },
            onConfirmReplacement = { viewModel.confirmRegeneration() },
            onRetryRead = viewModel::retryCalendarConflict,
            onRetryGeneration = viewModel::retryGeneration,
            onManual = { id ->
                val ordinal = state.source?.ordinal
                viewModel.cancelCalendarConflict()
                if (ordinal != null && viewModel.selectPlan(ordinal)) {
                    viewModel.selectFailedSession(id)
                    if (viewModel.startManualMove()) nav.go(Routes.ADJUST)
                }
            },
            onSettings = { openAppSettings(context) },
            onClose = ::closeReview,
        )
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
    calendarExport?.takeIf { calendarConflict == null }?.let { state ->
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

    Box(Modifier.fillMaxSize()) {
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
                conflictNotices = noticeCounts,
            )
        }
        screen(nav, Routes.PLAN_LIST) { entry ->
            val plans by viewModel.plans.collectAsStateWithLifecycle()
            val savedPlans by viewModel.savedPlans.collectAsStateWithLifecycle()
            fun back() {
                if (!nav.canGoBack(entry)) return
                viewModel.dismissDeletionNotice()
                nav.goBack(entry)
            }
            BackHandler(onBack = ::back)
            PlanListScreen(
                plans = plans,
                savedPlans = savedPlans,
                storage = planStorage,
                onOpen = { ordinal ->
                    viewModel.dismissDeletionNotice()
                    nav.go(Routes.plan(ordinal))
                },
                onCreate = { viewModel.dismissDeletionNotice(); createPlan() },
                onBack = ::back,
                onRetryLoad = viewModel::retryLoad,
                onRetrySave = viewModel::retrySave,
                conflictNotices = noticeCounts,
                deletionNotice = deletion?.status == PlanDeletionStatus.Deleted,
            )
        }
        screen(nav, Routes.ADJUST) { entry ->
            val plans by viewModel.plans.collectAsStateWithLifecycle()
            val savedPlans by viewModel.savedPlans.collectAsStateWithLifecycle()
            val selection by viewModel.adjustment.collectAsStateWithLifecycle()
            val manualSending by viewModel.manualMoveSending.collectAsStateWithLifecycle()
            val plan = plans.firstOrNull { it.ordinal == selection.planOrdinal }
            if (plan == null) {
                LaunchedEffect(entry) { nav.goBackAfterResume(entry) }
            } else {
                // Going back leaves the flow; a check-in or the chosen-time editor opened from here does not.
                fun leave() {
                    if (!nav.canGoBack(entry)) return
                    viewModel.leaveAdjustment()
                    nav.goBack(entry)
                }
                BackHandler(onBack = ::leave)
                PlanAdjustmentScreen(
                    plan = plan,
                    selection = selection,
                    storage = planStorage,
                    onSelect = viewModel::selectFailedSession,
                    onClearSelection = viewModel::clearFailedSession,
                    onSubmit = { viewModel.submitAdjustment() },
                    onRetry = viewModel::retryAdjustment,
                    onReload = { viewModel.reloadPlan() },
                    onBack = ::leave,
                    onList = {
                        viewModel.leaveAdjustment()
                        nav.fromHome(Routes.PLAN_LIST)
                    },
                    onCreate = ::createPlan,
                    onRetrySave = viewModel::retrySave,
                    freeTimeSearch = freeTimeSearch,
                    canSearchFreeTime = viewModel.canSearchFreeTime(),
                    onSearchFreeTime = { viewModel.startFreeTimeSearch() },
                    onFreeTimeSelect = viewModel::selectFreeTime,
                    onFreeTimeConfirm = { viewModel.confirmFreeTime() },
                    onFreeTimeManual = { viewModel.chooseTimeManually() },
                    onFreeTimeRetry = viewModel::retryFreeTimeSearch,
                    onFreeTimeBack = viewModel::cancelFreeTimeSearch,
                    onManual = { viewModel.startManualMove() },
                    onManualPick = viewModel::setManualStart,
                    onManualSubmit = { viewModel.submitManualMove() },
                    onManualConfirm = { viewModel.confirmOutsideAvailability() },
                    onManualRevise = viewModel::reviseManualMove,
                    onManualCancel = viewModel::cancelManualMove,
                    checkIns = checkInRows(viewModel, nav, plan, plans, savedPlans, storage),
                    manualSending = manualSending,
                    // Leaving closes only the editor; the adjustment view then asks for a reload.
                    onManualStopWaiting = { if (nav.canGoBack(entry)) viewModel.stopWaitingForManualMove() },
                    conflictNotices = { selectedNotices(plan.ordinal) },
                )
            }
        }
        // The top-bar back, like system back, returns home from the first input step.
        screen(nav, Routes.CERTIFICATION) { entry ->
            BackHandler { leaveCreation(entry) }
            CertificationScreen(form, viewModel::selectCertification, onBack = { leaveCreation(entry) }) {
                viewModel.reachCreationStep(CreationStep.DATES)
                nav.go(Routes.DATES)
            }
        }
        screen(nav, Routes.DATES) { entry ->
            DatesScreen(
                form = form,
                today = viewModel.currentLocalDate(),
                onStartChange = viewModel::setPreparationStart,
                onExamChange = viewModel::setExamDate,
                onBack = { nav.goBack(entry) },
                onContinue = { viewModel.reachCreationStep(CreationStep.LEVEL); nav.go(Routes.LEVEL) },
            )
        }
        screen(nav, Routes.LEVEL) { entry ->
            LevelScreen(form, viewModel::setLevel, onBack = { nav.goBack(entry) }) {
                viewModel.showQuestion(0)
                viewModel.reachCreationStep(CreationStep.QUESTIONS)
                nav.go(Routes.QUESTIONS)
            }
        }
        screen(nav, Routes.QUESTIONS) { entry ->
            QuestionsScreen(
                form = form,
                loadState = questionLoad,
                index = questionIndex,
                onShowQuestion = { nav.canGoBack(entry) && viewModel.showQuestion(it) },
                onLoad = viewModel::loadQuestions,
                onAnswer = viewModel::answer,
                onExit = { nav.goBack(entry, Routes.LEVEL) },
                onFinished = { viewModel.reachCreationStep(CreationStep.RESULTS); nav.go(Routes.RESULTS) },
            )
        }
        screen(nav, Routes.RESULTS) { entry ->
            ResultsScreen(form, onBack = { nav.goBack(entry) }) {
                viewModel.reachCreationStep(CreationStep.AVAILABILITY)
                nav.go(Routes.AVAILABILITY)
            }
        }
        screen(nav, Routes.AVAILABILITY) { entry ->
            val generation by viewModel.generation.collectAsStateWithLifecycle()
            val availabilityWeek by viewModel.availabilityWeek.collectAsStateWithLifecycle()
            val inputEditSource by viewModel.inputEditSource.collectAsStateWithLifecycle()
            AvailabilityScreen(
                form = form,
                selectedWeek = availabilityWeek,
                generating = generation == GenerationState.Loading,
                plansSaved = storage == PlanStorage.Ready,
                calendarRead = calendarRead,
                actions = AvailabilityActions(
                    onCapture = viewModel::captureWindow,
                    onApply = viewModel::applyWindowToDays,
                    onRemove = viewModel::removeWindow,
                    onShowWeek = { viewModel.showAvailabilityWeek(it) },
                    onReplaceWeek = { viewModel.replaceWeek(it) },
                    onClearWeek = { viewModel.clearWeek(it) },
                    onResetWeek = { viewModel.resetWeek(it) },
                ),
                onBack = { nav.goBack(entry) },
                onGenerate = { if (viewModel.generate()) nav.go(Routes.GENERATE) },
                onReadCalendar = { viewModel.startCalendarRead() },
                onOpenCalendarSettings = { openAppSettings(context) },
                inputEditSource = inputEditSource,
            )
        }
        // Each result destination collects the flow itself, so its first frame sees the
        // current value (a host-level collector could still hold the previous result).
        // A new plan is shown once it is saved; a failed save stays here with its own retry.
        // The new plan's view replaces the creation steps, so back from it returns home.
        screen(nav, Routes.GENERATE) { entry ->
            val generation by viewModel.generation.collectAsStateWithLifecycle()
            fun showGeneratedPlan() {
                viewModel.generatedPlan.value?.let { nav.fromHome(Routes.plan(it)) }
            }
            LaunchedEffect(generation, storage) {
                when (generation) {
                    is GenerationState.Success -> if (storage == PlanStorage.Ready) showGeneratedPlan()
                    is GenerationState.DemoShortfall -> nav.showResult(Routes.SHORTFALL)
                    else -> Unit
                }
            }
            GenerateScreen(
                state = generation,
                storage = storage,
                onRetry = viewModel::retryGeneration,
                onEdit = { nav.goBack(entry, Routes.AVAILABILITY) },
                onRetrySave = viewModel::retrySave,
                onShowPlan = ::showGeneratedPlan,
                onCancelGeneration = {
                    if (nav.canGoBack(entry)) {
                        viewModel.cancelGeneration()
                        nav.goBack(entry, Routes.AVAILABILITY)
                    }
                },
            )
        }
        // The route names the plan, so every plan view on the stack keeps its own plan; it reads
        // the stored plan, so an adjustment made above it is shown on return.
        screen(nav, Routes.PLAN, arguments = listOf(navArgument(Routes.PLAN_ORDINAL) { type = NavType.IntType })) { entry ->
            val plans by viewModel.plans.collectAsStateWithLifecycle()
            val savedPlans by viewModel.savedPlans.collectAsStateWithLifecycle()
            val generatedPlan by viewModel.generatedPlan.collectAsStateWithLifecycle()
            val ordinal = entry.arguments?.getInt(Routes.PLAN_ORDINAL)
            val plan = plans.firstOrNull { it.ordinal == ordinal }
            if (plan == null) {
                LaunchedEffect(entry) {
                    if (viewModel.planDeletion.value?.status != PlanDeletionStatus.Deleted) nav.goBackAfterResume(entry)
                }
            } else {
                PlanScreen(
                    plan = plan,
                    generatedPlan = generatedPlan,
                    exportCount = exportCount(plan),
                    exportBlock = exportBlocks.blockFor(plan.ordinal),
                    onExport = { viewModel.startCalendarExport(plan.ordinal) },
                    onRegenerate = { viewModel.startRegeneration(plan.ordinal) },
                    regenerationReason = viewModel.regenerationBlockReason(plan.ordinal),
                    onBack = { nav.goBack(entry) },
                    onEditInputs = { if (viewModel.startInputEdit(plan.ordinal)) nav.resumeCreation(CreationStep.AVAILABILITY) },
                    onCreate = ::createPlan,
                    onHome = { nav.goBack(entry, Routes.HOME) },
                    onAdjust = { if (viewModel.selectPlan(plan.ordinal)) nav.go(Routes.ADJUST) },
                    checkIns = checkInRows(viewModel, nav, plan, plans, savedPlans,
                        if (deletion?.status == PlanDeletionStatus.Deleting) PlanStorage.Saving else storage),
                    storage = storage,
                    onRetrySave = viewModel::retrySave,
                    conflictNotices = { selectedNotices(plan.ordinal) },
                    onRemainingStudy = { if (viewModel.startRemainingStudy(plan.ordinal)) nav.go(Routes.REMAINING_STUDY) },
                    remainingStudyReason = viewModel.remainingStudyBlockReason(plan.ordinal),
                    onReassess = { if (viewModel.startReassessment(plan.ordinal)) nav.go(Routes.REASSESSMENT) },
                    reassessmentReason = viewModel.reassessmentBlockReason(plan.ordinal),
                    initialScheduleId = remainingFocus,
                    onFocusApplied = { remainingFocus = null },
                    deletion = deletion?.takeIf { it.ordinal == plan.ordinal }?.status,
                    deletionBlockReason = viewModel.planDeletionBlockReason(plan.ordinal),
                    onDelete = { viewModel.deletePlan(plan.ordinal) },
                )
            }
        }
        // Re-planning sits above the plan view it was started from, so every exit pops back to it;
        // a placed result also shows the week of its first new session there.
        screen(nav, Routes.REMAINING_STUDY) { entry ->
            val current = remainingStudy
            fun close() {
                if (nav.canGoBack(entry) && viewModel.closeRemainingStudy()) nav.goBack(entry)
            }
            if (current == null) {
                LaunchedEffect(entry) { nav.goBackAfterResume(entry) }
            } else {
                RemainingStudyScreen(current, storage,
                    onConfirm = { viewModel.confirmRemainingStudy() }, onClose = ::close,
                    canSearchFreeTime = viewModel.canSearchRemainingFreeTime(),
                    onSearchFreeTime = { viewModel.startRemainingFreeTimeSearch() },
                    onFreeTimeSelect = viewModel::selectFollowUpTime,
                    onFreeTimeConfirm = { viewModel.confirmRemainingFreeTime() },
                    onFreeTimeRetry = viewModel::retryRemainingFreeTimeSearch,
                    onRetrySave = viewModel::retrySave,
                    onShowPlan = {
                        val focus = (current as? RemainingStudyState.Success)?.let { result ->
                            val ids = result.input.work.instructions.map { it.replacementScheduleId }.toSet()
                            result.rows.firstOrNull { it.scheduleId in ids }?.scheduleId
                        }
                        if (nav.canGoBack(entry) && viewModel.closeRemainingStudy()) {
                            remainingFocus = focus
                            nav.goBack(entry)
                        }
                    })
            }
        }
        // Reassessment also sits above the plan view it was started from; every step shares this
        // one entry, so each exit pops straight back and leaving discards the answers. A finished
        // update shows the week of its first changed session there.
        screen(nav, Routes.REASSESSMENT) { entry ->
            val current = reassessment
            fun close() {
                if (nav.canGoBack(entry) && viewModel.closeReassessment()) nav.goBack(entry)
            }
            if (current == null) {
                LaunchedEffect(entry) { nav.goBackAfterResume(entry) }
            } else {
                ReassessmentScreen(current,
                    onRetryLoad = { viewModel.retryReassessmentQuestions() },
                    onAnswer = viewModel::answerReassessment,
                    onShowQuestion = viewModel::showReassessmentQuestion,
                    onFinishQuiz = { viewModel.finishReassessmentQuiz() },
                    onApply = { viewModel.previewReassessment() },
                    onClose = ::close,
                    storage = storage,
                    onConfirm = { viewModel.confirmReassessment() },
                    onRetrySave = viewModel::retrySave,
                    onShowPlan = {
                        val focus = (current as? ReassessmentState.Done)?.let { firstChangedScheduleId(it.preview) }
                        if (nav.canGoBack(entry) && viewModel.closeReassessment()) {
                            remainingFocus = focus
                            nav.goBack(entry)
                        }
                    })
            }
        }
        // A saved report either closes the editor or offers a follow-up. The follow-up
        // flow sits immediately above the plan view, even when the report began in adjustment.
        screen(nav, Routes.CHECK_IN) { entry ->
            val editor by viewModel.checkIn.collectAsStateWithLifecycle()
            val plans by viewModel.plans.collectAsStateWithLifecycle()
            val current = editor
            val plan = current?.let { open -> plans.firstOrNull { it.ordinal == open.planOrdinal } }
            LaunchedEffect(current?.save, current?.followUp) {
                if (current?.save == CheckInSave.Saved && current.followUp == null) viewModel.closeCheckIn()
            }
            if (current == null || plan == null) {
                LaunchedEffect(Unit) {
                    viewModel.closeCheckIn()
                    nav.goBackAfterResume(entry)
                }
            } else {
                fun returnToPlan() {
                    val route = Routes.plan(plan.ordinal)
                    val fromAdjustment = nav.previousBackStackEntry?.destination?.route == Routes.ADJUST
                    if (!nav.popBackStack(route, inclusive = false)) {
                        nav.navigate(route) {
                            popUpTo(if (fromAdjustment) Routes.ADJUST else Routes.CHECK_IN) { inclusive = true }
                            launchSingleTop = true
                        }
                    }
                }
                StudyCheckInScreen(
                    plan = plan,
                    editor = current,
                    onStatus = viewModel::chooseCheckInStatus,
                    onMinutes = viewModel::setCheckInMinutes,
                    onSave = { viewModel.saveCheckIn() },
                    onRetrySave = viewModel::retrySave,
                    onClose = {
                        if (nav.canGoBack(entry)) {
                            viewModel.closeCheckIn()
                            if (current.followUp != null) returnToPlan()
                        }
                    },
                    onFollowUp = {
                        if (nav.canGoBack(entry) && viewModel.startCheckInFollowUp()) {
                            returnToPlan()
                            nav.go(Routes.REMAINING_STUDY)
                        }
                    },
                )
            }
        }
        screen(nav, Routes.SHORTFALL) { entry ->
            val generation by viewModel.generation.collectAsStateWithLifecycle()
            val shortfall = generation as? GenerationState.DemoShortfall
            if (shortfall == null) {
                LaunchedEffect(entry) { nav.goBackAfterResume(entry, Routes.AVAILABILITY) }
            } else {
                ShortfallScreen(
                    required = shortfall.required,
                    available = shortfall.available,
                    onEditDates = { nav.go(Routes.EDIT_EXAM_DATE) },
                    onEditAvailability = { nav.goBack(entry, Routes.AVAILABILITY) },
                )
            }
        }
        // A changed date commits and recalculates once,
        // replacing the old warning and this editor; back or an unchanged date returns to the warning.
        // A tap that lands after the editor started leaving is ignored, so it cannot pop the new result.
        screen(nav, Routes.EDIT_EXAM_DATE) { entry ->
            ExamDateEditScreen(form, onBack = { nav.goBack(entry) }) { date ->
                when {
                    !nav.canGoBack(entry) -> Unit
                    date == form.examDate -> nav.goBack(entry)
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
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).safeDrawingPadding().padding(16.dp))
    }
}
