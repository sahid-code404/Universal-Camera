package com.omnicam.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import com.omnicam.feature.camera.LiquidCameraRoute

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            OmniCamTheme {
                val container = (application as OmniCamApplication).appContainer
                LiquidCameraRoute(
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
