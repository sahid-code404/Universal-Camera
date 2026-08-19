package com.omnicam.feature.camera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.weight
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.omnicam.camera.camerax.Camera2PhotoController
import com.omnicam.camera.camerax.CameraBindResult
import com.omnicam.camera.camerax.CameraFlashMode
import com.omnicam.camera.camerax.PhotoAspectRatio
import com.omnicam.camera.camerax.PhotoCaptureResult
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
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val textureView = remember(context) { TextureView(context) }
    val preferences by preferencesStore.preferences.collectAsStateWithLifecycle(
        initialValue = LensPreferences(),
    )

    var permissionsGranted by remember { mutableStateOf(cameraPermissionsGranted(context)) }
    var profile by remember { mutableStateOf<DeviceCameraProfile?>(null) }
    var scanError by remember { mutableStateOf<String?>(null) }
    var selectedFacing by remember { mutableStateOf(LensFacing.BACK) }
    var selectedCameraId by remember { mutableStateOf<String?>(null) }
    var aspectRatio by remember { mutableStateOf(PhotoAspectRatio.FOUR_THREE) }
    var flashMode by remember { mutableStateOf(CameraFlashMode.OFF) }
    var zoomRatio by remember { mutableFloatStateOf(1f) }
    var minZoom by remember { mutableFloatStateOf(1f) }
    var maxZoom by remember { mutableFloatStateOf(1f) }
    var exposureComp by remember { mutableFloatStateOf(0f) }
    var bindResult by remember { mutableStateOf<CameraBindResult?>(null) }
    var captureResult by remember { mutableStateOf<PhotoCaptureResult?>(null) }
    var capturing by remember { mutableStateOf(false) }
    var focusPoint by remember { mutableStateOf<Offset?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    var showLensManager by remember { mutableStateOf(false) }
    var latestPhotoUri by remember { mutableStateOf<Uri?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        permissionsGranted = requiredCameraPermissions().all { permission ->
            grants[permission] == true ||
                ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        }
    }

    LaunchedEffect(Unit) {
        if (!permissionsGranted) {
            permissionLauncher.launch(requiredCameraPermissions())
        }
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
    val facingRoutes = remember(allRoutes, preferences, selectedFacing) {
        preferences
            .applyOrder(allRoutes.filter { it.camera.lensFacing == selectedFacing }, selectedFacing)
            .filter { preferences.isEnabled(it.camera.id) }
    }

    LaunchedEffect(facingRoutes, selectedFacing) {
        val visibleIds = facingRoutes.mapTo(mutableSetOf()) { it.camera.id }
        if (selectedCameraId !in visibleIds) {
            selectedCameraId = chooseDefaultRoute(facingRoutes)?.camera?.id
        }
    }

    val selectedRoute = facingRoutes.firstOrNull { it.camera.id == selectedCameraId }

    LaunchedEffect(selectedRoute, aspectRatio) {
        val route = selectedRoute ?: run {
            controller.unbind()
            bindResult = null
            return@LaunchedEffect
        }

        zoomRatio = 1f
        exposureComp = 0f
        controller.setFlashMode(flashMode)
        bindResult = controller.bind(textureView, route, aspectRatio)
        val success = bindResult as? CameraBindResult.Success
        if (success != null) {
            minZoom = success.minZoomRatio
            maxZoom = success.maxZoomRatio
            zoomRatio = 1f.coerceIn(minZoom, maxZoom)
            controller.setZoomRatio(zoomRatio)
        }
    }

    LaunchedEffect(flashMode) {
        controller.setFlashMode(flashMode)
    }

    LaunchedEffect(focusPoint) {
        if (focusPoint != null) {
            delay(1_100)
            focusPoint = null
        }
    }

    DisposableEffect(Unit) {
        onDispose { controller.unbind() }
    }

    Surface(modifier = modifier.fillMaxSize(), color = Color.Black) {
        when {
            !permissionsGranted -> CameraPermissionScreen(
                onGrant = { permissionLauncher.launch(requiredCameraPermissions()) },
            )

            scanError != null -> CameraErrorScreen(scanError.orEmpty())

            profile == null || resolution == null -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }

            else -> CameraContent(
                textureView = textureView,
                selectedRoute = selectedRoute,
                routes = facingRoutes,
                selectedFacing = selectedFacing,
                aspectRatio = aspectRatio,
                flashMode = flashMode,
                zoomRatio = zoomRatio,
                minZoom = minZoom,
                maxZoom = maxZoom,
                exposureComp = exposureComp,
                bindResult = bindResult,
                captureResult = captureResult,
                capturing = capturing,
                focusPoint = focusPoint,
                latestPhotoUri = latestPhotoUri,
                onTapFocus = { point, width, height ->
                    focusPoint = point
                    if (width > 0f && height > 0f) {
                        controller.focusAt(point.x / width, point.y / height)
                    }
                },
                onZoom = { requested ->
                    val next = requested.coerceIn(minZoom, maxZoom)
                    zoomRatio = next
                    controller.setZoomRatio(next)
                },
                onExposure = { requested ->
                    exposureComp = requested
                    controller.setExposureCompensation(requested.roundToInt())
                },
                onFlash = {
                    flashMode = nextFlashMode(
                        current = flashMode,
                        supported = selectedRoute?.camera?.flashAvailable == true,
                    )
                },
                onAspectRatio = {
                    aspectRatio = if (aspectRatio == PhotoAspectRatio.FOUR_THREE) {
                        PhotoAspectRatio.SIXTEEN_NINE
                    } else {
                        PhotoAspectRatio.FOUR_THREE
                    }
                },
                onSettings = { showSettings = true },
                onSelectLens = { selectedCameraId = it },
                onFlip = {
                    val target = if (selectedFacing == LensFacing.BACK) LensFacing.FRONT else LensFacing.BACK
                    if (allRoutes.any { it.camera.lensFacing == target && preferences.isEnabled(it.camera.id) }) {
                        selectedFacing = target
                        selectedCameraId = null
                        flashMode = CameraFlashMode.OFF
                    }
                },
                onCapture = {
                    if (!capturing && bindResult is CameraBindResult.Success) {
                        capturing = true
                        haptics.performHapticFeedback(
                            androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress,
                        )
                        scope.launch {
                            captureResult = controller.capturePhoto(displayRotationDegrees(textureView))
                            val success = captureResult as? PhotoCaptureResult.Success
                            if (success != null) latestPhotoUri = success.uri
                            capturing = false
                        }
                    }
                },
            )
        }
    }

    if (showSettings) {
        CameraSettingsSheet(
            onDismiss = { showSettings = false },
            onManageLenses = {
                showSettings = false
                showLensManager = true
            },
            onDiagnostics = {
                showSettings = false
                onOpenDiagnostics()
            },
        )
    }

    if (showLensManager && resolution != null) {
        CameraLensManagerSheet(
            routes = resolution.valuableRoutes,
            preferences = preferences,
            onDismiss = { showLensManager = false },
            onToggle = { cameraId, enabled ->
                scope.launch { preferencesStore.setEnabled(cameraId, enabled) }
            },
            onMove = { facing, cameraId, delta ->
                val faceRoutes = preferences.applyOrder(
                    resolution.valuableRoutes.filter { it.camera.lensFacing == facing },
                    facing,
                )
                val ids = faceRoutes.map { it.camera.id }.toMutableList()
                val from = ids.indexOf(cameraId)
                val to = (from + delta).coerceIn(0, ids.lastIndex)
                if (from >= 0 && from != to) {
                    ids.removeAt(from)
                    ids.add(to, cameraId)
                    scope.launch { preferencesStore.setOrder(facing, ids) }
                }
            },
            onReset = { scope.launch { preferencesStore.reset() } },
        )
    }
}

@Composable
private fun CameraContent(
    textureView: TextureView,
    selectedRoute: ValuableCameraRoute?,
    routes: List<ValuableCameraRoute>,
    selectedFacing: LensFacing,
    aspectRatio: PhotoAspectRatio,
    flashMode: CameraFlashMode,
    zoomRatio: Float,
    minZoom: Float,
    maxZoom: Float,
    exposureComp: Float,
    bindResult: CameraBindResult?,
    captureResult: PhotoCaptureResult?,
    capturing: Boolean,
    focusPoint: Offset?,
    latestPhotoUri: Uri?,
    onTapFocus: (Offset, Float, Float) -> Unit,
    onZoom: (Float) -> Unit,
    onExposure: (Float) -> Unit,
    onFlash: () -> Unit,
    onAspectRatio: () -> Unit,
    onSettings: () -> Unit,
    onSelectLens: (String) -> Unit,
    onFlip: () -> Unit,
    onCapture: () -> Unit,
) {
    val mainEq = chooseDefaultRoute(routes)?.camera?.equivalentFocalLengthsMm?.minOrNull()
    val exposureRange = selectedRoute?.camera?.aeCompensationRange ?: 0..0

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { textureView },
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(selectedRoute?.camera?.id, zoomRatio) {
                    detectTransformGestures { _, _, gestureZoom, _ ->
                        if (gestureZoom != 1f) onZoom(zoomRatio * gestureZoom)
                    }
                }
                .pointerInput(selectedRoute?.camera?.id) {
                    detectTapGestures { offset ->
                        onTapFocus(offset, size.width.toFloat(), size.height.toFloat())
                    }
                },
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(126.dp)
                .align(Alignment.TopCenter)
                .background(Color.Black.copy(alpha = 0.32f)),
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .padding(top = 42.dp, start = 14.dp, end = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CameraTextControl(
                text = if (selectedRoute?.camera?.flashAvailable == true) {
                    flashLabel(flashMode)
                } else {
                    "Flash --"
                },
                enabled = selectedRoute?.camera?.flashAvailable == true,
                onClick = onFlash,
            )
            CameraTextControl(
                text = if (aspectRatio == PhotoAspectRatio.FOUR_THREE) "4:3" else "16:9",
                onClick = onAspectRatio,
            )
            CameraTextControl(text = "Settings", onClick = onSettings)
        }

        bindStatusText(bindResult)?.let { status ->
            Surface(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 88.dp),
                shape = RoundedCornerShape(18.dp),
                color = Color.Black.copy(alpha = 0.58f),
            ) {
                Text(
                    text = status,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }

        focusPoint?.let { point ->
            Surface(
                modifier = Modifier
                    .offset {
                        IntOffset(
                            x = (point.x - 28).roundToInt(),
                            y = (point.y - 28).roundToInt(),
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
                    .padding(end = 8.dp)
                    .background(Color.Black.copy(alpha = 0.38f), RoundedCornerShape(18.dp))
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "EV ${exposureComp.roundToInt()}",
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                )
                Slider(
                    value = exposureComp,
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
                .background(Color.Black.copy(alpha = 0.84f))
                .padding(top = 10.dp, bottom = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (routes.isNotEmpty()) {
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    contentPadding = PaddingValues(horizontal = 12.dp),
                ) {
                    items(routes, key = { it.camera.id }) { route ->
                        LensPill(
                            text = lensLabel(route, mainEq),
                            selected = route.camera.id == selectedRoute?.camera?.id,
                            onClick = { onSelectLens(route.camera.id) },
                        )
                    }
                }
            }

            Text(
                text = if (zoomRatio > 1.02f) {
                    String.format(Locale.US, "%.1fx", zoomRatio)
                } else {
                    "PHOTO"
                },
                color = Color.White.copy(alpha = 0.82f),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(vertical = 7.dp),
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 28.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LatestPhotoThumbnail(latestPhotoUri)

                Surface(
                    modifier = Modifier.size(78.dp),
                    shape = CircleShape,
                    color = Color.White,
                    border = BorderStroke(2.dp, Color.LightGray),
                    enabled = !capturing && bindResult is CameraBindResult.Success,
                    onClick = onCapture,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        if (capturing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(30.dp),
                                color = Color.Black,
                            )
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
                    color = Color.Black.copy(alpha = 0.68f),
                    border = BorderStroke(1.dp, Color.DarkGray),
                    onClick = onFlip,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = if (selectedFacing == LensFacing.BACK) "Front" else "Rear",
                            color = Color.White,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }

            when (captureResult) {
                is PhotoCaptureResult.Failure -> Text(
                    text = captureResult.message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 6.dp, start = 16.dp, end = 16.dp),
                )
                else -> Unit
            }

            if (maxZoom > minZoom + 0.01f) {
                Slider(
                    value = zoomRatio.coerceIn(minZoom, maxZoom),
                    onValueChange = onZoom,
                    valueRange = minZoom..maxZoom,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 40.dp),
                )
            }
        }
    }
}

@Composable
private fun LatestPhotoThumbnail(uri: Uri?) {
    val context = LocalContext.current
    var bitmap by remember(uri) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(uri) {
        bitmap = if (uri == null) {
            null
        } else {
            withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use(BitmapFactory::decodeStream)
                }.getOrNull()
            }
        }
    }

    Surface(
        modifier = Modifier.size(50.dp),
        shape = RoundedCornerShape(12.dp),
        color = Color.DarkGray,
    ) {
        val current = bitmap
        if (current != null) {
            Image(
                bitmap = current.asImageBitmap(),
                contentDescription = "Latest OmniCam photo",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Box(contentAlignment = Alignment.Center) {
                Text("Gallery", color = Color.White, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun LensPill(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    if (selected) {
        Button(
            onClick = onClick,
            shape = CircleShape,
            contentPadding = PaddingValues(horizontal = 15.dp, vertical = 8.dp),
        ) {
            Text(text, fontWeight = FontWeight.Bold)
        }
    } else {
        TextButton(onClick = onClick) {
            Text(text, color = Color.White)
        }
    }
}

@Composable
private fun CameraTextControl(
    text: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    TextButton(onClick = onClick, enabled = enabled) {
        Text(
            text = text,
            color = if (enabled) Color.White else Color.White.copy(alpha = 0.4f),
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CameraSettingsSheet(
    onDismiss: () -> Unit,
    onManageLenses: () -> Unit,
    onDiagnostics: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
        ) {
            Text(
                text = "Camera settings",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = onManageLenses,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Manage lenses")
            }
            Spacer(Modifier.height(8.dp))
            TextButton(
                onClick = onDiagnostics,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Camera diagnostics")
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CameraLensManagerSheet(
    routes: List<ValuableCameraRoute>,
    preferences: LensPreferences,
    onDismiss: () -> Unit,
    onToggle: (String, Boolean) -> Unit,
    onMove: (LensFacing, String, Int) -> Unit,
    onReset: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
        ) {
            Text(
                text = "Lens layout",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "Enable only the useful lenses you want and choose their order.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(420.dp),
            ) {
                listOf(LensFacing.BACK, LensFacing.FRONT).forEach { facing ->
                    val faceRoutes = preferences.applyOrder(
                        routes.filter { it.camera.lensFacing == facing },
                        facing,
                    )
                    if (faceRoutes.isNotEmpty()) {
                        item(key = "header-$facing") {
                            Text(
                                text = if (facing == LensFacing.BACK) "Rear cameras" else "Front cameras",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(vertical = 8.dp),
                            )
                        }
                        items(faceRoutes, key = { "$facing-${it.camera.id}" }) { route ->
                            val index = faceRoutes.indexOfFirst { it.camera.id == route.camera.id }
                            val enabled = preferences.isEnabled(route.camera.id)
                            val enabledCount = faceRoutes.count { preferences.isEnabled(it.camera.id) }

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = lensManagerTitle(route),
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    Text(
                                        text = lensManagerSubtitle(route),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                TextButton(
                                    enabled = index > 0,
                                    onClick = { onMove(facing, route.camera.id, -1) },
                                ) { Text("Up") }
                                TextButton(
                                    enabled = index < faceRoutes.lastIndex,
                                    onClick = { onMove(facing, route.camera.id, 1) },
                                ) { Text("Down") }
                                Switch(
                                    checked = enabled,
                                    enabled = !(enabled && enabledCount <= 1),
                                    onCheckedChange = { onToggle(route.camera.id, it) },
                                )
                            }
                            HorizontalDivider()
                        }
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onReset) { Text("Reset") }
                Button(onClick = onDismiss) { Text("Done") }
            }
        }
    }
}

@Composable
private fun CameraPermissionScreen(onGrant: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "OmniCam",
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(10.dp))
        Text("Camera permission is required for the live viewfinder and photo capture.")
        Spacer(Modifier.height(20.dp))
        Button(onClick = onGrant) { Text("Allow camera") }
    }
}

@Composable
private fun CameraErrorScreen(message: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Camera unavailable",
            style = MaterialTheme.typography.headlineMedium,
        )
        Spacer(Modifier.height(8.dp))
        Text(message)
    }
}

private fun requiredCameraPermissions(): Array<String> = buildList {
    add(Manifest.permission.CAMERA)
    if (Build.VERSION.SDK_INT == Build.VERSION_CODES.P) {
        add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }
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

private fun nextFlashMode(
    current: CameraFlashMode,
    supported: Boolean,
): CameraFlashMode {
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

private fun bindStatusText(result: CameraBindResult?): String? = when (result) {
    is CameraBindResult.Failure -> "Lens ${result.cameraId}: ${result.reason}"
    else -> null
}

private fun chooseDefaultRoute(routes: List<ValuableCameraRoute>): ValuableCameraRoute? = routes.minByOrNull { route ->
    val eq = route.camera.equivalentFocalLengthsMm.minOrNull()
        ?: return@minByOrNull Float.MAX_VALUE
    val rolePenalty = if (route.camera.classification.role == LensRole.WIDE) 0f else 100f
    rolePenalty + abs(eq - 26f)
}

private fun lensLabel(
    route: ValuableCameraRoute,
    mainEq: Float?,
): String {
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
    val optical = eq?.let { String.format(Locale.US, "%.1f mm eq", it) } ?: "optics unknown"
    val access = when (route.access) {
        CameraRouteAccess.DIRECT_CAMERA_DEVICE -> "direct Camera2"
        CameraRouteAccess.PHYSICAL_VIA_LOGICAL -> "via logical ${route.logicalCameraIds.joinToString()}"
    }
    return "$optical - $access"
}
