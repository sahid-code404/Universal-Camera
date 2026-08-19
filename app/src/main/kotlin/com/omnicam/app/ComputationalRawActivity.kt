package com.omnicam.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import com.omnicam.feature.camera.ComputationalRawLabRoute

/** Temporary secondary launcher for hardware validation of the isolated computational RAW engine. */
class ComputationalRawActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                val container = (application as OmniCamApplication).appContainer
                ComputationalRawLabRoute(
                    scanner = container.cameraCapabilityScanner,
                    controller = container.computationalRawController,
                    onBack = { finish() },
                )
            }
        }
    }
}
