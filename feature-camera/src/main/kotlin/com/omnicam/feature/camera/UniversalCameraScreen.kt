package com.omnicam.feature.camera

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import android.view.Surface
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.viewfinder.compose.MutableCoordinateTransformer
import androidx.camera.viewfinder.compose.Viewfinder
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.omnicam.camera.camerax.Camera2PhotoController
import com.omnicam.camera.camerax.CameraBindResult
import com.omnicam.camera.camerax.CameraFlashMode
import com.omnicam.camera.camerax.CameraViewfinderSpec
import com.omnicam.camera.camerax.HeifEncodingPath
import com.omnicam.camera.camerax.PhotoAspectRatio
import com.omnicam.camera.camerax.PhotoCaptureResult
import com.omnicam.camera.camerax.PhotoOutputFormat
import com.omnicam.camera.camerax.ProControls
import com.omnicam.camera.capability.CameraCapabilityScanner
import com.omnicam.camera.capability.CameraRouteAccess
import com.omnicam.camera.capability.ValuableCameraResolver
import com.omnicam.camera.capability.ValuableCameraRoute
import com.omnicam.core.model.DeviceCameraProfile
import com.omnicam.core.model.LensFacing
import com.omnicam.core.model.LensRole
import java.util.Locale
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun UniversalCameraRoute(
    scanner: CameraCapabilityScanner,
    controller: Camera2PhotoController,
    preferencesStore: LensPreferencesStore,
    onOpenDiagnostics: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val hostView = LocalView.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val preferences by preferencesStore.preferences.collectAsStateWithLifecycle(LensPreferences())

    var permissionsGranted by remember { mutableStateOf(cameraPermissionsGranted(context)) }
    var profile by remember { mutableStateOf<DeviceCameraProfile?>(null) }
    var scanError by remember { mutableStateOf<String?>(null) }
    var facing by remember { mutableStateOf(LensFacing.BACK) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var aspect by remember { mutableStateOf(PhotoAspectRatio.FOUR_THREE) }
    var flash by remember { mutableStateOf(CameraFlashMode.OFF) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var minZoom by remember { mutableFloatStateOf(1f) }
    var maxZoom by remember { mutableFloatStateOf(1f) }
    var exposure by remember { mutableFloatStateOf(0f) }
    var bindResult by remember { mutableStateOf<CameraBindResult?>(null) }
    var captureResult by remember { mutableStateOf<PhotoCaptureResult?>(null) }
    var capturing by remember { mutableStateOf(false) }
    var latestPhoto by remember { mutableStateOf<Uri?>(null) }
    var settingsOpen by remember { mutableStateOf(false) }
    var lensManagerOpen by remember { mutableStateOf(false) }
    var proOpen by remember { mutableStateOf(false) }
    var proEnabled by remember { mutableStateOf(false) }
    var manualIso by remember { mutableStateOf(100) }
    var manualExposureNs by remember { mutableStateOf(16_666_667L) }
    var manualFocusDiopters by remember { mutableFloatStateOf(0f) }
    var viewfinderSpec by remember { mutableStateOf<CameraViewfinderSpec?>(null) }
    var lifecycleResumed by remember {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        permissionsGranted = requiredCameraPermissions().all { permission ->
            result[permission] == true || ContextCompat.checkSelfPermission(
                context,
                permission,
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result -> result.data?.data?.let { latestPhoto = it } }

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
        if (!permissionsGranted) permissionLauncher.launch(requiredCameraPermissions())
    }

    LaunchedEffect(permissionsGranted) {
        if (!permissionsGranted) return@LaunchedEffect
        scanError = null
        runCatching { scanner.scan() }
            .onSuccess { profile = it }
            .onFailure { scanError = it.message ?: it::class.java.simpleName }
    }

    val resolution = remember(profile) { profile?.let(ValuableCameraResolver::resolve) }
    val allRoutes = resolution?.valuableRoutes.orEmpty()
    val visibleRoutes = remember(allRoutes, preferences, facing) {
        preferences.applyOrder(
            routes = allRoutes.filter { it.camera.lensFacing == facing },
            facing = facing,
        ).filter { preferences.isEnabled(it.camera.id) }
    }

    LaunchedEffect(visibleRoutes, facing) {
        if (selectedId !in visibleRoutes.map { it.camera.id }) {
            selectedId = chooseDefaultRoute(visibleRoutes)?.camera?.id
        }
    }

    val selectedRoute = visibleRoutes.firstOrNull { it.camera.id == selectedId }
    val selectedUpscalingEnabled = selectedRoute?.let {
        preferences.isUpscalingEnabled(it.camera.id)
    } == true

    LaunchedEffect(selectedRoute?.camera?.id) {
        val camera = selectedRoute?.camera
        val isoRange = camera?.sensitivityRange
        val shutterRange = camera?.exposureTimeRangeNs
        manualIso = isoRange?.let { 100.coerceIn(it.first, it.last) } ?: 100
        manualExposureNs = shutterRange?.let {
            16_666_667L.coerceIn(it.first, it.last)
        } ?: 16_666_667L
        manualFocusDiopters = 0f
        if (camera?.manualSensorSupported != true) proEnabled = false
    }

    LaunchedEffect(
        proEnabled,
        manualIso,
        manualExposureNs,
        manualFocusDiopters,
        selectedRoute?.camera?.id,
    ) {
        controller.setProControls(
            ProControls(
                enabled = proEnabled && selectedRoute?.camera?.manualSensorSupported == true,
                iso = manualIso,
                exposureTimeNs = manualExposureNs,
                focusDistanceDiopters = manualFocusDiopters,
            ),
        )
    }

    LaunchedEffect(
        selectedRoute,
        aspect,
        preferences.photoFormat,
        preferences.photoQuality,
        selectedUpscalingEnabled,
        lifecycleResumed,
    ) {
        controller.unbind()
        bindResult = null
        captureResult = null
        zoom = 1f
        exposure = 0f
        if (!lifecycleResumed) {
            viewfinderSpec = null
            return@LaunchedEffect
        }
        val route = selectedRoute ?: run {
            viewfinderSpec = null
            return@LaunchedEffect
        }
        controller.setAdaptiveUpscalingEnabled(selectedUpscalingEnabled)
        viewfinderSpec = runCatching {
            controller.createViewfinderSpec(
                route = route,
                aspectRatio = aspect,
                sessionKey = "${preferences.photoFormat.name}:${preferences.photoQuality}:upscale=$selectedUpscalingEnabled",
            )
        }.onFailure { error ->
            bindResult = CameraBindResult.Failure(
                cameraId = route.camera.id,
                reason = error.message ?: error::class.java.simpleName,
            )
        }.getOrNull()
    }

    LaunchedEffect(flash) { controller.setFlashMode(flash) }

    DisposableEffect(Unit) {
        onDispose { controller.unbind() }
    }

    Surface(modifier.fillMaxSize(), color = Color.Black) {
        when {
            !permissionsGranted -> CenterMessage(
                title = "OmniCam",
                message = "Camera permission is required for live preview and photo capture.",
                action = "Allow camera",
                onAction = { permissionLauncher.launch(requiredCameraPermissions()) },
            )
            scanError != null -> CenterMessage("Camera unavailable", scanError.orEmpty())
            profile == null || resolution == null -> Box(
                Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }
            else -> CameraView(
                controller = controller,
                viewfinderSpec = viewfinderSpec,
                route = selectedRoute,
                routes = visibleRoutes,
                facing = facing,
                aspect = aspect,
                flash = flash,
                zoom = zoom,
                minZoom = minZoom,
                maxZoom = maxZoom,
                exposure = exposure,
                proEnabled = proEnabled,
                bindResult = bindResult,
                captureResult = captureResult,
                capturing = capturing,
                latestPhoto = latestPhoto,
                photoFormat = preferences.photoFormat,
                upscalingEnabled = selectedUpscalingEnabled,
                lifecycleResumed = lifecycleResumed,
                onSurfaceAvailable = { surface ->
                    val spec = viewfinderSpec
                    val route = selectedRoute
                    if (spec == null || route == null || !lifecycleResumed) return@CameraView
                    bindResult = null
                    controller.setFlashMode(flash)
                    controller.setAdaptiveUpscalingEnabled(
                        preferences.isUpscalingEnabled(route.camera.id),
                    )
                    val result = controller.bind(
                        surface = surface,
                        spec = spec,
                        route = route,
                        aspectRatio = aspect,
                        outputFormat = preferences.photoFormat,
                        quality = preferences.photoQuality,
                    )
                    bindResult = result
                    (result as? CameraBindResult.Success)?.let { success ->
                        minZoom = success.minZoomRatio
                        maxZoom = success.maxZoomRatio
                        zoom = 1f.coerceIn(minZoom, maxZoom)
                        controller.setZoomRatio(zoom)
                    }
                    controller.setProControls(
                        ProControls(
                            enabled = proEnabled && route.camera.manualSensorSupported,
                            iso = manualIso,
                            exposureTimeNs = manualExposureNs,
                            focusDistanceDiopters = manualFocusDiopters,
                        ),
                    )
                },
                onTapFocus = { sourcePoint, sourceSize ->
                    controller.focusAtSurface(
                        sourceX = sourcePoint.x,
                        sourceY = sourcePoint.y,
                        sourceWidth = sourceSize.width,
                        sourceHeight = sourceSize.height,
                    )
                },
                onZoom = { requested ->
                    zoom = requested.coerceIn(minZoom, maxZoom)
                    controller.setZoomRatio(zoom)
                },
                onExposure = { value ->
                    exposure = value
                    controller.setExposureCompensation(value.roundToInt())
                },
                onFlash = {
                    flash = nextFlashMode(flash, selectedRoute?.camera?.flashAvailable == true)
                },
                onAspect = { aspect = nextAspectRatio(aspect) },
                onPro = { proOpen = true },
                onSettings = { settingsOpen = true },
                onSelectLens = { selectedId = it },
                onFlip = {
                    val target = if (facing == LensFacing.BACK) LensFacing.FRONT else LensFacing.BACK
                    if (allRoutes.any { route ->
                            route.camera.lensFacing == target && preferences.isEnabled(route.camera.id)
                        }
                    ) {
                        facing = target
                        selectedId = null
                        flash = CameraFlashMode.OFF
                    }
                },
                onCapture = {
                    if (!capturing && bindResult is CameraBindResult.Success && lifecycleResumed) {
                        capturing = true
                        haptics.performHapticFeedback(
                            androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress,
                        )
                        scope.launch {
                            val immediate = controller.capturePhoto(
                                displayRotationDegrees(hostView.display?.rotation ?: Surface.ROTATION_0),
                            ) { finalized ->
                                captureResult = finalized
                                (finalized as? PhotoCaptureResult.Success)?.let {
                                    latestPhoto = it.uri
                                }
                            }
                            captureResult = immediate
                            (immediate as? PhotoCaptureResult.Success)?.let { latestPhoto = it.uri }
                            capturing = false
                        }
                    }
                },
                onOpenGallery = {
                    val intent = latestPhoto?.let { uri ->
                        Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(
                                uri,
                                context.contentResolver.getType(uri) ?: "image/*",
                            )
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                    } ?: Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI).apply {
                        type = "image/*"
                    }
                    runCatching { galleryLauncher.launch(intent) }
                        .onFailure {
                            captureResult = PhotoCaptureResult.Failure(
                                "No gallery app can open this photo",
                            )
                        }
                },
            )
        }
    }

    if (settingsOpen) {
        SettingsSheet(
            photoFormat = preferences.photoFormat,
            photoQuality = preferences.photoQuality,
            rawAvailable = selectedRoute?.let {
                it.access == CameraRouteAccess.DIRECT_CAMERA_DEVICE && it.camera.rawSupported
            } == true,
            onDismiss = { settingsOpen = false },
            onPhotoFormat = { format ->
                scope.launch { preferencesStore.setPhotoFormat(format) }
            },
            onPhotoQuality = { quality ->
                scope.launch { preferencesStore.setPhotoQuality(quality) }
            },
            onLenses = {
                settingsOpen = false
                lensManagerOpen = true
            },
            onDiagnostics = {
                settingsOpen = false
                onOpenDiagnostics()
            },
        )
    }

    if (proOpen) {
        ProSheet(
            route = selectedRoute,
            enabled = proEnabled,
            iso = manualIso,
            exposureTimeNs = manualExposureNs,
            focusDistanceDiopters = manualFocusDiopters,
            onDismiss = { proOpen = false },
            onEnabled = { proEnabled = it },
            onIso = { manualIso = it },
            onExposureTimeNs = { manualExposureNs = it },
            onFocusDistanceDiopters = { manualFocusDiopters = it },
        )
    }

    if (lensManagerOpen && resolution != null) {
        LensManagerSheet(
            routes = resolution.valuableRoutes,
            preferences = preferences,
            onDismiss = { lensManagerOpen = false },
            onToggle = { id, enabled -> scope.launch { preferencesStore.setEnabled(id, enabled) } },
            onUpscale = { id, enabled ->
                scope.launch { preferencesStore.setUpscalingEnabled(id, enabled) }
            },
            onMove = { face, id, delta ->
                val ordered = preferences.applyOrder(
                    resolution.valuableRoutes.filter { it.camera.lensFacing == face },
                    face,
                )
                val ids = ordered.map { it.camera.id }.toMutableList()
                val from = ids.indexOf(id)
                if (from >= 0) {
                    val to = (from + delta).coerceIn(0, ids.lastIndex)
                    if (to != from) {
                        ids.removeAt(from)
                        ids.add(to, id)
                        scope.launch { preferencesStore.setOrder(face, ids) }
                    }
                }
            },
            onReset = { scope.launch { preferencesStore.reset() } },
        )
    }
}

@Composable
private fun CameraView(
    controller: Camera2PhotoController,
    viewfinderSpec: CameraViewfinderSpec?,
    route: ValuableCameraRoute?,
    routes: List<ValuableCameraRoute>,
    facing: LensFacing,
    aspect: PhotoAspectRatio,
    flash: CameraFlashMode,
    zoom: Float,
    minZoom: Float,
    maxZoom: Float,
    exposure: Float,
    proEnabled: Boolean,
    bindResult: CameraBindResult?,
    captureResult: PhotoCaptureResult?,
    capturing: Boolean,
    latestPhoto: Uri?,
    photoFormat: PhotoOutputFormat,
    upscalingEnabled: Boolean,
    lifecycleResumed: Boolean,
    onSurfaceAvailable: suspend (android.view.Surface) -> Unit,
    onTapFocus: (Offset, Size) -> Unit,
    onZoom: (Float) -> Unit,
    onExposure: (Float) -> Unit,
    onFlash: () -> Unit,
    onAspect: () -> Unit,
    onPro: () -> Unit,
    onSettings: () -> Unit,
    onSelectLens: (String) -> Unit,
    onFlip: () -> Unit,
    onCapture: () -> Unit,
    onOpenGallery: () -> Unit,
) {
    val mainEq = chooseDefaultRoute(routes)?.camera?.equivalentFocalLengthsMm?.minOrNull()
    val exposureRange = route?.camera?.aeCompensationRange ?: 0..0
    val coordinateTransformer = remember(viewfinderSpec?.surfaceRequest?.requestId) {
        MutableCoordinateTransformer()
    }
    var focusPoint by remember(viewfinderSpec?.surfaceRequest?.requestId) {
        mutableStateOf<Offset?>(null)
    }

    LaunchedEffect(focusPoint) {
        if (focusPoint != null) {
            delay(1_000)
            focusPoint = null
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        PreviewViewport(
            aspect = aspect,
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 88.dp, bottom = 225.dp),
        ) {
            val spec = viewfinderSpec
            if (spec != null && lifecycleResumed) {
                Viewfinder(
                    surfaceRequest = spec.surfaceRequest,
                    transformationInfo = spec.transformationInfo,
                    coordinateTransformer = coordinateTransformer,
                    alignment = Alignment.Center,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(route?.camera?.id, zoom, spec.surfaceRequest.requestId) {
                            detectTransformGestures { _, _, scale, _ ->
                                if (scale != 1f) onZoom(zoom * scale)
                            }
                        }
                        .pointerInput(route?.camera?.id, proEnabled, spec.surfaceRequest.requestId) {
                            detectTapGestures { point ->
                                if (!proEnabled && bindResult is CameraBindResult.Success) {
                                    val sourcePoint = with(coordinateTransformer) { point.transform() }
                                    focusPoint = point
                                    onTapFocus(sourcePoint, spec.previewSize)
                                }
                            }
                        },
                ) {
                    onSurfaceSession {
                        onSurfaceAvailable(surface)
                        try {
                            awaitCancellation()
                        } finally {
                            controller.unbind()
                        }
                    }
                }
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }

            focusPoint?.takeIf { !proEnabled }?.let { point ->
                Surface(
                    modifier = Modifier
                        .offset {
                            IntOffset(
                                (point.x - 28).roundToInt(),
                                (point.y - 28).roundToInt(),
                            )
                        }
                        .size(56.dp),
                    shape = RoundedCornerShape(8.dp),
                    color = Color.Transparent,
                    border = BorderStroke(2.dp, Color.White),
                ) {}
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .background(Color.Black)
                .padding(top = 34.dp, start = 4.dp, end = 4.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            TopControl(
                if (route?.camera?.flashAvailable == true) flashLabel(flash) else "Flash --",
                route?.camera?.flashAvailable == true,
                onFlash,
            )
            TopControl(aspect.label, true, onAspect)
            TopControl(
                if (proEnabled) "PRO" else "Auto",
                route?.camera?.manualSensorSupported == true,
                onPro,
            )
            TopControl("Settings", true, onSettings)
        }

        (bindResult as? CameraBindResult.Failure)?.let { failure ->
            StatusPill(
                text = "Lens ${failure.cameraId}: ${failure.reason}",
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 88.dp),
            )
        }

        if (!proEnabled && exposureRange.first != exposureRange.last) {
            Column(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 6.dp)
                    .background(Color.Black.copy(alpha = 0.72f), RoundedCornerShape(18.dp))
                    .padding(6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "EV ${exposure.roundToInt()}",
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                )
                Slider(
                    value = exposure,
                    onValueChange = onExposure,
                    valueRange = exposureRange.first.toFloat()..exposureRange.last.toFloat(),
                    steps = (exposureRange.last - exposureRange.first - 1).coerceAtLeast(0),
                    modifier = Modifier.width(120.dp),
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .background(Color.Black)
                .padding(top = 8.dp, bottom = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                contentPadding = PaddingValues(horizontal = 12.dp),
            ) {
                items(routes, key = { it.camera.id }) { item ->
                    LensPill(
                        label = lensLabel(item, mainEq),
                        selected = item.camera.id == route?.camera?.id,
                        onClick = { onSelectLens(item.camera.id) },
                    )
                }
            }

            Text(
                buildString {
                    append(if (zoom > 1.02f) String.format(Locale.US, "%.1fx", zoom) else "PHOTO")
                    if (proEnabled) append(" · PRO")
                    append(" · ")
                    append(aspect.label)
                    append(" · ")
                    append(photoFormat.name)
                    if (upscalingEnabled && photoFormat != PhotoOutputFormat.DNG) {
                        append(" · 2× ADAPTIVE")
                    }
                },
                color = Color.White.copy(alpha = 0.82f),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(vertical = 5.dp),
            )

            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LatestThumbnail(latestPhoto, onOpenGallery)
                Surface(
                    modifier = Modifier.size(76.dp),
                    shape = CircleShape,
                    color = Color.White,
                    border = BorderStroke(2.dp, Color.LightGray),
                    enabled = !capturing && bindResult is CameraBindResult.Success,
                    onClick = onCapture,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        if (capturing) {
                            CircularProgressIndicator(Modifier.size(30.dp), color = Color.Black)
                        } else {
                            Surface(
                                modifier = Modifier.size(62.dp),
                                shape = CircleShape,
                                color = Color.White,
                                border = BorderStroke(2.dp, Color.Black),
                            ) {}
                        }
                    }
                }
                Surface(
                    modifier = Modifier.size(50.dp),
                    shape = CircleShape,
                    color = Color.Black,
                    border = BorderStroke(1.dp, Color.DarkGray),
                    onClick = onFlip,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            if (facing == LensFacing.BACK) "Front" else "Rear",
                            color = Color.White,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }

            CaptureStatus(captureResult)

            if (maxZoom > minZoom + 0.01f) {
                Slider(
                    value = zoom.coerceIn(minZoom, maxZoom),
                    onValueChange = onZoom,
                    valueRange = minZoom..maxZoom,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 40.dp),
                )
            }
        }
    }
}

@Composable
private fun PreviewViewport(
    aspect: PhotoAspectRatio,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val landscape = maxWidth > maxHeight
        val targetAspect = if (landscape) {
            aspect.width.toFloat() / aspect.height.toFloat()
        } else {
            aspect.height.toFloat() / aspect.width.toFloat()
        }
        val parentAspect = if (maxHeight.value > 0f) maxWidth.value / maxHeight.value else targetAspect
        val viewportModifier = if (targetAspect >= parentAspect) {
            Modifier.fillMaxWidth().aspectRatio(targetAspect)
        } else {
            Modifier.fillMaxHeight().aspectRatio(targetAspect)
        }
        Box(
            viewportModifier
                .align(Alignment.Center)
                .background(Color.Black),
        ) {
            content()
        }
    }
}

@Composable
private fun CaptureStatus(result: PhotoCaptureResult?) {
    val message = when (result) {
        is PhotoCaptureResult.Processing -> result.message
        is PhotoCaptureResult.Failure -> result.message
        is PhotoCaptureResult.Success -> {
            val resolution = formatResolution(result.width, result.height)
            val base = when {
                result.usedFormatFallback ->
                    "${result.requestedFormat.name} unavailable; saved ${result.actualFormat.name} · $resolution"
                result.actualFormat == PhotoOutputFormat.DNG ->
                    "Saved RAW DNG · $resolution"
                result.actualFormat == PhotoOutputFormat.HEIF &&
                    result.heifEncodingPath == HeifEncodingPath.SOFTWARE_HEVC ->
                    "Saved HEIF via YUV + HEVC · $resolution"
                result.actualFormat == PhotoOutputFormat.HEIF &&
                    result.heifEncodingPath == HeifEncodingPath.NATIVE_CAMERA ->
                    "Saved native Camera2 HEIF · $resolution"
                else -> "Saved ${result.actualFormat.name} · $resolution"
            }
            val enhancement = when {
                result.upscaled -> {
                    val source = formatResolution(result.sourceWidth, result.sourceHeight)
                    " · 2× upscale from $source"
                }
                result.usedMaximumResolutionMode -> " · maximum-resolution sensor mode"
                else -> ""
            }
            base + enhancement
        }
        null -> null
    } ?: return

    Text(
        message,
        color = if (result is PhotoCaptureResult.Failure) {
            MaterialTheme.colorScheme.error
        } else {
            Color.White.copy(alpha = 0.76f)
        },
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

@Composable
private fun LatestThumbnail(uri: Uri?, onClick: () -> Unit) {
    val context = LocalContext.current
    var bitmap by remember(uri) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(uri) {
        bitmap = if (uri == null) {
            null
        } else {
            withContext(Dispatchers.IO) {
                runCatching {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        context.contentResolver.loadThumbnail(
                            uri,
                            android.util.Size(256, 256),
                            null,
                        )
                    } else {
                        context.contentResolver.openInputStream(uri)?.use(BitmapFactory::decodeStream)
                    }
                }.getOrNull()
            }
        }
    }

    Surface(
        modifier = Modifier.size(50.dp),
        shape = RoundedCornerShape(12.dp),
        color = Color.DarkGray,
        onClick = onClick,
    ) {
        bitmap?.let { image ->
            Image(
                image.asImageBitmap(),
                "Open latest photo",
                Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } ?: Box(contentAlignment = Alignment.Center) {
            Text("Gallery", color = Color.White, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun TopControl(text: String, enabled: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = enabled) {
        Text(
            text,
            color = if (enabled) Color.White else Color.White.copy(alpha = 0.4f),
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun LensPill(label: String, selected: Boolean, onClick: () -> Unit) {
    if (selected) {
        Button(
            onClick = onClick,
            shape = CircleShape,
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 7.dp),
        ) { Text(label, fontWeight = FontWeight.Bold) }
    } else {
        TextButton(onClick = onClick) { Text(label, color = Color.White) }
    }
}

@Composable
private fun StatusPill(text: String, modifier: Modifier = Modifier) {
    Surface(
        modifier,
        shape = RoundedCornerShape(18.dp),
        color = Color.Black.copy(alpha = 0.78f),
    ) {
        Text(
            text,
            Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            color = Color.White,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsSheet(
    photoFormat: PhotoOutputFormat,
    photoQuality: Int,
    rawAvailable: Boolean,
    onDismiss: () -> Unit,
    onPhotoFormat: (PhotoOutputFormat) -> Unit,
    onPhotoQuality: (Int) -> Unit,
    onLenses: () -> Unit,
    onDiagnostics: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(20.dp)) {
            Text(
                "Camera settings",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(16.dp))

            Text("Photo format", fontWeight = FontWeight.SemiBold)
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FormatButton(
                    selected = photoFormat == PhotoOutputFormat.HEIF,
                    text = "HEIF",
                    onClick = { onPhotoFormat(PhotoOutputFormat.HEIF) },
                )
                FormatButton(
                    selected = photoFormat == PhotoOutputFormat.JPEG,
                    text = "JPEG",
                    onClick = { onPhotoFormat(PhotoOutputFormat.JPEG) },
                )
                FormatButton(
                    selected = photoFormat == PhotoOutputFormat.DNG,
                    text = "RAW DNG",
                    enabled = rawAvailable,
                    onClick = { onPhotoFormat(PhotoOutputFormat.DNG) },
                )
            }
            Text(
                if (rawAvailable) {
                    "Still capture now checks both normal and Camera2 high-resolution output lists. RAW DNG uses the native RAW_SENSOR stream; HEIF uses native HEIC or high-resolution YUV + HEVC."
                } else {
                    "Still capture checks both normal and high-resolution Camera2 sizes. This lens does not expose an independently usable RAW_SENSOR route."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(16.dp))
            Text("Compression quality: $photoQuality", fontWeight = FontWeight.SemiBold)
            Slider(
                value = photoQuality.toFloat(),
                onValueChange = { onPhotoQuality(it.roundToInt()) },
                valueRange = 70f..100f,
                steps = 29,
                enabled = photoFormat != PhotoOutputFormat.DNG,
            )
            Text(
                "Preview resolution is independent from photo resolution. Aspect modes crop the high-quality source instead of selecting tiny preview streams.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(16.dp))
            Text("Aspect ratios", fontWeight = FontWeight.SemiBold)
            Text(
                PhotoAspectRatio.entries.joinToString(" · ") { it.label },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "DNG keeps native RAW dimensions. Processed HEIF/JPEG uses the selected composition when the chosen pipeline supports it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(18.dp))
            Button(onClick = onLenses, modifier = Modifier.fillMaxWidth()) {
                Text("Manage lenses & upscaling")
            }
            Text(
                "Adaptive 2× upscaling can be enabled or disabled separately for every lens. It only enlarges eligible low-resolution processed streams; RAW/DNG always stays native.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = onDiagnostics, modifier = Modifier.fillMaxWidth()) {
                Text("Camera diagnostics")
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun FormatButton(
    selected: Boolean,
    text: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    if (selected) {
        Button(onClick = onClick, enabled = enabled) { Text(text) }
    } else {
        TextButton(onClick = onClick, enabled = enabled) { Text(text) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProSheet(
    route: ValuableCameraRoute?,
    enabled: Boolean,
    iso: Int,
    exposureTimeNs: Long,
    focusDistanceDiopters: Float,
    onDismiss: () -> Unit,
    onEnabled: (Boolean) -> Unit,
    onIso: (Int) -> Unit,
    onExposureTimeNs: (Long) -> Unit,
    onFocusDistanceDiopters: (Float) -> Unit,
) {
    val camera = route?.camera
    val supported = camera?.manualSensorSupported == true &&
        camera.sensitivityRange != null && camera.exposureTimeRangeNs != null
    val isoRange = camera?.sensitivityRange ?: 100..100
    val exposureRange = camera?.exposureTimeRangeNs ?: 16_666_667L..16_666_667L
    val minFocus = camera?.minimumFocusDistanceDiopters ?: 0f

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(20.dp)) {
            Text("Pro controls", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.width(240.dp)) {
                    Text("Manual sensor", fontWeight = FontWeight.SemiBold)
                    Text(
                        if (supported) {
                            "Manual ISO, shutter and focus use Camera2 sensor controls."
                        } else {
                            "Not exposed by this lens."
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(
                    checked = enabled && supported,
                    enabled = supported,
                    onCheckedChange = onEnabled,
                )
            }

            if (supported && enabled) {
                Spacer(Modifier.height(14.dp))
                Text("ISO $iso", fontWeight = FontWeight.SemiBold)
                Slider(
                    value = iso.toFloat().coerceIn(isoRange.first.toFloat(), isoRange.last.toFloat()),
                    onValueChange = { onIso(it.roundToInt()) },
                    valueRange = isoRange.first.toFloat()..isoRange.last.toFloat(),
                )

                Spacer(Modifier.height(8.dp))
                Text("Shutter ${formatShutter(exposureTimeNs)}", fontWeight = FontWeight.SemiBold)
                Slider(
                    value = longToLogSlider(exposureTimeNs, exposureRange),
                    onValueChange = { onExposureTimeNs(logSliderToLong(it, exposureRange)) },
                    valueRange = 0f..1f,
                )

                if (minFocus > 0f) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Focus ${formatFocusDistance(focusDistanceDiopters)}",
                        fontWeight = FontWeight.SemiBold,
                    )
                    Slider(
                        value = focusDistanceDiopters.coerceIn(0f, minFocus),
                        onValueChange = onFocusDistanceDiopters,
                        valueRange = 0f..minFocus,
                    )
                } else {
                    Text(
                        "This lens is fixed-focus or does not expose manual focus distance.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Text(
                    "In manual sensor mode AE and AF are disabled. Flash Auto/On are not used; Torch can remain active where supported.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(18.dp))
            Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("Done") }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LensManagerSheet(
    routes: List<ValuableCameraRoute>,
    preferences: LensPreferences,
    onDismiss: () -> Unit,
    onToggle: (String, Boolean) -> Unit,
    onUpscale: (String, Boolean) -> Unit,
    onMove: (LensFacing, String, Int) -> Unit,
    onReset: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Text(
                "Lens layout & upscaling",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "Each photographic lens keeps its own visibility, order and adaptive 2× upscale preference. Logical/vendor duplicate routes stay hidden.",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(10.dp))
            LazyColumn(Modifier.fillMaxWidth().height(470.dp)) {
                listOf(LensFacing.BACK, LensFacing.FRONT).forEach { face ->
                    val ordered = preferences.applyOrder(
                        routes.filter { it.camera.lensFacing == face },
                        face,
                    )
                    if (ordered.isNotEmpty()) {
                        item("header-$face") {
                            Text(
                                if (face == LensFacing.BACK) "Rear cameras" else "Front cameras",
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(vertical = 8.dp),
                            )
                        }
                        items(ordered, key = { "$face-${it.camera.id}" }) { item ->
                            val index = ordered.indexOfFirst { it.camera.id == item.camera.id }
                            val enabled = preferences.isEnabled(item.camera.id)
                            val upscaleEnabled = preferences.isUpscalingEnabled(item.camera.id)
                            val enabledCount = ordered.count { preferences.isEnabled(it.camera.id) }
                            Column(
                                Modifier.fillMaxWidth().padding(vertical = 6.dp),
                            ) {
                                Row(
                                    Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(Modifier.width(185.dp)) {
                                        Text(lensManagerTitle(item), fontWeight = FontWeight.SemiBold)
                                        Text(
                                            lensManagerSubtitle(item),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    TextButton(
                                        enabled = index > 0,
                                        onClick = { onMove(face, item.camera.id, -1) },
                                    ) { Text("Up") }
                                    TextButton(
                                        enabled = index < ordered.lastIndex,
                                        onClick = { onMove(face, item.camera.id, 1) },
                                    ) { Text("Down") }
                                    Switch(
                                        checked = enabled,
                                        enabled = !(enabled && enabledCount <= 1),
                                        onCheckedChange = { onToggle(item.camera.id, it) },
                                    )
                                }
                                Row(
                                    Modifier.fillMaxWidth().padding(top = 4.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(Modifier.width(270.dp)) {
                                        Text("Adaptive 2× upscale", fontWeight = FontWeight.SemiBold)
                                        Text(
                                            if (upscaleEnabled) {
                                                "Enabled for this lens when its processed source is ≤5 MP. RAW/DNG stays native."
                                            } else {
                                                "Disabled for this lens; save its native processed resolution."
                                            },
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    Switch(
                                        checked = upscaleEnabled,
                                        onCheckedChange = { onUpscale(item.camera.id, it) },
                                    )
                                }
                            }
                            HorizontalDivider()
                        }
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(vertical = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                TextButton(onClick = onReset) { Text("Reset") }
                Button(onClick = onDismiss) { Text("Done") }
            }
        }
    }
}

@Composable
private fun CenterMessage(
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
        Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(message)
        if (action != null && onAction != null) {
            Spacer(Modifier.height(18.dp))
            Button(onClick = onAction) { Text(action) }
        }
    }
}

private fun requiredCameraPermissions(): Array<String> = buildList {
    add(Manifest.permission.CAMERA)
    if (Build.VERSION.SDK_INT == Build.VERSION_CODES.P) add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
}.toTypedArray()

private fun cameraPermissionsGranted(context: Context): Boolean = requiredCameraPermissions().all {
    ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
}

private fun displayRotationDegrees(rotation: Int): Int = when (rotation) {
    Surface.ROTATION_90 -> 90
    Surface.ROTATION_180 -> 180
    Surface.ROTATION_270 -> 270
    else -> 0
}

private fun nextAspectRatio(current: PhotoAspectRatio): PhotoAspectRatio {
    val values = PhotoAspectRatio.entries
    return values[(values.indexOf(current) + 1) % values.size]
}

private fun nextFlashMode(current: CameraFlashMode, supported: Boolean): CameraFlashMode {
    if (!supported) return CameraFlashMode.OFF
    return when (current) {
        CameraFlashMode.OFF -> CameraFlashMode.AUTO
        CameraFlashMode.AUTO -> CameraFlashMode.ON
        CameraFlashMode.ON -> CameraFlashMode.TORCH
        CameraFlashMode.TORCH -> CameraFlashMode.OFF
    }
}

private fun flashLabel(mode: CameraFlashMode): String = when (mode) {
    CameraFlashMode.OFF -> "Flash Off"
    CameraFlashMode.AUTO -> "Flash Auto"
    CameraFlashMode.ON -> "Flash On"
    CameraFlashMode.TORCH -> "Torch"
}

private fun formatResolution(width: Int, height: Int): String {
    val megapixels = width.toDouble() * height.toDouble() / 1_000_000.0
    return String.format(Locale.US, "%dx%d · %.1f MP", width, height, megapixels)
}

private fun formatShutter(exposureTimeNs: Long): String {
    val seconds = exposureTimeNs / 1_000_000_000.0
    return if (seconds >= 1.0) {
        String.format(Locale.US, "%.1fs", seconds)
    } else {
        val denominator = (1.0 / seconds.coerceAtLeast(0.000001)).roundToInt().coerceAtLeast(1)
        "1/${denominator}s"
    }
}

private fun longToLogSlider(value: Long, range: LongRange): Float {
    if (range.first <= 0L || range.last <= range.first) return 0f
    val minLog = ln(range.first.toDouble())
    val maxLog = ln(range.last.toDouble())
    val valueLog = ln(value.coerceIn(range.first, range.last).toDouble())
    return ((valueLog - minLog) / (maxLog - minLog)).toFloat().coerceIn(0f, 1f)
}

private fun logSliderToLong(value: Float, range: LongRange): Long {
    if (range.first <= 0L || range.last <= range.first) return range.first
    val minLog = ln(range.first.toDouble())
    val maxLog = ln(range.last.toDouble())
    return exp(minLog + value.coerceIn(0f, 1f) * (maxLog - minLog))
        .toLong()
        .coerceIn(range.first, range.last)
}

private fun formatFocusDistance(diopters: Float): String {
    if (diopters <= 0.001f) return "∞"
    val meters = 1f / diopters
    return if (meters >= 1f) {
        String.format(Locale.US, "%.2f m", meters)
    } else {
        String.format(Locale.US, "%.0f cm", meters * 100f)
    }
}

private fun chooseDefaultRoute(routes: List<ValuableCameraRoute>): ValuableCameraRoute? =
    routes.minByOrNull { route ->
        val eq = route.camera.equivalentFocalLengthsMm.minOrNull()
            ?: return@minByOrNull Float.MAX_VALUE
        val penalty = if (route.camera.classification.role == LensRole.WIDE) 0f else 100f
        penalty + abs(eq - 26f)
    }

private fun lensLabel(route: ValuableCameraRoute, mainEq: Float?): String {
    val eq = route.camera.equivalentFocalLengthsMm.minOrNull()
    if (eq != null && mainEq != null && mainEq > 0f) {
        return String.format(Locale.US, "%.1fx", eq / mainEq)
    }
    return when (route.camera.classification.role) {
        LensRole.ULTRA_WIDE -> "UW"
        LensRole.TELEPHOTO -> "Tele"
        LensRole.LONG_TELEPHOTO -> "Tele+"
        LensRole.FRONT -> "Front"
        else -> route.camera.id
    }
}

private fun lensManagerTitle(route: ValuableCameraRoute): String =
    "${route.camera.classification.role.name.replace('_', ' ')} - ID ${route.camera.id}"

private fun lensManagerSubtitle(route: ValuableCameraRoute): String {
    val eq = route.camera.equivalentFocalLengthsMm.minOrNull()
    val optics = eq?.let { String.format(Locale.US, "%.1f mm eq", it) } ?: "optics unknown"
    val access = when (route.access) {
        CameraRouteAccess.DIRECT_CAMERA_DEVICE -> "direct Camera2"
        CameraRouteAccess.PHYSICAL_VIA_LOGICAL ->
            "via logical ${route.logicalCameraIds.joinToString()}"
    }
    return "$optics - $access"
}
