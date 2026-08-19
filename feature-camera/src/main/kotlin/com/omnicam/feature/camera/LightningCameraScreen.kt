package com.omnicam.feature.camera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.viewfinder.compose.Viewfinder
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.omnicam.camera.camerax.ComputationalRawBindResult
import com.omnicam.camera.camerax.ComputationalRawPreset
import com.omnicam.camera.camerax.ComputationalRawViewfinderSpec
import com.omnicam.camera.camerax.LightningCaptureResult
import com.omnicam.camera.camerax.LightningJobStage
import com.omnicam.camera.camerax.LightningRawController
import com.omnicam.camera.camerax.LightningRawTuning
import com.omnicam.camera.capability.CameraCapabilityScanner
import com.omnicam.camera.capability.CameraRouteAccess
import com.omnicam.camera.capability.ValuableCameraResolver
import com.omnicam.camera.capability.ValuableCameraRoute
import com.omnicam.core.model.LensFacing
import com.omnicam.core.model.LensRole
import java.util.Locale
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch

private val CameraYellow = Color(0xFFFFD60A)

private enum class TunePanel { NONE, HIGHLIGHT, DENOISE }

@Composable
fun LightningCameraRoute(
    scanner: CameraCapabilityScanner,
    controller: LightningRawController,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val jobs by controller.jobs.collectAsState()
    val cameraState by controller.cameraState.collectAsState()
    val activeJobs = jobs.count { !it.terminal }
    val queueFull = activeJobs >= 3

    var permissionGranted by remember { mutableStateOf(hasCameraPermission(context)) }
    var routes by remember { mutableStateOf<List<ValuableCameraRoute>>(emptyList()) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var scanError by remember { mutableStateOf<String?>(null) }
    var spec by remember { mutableStateOf<ComputationalRawViewfinderSpec?>(null) }
    var bindResult by remember { mutableStateOf<ComputationalRawBindResult?>(null) }
    var preset by remember { mutableStateOf(ComputationalRawPreset.HDR) }
    var capturing by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Ready") }
    var highlight by remember { mutableFloatStateOf(0.80f) }
    var denoise by remember { mutableFloatStateOf(0.88f) }
    var hdPlus by remember { mutableStateOf(true) }
    var tunePanel by remember { mutableStateOf(TunePanel.NONE) }
    var lifecycleResumed by remember {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionGranted = granted || hasCameraPermission(context)
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            lifecycleResumed = when (event) {
                Lifecycle.Event.ON_RESUME -> true
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP, Lifecycle.Event.ON_DESTROY -> false
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
                            .thenBy { routeEq(it) },
                    )
                if (selectedId !in routes.map { it.camera.id }) selectedId = chooseWide(routes)?.camera?.id
            }
            .onFailure { scanError = it.message ?: it::class.java.simpleName }
    }

    val selected = routes.firstOrNull { it.camera.id == selectedId }
    val sameFacing = routes.filter { it.camera.lensFacing == selected?.camera?.lensFacing }
    val baseRoute = chooseWide(sameFacing) ?: selected
    val baseEq = baseRoute?.let(::routeEq)?.takeIf { it.isFinite() && it > 0f } ?: 26f
    val selectedFactor = selected?.let { (routeEq(it) / baseEq).coerceAtLeast(0.1f) } ?: 1f
    val overallZoom = selectedFactor * cameraState.zoomRatio

    LaunchedEffect(selected?.camera?.id, lifecycleResumed) {
        controller.unbind()
        bindResult = null
        if (!lifecycleResumed) {
            spec = null
            return@LaunchedEffect
        }
        val route = selected ?: run {
            spec = null
            return@LaunchedEffect
        }
        spec = runCatching {
            controller.createViewfinderSpec(route, "${route.camera.id}-${System.nanoTime()}")
        }.onFailure {
            bindResult = ComputationalRawBindResult.Failure(route.camera.id, it.message ?: "Camera unavailable")
        }.getOrNull()
    }

    LaunchedEffect(jobs, capturing) {
        if (capturing) return@LaunchedEffect
        val active = jobs.firstOrNull { !it.terminal }
        val saved = jobs.firstOrNull { it.stage == LightningJobStage.SAVED }
        status = when {
            active != null -> "Processing ${jobs.count { !it.terminal }}"
            saved?.enhancedUri != null -> "Saved RAW + HD+"
            saved != null -> "Saved RAW"
            else -> "Ready"
        }
    }

    DisposableEffect(Unit) {
        onDispose { controller.unbind() }
    }

    Surface(modifier.fillMaxSize(), color = Color.Black) {
        when {
            !permissionGranted -> CenterMessage("Camera access needed", "Allow camera permission to use OmniCam.") {
                permissionLauncher.launch(Manifest.permission.CAMERA)
            }
            scanError != null -> CenterMessage("Camera unavailable", scanError.orEmpty())
            routes.isEmpty() -> CenterMessage("RAW unavailable", "No useful direct RAW_SENSOR camera is exposed on this device.")
            else -> Box(Modifier.fillMaxSize().background(Color.Black)) {
                val currentSpec = spec
                val currentRoute = selected
                if (currentSpec != null && currentRoute != null && lifecycleResumed) {
                    Viewfinder(
                        surfaceRequest = currentSpec.surfaceRequest,
                        transformationInfo = currentSpec.transformationInfo,
                        alignment = Alignment.Center,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(cameraState.maxZoomRatio, selectedId) {
                                detectTransformGestures { _, _, gestureZoom, _ ->
                                    val current = controller.cameraState.value.zoomRatio
                                    controller.setZoomRatio(current * gestureZoom)
                                }
                            },
                    ) {
                        onSurfaceSession {
                            bindResult = controller.bind(surface, currentSpec, currentRoute)
                            try {
                                awaitCancellation()
                            } finally {
                                controller.unbind()
                            }
                        }
                    }
                } else {
                    CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
                }

                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(190.dp)
                        .align(Alignment.TopCenter)
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Black.copy(alpha = 0.82f), Color.Black.copy(alpha = 0.26f), Color.Transparent),
                            ),
                        ),
                )

                Column(
                    Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                        .padding(top = 34.dp, start = 14.dp, end = 14.dp),
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TinyChip("RAW", active = true, onClick = {})
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TinyChip("HL ${(highlight * 100).roundToInt()}", tunePanel == TunePanel.HIGHLIGHT) {
                                tunePanel = if (tunePanel == TunePanel.HIGHLIGHT) TunePanel.NONE else TunePanel.HIGHLIGHT
                            }
                            TinyChip("NR ${(denoise * 100).roundToInt()}", tunePanel == TunePanel.DENOISE) {
                                tunePanel = if (tunePanel == TunePanel.DENOISE) TunePanel.NONE else TunePanel.DENOISE
                            }
                            TinyChip("HD+", hdPlus) { hdPlus = !hdPlus }
                        }
                    }
                    if (tunePanel != TunePanel.NONE) {
                        Surface(
                            Modifier.fillMaxWidth().padding(top = 10.dp),
                            shape = RoundedCornerShape(22.dp),
                            color = Color.Black.copy(alpha = 0.66f),
                        ) {
                            Column(Modifier.padding(horizontal = 18.dp, vertical = 8.dp)) {
                                val isHighlight = tunePanel == TunePanel.HIGHLIGHT
                                Text(
                                    if (isHighlight) "Highlight protection" else "RAW denoise",
                                    color = Color.White,
                                    fontSize = 12.sp,
                                )
                                Slider(
                                    value = if (isHighlight) highlight else denoise,
                                    onValueChange = { if (isHighlight) highlight = it else denoise = it },
                                    valueRange = if (isHighlight) 0f..1f else 0.50f..1f,
                                )
                            }
                        }
                    }
                }

                Column(
                    Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Transparent, Color.Black.copy(alpha = 0.72f), Color.Black),
                            ),
                        )
                        .padding(top = 84.dp, bottom = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        String.format(Locale.US, "%.1f×", overallZoom),
                        color = CameraYellow,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp,
                    )

                    Row(
                        Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        sameFacing.forEach { route ->
                            val factor = (routeEq(route) / baseEq).coerceAtLeast(0.1f)
                            LensBubble(
                                label = zoomLabel(factor),
                                selected = route.camera.id == selectedId,
                                onClick = { if (!capturing) selectedId = route.camera.id },
                            )
                        }
                    }

                    if (cameraState.maxZoomRatio > cameraState.minZoomRatio + 0.02f) {
                        Slider(
                            value = cameraState.zoomRatio.coerceIn(cameraState.minZoomRatio, cameraState.maxZoomRatio),
                            onValueChange = { controller.setZoomRatio(it) },
                            valueRange = cameraState.minZoomRatio..cameraState.maxZoomRatio,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 48.dp).height(28.dp),
                        )
                    } else {
                        Spacer(Modifier.height(28.dp))
                    }

                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 58.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        ModeLabel("RAW", preset == ComputationalRawPreset.QUALITY) { if (!capturing) preset = ComputationalRawPreset.QUALITY }
                        ModeLabel("HDR", preset == ComputationalRawPreset.HDR) { if (!capturing) preset = ComputationalRawPreset.HDR }
                        ModeLabel("MAX", preset == ComputationalRawPreset.MAX) { if (!capturing) preset = ComputationalRawPreset.MAX }
                    }

                    Text(
                        if (queueFull) "Processing queue full" else status,
                        color = Color.White.copy(alpha = 0.72f),
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
                    )

                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 28.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Surface(
                            Modifier.size(50.dp),
                            shape = RoundedCornerShape(13.dp),
                            color = Color(0xFF242424),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text("▣", color = Color.White, fontSize = 22.sp)
                            }
                        }

                        ShutterButton(
                            enabled = !capturing && !queueFull && bindResult is ComputationalRawBindResult.Success,
                            capturing = capturing,
                        ) {
                            if (capturing || queueFull) return@ShutterButton
                            capturing = true
                            status = "Capturing"
                            scope.launch {
                                val result = controller.capture(
                                    preset = preset,
                                    tuning = LightningRawTuning(
                                        denoiseStrength = denoise,
                                        highlightProtection = highlight,
                                        enhancedUpscale = hdPlus,
                                    ),
                                ) { status = it }
                                status = when (result) {
                                    is LightningCaptureResult.Queued -> "Processing in background"
                                    is LightningCaptureResult.Failure -> result.message
                                }
                                capturing = false
                            }
                        }

                        val opposite = if (selected?.camera?.lensFacing == LensFacing.FRONT) LensFacing.BACK else LensFacing.FRONT
                        val canFlip = routes.any { it.camera.lensFacing == opposite }
                        Surface(
                            Modifier
                                .size(50.dp)
                                .clickable(enabled = canFlip && !capturing) {
                                    val next = chooseWide(routes.filter { it.camera.lensFacing == opposite })
                                    if (next != null) selectedId = next.camera.id
                                },
                            shape = CircleShape,
                            color = Color(0xAA2C2C2E),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text("↻", color = if (canFlip) Color.White else Color.Gray, fontSize = 24.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TinyChip(text: String, active: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.clickable(onClick = onClick),
        shape = CircleShape,
        color = if (active) Color.White.copy(alpha = 0.92f) else Color.Black.copy(alpha = 0.48f),
    ) {
        Text(
            text,
            color = if (active) Color.Black else Color.White,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
        )
    }
}

@Composable
private fun LensBubble(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.size(if (selected) 48.dp else 42.dp).clickable(onClick = onClick),
        shape = CircleShape,
        color = if (selected) Color.White.copy(alpha = 0.90f) else Color.Black.copy(alpha = 0.58f),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                label,
                color = if (selected) Color.Black else Color.White,
                fontSize = if (selected) 13.sp else 12.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun ModeLabel(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        label,
        color = if (selected) CameraYellow else Color.White.copy(alpha = 0.82f),
        fontSize = 13.sp,
        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
        modifier = Modifier.clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 6.dp),
    )
}

@Composable
private fun ShutterButton(enabled: Boolean, capturing: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(82.dp)
            .border(4.dp, if (enabled) Color.White else Color.Gray, CircleShape)
            .padding(6.dp)
            .background(if (capturing) Color(0xFFB0B0B0) else Color.White, CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (capturing) CircularProgressIndicator(Modifier.size(28.dp), color = Color.Black, strokeWidth = 2.dp)
    }
}

@Composable
private fun CenterMessage(title: String, message: String, action: (() -> Unit)? = null) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, color = Color.White, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(message, color = Color.White.copy(alpha = 0.70f), textAlign = TextAlign.Center)
        if (action != null) {
            Spacer(Modifier.height(20.dp))
            Surface(Modifier.clickable(onClick = action), shape = CircleShape, color = Color.White) {
                Text("Continue", color = Color.Black, modifier = Modifier.padding(horizontal = 22.dp, vertical = 10.dp))
            }
        }
    }
}

private fun chooseWide(routes: List<ValuableCameraRoute>): ValuableCameraRoute? {
    val back = routes.filter { it.camera.lensFacing == LensFacing.BACK }
    val pool = if (back.isNotEmpty()) back else routes
    return pool.firstOrNull { it.camera.classification.role == LensRole.WIDE }
        ?: pool.minByOrNull { kotlin.math.abs(routeEq(it) - 26f) }
}

private fun routeEq(route: ValuableCameraRoute): Float =
    route.camera.equivalentFocalLengthsMm.minOrNull()
        ?: route.camera.focalLengthsMm.minOrNull()
        ?: 26f

private fun zoomLabel(value: Float): String = when {
    kotlin.math.abs(value - 1f) < 0.06f -> "1×"
    value < 1f -> String.format(Locale.US, "%.1f×", value)
    kotlin.math.abs(value - value.roundToInt()) < 0.08f -> "${value.roundToInt()}×"
    else -> String.format(Locale.US, "%.1f×", value)
}

private fun hasCameraPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
