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
import android.view.Surface
import android.view.TextureView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.omnicam.camera.camerax.Camera2PhotoController
import com.omnicam.camera.camerax.CameraBindResult
import com.omnicam.camera.camerax.CameraFlashMode
import com.omnicam.camera.camerax.PhotoAspectRatio
import com.omnicam.camera.camerax.PhotoCaptureResult
import com.omnicam.camera.camerax.PhotoOutputFormat
import com.omnicam.camera.capability.CameraCapabilityScanner
import com.omnicam.camera.capability.CameraRouteAccess
import com.omnicam.camera.capability.ValuableCameraResolver
import com.omnicam.camera.capability.ValuableCameraRoute
import com.omnicam.core.model.DeviceCameraProfile
import com.omnicam.core.model.LensFacing
import com.omnicam.core.model.LensRole
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
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
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val textureView = remember(context) { TextureView(context).apply { isOpaque = false } }
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
    var focusPoint by remember { mutableStateOf<Offset?>(null) }
    var latestPhoto by remember { mutableStateOf<Uri?>(null) }
    var settingsOpen by remember { mutableStateOf(false) }
    var lensManagerOpen by remember { mutableStateOf(false) }
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
    ) { result ->
        result.data?.data?.let { selected -> latestPhoto = selected }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> lifecycleResumed = true
                Lifecycle.Event.ON_PAUSE,
                Lifecycle.Event.ON_STOP,
                Lifecycle.Event.ON_DESTROY,
                -> lifecycleResumed = false
                else -> Unit
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

    LaunchedEffect(
        selectedRoute,
        aspect,
        preferences.photoFormat,
        preferences.photoQuality,
        lifecycleResumed,
    ) {
        if (!lifecycleResumed) {
            controller.unbind()
            bindResult = null
            return@LaunchedEffect
        }
        val route = selectedRoute ?: run {
            controller.unbind()
            bindResult = null
            return@LaunchedEffect
        }

        bindResult = null
        zoom = 1f
        exposure = 0f
        controller.setFlashMode(flash)
        bindResult = controller.bind(
            textureView = textureView,
            route = route,
            aspectRatio = aspect,
            outputFormat = preferences.photoFormat,
            quality = preferences.photoQuality,
        )
        (bindResult as? CameraBindResult.Success)?.let { success ->
            minZoom = success.minZoomRatio
            maxZoom = success.maxZoomRatio
            zoom = 1f.coerceIn(minZoom, maxZoom)
            controller.setZoomRatio(zoom)
        }
    }

    LaunchedEffect(flash) { controller.setFlashMode(flash) }
    LaunchedEffect(focusPoint) {
        if (focusPoint != null) {
            delay(1_000)
            focusPoint = null
        }
    }

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
                textureView = textureView,
                controller = controller,
                route = selectedRoute,
                routes = visibleRoutes,
                facing = facing,
                aspect = aspect,
                flash = flash,
                zoom = zoom,
                minZoom = minZoom,
                maxZoom = maxZoom,
                exposure = exposure,
                bindResult = bindResult,
                captureResult = captureResult,
                capturing = capturing,
                focusPoint = focusPoint,
                latestPhoto = latestPhoto,
                photoFormat = preferences.photoFormat,
                onTapFocus = { point, width, height ->
                    focusPoint = point
                    if (width > 0f && height > 0f) {
                        controller.focusAt(point.x / width, point.y / height)
                    }
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
                onAspect = {
                    aspect = if (aspect == PhotoAspectRatio.FOUR_THREE) {
                        PhotoAspectRatio.SIXTEEN_NINE
                    } else {
                        PhotoAspectRatio.FOUR_THREE
                    }
                },
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
                            captureResult = controller.capturePhoto(displayRotationDegrees(textureView))
                            (captureResult as? PhotoCaptureResult.Success)?.let { latestPhoto = it.uri }
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
                            captureResult = PhotoCaptureResult.Failure("No gallery app can open this photo")
                        }
                },
            )
        }
    }

    if (settingsOpen) {
        SettingsSheet(
            photoFormat = preferences.photoFormat,
            photoQuality = preferences.photoQuality,
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

    if (lensManagerOpen && resolution != null) {
        LensManagerSheet(
            routes = resolution.valuableRoutes,
            preferences = preferences,
            onDismiss = { lensManagerOpen = false },
            onToggle = { id, enabled -> scope.launch { preferencesStore.setEnabled(id, enabled) } },
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
    textureView: TextureView,
    controller: Camera2PhotoController,
    route: ValuableCameraRoute?,
    routes: List<ValuableCameraRoute>,
    facing: LensFacing,
    aspect: PhotoAspectRatio,
    flash: CameraFlashMode,
    zoom: Float,
    minZoom: Float,
    maxZoom: Float,
    exposure: Float,
    bindResult: CameraBindResult?,
    captureResult: PhotoCaptureResult?,
    capturing: Boolean,
    focusPoint: Offset?,
    latestPhoto: Uri?,
    photoFormat: PhotoOutputFormat,
    onTapFocus: (Offset, Float, Float) -> Unit,
    onZoom: (Float) -> Unit,
    onExposure: (Float) -> Unit,
    onFlash: () -> Unit,
    onAspect: () -> Unit,
    onSettings: () -> Unit,
    onSelectLens: (String) -> Unit,
    onFlip: () -> Unit,
    onCapture: () -> Unit,
    onOpenGallery: () -> Unit,
) {
    val mainEq = chooseDefaultRoute(routes)?.camera?.equivalentFocalLengthsMm?.minOrNull()
    val exposureRange = route?.camera?.aeCompensationRange ?: 0..0

    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        val landscape = maxWidth > maxHeight
        val previewAspect = if (landscape) {
            aspect.width.toFloat() / aspect.height.toFloat()
        } else {
            aspect.height.toFloat() / aspect.width.toFloat()
        }
        val parentAspect = if (maxHeight.value > 0f) maxWidth.value / maxHeight.value else previewAspect
        val previewModifier = if (previewAspect >= parentAspect) {
            Modifier.fillMaxWidth().aspectRatio(previewAspect)
        } else {
            Modifier.fillMaxHeight().aspectRatio(previewAspect)
        }

        AndroidView(
            factory = { textureView },
            update = { view -> controller.updatePreviewTransform(view) },
            modifier = previewModifier
                .align(Alignment.Center)
                .pointerInput(route?.camera?.id, zoom) {
                    detectTransformGestures { _, _, scale, _ ->
                        if (scale != 1f) onZoom(zoom * scale)
                    }
                }
                .pointerInput(route?.camera?.id) {
                    detectTapGestures { point ->
                        onTapFocus(point, size.width.toFloat(), size.height.toFloat())
                    }
                },
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .background(Color.Black.copy(alpha = 0.62f))
                .padding(top = 38.dp, start = 8.dp, end = 8.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            TopControl(
                if (route?.camera?.flashAvailable == true) flashLabel(flash) else "Flash --",
                route?.camera?.flashAvailable == true,
                onFlash,
            )
            TopControl(
                if (aspect == PhotoAspectRatio.FOUR_THREE) "4:3" else "16:9",
                true,
                onAspect,
            )
            TopControl("Settings", true, onSettings)
        }

        (bindResult as? CameraBindResult.Failure)?.let { failure ->
            StatusPill(
                text = "Lens ${failure.cameraId}: ${failure.reason}",
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 94.dp),
            )
        }

        focusPoint?.let { point ->
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

        if (exposureRange.first != exposureRange.last) {
            Column(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 6.dp)
                    .background(Color.Black.copy(alpha = 0.62f), RoundedCornerShape(18.dp))
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
                .background(Color.Black.copy(alpha = 0.92f))
                .padding(top = 8.dp, bottom = 22.dp),
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
                    append(" · ")
                    append(if (photoFormat == PhotoOutputFormat.HEIF) "HEIF" else "JPEG")
                },
                color = Color.White.copy(alpha = 0.82f),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(vertical = 6.dp),
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
                    color = Color.Black.copy(alpha = 0.7f),
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

            when (captureResult) {
                is PhotoCaptureResult.Failure -> Text(
                    captureResult.message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 5.dp),
                )
                is PhotoCaptureResult.Success -> if (captureResult.usedFormatFallback) {
                    Text(
                        "HEIF is unavailable on this lens/session; saved JPEG at full requested quality.",
                        color = Color.White.copy(alpha = 0.72f),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 5.dp),
                    )
                }
                null -> Unit
            }

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
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            BitmapFactory.decodeStream(input)
                        }
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
        ) {
            Text(label, fontWeight = FontWeight.Bold)
        }
    } else {
        TextButton(onClick = onClick) { Text(label, color = Color.White) }
    }
}

@Composable
private fun StatusPill(text: String, modifier: Modifier = Modifier) {
    Surface(
        modifier,
        shape = RoundedCornerShape(18.dp),
        color = Color.Black.copy(alpha = 0.72f),
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
                if (photoFormat == PhotoOutputFormat.HEIF) {
                    Button(onClick = { onPhotoFormat(PhotoOutputFormat.HEIF) }) { Text("HEIF") }
                } else {
                    TextButton(onClick = { onPhotoFormat(PhotoOutputFormat.HEIF) }) { Text("HEIF") }
                }
                if (photoFormat == PhotoOutputFormat.JPEG) {
                    Button(onClick = { onPhotoFormat(PhotoOutputFormat.JPEG) }) { Text("JPEG") }
                } else {
                    TextButton(onClick = { onPhotoFormat(PhotoOutputFormat.JPEG) }) { Text("JPEG") }
                }
            }
            Text(
                "HEIF is the default. OmniCam requests native HEIC/HEVC still output at the highest quality; if a lens or HAL does not support that session, it automatically falls back to JPEG instead of re-encoding a JPEG as HEIF.",
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
            )
            Text(
                "100 asks the device for its best supported HEIF/JPEG quality. HEIF is more efficient, but it is not mathematically lossless.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(18.dp))
            Button(onClick = onLenses, modifier = Modifier.fillMaxWidth()) {
                Text("Manage lenses")
            }
            TextButton(onClick = onDiagnostics, modifier = Modifier.fillMaxWidth()) {
                Text("Camera diagnostics")
            }
            Spacer(Modifier.height(20.dp))
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
    onMove: (LensFacing, String, Int) -> Unit,
    onReset: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Text(
                "Lens layout",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "Enable useful lenses and choose their order. Logical/vendor duplicate routes stay hidden.",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(10.dp))
            LazyColumn(Modifier.fillMaxWidth().height(420.dp)) {
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
                            val enabledCount = ordered.count { preferences.isEnabled(it.camera.id) }
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 6.dp),
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

private fun displayRotationDegrees(textureView: TextureView): Int = when (
    textureView.display?.rotation ?: Surface.ROTATION_0
) {
    Surface.ROTATION_90 -> 90
    Surface.ROTATION_180 -> 180
    Surface.ROTATION_270 -> 270
    else -> 0
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
