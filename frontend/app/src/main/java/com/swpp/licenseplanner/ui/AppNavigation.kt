package com.swpp.licenseplanner.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.swpp.licenseplanner.ui.screens.AvailabilityScreen
import com.swpp.licenseplanner.ui.screens.CertificationScreen
import com.swpp.licenseplanner.ui.screens.DatesScreen
import com.swpp.licenseplanner.ui.screens.GenerateScreen
import com.swpp.licenseplanner.ui.screens.LevelScreen
import com.swpp.licenseplanner.ui.screens.PlanScreen
import com.swpp.licenseplanner.ui.screens.QuestionsScreen
import com.swpp.licenseplanner.ui.screens.ResultsScreen
import com.swpp.licenseplanner.ui.screens.ShortfallScreen

/** One route per step (design D4). */
private object Routes {
    const val CERTIFICATION = "certification"
    const val DATES = "dates"
    const val LEVEL = "level"
    const val QUESTIONS = "questions"
    const val RESULTS = "results"
    const val AVAILABILITY = "availability"
    const val GENERATE = "generate"
    const val PLAN = "plan"
    const val SHORTFALL = "shortfall"
}

private fun NavHostController.go(route: String) = navigate(route) { launchSingleTop = true }

/** Replaces the transient generate step with its result, so back returns to availability. */
private fun NavHostController.showResult(route: String) = navigate(route) {
    popUpTo(Routes.GENERATE) { inclusive = true }
    launchSingleTop = true
}

/** The whole input flow; [viewModel] is activity-scoped, so state survives back and rotation (FLOW-07). */
@Composable
fun AppNavigation(viewModel: PlanFormViewModel) {
    val nav = rememberNavController()
    val form by viewModel.form.collectAsStateWithLifecycle()
    val questionLoad by viewModel.questionLoad.collectAsStateWithLifecycle()

    NavHost(navController = nav, startDestination = Routes.CERTIFICATION) {
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
        composable(Routes.PLAN) {
            val generation by viewModel.generation.collectAsStateWithLifecycle()
            val success = generation as? GenerationState.Success
            if (success == null) {
                LaunchedEffect(Unit) { nav.popBackStack(Routes.AVAILABILITY, inclusive = false) }
            } else {
                PlanScreen(success.response, success.isDemo) { nav.popBackStack(Routes.AVAILABILITY, inclusive = false) }
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
                    onEditDates = { nav.popBackStack(Routes.DATES, inclusive = false) },
                    onEditAvailability = { nav.popBackStack(Routes.AVAILABILITY, inclusive = false) },
                )
            }
        }
    }
}
