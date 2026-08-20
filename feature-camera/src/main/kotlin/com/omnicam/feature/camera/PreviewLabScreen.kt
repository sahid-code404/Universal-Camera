package com.omnicam.feature.camera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.TextureView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface as ComposeSurface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.omnicam.camera.camerax.PreviewLabController
import com.omnicam.camera.camerax.PreviewLabMode
import com.omnicam.camera.camerax.PreviewLabSpec
import com.omnicam.camera.capability.CameraCapabilityScanner
import com.omnicam.camera.capability.CameraRouteAccess
import com.omnicam.camera.capability.ValuableCameraResolver
import com.omnicam.camera.capability.ValuableCameraRoute
import com.omnicam.core.model.LensFacing
import java.util.Locale
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs
import kotlinx.coroutines.suspendCancellableCoroutine

private enum class PreviewLabChoice(val label: String, val description: String) {
    SURFACE("SURFACE", "Camera2 SurfaceView / PRIVATE · direct compositor path"),
    TEXTURE("TEXTURE", "Camera2 TextureView / PRIVATE · GPU-composited path"),
    YUV("YUV", "Camera2 YUV_420_888 ImageReader · CPU conversion path"),
    JPEG("JPEG", "Camera2 JPEG ImageReader · diagnostic repeating-JPEG path"),
    CAMERAX_SURFACE("CX SURF", "CameraX PreviewView PERFORMANCE · normally SurfaceView"),
    CAMERAX_TEXTURE("CX TEX", "CameraX PreviewView COMPATIBLE · normally TextureView"),
}

@Composable
fun CameraLabHost(
    scanner: CameraCapabilityScanner,
    controller: com.omnicam.camera.camerax.LightningRawController,
    modifier: Modifier = Modifier,
) {
    var labVisible by remember { mutableStateOf(true) }
    if (labVisible) {
        PreviewLabRoute(scanner = scanner, onOpenFullCamera = { labVisible = false }, modifier = modifier)
    } else {
        Box(modifier.fillMaxSize()) {
            LightningCameraRoute(scanner = scanner, controller = controller, modifier = Modifier.fillMaxSize())
            ComposeSurface(
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp).clickable { labVisible = true },
                shape = CircleShape,
                color = Color.Black.copy(alpha = 0.62f),
            ) {
                Text(
                    "PREVIEW LAB",
                    color = Color.White,
                    fontSize = 10.sp,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                )
            }
        }
    }
}

@Composable
fun PreviewLabRoute(
    scanner: CameraCapabilityScanner,
    onOpenFullCamera: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val controller = remember(context) { PreviewLabController(context) }
    val state by controller.state.collectAsState()
    val frame by controller.frame.collectAsState()
    var permissionGranted by remember { mutableStateOf(hasCameraPermission(context)) }
    var routes by remember { mutableStateOf<List<ValuableCameraRoute>>(emptyList()) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var choice by remember { mutableStateOf(PreviewLabChoice.TEXTURE) }
    var scanError by remember { mutableStateOf<String?>(null) }
    var bindError by remember { mutableStateOf<String?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        permissionGranted = it || hasCameraPermission(context)
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
                            it.camera.lensFacing in setOf(LensFacing.BACK, LensFacing.FRONT)
                    }
                    .sortedWith(
                        compareBy<ValuableCameraRoute> {
                            if (it.camera.lensFacing == LensFacing.BACK) 0 else 1
                        }.thenBy { it.camera.equivalentFocalLengthsMm.minOrNull() ?: 999f },
                    )
                if (selectedId !in routes.map { it.camera.id }) {
                    selectedId = routes.firstOrNull { it.camera.lensFacing == LensFacing.BACK }?.camera?.id
                        ?: routes.firstOrNull()?.camera?.id
                }
            }
            .onFailure { scanError = it.message ?: "Camera scan failed" }
    }

    DisposableEffect(Unit) {
        onDispose { controller.shutdown() }
    }

    val selected = routes.firstOrNull { it.camera.id == selectedId }
    val displayRotation = when (view.display?.rotation ?: android.view.Surface.ROTATION_0) {
        android.view.Surface.ROTATION_90 -> 90
        android.view.Surface.ROTATION_180 -> 180
        android.view.Surface.ROTATION_270 -> 270
        else -> 0
    }
    val camera2Mode = when (choice) {
        PreviewLabChoice.SURFACE -> PreviewLabMode.SURFACE_PRIVATE
        PreviewLabChoice.TEXTURE -> PreviewLabMode.TEXTURE_PRIVATE
        PreviewLabChoice.YUV -> PreviewLabMode.YUV_READER
        PreviewLabChoice.JPEG -> PreviewLabMode.JPEG_READER
        else -> null
    }
    val spec = remember(selectedId, camera2Mode, displayRotation) {
        if (selectedId == null || camera2Mode == null) {
            null
        } else {
            runCatching {
                controller.createSpec(selectedId!!, camera2Mode, 4f / 3f, displayRotation)
            }.onFailure {
                bindError = it.message ?: "Could not create preview spec"
            }.getOrNull()
        }
    }

    LaunchedEffect(choice, selectedId) {
        bindError = null
        controller.unbind()
    }

    Column(modifier.fillMaxSize().background(Color.Black)) {
        Row(
            Modifier.fillMaxWidth().padding(top = 12.dp, start = 12.dp, end = 12.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text("PREVIEW LAB", color = Color.White, fontSize = 16.sp)
                Text("Compare on your actual camera HAL", color = Color.White.copy(alpha = 0.55f), fontSize = 10.sp)
            }
            ComposeSurface(
                modifier = Modifier.clickable(onClick = onOpenFullCamera),
                shape = CircleShape,
                color = Color.White.copy(alpha = 0.15f),
            ) {
                Text(
                    "FULL CAMERA",
                    color = Color.White,
                    fontSize = 10.sp,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }

        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            when {
                !permissionGranted -> LabMessage("Camera permission required")
                scanError != null -> LabMessage(scanError.orEmpty())
                selected == null -> LabMessage("No direct camera found")
                choice == PreviewLabChoice.CAMERAX_SURFACE ->
                    CameraXPreviewPane(
                        lensFacing = selected.camera.lensFacing,
                        implementation = PreviewView.ImplementationMode.PERFORMANCE,
                        onError = { bindError = it },
                    )
                choice == PreviewLabChoice.CAMERAX_TEXTURE ->
                    CameraXPreviewPane(
                        lensFacing = selected.camera.lensFacing,
                        implementation = PreviewView.ImplementationMode.COMPATIBLE,
                        onError = { bindError = it },
                    )
                spec != null && camera2Mode == PreviewLabMode.TEXTURE_PRIVATE ->
                    TexturePrivatePane(controller, spec, onError = { bindError = it })
                spec != null && camera2Mode == PreviewLabMode.SURFACE_PRIVATE ->
                    SurfacePrivatePane(controller, spec, onError = { bindError = it })
                spec != null && camera2Mode != null ->
                    ReaderPreviewPane(controller, spec, frame?.bitmap, onError = { bindError = it })
                else -> LabMessage("Preparing preview…")
            }

            Column(
                Modifier
                    .align(Alignment.TopStart)
                    .padding(10.dp)
                    .background(Color.Black.copy(alpha = 0.56f), RoundedCornerShape(12.dp))
                    .padding(10.dp),
            ) {
                val sizeText = state.size?.let { "${it.width}×${it.height}" }
                    ?: spec?.size?.let { "${it.width}×${it.height}" }
                    ?: "auto"
                Text(choice.label, color = Color.White, fontSize = 12.sp)
                Text(
                    "$sizeText  ${String.format(Locale.US, "%.1f", state.fps)} fps",
                    color = Color.White.copy(alpha = 0.72f),
                    fontSize = 10.sp,
                )
                Text(
                    bindError ?: state.status,
                    color = if (bindError == null) Color.White.copy(alpha = 0.62f) else Color(0xFFFF6961),
                    fontSize = 9.sp,
                )
            }
        }

        Text(
            choice.description,
            color = Color.White.copy(alpha = 0.72f),
            fontSize = 10.sp,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 5.dp),
        )
        if (choice == PreviewLabChoice.JPEG) {
            Text(
                "If JPEG stays black, your HAL does not provide repeating JPEG as a live preview. That result is expected on many phones.",
                color = Color(0xFFFFD60A),
                fontSize = 9.sp,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 3.dp),
            )
        }
        if (choice == PreviewLabChoice.CAMERAX_SURFACE || choice == PreviewLabChoice.CAMERAX_TEXTURE) {
            Text(
                "CameraX selects the logical camera for this facing; auxiliary physical-lens selection can differ from Camera2.",
                color = Color.White.copy(alpha = 0.48f),
                fontSize = 9.sp,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 3.dp),
            )
        }

        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 7.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            PreviewLabChoice.entries.forEach { option ->
                LabPill(option.label, option == choice) { choice = option }
            }
        }

        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 7.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            routes.forEach { route ->
                val focal = route.camera.equivalentFocalLengthsMm.minOrNull()
                    ?: route.camera.focalLengthsMm.minOrNull()
                    ?: 0f
                val label = when (route.camera.lensFacing) {
                    LensFacing.FRONT -> "FRONT ${focal.roundOne()}mm"
                    else -> "${focal.roundOne()}mm"
                }
                LabPill(label, route.camera.id == selectedId) { selectedId = route.camera.id }
            }
        }
        Spacer(Modifier.height(10.dp))
    }
}

@Composable
private fun TexturePrivatePane(
    controller: PreviewLabController,
    spec: PreviewLabSpec,
    onError: (String) -> Unit,
) {
    var previewSurface by remember(spec) { mutableStateOf<Surface?>(null) }
    LaunchedEffect(spec, previewSurface) {
        val surface = previewSurface ?: return@LaunchedEffect
        runCatching { controller.bindSurface(spec, surface) }
            .onFailure { onError(it.message ?: "Texture preview failed") }
    }
    Box(Modifier.fillMaxWidth().aspectRatio(0.75f).background(Color.Black), contentAlignment = Alignment.Center) {
        AndroidView(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(
                    rotationZ = spec.rotationDegrees.toFloat(),
                    scaleX = if (spec.mirrorX) -1f else 1f,
                ),
            factory = { context ->
                TextureView(context).apply {
                    surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                            texture.setDefaultBufferSize(spec.size.width, spec.size.height)
                            previewSurface = Surface(texture)
                        }

                        override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) = Unit
                        override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit

                        override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                            controller.unbind()
                            previewSurface?.release()
                            previewSurface = null
                            return true
                        }
                    }
                }
            },
        )
    }
}

@Composable
private fun SurfacePrivatePane(
    controller: PreviewLabController,
    spec: PreviewLabSpec,
    onError: (String) -> Unit,
) {
    var previewSurface by remember(spec) { mutableStateOf<Surface?>(null) }
    LaunchedEffect(spec, previewSurface) {
        val surface = previewSurface ?: return@LaunchedEffect
        runCatching { controller.bindSurface(spec, surface) }
            .onFailure { onError(it.message ?: "Surface preview failed") }
    }
    Box(Modifier.fillMaxWidth().aspectRatio(0.75f).background(Color.Black), contentAlignment = Alignment.Center) {
        AndroidView(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(
                    rotationZ = spec.rotationDegrees.toFloat(),
                    scaleX = if (spec.mirrorX) -1f else 1f,
                ),
            factory = { context ->
                SurfaceView(context).apply {
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) {
                            holder.setFixedSize(spec.size.width, spec.size.height)
                            previewSurface = holder.surface
                        }

                        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                            previewSurface = holder.surface
                        }

                        override fun surfaceDestroyed(holder: SurfaceHolder) {
                            controller.unbind()
                            previewSurface = null
                        }
                    })
                }
            },
        )
    }
}

@Composable
private fun ReaderPreviewPane(
    controller: PreviewLabController,
    spec: PreviewLabSpec,
    bitmap: android.graphics.Bitmap?,
    onError: (String) -> Unit,
) {
    LaunchedEffect(spec) {
        runCatching { controller.bindReader(spec) }
            .onFailure { onError(it.message ?: "Reader preview failed") }
    }
    Box(Modifier.fillMaxWidth().aspectRatio(0.75f).background(Color.Black), contentAlignment = Alignment.Center) {
        if (bitmap != null && !bitmap.isRecycled) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "Preview lab frame",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            CircularProgressIndicator(color = Color.White)
        }
    }
}

@Composable
private fun CameraXPreviewPane(
    lensFacing: LensFacing,
    implementation: PreviewView.ImplementationMode,
    onError: (String) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var previewView by remember(implementation) { mutableStateOf<PreviewView?>(null) }
    val provider by produceState<ProcessCameraProvider?>(null, context) {
        value = runCatching { context.awaitCameraProvider() }
            .onFailure { onError(it.message ?: "CameraX provider failed") }
            .getOrNull()
    }

    DisposableEffect(provider, previewView, lifecycleOwner, lensFacing, implementation) {
        val cameraProvider = provider
        val view = previewView
        if (cameraProvider != null && view != null) {
            runCatching {
                cameraProvider.unbindAll()
                val selector = CameraSelector.Builder()
                    .requireLensFacing(
                        if (lensFacing == LensFacing.FRONT) CameraSelector.LENS_FACING_FRONT
                        else CameraSelector.LENS_FACING_BACK,
                    )
                    .build()
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(view.surfaceProvider)
                }
                cameraProvider.bindToLifecycle(lifecycleOwner, selector, preview)
            }.onFailure { onError(it.message ?: "CameraX bind failed") }
        }
        onDispose { runCatching { cameraProvider?.unbindAll() } }
    }

    Box(Modifier.fillMaxWidth().aspectRatio(0.75f).background(Color.Black), contentAlignment = Alignment.Center) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                PreviewView(ctx).apply {
                    implementationMode = implementation
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                    previewView = this
                }
            },
            update = { preview ->
                preview.implementationMode = implementation
                previewView = preview
            },
        )
    }
}

private suspend fun Context.awaitCameraProvider(): ProcessCameraProvider =
    suspendCancellableCoroutine { continuation ->
        val future = ProcessCameraProvider.getInstance(this)
        val executor: Executor = ContextCompat.getMainExecutor(this)
        future.addListener({
            runCatching { future.get() }
                .onSuccess { if (continuation.isActive) continuation.resume(it) }
                .onFailure { if (continuation.isActive) continuation.resumeWithException(it) }
        }, executor)
        continuation.invokeOnCancellation { future.cancel(true) }
    }

@Composable
private fun LabPill(label: String, selected: Boolean, onClick: () -> Unit) {
    ComposeSurface(
        modifier = Modifier.clickable(onClick = onClick),
        shape = CircleShape,
        color = if (selected) Color.White else Color.White.copy(alpha = 0.12f),
    ) {
        Text(
            label,
            color = if (selected) Color.Black else Color.White,
            fontSize = 10.sp,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun LabMessage(text: String) {
    Box(Modifier.fillMaxWidth().aspectRatio(0.75f).background(Color.Black), contentAlignment = Alignment.Center) {
        Text(text, color = Color.White, fontSize = 12.sp)
    }
}

private fun hasCameraPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

private fun Float.roundOne(): String = if (abs(this - kotlin.math.round(this)) < 0.05f) {
    kotlin.math.round(this).toInt().toString()
} else {
    String.format(Locale.US, "%.1f", this)
}
