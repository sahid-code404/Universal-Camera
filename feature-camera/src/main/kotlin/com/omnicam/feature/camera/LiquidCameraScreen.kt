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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.graphics.Color
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
import com.omnicam.camera.camerax.LightningJobStage
import com.omnicam.camera.camerax.LightningRawController
import com.omnicam.camera.camerax.LightningRawTuning
import com.omnicam.camera.capability.CameraCapabilityScanner
import com.omnicam.camera.capability.CameraRouteAccess
import com.omnicam.camera.capability.ValuableCameraResolver
import com.omnicam.camera.capability.ValuableCameraRoute
import com.omnicam.core.model.LensFacing
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class LiquidPreviewBackend(val label: String, val mode: ImplementationMode) {
    SURFACE("SURF", ImplementationMode.EXTERNAL),
    TEXTURE("TEX", ImplementationMode.EMBEDDED),
}

private enum class LiquidAspect(val label: String, val ratio: Float?) {
    FOUR_THREE("4:3", 4f / 3f),
    SIXTEEN_NINE("16:9", 16f / 9f),
    SQUARE("1:1", 1f),
    FULL("FULL", null),
}

@Composable
fun LiquidCameraRoute(
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

    var permissionGranted by remember { mutableStateOf(hasCameraPermission(context)) }
    var routes by remember { mutableStateOf<List<ValuableCameraRoute>>(emptyList()) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var scanError by remember { mutableStateOf<String?>(null) }
    var bindResult by remember { mutableStateOf<ComputationalRawBindResult?>(null) }
    var preset by remember { mutableStateOf(ComputationalRawPreset.HDR) }
    var aspect by remember { mutableStateOf(LiquidAspect.FOUR_THREE) }
    var highlight by remember { mutableFloatStateOf(0.80f) }
    var denoise by remember { mutableFloatStateOf(0.88f) }
    var gridEnabled by remember { mutableStateOf(true) }
    var drawerOpen by remember { mutableStateOf(false) }
    var capturing by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("Ready") }
    var resumed by remember {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }

    val uiStore = remember { context.getSharedPreferences("omnicam_liquid_ui", Context.MODE_PRIVATE) }
    var previewBackend by remember {
        mutableStateOf(
            runCatching {
                LiquidPreviewBackend.valueOf(
                    uiStore.getString("preview_backend", LiquidPreviewBackend.SURFACE.name).orEmpty(),
                )
            }.getOrDefault(LiquidPreviewBackend.SURFACE),
        )
    }

    val lensScaleStore = remember {
        context.getSharedPreferences("omnicam_lens_upscale", Context.MODE_PRIVATE)
    }
    var upscale by remember(selectedId) {
        mutableFloatStateOf(
            selectedId?.let {
                lensScaleStore.getFloat("factor_$it", 1f).coerceIn(1f, LIQUID_MAX_UPSCALE)
            } ?: 1f,
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        permissionGranted = it || hasCameraPermission(context)
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
                        }.thenBy(::liquidEquivalentFocal),
                    )
                if (selectedId !in routes.map { it.camera.id }) {
                    selectedId = liquidWidest(routes.filter { it.camera.lensFacing == LensFacing.BACK })?.camera?.id
                        ?: routes.firstOrNull()?.camera?.id
                }
            }
            .onFailure { scanError = it.message ?: "Camera unavailable" }
    }

    val selected = routes.firstOrNull { it.camera.id == selectedId }
    val facingRoutes = routes.filter { it.camera.lensFacing == selected?.camera?.lensFacing }
    val baseEq = (liquidWidest(facingRoutes) ?: selected)?.let(::liquidEquivalentFocal)?.coerceAtLeast(1f) ?: 26f
    val lensFactor = selected?.let { liquidEquivalentFocal(it) / baseEq } ?: 1f
    val displayZoom = lensFactor * cameraState.zoomRatio
    val queueFull = jobs.count { !it.terminal } >= 3
    val lastSaved = jobs.firstOrNull { it.stage == LightningJobStage.SAVED && it.dngUri != null }
    val displayRotation = liquidRotationDegrees(view.display?.rotation ?: AndroidSurface.ROTATION_0)

    val spec = remember(selectedId, aspect, displayRotation) {
        selected?.let { route ->
            runCatching {
                controller.createViewfinderSpec(
                    route = route,
                    targetAspect = aspect.ratio,
                    displayRotationDegrees = displayRotation,
                    sessionKey = "liquid-${route.camera.id}-${aspect.name}-$displayRotation",
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
            active != null -> "Processing ${jobs.count { !it.terminal }}"
            failed != null && failed.id > (saved?.id ?: 0L) -> "Capture failed"
            saved != null -> "Saved ${saved.outputWidth}×${saved.outputHeight} DNG"
            else -> "Ready"
        }
    }

    DisposableEffect(Unit) {
        onDispose { controller.unbind() }
    }

    Surface(modifier.fillMaxSize(), color = Color.Black) {
        when {
            !permissionGranted -> LiquidCenterMessage("Camera permission required") {
                permissionLauncher.launch(Manifest.permission.CAMERA)
            }
            scanError != null -> LiquidCenterMessage(scanError.orEmpty())
            routes.isEmpty() -> LiquidCenterMessage("No RAW camera available")
            else -> Box(Modifier.fillMaxSize().background(Color.Black)) {
                val route = selected
                val currentSpec = spec
                if (resumed && route != null && currentSpec != null) {
                    LiquidPreview(
                        spec = currentSpec,
                        route = route,
                        controller = controller,
                        backend = previewBackend,
                        focusState = focusState,
                        gridEnabled = gridEnabled,
                        maxZoom = cameraState.maxZoomRatio,
                        onBindResult = { bindResult = it },
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Color.White)
                    }
                }

                LiquidBottomScrim(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .fillMaxHeight(0.40f),
                )

                LiquidTopControls(
                    aspect = aspect,
                    flashAvailable = cameraState.flashAvailable,
                    flashMode = cameraState.flashMode,
                    preset = preset,
                    onFlash = {
                        if (cameraState.flashAvailable) {
                            controller.setFlashMode(nextLiquidFlash(cameraState.flashMode))
                        }
                    },
                    onPreset = {
                        preset = when (preset) {
                            ComputationalRawPreset.QUALITY -> ComputationalRawPreset.HDR
                            ComputationalRawPreset.HDR -> ComputationalRawPreset.MAX
                            ComputationalRawPreset.MAX -> ComputationalRawPreset.QUALITY
                        }
                    },
                    onAspect = {
                        aspect = when (aspect) {
                            LiquidAspect.FOUR_THREE -> LiquidAspect.SIXTEEN_NINE
                            LiquidAspect.SIXTEEN_NINE -> LiquidAspect.SQUARE
                            LiquidAspect.SQUARE -> LiquidAspect.FULL
                            LiquidAspect.FULL -> LiquidAspect.FOUR_THREE
                        }
                    },
                    onMore = { drawerOpen = !drawerOpen },
                    modifier = Modifier.align(Alignment.TopCenter),
                )

                LiquidSideRail(
                    gridEnabled = gridEnabled,
                    onGrid = { gridEnabled = !gridEnabled },
                    onMore = { drawerOpen = !drawerOpen },
                    modifier = Modifier.align(Alignment.CenterStart),
                )

                LiquidCaptureArea(
                    routes = facingRoutes,
                    selectedId = selectedId,
                    baseEq = baseEq,
                    displayZoom = displayZoom,
                    preset = preset,
                    message = if (queueFull) "Processing queue full" else message,
                    capturing = capturing,
                    captureEnabled = !capturing && !queueFull && bindResult is ComputationalRawBindResult.Success,
                    lastUri = lastSaved?.dngUri,
                    canFlip = routes.any { it.camera.lensFacing != selected?.camera?.lensFacing },
                    onLens = { selectedId = it },
                    onPreset = { preset = it },
                    onGallery = { lastSaved?.dngUri?.let { liquidOpenExternalDng(context, it) } },
                    onFlip = {
                        val opposite = if (selected?.camera?.lensFacing == LensFacing.FRONT) {
                            LensFacing.BACK
                        } else {
                            LensFacing.FRONT
                        }
                        liquidWidest(routes.filter { it.camera.lensFacing == opposite })?.let {
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
                                    is LightningCaptureResult.Queued -> "Processing DNG"
                                    is LightningCaptureResult.Failure -> result.message
                                }
                                capturing = false
                            }
                        }
                    },
                    modifier = Modifier.align(Alignment.BottomCenter),
                )

                AnimatedVisibility(
                    visible = drawerOpen,
                    enter = fadeIn() + slideInVertically(initialOffsetY = { it / 2 }),
                    exit = fadeOut() + slideOutVertically(targetOffsetY = { it / 2 }),
                    modifier = Modifier.align(Alignment.BottomEnd),
                ) {
                    LiquidAdvancedDrawer(
                        backend = previewBackend,
                        aspect = aspect,
                        upscale = upscale,
                        denoise = denoise,
                        highlight = highlight,
                        gridEnabled = gridEnabled,
                        onBackend = {
                            previewBackend = it
                            uiStore.edit().putString("preview_backend", it.name).apply()
                        },
                        onAspect = { aspect = it },
                        onUpscale = {
                            upscale = it.coerceIn(1f, LIQUID_MAX_UPSCALE)
                            selectedId?.let { id ->
                                lensScaleStore.edit().putFloat("factor_$id", upscale).apply()
                            }
                        },
                        onDenoise = { denoise = it },
                        onHighlight = { highlight = it },
                        onGrid = { gridEnabled = !gridEnabled },
                        onClose = { drawerOpen = false },
                        modifier = Modifier.padding(end = 12.dp, bottom = 206.dp, start = 54.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun LiquidPreview(
    spec: ComputationalRawViewfinderSpec,
    route: ValuableCameraRoute,
    controller: LightningRawController,
    backend: LiquidPreviewBackend,
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
            requestId = "OmniCam-Liquid-${route.camera.id}-${backend.name}-${spec.previewSize.width}x${spec.previewSize.height}",
        )
    }
    val transformationInfo = remember(spec) {
        TransformationInfo(
            sourceRotation = spec.rotationDegrees,
            isSourceMirroredHorizontally = spec.mirrorX,
            isSourceMirroredVertically = false,
        )
    }

    Box(modifier.background(Color.Black)) {
        Viewfinder(
            surfaceRequest = surfaceRequest,
            transformationInfo = transformationInfo,
            coordinateTransformer = coordinateTransformer,
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(route.camera.id, maxZoom) {
                    detectTransformGestures { _, _, gestureZoom, _ ->
                        controller.setZoomRatio(controller.cameraState.value.zoomRatio * gestureZoom)
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
                withContext(Dispatchers.Main.immediate) { onBindResult(result) }
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
        if (gridEnabled) LiquidGrid(Modifier.fillMaxSize())
        LiquidFocusIndicator(focusState, Modifier.fillMaxSize())
    }
}

@Composable
private fun LiquidTopControls(
    aspect: LiquidAspect,
    flashAvailable: Boolean,
    flashMode: LightningFlashMode,
    preset: ComputationalRawPreset,
    onFlash: () -> Unit,
    onPreset: () -> Unit,
    onAspect: () -> Unit,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(top = 22.dp, start = 14.dp, end = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlassCircleButton(
            text = when (flashMode) {
                LightningFlashMode.OFF -> "⚡"
                LightningFlashMode.AUTO -> "A⚡"
                LightningFlashMode.ON -> "⚡"
                LightningFlashMode.TORCH -> "T⚡"
            },
            selected = flashMode != LightningFlashMode.OFF,
            enabled = flashAvailable,
            onClick = onFlash,
        )
        GlassPillButton("DNG", selected = true, onClick = {})
        GlassPillButton(preset.name, selected = preset != ComputationalRawPreset.QUALITY, onClick = onPreset)
        GlassPillButton(aspect.label, onClick = onAspect)
        GlassCircleButton("•••", onClick = onMore)
    }
}

@Composable
private fun LiquidSideRail(
    gridEnabled: Boolean,
    onGrid: () -> Unit,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.padding(start = 12.dp),
        shape = RoundedCornerShape(28.dp),
        color = LiquidGlass,
        border = androidx.compose.foundation.BorderStroke(1.dp, LiquidBorder),
        shadowElevation = 4.dp,
    ) {
        Column(
            Modifier.padding(horizontal = 5.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            GlassCircleButton("#", selected = gridEnabled, sizeDp = 42, onClick = onGrid)
            GlassCircleButton("☷", sizeDp = 42, onClick = onMore)
        }
    }
}

@Composable
private fun LiquidCaptureArea(
    routes: List<ValuableCameraRoute>,
    selectedId: String?,
    baseEq: Float,
    displayZoom: Float,
    preset: ComputationalRawPreset,
    message: String,
    capturing: Boolean,
    captureEnabled: Boolean,
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
        modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, bottom = 22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            routes.forEach { route ->
                val factor = liquidEquivalentFocal(route) / baseEq
                val label = if (route.camera.lensFacing == LensFacing.FRONT) "FRONT" else liquidZoomLabel(factor)
                LiquidLensButton(label, route.camera.id == selectedId) { onLens(route.camera.id) }
                Spacer(Modifier.size(7.dp))
            }
        }

        Text(
            "${String.format(Locale.US, "%.2f", displayZoom)}×  ·  $message",
            color = Color.White.copy(alpha = 0.72f),
            fontSize = 10.sp,
            maxLines = 1,
            modifier = Modifier.padding(top = 7.dp, bottom = 7.dp),
        )

        LiquidPresetSelector(
            labels = listOf("QUALITY", "HDR", "MAX"),
            selectedIndex = when (preset) {
                ComputationalRawPreset.QUALITY -> 0
                ComputationalRawPreset.HDR -> 1
                ComputationalRawPreset.MAX -> 2
            },
            onSelected = {
                onPreset(
                    when (it) {
                        0 -> ComputationalRawPreset.QUALITY
                        1 -> ComputationalRawPreset.HDR
                        else -> ComputationalRawPreset.MAX
                    },
                )
            },
        )

        Row(
            Modifier.fillMaxWidth().padding(top = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GlassCircleButton(
                text = if (lastUri != null) "IMG" else "—",
                enabled = lastUri != null,
                sizeDp = 54,
                onClick = onGallery,
            )
            LiquidShutter(captureEnabled, capturing, onCapture)
            GlassCircleButton(
                text = if (canFlip) "↻" else "—",
                enabled = canFlip,
                sizeDp = 54,
                onClick = onFlip,
            )
        }
    }
}

@Composable
private fun LiquidAdvancedDrawer(
    backend: LiquidPreviewBackend,
    aspect: LiquidAspect,
    upscale: Float,
    denoise: Float,
    highlight: Float,
    gridEnabled: Boolean,
    onBackend: (LiquidPreviewBackend) -> Unit,
    onAspect: (LiquidAspect) -> Unit,
    onUpscale: (Float) -> Unit,
    onDenoise: (Float) -> Unit,
    onHighlight: (Float) -> Unit,
    onGrid: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.widthIn(max = 390.dp),
        shape = RoundedCornerShape(34.dp),
        color = LiquidGlassStrong,
        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.24f)),
        shadowElevation = 14.dp,
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("CAMERA CONTROLS", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                GlassCircleButton("×", sizeDp = 36, onClick = onClose)
            }

            Text("ASPECT", color = LiquidTextDim, fontSize = 9.sp, modifier = Modifier.padding(top = 9.dp, bottom = 5.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                LiquidAspect.entries.forEach { option ->
                    GlassPillButton(option.label, selected = option == aspect) { onAspect(option) }
                }
            }

            Text("PREVIEW", color = LiquidTextDim, fontSize = 9.sp, modifier = Modifier.padding(top = 10.dp, bottom = 5.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                LiquidPreviewBackend.entries.forEach { option ->
                    GlassPillButton(option.label, selected = option == backend) { onBackend(option) }
                }
                GlassPillButton(if (gridEnabled) "GRID ON" else "GRID OFF", selected = gridEnabled, onClick = onGrid)
            }

            DrawerSlider(
                label = "PER-LENS UPSCALE",
                value = upscale,
                valueText = upscaleText(upscale),
                range = 1f..LIQUID_MAX_UPSCALE,
                steps = 19,
                onValue = { onUpscale((it * 10f).roundToInt() / 10f) },
            )
            DrawerSlider(
                label = "RAW DENOISE",
                value = denoise,
                valueText = percentText(denoise),
                range = 0f..1f,
                steps = 19,
                onValue = onDenoise,
            )
            DrawerSlider(
                label = "HIGHLIGHT PROTECTION",
                value = highlight,
                valueText = percentText(highlight),
                range = 0f..1f,
                steps = 19,
                onValue = onHighlight,
            )

            Text(
                "Native DNG only · RAW is created only during shutter capture",
                color = Color.White.copy(alpha = 0.48f),
                fontSize = 9.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            )
        }
    }
}

@Composable
private fun LiquidCenterMessage(text: String, action: (() -> Unit)? = null) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text, color = Color.White, textAlign = TextAlign.Center)
        if (action != null) {
            Spacer(Modifier.height(18.dp))
            GlassPillButton("Continue", onClick = action)
        }
    }
}

private fun nextLiquidFlash(mode: LightningFlashMode): LightningFlashMode = when (mode) {
    LightningFlashMode.OFF -> LightningFlashMode.AUTO
    LightningFlashMode.AUTO -> LightningFlashMode.ON
    LightningFlashMode.ON -> LightningFlashMode.TORCH
    LightningFlashMode.TORCH -> LightningFlashMode.OFF
}

private fun liquidEquivalentFocal(route: ValuableCameraRoute): Float =
    route.camera.equivalentFocalLengthsMm.minOrNull()
        ?: route.camera.focalLengthsMm.minOrNull()
        ?: 26f

private fun liquidWidest(routes: List<ValuableCameraRoute>): ValuableCameraRoute? =
    routes.minByOrNull(::liquidEquivalentFocal)

private fun liquidZoomLabel(value: Float): String {
    val rounded = (value * 10f).roundToInt() / 10f
    return if (abs(rounded - rounded.roundToInt()) < 0.04f) {
        "${rounded.roundToInt()}×"
    } else {
        String.format(Locale.US, "%.1f×", rounded)
    }
}

private fun liquidRotationDegrees(rotation: Int): Int = when (rotation) {
    AndroidSurface.ROTATION_90 -> 90
    AndroidSurface.ROTATION_180 -> 180
    AndroidSurface.ROTATION_270 -> 270
    else -> 0
}

private fun hasCameraPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

private fun liquidOpenExternalDng(context: Context, uri: Uri) {
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "image/x-adobe-dng")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching { context.startActivity(Intent.createChooser(intent, "Open DNG with")) }
        .onFailure { Toast.makeText(context, "No app can open this DNG", Toast.LENGTH_SHORT).show() }
}
