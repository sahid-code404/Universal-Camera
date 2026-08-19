package com.omnicam.feature.camera

import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import com.omnicam.camera.camerax.Camera2AuxPreviewController
import com.omnicam.camera.camerax.CameraBindResult
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
    previewController: Camera2AuxPreviewController,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val textureView = remember(context) { TextureView(context) }

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

    LaunchedEffect(selectedCameraId) {
        val cameraId = selectedCameraId ?: return@LaunchedEffect
        binding = true
        captureResult = null
        bindResult = previewController.bind(
            textureView = textureView,
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
            else -> LensTestContent(
                profile = requireNotNull(profile),
                textureView = textureView,
                selectedCameraId = selectedCameraId,
                bindResult = bindResult,
                captureResult = captureResult,
                binding = binding,
                capturing = capturing,
                onBack = onBack,
                onSelectCamera = { selectedCameraId = it },
                onCapture = {
                    capturing = true
                    scope.launch {
                        captureResult = previewController.captureProbe()
                        capturing = false
                    }
                },
            )
        }
    }
}

@Composable
private fun LensTestContent(
    profile: DeviceCameraProfile,
    textureView: TextureView,
    selectedCameraId: String?,
    bindResult: CameraBindResult?,
    captureResult: CaptureProbeResult?,
    binding: Boolean,
    capturing: Boolean,
    onBack: () -> Unit,
    onSelectCamera: (String) -> Unit,
    onCapture: () -> Unit,
) {
    val backCameras = profile.cameras
        .filter { it.directlyListed && it.lensFacing == LensFacing.BACK }
        .sortedWith(
            compareBy<CameraDescriptor> { it.equivalentFocalLengthsMm.minOrNull() ?: Float.MAX_VALUE }
                .thenBy { it.id },
        )
    val mainEq = chooseDefaultRearCamera(profile)?.equivalentFocalLengthsMm?.minOrNull()

    Column(Modifier.fillMaxSize().background(Color.Black)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(430.dp)
                .background(Color.Black),
        ) {
            AndroidView(
                factory = { textureView },
                modifier = Modifier.fillMaxSize(),
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onBack) { Text("‹ Diagnostics", color = Color.White) }
                Text(
                    "DIRECT CAMERA2 AUX TEST",
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                )
            }

            Surface(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 60.dp),
                color = Color.Black.copy(alpha = 0.62f),
                shape = RoundedCornerShape(18.dp),
            ) {
                Text(
                    bindStatus(bindResult, binding),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                    color = Color.White,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "${backCameras.size} exposed rear Camera2 IDs · direct open",
                color = Color.White.copy(alpha = 0.72f),
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))

            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(horizontal = 16.dp),
            ) {
                items(backCameras, key = { it.id }) { camera ->
                    LensButton(
                        camera = camera,
                        label = cameraZoomLabel(camera, mainEq),
                        selected = camera.id == selectedCameraId,
                        onClick = { onSelectCamera(camera.id) },
                    )
                }
            }

            Spacer(Modifier.height(10.dp))
            backCameras.firstOrNull { it.id == selectedCameraId }?.let { selected ->
                Text(
                    selectedLensDescription(selected),
                    modifier = Modifier.padding(horizontal = 16.dp),
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            Spacer(Modifier.height(12.dp))
            Button(
                enabled = bindResult is CameraBindResult.Success && !capturing,
                onClick = onCapture,
                shape = CircleShape,
            ) {
                Text(if (capturing) "Reading frame…" else "Frame probe")
            }

            Text(
                captureStatus(captureResult),
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                color = Color.White.copy(alpha = 0.72f),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun LensButton(
    camera: CameraDescriptor,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val content: @Composable () -> Unit = {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, color = if (selected) MaterialTheme.colorScheme.onPrimary else Color.White)
            Text(
                "ID ${camera.id}",
                color = if (selected) MaterialTheme.colorScheme.onPrimary else Color.White.copy(alpha = 0.65f),
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }

    if (selected) {
        Button(onClick = onClick, shape = CircleShape) { content() }
    } else {
        OutlinedButton(onClick = onClick, shape = CircleShape) { content() }
    }
}

@Composable
private fun LensTestError(message: String, onBack: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
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

private fun bindStatus(result: CameraBindResult?, binding: Boolean): String = when (result) {
    is CameraBindResult.Success -> "Camera2 direct · requested ${result.requestedCameraId} · active ${result.actualCameraId}"
    is CameraBindResult.Failure -> "Camera ${result.cameraId} failed: ${result.reason}"
    null -> if (binding) "Opening exact Camera2 ID…" else "Select a camera"
}

private fun captureStatus(result: CaptureProbeResult?): String = when (result) {
    is CaptureProbeResult.Success ->
        "Frame OK · ID ${result.cameraId} · ${result.width}×${result.height} · format ${result.format}"
    is CaptureProbeResult.Failure -> "Frame probe failed: ${result.reason}"
    null -> "Direct Camera2 bypasses CameraX filtering. Frame probe reads the live preview in memory."
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
        if (camera.parentLogicalCameraIds.isNotEmpty()) {
            append(" · member of ${camera.parentLogicalCameraIds.joinToString()}")
        }
    }
}
