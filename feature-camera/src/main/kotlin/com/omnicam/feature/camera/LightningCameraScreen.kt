package com.omnicam.feature.camera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.viewfinder.compose.Viewfinder
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
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

private val CameraYellow = Color(0xFFFFD60A)

private enum class TunePanel { NONE, HIGHLIGHT, DENOISE, UPSCALE, ASPECT }
private enum class PhotoAspect(val label: String, val sensorRatio: Float?) {
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
    var aspect by remember { mutableStateOf(PhotoAspect.FOUR_THREE) }
    var tunePanel by remember { mutableStateOf(TunePanel.NONE) }
    var viewerUri by remember { mutableStateOf<Uri?>(null) }
    var lifecycleResumed by remember {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }

    val lensPrefs = remember { context.getSharedPreferences("omnicam_lens_upscale", Context.MODE_PRIVATE) }
    var upscale by remember(selectedId) {
        mutableFloatStateOf(selectedId?.let { lensPrefs.getFloat("factor_$it", 1f) } ?: 1f)
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

    LaunchedEffect(Unit) { if (!permissionGranted) permissionLauncher.launch(Manifest.permission.CAMERA) }

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
    val lastSaved = jobs.firstOrNull { it.stage == LightningJobStage.SAVED && it.dngUri != null }

    LaunchedEffect(selected?.camera?.id, lifecycleResumed, aspect) {
        controller.unbind()
        bindResult = null
        if (!lifecycleResumed || viewerUri != null) {
            spec = null
            return@LaunchedEffect
        }
        val route = selected ?: run {
            spec = null
            return@LaunchedEffect
        }
        spec = runCatching {
            controller.createViewfinderSpec(
                route = route,
                targetAspect = aspect.sensorRatio,
                sessionKey = "${route.camera.id}-${aspect.name}-${System.nanoTime()}",
            )
        }.onFailure {
            bindResult = ComputationalRawBindResult.Failure(route.camera.id, it.message ?: "Camera unavailable")
        }.getOrNull()
    }

    LaunchedEffect(viewerUri) {
        if (viewerUri != null) controller.unbind()
    }

    LaunchedEffect(jobs, capturing) {
        if (capturing) return@LaunchedEffect
        val active = jobs.firstOrNull { !it.terminal }
        val saved = jobs.firstOrNull { it.stage == LightningJobStage.SAVED }
        status = when {
            active != null -> "Processing ${jobs.count { !it.terminal }}"
            saved != null -> saved.message
            else -> "Ready"
        }
    }

    DisposableEffect(Unit) { onDispose { controller.unbind() } }

    Surface(modifier.fillMaxSize(), color = Color.Black) {
        when {
            viewerUri != null -> DngViewer(uri = viewerUri!!, onClose = { viewerUri = null })
            !permissionGranted -> CenterMessage("Camera access needed", "Allow camera permission to use OmniCam.") {
                permissionLauncher.launch(Manifest.permission.CAMERA)
            }
            scanError != null -> CenterMessage("Camera unavailable", scanError.orEmpty())
            routes.isEmpty() -> CenterMessage("RAW unavailable", "No useful direct RAW_SENSOR camera is exposed on this device.")
            else -> Column(Modifier.fillMaxSize().background(Color.Black)) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .background(Color.Black),
                    contentAlignment = Alignment.Center,
                ) {
                    val currentSpec = spec
                    val currentRoute = selected
                    val previewModifier = when (aspect) {
                        PhotoAspect.FULL -> Modifier.fillMaxSize()
                        else -> Modifier.fillMaxWidth().aspectRatio(1f / (aspect.sensorRatio ?: 4f / 3f))
                    }
                    Box(previewModifier.background(Color.Black), contentAlignment = Alignment.Center) {
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
                                            controller.setZoomRatio(controller.cameraState.value.zoomRatio * gestureZoom)
                                        }
                                    },
                            ) {
                                onSurfaceSession {
                                    bindResult = controller.bind(surface, currentSpec, currentRoute)
                                    try { awaitCancellation() } finally { controller.unbind() }
                                }
                            }
                            RuleOfThirdsGrid(Modifier.fillMaxSize())
                        } else {
                            CircularProgressIndicator(color = Color.White)
                        }

                        Row(
                            Modifier
                                .fillMaxWidth()
                                .align(Alignment.TopCenter)
                                .padding(top = 14.dp, start = 12.dp, end = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            GlassChip("DNG") { }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                GlassChip("HL") { tunePanel = toggle(tunePanel, TunePanel.HIGHLIGHT) }
                                GlassChip("NR") { tunePanel = toggle(tunePanel, TunePanel.DENOISE) }
                                GlassChip("•••") { tunePanel = toggle(tunePanel, TunePanel.ASPECT) }
                            }
                        }

                        if (tunePanel != TunePanel.NONE) {
                            TuneSheet(
                                panel = tunePanel,
                                highlight = highlight,
                                denoise = denoise,
                                upscale = upscale,
                                aspect = aspect,
                                onHighlight = { highlight = it },
                                onDenoise = { denoise = it },
                                onUpscale = {
                                    upscale = it
                                    selectedId?.let { id -> lensPrefs.edit().putFloat("factor_$id", it).apply() }
                                },
                                onAspect = {
                                    aspect = it
                                    tunePanel = TunePanel.NONE
                                },
                                onOpenUpscale = { tunePanel = TunePanel.UPSCALE },
                                modifier = Modifier.align(Alignment.BottomCenter).padding(14.dp),
                            )
                        }
                    }
                }

                CameraControls(
                    sameFacing = sameFacing,
                    selectedId = selectedId,
                    baseEq = baseEq,
                    overallZoom = overallZoom,
                    cameraStateZoom = cameraState.zoomRatio,
                    minZoom = cameraState.minZoomRatio,
                    maxZoom = cameraState.maxZoomRatio,
                    preset = preset,
                    capturing = capturing,
                    queueFull = queueFull,
                    status = status,
                    upscale = upscale,
                    aspect = aspect,
                    lastUri = lastSaved?.dngUri,
                    onLens = { id -> if (!capturing) selectedId = id },
                    onZoom = controller::setZoomRatio,
                    onPreset = { if (!capturing) preset = it },
                    onGallery = { lastSaved?.dngUri?.let { viewerUri = it } },
                    onAspect = { tunePanel = toggle(tunePanel, TunePanel.ASPECT) },
                    onUpscale = { tunePanel = toggle(tunePanel, TunePanel.UPSCALE) },
                    onFlip = {
                        val opposite = if (selected?.camera?.lensFacing == LensFacing.FRONT) LensFacing.BACK else LensFacing.FRONT
                        chooseWide(routes.filter { it.camera.lensFacing == opposite })?.let { selectedId = it.camera.id }
                    },
                    canFlip = routes.any { it.camera.lensFacing != selected?.camera?.lensFacing },
                    onCapture = {
                        if (capturing || queueFull) return@CameraControls
                        capturing = true
                        status = "Capturing"
                        scope.launch {
                            val result = controller.capture(
                                preset = preset,
                                tuning = LightningRawTuning(
                                    denoiseStrength = denoise,
                                    highlightProtection = highlight,
                                    upscaleFactor = upscale,
                                    aspectRatio = aspect.sensorRatio,
                                ),
                            ) { status = it }
                            status = when (result) {
                                is LightningCaptureResult.Queued -> "DNG processing"
                                is LightningCaptureResult.Failure -> result.message
                            }
                            capturing = false
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun CameraControls(
    sameFacing: List<ValuableCameraRoute>,
    selectedId: String?,
    baseEq: Float,
    overallZoom: Float,
    cameraStateZoom: Float,
    minZoom: Float,
    maxZoom: Float,
    preset: ComputationalRawPreset,
    capturing: Boolean,
    queueFull: Boolean,
    status: String,
    upscale: Float,
    aspect: PhotoAspect,
    lastUri: Uri?,
    onLens: (String) -> Unit,
    onZoom: (Float) -> Unit,
    onPreset: (ComputationalRawPreset) -> Unit,
    onGallery: () -> Unit,
    onAspect: () -> Unit,
    onUpscale: () -> Unit,
    onFlip: () -> Unit,
    canFlip: Boolean,
    onCapture: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().background(Color.Black).padding(top = 10.dp, bottom = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            sameFacing.forEach { route ->
                val factor = (routeEq(route) / baseEq).coerceAtLeast(0.1f)
                LensBubble(zoomLabel(factor), route.camera.id == selectedId) { onLens(route.camera.id) }
            }
        }
        Text(
            String.format(Locale.US, "%.1f×", overallZoom),
            color = CameraYellow,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 3.dp),
        )

        if (maxZoom > minZoom + 0.02f) {
            Slider(
                value = cameraStateZoom.coerceIn(minZoom, maxZoom),
                onValueChange = onZoom,
                valueRange = minZoom..maxZoom,
                modifier = Modifier.fillMaxWidth().height(28.dp).padding(horizontal = 54.dp),
            )
        } else Spacer(Modifier.height(28.dp))

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 52.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            ModeLabel("RAW", preset == ComputationalRawPreset.QUALITY) { onPreset(ComputationalRawPreset.QUALITY) }
            ModeLabel("HDR", preset == ComputationalRawPreset.HDR) { onPreset(ComputationalRawPreset.HDR) }
            ModeLabel("MAX", preset == ComputationalRawPreset.MAX) { onPreset(ComputationalRawPreset.MAX) }
        }

        Text(
            if (queueFull) "Processing queue full" else status,
            color = Color.White.copy(alpha = 0.66f),
            fontSize = 11.sp,
            modifier = Modifier.padding(vertical = 5.dp),
        )

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 28.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GalleryButton(lastUri, onGallery)
            ShutterButton(!capturing && !queueFull, capturing, onCapture)
            RoundTextButton("↻", canFlip && !capturing, onFlip)
        }

        Row(
            Modifier.fillMaxWidth().padding(top = 10.dp, start = 44.dp, end = 44.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            BottomOption(aspect.label, onAspect)
            BottomOption("DNG", {})
            BottomOption(String.format(Locale.US, "UP %.1f×", upscale), onUpscale)
        }
    }
}

@Composable
private fun TuneSheet(
    panel: TunePanel,
    highlight: Float,
    denoise: Float,
    upscale: Float,
    aspect: PhotoAspect,
    onHighlight: (Float) -> Unit,
    onDenoise: (Float) -> Unit,
    onUpscale: (Float) -> Unit,
    onAspect: (PhotoAspect) -> Unit,
    onOpenUpscale: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        color = Color(0xE62B2B2D),
    ) {
        when (panel) {
            TunePanel.HIGHLIGHT -> SliderSheet("HIGHLIGHT", highlight, 0f..1f, onHighlight)
            TunePanel.DENOISE -> SliderSheet("DENOISE", denoise, 0.35f..1f, onDenoise)
            TunePanel.UPSCALE -> SliderSheet("DNG UPSCALE  ${String.format(Locale.US, "%.1f×", upscale)}", upscale, 1f..2f, onUpscale)
            TunePanel.ASPECT -> Column(Modifier.padding(18.dp)) {
                Text("CAMERA CONTROLS", color = Color.White.copy(alpha = 0.55f), fontSize = 11.sp)
                Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    PhotoAspect.entries.forEach { choice ->
                        ControlTile(choice.label, choice == aspect) { onAspect(choice) }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.Center) {
                    ControlTile("UPSCALE", false, onOpenUpscale)
                }
            }
            TunePanel.NONE -> Unit
        }
    }
}

@Composable
private fun SliderSheet(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onValue: (Float) -> Unit) {
    Column(Modifier.padding(horizontal = 20.dp, vertical = 14.dp)) {
        Text(label, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        Slider(value = value, onValueChange = onValue, valueRange = range)
    }
}

@Composable
private fun ControlTile(label: String, selected: Boolean, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable(onClick = onClick)) {
        Surface(
            modifier = Modifier.size(54.dp),
            shape = CircleShape,
            color = if (selected) Color.White.copy(alpha = 0.22f) else Color.Black.copy(alpha = 0.28f),
        ) { Box(contentAlignment = Alignment.Center) { Text(label.take(2), color = Color.White, fontWeight = FontWeight.Bold) } }
        Text(label, color = Color.White.copy(alpha = 0.75f), fontSize = 9.sp, modifier = Modifier.padding(top = 5.dp))
    }
}

@Composable
private fun RuleOfThirdsGrid(modifier: Modifier = Modifier) {
    androidx.compose.foundation.Canvas(modifier) {
        val paint = Color.White.copy(alpha = 0.22f)
        val stroke = 1.dp.toPx()
        drawLine(paint, start = androidx.compose.ui.geometry.Offset(size.width / 3f, 0f), end = androidx.compose.ui.geometry.Offset(size.width / 3f, size.height), strokeWidth = stroke)
        drawLine(paint, start = androidx.compose.ui.geometry.Offset(size.width * 2f / 3f, 0f), end = androidx.compose.ui.geometry.Offset(size.width * 2f / 3f, size.height), strokeWidth = stroke)
        drawLine(paint, start = androidx.compose.ui.geometry.Offset(0f, size.height / 3f), end = androidx.compose.ui.geometry.Offset(size.width, size.height / 3f), strokeWidth = stroke)
        drawLine(paint, start = androidx.compose.ui.geometry.Offset(0f, size.height * 2f / 3f), end = androidx.compose.ui.geometry.Offset(size.width, size.height * 2f / 3f), strokeWidth = stroke)
    }
}

@Composable
private fun GlassChip(text: String, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.clickable(onClick = onClick),
        shape = CircleShape,
        color = Color.Black.copy(alpha = 0.46f),
    ) { Text(text, color = Color.White, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 11.dp, vertical = 7.dp)) }
}

@Composable
private fun LensBubble(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.size(if (selected) 42.dp else 34.dp).clickable(onClick = onClick),
        shape = CircleShape,
        color = if (selected) Color(0xFF2C2C2E) else Color.Transparent,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(label, color = if (selected) CameraYellow else Color.White.copy(alpha = 0.75f), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun ModeLabel(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        label,
        color = if (selected) CameraYellow else Color.White.copy(alpha = 0.78f),
        fontSize = 12.sp,
        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
        modifier = Modifier.clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 5.dp),
    )
}

@Composable
private fun ShutterButton(enabled: Boolean, capturing: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(78.dp)
            .border(4.dp, if (enabled) Color.White else Color.Gray, CircleShape)
            .padding(6.dp)
            .background(if (capturing) Color.LightGray else Color.White, CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (capturing) CircularProgressIndicator(Modifier.size(26.dp), color = Color.Black, strokeWidth = 2.dp)
    }
}

@Composable
private fun GalleryButton(uri: Uri?, onClick: () -> Unit) {
    val bitmap by rememberDngBitmap(uri, 256)
    Surface(
        modifier = Modifier.size(50.dp).clickable(enabled = uri != null, onClick = onClick),
        shape = CircleShape,
        color = Color(0xFF252525),
    ) {
        if (bitmap != null) {
            Image(bitmap!!, contentDescription = "Last DNG", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Box(contentAlignment = Alignment.Center) { Text("▣", color = Color.White, fontSize = 20.sp) }
        }
    }
}

@Composable
private fun RoundTextButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.size(50.dp).clickable(enabled = enabled, onClick = onClick),
        shape = CircleShape,
        color = Color(0xFF252525),
    ) { Box(contentAlignment = Alignment.Center) { Text(text, color = if (enabled) Color.White else Color.Gray, fontSize = 23.sp) } }
}

@Composable
private fun BottomOption(text: String, onClick: () -> Unit) {
    Text(text, color = Color.White.copy(alpha = 0.62f), fontSize = 10.sp, modifier = Modifier.clickable(onClick = onClick).padding(5.dp))
}

@Composable
private fun DngViewer(uri: Uri, onClose: () -> Unit) {
    val bitmap by rememberDngBitmap(uri, 2200)
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (bitmap != null) {
            Image(bitmap!!, contentDescription = "DNG photo", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        } else {
            CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
        }
        Surface(
            modifier = Modifier.align(Alignment.TopStart).padding(18.dp).size(44.dp).clickable(onClick = onClose),
            shape = CircleShape,
            color = Color.Black.copy(alpha = 0.55f),
        ) { Box(contentAlignment = Alignment.Center) { Text("‹", color = Color.White, fontSize = 34.sp) } }
        Text("DNG", color = Color.White, fontWeight = FontWeight.SemiBold, modifier = Modifier.align(Alignment.TopCenter).padding(top = 28.dp))
    }
}

@Composable
private fun rememberDngBitmap(uri: Uri?, maxSide: Int) = produceState<ImageBitmap?>(initialValue = null, uri, maxSide) {
    value = if (uri == null) null else withContext(Dispatchers.IO) {
        runCatching {
            val source = ImageDecoder.createSource(LocalContext.current.contentResolver, uri)
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                val width = info.size.width
                val height = info.size.height
                val longest = maxOf(width, height)
                if (longest > maxSide) {
                    val scale = maxSide.toFloat() / longest
                    decoder.setTargetSize((width * scale).roundToInt().coerceAtLeast(1), (height * scale).roundToInt().coerceAtLeast(1))
                }
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }.asImageBitmap()
        }.getOrNull()
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

private fun toggle(current: TunePanel, requested: TunePanel): TunePanel = if (current == requested) TunePanel.NONE else requested

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
