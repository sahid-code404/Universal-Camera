package com.omnicam.feature.camera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.viewfinder.compose.Viewfinder
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.omnicam.camera.camerax.ComputationalRawBindResult
import com.omnicam.camera.camerax.ComputationalRawCaptureResult
import com.omnicam.camera.camerax.ComputationalRawController
import com.omnicam.camera.camerax.ComputationalRawPreset
import com.omnicam.camera.camerax.ComputationalRawViewfinderSpec
import com.omnicam.camera.capability.CameraCapabilityScanner
import com.omnicam.camera.capability.CameraRouteAccess
import com.omnicam.camera.capability.ValuableCameraResolver
import com.omnicam.camera.capability.ValuableCameraRoute
import com.omnicam.core.model.LensFacing
import java.util.Locale
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch

@Composable
fun ComputationalRawLabRoute(
    scanner: CameraCapabilityScanner,
    controller: ComputationalRawController,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    var permissionGranted by remember { mutableStateOf(hasCameraPermission(context)) }
    var routes by remember { mutableStateOf<List<ValuableCameraRoute>>(emptyList()) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var scanError by remember { mutableStateOf<String?>(null) }
    var spec by remember { mutableStateOf<ComputationalRawViewfinderSpec?>(null) }
    var bindResult by remember { mutableStateOf<ComputationalRawBindResult?>(null) }
    var preset by remember { mutableStateOf(ComputationalRawPreset.MAX) }
    var capturing by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf("C2 ready") }
    var result by remember { mutableStateOf<ComputationalRawCaptureResult?>(null) }
    var latestUri by remember { mutableStateOf<Uri?>(null) }
    var lifecycleResumed by remember {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> permissionGranted = granted || hasCameraPermission(context) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            lifecycleResumed = when (event) {
                Lifecycle.Event.ON_RESUME -> true
                Lifecycle.Event.ON_PAUSE,
                Lifecycle.Event.ON_STOP,
                Lifecycle.Event.ON_DESTROY,
                -> false
                else -> lifecycleResumed
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(Unit) {
        if (!permissionGranted) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    LaunchedEffect(permissionGranted) {
        if (!permissionGranted) return@LaunchedEffect
        scanError = null
        runCatching { scanner.scan() }
            .onSuccess { profile ->
                routes = ValuableCameraResolver.resolve(profile).valuableRoutes
                    .filter {
                        it.access == CameraRouteAccess.DIRECT_CAMERA_DEVICE &&
                            it.camera.rawSupported &&
                            it.camera.lensFacing in setOf(LensFacing.BACK, LensFacing.FRONT)
                    }
                    .sortedWith(
                        compareBy<ValuableCameraRoute> { if (it.camera.lensFacing == LensFacing.BACK) 0 else 1 }
                            .thenBy { it.camera.equivalentFocalLengthsMm.minOrNull() ?: Float.MAX_VALUE },
                    )
                if (selectedId !in routes.map { it.camera.id }) {
                    selectedId = chooseCrawDefault(routes)?.camera?.id
                }
            }
            .onFailure { scanError = it.message ?: it::class.java.simpleName }
    }

    val selected = routes.firstOrNull { it.camera.id == selectedId }

    LaunchedEffect(selected?.camera?.id, lifecycleResumed) {
        controller.unbind()
        bindResult = null
        result = null
        if (!lifecycleResumed) {
            spec = null
            return@LaunchedEffect
        }
        val route = selected ?: run {
            spec = null
            return@LaunchedEffect
        }
        spec = runCatching {
            controller.createViewfinderSpec(route, sessionKey = "${route.camera.id}-${System.nanoTime()}")
        }.onFailure {
            bindResult = ComputationalRawBindResult.Failure(
                route.camera.id,
                it.message ?: it::class.java.simpleName,
            )
        }.getOrNull()
    }

    DisposableEffect(Unit) {
        onDispose { controller.unbind() }
    }

    Surface(modifier.fillMaxSize(), color = Color.Black) {
        when {
            !permissionGranted -> LabMessage(
                title = "OmniCam C-RAW",
                message = "Camera permission is required.",
                action = "Allow camera",
                onAction = { permissionLauncher.launch(Manifest.permission.CAMERA) },
            )
            scanError != null -> LabMessage("Camera scan failed", scanError.orEmpty())
            routes.isEmpty() -> LabMessage(
                "No C-RAW route",
                "No independently openable useful camera currently exposes RAW_SENSOR.",
            )
            else -> Column(Modifier.fillMaxSize().background(Color.Black)) {
                Row(
                    Modifier.fillMaxWidth().padding(top = 28.dp, start = 8.dp, end = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onBack) { Text("Back", color = Color.White) }
                    Column(Modifier.weight(1f)) {
                        Text(
                            "OmniCam Computational RAW · C2",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            "RAW burst → align/fuse → demosaic → WB/color → HDR tone map → HEIF + DNG",
                            color = Color.White.copy(alpha = 0.65f),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }

                Box(
                    Modifier.fillMaxWidth().weight(1f).background(Color.Black),
                    contentAlignment = Alignment.Center,
                ) {
                    val currentSpec = spec
                    val route = selected
                    if (currentSpec != null && route != null && lifecycleResumed) {
                        Viewfinder(
                            surfaceRequest = currentSpec.surfaceRequest,
                            transformationInfo = currentSpec.transformationInfo,
                            alignment = Alignment.Center,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            onSurfaceSession {
                                bindResult = controller.bind(surface, currentSpec, route)
                                try {
                                    awaitCancellation()
                                } finally {
                                    controller.unbind()
                                }
                            }
                        }
                    } else {
                        CircularProgressIndicator()
                    }

                    (bindResult as? ComputationalRawBindResult.Success)?.let { bound ->
                        Surface(
                            modifier = Modifier.align(Alignment.TopCenter).padding(10.dp),
                            shape = RoundedCornerShape(16.dp),
                            color = Color.Black.copy(alpha = 0.72f),
                        ) {
                            Text(
                                buildString {
                                    append("RAW ")
                                    append(bound.rawWidth)
                                    append('×')
                                    append(bound.rawHeight)
                                    if (bound.maximumResolutionMode) append(" · MAX SENSOR MODE")
                                },
                                Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                                color = Color.White,
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                    }
                }

                Column(
                    Modifier.fillMaxWidth().background(Color.Black).padding(bottom = 18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    LazyRow(
                        Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        items(routes, key = { it.camera.id }) { route ->
                            val selectedLens = route.camera.id == selectedId
                            if (selectedLens) {
                                Button(onClick = { selectedId = route.camera.id }) {
                                    Text(crawLensLabel(route))
                                }
                            } else {
                                TextButton(onClick = { selectedId = route.camera.id }) {
                                    Text(crawLensLabel(route), color = Color.White)
                                }
                            }
                        }
                    }

                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                    ) {
                        ComputationalRawPreset.entries.forEach { item ->
                            if (item == preset) {
                                Button(onClick = { preset = item }, enabled = !capturing) {
                                    Text(item.name)
                                }
                            } else {
                                TextButton(onClick = { preset = item }, enabled = !capturing) {
                                    Text(item.name, color = Color.White)
                                }
                            }
                        }
                    }
                    Text(
                        preset.label,
                        color = Color.White.copy(alpha = 0.8f),
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Text(
                        progress,
                        color = Color.White.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 5.dp),
                    )

                    Button(
                        enabled = !capturing && bindResult is ComputationalRawBindResult.Success,
                        onClick = {
                            if (capturing) return@Button
                            capturing = true
                            result = null
                            scope.launch {
                                val captured = controller.capture(preset) { progress = it }
                                result = captured
                                if (captured is ComputationalRawCaptureResult.Success) {
                                    latestUri = captured.uri
                                    progress = "Saved C2 computational HEIF + merged DNG"
                                } else if (captured is ComputationalRawCaptureResult.Failure) {
                                    progress = captured.message
                                }
                                capturing = false
                            }
                        },
                    ) {
                        if (capturing) {
                            CircularProgressIndicator(Modifier.height(22.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.padding(horizontal = 5.dp))
                            Text("Computational processing…")
                        } else {
                            Text("Capture ${preset.frameCount}-frame C-RAW")
                        }
                    }

                    CrawResult(result)
                    latestUri?.let {
                        Text(
                            "Saved HEIF + merged DNG to DCIM/OmniCam/C-RAW",
                            color = Color.White.copy(alpha = 0.55f),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Text(
                        "C2 now produces a finished HEIF from the fused RAW buffer using OmniCam's software demosaic, Camera2 white balance/color matrix and HDR tone curve. The ~24 MB DNG is still saved as the merged 16-bit Bayer expert sidecar; its fixed size is expected for 4000×3000 RAW16.",
                        color = Color.White.copy(alpha = 0.55f),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 7.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun CrawResult(result: ComputationalRawCaptureResult?) {
    when (result) {
        is ComputationalRawCaptureResult.Failure -> Text(
            result.message,
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(8.dp),
        )
        is ComputationalRawCaptureResult.Success -> {
            val total = result.acceptedSamples + result.rejectedSamples
            val acceptedPercent = if (total > 0L) result.acceptedSamples * 100.0 / total else 0.0
            val moves = result.alignments.joinToString(" ") {
                "(${it.dx},${it.dy};${String.format(Locale.US, "%.2f", it.confidence)})"
            }
            Text(
                buildString {
                    append("Saved C2 HEIF ")
                    append(result.width)
                    append('×')
                    append(result.height)
                    append(" + merged DNG · ")
                    append(result.frameCount)
                    append(" RAW · ")
                    append(String.format(Locale.US, "%.1f%%", acceptedPercent))
                    append(" merge samples · total ")
                    append(String.format(Locale.US, "%.1fs", result.elapsedMillis / 1000.0))
                    append(" · C2 ")
                    append(String.format(Locale.US, "%.1fs", result.c2ProcessingMillis / 1000.0))
                    if (result.maximumResolutionMode) append(" · max sensor mode")
                    append("\nWB metadata: ")
                    append(if (result.whiteBalanceFromCamera) "camera" else "fallback")
                    append(" · color matrix: ")
                    append(if (result.colorTransformFromCamera) "camera" else "fallback")
                    append("\nAlignment dx/dy/conf: ")
                    append(moves)
                },
                color = Color.White.copy(alpha = 0.78f),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
            )
        }
        null -> Unit
    }
}

@Composable
private fun LabMessage(
    title: String,
    message: String,
    action: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        Modifier.fillMaxSize().padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, color = Color.White, style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text(message, color = Color.White.copy(alpha = 0.75f))
        if (action != null && onAction != null) {
            Spacer(Modifier.height(16.dp))
            Button(onClick = onAction) { Text(action) }
        }
    }
}

private fun hasCameraPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

private fun chooseCrawDefault(routes: List<ValuableCameraRoute>): ValuableCameraRoute? =
    routes.firstOrNull { it.camera.lensFacing == LensFacing.BACK && it.camera.classification.role.name == "WIDE" }
        ?: routes.firstOrNull { it.camera.lensFacing == LensFacing.BACK }
        ?: routes.firstOrNull()

private fun crawLensLabel(route: ValuableCameraRoute): String {
    val facing = if (route.camera.lensFacing == LensFacing.FRONT) "Front" else "Rear"
    val role = route.camera.classification.role.name.replace('_', ' ')
    return "$facing $role · ${route.camera.id}"
}
