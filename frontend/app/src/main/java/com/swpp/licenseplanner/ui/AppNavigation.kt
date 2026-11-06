package com.swpp.licenseplanner.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.swpp.licenseplanner.domain.CalendarExport
import com.swpp.licenseplanner.domain.SessionPlan
import com.swpp.licenseplanner.ui.components.CalendarExportDialog
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
import com.swpp.licenseplanner.ui.screens.QuestionsScreen
import com.swpp.licenseplanner.ui.screens.ResultsScreen
import com.swpp.licenseplanner.ui.screens.ShortfallScreen

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

private val calendarPermissions = arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
private val calendarReadPermission = Manifest.permission.READ_CALENDAR

private fun hasCalendarAccess(context: Context) =
    calendarPermissions.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

private fun hasCalendarReadAccess(context: Context) =
    ContextCompat.checkSelfPermission(context, calendarReadPermission) == PackageManager.PERMISSION_GRANTED

private fun openAppSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        // The dialog already says where to allow access.
    }
}

/** Generation, adjustment, reload or another export is running; calendar export waits for it. */
private fun operationBusy(generation: GenerationState, selection: AdjustmentSelection, export: CalendarExportState?) =
    generation == GenerationState.Loading || selection.state == AdjustmentState.Loading ||
        selection.state == AdjustmentState.Reloading || export != null

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

    val context = LocalContext.current
    // The answer arrives here also after rotation; access is read again rather than taken from the result.
    val exportPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        viewModel.onCalendarPermission(hasCalendarAccess(context))
    }
    val readPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        viewModel.onCalendarReadPermission(hasCalendarReadAccess(context))
    }
    val requestingPermission = calendarExport?.phase == CalendarExportPhase.RequestingPermission
    LaunchedEffect(requestingPermission) {
        if (!requestingPermission) return@LaunchedEffect
        if (hasCalendarAccess(context)) viewModel.onCalendarPermission(true)
        else if (viewModel.calendarPermissionPrompted()) exportPermissionLauncher.launch(calendarPermissions)
    }
    val requestingReadPermission = calendarRead == CalendarReadState.RequestingPermission
    LaunchedEffect(requestingReadPermission) {
        if (!requestingReadPermission) return@LaunchedEffect
        if (hasCalendarReadAccess(context)) viewModel.onCalendarReadPermission(true)
        else if (viewModel.calendarReadPermissionPrompted()) readPermissionLauncher.launch(calendarReadPermission)
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
                storage = storage,
                onCreate = { nav.fromHome(Routes.CERTIFICATION) },
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
                storage = storage,
                onOpen = { ordinal -> if (viewModel.selectPlan(ordinal)) nav.go(Routes.ADJUST) },
                onCreate = { nav.fromHome(Routes.CERTIFICATION) },
                onBack = { nav.popBackStack() },
                onRetryLoad = viewModel::retryLoad,
                onRetrySave = viewModel::retrySave,
            )
        }
        composable(Routes.ADJUST) {
            val plans by viewModel.plans.collectAsStateWithLifecycle()
            val selection by viewModel.adjustment.collectAsStateWithLifecycle()
            val generation by viewModel.generation.collectAsStateWithLifecycle()
            val plan = plans.firstOrNull { it.ordinal == selection.planOrdinal }
            if (plan == null) {
                LaunchedEffect(Unit) { nav.popBackStack() }
            } else {
                PlanAdjustmentScreen(
                    plan = plan,
                    selection = selection,
                    storage = storage,
                    onSelect = viewModel::selectFailedSession,
                    onSubmit = { viewModel.submitAdjustment() },
                    onRetry = viewModel::retryAdjustment,
                    onReload = { viewModel.reloadPlan() },
                    onBack = { nav.popBackStack() },
                    onCreate = { nav.fromHome(Routes.CERTIFICATION) },
                    onRetrySave = viewModel::retrySave,
                    exportCount = exportCount(plan),
                    exportBlock = exportBlock(plan, storage, operationBusy(generation, selection, calendarExport)),
                    onExport = { viewModel.startCalendarExport(plan.ordinal) },
                )
            }
        }
        // The top-bar back, like system back, returns home from the first input step.
        composable(Routes.CERTIFICATION) {
            CertificationScreen(form, viewModel::selectCertification, onBack = { nav.popBackStack() }) { nav.go(Routes.DATES) }
        }
        composable(Routes.DATES) {
            DatesScreen(
                form = form,
                onStartChange = viewModel::setPreparationStart,
                onExamChange = viewModel::setExamDate,
                onBack = { nav.popBackStack() },
                onContinue = { nav.go(Routes.LEVEL) },
            )
        }
        composable(Routes.LEVEL) {
            LevelScreen(form, viewModel::setLevel, onBack = { nav.popBackStack() }) { nav.go(Routes.QUESTIONS) }
        }
        composable(Routes.QUESTIONS) {
            QuestionsScreen(
                form = form,
                loadState = questionLoad,
                onLoad = viewModel::loadQuestions,
                onAnswer = viewModel::answer,
                onExit = { nav.popBackStack(Routes.LEVEL, inclusive = false) },
                onFinished = { nav.go(Routes.RESULTS) },
            )
        }
        composable(Routes.RESULTS) {
            ResultsScreen(form, onBack = { nav.popBackStack() }) { nav.go(Routes.AVAILABILITY) }
        }
        composable(Routes.AVAILABILITY) {
            val generation by viewModel.generation.collectAsStateWithLifecycle()
            AvailabilityScreen(
                form = form,
                generating = generation == GenerationState.Loading,
                plansSaved = storage == PlanStorage.Ready,
                calendarRead = calendarRead,
                onEnable = viewModel::setDayEnabled,
                onStep = viewModel::stepDuration,
                onStart = viewModel::setDayStart,
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
            val generatedPlan by viewModel.generatedPlan.collectAsStateWithLifecycle()
            val generation by viewModel.generation.collectAsStateWithLifecycle()
            val selection by viewModel.adjustment.collectAsStateWithLifecycle()
            val plan = plans.firstOrNull { it.ordinal == generatedPlan }
            if (plan == null) {
                LaunchedEffect(Unit) { nav.popBackStack(Routes.AVAILABILITY, inclusive = false) }
            } else {
                PlanScreen(
                    response = plan.current,
                    isDemo = plan.isDemo,
                    exportCount = exportCount(plan),
                    exportBlock = exportBlock(plan, storage, operationBusy(generation, selection, calendarExport)),
                    onExport = { viewModel.startCalendarExport(plan.ordinal) },
                    onEdit = { nav.popBackStack(Routes.AVAILABILITY, inclusive = false) },
                    onHome = { nav.popBackStack(Routes.HOME, inclusive = false) },
                    onAdjust = { nav.fromHome(Routes.PLAN_LIST) },
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
