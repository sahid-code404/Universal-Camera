package com.omnicam.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import com.omnicam.feature.camera.CameraDiagnosticsViewModel

class MainActivity : ComponentActivity() {
    private val diagnosticsViewModel: CameraDiagnosticsViewModel by viewModels {
        CameraDiagnosticsViewModel.Factory((application as OmniCamApplication).appContainer.cameraCapabilityScanner)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            OmniCamTheme {
                OmniCamApp(diagnosticsViewModel = diagnosticsViewModel)
            }
        }
    }
}

@Composable
private fun OmniCamTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(),
        content = content,
    )
}
