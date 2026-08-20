package com.omnicam.feature.camera

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.ImageDecoder
import android.net.Uri
import android.view.Surface as AndroidSurface
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
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
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
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
import com.omnicam.camera.camerax.JpegPreviewFrame
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
import com.omnicam.core.model.LensRole
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val IosYellow = Color(0xFFFFD60A)
private val BottomBlack = Color(0xFF050505)
private val Glass = Color(0xE62C2C2E)
private const val MAX_UPSCALE = 3f

private enum class CameraSheet { NONE, CONTROLS, HIGHLIGHT, DENOISE, UPSCALE, ASPECT }

private enum class DngAspect(val label: String, val ratio: Float?) {
    FOUR_THREE("4:3", 4f / 3f),
    SIXTEEN_NINE("16:9", 16f / 9f),
    SQUARE("1:1", 1f),
    FULL("FULL", null),
}

@Composable
fun LightningCameraRoute(
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
    val previewFrame by controller.previewFrame.collectAsState()
    val focusState by controller.focusState.collectAsState()

    var permissionGranted by remember { mutableStateOf(hasPermission(context)) }
    var routes by remember { mutableStateOf<List<ValuableCameraRoute>>(emptyList()) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var scanError by remember { mutableStateOf<String?>(null) }
    var bindResult by remember { mutableStateOf<ComputationalRawBindResult?>(null) }
    var preset by remember { mutableStateOf(ComputationalRawPreset.HDR) }
    var aspect by remember { mutableStateOf(DngAspect.FOUR_THREE) }
    var sheet by remember { mutableStateOf(CameraSheet.NONE) }
    var highlight by remember { mutableFloatStateOf(0.80f) }
    var denoise by remember { mutableFloatStateOf(0.88f) }
    var gridEnabled by remember { mutableStateOf(true) }
    var capturing by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("Ready") }
    var resumed by remember {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }

    val lensScaleStore = remember {
        context.getSharedPreferences("omnicam_lens_upscale", Context.MODE_PRIVATE)
    }
    var upscale by remember(selectedId) {
        mutableFloatStateOf(
            selectedId?.let { lensScaleStore.getFloat("factor_$it", 1f).coerceIn(1f, MAX_UPSCALE) } ?: 1f,
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        permissionGranted = it || hasPermission(context)
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            resumed = when (event) {
                Lifecycle.Event.ON_RESUME -> true
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP, Lifecycle.Event.ON_DESTROY -> false
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
                        }.thenBy(::eqFocal),
                    )
                if (selectedId !in routes.map { it.camera.id }) selectedId = wide(routes)?.camera?.id
            }
            .onFailure { scanError = it.message ?: "Camera scan failed" }
    }

    val selected = routes.firstOrNull { it.camera.id == selectedId }
    val facingRoutes = routes.filter { it.camera.lensFacing == selected?.camera?.lensFacing }
    val baseEq = (wide(facingRoutes) ?: selected)?.let(::eqFocal)?.coerceAtLeast(1f) ?: 26f
    val lensFactor = selected?.let { eqFocal(it) / baseEq } ?: 1f
    val displayZoom = lensFactor * cameraState.zoomRatio
    val queueFull = jobs.count { !it.terminal } >= 3
    val lastSaved = jobs.firstOrNull { it.stage == LightningJobStage.SAVED && it.dngUri != null }
    val displayRotation = rotationDegrees(view.display?.rotation ?: AndroidSurface.ROTATION_0)

    LaunchedEffect(selectedId, aspect, resumed, displayRotation) {
        controller.unbind()
        bindResult = null
        if (!resumed) return@LaunchedEffect
        val route = selected ?: return@LaunchedEffect
        val spec = runCatching {
            controller.createViewfinderSpec(
                route = route,
                targetAspect = aspect.ratio,
                displayRotationDegrees = displayRotation,
                sessionKey = "${route.camera.id}-${aspect.name}-${System.nanoTime()}",
            )
        }.onFailure {
            bindResult = ComputationalRawBindResult.Failure(route.camera.id, it.message ?: "Camera unavailable")
        }.getOrNull() ?: return@LaunchedEffect

        bindResult = runCatching { controller.bind(spec, route) }
            .getOrElse {
                ComputationalRawBindResult.Failure(route.camera.id, it.message ?: "Camera unavailable")
            }
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
            !permissionGranted -> CenterText("Camera permission required") {
                permissionLauncher.launch(Manifest.permission.CAMERA)
            }
            scanError != null -> CenterText(scanError.orEmpty())
            routes.isEmpty() -> CenterText("No direct RAW camera is available")
            else -> Box(Modifier.fillMaxSize().background(Color.Black)) {
                PreviewArea(
                    frame = previewFrame,
                    focusState = focusState,
                    controller = controller,
                    aspect = aspect,
                    gridEnabled = gridEnabled,
                    zoomKey = selectedId,
                    maxZoom = cameraState.maxZoomRatio,
                    modifier = Modifier.fillMaxSize().padding(bottom = 252.dp),
                )

                TopCameraBar(
                    aspect = aspect,
                    flashAvailable = cameraState.flashAvailable,
                    flashMode = cameraState.flashMode,
                    onFlash = {
                        if (cameraState.flashAvailable) controller.setFlashMode(nextFlash(cameraState.flashMode))
                    },
                    onMore = { sheet = toggleSheet(sheet, CameraSheet.CONTROLS) },
                    modifier = Modifier.align(Alignment.TopCenter),
                )

                BottomCameraBar(
                    routes = facingRoutes,
                    selectedId = selectedId,
                    baseEq = baseEq,
                    displayZoom = displayZoom,
                    zoom = cameraState.zoomRatio,
                    minZoom = cameraState.minZoomRatio,
                    maxZoom = cameraState.maxZoomRatio,
                    preset = preset,
                    message = if (queueFull) "Processing queue full" else message,
                    capturing = capturing,
                    enabled = !capturing && !queueFull && bindResult is ComputationalRawBindResult.Success,
                    lastUri = lastSaved?.dngUri,
                    canFlip = routes.any { it.camera.lensFacing != selected?.camera?.lensFacing },
                    onLens = { selectedId = it },
                    onZoom = controller::setZoomRatio,
                    onPreset = { preset = it },
                    onGallery = {
                        lastSaved?.dngUri?.let { openExternalPhoto(context, it) }
                    },
                    onFlip = {
                        val opposite = if (selected?.camera?.lensFacing == LensFacing.FRONT) {
                            LensFacing.BACK
                        } else {
                            LensFacing.FRONT
                        }
                        wide(routes.filter { it.camera.lensFacing == opposite })?.let {
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
                                ) { message = it }
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

                if (sheet != CameraSheet.NONE) {
                    CameraControlSheet(
                        sheet = sheet,
                        aspect = aspect,
                        flashAvailable = cameraState.flashAvailable,
                        flashMode = cameraState.flashMode,
                        highlight = highlight,
                        denoise = denoise,
                        upscale = upscale,
                        gridEnabled = gridEnabled,
                        onAspect = { aspect = it; sheet = CameraSheet.NONE },
                        onFlash = {
                            if (cameraState.flashAvailable) controller.setFlashMode(nextFlash(cameraState.flashMode))
                        },
                        onHighlight = { highlight = it },
                        onDenoise = { denoise = it },
                        onUpscale = {
                            upscale = it.coerceIn(1f, MAX_UPSCALE)
                            selectedId?.let { id ->
                                lensScaleStore.edit().putFloat("factor_$id", upscale).apply()
                            }
                        },
                        onGrid = { gridEnabled = !gridEnabled },
                        onOpenAspect = { sheet = CameraSheet.ASPECT },
                        onOpenHighlight = { sheet = CameraSheet.HIGHLIGHT },
                        onOpenDenoise = { sheet = CameraSheet.DENOISE },
                        onOpenUpscale = { sheet = CameraSheet.UPSCALE },
                        onClose = { sheet = CameraSheet.NONE },
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 260.dp, start = 12.dp, end = 12.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun PreviewArea(
    frame: JpegPreviewFrame?,
    focusState: LightningFocusState,
    controller: LightningRawController,
    aspect: DngAspect,
    gridEnabled: Boolean,
    zoomKey: String?,
    maxZoom: Float,
    modifier: Modifier = Modifier,
) {
    Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        val frameModifier = if (aspect == DngAspect.FULL) {
            Modifier.fillMaxSize()
        } else {
            Modifier.fillMaxWidth().aspectRatio(1f / (aspect.ratio ?: 4f / 3f))
        }
        Box(frameModifier.background(Color.Black), contentAlignment = Alignment.Center) {
            if (frame != null) {
                Image(
                    bitmap = frame.bitmap.asImageBitmap(),
                    contentDescription = "JPEG camera preview",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(zoomKey, maxZoom) {
                            detectTransformGestures { _, _, gestureZoom, _ ->
                                controller.setZoomRatio(controller.cameraState.value.zoomRatio * gestureZoom)
                            }
                        }
                        .pointerInput(zoomKey) {
                            detectTapGestures { tap ->
                                if (size.width > 0 && size.height > 0) {
                                    controller.focusAt(
                                        tap.x / size.width.toFloat(),
                                        tap.y / size.height.toFloat(),
                                    )
                                }
                            }
                        },
                )
                if (gridEnabled) Grid(Modifier.fillMaxSize())
                FocusIndicator(focusState, Modifier.fillMaxSize())
            } else {
                CircularProgressIndicator(color = Color.White)
            }
        }
    }
}

@Composable
private fun FocusIndicator(state: LightningFocusState, modifier: Modifier = Modifier) {
    if (state.status == LightningFocusStatus.IDLE) return
    Canvas(modifier) {
        val center = Offset(size.width * state.normalizedX, size.height * state.normalizedY)
        val radius = 30.dp.toPx()
        val color = when (state.status) {
            LightningFocusStatus.FAILED -> Color(0xFFFF453A)
            else -> IosYellow
        }
        drawCircle(color, radius, center, style = Stroke(width = 2.dp.toPx()))
        val tick = 8.dp.toPx()
        drawLine(color, Offset(center.x - radius - tick, center.y), Offset(center.x - radius + tick, center.y), 2.dp.toPx())
        drawLine(color, Offset(center.x + radius - tick, center.y), Offset(center.x + radius + tick, center.y), 2.dp.toPx())
        drawLine(color, Offset(center.x, center.y - radius - tick), Offset(center.x, center.y - radius + tick), 2.dp.toPx())
        drawLine(color, Offset(center.x, center.y + radius - tick), Offset(center.x, center.y + radius + tick), 2.dp.toPx())
    }
}

@Composable
private fun TopCameraBar(
    aspect: DngAspect,
    flashAvailable: Boolean,
    flashMode: LightningFlashMode,
    onFlash: () -> Unit,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.fillMaxWidth().padding(top = 30.dp, start = 14.dp, end = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill("DNG") { }
            Pill(if (flashAvailable) "⚡ ${flashMode.label}" else "⚡ —", onFlash)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill("JPEG") { }
            Pill("${aspect.label}  •••", onMore)
        }
    }
}

@Composable
private fun BottomCameraBar(
    routes: List<ValuableCameraRoute>,
    selectedId: String?,
    baseEq: Float,
    displayZoom: Float,
    zoom: Float,
    minZoom: Float,
    maxZoom: Float,
    preset: ComputationalRawPreset,
    message: String,
    capturing: Boolean,
    enabled: Boolean,
    lastUri: Uri?,
    canFlip: Boolean,
    onLens: (String) -> Unit,
    onZoom: (Float) -> Unit,
    onPreset: (ComputationalRawPreset) -> Unit,
    onGallery: () -> Unit,
    onFlip: () -> Unit,
    onCapture: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxWidth()
            .height(252.dp)
            .background(BottomBlack)
            .padding(top = 7.dp, bottom = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            routes.forEach { route ->
                val factor = (eqFocal(route) / baseEq).coerceAtLeast(0.1f)
                ZoomBubble(zoomText(factor), route.camera.id == selectedId) { onLens(route.camera.id) }
            }
        }
        Text(String.format(Locale.US, "%.1f×", displayZoom), color = IosYellow, fontSize = 11.sp)
        if (maxZoom > minZoom + 0.02f) {
            Slider(
                value = zoom.coerceIn(minZoom, maxZoom),
                onValueChange = onZoom,
                valueRange = minZoom..maxZoom,
                modifier = Modifier.fillMaxWidth().height(29.dp).padding(horizontal = 56.dp),
            )
        } else {
            Spacer(Modifier.height(29.dp))
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 54.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Mode("RAW", preset == ComputationalRawPreset.QUALITY) {
                if (!capturing) onPreset(ComputationalRawPreset.QUALITY)
            }
            Mode("HDR", preset == ComputationalRawPreset.HDR) {
                if (!capturing) onPreset(ComputationalRawPreset.HDR)
            }
            Mode("MAX", preset == ComputationalRawPreset.MAX) {
                if (!capturing) onPreset(ComputationalRawPreset.MAX)
            }
        }
        Text(
            message,
            color = Color.White.copy(alpha = 0.58f),
            fontSize = 10.sp,
            modifier = Modifier.padding(vertical = 4.dp),
        )
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 28.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Gallery(lastUri, onGallery)
            Shutter(enabled, capturing, onCapture)
            CircleButton("↻", canFlip && !capturing, onFlip)
        }
        Text(
            "NATIVE DNG · EXTERNAL GALLERY",
            color = Color.White.copy(alpha = 0.38f),
            fontSize = 8.sp,
            modifier = Modifier.padding(top = 5.dp),
        )
    }
}

@Composable
private fun CameraControlSheet(
    sheet: CameraSheet,
    aspect: DngAspect,
    flashAvailable: Boolean,
    flashMode: LightningFlashMode,
    highlight: Float,
    denoise: Float,
    upscale: Float,
    gridEnabled: Boolean,
    onAspect: (DngAspect) -> Unit,
    onFlash: () -> Unit,
    onHighlight: (Float) -> Unit,
    onDenoise: (Float) -> Unit,
    onUpscale: (Float) -> Unit,
    onGrid: () -> Unit,
    onOpenAspect: () -> Unit,
    onOpenHighlight: () -> Unit,
    onOpenDenoise: () -> Unit,
    onOpenUpscale: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(30.dp), color = Glass) {
        when (sheet) {
            CameraSheet.HIGHLIGHT -> ValueSlider(
                title = "HIGHLIGHT PROTECTION",
                valueLabel = "${(highlight * 100).roundToInt()}%",
                value = highlight,
                range = 0f..1f,
                steps = 19,
                onValue = onHighlight,
                onBack = onClose,
            )
            CameraSheet.DENOISE -> ValueSlider(
                title = "RAW DENOISE",
                valueLabel = "${(denoise * 100).roundToInt()}%",
                value = denoise,
                range = 0.35f..1f,
                steps = 12,
                onValue = onDenoise,
                onBack = onClose,
            )
            CameraSheet.UPSCALE -> ValueSlider(
                title = "PER-LENS DNG UPSCALE",
                valueLabel = String.format(Locale.US, "%.1f×", upscale),
                value = upscale,
                range = 1f..MAX_UPSCALE,
                steps = 19,
                onValue = { onUpscale((it * 10f).roundToInt() / 10f) },
                onBack = onClose,
                minus = { onUpscale((upscale - 0.1f).coerceAtLeast(1f)) },
                plus = { onUpscale((upscale + 0.1f).coerceAtMost(MAX_UPSCALE)) },
            )
            CameraSheet.ASPECT -> Column(Modifier.padding(18.dp)) {
                SheetHeader("ASPECT RATIO", onClose)
                Row(
                    Modifier.fillMaxWidth().padding(top = 16.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    DngAspect.entries.forEach { option ->
                        ControlTile(option.label, option == aspect) { onAspect(option) }
                    }
                }
            }
            CameraSheet.CONTROLS -> Column(Modifier.padding(horizontal = 18.dp, vertical = 16.dp)) {
                SheetHeader("CAMERA CONTROLS", onClose)
                Row(
                    Modifier.fillMaxWidth().padding(top = 18.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    ControlTile(
                        if (flashAvailable) "FLASH ${flashMode.label}" else "NO FLASH",
                        flashMode != LightningFlashMode.OFF && flashAvailable,
                        onFlash,
                    )
                    ControlTile("HIGHLIGHT", false, onOpenHighlight)
                    ControlTile("DENOISE", false, onOpenDenoise)
                }
                Row(
                    Modifier.fillMaxWidth().padding(top = 18.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    ControlTile(aspect.label, false, onOpenAspect)
                    ControlTile(String.format(Locale.US, "UP %.1f×", upscale), false, onOpenUpscale)
                    ControlTile(if (gridEnabled) "GRID ON" else "GRID OFF", gridEnabled, onGrid)
                }
            }
            CameraSheet.NONE -> Unit
        }
    }
}

@Composable
private fun SheetHeader(title: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(title, color = Color.White.copy(alpha = 0.72f), fontSize = 10.sp)
        Text("×", color = Color.White, fontSize = 18.sp, modifier = Modifier.clickable(onClick = onBack))
    }
}

@Composable
private fun ValueSlider(
    title: String,
    valueLabel: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onValue: (Float) -> Unit,
    onBack: () -> Unit,
    minus: (() -> Unit)? = null,
    plus: (() -> Unit)? = null,
) {
    Column(Modifier.padding(horizontal = 20.dp, vertical = 14.dp)) {
        SheetHeader(title, onBack)
        Text(
            valueLabel,
            color = IosYellow,
            fontSize = 22.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 4.dp),
        )
        if (minus != null && plus != null) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SmallRoundButton("−", minus)
                Slider(
                    value = value,
                    onValueChange = onValue,
                    valueRange = range,
                    steps = steps,
                    modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
                )
                SmallRoundButton("+", plus)
            }
        } else {
            Slider(value = value, onValueChange = onValue, valueRange = range, steps = steps)
        }
    }
}

@Composable
private fun SmallRoundButton(label: String, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.size(34.dp).clickable(onClick = onClick),
        shape = CircleShape,
        color = Color.Black.copy(alpha = 0.28f),
    ) {
        Box(contentAlignment = Alignment.Center) { Text(label, color = Color.White, fontSize = 19.sp) }
    }
}

@Composable
private fun ControlTile(label: String, selected: Boolean, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable(onClick = onClick)) {
        Surface(
            Modifier.size(54.dp),
            shape = CircleShape,
            color = if (selected) Color.White.copy(alpha = 0.22f) else Color.Black.copy(alpha = 0.24f),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    tileGlyph(label),
                    color = if (selected) IosYellow else Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                )
            }
        }
        Text(
            label,
            color = Color.White.copy(alpha = 0.70f),
            fontSize = 8.sp,
            modifier = Modifier.padding(top = 5.dp),
        )
    }
}

private fun tileGlyph(label: String): String = when {
    label.startsWith("FLASH") -> "⚡"
    label.startsWith("NO FLASH") -> "—"
    label.startsWith("HIGHLIGHT") -> "HL"
    label.startsWith("DENOISE") -> "NR"
    label.startsWith("UP ") -> "↑"
    label.startsWith("GRID") -> "#"
    else -> label.take(4)
}

@Composable
private fun Pill(text: String, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.clickable(onClick = onClick),
        shape = CircleShape,
        color = Color.Black.copy(alpha = 0.48f),
    ) {
        Text(
            text,
            color = Color.White,
            fontSize = 10.sp,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
        )
    }
}

@Composable
private fun ZoomBubble(text: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.size(if (selected) 42.dp else 34.dp).clickable(onClick = onClick),
        shape = CircleShape,
        color = if (selected) Color(0xFF2C2C2E) else Color.Transparent,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text,
                color = if (selected) IosYellow else Color.White.copy(alpha = 0.76f),
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun Mode(text: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text,
        color = if (selected) IosYellow else Color.White.copy(alpha = 0.80f),
        fontSize = 11.sp,
        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
        modifier = Modifier.clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

@Composable
private fun Shutter(enabled: Boolean, capturing: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(76.dp)
            .border(4.dp, if (enabled) Color.White else Color.Gray, CircleShape)
            .padding(6.dp)
            .background(if (capturing) Color.LightGray else Color.White, CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (capturing) {
            CircularProgressIndicator(Modifier.size(25.dp), color = Color.Black, strokeWidth = 2.dp)
        }
    }
}

@Composable
private fun Gallery(uri: Uri?, onClick: () -> Unit) {
    val preview by dngBitmap(uri, 220)
    Surface(
        modifier = Modifier.size(48.dp).clickable(enabled = uri != null, onClick = onClick),
        shape = CircleShape,
        color = Color(0xFF252525),
    ) {
        if (preview != null) {
            Image(preview!!, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Box(contentAlignment = Alignment.Center) { Text("▣", color = Color.White, fontSize = 20.sp) }
        }
    }
}

@Composable
private fun CircleButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.size(48.dp).clickable(enabled = enabled, onClick = onClick),
        shape = CircleShape,
        color = Color(0xFF252525),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(text, color = if (enabled) Color.White else Color.Gray, fontSize = 22.sp)
        }
    }
}

@Composable
private fun Grid(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val c = Color.White.copy(alpha = 0.22f)
        val stroke = 1.dp.toPx()
        drawLine(c, Offset(size.width / 3f, 0f), Offset(size.width / 3f, size.height), stroke)
        drawLine(c, Offset(size.width * 2f / 3f, 0f), Offset(size.width * 2f / 3f, size.height), stroke)
        drawLine(c, Offset(0f, size.height / 3f), Offset(size.width, size.height / 3f), stroke)
        drawLine(c, Offset(0f, size.height * 2f / 3f), Offset(size.width, size.height * 2f / 3f), stroke)
    }
}

@Composable
private fun dngBitmap(uri: Uri?, maxSide: Int): State<ImageBitmap?> {
    val context = LocalContext.current
    return produceState<ImageBitmap?>(null, uri, maxSide) {
        value = if (uri == null) {
            null
        } else {
            withContext(Dispatchers.IO) {
                runCatching {
                    val source = ImageDecoder.createSource(context.contentResolver, uri)
                    ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                        val longest = maxOf(info.size.width, info.size.height)
                        if (longest > maxSide) {
                            val scale = maxSide.toFloat() / longest
                            decoder.setTargetSize(
                                (info.size.width * scale).roundToInt().coerceAtLeast(1),
                                (info.size.height * scale).roundToInt().coerceAtLeast(1),
                            )
                        }
                        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    }.asImageBitmap()
                }.getOrNull()
            }
        }
    }
}

@Composable
private fun CenterText(text: String, action: (() -> Unit)? = null) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text, color = Color.White, textAlign = TextAlign.Center)
        if (action != null) {
            Spacer(Modifier.height(18.dp))
            Surface(Modifier.clickable(onClick = action), shape = CircleShape, color = Color.White) {
                Text("Continue", color = Color.Black, modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp))
            }
        }
    }
}

private fun openExternalPhoto(context: Context, uri: Uri) {
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "image/*")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching {
        context.startActivity(Intent.createChooser(intent, "Open DNG in gallery"))
    }.onFailure {
        Toast.makeText(context, "No gallery app can open this DNG", Toast.LENGTH_SHORT).show()
    }
}

private fun nextFlash(mode: LightningFlashMode): LightningFlashMode = when (mode) {
    LightningFlashMode.OFF -> LightningFlashMode.AUTO
    LightningFlashMode.AUTO -> LightningFlashMode.ON
    LightningFlashMode.ON -> LightningFlashMode.TORCH
    LightningFlashMode.TORCH -> LightningFlashMode.OFF
}

private fun toggleSheet(current: CameraSheet, requested: CameraSheet): CameraSheet =
    if (current == requested) CameraSheet.NONE else requested

private fun wide(routes: List<ValuableCameraRoute>): ValuableCameraRoute? {
    val back = routes.filter { it.camera.lensFacing == LensFacing.BACK }
    val pool = if (back.isNotEmpty()) back else routes
    return pool.firstOrNull { it.camera.classification.role == LensRole.WIDE }
        ?: pool.minByOrNull { abs(eqFocal(it) - 26f) }
}

private fun eqFocal(route: ValuableCameraRoute): Float =
    route.camera.equivalentFocalLengthsMm.minOrNull()
        ?: route.camera.focalLengthsMm.minOrNull()
        ?: 26f

private fun zoomText(value: Float): String = when {
    abs(value - 1f) < 0.06f -> "1×"
    value < 1f -> String.format(Locale.US, "%.1f×", value)
    abs(value - value.roundToInt()) < 0.08f -> "${value.roundToInt()}×"
    else -> String.format(Locale.US, "%.1f×", value)
}

private fun rotationDegrees(rotation: Int): Int = when (rotation) {
    AndroidSurface.ROTATION_90 -> 90
    AndroidSurface.ROTATION_180 -> 180
    AndroidSurface.ROTATION_270 -> 270
    else -> 0
}

private fun hasPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
