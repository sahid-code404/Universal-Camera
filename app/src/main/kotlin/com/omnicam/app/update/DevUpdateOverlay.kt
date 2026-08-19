package com.omnicam.app.update

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun DevUpdateOverlay(manager: DevUpdateManager, modifier: Modifier = Modifier) {
    val state by manager.state.collectAsStateWithLifecycle()
    val activity = LocalContext.current.findActivity()
    LaunchedEffect(manager) { manager.check() }

    Box(modifier) {
        when (val current = state) {
            is DevUpdateState.Available -> Button(
                onClick = { manager.download(current.update) },
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 48.dp, end = 12.dp),
            ) { Text("Update available") }
            is DevUpdateState.Downloading -> StatusPill("Downloading update…", Modifier.align(Alignment.TopEnd).padding(top = 48.dp, end = 12.dp))
            is DevUpdateState.ReadyToInstall -> Button(
                onClick = { activity?.let { manager.install(it, current.apk) } },
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 48.dp, end = 12.dp),
            ) { Text("Install update") }
            is DevUpdateState.Error -> StatusPill(current.message, Modifier.align(Alignment.TopCenter).padding(top = 48.dp, start = 12.dp, end = 12.dp))
            DevUpdateState.Checking, DevUpdateState.Idle, DevUpdateState.UpToDate -> Unit
        }
    }
}

@Composable
private fun StatusPill(text: String, modifier: Modifier = Modifier) {
    Surface(modifier = modifier, shape = MaterialTheme.shapes.extraLarge, tonalElevation = 6.dp) {
        Text(text, Modifier.padding(horizontal = 14.dp, vertical = 9.dp), style = MaterialTheme.typography.bodySmall)
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
