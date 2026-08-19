package com.omnicam.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.omnicam.app.update.DevUpdateManager
import com.omnicam.app.update.DevUpdateOverlay
import com.omnicam.camera.camerax.Camera2AuxPreviewController
import com.omnicam.camera.camerax.Camera2PhotoController
import com.omnicam.camera.capability.CameraCapabilityScanner
import com.omnicam.feature.camera.CameraDiagnosticsRoute
import com.omnicam.feature.camera.CameraDiagnosticsViewModel
import com.omnicam.feature.camera.CameraLensTestRoute
import com.omnicam.feature.camera.LensPreferencesStore
import com.omnicam.feature.camera.UniversalCameraRoute

private object Routes {
    const val Camera = "camera"
    const val Diagnostics = "diagnostics"
    const val LensTest = "lens-test"
}

@Composable
fun OmniCamApp(
    diagnosticsViewModel: CameraDiagnosticsViewModel,
    scanner: CameraCapabilityScanner,
    previewController: Camera2AuxPreviewController,
    photoController: Camera2PhotoController,
    lensPreferencesStore: LensPreferencesStore,
    devUpdateManager: DevUpdateManager,
) {
    val navController = rememberNavController()

    Box(Modifier.fillMaxSize()) {
        NavHost(
            navController = navController,
            startDestination = Routes.Camera,
        ) {
            composable(Routes.Camera) {
                UniversalCameraRoute(
                    scanner = scanner,
                    controller = photoController,
                    preferencesStore = lensPreferencesStore,
                    onOpenDiagnostics = { navController.navigate(Routes.Diagnostics) },
                )
            }
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
        DevUpdateOverlay(
            manager = devUpdateManager,
            modifier = Modifier.fillMaxSize(),
        )
    }
}
