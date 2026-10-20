package com.swpp.licenseplanner.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
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

/** The whole app flow; [viewModel] is activity-scoped, so state survives back and rotation. */
@Composable
fun AppNavigation(viewModel: PlanFormViewModel) {
    val nav = rememberNavController()
    val form by viewModel.form.collectAsStateWithLifecycle()
    val questionLoad by viewModel.questionLoad.collectAsStateWithLifecycle()

    NavHost(navController = nav, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            val plans by viewModel.plans.collectAsStateWithLifecycle()
            HomeScreen(plans.size, onCreate = { nav.fromHome(Routes.CERTIFICATION) }, onAdjust = { nav.fromHome(Routes.PLAN_LIST) })
        }
        composable(Routes.PLAN_LIST) {
            val plans by viewModel.plans.collectAsStateWithLifecycle()
            PlanListScreen(
                plans = plans,
                onOpen = { ordinal -> if (viewModel.selectPlan(ordinal)) nav.go(Routes.ADJUST) },
                onCreate = { nav.fromHome(Routes.CERTIFICATION) },
                onBack = { nav.popBackStack() },
            )
        }
        composable(Routes.ADJUST) {
            val plans by viewModel.plans.collectAsStateWithLifecycle()
            val selection by viewModel.adjustment.collectAsStateWithLifecycle()
            val plan = plans.firstOrNull { it.ordinal == selection.planOrdinal }
            if (plan == null) {
                LaunchedEffect(Unit) { nav.popBackStack() }
            } else {
                PlanAdjustmentScreen(
                    plan = plan,
                    selection = selection,
                    onSelect = viewModel::selectFailedSession,
                    onSubmit = { viewModel.submitAdjustment() },
                    onRetry = viewModel::retryAdjustment,
                    onReload = { viewModel.reloadPlan() },
                    onBack = { nav.popBackStack() },
                    onCreate = { nav.fromHome(Routes.CERTIFICATION) },
                )
            }
        }
        // System back from the first input step returns home.
        composable(Routes.CERTIFICATION) {
            CertificationScreen(form, viewModel::selectCertification) { nav.go(Routes.DATES) }
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
                onEnable = viewModel::setDayEnabled,
                onStep = viewModel::stepDuration,
                onStart = viewModel::setDayStart,
                onBack = { nav.popBackStack() },
                onGenerate = { if (viewModel.generate()) nav.go(Routes.GENERATE) },
            )
        }
        // Each result destination collects the flow itself, so its first frame sees the
        // current value (a host-level collector could still hold the previous result).
        composable(Routes.GENERATE) {
            val generation by viewModel.generation.collectAsStateWithLifecycle()
            LaunchedEffect(generation) {
                when (generation) {
                    is GenerationState.Success -> nav.showResult(Routes.PLAN)
                    is GenerationState.DemoShortfall -> nav.showResult(Routes.SHORTFALL)
                    else -> Unit
                }
            }
            GenerateScreen(
                state = generation,
                onRetry = viewModel::retryGeneration,
                onEdit = { nav.popBackStack(Routes.AVAILABILITY, inclusive = false) },
            )
        }
        // Reads the stored plan, so an adjustment made from the list is shown here too.
        composable(Routes.PLAN) {
            val plans by viewModel.plans.collectAsStateWithLifecycle()
            val generatedPlan by viewModel.generatedPlan.collectAsStateWithLifecycle()
            val plan = plans.firstOrNull { it.ordinal == generatedPlan }
            if (plan == null) {
                LaunchedEffect(Unit) { nav.popBackStack(Routes.AVAILABILITY, inclusive = false) }
            } else {
                PlanScreen(
                    response = plan.current,
                    isDemo = plan.isDemo,
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
