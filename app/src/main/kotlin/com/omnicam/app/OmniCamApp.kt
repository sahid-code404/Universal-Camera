package com.omnicam.app

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.omnicam.feature.camera.CameraDiagnosticsRoute
import com.omnicam.feature.camera.CameraDiagnosticsViewModel

private object Routes {
    const val Diagnostics = "diagnostics"
}

@Composable
fun OmniCamApp(
    diagnosticsViewModel: CameraDiagnosticsViewModel,
) {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = Routes.Diagnostics,
    ) {
        composable(Routes.Diagnostics) {
            CameraDiagnosticsRoute(viewModel = diagnosticsViewModel)
        }
    }
}
