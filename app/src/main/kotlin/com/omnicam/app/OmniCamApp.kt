package com.omnicam.app

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.omnicam.camera.camerax.Camera2AuxPreviewController
import com.omnicam.camera.capability.CameraCapabilityScanner
import com.omnicam.feature.camera.CameraDiagnosticsRoute
import com.omnicam.feature.camera.CameraDiagnosticsViewModel
import com.omnicam.feature.camera.CameraLensTestRoute
import com.omnicam.feature.camera.LensPreferencesStore

private object Routes {
    const val Diagnostics = "diagnostics"
    const val LensTest = "lens-test"
}

@Composable
fun OmniCamApp(
    diagnosticsViewModel: CameraDiagnosticsViewModel,
    scanner: CameraCapabilityScanner,
    previewController: Camera2AuxPreviewController,
    lensPreferencesStore: LensPreferencesStore,
) {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = Routes.Diagnostics,
    ) {
        composable(Routes.Diagnostics) {
            CameraDiagnosticsRoute(
                viewModel = diagnosticsViewModel,
                onOpenLensTest = { navController.navigate(Routes.LensTest) },
            )
        }
        composable(Routes.LensTest) {
            CameraLensTestRoute(
                scanner = scanner,
                previewController = previewController,
                preferencesStore = lensPreferencesStore,
                onBack = { navController.popBackStack() },
            )
        }
    }
}
