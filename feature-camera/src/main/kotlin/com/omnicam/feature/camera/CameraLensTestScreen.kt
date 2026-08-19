package com.omnicam.feature.camera

import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.omnicam.camera.camerax.Camera2AuxPreviewController
import com.omnicam.camera.camerax.CameraBindResult
import com.omnicam.camera.camerax.CaptureProbeResult
import com.omnicam.camera.capability.CameraCapabilityScanner
import com.omnicam.camera.capability.CameraRouteAccess
import com.omnicam.camera.capability.ValuableCameraResolver
import com.omnicam.camera.capability.ValuableCameraRoute
import com.omnicam.core.model.DeviceCameraProfile
import com.omnicam.core.model.LensFacing
import com.omnicam.core.model.LensRole
import java.util.Locale
import kotlinx.coroutines.launch
import kotlin.math.abs

@Composable
fun CameraLensTestRoute(
    scanner: CameraCapabilityScanner,
    previewController: Camera2AuxPreviewController,
    preferencesStore: LensPreferencesStore,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val textureView = remember(context) { TextureView(context) }
    val preferences by preferencesStore.preferences.collectAsStateWithLifecycle(
        initialValue = LensPreferences(),
    )

    var profile by remember { mutableStateOf<DeviceCameraProfile?>(null) }
    var scanError by remember { mutableStateOf<String?>(null) }
    var selectedCameraId by remember { mutableStateOf<String?>(null) }
    var bindResult by remember { mutableStateOf<CameraBindResult?>(null) }
    var captureResult by remember { mutableStateOf<CaptureProbeResult?>(null) }
    var binding by remember { mutableStateOf(false) }
    var capturing by remember { mutableStateOf(false) }
    var showLensManager by remember { mutableStateOf(false) }

    val resolution = remember(profile) { profile?.let(ValuableCameraResolver::resolve) }
    val directRearRoutes = remember(resolution) {
        resolution?.rearRoutes
            ?.filter { it.access == CameraRouteAccess.DIRECT_CAMERA_DEVICE }
            .orEmpty()
    }
    val orderedRearRoutes = remember(directRearRoutes, preferences) {
        preferences.applyOrder(directRearRoutes, LensFacing.BACK)
    }
    val enabledRearRoutes = remember(orderedRearRoutes, preferences) {
        orderedRearRoutes.filter { preferences.isEnabled(it.camera.id) }
    }

    LaunchedEffect(Unit) {
        runCatching { scanner.scan() }
            .onSuccess { scanned -> profile = scanned }
            .onFailure { scanError = it.message ?: it::class.java.simpleName }
    }

    LaunchedEffect(enabledRearRoutes) {
        val enabledIds = enabledRearRoutes.mapTo(mutableSetOf()) { it.camera.id }
        if (selectedCameraId !in enabledIds) {
            selectedCameraId = chooseDefaultRearRoute(enabledRearRoutes)?.camera?.id
        }
    }

    LaunchedEffect(selectedCameraId) {
        val cameraId = selectedCameraId ?: run {
            previewController.unbind()
            bindResult = null
            return@LaunchedEffect
        }
        binding = true
        captureResult = null
        bindResult = previewController.bind(
            textureView = textureView,
            cameraId = cameraId,
        )
        binding = false
    }

    DisposableEffect(Unit) {
        onDispose { previewController.unbind() }
    }

    Surface(modifier = modifier.fillMaxSize(), color = Color.Black) {
        when {
            scanError != null -> LensTestError(scanError.orEmpty(), onBack)
            profile == null || resolution == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            else -> LensTestContent(
                profile = requireNotNull(profile),
                orderedRearRoutes = orderedRearRoutes,
                enabledRearRoutes = enabledRearRoutes,
                excludedCount = requireNotNull(resolution).excludedRoutes.size,
                textureView = textureView,
                selectedCameraId = selectedCameraId,
                bindResult = bindResult,
                captureResult = captureResult,
                binding = binding,
                capturing = capturing,
                onBack = onBack,
                onManageLenses = { showLensManager = true },
                onSelectCamera = { selectedCameraId = it },
                onCapture = {
                    capturing = true
                    scope.launch {
                        captureResult = previewController.captureProbe()
                        capturing = false
                    }
                },
            )
        }
    }

    if (showLensManager) {
        LensManagerSheet(
            routes = orderedRearRoutes,
            preferences = preferences,
            onDismiss = { showLensManager = false },
            onToggle = { cameraId, enabled ->
                scope.launch { preferencesStore.setEnabled(cameraId, enabled) }
            },
            onMove = { cameraId, delta ->
                val ids = orderedRearRoutes.map { it.camera.id }.toMutableList()
                val from = ids.indexOf(cameraId)
                val to = (from + delta).coerceIn(0, ids.lastIndex)
                if (from >= 0 && from != to) {
                    ids.removeAt(from)
                    ids.add(to, cameraId)
                    scope.launch { preferencesStore.setOrder(LensFacing.BACK, ids) }
                }
            },
            onReset = { scope.launch { preferencesStore.reset() } },
        )
    }
}

@Composable
private fun LensTestContent(
    profile: DeviceCameraProfile,
    orderedRearRoutes: List<ValuableCameraRoute>,
    enabledRearRoutes: List<ValuableCameraRoute>,
    excludedCount: Int,
    textureView: TextureView,
    selectedCameraId: String?,
    bindResult: CameraBindResult?,
    captureResult: CaptureProbeResult?,
    binding: Boolean,
    capturing: Boolean,
    onBack: () -> Unit,
    onManageLenses: () -> Unit,
    onSelectCamera: (String) -> Unit,
    onCapture: () -> Unit,
) {
    val rawRearCount = profile.cameras.count { it.directlyListed && it.lensFacing == LensFacing.BACK }
    val mainEq = chooseDefaultRearRoute(orderedRearRoutes)?.camera?.equivalentFocalLengthsMm?.minOrNull()

    Column(Modifier.fillMaxSize().background(Color.Black)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(430.dp)
                .background(Color.Black),
        ) {
            AndroidView(
                factory = { textureView },
                modifier = Modifier.fillMaxSize(),
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onBack) { Text("‹ Diagnostics", color = Color.White) }
                Text(
                    "USEFUL LENS TEST",
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                )
            }

            Surface(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 60.dp),
                color = Color.Black.copy(alpha = 0.62f),
                shape = RoundedCornerShape(18.dp),
            ) {
                Text(
                    bindStatus(bindResult, binding),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                    color = Color.White,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "${enabledRearRoutes.size} enabled · ${orderedRearRoutes.size} useful rear lenses · $rawRearCount raw rear routes",
                color = Color.White.copy(alpha = 0.76f),
                style = MaterialTheme.typography.bodySmall,
            )
            if (excludedCount > 0) {
                Text(
                    "$excludedCount logical/duplicate/non-photo routes kept in diagnostics only",
                    color = Color.White.copy(alpha = 0.52f),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            Spacer(Modifier.height(8.dp))

            if (enabledRearRoutes.isEmpty()) {
                Text(
                    "All rear lenses are disabled. Open Manage lenses to enable one.",
                    modifier = Modifier.padding(horizontal = 20.dp),
                    color = Color.White,
                )
            } else {
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp),
                ) {
                    items(enabledRearRoutes, key = { it.camera.id }) { route ->
                        LensButton(
                            route = route,
                            label = cameraZoomLabel(route, mainEq),
                            selected = route.camera.id == selectedCameraId,
                            onClick = { onSelectCamera(route.camera.id) },
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onManageLenses) {
                Text("Manage lenses · on/off · reorder")
            }

            Spacer(Modifier.height(8.dp))
            orderedRearRoutes.firstOrNull { it.camera.id == selectedCameraId }?.let { selected ->
                Text(
                    selectedLensDescription(selected),
                    modifier = Modifier.padding(horizontal = 16.dp),
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            Spacer(Modifier.height(12.dp))
            Button(
                enabled = bindResult is CameraBindResult.Success && !capturing,
                onClick = onCapture,
                shape = CircleShape,
            ) {
                Text(if (capturing) "Reading frame…" else "Frame probe")
            }

            Text(
                captureStatus(captureResult),
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                color = Color.White.copy(alpha = 0.72f),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun LensButton(
    route: ValuableCameraRoute,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val camera = route.camera
    val content: @Composable () -> Unit = {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, color = if (selected) MaterialTheme.colorScheme.onPrimary else Color.White)
            Text(
                "ID ${camera.id}",
                color = if (selected) MaterialTheme.colorScheme.onPrimary else Color.White.copy(alpha = 0.65f),
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }

    if (selected) {
        Button(onClick = onClick, shape = CircleShape) { content() }
    } else {
        OutlinedButton(onClick = onClick, shape = CircleShape) { content() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LensManagerSheet(
    routes: List<ValuableCameraRoute>,
    preferences: LensPreferences,
    onDismiss: () -> Unit,
    onToggle: (String, Boolean) -> Unit,
    onMove: (String, Int) -> Unit,
    onReset: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
        ) {
            Text("Rear lens layout", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(
                "Only distinct useful photographic routes are listed here. Raw logical/alias IDs stay in diagnostics.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))

            val enabledCount = routes.count { preferences.isEnabled(it.camera.id) }
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                items(routes, key = { it.camera.id }) { route ->
                    val camera = route.camera
                    val enabled = preferences.isEnabled(camera.id)
                    val index = routes.indexOfFirst { it.camera.id == camera.id }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                lensManagerTitle(route),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                lensManagerSubtitle(route),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(
                            enabled = index > 0,
                            onClick = { onMove(camera.id, -1) },
                        ) { Text("↑") }
                        TextButton(
                            enabled = index in 0 until routes.lastIndex,
                            onClick = { onMove(camera.id, 1) },
                        ) { Text("↓") }
                        Spacer(Modifier.width(4.dp))
                        Switch(
                            checked = enabled,
                            enabled = !(enabled && enabledCount <= 1),
                            onCheckedChange = { onToggle(camera.id, it) },
                        )
                    }
                    HorizontalDivider()
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onReset) { Text("Reset defaults") }
                Button(onClick = onDismiss) { Text("Done") }
            }
        }
    }
}

@Composable
private fun LensTestError(message: String, onBack: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Lens test could not start", color = Color.White, style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(message, color = Color.White.copy(alpha = 0.72f))
        Spacer(Modifier.height(16.dp))
        Button(onClick = onBack) { Text("Back") }
    }
}

private fun bindStatus(result: CameraBindResult?, binding: Boolean): String = when (result) {
    is CameraBindResult.Success -> "Camera2 direct · requested ${result.requestedCameraId} · active ${result.actualCameraId}"
    is CameraBindResult.Failure -> "Camera ${result.cameraId} failed: ${result.reason}"
    null -> if (binding) "Opening exact Camera2 ID…" else "Select a camera"
}

private fun captureStatus(result: CaptureProbeResult?): String = when (result) {
    is CaptureProbeResult.Success ->
        "Frame OK · ID ${result.cameraId} · ${result.width}×${result.height} · format ${result.format}"
    is CaptureProbeResult.Failure -> "Frame probe failed: ${result.reason}"
    null -> "Direct Camera2 preview. Lens visibility/order is now resolved independently from raw HAL route count."
}

private fun chooseDefaultRearRoute(routes: List<ValuableCameraRoute>): ValuableCameraRoute? = routes
    .minByOrNull { route ->
        val camera = route.camera
        val eq = camera.equivalentFocalLengthsMm.minOrNull() ?: return@minByOrNull Float.MAX_VALUE
        val rolePenalty = if (camera.classification.role == LensRole.WIDE) 0f else 100f
        rolePenalty + abs(eq - 26f)
    }

private fun cameraZoomLabel(route: ValuableCameraRoute, mainEq: Float?): String {
    val eq = route.camera.equivalentFocalLengthsMm.minOrNull()
    if (eq != null && mainEq != null && mainEq > 0f) {
        return String.format(Locale.US, "%.1f×", eq / mainEq)
    }
    return route.camera.classification.role.name.replace('_', ' ')
}

private fun selectedLensDescription(route: ValuableCameraRoute): String {
    val camera = route.camera
    val eq = camera.equivalentFocalLengthsMm.minOrNull()
    val focal = camera.focalLengthsMm.minOrNull()
    return buildString {
        append(camera.classification.role.name.replace('_', ' '))
        append(" · Camera ")
        append(camera.id)
        if (eq != null) append(String.format(Locale.US, " · %.1f mm eq", eq))
        if (focal != null) append(String.format(Locale.US, " · %.2f mm native", focal))
        append(
            when (route.access) {
                CameraRouteAccess.DIRECT_CAMERA_DEVICE -> " · direct"
                CameraRouteAccess.PHYSICAL_VIA_LOGICAL -> " · via logical ${route.logicalCameraIds.joinToString()}"
            },
        )
    }
}

private fun lensManagerTitle(route: ValuableCameraRoute): String {
    val role = route.camera.classification.role.name.replace('_', ' ')
    return "$role · ID ${route.camera.id}"
}

private fun lensManagerSubtitle(route: ValuableCameraRoute): String {
    val eq = route.camera.equivalentFocalLengthsMm.minOrNull()
    val eqText = eq?.let { String.format(Locale.US, "%.1f mm eq", it) } ?: "unknown focal"
    val access = when (route.access) {
        CameraRouteAccess.DIRECT_CAMERA_DEVICE -> "direct Camera2 route"
        CameraRouteAccess.PHYSICAL_VIA_LOGICAL -> "physical via logical ${route.logicalCameraIds.joinToString()}"
    }
    return "$eqText · $access"
}
