package com.omnicam.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import com.omnicam.feature.camera.DngCameraRoute

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            OmniCamTheme {
                val container = (application as OmniCamApplication).appContainer
                DngCameraRoute(
                    scanner = container.cameraCapabilityScanner,
                    controller = container.dngCameraController,
                )
            }
        }
    }
}

@Composable
private fun OmniCamTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme(), content = content)
}
