package com.omnicam.feature.camera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.viewfinder.compose.Viewfinder
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
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
import com.omnicam.camera.camerax.DngBindResult
import com.omnicam.camera.camerax.DngCaptureMode
import com.omnicam.camera.camerax.DngCaptureResult
import com.omnicam.camera.camerax.DngCaptureTuning
import com.omnicam.camera.camerax.DngJobStage
import com.omnicam.camera.camerax.DngLastPhoto
import com.omnicam.camera.camerax.DngOnlyCameraController
import com.omnicam.camera.camerax.DngViewfinderSpec
import com.omnicam.camera.capability.CameraCapabilityScanner
import com.omnicam.camera.capability.CameraRouteAccess
import com.omnicam.camera.capability.ValuableCameraResolver
import com.omnicam.camera.capability.ValuableCameraRoute
import com.omnicam.core.model.LensFacing
import com.omnicam.core.model.LensRole
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch

private val CameraYellow = Color(0xFFFFD60A)
private val PanelBlack = Color(0xF229292B)

enum class PreviewAspect(val label: String, val portraitRatio: Float) {
    FOUR_THREE("4:3", 3f / 4f),
    SIXTEEN_NINE("16:9", 9f / 16f),
    SQUARE("1:1", 1f),
}

@Composable
fun DngCameraRoute(
    scanner: CameraCapabilityScanner,
    controller: DngOnlyCameraController,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val store = remember { DngLensSettingsStore(context) }
    val jobs by controller.jobs.collectAsState()
    val cameraState by controller.cameraState.collectAsState()
    val lastPhoto by controller.lastPhoto.collectAsState()
    val activeJobs = jobs.count { !it.terminal }
    val queueFull = activeJobs >= 2

    var permissionGranted by remember { mutableStateOf(hasCameraPermission(context)) }
    var routes by remember { mutableStateOf<List<ValuableCameraRoute>>(emptyList()) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var scanError by remember { mutableStateOf<String?>(null) }
    var spec by remember { mutableStateOf<DngViewfinderSpec?>(null) }
    var bindResult by remember { mutableStateOf<DngBindResult?>(null) }
    var mode by remember { mutableStateOf(DngCaptureMode.HDR) }
    var capturing by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Ready") }
    var highlight by remember { mutableFloatStateOf(0.80f) }
    var denoise by remember { mutableFloatStateOf(0.88f) }
    var aspect by remember { mutableStateOf(store.aspect()) }
    var upscale by remember { mutableFloatStateOf(1f) }
    var manualZoom by remember { mutableFloatStateOf(1f) }
    var showControls by remember { mutableStateOf(false) }
    var viewerPhoto by remember { mutableStateOf<DngLastPhoto?>(null) }
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

    LaunchedEffect(selectedId) {
        upscale = selectedId?.let(store::upscale) ?: 1f
        manualZoom = 1f
    }

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
            bindResult = DngBindResult.Failure(route.camera.id, it.message ?: "Camera unavailable")
        }.getOrNull()
    }

    LaunchedEffect(selectedId, upscale, manualZoom, cameraState.cameraId, cameraState.maxZoomRatio) {
        if (selectedId == null || cameraState.cameraId != selectedId) return@LaunchedEffect
        val maxManual = max(1f, cameraState.maxZoomRatio / upscale.coerceAtLeast(1f))
        manualZoom = manualZoom.coerceIn(1f, maxManual)
        controller.setZoomRatio((manualZoom * upscale).coerceIn(cameraState.minZoomRatio, cameraState.maxZoomRatio))
    }

    LaunchedEffect(jobs, capturing) {
        if (capturing) return@LaunchedEffect
        val active = jobs.firstOrNull { !it.terminal }
        val saved = jobs.firstOrNull { it.stage == DngJobStage.SAVED }
        val failed = jobs.firstOrNull { it.stage == DngJobStage.FAILED }
        status = when {
            active != null -> active.message
            saved != null -> "Saved DNG"
            failed != null -> failed.message
            else -> "Ready"
        }
    }

    DisposableEffect(Unit) {
        onDispose { controller.unbind() }
    }

    if (viewerPhoto != null) {
        RawViewer(viewerPhoto!!, onClose = { viewerPhoto = null })
        return
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

                Box(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(aspect.portraitRatio)
                        .align(Alignment.Center)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Color.Black),
                ) {
                    if (currentSpec != null && currentRoute != null && lifecycleResumed) {
                        Viewfinder(
                            surfaceRequest = currentSpec.surfaceRequest,
                            transformationInfo = currentSpec.transformationInfo,
                            alignment = Alignment.Center,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxSize()
                                .pointerInput(cameraState.maxZoomRatio, selectedId, upscale) {
                                    detectTransformGestures { _, _, gestureZoom, _ ->
                                        val maxManual = max(1f, cameraState.maxZoomRatio / upscale.coerceAtLeast(1f))
                                        manualZoom = (manualZoom * gestureZoom).coerceIn(1f, maxManual)
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
                        RuleOfThirdsGrid(Modifier.fillMaxSize())
                    } else {
                        CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
                    }
                }

                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(150.dp)
                        .align(Alignment.TopCenter)
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Black.copy(alpha = 0.76f), Color.Black.copy(alpha = 0.18f), Color.Transparent),
                            ),
                        ),
                )

                Row(
                    Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                        .padding(top = 34.dp, start = 14.dp, end = 14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    GlassChip("DNG", active = true, onClick = {})
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GlassChip("${cameraState.previewFps} FPS", active = false, onClick = {})
                        RoundIcon("•••", active = showControls) { showControls = !showControls }
                    }
                }

                Column(
                    Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Transparent, Color.Black.copy(alpha = 0.70f), Color.Black),
                            ),
                        )
                        .padding(top = 82.dp, bottom = 22.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        String.format(Locale.US, "%.1f×", overallZoom),
                        color = CameraYellow,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp,
                    )

                    Row(
                        Modifier.horizontalScroll(rememberScrollState()).padding(top = 7.dp, bottom = 3.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        sameFacing.forEach { route ->
                            val factor = (routeEq(route) / baseEq).coerceAtLeast(0.1f)
                            LensBubble(
                                label = zoomLabel(factor),
                                selected = route.camera.id == selectedId,
                                onClick = {
                                    if (!capturing) {
                                        selectedId = route.camera.id
                                        showControls = false
                                    }
                                },
                            )
                        }
                    }

                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 72.dp, vertical = 5.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        DngCaptureMode.entries.forEach { item ->
                            ModeLabel(item.name, mode == item) {
                                if (!capturing) mode = item
                            }
                        }
                    }

                    Text(
                        if (queueFull) "Processing queue full" else status,
                        color = Color.White.copy(alpha = 0.66f),
                        fontSize = 11.sp,
                        modifier = Modifier.padding(bottom = 9.dp),
                    )

                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 28.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        GalleryButton(lastPhoto) {
                            lastPhoto?.let { viewerPhoto = it }
                        }

                        ShutterButton(
                            enabled = !capturing && !queueFull && bindResult is DngBindResult.Success,
                            capturing = capturing,
                        ) {
                            if (capturing || queueFull) return@ShutterButton
                            capturing = true
                            status = "Capturing"
                            scope.launch {
                                val result = controller.capture(
                                    mode = mode,
                                    tuning = DngCaptureTuning(
                                        denoiseStrength = denoise,
                                        highlightProtection = highlight,
                                    ),
                                ) { status = it }
                                status = when (result) {
                                    is DngCaptureResult.Queued -> "Processing DNG"
                                    is DngCaptureResult.Failure -> result.message
                                }
                                capturing = false
                            }
                        }

                        val opposite = if (selected?.camera?.lensFacing == LensFacing.FRONT) LensFacing.BACK else LensFacing.FRONT
                        val canFlip = routes.any { it.camera.lensFacing == opposite }
                        RoundIcon("↻", active = false, enabled = canFlip && !capturing) {
                            val next = chooseWide(routes.filter { it.camera.lensFacing == opposite })
                            if (next != null) selectedId = next.camera.id
                        }
                    }
                }

                if (showControls) {
                    DngControlSheet(
                        modifier = Modifier.align(Alignment.BottomCenter),
                        aspect = aspect,
                        onAspect = {
                            aspect = it
                            store.setAspect(it)
                        },
                        highlight = highlight,
                        onHighlight = { highlight = it },
                        denoise = denoise,
                        onDenoise = { denoise = it },
                        upscale = upscale,
                        onUpscale = { value ->
                            val id = selectedId ?: return@DngControlSheet
                            upscale = value
                            store.setUpscale(id, value)
                        },
                        lensLabel = selected?.let { zoomLabel((routeEq(it) / baseEq).coerceAtLeast(0.1f)) }.orEmpty(),
                        onClose = { showControls = false },
                    )
                }
            }
        }
    }
}

@Composable
private fun DngControlSheet(
    modifier: Modifier,
    aspect: PreviewAspect,
    onAspect: (PreviewAspect) -> Unit,
    highlight: Float,
    onHighlight: (Float) -> Unit,
    denoise: Float,
    onDenoise: (Float) -> Unit,
    upscale: Float,
    onUpscale: (Float) -> Unit,
    lensLabel: String,
    onClose: () -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 12.dp),
        shape = RoundedCornerShape(34.dp),
        color = PanelBlack,
        shadowElevation = 12.dp,
    ) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 18.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text("DNG Controls", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                    Text("Lens $lensLabel · DNG only", color = Color.White.copy(alpha = 0.56f), fontSize = 11.sp)
                }
                RoundIcon("×", active = false, onClick = onClose)
            }

            Text("ASPECT", color = Color.White.copy(alpha = 0.54f), fontSize = 10.sp, modifier = Modifier.padding(top = 14.dp, bottom = 7.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PreviewAspect.entries.forEach { item ->
                    Surface(
                        modifier = Modifier.weight(1f).clickable { onAspect(item) },
                        shape = RoundedCornerShape(18.dp),
                        color = if (aspect == item) Color.White else Color.White.copy(alpha = 0.10f),
                    ) {
                        Text(
                            item.label,
                            color = if (aspect == item) Color.Black else Color.White,
                            textAlign = TextAlign.Center,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(vertical = 10.dp),
                        )
                    }
                }
            }

            ControlSlider(
                title = "HIGHLIGHT",
                valueText = "${(highlight * 100).roundToInt()}",
                value = highlight,
                valueRange = 0f..1f,
                onValueChange = onHighlight,
            )
            ControlSlider(
                title = "DENOISE",
                valueText = "${(denoise * 100).roundToInt()}",
                value = denoise,
                valueRange = 0.45f..1f,
                onValueChange = onDenoise,
            )
            ControlSlider(
                title = "PER-LENS UPSCALE",
                valueText = String.format(Locale.US, "%.1f×", upscale),
                value = upscale,
                valueRange = DngLensSettingsStore.MIN_UPSCALE..DngLensSettingsStore.MAX_UPSCALE,
                steps = 19,
                onValueChange = onUpscale,
            )
            Text(
                "Upscale is applied in the Bayer RAW domain and the preview follows the same crop, so the saved DNG framing matches what you see.",
                color = Color.White.copy(alpha = 0.52f),
                fontSize = 10.sp,
                lineHeight = 14.sp,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun ControlSlider(
    title: String,
    valueText: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    onValueChange: (Float) -> Unit,
) {
    Column(Modifier.padding(top = 12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title, color = Color.White.copy(alpha = 0.58f), fontSize = 10.sp)
            Text(valueText, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Medium)
        }
        Slider(
            value = value.coerceIn(valueRange.start, valueRange.endInclusive),
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps,
            modifier = Modifier.height(34.dp),
        )
    }
}

@Composable
private fun RuleOfThirdsGrid(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val lineColor = Color.White.copy(alpha = 0.26f)
        drawLine(lineColor, start = androidx.compose.ui.geometry.Offset(size.width / 3f, 0f), end = androidx.compose.ui.geometry.Offset(size.width / 3f, size.height), strokeWidth = 1f)
        drawLine(lineColor, start = androidx.compose.ui.geometry.Offset(size.width * 2f / 3f, 0f), end = androidx.compose.ui.geometry.Offset(size.width * 2f / 3f, size.height), strokeWidth = 1f)
        drawLine(lineColor, start = androidx.compose.ui.geometry.Offset(0f, size.height / 3f), end = androidx.compose.ui.geometry.Offset(size.width, size.height / 3f), strokeWidth = 1f)
        drawLine(lineColor, start = androidx.compose.ui.geometry.Offset(0f, size.height * 2f / 3f), end = androidx.compose.ui.geometry.Offset(size.width, size.height * 2f / 3f), strokeWidth = 1f)
    }
}

@Composable
private fun GlassChip(text: String, active: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.clickable(onClick = onClick),
        shape = CircleShape,
        color = if (active) Color.White.copy(alpha = 0.88f) else Color.Black.copy(alpha = 0.38f),
    ) {
        Text(
            text,
            color = if (active) Color.Black else Color.White,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 7.dp),
        )
    }
}

@Composable
private fun RoundIcon(
    text: String,
    active: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.size(46.dp).clickable(enabled = enabled, onClick = onClick),
        shape = CircleShape,
        color = if (active) Color.White.copy(alpha = 0.90f) else Color.Black.copy(alpha = 0.46f),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text,
                color = when {
                    !enabled -> Color.Gray
                    active -> Color.Black
                    else -> Color.White
                },
                fontSize = if (text == "•••") 15.sp else 22.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun LensBubble(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.size(if (selected) 47.dp else 39.dp).clickable(onClick = onClick),
        shape = CircleShape,
        color = if (selected) Color(0xCC262628) else Color.Black.copy(alpha = 0.35f),
        border = if (selected) androidx.compose.foundation.BorderStroke(1.dp, CameraYellow.copy(alpha = 0.72f)) else null,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                label,
                color = if (selected) CameraYellow else Color.White,
                fontSize = if (selected) 12.sp else 11.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun ModeLabel(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        label,
        color = if (selected) CameraYellow else Color.White.copy(alpha = 0.76f),
        fontSize = 12.sp,
        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
        modifier = Modifier.clickable(onClick = onClick).padding(horizontal = 7.dp, vertical = 5.dp),
    )
}

@Composable
private fun GalleryButton(photo: DngLastPhoto?, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.size(52.dp).clickable(enabled = photo != null, onClick = onClick),
        shape = CircleShape,
        color = Color(0xFF252527),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.22f)),
    ) {
        if (photo?.bitmap != null) {
            Image(
                bitmap = photo.bitmap.asImageBitmap(),
                contentDescription = "Open last DNG",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Box(contentAlignment = Alignment.Center) {
                Text("▣", color = Color.White.copy(alpha = 0.70f), fontSize = 19.sp)
            }
        }
    }
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
        if (capturing) CircularProgressIndicator(Modifier.size(27.dp), color = Color.Black, strokeWidth = 2.dp)
    }
}

@Composable
private fun RawViewer(photo: DngLastPhoto, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    Surface(Modifier.fillMaxSize(), color = Color.Black) {
        Box(Modifier.fillMaxSize()) {
            photo.bitmap?.let { bitmap ->
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = photo.displayName,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            RoundIcon(
                text = "×",
                active = false,
                onClick = onClose,
            )
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.Black.copy(alpha = 0.72f)).padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(photo.displayName, color = Color.White, fontSize = 12.sp, textAlign = TextAlign.Center)
                Text(
                    "${photo.width}×${photo.height} DNG · ${String.format(Locale.US, "%.1f×", photo.zoomRatio)} RAW scale",
                    color = Color.White.copy(alpha = 0.60f),
                    fontSize = 10.sp,
                )
            }
        }
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
        ?: pool.minByOrNull { abs(routeEq(it) - 26f) }
}

private fun routeEq(route: ValuableCameraRoute): Float =
    route.camera.equivalentFocalLengthsMm.minOrNull()
        ?: route.camera.focalLengthsMm.minOrNull()
        ?: 26f

private fun zoomLabel(value: Float): String = when {
    abs(value - 1f) < 0.06f -> "1×"
    value < 1f -> String.format(Locale.US, "%.1f×", value)
    abs(value - value.roundToInt()) < 0.08f -> "${value.roundToInt()}×"
    else -> String.format(Locale.US, "%.1f×", value)
}

private fun hasCameraPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
