package com.swpp.licenseplanner

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import com.swpp.licenseplanner.ui.AppNavigation
import com.swpp.licenseplanner.ui.PlanFormViewModel
import com.swpp.licenseplanner.ui.theme.LicensePlannerTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LicensePlannerTheme {
                // Activity-scoped: one form state for the whole flow (design D4).
                val formViewModel: PlanFormViewModel = viewModel(factory = PlanFormViewModel.Factory)
                AppNavigation(formViewModel)
            }
        }
    }
}
