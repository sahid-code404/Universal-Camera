package com.omnicam.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import com.omnicam.camera.camerax.ComputationalRawController
import com.omnicam.feature.camera.ComputationalRawLabRoute

/** Temporary secondary launcher for hardware validation of the isolated C1 RAW fusion engine. */
class ComputationalRawActivity : ComponentActivity() {
    private val controller by lazy { ComputationalRawController(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                val container = (application as OmniCamApplication).appContainer
                ComputationalRawLabRoute(
                    scanner = container.cameraCapabilityScanner,
                    controller = controller,
                    onBack = { finish() },
                )
            }
        }
    }

    override fun onDestroy() {
        controller.shutdown()
        super.onDestroy()
    }
}
