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
import com.omnicam.camera.capability.CameraRouteAccess
import com.omnicam.camera.capability.ValuableCameraResolver
import com.omnicam.camera.capability.ValuableCameraRoute
import com.omnicam.camera.capability.toSanitizedJson
import com.omnicam.core.model.CameraDescriptor
import com.omnicam.core.model.DeviceCameraProfile
import com.omnicam.core.model.PublicCameraExposureAssessment
import java.util.Locale

@Composable
fun CameraDiagnosticsRoute(
    viewModel: CameraDiagnosticsViewModel,
    onOpenLensTest: () -> Unit,
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
                onOpenLensTest = onOpenLensTest,
                onExport = { profile ->
                    pendingExport = profile.toSanitizedJson()
                    exportLauncher.launch("omnicam-snapcam-aux-identity-report.json")
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
        Text("OmniCam Aux Identity Test", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(12.dp))
        Text(
            "Temporary diagnostic build using a Snapcam-compatible package identity to test vendor auxiliary-camera filtering. No photos are uploaded.",
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
    onOpenLensTest: () -> Unit,
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
            val resolution = remember(profile) { ValuableCameraResolver.resolve(profile) }
            val rawCount = profile.cameras.count { it.directlyListed }
            var showRawRoutes by remember(profile.scannedAtEpochMillis) { mutableStateOf(false) }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Text(
                        "OmniCam Useful Camera Resolver",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "${profile.manufacturer} ${profile.model} · Android API ${profile.sdkInt}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "Client package: ${profile.clientPackageName}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "${resolution.valuableRoutes.size} useful cameras · $rawCount raw Camera2 routes · ${profile.logicalGroups.size} logical groups",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        "Rear ${resolution.rearRoutes.size} · Front ${resolution.frontRoutes.size}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(onClick = onOpenLensTest) { Text("Open useful lens test") }
                        OutlinedButton(onClick = { onExport(profile) }) { Text("Export JSON") }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(onClick = onRetry) { Text("Rescan") }
                        OutlinedButton(onClick = { showRawRoutes = !showRawRoutes }) {
                            Text(if (showRawRoutes) "Hide raw routes" else "Show raw routes")
                        }
                    }
                }

                item { UsefulResolutionCard(profile, resolution.valuableRoutes, resolution.excludedRoutes.size) }
                items(resolution.valuableRoutes, key = { "useful-${it.camera.id}" }) { route ->
                    ValuableCameraCard(route)
                }

                if (showRawRoutes) {
                    item { PublicExposureProbeCard(profile) }
                    item {
                        Text(
                            "Raw Camera2 graph · developer diagnostics",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    items(profile.cameras, key = { "raw-${it.id}" }) { camera -> CameraCard(camera) }
                }

                item {
                    Text(
                        "Normal UI uses only resolved useful camera routes. Logical aggregators and strong duplicate aliases remain available in raw diagnostics, not as extra user lenses.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun UsefulResolutionCard(
    profile: DeviceCameraProfile,
    routes: List<ValuableCameraRoute>,
    excludedCount: Int,
) {
    val directCount = routes.count { it.access == CameraRouteAccess.DIRECT_CAMERA_DEVICE }
    val logicalPhysicalCount = routes.count { it.access == CameraRouteAccess.PHYSICAL_VIA_LOGICAL }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Resolved user cameras", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text("${routes.size} useful routes · $directCount direct · $logicalPhysicalCount physical-via-logical")
            Text("$excludedCount raw routes hidden from normal lens UI")
            Text(
                "The resolver is capability/metadata-driven; no ${profile.model}-specific camera ID table is used.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun ValuableCameraCard(route: ValuableCameraRoute) {
    val camera = route.camera
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Camera ${camera.id}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(camera.classification.role.name.replace('_', ' '), style = MaterialTheme.typography.labelLarge)
            }
            val access = when (route.access) {
                CameraRouteAccess.DIRECT_CAMERA_DEVICE -> "Direct Camera2 device"
                CameraRouteAccess.PHYSICAL_VIA_LOGICAL ->
                    "Physical via logical ${route.logicalCameraIds.joinToString()}"
            }
            Text(access, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            camera.equivalentFocalLengthsMm.takeIf { it.isNotEmpty() }?.let { values ->
                Text("35mm eq: ${values.joinToString { String.format(Locale.US, "%.1f mm", it) }}")
            }
            camera.focalLengthsMm.takeIf { it.isNotEmpty() }?.let { values ->
                Text("Native focal: ${values.joinToString { String.format(Locale.US, "%.2f mm", it) }}")
            }
            Text(
                listOfNotNull(
                    "RAW".takeIf { camera.rawSupported },
                    "Manual".takeIf { camera.manualSensorSupported },
                    "Burst".takeIf { camera.burstCaptureSupported },
                    "OIS".takeIf { camera.opticalStabilizationAvailable },
                    "Flash".takeIf { camera.flashAvailable },
                ).ifEmpty { listOf("Standard photographic route") }.joinToString(" · "),
            )
        }
    }
}

@Composable
private fun PublicExposureProbeCard(profile: DeviceCameraProfile) {
    val camera2Count = profile.cameras.count { it.directlyListed }
    val legacyCount = profile.legacyCameraCount?.toString() ?: "unavailable"
    val assessmentText = when (profile.publicExposureAssessment) {
        PublicCameraExposureAssessment.MULTIPLE_CAMERA2_IDS ->
            "The Snapcam client identity exposes multiple Camera2 devices on at least one side. This strongly supports vendor package filtering as the reason the normal OmniCam identity saw fewer cameras."
        PublicCameraExposureAssessment.LOGICAL_MULTI_CAMERA_EXPOSED ->
            "The Snapcam client identity exposes a logical multi-camera group. Its physical members can be tested through public logical/physical camera APIs."
        PublicCameraExposureAssessment.LEGACY_API_SEES_ADDITIONAL_CAMERAS ->
            "Camera1 sees more devices than Camera2 under this identity. A vendor-specific legacy capture test is warranted."
        PublicCameraExposureAssessment.UNLISTED_NUMERIC_CAMERA_CHARACTERISTICS_READABLE ->
            "At least one unlisted numeric Camera2 ID returned characteristics. This is diagnostic-only evidence of a vendor filtering quirk; it is not yet proof that the camera can be opened."
        PublicCameraExposureAssessment.AUXILIARY_NOT_EXPOSED_BY_STANDARD_DISCOVERY ->
            "Even the Snapcam client identity did not expose additional cameras through Camera2 or Camera1 enumeration. Package-name allowlisting alone is not sufficient on this ROM."
        PublicCameraExposureAssessment.UNKNOWN ->
            "The auxiliary exposure result is inconclusive."
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Raw Snapcam identity exposure probe", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text("Package: ${profile.clientPackageName}", style = MaterialTheme.typography.bodySmall)
            Text("Camera2 IDs: $camera2Count · Camera1 devices: $legacyCount · logical groups: ${profile.logicalGroups.size}")
            if (profile.legacyCameras.isNotEmpty()) {
                Text(
                    "Camera1: " + profile.legacyCameras.joinToString { camera ->
                        "#${camera.index} ${camera.lensFacing} ${camera.orientationDegrees}°"
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (profile.numericCameraIdProbeReadableIds.isNotEmpty()) {
                Text(
                    "Readable unlisted numeric Camera2 IDs: ${profile.numericCameraIdProbeReadableIds.joinToString()}",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            if (profile.concurrentCameraIdSets.isNotEmpty()) {
                Text(
                    "Concurrent Camera2 sets: ${profile.concurrentCameraIdSets.joinToString { it.joinToString(prefix = "[", postfix = "]") }}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text(assessmentText, style = MaterialTheme.typography.bodyMedium)
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
                    "Raw Camera ${camera.id}",
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
                    "Physical member; not directly listed as an independent camera ID",
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}
