package com.omnicam.feature.camera

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.view.Surface as AndroidSurface
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.viewfinder.compose.MutableCoordinateTransformer
import androidx.camera.viewfinder.compose.Viewfinder
import androidx.camera.viewfinder.core.ImplementationMode
import androidx.camera.viewfinder.core.TransformationInfo
import androidx.camera.viewfinder.core.ViewfinderSurfaceRequest
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
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
import com.omnicam.camera.camerax.LightningFlashMode
import com.omnicam.camera.camerax.LightningFocusState
import com.omnicam.camera.camerax.LightningFocusStatus
import com.omnicam.camera.camerax.LightningJobStage
import com.omnicam.camera.camerax.LightningRawController
import com.omnicam.camera.camerax.LightningRawTuning
import com.omnicam.camera.capability.CameraCapabilityScanner
import com.omnicam.camera.capability.CameraRouteAccess
import com.omnicam.camera.capability.ValuableCameraResolver
import com.omnicam.camera.capability.ValuableCameraRoute
import com.omnicam.core.model.LensFacing
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val CameraYellow = Color(0xFFFFD60A)
private val CameraBlack = Color(0xFF050505)
private val CameraGlass = Color(0xDA262628)
private const val MAX_UPSCALE = 3f

private enum class PreviewBackend(val label: String, val mode: ImplementationMode) {
    SURFACE("SURFACE", ImplementationMode.EXTERNAL),
    TEXTURE("TEXTURE", ImplementationMode.EMBEDDED),
}

private enum class RawAspect(val label: String, val ratio: Float?) {
    FOUR_THREE("4:3", 4f / 3f),
    SIXTEEN_NINE("16:9", 16f / 9f),
    SQUARE("1:1", 1f),
    FULL("FULL", null),
}

/**
 * Production Camera2 RAW screen. The viewfinder is AndroidX Camera Viewfinder in EXTERNAL mode by
 * default (the same SurfaceView-class rendering path that won the hardware preview comparison), with
 * EMBEDDED/TextureView available as a user fallback. CameraX is not used to own the camera, so the
 * same Camera2 session can contain both the low-latency preview Surface and native RAW_SENSOR output.
 */
@Composable
fun SurfaceCameraRoute(
    scanner: CameraCapabilityScanner,
    controller: LightningRawController,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val jobs by controller.jobs.collectAsState()
    val cameraState by controller.cameraState.collectAsState()
    val focusState by controller.focusState.collectAsState()

    var permissionGranted by remember { mutableStateOf(hasCameraPermissionSurface(context)) }
    var routes by remember { mutableStateOf<List<ValuableCameraRoute>>(emptyList()) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var scanError by remember { mutableStateOf<String?>(null) }
    var bindResult by remember { mutableStateOf<ComputationalRawBindResult?>(null) }
    var preset by remember { mutableStateOf(ComputationalRawPreset.HDR) }
    var aspect by remember { mutableStateOf(RawAspect.FOUR_THREE) }
    var highlight by remember { mutableFloatStateOf(0.80f) }
    var denoise by remember { mutableFloatStateOf(0.88f) }
    var gridEnabled by remember { mutableStateOf(true) }
    var controlsOpen by remember { mutableStateOf(false) }
    var capturing by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("Ready") }
    var resumed by remember {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }

    val previewStore = remember {
        context.getSharedPreferences("omnicam_preview_backend", Context.MODE_PRIVATE)
    }
    var previewBackend by remember {
        mutableStateOf(
            runCatching {
                PreviewBackend.valueOf(previewStore.getString("backend", PreviewBackend.SURFACE.name).orEmpty())
            }.getOrDefault(PreviewBackend.SURFACE),
        )
    }

    val lensScaleStore = remember {
        context.getSharedPreferences("omnicam_lens_upscale", Context.MODE_PRIVATE)
    }
    var upscale by remember(selectedId) {
        mutableFloatStateOf(
            selectedId?.let {
                lensScaleStore.getFloat("factor_$it", 1f).coerceIn(1f, MAX_UPSCALE)
            } ?: 1f,
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        permissionGranted = it || hasCameraPermissionSurface(context)
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            resumed = when (event) {
                Lifecycle.Event.ON_RESUME -> true
                Lifecycle.Event.ON_PAUSE,
                Lifecycle.Event.ON_STOP,
                Lifecycle.Event.ON_DESTROY,
                -> false
                else -> resumed
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
        runCatching { scanner.scan() }
            .onSuccess { profile ->
                routes = ValuableCameraResolver.resolve(profile).valuableRoutes
                    .filter {
                        it.access == CameraRouteAccess.DIRECT_CAMERA_DEVICE &&
                            it.camera.rawSupported &&
                            it.camera.lensFacing in setOf(LensFacing.BACK, LensFacing.FRONT)
                    }
                    .sortedWith(
                        compareBy<ValuableCameraRoute> {
                            if (it.camera.lensFacing == LensFacing.BACK) 0 else 1
                        }.thenBy(::equivalentFocal),
                    )
                if (selectedId !in routes.map { it.camera.id }) {
                    selectedId = widest(routes.filter { it.camera.lensFacing == LensFacing.BACK })?.camera?.id
                        ?: routes.firstOrNull()?.camera?.id
                }
            }
            .onFailure { scanError = it.message ?: "Camera scan failed" }
    }

    val selected = routes.firstOrNull { it.camera.id == selectedId }
    val facingRoutes = routes.filter { it.camera.lensFacing == selected?.camera?.lensFacing }
    val baseEq = (widest(facingRoutes) ?: selected)?.let(::equivalentFocal)?.coerceAtLeast(1f) ?: 26f
    val lensFactor = selected?.let { equivalentFocal(it) / baseEq } ?: 1f
    val displayZoom = lensFactor * cameraState.zoomRatio
    val queueFull = jobs.count { !it.terminal } >= 3
    val lastSaved = jobs.firstOrNull { it.stage == LightningJobStage.SAVED && it.dngUri != null }
    val displayRotation = rotationDegreesSurface(view.display?.rotation ?: AndroidSurface.ROTATION_0)

    val spec = remember(selectedId, aspect, displayRotation) {
        val route = selected
        if (route == null) {
            null
        } else {
            runCatching {
                controller.createViewfinderSpec(
                    route = route,
                    targetAspect = aspect.ratio,
                    displayRotationDegrees = displayRotation,
                    sessionKey = "${route.camera.id}-${aspect.name}-$displayRotation",
                )
            }.onFailure {
                bindResult = ComputationalRawBindResult.Failure(
                    route.camera.id,
                    it.message ?: "Camera unavailable",
                )
            }.getOrNull()
        }
    }

    LaunchedEffect(selectedId, aspect, previewBackend, resumed) {
        bindResult = null
        controller.unbind()
    }

    LaunchedEffect(jobs, capturing) {
        if (capturing) return@LaunchedEffect
        val active = jobs.firstOrNull { !it.terminal }
        val failed = jobs.firstOrNull { it.stage == LightningJobStage.FAILED }
        val saved = jobs.firstOrNull { it.stage == LightningJobStage.SAVED }
        message = when {
            active != null -> "Processing ${jobs.count { !it.terminal }} DNG"
            failed != null && failed.id > (saved?.id ?: 0L) -> "Failed: ${failed.message}"
            saved != null -> saved.message
            else -> "Ready"
        }
    }

    DisposableEffect(Unit) {
        onDispose { controller.unbind() }
    }

    Surface(modifier.fillMaxSize(), color = Color.Black) {
        when {
            !permissionGranted -> SurfaceCenterMessage("Camera permission required") {
                permissionLauncher.launch(Manifest.permission.CAMERA)
            }
            scanError != null -> SurfaceCenterMessage(scanError.orEmpty())
            routes.isEmpty() -> SurfaceCenterMessage("No direct RAW camera is available")
            else -> Box(Modifier.fillMaxSize().background(Color.Black)) {
                val route = selected
                val currentSpec = spec
                if (resumed && route != null && currentSpec != null) {
                    SurfacePreview(
                        spec = currentSpec,
                        route = route,
                        controller = controller,
                        backend = previewBackend,
                        aspect = aspect,
                        focusState = focusState,
                        gridEnabled = gridEnabled,
                        maxZoom = cameraState.maxZoomRatio,
                        onBindResult = { bindResult = it },
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(bottom = 238.dp),
                    )
                } else {
                    Box(
                        Modifier.fillMaxSize().padding(bottom = 238.dp).background(Color.Black),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(color = Color.White)
                    }
                }

                SurfaceTopBar(
                    aspect = aspect,
                    flashAvailable = cameraState.flashAvailable,
                    flashMode = cameraState.flashMode,
                    backend = previewBackend,
                    onFlash = {
                        if (cameraState.flashAvailable) {
                            controller.setFlashMode(nextSurfaceFlash(cameraState.flashMode))
                        }
                    },
                    onControls = { controlsOpen = !controlsOpen },
                    modifier = Modifier.align(Alignment.TopCenter),
                )

                SurfaceBottomBar(
                    routes = facingRoutes,
                    selectedId = selectedId,
                    baseEq = baseEq,
                    displayZoom = displayZoom,
                    preset = preset,
                    message = if (queueFull) "Processing queue full" else message,
                    capturing = capturing,
                    enabled = !capturing && !queueFull && bindResult is ComputationalRawBindResult.Success,
                    lastUri = lastSaved?.dngUri,
                    canFlip = routes.any { it.camera.lensFacing != selected?.camera?.lensFacing },
                    onLens = { selectedId = it },
                    onPreset = { preset = it },
                    onGallery = {
                        lastSaved?.dngUri?.let { openExternalDng(context, it) }
                    },
                    onFlip = {
                        val opposite = if (selected?.camera?.lensFacing == LensFacing.FRONT) {
                            LensFacing.BACK
                        } else {
                            LensFacing.FRONT
                        }
                        widest(routes.filter { it.camera.lensFacing == opposite })?.let {
                            selectedId = it.camera.id
                        }
                    },
                    onCapture = {
                        if (!capturing && !queueFull) {
                            capturing = true
                            message = "Capturing native RAW"
                            scope.launch {
                                val result = controller.capture(
                                    preset = preset,
                                    tuning = LightningRawTuning(
                                        denoiseStrength = denoise,
                                        highlightProtection = highlight,
                                        upscaleFactor = upscale,
                                        aspectRatio = aspect.ratio,
                                    ),
                                ) { progress -> message = progress }
                                message = when (result) {
                                    is LightningCaptureResult.Queued -> "DNG processing"
                                    is LightningCaptureResult.Failure -> result.message
                                }
                                capturing = false
                            }
                        }
                    },
                    modifier = Modifier.align(Alignment.BottomCenter),
                )

                if (controlsOpen) {
                    SurfaceControlSheet(
                        backend = previewBackend,
                        aspect = aspect,
                        upscale = upscale,
                        denoise = denoise,
                        highlight = highlight,
                        gridEnabled = gridEnabled,
                        onBackend = {
                            previewBackend = it
                            previewStore.edit().putString("backend", it.name).apply()
                        },
                        onAspect = { aspect = it },
                        onUpscale = {
                            upscale = it.coerceIn(1f, MAX_UPSCALE)
                            selectedId?.let { id ->
                                lensScaleStore.edit().putFloat("factor_$id", upscale).apply()
                            }
                        },
                        onDenoise = { denoise = it },
                        onHighlight = { highlight = it },
                        onGrid = { gridEnabled = !gridEnabled },
                        onClose = { controlsOpen = false },
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 246.dp, start = 10.dp, end = 10.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun SurfacePreview(
    spec: ComputationalRawViewfinderSpec,
    route: ValuableCameraRoute,
    controller: LightningRawController,
    backend: PreviewBackend,
    aspect: RawAspect,
    focusState: LightningFocusState,
    gridEnabled: Boolean,
    maxZoom: Float,
    onBindResult: (ComputationalRawBindResult) -> Unit,
    modifier: Modifier = Modifier,
) {
    val coordinateTransformer = remember(spec, backend) { MutableCoordinateTransformer() }
    val surfaceRequest = remember(spec, backend, route.camera.id) {
        ViewfinderSurfaceRequest(
            width = spec.previewSize.width,
            height = spec.previewSize.height,
            implementationMode = backend.mode,
            requestId = "OmniCam-${route.camera.id}-${backend.name}-${spec.previewSize.width}x${spec.previewSize.height}",
        )
    }
    val transformationInfo = remember(spec) {
        TransformationInfo(
            sourceRotation = spec.rotationDegrees,
            isSourceMirroredHorizontally = spec.mirrorX,
            isSourceMirroredVertically = false,
        )
    }

    Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        val frameModifier = if (aspect == RawAspect.FULL) {
            Modifier.fillMaxSize()
        } else {
            Modifier.fillMaxWidth().aspectRatio(1f / (aspect.ratio ?: 4f / 3f))
        }
        Box(frameModifier.background(Color.Black), contentAlignment = Alignment.Center) {
            Viewfinder(
                surfaceRequest = surfaceRequest,
                transformationInfo = transformationInfo,
                coordinateTransformer = coordinateTransformer,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(route.camera.id, maxZoom) {
                        detectTransformGestures { _, _, gestureZoom, _ ->
                            controller.setZoomRatio(
                                controller.cameraState.value.zoomRatio * gestureZoom,
                            )
                        }
                    }
                    .pointerInput(route.camera.id, spec, backend) {
                        detectTapGestures { tap ->
                            if (size.width <= 0 || size.height <= 0) return@detectTapGestures
                            val bufferPoint = with(coordinateTransformer) { tap.transform() }
                            controller.focusAtBuffer(
                                bufferX = bufferPoint.x,
                                bufferY = bufferPoint.y,
                                bufferWidth = spec.previewSize.width,
                                bufferHeight = spec.previewSize.height,
                                indicatorX = tap.x / size.width.toFloat(),
                                indicatorY = tap.y / size.height.toFloat(),
                            )
                        }
                    },
            ) {
                onSurfaceSession {
                    val result = controller.bindSurface(spec, route, surface)
                    withContext(Dispatchers.Main.immediate) {
                        onBindResult(result)
                    }
                    if (result is ComputationalRawBindResult.Success) {
                        try {
                            awaitCancellation()
                        } finally {
                            withContext(NonCancellable + Dispatchers.Main.immediate) {
                                controller.unbindSurface(surface)
                            }
                        }
                    }
                }
            }

            if (gridEnabled) SurfaceGrid(Modifier.fillMaxSize())
            SurfaceFocusIndicator(focusState, Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun SurfaceTopBar(
    aspect: RawAspect,
    flashAvailable: Boolean,
    flashMode: LightningFlashMode,
    backend: PreviewBackend,
    onFlash: () -> Unit,
    onControls: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(top = 12.dp, start = 12.dp, end = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlassPill("DNG · ${aspect.label}")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GlassPill(
                if (flashAvailable) "⚡ ${flashMode.name}" else "⚡ —",
                enabled = flashAvailable,
                onClick = onFlash,
            )
            GlassPill(if (backend == PreviewBackend.SURFACE) "SURF" else "TEX", onClick = onControls)
            GlassPill("•••", onClick = onControls)
        }
    }
}

@Composable
private fun SurfaceBottomBar(
    routes: List<ValuableCameraRoute>,
    selectedId: String?,
    baseEq: Float,
    displayZoom: Float,
    preset: ComputationalRawPreset,
    message: String,
    capturing: Boolean,
    enabled: Boolean,
    lastUri: Uri?,
    canFlip: Boolean,
    onLens: (String) -> Unit,
    onPreset: (ComputationalRawPreset) -> Unit,
    onGallery: () -> Unit,
    onFlip: () -> Unit,
    onCapture: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxWidth()
            .height(238.dp)
            .background(CameraBlack)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            routes.forEach { route ->
                val factor = equivalentFocal(route) / baseEq
                val label = if (route.camera.lensFacing == LensFacing.FRONT) {
                    "FRONT"
                } else {
                    formatZoomFactor(factor)
                }
                LensBubble(
                    label = label,
                    selected = route.camera.id == selectedId,
                    onClick = { onLens(route.camera.id) },
                )
                Spacer(Modifier.size(6.dp))
            }
        }

        Text(
            "${String.format(Locale.US, "%.2f", displayZoom)}×  ·  $message",
            color = Color.White.copy(alpha = 0.72f),
            fontSize = 10.sp,
            maxLines = 1,
            modifier = Modifier.padding(top = 5.dp),
        )

        Row(
            Modifier.fillMaxWidth().padding(top = 5.dp),
            horizontalArrangement = Arrangement.Center,
        ) {
            ComputationalRawPreset.entries.forEach { option ->
                SmallModePill(option.name, option == preset) { onPreset(option) }
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            RoundAction(
                text = if (lastUri != null) "IMG" else "—",
                enabled = lastUri != null,
                onClick = onGallery,
            )

            Box(
                Modifier
                    .size(78.dp)
                    .border(4.dp, Color.White, CircleShape)
                    .padding(6.dp)
                    .background(
                        if (enabled) Color.White else Color.White.copy(alpha = 0.35f),
                        CircleShape,
                    )
                    .clickable(enabled = enabled, onClick = onCapture),
                contentAlignment = Alignment.Center,
            ) {
                if (capturing) {
                    CircularProgressIndicator(
                        color = Color.Black,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(28.dp),
                    )
                }
            }

            RoundAction(text = if (canFlip) "↻" else "—", enabled = canFlip, onClick = onFlip)
        }
    }
}

@Composable
private fun SurfaceControlSheet(
    backend: PreviewBackend,
    aspect: RawAspect,
    upscale: Float,
    denoise: Float,
    highlight: Float,
    gridEnabled: Boolean,
    onBackend: (PreviewBackend) -> Unit,
    onAspect: (RawAspect) -> Unit,
    onUpscale: (Float) -> Unit,
    onDenoise: (Float) -> Unit,
    onHighlight: (Float) -> Unit,
    onGrid: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = CameraGlass,
        shape = RoundedCornerShape(28.dp),
        shadowElevation = 10.dp,
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("CAMERA CONTROLS", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                Text(
                    "DONE",
                    color = CameraYellow,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                    modifier = Modifier.clickable(onClick = onClose).padding(6.dp),
                )
            }

            ControlLabel("PREVIEW ENGINE")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PreviewBackend.entries.forEach { option ->
                    SelectPill(option.label, option == backend) { onBackend(option) }
                }
            }
            Text(
                if (backend == PreviewBackend.SURFACE) {
                    "Low-latency SurfaceView path · selected from your hardware test"
                } else {
                    "TextureView fallback · same transform engine"
                },
                color = Color.White.copy(alpha = 0.52f),
                fontSize = 9.sp,
                modifier = Modifier.padding(top = 4.dp),
            )

            ControlLabel("ASPECT")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                RawAspect.entries.forEach { option ->
                    SelectPill(option.label, option == aspect) { onAspect(option) }
                }
            }

            Row(
                Modifier.fillMaxWidth().padding(top = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("GRID", color = Color.White, fontSize = 10.sp)
                SelectPill(if (gridEnabled) "ON" else "OFF", gridEnabled, onGrid)
            }

            SliderControl(
                label = "PER-LENS UPSCALE",
                value = upscale,
                valueText = "${String.format(Locale.US, "%.1f", upscale)}×",
                range = 1f..MAX_UPSCALE,
                steps = 19,
                onValue = onUpscale,
            )
            SliderControl(
                label = "RAW DENOISE",
                value = denoise,
                valueText = "${(denoise * 100).roundToInt()}%",
                range = 0f..1f,
                steps = 19,
                onValue = onDenoise,
            )
            SliderControl(
                label = "HIGHLIGHT PROTECTION",
                value = highlight,
                valueText = "${(highlight * 100).roundToInt()}%",
                range = 0f..1f,
                steps = 19,
                onValue = onHighlight,
            )
        }
    }
}

@Composable
private fun SliderControl(
    label: String,
    value: Float,
    valueText: String,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onValue: (Float) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, color = Color.White.copy(alpha = 0.72f), fontSize = 9.sp)
        Text(valueText, color = CameraYellow, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
    }
    Slider(
        value = value,
        onValueChange = onValue,
        valueRange = range,
        steps = steps,
        modifier = Modifier.fillMaxWidth().height(30.dp),
    )
}

@Composable
private fun ControlLabel(text: String) {
    Text(
        text,
        color = Color.White.copy(alpha = 0.65f),
        fontSize = 9.sp,
        modifier = Modifier.padding(top = 10.dp, bottom = 5.dp),
    )
}

@Composable
private fun SurfaceFocusIndicator(state: LightningFocusState, modifier: Modifier = Modifier) {
    if (state.status == LightningFocusStatus.IDLE) return
    Canvas(modifier) {
        val center = Offset(size.width * state.normalizedX, size.height * state.normalizedY)
        val radius = 30.dp.toPx()
        val color = if (state.status == LightningFocusStatus.FAILED) Color(0xFFFF453A) else CameraYellow
        drawCircle(color, radius, center, style = Stroke(width = 2.dp.toPx()))
        val tick = 8.dp.toPx()
        drawLine(color, Offset(center.x - radius - tick, center.y), Offset(center.x - radius + tick, center.y), 2.dp.toPx())
        drawLine(color, Offset(center.x + radius - tick, center.y), Offset(center.x + radius + tick, center.y), 2.dp.toPx())
        drawLine(color, Offset(center.x, center.y - radius - tick), Offset(center.x, center.y - radius + tick), 2.dp.toPx())
        drawLine(color, Offset(center.x, center.y + radius - tick), Offset(center.x, center.y + radius + tick), 2.dp.toPx())
    }
}

@Composable
private fun SurfaceGrid(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val paint = Color.White.copy(alpha = 0.34f)
        val stroke = 0.7.dp.toPx()
        drawLine(paint, Offset(size.width / 3f, 0f), Offset(size.width / 3f, size.height), stroke)
        drawLine(paint, Offset(size.width * 2f / 3f, 0f), Offset(size.width * 2f / 3f, size.height), stroke)
        drawLine(paint, Offset(0f, size.height / 3f), Offset(size.width, size.height / 3f), stroke)
        drawLine(paint, Offset(0f, size.height * 2f / 3f), Offset(size.width, size.height * 2f / 3f), stroke)
    }
}

@Composable
private fun GlassPill(
    text: String,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = Color.Black.copy(alpha = 0.47f),
        modifier = if (onClick != null) Modifier.clickable(enabled = enabled, onClick = onClick) else Modifier,
    ) {
        Text(
            text,
            color = if (enabled) Color.White else Color.White.copy(alpha = 0.38f),
            fontSize = 9.sp,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
        )
    }
}

@Composable
private fun LensBubble(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.size(if (selected) 42.dp else 34.dp).clickable(onClick = onClick),
        shape = CircleShape,
        color = if (selected) Color(0xFF2C2C2E) else Color(0xFF171719),
        border = if (selected) androidx.compose.foundation.BorderStroke(1.dp, CameraYellow.copy(alpha = 0.7f)) else null,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                label,
                color = if (selected) CameraYellow else Color.White,
                fontSize = if (label.length > 4) 7.sp else 9.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun SmallModePill(text: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text,
        color = if (selected) CameraYellow else Color.White.copy(alpha = 0.6f),
        fontSize = 9.sp,
        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        modifier = Modifier.clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

@Composable
private fun SelectPill(text: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (selected) Color.White.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.07f),
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Text(
            text,
            color = if (selected) CameraYellow else Color.White,
            fontSize = 9.sp,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
        )
    }
}

@Composable
private fun RoundAction(text: String, enabled: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.size(46.dp).clickable(enabled = enabled, onClick = onClick),
        shape = CircleShape,
        color = Color(0xFF1C1C1E),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text,
                color = if (enabled) Color.White else Color.White.copy(alpha = 0.3f),
                fontSize = if (text.length > 2) 8.sp else 18.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun SurfaceCenterMessage(text: String, onClick: (() -> Unit)? = null) {
    Box(
        Modifier.fillMaxSize().background(Color.Black).let {
            if (onClick != null) it.clickable(onClick = onClick) else it
        },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = Color.White,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(28.dp),
        )
    }
}

private fun nextSurfaceFlash(mode: LightningFlashMode): LightningFlashMode = when (mode) {
    LightningFlashMode.OFF -> LightningFlashMode.AUTO
    LightningFlashMode.AUTO -> LightningFlashMode.ON
    LightningFlashMode.ON -> LightningFlashMode.TORCH
    LightningFlashMode.TORCH -> LightningFlashMode.OFF
}

private fun hasCameraPermissionSurface(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

private fun rotationDegreesSurface(rotation: Int): Int = when (rotation) {
    AndroidSurface.ROTATION_90 -> 90
    AndroidSurface.ROTATION_180 -> 180
    AndroidSurface.ROTATION_270 -> 270
    else -> 0
}

private fun equivalentFocal(route: ValuableCameraRoute): Float =
    route.camera.equivalentFocalLengthsMm.minOrNull()
        ?: route.camera.focalLengthsMm.minOrNull()
        ?: 26f

private fun widest(routes: List<ValuableCameraRoute>): ValuableCameraRoute? =
    routes.minByOrNull(::equivalentFocal)

private fun formatZoomFactor(value: Float): String {
    val rounded = (value * 10f).roundToInt() / 10f
    return if (kotlin.math.abs(rounded - rounded.roundToInt()) < 0.04f) {
        "${rounded.roundToInt()}×"
    } else {
        String.format(Locale.US, "%.1f×", rounded)
    }
}

private fun openExternalDng(context: Context, uri: Uri) {
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "image/x-adobe-dng")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching {
        context.startActivity(Intent.createChooser(intent, "Open DNG with"))
    }.onFailure {
        Toast.makeText(context, "No app can open this DNG", Toast.LENGTH_SHORT).show()
    }
}
