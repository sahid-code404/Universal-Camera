package com.omnicam.feature.camera

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.omnicam.camera.capability.toSanitizedJson
import com.omnicam.core.model.CameraDescriptor
import com.omnicam.core.model.DeviceCameraProfile
import java.util.Locale

@Composable
fun CameraDiagnosticsRoute(
    viewModel: CameraDiagnosticsViewModel,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    var permissionGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED,
        )
    }
    var pendingExport by remember { mutableStateOf<String?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionGranted = granted
        if (granted) viewModel.scan(force = true)
    }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        val payload = pendingExport
        if (uri != null && payload != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { writer ->
                    writer.write(payload)
                }
            }
        }
        pendingExport = null
    }

    LaunchedEffect(permissionGranted) {
        if (permissionGranted) viewModel.scan()
    }

    Surface(modifier = modifier.fillMaxSize()) {
        if (!permissionGranted) {
            PermissionContent(onRequestPermission = { permissionLauncher.launch(Manifest.permission.CAMERA) })
        } else {
            DiagnosticsContent(
                state = state,
                onRetry = { viewModel.scan(force = true) },
                onExport = { profile ->
                    pendingExport = profile.toSanitizedJson()
                    exportLauncher.launch("omnicam-device-report.json")
                },
            )
        }
    }
}

@Composable
private fun PermissionContent(onRequestPermission: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("OmniCam", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(12.dp))
        Text(
            "Camera permission is needed to inspect the camera hardware that Android exposes to this app. No photos are uploaded.",
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onRequestPermission) { Text("Allow camera access") }
    }
}

@Composable
private fun DiagnosticsContent(
    state: CameraDiagnosticsUiState,
    onRetry: () -> Unit,
    onExport: (DeviceCameraProfile) -> Unit,
) {
    when (state) {
        CameraDiagnosticsUiState.Idle, CameraDiagnosticsUiState.Scanning -> Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CircularProgressIndicator()
            Spacer(Modifier.height(16.dp))
            Text("Inspecting exposed camera hardware…")
        }

        is CameraDiagnosticsUiState.Error -> Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(28.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Camera discovery failed", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
            Text(state.message)
            Spacer(Modifier.height(20.dp))
            Button(onClick = onRetry) { Text("Try again") }
        }

        is CameraDiagnosticsUiState.Ready -> {
            val profile = state.profile
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Text(
                        "OmniCam Camera Diagnostics",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "${profile.manufacturer} ${profile.model} · Android API ${profile.sdkInt}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "${profile.cameras.count { it.directlyListed }} public camera IDs · ${profile.logicalGroups.size} logical multi-camera groups",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(onClick = { onExport(profile) }) { Text("Export JSON") }
                        OutlinedButton(onClick = onRetry) { Text("Rescan") }
                    }
                }
                items(profile.cameras, key = { it.id }) { camera -> CameraCard(camera) }
                item {
                    Text(
                        "Physical members that are not directly listed are shown for diagnostics only. Their presence does not imply that Android allows them to be opened independently.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun CameraCard(camera: CameraDescriptor) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    "Camera ${camera.id}",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(camera.classification.role.name.replace('_', ' '), style = MaterialTheme.typography.labelLarge)
            }
            Text(
                "${camera.lensFacing} · ${camera.hardwareLevel} · confidence ${(camera.classification.confidence * 100).toInt()}%",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(camera.classification.reason, style = MaterialTheme.typography.bodySmall)

            camera.equivalentFocalLengthsMm.takeIf { it.isNotEmpty() }?.let { values ->
                Text("35mm eq: ${values.joinToString { String.format(Locale.US, "%.1f mm", it) }}")
            }
            camera.focalLengthsMm.takeIf { it.isNotEmpty() }?.let { values ->
                Text("Native focal: ${values.joinToString { String.format(Locale.US, "%.2f mm", it) }}")
            }
            camera.physicalCameraIds.takeIf { it.isNotEmpty() }?.let { Text("Physical members: ${it.joinToString()}") }
            camera.parentLogicalCameraIds.takeIf { it.isNotEmpty() }?.let { Text("Member of logical: ${it.joinToString()}") }

            Text(
                listOfNotNull(
                    "RAW".takeIf { camera.rawSupported },
                    "Manual".takeIf { camera.manualSensorSupported },
                    "Burst".takeIf { camera.burstCaptureSupported },
                    "OIS".takeIf { camera.opticalStabilizationAvailable },
                    "Flash".takeIf { camera.flashAvailable },
                    "Depth".takeIf { camera.depthOutputSupported },
                ).ifEmpty { listOf("Basic public capabilities only") }.joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
            )
            if (!camera.directlyListed) {
                Text(
                    "Physical member; not directly listed as an openable camera ID",
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}
