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
import androidx.compose.runtime.collectAsState
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
import com.omnicam.camera.camerax.ComputationalRawJobStage
import com.omnicam.camera.camerax.ComputationalRawJobStatus
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
    val jobs by controller.jobs.collectAsState()
    val activeJobs = jobs.filterNot { it.terminal }
    val queueFull = activeJobs.size >= 3

    var permissionGranted by remember { mutableStateOf(hasCameraPermission(context)) }
    var routes by remember { mutableStateOf<List<ValuableCameraRoute>>(emptyList()) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var scanError by remember { mutableStateOf<String?>(null) }
    var spec by remember { mutableStateOf<ComputationalRawViewfinderSpec?>(null) }
    var bindResult by remember { mutableStateOf<ComputationalRawBindResult?>(null) }
    var preset by remember { mutableStateOf(ComputationalRawPreset.MAX) }
    var capturing by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf("C1.6 realtime DNG ready") }
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

    LaunchedEffect(jobs) {
        val saved = jobs.firstOrNull { it.stage == ComputationalRawJobStage.SAVED && it.uri != null }
        if (saved?.uri != null) latestUri = saved.uri
        if (!capturing) {
            val current = jobs.firstOrNull { !it.terminal }
            when {
                current != null -> progress = current.message + " · shutter ready"
                saved != null -> progress = "Saved ${saved.preset.name} DNG · shutter ready"
            }
        }
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
                            "OmniCam Computational RAW · C1.6",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            "RAW burst → preview resumes → background align/denoise/fuse → DNG",
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
                                Button(onClick = { selectedId = route.camera.id }, enabled = !capturing) {
                                    Text(crawLensLabel(route))
                                }
                            } else {
                                TextButton(onClick = { selectedId = route.camera.id }, enabled = !capturing) {
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

                    BackgroundRawJobs(jobs)

                    Button(
                        enabled = !capturing && !queueFull && bindResult is ComputationalRawBindResult.Success,
                        onClick = {
                            if (capturing) return@Button
                            capturing = true
                            result = null
                            scope.launch {
                                val captured = controller.capture(preset) { progress = it }
                                result = captured
                                when (captured) {
                                    is ComputationalRawCaptureResult.Queued -> {
                                        progress = "Burst #${captured.jobId} queued · processing in background · shutter ready"
                                    }
                                    is ComputationalRawCaptureResult.Failure -> progress = captured.message
                                }
                                capturing = false
                            }
                        },
                    ) {
                        if (capturing) {
                            CircularProgressIndicator(Modifier.height(22.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.padding(horizontal = 5.dp))
                            Text("Capturing RAW burst…")
                        } else if (queueFull) {
                            Text("Background queue full")
                        } else {
                            Text("Capture ${preset.frameCount}-frame C-RAW")
                        }
                    }

                    CrawResult(result)
                    latestUri?.let {
                        Text(
                            "Latest saved DNG: DCIM/OmniCam/C-RAW",
                            color = Color.White.copy(alpha = 0.55f),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Text(
                        "Realtime DNG-only mode: the shutter is held only during RAW acquisition. Alignment, temporal denoise, Bayer fusion and DNG writing continue in a low-priority background queue while preview and the next capture stay available.",
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
private fun BackgroundRawJobs(jobs: List<ComputationalRawJobStatus>) {
    val active = jobs.filterNot { it.terminal }
    val latest = jobs.firstOrNull { it.terminal }
    if (active.isEmpty() && latest == null) return

    Column(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (active.isNotEmpty()) {
            val processing = active.count { it.stage == ComputationalRawJobStage.PROCESSING || it.stage == ComputationalRawJobStage.SAVING }
            val queued = active.count { it.stage == ComputationalRawJobStage.QUEUED }
            Text(
                "Background: $processing processing · $queued queued · preview/shutter live",
                color = Color.White.copy(alpha = 0.82f),
                style = MaterialTheme.typography.labelMedium,
            )
            active.take(2).forEach { job ->
                Text(
                    "#${job.id} ${job.preset.name} · ${job.stage.name.lowercase()} · ${job.message}",
                    color = Color.White.copy(alpha = 0.58f),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        if (latest != null && latest.stage == ComputationalRawJobStage.FAILED) {
            Text(
                "Background job #${latest.id} failed: ${latest.message}",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
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
        is ComputationalRawCaptureResult.Queued -> Text(
            buildString {
                append("Captured ")
                append(result.width)
                append('×')
                append(result.height)
                append(" · ")
                append(result.frameCount)
                append(" RAW · burst ")
                append(String.format(Locale.US, "%.1fs", result.captureElapsedMillis / 1000.0))
                append(" · job #")
                append(result.jobId)
                append(" now processing in background")
                if (result.maximumResolutionMode) append(" · max sensor mode")
            },
            color = Color.White.copy(alpha = 0.78f),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
        )
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
