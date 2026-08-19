package com.omnicam.feature.camera

import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.omnicam.camera.camerax.CameraBindResult
import com.omnicam.camera.camerax.CameraXPreviewController
import com.omnicam.camera.camerax.CaptureProbeResult
import com.omnicam.camera.capability.CameraCapabilityScanner
import com.omnicam.core.model.CameraDescriptor
import com.omnicam.core.model.DeviceCameraProfile
import com.omnicam.core.model.LensFacing
import com.omnicam.core.model.LensRole
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs

@Composable
fun CameraLensTestRoute(
    scanner: CameraCapabilityScanner,
    previewController: CameraXPreviewController,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val previewView = remember(context) {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }

    var profile by remember { mutableStateOf<DeviceCameraProfile?>(null) }
    var scanError by remember { mutableStateOf<String?>(null) }
    var selectedCameraId by remember { mutableStateOf<String?>(null) }
    var bindResult by remember { mutableStateOf<CameraBindResult?>(null) }
    var captureResult by remember { mutableStateOf<CaptureProbeResult?>(null) }
    var binding by remember { mutableStateOf(false) }
    var capturing by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        runCatching { scanner.scan() }
            .onSuccess { scanned ->
                profile = scanned
                selectedCameraId = chooseDefaultRearCamera(scanned)?.id
            }
            .onFailure { scanError = it.message ?: it::class.java.simpleName }
    }

    LaunchedEffect(selectedCameraId, lifecycleOwner) {
        val cameraId = selectedCameraId ?: return@LaunchedEffect
        binding = true
        captureResult = null
        bindResult = previewController.bind(
            lifecycleOwner = lifecycleOwner,
            previewView = previewView,
            cameraId = cameraId,
        )
        binding = false
    }

    DisposableEffect(Unit) {
        onDispose { previewController.unbind() }
    }

    Surface(modifier = modifier.fillMaxSize(), color = Color.Black) {
        when {
            scanError != null -> LensTestError(scanError.orEmpty(), onBack)
            profile == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            else -> {
                val scanned = profile ?: return@Surface
                val backCameras = scanned.cameras
                    .filter { it.directlyListed && it.lensFacing == LensFacing.BACK }
                    .sortedWith(compareBy<CameraDescriptor> { it.equivalentFocalLengthsMm.minOrNull() ?: Float.MAX_VALUE }.thenBy { it.id })
                val main = chooseDefaultRearCamera(scanned)
                val mainEq = main?.equivalentFocalLengthsMm?.minOrNull()

                Column(Modifier.fillMaxSize()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .background(Color.Black),
                    ) {
                        AndroidView(
                            factory = { previewView },
                            modifier = Modifier.fillMaxSize(),
                        )

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 14.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TextButton(onClick = onBack) {
                                Text("‹ Diagnostics", color = Color.White)
                            }
                            Text(
                                "LIVE AUX TEST",
                                color = Color.White,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                            )
                        }

                        val status = when (val result = bindResult) {
                            is CameraBindResult.Success -> "Requested ${result.requestedCameraId} · active ${result.actualCameraId}"
                            is CameraBindResult.Failure -> "Camera ${result.cameraId} failed: ${result.reason}"
                            null -> if (binding) "Opening camera…" else "Select a camera"
                        }
                        Surface(
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(top = 58.dp),
                            color = Color.Black.copy(alpha = 0.58f),
                            shape = RoundedCornerShape(18.dp),
                        ) {
                            Text(
                                status,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                                color = Color.White,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color.Black)
                            .padding(vertical = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            "${backCameras.size} exposed rear Camera2 IDs",
                            color = Color.White.copy(alpha = 0.72f),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Spacer(Modifier.height(8.dp))
                        LazyRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
                        ) {
                            items(backCameras, key = { it.id }) { camera ->
                                val selected = camera.id == selectedCameraId
                                val label = cameraZoomLabel(camera, mainEq)
                                if (selected) {
                                    Button(
                                        onClick = {},
                                        shape = CircleShape,
                                    ) {
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            Text(label)
                                            Text("ID ${camera.id}", style = MaterialTheme.typography.labelSmall)
                                        }
                                    }
                                } else {
                                    OutlinedButton(
                                        onClick = { selectedCameraId = camera.id },
                                        shape = CircleShape,
                                    ) {
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            Text(label, color = Color.White)
                                            Text("ID ${camera.id}", color = Color.White.copy(alpha = 0.65f), style = MaterialTheme.typography.labelSmall)
                                        }
                                    }
                                }
                            }
                        }

                        Spacer(Modifier.height(10.dp))
                        val selected = backCameras.firstOrNull { it.id == selectedCameraId }
                        if (selected != null) {
                            Text(
                                selectedLensDescription(selected),
                                color = Color.White,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }

                        Spacer(Modifier.height(12.dp))
                        Button(
                            enabled = bindResult is CameraBindResult.Success && !capturing,
                            onClick = {
                                capturing = true
                                scope.launch {
                                    captureResult = previewController.captureProbe()
                                    capturing = false
                                }
                            },
                            shape = CircleShape,
                        ) {
                            Text(if (capturing) "Capturing…" else "Capture probe")
                        }

                        val captureText = when (val result = captureResult) {
                            is CaptureProbeResult.Success -> "Capture OK · ID ${result.cameraId} · ${result.width}×${result.height} · format ${result.format}"
                            is CaptureProbeResult.Failure -> "Capture failed: ${result.reason}"
                            null -> "This probe keeps the frame in memory and does not save a photo."
                        }
                        Text(
                            captureText,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                            color = Color.White.copy(alpha = 0.72f),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LensTestError(message: String, onBack: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Lens test could not start", color = Color.White, style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(message, color = Color.White.copy(alpha = 0.72f))
        Spacer(Modifier.height(16.dp))
        Button(onClick = onBack) { Text("Back") }
    }
}

private fun chooseDefaultRearCamera(profile: DeviceCameraProfile): CameraDescriptor? = profile.cameras
    .filter { it.directlyListed && it.lensFacing == LensFacing.BACK }
    .minByOrNull { camera ->
        val eq = camera.equivalentFocalLengthsMm.minOrNull() ?: return@minByOrNull Float.MAX_VALUE
        val rolePenalty = if (camera.classification.role == LensRole.WIDE) 0f else 100f
        rolePenalty + abs(eq - 26f)
    }

private fun cameraZoomLabel(camera: CameraDescriptor, mainEq: Float?): String {
    val eq = camera.equivalentFocalLengthsMm.minOrNull()
    if (eq != null && mainEq != null && mainEq > 0f) {
        return String.format(Locale.US, "%.1f×", eq / mainEq)
    }
    return camera.classification.role.name.replace('_', ' ')
}

private fun selectedLensDescription(camera: CameraDescriptor): String {
    val eq = camera.equivalentFocalLengthsMm.minOrNull()
    val focal = camera.focalLengthsMm.minOrNull()
    return buildString {
        append(camera.classification.role.name.replace('_', ' '))
        append(" · Camera ")
        append(camera.id)
        if (eq != null) append(String.format(Locale.US, " · %.1f mm eq", eq))
        if (focal != null) append(String.format(Locale.US, " · %.2f mm native", focal))
        if (camera.isLogical) append(" · logical")
        if (camera.parentLogicalCameraIds.isNotEmpty()) append(" · member of ${camera.parentLogicalCameraIds.joinToString()}")
    }
}
