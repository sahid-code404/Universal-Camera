package com.omnicam.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.omnicam.camera.camerax.PreviewExposureGuard
import com.omnicam.feature.camera.SurfaceCameraRoute
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            OmniCamTheme {
                val container = (application as OmniCamApplication).appContainer

                // Re-apply only when the controller creates a new preview builder (lens/aspect/rebind).
                // The guard itself de-duplicates builders, so this does not continuously resubmit
                // capture requests or interfere with tap-to-focus after it has been applied.
                LaunchedEffect(container.lightningRawController) {
                    while (true) {
                        PreviewExposureGuard.apply(container.lightningRawController)
                        delay(250)
                    }
                }

                SurfaceCameraRoute(
                    scanner = container.cameraCapabilityScanner,
                    controller = container.lightningRawController,
                )
            }
        }
    }
}

@Composable
private fun OmniCamTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme(), content = content)
}
