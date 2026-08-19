package com.omnicam.camera.camerax

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.RectF
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.media.ImageReader
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import android.util.Size
import android.view.Surface
import androidx.camera.viewfinder.core.TransformationInfo
import androidx.camera.viewfinder.core.ViewfinderSurfaceRequest
import androidx.camera.viewfinder.core.camera2.Camera2TransformationInfo
import com.omnicam.camera.capability.CameraRouteAccess
import com.omnicam.camera.capability.ValuableCameraRoute
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** User-facing C1 presets. They differ only in RAW acquisition policy; no fake super-resolution. */
enum class ComputationalRawPreset(
    val label: String,
    val frameCount: Int,
    val exposureOffsetsEv: List<Float>,
) {
    QUALITY(
        label = "Quality · 6 RAW",
        frameCount = 6,
        exposureOffsetsEv = List(6) { 0f },
    ),
    HDR(
        label = "HDR · 8 RAW",
        frameCount = 8,
        exposureOffsetsEv = listOf(-2f, -1f, -1f, 0f, 0f, 0f, 0f, 0f),
    ),
    MAX(
        label = "MAX · 12 RAW",
        frameCount = 12,
        exposureOffsetsEv = listOf(-2f, -2f, -1f, -1f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f),
    ),
}

data class ComputationalRawViewfinderSpec(
    val surfaceRequest: ViewfinderSurfaceRequest,
    val transformationInfo: TransformationInfo,
    val previewSize: Size,
)

sealed interface ComputationalRawBindResult {
    data class Success(
        val cameraId: String,
        val rawWidth: Int,
        val rawHeight: Int,
        val maximumResolutionMode: Boolean,
    ) : ComputationalRawBindResult

    data class Failure(
        val cameraId: String,
        val reason: String,
    ) : ComputationalRawBindResult
}

sealed interface ComputationalRawCaptureResult {
    data class Success(
        val uri: Uri,
        val width: Int,
        val height: Int,
        val frameCount: Int,
        val acceptedSamples: Long,
        val rejectedSamples: Long,
        val alignments: List<ComputationalRawEngine.Alignment>,
        val preset: ComputationalRawPreset,
        val elapsedMillis: Long,
        val maximumResolutionMode: Boolean,
    ) : ComputationalRawCaptureResult

    data class Failure(val message: String) : ComputationalRawCaptureResult
}

/**
 * Isolated hardware-validation controller for the Phase C1 computational RAW engine.
 *
 * Normal OmniCam JPEG/HEIF capture is intentionally not routed through this class yet. The lab
 * opens a direct RAW-capable Camera2 route, maintains an AE/AF preview, freezes the latest exposure,
 * focus and AWB state for a burst, performs C1 RAW fusion off the UI thread, then writes the merged
 * Bayer buffer as a DNG using the reference capture metadata.
 */
class ComputationalRawController(context: Context) {
    private val appContext = context.applicationContext
    private val cameraManager = appContext.getSystemService(CameraManager::class.java)
    private val cameraThread = HandlerThread("OmniCam-C1-Camera").apply { start() }
    private val imageThread = HandlerThread("OmniCam-C1-RawCopy").apply { start() }
    private val cameraHandler = Handler(cameraThread.looper)
    private val imageHandler = Handler(imageThread.looper)
    private val engine = ComputationalRawEngine(
        cameraHandler = cameraHandler,
        imageHandler = imageHandler,
        scratchDir = java.io.File(appContext.cacheDir, "computational-raw"),
    )

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var rawReader: ImageReader? = null
    private var previewBuilder: CaptureRequest.Builder? = null
    private var activeRoute: ValuableCameraRoute? = null
    private var activeCharacteristics: CameraCharacteristics? = null
    private var activeCandidate: RawCandidate? = null
    @Volatile private var latestPreviewResult: TotalCaptureResult? = null

    fun createViewfinderSpec(
        route: ValuableCameraRoute,
        sessionKey: String = "",
    ): ComputationalRawViewfinderSpec {
        require(route.access == CameraRouteAccess.DIRECT_CAMERA_DEVICE) {
            "C1 currently requires an independently openable RAW camera route"
        }
        val chars = cameraManager.getCameraCharacteristics(route.camera.id)
        val previewSize = choosePreviewSize(chars)
        val crop = RectF(0f, 0f, previewSize.width.toFloat(), previewSize.height.toFloat())
        val transformation = Camera2TransformationInfo.createFromCharacteristics(
            chars,
            crop.left,
            crop.top,
            crop.right,
            crop.bottom,
        )
        val request = ViewfinderSurfaceRequest(
            width = previewSize.width,
            height = previewSize.height,
            requestId = buildString {
                append("c1:")
                append(route.camera.id)
                append(':')
                append(previewSize.width)
                append('x')
                append(previewSize.height)
                if (sessionKey.isNotBlank()) {
                    append(':')
                    append(sessionKey)
                }
            },
        )
        return ComputationalRawViewfinderSpec(request, transformation, previewSize)
    }

    @SuppressLint("MissingPermission")
    suspend fun bind(
        surface: Surface,
        spec: ComputationalRawViewfinderSpec,
        route: ValuableCameraRoute,
    ): ComputationalRawBindResult = withContext(Dispatchers.Main.immediate) {
        closeCurrent()
        if (route.access != CameraRouteAccess.DIRECT_CAMERA_DEVICE || !route.camera.rawSupported) {
            return@withContext ComputationalRawBindResult.Failure(
                route.camera.id,
                "Computational RAW requires a direct Camera2 route with RAW_SENSOR support",
            )
        }

        val chars = runCatching { cameraManager.getCameraCharacteristics(route.camera.id) }
            .getOrElse {
                return@withContext ComputationalRawBindResult.Failure(
                    route.camera.id,
                    it.message ?: it::class.java.simpleName,
                )
            }
        val candidates = rawCandidates(chars)
        if (candidates.isEmpty()) {
            return@withContext ComputationalRawBindResult.Failure(
                route.camera.id,
                "No RAW_SENSOR output size is exposed",
            )
        }

        var lastError: Throwable? = null
        for (candidate in candidates) {
            closeCurrent()
            val attempt = runCatching {
                bindAttempt(surface, route, chars, candidate)
            }
            attempt.onSuccess { return@withContext it }
            lastError = attempt.exceptionOrNull()
        }
        ComputationalRawBindResult.Failure(
            route.camera.id,
            lastError?.message ?: "Camera2 rejected every RAW + preview session candidate",
        )
    }

    @SuppressLint("MissingPermission")
    private suspend fun bindAttempt(
        previewSurface: Surface,
        route: ValuableCameraRoute,
        chars: CameraCharacteristics,
        candidate: RawCandidate,
    ): ComputationalRawBindResult.Success {
        val reader = ImageReader.newInstance(
            candidate.size.width,
            candidate.size.height,
            ImageFormat.RAW_SENSOR,
            RAW_READER_MAX_IMAGES,
        )
        rawReader = reader
        activeRoute = route
        activeCharacteristics = chars
        activeCandidate = candidate
        latestPreviewResult = null

        val device = openCamera(route.camera.id)
        cameraDevice = device
        val session = createSession(device, previewSurface, reader.surface)
        captureSession = session
        val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(previewSurface)
            set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
            set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
            setContinuousAfIfSupported(this, chars)
        }
        previewBuilder = builder
        session.setRepeatingRequest(builder.build(), previewCaptureCallback, cameraHandler)

        return ComputationalRawBindResult.Success(
            cameraId = route.camera.id,
            rawWidth = candidate.size.width,
            rawHeight = candidate.size.height,
            maximumResolutionMode = candidate.maximumResolutionMode,
        )
    }

    suspend fun capture(
        preset: ComputationalRawPreset,
        onProgress: (String) -> Unit = {},
    ): ComputationalRawCaptureResult {
        val route = activeRoute
            ?: return ComputationalRawCaptureResult.Failure("No computational RAW camera is active")
        val device = cameraDevice
            ?: return ComputationalRawCaptureResult.Failure("Camera device is unavailable")
        val session = captureSession
            ?: return ComputationalRawCaptureResult.Failure("Capture session is unavailable")
        val reader = rawReader
            ?: return ComputationalRawCaptureResult.Failure("RAW output is unavailable")
        val chars = activeCharacteristics
            ?: return ComputationalRawCaptureResult.Failure("Camera characteristics are unavailable")
        val candidate = activeCandidate
            ?: return ComputationalRawCaptureResult.Failure("RAW candidate is unavailable")

        val started = android.os.SystemClock.elapsedRealtime()
        return runCatching {
            val reference = latestPreviewResult ?: awaitReferenceResult(session)
            val baseExposure = reference.get(CaptureResult.SENSOR_EXPOSURE_TIME)
                ?: DEFAULT_EXPOSURE_NS
            val baseIso = reference.get(CaptureResult.SENSOR_SENSITIVITY)
                ?: DEFAULT_ISO
            val focus = reference.get(CaptureResult.LENS_FOCUS_DISTANCE)

            onProgress(
                "Capturing ${preset.frameCount} full-resolution RAW frames · " +
                    "ISO $baseIso · ${formatExposure(baseExposure)}",
            )
            withContext(Dispatchers.Default) {
                runCatching { session.stopRepeating() }
                val merged = try {
                    engine.captureAndMerge(
                        device = device,
                        session = session,
                        reader = reader,
                        characteristics = chars,
                        plan = ComputationalRawEngine.BurstPlan(
                            frameCount = preset.frameCount,
                            exposureOffsetsEv = preset.exposureOffsetsEv,
                        ),
                        baseIso = baseIso,
                        baseExposureTimeNs = baseExposure,
                        lockedFocusDistanceDiopters = focus,
                        applyCommonSettings = { builder ->
                            builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                            builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                            builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
                        },
                        applySensorPixelMode = { builder ->
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                builder.set(
                                    CaptureRequest.SENSOR_PIXEL_MODE,
                                    if (candidate.maximumResolutionMode) {
                                        CameraMetadata.SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION
                                    } else {
                                        CameraMetadata.SENSOR_PIXEL_MODE_DEFAULT
                                    },
                                )
                            }
                        },
                    )
                } finally {
                    resumePreview()
                }
                merged
            }.let { merged ->
                onProgress(
                    "RAW fusion complete · writing ${merged.width}×${merged.height} computational DNG",
                )
                val uri = withContext(Dispatchers.IO) { saveMergedDng(merged, preset) }
                ComputationalRawCaptureResult.Success(
                    uri = uri,
                    width = merged.width,
                    height = merged.height,
                    frameCount = merged.frameCount,
                    acceptedSamples = merged.acceptedSamples,
                    rejectedSamples = merged.rejectedSamples,
                    alignments = merged.alignments,
                    preset = preset,
                    elapsedMillis = android.os.SystemClock.elapsedRealtime() - started,
                    maximumResolutionMode = candidate.maximumResolutionMode,
                )
            }
        }.getOrElse { error ->
            runCatching { resumePreview() }
            ComputationalRawCaptureResult.Failure(error.message ?: error::class.java.simpleName)
        }
    }

    fun unbind() = closeCurrent()

    fun shutdown() {
        closeCurrent()
        cameraThread.quitSafely()
        imageThread.quitSafely()
    }

    private val previewCaptureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult,
        ) {
            latestPreviewResult = result
        }
    }

    private suspend fun awaitReferenceResult(
        session: CameraCaptureSession,
    ): TotalCaptureResult = withTimeout(1_500) {
        val builder = previewBuilder ?: error("Preview request is unavailable")
        suspendCancellableCoroutine { continuation ->
            val callback = object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult,
                ) {
                    latestPreviewResult = result
                    if (continuation.isActive) continuation.resume(result)
                }
            }
            runCatching { session.capture(builder.build(), callback, cameraHandler) }
                .onFailure {
                    if (continuation.isActive) continuation.resumeWithException(it)
                }
        }
    }

    private fun resumePreview() {
        val session = captureSession ?: return
        val builder = previewBuilder ?: return
        cameraHandler.post {
            runCatching {
                session.setRepeatingRequest(builder.build(), previewCaptureCallback, cameraHandler)
            }
        }
    }

    private fun saveMergedDng(
        merged: ComputationalRawEngine.MergeResult,
        preset: ComputationalRawPreset,
    ): Uri {
        val resolver = appContext.contentResolver
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "OMNI_CRAW_${preset.name}_${timestamp}.dng")
            put(MediaStore.Images.Media.MIME_TYPE, "image/x-adobe-dng")
            put(MediaStore.Images.Media.WIDTH, merged.width)
            put(MediaStore.Images.Media.HEIGHT, merged.height)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/OmniCam/C-RAW")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("MediaStore could not create the computational DNG")
        try {
            resolver.openOutputStream(uri, "w")?.use { output ->
                android.hardware.camera2.DngCreator(
                    merged.referenceCharacteristics,
                    merged.referenceResult,
                ).use { creator ->
                    creator.setDescription(
                        "OmniCam Phase C1 ${preset.name}: ${merged.frameCount}-frame aligned RAW fusion",
                    )
                    creator.writeByteBuffer(
                        output,
                        Size(merged.width, merged.height),
                        merged.pixels16.duplicate(),
                        0,
                    )
                }
            } ?: error("MediaStore DNG output stream is unavailable")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                resolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
                    null,
                    null,
                )
            }
            return uri
        } catch (error: Throwable) {
            resolver.delete(uri, null, null)
            throw error
        }
    }

    private fun rawCandidates(chars: CameraCharacteristics): List<RawCandidate> {
        val maps = ResolutionPolicy.maps(chars) ?: return emptyList()
        val out = mutableListOf<RawCandidate>()
        maps.maximumResolution?.let { map ->
            allRawSizes(map).sortedByDescending(::pixels).take(2).forEach { size ->
                out += RawCandidate(size, maximumResolutionMode = true)
            }
        }
        allRawSizes(maps.normal).sortedByDescending(::pixels).take(3).forEach { size ->
            out += RawCandidate(size, maximumResolutionMode = false)
        }
        return out.distinctBy { "${it.maximumResolutionMode}:${it.size.width}x${it.size.height}" }
    }

    private fun allRawSizes(map: android.hardware.camera2.params.StreamConfigurationMap): List<Size> {
        val regular = runCatching { map.getOutputSizes(ImageFormat.RAW_SENSOR) }.getOrNull().orEmpty()
        val high = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            runCatching { map.getHighResolutionOutputSizes(ImageFormat.RAW_SENSOR) }.getOrNull().orEmpty()
        } else emptyArray()
        return (regular.asList() + high.asList()).distinctBy { "${it.width}x${it.height}" }
    }

    private fun choosePreviewSize(chars: CameraCharacteristics): Size {
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: return Size(1440, 1080)
        val sizes = runCatching { map.getOutputSizes(ImageFormat.PRIVATE) }.getOrNull().orEmpty()
        val bounded = sizes.filter {
            max(it.width, it.height) <= PREVIEW_MAX_LONG_EDGE && pixels(it) <= PREVIEW_MAX_PIXELS
        }.ifEmpty { sizes.toList() }
        val active = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        val targetAspect = active?.let { it.width().toFloat() / it.height().toFloat() } ?: 4f / 3f
        return bounded.minWithOrNull(
            compareBy<Size> {
                kotlin.math.abs(it.width.toFloat() / it.height.toFloat() - targetAspect)
            }.thenByDescending(::pixels),
        ) ?: Size(1440, 1080)
    }

    private fun setContinuousAfIfSupported(
        builder: CaptureRequest.Builder,
        chars: CameraCharacteristics,
    ) {
        val modes = chars.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES).orEmpty()
        when {
            modes.contains(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE) -> builder.set(
                CaptureRequest.CONTROL_AF_MODE,
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE,
            )
            modes.contains(CaptureRequest.CONTROL_AF_MODE_AUTO) -> builder.set(
                CaptureRequest.CONTROL_AF_MODE,
                CaptureRequest.CONTROL_AF_MODE_AUTO,
            )
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun openCamera(cameraId: String): CameraDevice =
        suspendCancellableCoroutine { continuation ->
            try {
                cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        if (continuation.isActive) continuation.resume(camera) else camera.close()
                    }

                    override fun onDisconnected(camera: CameraDevice) {
                        camera.close()
                        if (continuation.isActive) continuation.resumeWithException(
                            IllegalStateException("Camera $cameraId disconnected"),
                        )
                    }

                    override fun onError(camera: CameraDevice, error: Int) {
                        camera.close()
                        if (continuation.isActive) continuation.resumeWithException(
                            IllegalStateException("Camera $cameraId open error $error"),
                        )
                    }
                }, cameraHandler)
            } catch (error: Throwable) {
                if (continuation.isActive) continuation.resumeWithException(error)
            }
        }

    @Suppress("DEPRECATION")
    private suspend fun createSession(
        device: CameraDevice,
        preview: Surface,
        raw: Surface,
    ): CameraCaptureSession = suspendCancellableCoroutine { continuation ->
        val callback = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                if (continuation.isActive) continuation.resume(session) else session.close()
            }

            override fun onConfigureFailed(session: CameraCaptureSession) {
                session.close()
                if (continuation.isActive) continuation.resumeWithException(
                    IllegalStateException("Camera2 rejected the preview + RAW computational session"),
                )
            }
        }
        runCatching {
            device.createCaptureSession(listOf(preview, raw), callback, cameraHandler)
        }.onFailure {
            if (continuation.isActive) continuation.resumeWithException(it)
        }
    }

    private fun closeCurrent() {
        runCatching { captureSession?.stopRepeating() }
        runCatching { captureSession?.abortCaptures() }
        runCatching { captureSession?.close() }
        runCatching { cameraDevice?.close() }
        runCatching { rawReader?.close() }
        captureSession = null
        cameraDevice = null
        rawReader = null
        previewBuilder = null
        activeRoute = null
        activeCharacteristics = null
        activeCandidate = null
        latestPreviewResult = null
    }

    private fun pixels(size: Size): Long = size.width.toLong() * size.height.toLong()

    private fun formatExposure(ns: Long): String {
        val seconds = ns / 1_000_000_000.0
        return if (seconds >= 1.0) {
            String.format(Locale.US, "%.2fs", seconds)
        } else {
            val denominator = (1.0 / seconds.coerceAtLeast(0.000001)).toInt().coerceAtLeast(1)
            "1/${denominator}s"
        }
    }

    private data class RawCandidate(
        val size: Size,
        val maximumResolutionMode: Boolean,
    )

    private companion object {
        const val RAW_READER_MAX_IMAGES = 4
        const val PREVIEW_MAX_LONG_EDGE = 1920
        const val PREVIEW_MAX_PIXELS = 2_500_000L
        const val DEFAULT_EXPOSURE_NS = 16_666_667L
        const val DEFAULT_ISO = 100
    }
}
