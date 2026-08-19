package com.omnicam.feature.camera

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.view.Surface
import android.view.TextureView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
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
    val preferences by preferencesStore.preferences.collectAsStateWithLifecycle(initialValue = LensPreferences())

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
        permissionsGranted = requiredCameraPermissions().all { permission -> grants[permission] == true ||
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED }
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
    val allRoutes = remember(resolution, preferences) {
        resolution?.valuableRoutes.orEmpty()
    }
    val facingRoutes = remember(allRoutes, preferences, selectedFacing) {
        val raw = allRoutes.filter { it.camera.lensFacing == selectedFacing }
        preferences.applyOrder(raw, selectedFacing).filter { preferences.isEnabled(it.camera.id) }
    }

    LaunchedEffect(facingRoutes, selectedFacing) {
        val ids = facingRoutes.mapTo(mutableSetOf()) { it.camera.id }
        if (selectedCameraId !in ids) {
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
        val success = bindResult as? com.omnicam.camera.camerax.CameraBindResult.Success
        if (success != null) {
            minZoom = success.minZoomRatio
            maxZoom = success.maxZoomRatio
            zoomRatio = 1f.coerceIn(minZoom, maxZoom)
        }
    }

    LaunchedEffect(flashMode) { controller.setFlashMode(flashMode) }

    LaunchedEffect(focusPoint) {
        if (focusPoint != null) {
            delay(1_200)
            focusPoint = null
        }
    }

    DisposableEffect(Unit) { onDispose { controller.unbind() } }

    Surface(modifier = modifier.fillMaxSize(), color = Color.Black) {
        when {
            !permissionsGranted -> CameraPermissionScreen(onGrant = { permissionLauncher.launch(requiredCameraPermissions()) })
            scanError != null -> CameraErrorScreen(scanError.orEmpty())
            profile == null || resolution == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
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
                    if (width > 0f && height > 0f) controller.focusAt(point.x / width, point.y / height)
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
                onFlash = { flashMode = nextFlashMode(flashMode, selectedRoute?.camera?.flashAvailable == true) },
                onAspectRatio = {
                    aspectRatio = if (aspectRatio == PhotoAspectRatio.FOUR_THREE) PhotoAspectRatio.SIXTEEN_NINE else PhotoAspectRatio.FOUR_THREE
                },
                onSettings = { showSettings = true },
                onSelectLens = { selectedCameraId = it },
                onFlip = {
                    val target = if (selectedFacing == LensFacing.BACK) LensFacing.FRONT else LensFacing.BACK
                    if (allRoutes.any { it.camera.lensFacing == target }) {
                        selectedFacing = target
                        flashMode = CameraFlashMode.OFF
                    }
                },
                onCapture = {
                    if (!capturing && bindResult is CameraBindResult.Success) {
                        capturing = true
                        haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                        scope.launch {
                            val rotationDegrees = displayRotationDegrees(textureView)
                            captureResult = controller.capturePhoto(rotationDegrees)
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
            onToggle = { cameraId, enabled -> scope.launch { preferencesStore.setEnabled(cameraId, enabled) } },
            onMove = { facing, cameraId, delta ->
                val faceRoutes = preferences.applyOrder(resolution.valuableRoutes.filter { it.camera.lensFacing == facing }, facing)
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
    val context = LocalContext.current
    val mainEq = chooseDefaultRoute(routes)?.camera?.equivalentFocalLengthsMm?.minOrNull()
    val exposureRange = selectedRoute?.camera?.aeCompensationRange ?: 0..0

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { textureView },
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(selectedRoute?.camera?.id) {
                    detectTransformGestures { _, _, gestureZoom, _ ->
                        if (gestureZoom != 1f) onZoom(zoomRatio * gestureZoom)
                    }
                }
                .pointerInput(selectedRoute?.camera?.id) {
                    detectTapGestures { offset -> onTapFocus(offset, size.width.toFloat(), size.height.toFloat()) }
                },
        )

        Box(
            Modifier.fillMaxWidth().height(130.dp).align(Alignment.TopCenter).background(Color.Black.copy(alpha = 0.30f)),
        )

        Row(
            modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter).padding(top = 42.dp, start = 18.dp, end = 18.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CameraTextControl(
                text = if (selectedRoute?.camera?.flashAvailable == true) flashLabel(flashMode) else "Flash â€”",
                enabled = selectedRoute?.camera?.flashAvailable == true,
                onClick = onFlash,
            )
            CameraTextControl(
                text = if (aspectRatio == PhotoAspectRatio.FOUR_THREE) "4:3" else "16:9",
                onClick = onAspectRatio,
            )
            CameraTextControl(text = "âš™", onClick = onSettings)
        }

        bindStatusText(bindResult)?.let { status ->
            Surface(
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 90.dp),
                shape = RoundedCornerShape(20.dp),
                color = Color.Black.copy(alpha = 0.55f),
            ) {
                Text(status, Modifier.padding(horizontal = 12.dp, vertical = 6.dp), color = Color.White, style = MaterialTheme.typography.labelSmall)
            }
        }

        focusPoint?.let { point ->
            Box(
                modifier = Modifier.offset { IntOffset((point.x - 30).roundToInt(), (point.y - 30).roundToInt()) }.size(60.dp),
                contentAlignment = Alignment.Center,
            ) { Text("â–¢", color = Color.White, style = MaterialTheme.typography.headlineLarge) }
        }

        if (exposureRange.first != exposureRange.last) {
            Column(
                modifier = Modifier.align(Alignment.CenterEnd).padding(end = 8.dp).background(Color.Black.copy(alpha = 0.35f), RoundedCornerShape(18.dp)).padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("EV ${exposureComp.roundToInt()}", color = Color.White, style = MaterialTheme.typography.labelSmall)
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
            modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter).background(Color.Black.copy(alpha = 0.82f)).padding(top = 10.dp, bottom = 24.dp),
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
                if (zoomRatio > 1.02f) String.format(Locale.US, "%.1fÃ—È‹›ÛÛT˜][ÊH[ÙH”ÕÈ‹ˆÛÛÜˆHÛÛÜ‹•Ú]K˜ÛÜJ[HH™ŠKˆİ[HHX]\šX[[YK\ÙÜ˜\K›X™[\™ÙKˆ[ÙYšY\ˆH[ÙYšY\‹œY[™Ê™\XØ[H™
Kˆ
B‚ˆ›İÊˆ[ÙYšY\ˆH[ÙYšY\‹™š[X^ÚY

KœY[™ÊÜš^›Û[H™
KˆÜš^›Û[\œ˜[™Ù[Y[H\œ˜[™Ù[Y[”ÜXÙP™]ÙY[‹ˆ™\XØ[[YÛ›Y[H[YÛ›Y[Ù[\•™\XØ[Kˆ
HÂˆ]\İİÕ[X›˜Z[
]\İİÕ\šJB‚ˆİ\™˜XÙJˆ[ÙYšY\ˆH[ÙYšY\‹œÚ^™JÎ™
KˆÚ\HHÚ\˜ÛTÚ\KˆÛÛÜˆHÛÛÜ‹•Ú]KˆÛÛXÚÈHÛØ\\™Kˆ[˜X›YHXØ\\š[™È	‰ˆš[™™\İ[\ÈØ[Y\˜Pš[™™\İ[”İXØÙ\ÜËˆ
HÂˆ›Ş
ÛÛ[[YÛ›Y[H[YÛ›Y[Ù[\ŠHÂˆYˆ
Ø\\š[™ÊHÚ\˜İ[\”›ÙÜ™\ÜÒ[™XØ]ÜŠ[ÙYšY\ˆH[ÙYšY\‹œÚ^™JÌ™
KÛÛÜˆHÛÛÜ‹›XÚÊBˆ[ÙHİ\™˜XÙJ[ÙYšY\ˆH[ÙYšY\‹œÚ^™JŒ‹™
KÚ\HHÚ\˜ÛTÚ\KÛÛÜˆHÛÛÜ‹•Ú]K›Ü™\ˆH[™›ÚY˜ÛÛ\ÜÙK™›İ[™][Û‹›Ü™\”İ›ÚÙJ‹™ÛÛÜ‹›XÚÊJHßBˆBˆB‚ˆİ\™˜XÙJˆ[ÙYšY\ˆH[ÙYšY\‹œÚ^™JL™
KˆÚ\HHÚ\˜ÛTÚ\KˆÛÛÜˆHÛÛÜ‹›XÚË˜ÛÜJ[HHYŠKˆÛÛXÚÈHÛ‘›\ˆ
HÂˆ›Ş
ÛÛ[[YÛ›Y[H[YÛ›Y[Ù[\ŠHÂˆ^
Yˆ
Ù[XİY˜XÚ[™ÈOH[œÑ˜XÚ[™ËPÒÊH¸¡®Èˆ[ÙH¸¡¦È‹ÛÛÜˆHÛÛÜ‹•Ú]Kİ[HHX]\šX[[YK\ÙÜ˜\KšXY[™TÛX[
BˆBˆBˆB‚ˆÚ[ˆ
Ø\\™T™\İ[
HÂˆ\ÈİĞØ\\™T™\İ[‘˜Z[\™HOˆ^
ˆØ\\™T™\İ[›Y\ÜØYÙKˆÛÛÜˆHX]\šX[[YK˜ÛÛÜ”ØÚ[YK™\œ›Ü‹ˆİ[HHX]\šX[[YK\ÙÜ˜\K˜›ÙTÛX[ˆ[ÙYšY\ˆH[ÙYšY\‹œY[™ÊÜH‹™İ\HM‹™[™HM‹™
Kˆ
Bˆ[ÙHOˆ[š]ˆBˆBˆBŸB‚ÛÛ\ÜØX›Bœš]˜]H[ˆ]\İİÕ[X›˜Z[
\šNˆ\šOÊHÂˆ˜[ÛÛ^HØØ[ÛÛ^˜İ\œ™[ˆ˜\ˆš]X\H™[Y[X™\Š\šJHÈ]]X›Tİ]SÙ[™›ÚY™Ü˜\XÜËš]X\ÏŠ[
HBˆ][˜ÚYY™™Xİ
\šJHÂˆš]X\Ëœ™XŞXÛJ
Bˆš]X\HYˆ
\šHOH[
H[[ÙHÚ]ÛÛ^
\Ü]Ú\œË’SÊHÂˆ[Ø]Ú[™ÈÂˆÛÛ^˜ÛÛ[™\ÛÛ™\‹›Ü[’[œ]İ™X[J\šJOË\ÙHÈ[œ]O‚ˆ[™›ÚY™Ü˜\XÜËš]X\˜XİÜK™XÛÙTİ™X[J[œ]
BˆBˆK™Ù]Ü“[

BˆBˆBˆİ\™˜XÙJ[ÙYšY\ˆH[ÙYšY\‹œÚ^™JL™
KÚ\HH›İ[™YÛÜ›™\”Ú\JL‹™
KÛÛÜˆHÛÛÜ‹‘\šÑÜ˜^JHÂˆ˜[İ\œ™[Hš]X\ˆYˆ
İ\œ™[OH[
HÂˆ[XYÙJˆš]X\Hİ\œ™[˜\Ò[XYÙPš]X\

KˆÛÛ[\ØÜš\[ÛˆH“]\İÛ[šPØ[HİÈ‹ˆÛÛ[ØØ[HHÛÛ[ØØ[KÜ›Üˆ[ÙYšY\ˆH[ÙYšY\‹™š[X^Ú^™J
Kˆ
BˆH[ÙHÂˆ›Ş
ÛÛ[[YÛ›Y[H[YÛ›Y[Ù[\ŠHÈ^
¸¥©È‹ÛÛÜˆHÛÛÜ‹•Ú]JHBˆBˆBŸB‚ÛÛ\ÜØX›Bœš]˜]H[ˆ[œÔ[
^ˆİš[™ËÙ[XİYˆ›ÛÛX[‹ÛÛXÚÎˆ

HOˆ[š]
HÂˆYˆ
Ù[XİY
HÂˆ]ÛŠÛÛXÚÈHÛÛXÚËÚ\HHÚ\˜ÛTÚ\KÛÛ[Y[™ÈHY[™Õ˜[Y\ÊÜš^›Û[HM™™\XØ[H™
JHÂˆ^
^›ÛÙZYÚH›ÛÙZYÚ›Û
BˆBˆH[ÙHÂˆ^]ÛŠÛÛXÚÈHÛÛXÚÊHÈ^
^ÛÛÜˆHÛÛÜ‹•Ú]JHBˆBŸB‚ÛÛ\ÜØX›Bœš]˜]H[ˆØ[Y\˜U^ÛÛ›Û
^ˆİš[™Ë[˜X›Yˆ›ÛÛX[ˆHYKÛÛXÚÎˆ

HOˆ[š]
HÂˆ^]ÛŠÛÛXÚÈHÛÛXÚË[˜X›YH[˜X›Y
HÂˆ^
^ÛÛÜˆHYˆ
[˜X›Y
HÛÛÜ‹•Ú]H[ÙHÛÛÜ‹•Ú]K˜ÛÜJ[HHYŠK›ÛÙZYÚH›ÛÙZYÚ”Ù[ZP›Û
BˆBŸB‚Ü[Š^\š[Y[[X]\šX[Ğ\N˜Û\ÜÊBÛÛ\ÜØX›Bœš]˜]H[ˆØ[Y\˜TÙ][™ÜÔÚY]
ˆÛ‘\ÛZ\ÜÎˆ

HOˆ[š]ˆÛ“X[˜YÙS[œÙ\Îˆ

HOˆ[š]ˆÛ‘XYÛ›ÜİXÜÎˆ

HOˆ[š]ŠHÂˆ[Ù[›İÛTÚY]
Û‘\ÛZ\ÜÔ™\]Y\İHÛ‘\ÛZ\ÜÊHÂˆÛÛ[[Š[ÙYšY\‹™š[X^ÚY

KœY[™ÊÜš^›Û[HŒ™™\XØ[H™
JHÂˆ^
Ø[Y\˜HÙ][™ÜÈ‹İ[HHX]\šX[[YK\ÙÜ˜\KšXY[™TÛX[›ÛÙZYÚH›ÛÙZYÚ›Û
BˆÜXÙ\Š[ÙYšY\‹šZYÚ
M‹™
JBˆ]ÛŠÛÛXÚÈHÛ“X[˜YÙS[œÙ\Ë[ÙYšY\ˆH[ÙYšY\‹™š[X^ÚY

JHÈ^
“X[˜YÙH[œÙ\ÈŠHBˆÜXÙ\Š[ÙYšY\‹šZYÚ
™
JBˆİ][™Y]ÛŠÛÛXÚÈHÛ‘XYÛ›ÜİXÜË[ÙYšY\ˆH[ÙYšY\‹™š[X^ÚY

JHÈ^
Ø[Y\˜HXYÛ›ÜİXÜÈŠHBˆÜXÙ\Š[ÙYšY\‹šZYÚ
™
JBˆBˆBŸB‚Ü[Š^\š[Y[[X]\šX[Ğ\N˜Û\ÜÊBÛÛ\ÜØX›Bœš]˜]H[ˆØ[Y\˜S[œÓX[˜YÙ\”ÚY]
ˆ›İ]\Îˆ\İ˜[XX›PØ[Y\˜T›İ]O‹ˆ™Y™\™[˜Ù\Îˆ[œÔ™Y™\™[˜Ù\ËˆÛ‘\ÛZ\ÜÎˆ

HOˆ[š]ˆÛ•ÙÙÛNˆ
İš[™Ë›ÛÛX[ŠHOˆ[š]ˆÛ“[İ™Nˆ
[œÑ˜XÚ[™Ëİš[™Ë[
HOˆ[š]ˆÛ”™\Ù]ˆ

HOˆ[š]ŠHÂˆ[Ù[›İÛTÚY]
Û‘\ÛZ\ÜÔ™\]Y\İHÛ‘\ÛZ\ÜÊHÂˆÛÛ[[Š[ÙYšY\‹™š[X^ÚY

KœY[™ÊÜš^›Û[HŒ™
JHÂˆ^
“[œÈ^[İ]‹İ[HHX]\šX[[YK\ÙÜ˜\KšXY[™TÛX[›ÛÙZYÚH›ÛÙZYÚ›Û
Bˆ^
ˆ‘[˜X›HÛ›HH[œÙ\È[İHØ[[™ÚÛÜÙHZ\ˆÜ™\‹ˆÙÚXØ[Ù\XØ]H™[™Üˆ›İ]\Èİ^HY[‹ˆ‹ˆİ[HHX]\šX[[YK\ÙÜ˜\K˜›ÙTÛX[ˆÛÛÜˆHX]\šX[[YK˜ÛÛÜ”ØÚ[YK›Û”İ\™˜XÙU˜\šX[ˆ
BˆÜXÙ\Š[ÙYšY\‹šZYÚ
L‹™
JBˆ^PÛÛ[[Š[ÙYšY\‹™š[X^ÚY

JHÂˆ\İÙŠ[œÑ˜XÚ[™ËPÒË[œÑ˜XÚ[™Ë‘”“Ó•
K™›Ü‘XXÚÈ˜XÚ[™ÈO‚ˆ˜[˜XÙT›İ]\ÈH™Y™\™[˜Ù\Ë˜\SÜ™\Š›İ]\Ë™š[\ˆÈ]˜Ø[Y\˜K›[œÑ˜XÚ[™ÈOH˜XÚ[™ÈK˜XÚ[™ÊBˆYˆ
˜XÙT›İ]\Ëš\Ó›İ[\J
JHÂˆ][JÙ^HHšXY\‹I˜XÚ[™ÈŠHÂˆ^
ˆYˆ
˜XÚ[™ÈOH[œÑ˜XÚ[™ËPÒÊH”™X\ˆØ[Y\˜\Èˆ[ÙH‘œ›ÛØ[Y\˜\È‹ˆİ[HHX]\šX[[YK\ÙÜ˜\K]SYY][Kˆ›ÛÙZYÚH›ÛÙZYÚ›Ûˆ[ÙYšY\ˆH[ÙYšY\‹œY[™Ê™\XØ[H™
Kˆ
BˆBˆ][\Ê˜XÙT›İ]\ËÙ^HHÈ‰Ù˜XÚ[™ßKIÚ]˜Ø[Y\˜KšYHˆJHÈ›İ]HO‚ˆ˜[[™^H˜XÙT›İ]\Ëš[™^Ù‘š\œİÈ]˜Ø[Y\˜KšYOH›İ]K˜Ø[Y\˜KšYBˆ˜[[˜X›YH™Y™\™[˜Ù\Ëš\Ñ[˜X›Y
›İ]K˜Ø[Y\˜KšY
Bˆ˜[[˜X›YÛİ[H˜XÙT›İ]\Ë˜Ûİ[È™Y™\™[˜Ù\Ëš\Ñ[˜X›Y
]˜Ø[Y\˜KšY
HBˆ›İÊˆ[ÙYšY\ˆH[ÙYšY\‹™š[X^ÚY

KœY[™Ê™\XØ[HË™
Kˆ™\XØ[[YÛ›Y[H[YÛ›Y[Ù[\•™\XØ[Kˆ
HÂˆÛÛ[[Š[ÙYšY\‹ÙZYÚ
YŠJHÂˆ^
[œÓX[˜YÙ\•]J›İ]JK›ÛÙZYÚH›ÛÙZYÚ”Ù[ZP›Û
Bˆ^
[œÓX[˜YÙ\”İX]J›İ]JKİ[HHX]\šX[[YK\ÙÜ˜\K˜›ÙTÛX[ÛÛÜˆHX]\šX[[YK˜ÛÛÜ”ØÚ[YK›Û”İ\™˜XÙU˜\šX[
BˆBˆ^]ÛŠ[˜X›YH[™^ˆÛÛXÚÈHÈÛ“[İ™J˜XÚ[™Ë›İ]K˜Ø[Y\˜KšYLJHJHÈ^
¸¡¤HŠHBˆ^]ÛŠ[˜X›YH[™^˜XÙT›İ]\Ë›\İ[™^ÛÛXÚÈHÈÛ“[İ™J˜XÚ[™Ë›İ]K˜Ø[Y\˜KšYJHJHÈ^
¸¡¤ÈŠHBˆİÚ]Ú
ˆÚXÚÙYH[˜X›Yˆ[˜X›YHJ[˜X›Y	‰ˆ[˜X›YÛİ[HJKˆÛÚXÚÙYÚ[™ÙHHÈÛ•ÙÙÛJ›İ]K˜Ø[Y\˜KšY]
HKˆ
BˆBˆÜš^›Û[]šY\Š
BˆBˆBˆBˆBˆ›İÊ[ÙYšY\‹™š[X^ÚY

KœY[™Ê™\XØ[HM‹™
KÜš^›Û[\œ˜[™Ù[Y[H\œ˜[™Ù[Y[”ÜXÙP™]ÙY[ŠHÂˆ^]ÛŠÛÛXÚÈHÛ”™\Ù]
HÈ^
”™\Ù]ŠHBˆ]ÛŠÛÛXÚÈHÛ‘\ÛZ\ÜÊHÈ^
‘Û™HŠHBˆBˆBˆBŸB‚ÛÛ\ÜØX›Bœš]˜]H[ˆØ[Y\˜T\›Z\ÜÚ[Û”ØÜ™Y[ŠÛ‘Ü˜[ˆ

HOˆ[š]
HÂˆÛÛ[[Šˆ[ÙYšY\‹™š[X^Ú^™J
KœY[™Ê™
Kˆ™\XØ[\œ˜[™Ù[Y[H\œ˜[™Ù[Y[Ù[\‹ˆÜš^›Û[[YÛ›Y[H[YÛ›Y[Ù[\’Üš^›Û[Kˆ
HÂˆ^
“Û[šPØ[H‹İ[HHX]\šX[[YK\ÙÜ˜\K™\Ü^TÛX[›ÛÙZYÚH›ÛÙZYÚ›Û
BˆÜXÙ\Š[ÙYšY\‹šZYÚ
L™
JBˆ^
Ø[Y\˜HXØÙ\ÜÈ\È™\]Z\™YÈÚİÈH]™HšY]Ùš[™\ˆ[™Ø\\™HİÜËˆŠBˆÜXÙ\Š[ÙYšY\‹šZYÚ
Œ™
JBˆ]ÛŠÛÛXÚÈHÛ‘Ü˜[
HÈ^
[İÈØ[Y\˜HŠHBˆBŸB‚ÛÛ\ÜØX›Bœš]˜]H[ˆØ[Y\˜Q\œ›Ü”ØÜ™Y[ŠY\ÜØYÙNˆİš[™ÊHÂˆÛÛ[[Šˆ[ÙYšY\‹™š[X^Ú^™J
KœY[™Ê™
Kˆ™\XØ[\œ˜[™Ù[Y[H\œ˜[™Ù[Y[Ù[\‹ˆÜš^›Û[[YÛ›Y[H[YÛ›Y[Ù[\’Üš^›Û[Kˆ
HÂˆ^
Ø[Y\˜H[˜]˜Z[X›H‹İ[HHX]\šX[[YK\ÙÜ˜\KšXY[™SYY][JBˆÜXÙ\Š[ÙYšY\‹šZYÚ
™
JBˆ^
Y\ÜØYÙJBˆBŸB‚œš]˜]H[ˆ™\]Z\™YØ[Y\˜T\›Z\ÜÚ[ÛœÊ
Nˆ\œ˜^Oİš[™ÏˆHZ[\İÂˆY
X[šY™\İœ\›Z\ÜÚ[Û‹ĞSQTJBˆYˆ
Z[•‘T”ÒSÓ‹”Ñ×ÒS•OHZ[•‘T”ÒSÓ—ĞÓÑTË”
HY
X[šY™\İœ\›Z\ÜÚ[Û‹•Ô’UWÑVT“SÔÕÔQÑJBŸKÕ\Y\œ˜^J
B‚œš]˜]H[ˆØ[Y\˜T\›Z\ÜÚ[ÛœÑÜ˜[Y
ÛÛ^ˆ[™›ÚY˜ÛÛ[ÛÛ^
Nˆ›ÛÛX[ˆH™\]Z\™YØ[Y\˜T\›Z\ÜÚ[ÛœÊ
K˜[ÂˆÛÛ^ÛÛ\]˜ÚXÚÔÙ[”\›Z\ÜÚ[ÛŠÛÛ^]
HOHXÚØYÙSX[˜YÙ\‹”T“RTÔÒSÓ—ÑÔS•QŸB‚œš]˜]H[ˆ\Ü^T›İ][Û‘YÜ™Y\Ê^\™UšY]Îˆ^\™UšY]ÊNˆ[HÚ[ˆ
^\™UšY]Ë™\Ü^OËœ›İ][ÛˆÎˆİ\™˜XÙK”“ÕUSÓ—Ì
HÂˆİ\™˜XÙK”“ÕUSÓ—ÎLOˆLˆİ\™˜XÙK”“ÕUSÓ—ÌNOˆNˆİ\™˜XÙK”“ÕUSÓ—ÌÌOˆÌˆ[ÙHOˆŸB‚œš]˜]H[ˆ™^›\Ú[ÙJİ\œ™[ˆØ[Y\˜Q›\Ú[ÙKİ\ÜYˆ›ÛÛX[ŠNˆØ[Y\˜Q›\Ú[ÙHÂˆYˆ
\İ\ÜY
H™]\›ˆØ[Y\˜Q›\Ú[ÙK“Ñ‘‚ˆ™]\›ˆÚ[ˆ
İ\œ™[
HÂˆØ[Y\˜Q›\Ú[ÙK“Ñ‘ˆOˆØ[Y\˜Q›\Ú[ÙKUUÂˆØ[Y\˜Q›\Ú[ÙKUUÈOˆØ[Y\˜Q›\Ú[ÙK“Ó‚ˆØ[Y\˜Q›\Ú[ÙK“ÓˆOˆØ[Y\˜Q›\Ú[ÙK•ÔÒˆØ[Y\˜Q›\Ú[ÙK•ÔÒOˆØ[Y\˜Q›\Ú[ÙK“Ñ‘‚ˆBŸB‚œš]˜]H[ˆ›\ÚX™[
[ÙNˆØ[Y\˜Q›\Ú[ÙJNˆİš[™ÈHÚ[ˆ
[ÙJHÂˆØ[Y\˜Q›\Ú[ÙK“Ñ‘ˆOˆ‘›\ÚÙ™ˆ‚ˆØ[Y\˜Q›\Ú[ÙKUUÈOˆ‘›\Ú]]È‚ˆØ[Y\˜Q›\Ú[ÙK“ÓˆOˆ‘›\ÚÛˆ‚ˆØ[Y\˜Q›\Ú[ÙK•ÔÒOˆ•Ü˜Ú‚ŸB‚œš]˜]H[ˆš[™İ]\Õ^
™\İ[ˆØ[Y\˜Pš[™™\İ[ÊNˆİš[™ÏÈHÚ[ˆ
™\İ[
HÂˆ\ÈØ[Y\˜Pš[™™\İ[‘˜Z[\™HOˆ“[œÈ	Ü™\İ[˜Ø[Y\˜RYNˆ	Ü™\İ[œ™X\ÛÛŸH‚ˆ[ÙHOˆ[ŸB‚œš]˜]H[ˆÚÛÜÙQY˜][›İ]J›İ]\Îˆ\İ˜[XX›PØ[Y\˜T›İ]OŠNˆ˜[XX›PØ[Y\˜T›İ]OÈH›İ]\Ë›Z[SÜ“[È›İ]HO‚ˆ˜[\HH›İ]K˜Ø[Y\˜K™\]Z]˜[[›ØØ[[™İÓ[K›Z[“Ü“[

HÎˆ™]\›Z[SÜ“[›Ø]“PVÕSQBˆ˜[›ÛT[˜[HHYˆ
›İ]K˜Ø[Y\˜K˜Û\ÜÚYšXØ][Û‹œ›ÛHOH[œÔ›ÛK•ÒQJHˆ[ÙHL‚ˆ›ÛT[˜[H
ÈXœÊ\HH™ŠBŸB‚œš]˜]H[ˆ[œÓX™[
›İ]Nˆ˜[XX›PØ[Y\˜T›İ]KXZ[‘\Nˆ›Ø]ÊNˆİš[™ÈÂˆ˜[\HH›İ]K˜Ø[Y\˜K™\]Z]˜[[›ØØ[[™İÓ[K›Z[“Ü“[

BˆYˆ
\HOH[	‰ˆXZ[‘\HOH[	‰ˆXZ[‘\HˆŠHÂˆ™]\›ˆİš[™Ë™›Ü›X]
ØØ[K•TË‰KŒY°åò"ÂWòÖ–äW¢Ğ¢&WGW&âv†Vâ‡&÷WFRæ6ÖW&æ6Æ76–f–6F–öâç&öÆR’°¢ÆVç5&öÆRåTÅE$õt”DRÓâ%Ur"
"ÆVç5&öÆRåDTÄU„õDòÓâ%FVÆR ¢ÆVç5&öÆRäÄôäuõDTÄU„õDòÓâ%FVÆR² ¢ÆVç5&öÆRäe$ôåBÓâ$g&öçB ¢VÇ6RÓâ&÷WFRæ6ÖW&æ–@¢Ğ§Ğ §&—fFRgVâÆVç4ÖævW%F—FÆR‡&÷WFS¢fÇV&ÆT6ÖW&&÷WFR“¢7G&–ærĞ¢"G·&÷WFRæ6ÖW&æ6Æ76–f–6F–öâç&öÆRææÖRç&WÆ6R‚uòrÂrr—Ò+r”BG·&÷WFRæ6ÖW&æ–GÒ  §&—fFRgVâÆVç4ÖævW%7V'F—FÆR‡&÷WFS¢fÇV&ÆT6ÖW&&÷WFR“¢7G&–ær°¢fÂWÒ&÷WFRæ6ÖW&æWV—fÆVçDfö6ÄÆVæwF‡4ÖÒæÖ–ä÷$çVÆÂ‚¢fÂ÷F–6ÂÒWòæÆWB²7G&–æræf÷&ÖB„Æö6ÆRåU2Â"RãbÖÒW"Â—B’Òó¢&÷F–72Væ¶æ÷vâ ¢fÂ&÷WF–ærÒv†Vâ‡&÷WFRæ66W72’°¢6ÖW&&÷WFT66W72äD•$T5Eô4ÔU$ôDUd”4RÓâ&F—&V7B6ÖW&" ¢6ÖW&&÷WFT66W72å…•4”4Åõd”ôÄôt”4ÂÓâ'f–Æöv–6ÂG·&÷WFRæÆöv–6Ä6ÖW&–G2æ¦ö–åFõ7G&–ær‚—Ò ¢Ğ¢&WGW&â"F÷F–6Â+rG&÷WF–ær §Ğ 