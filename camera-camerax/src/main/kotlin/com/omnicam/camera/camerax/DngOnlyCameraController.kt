package com.omnicam.camera.camerax

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.RectF
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.media.DngCreator
import android.media.ExifInterface
import android.media.ImageReader
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import android.util.Range
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
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

enum class DngCaptureMode(val frameCount: Int) {
    RAW(3),
    HDR(6),
    MAX(10),
}

data class DngCaptureTuning(
    val denoiseStrength: Float = 0.88f,
    val highlightProtection: Float = 0.80f,
)

data class DngCameraState(
    val cameraId: String? = null,
    val minZoomRatio: Float = 1f,
    val maxZoomRatio: Float = 1f,
    val zoomRatio: Float = 1f,
    val previewFps: Int = 30,
)

data class DngViewfinderSpec(
    val surfaceRequest: ViewfinderSurfaceRequest,
    val transformationInfo: TransformationInfo,
    val previewSize: Size,
)

sealed interface DngBindResult {
    data class Success(
        val cameraId: String,
        val rawWidth: Int,
        val rawHeight: Int,
    ) : DngBindResult

    data class Failure(val cameraId: String, val message: String) : DngBindResult
}

sealed interface DngCaptureResult {
    data class Queued(
        val width: Int,
        val height: Int,
        val frameCount: Int,
        val captureMillis: Long,
    ) : DngCaptureResult

    data class Failure(val message: String) : DngCaptureResult
}

enum class DngJobStage {
    QUEUED,
    FUSING,
    RAW_SCALING,
    SAVING,
    SAVED,
    FAILED,
}

data class DngJobStatus(
    val id: Long,
    val mode: DngCaptureMode,
    val stage: DngJobStage,
    val message: String,
    val width: Int,
    val height: Int,
    val frameCount: Int,
    val zoomRatio: Float,
    val dngUri: Uri? = null,
) {
    val terminal: Boolean get() = stage == DngJobStage.SAVED || stage == DngJobStage.FAILED
}

data class DngLastPhoto(
    val uri: Uri,
    val displayName: String,
    val bitmap: Bitmap?,
    val width: Int,
    val height: Int,
    val zoomRatio: Float,
)

/**
 * The only user-facing OmniCam camera engine.
 *
 * Idle preview uses a preview-only processed stream. Full-resolution RAW is attached only while the
 * shutter burst is being acquired, so a slow RAW stream does not force the normal viewfinder into a
 * low-frame-rate/high-exposure operating point. The final and only persisted image is DNG.
 */
class DngOnlyCameraController(context: Context) {
    private val appContext = context.applicationContext
    private val cameraManager = appContext.getSystemService(CameraManager::class.java)
    private val cameraThread = HandlerThread("OmniCam-DNG-Camera").apply { start() }
    private val imageThread = HandlerThread("OmniCam-DNG-RawCopy").apply { start() }
    private val cameraHandler = Handler(cameraThread.looper)
    private val imageHandler = Handler(imageThread.looper)
    private val scratchDir = java.io.File(appContext.cacheDir, "dng-only-raw")
    private val engine = ComputationalRawEngine(cameraHandler, imageHandler, scratchDir)

    private val postDispatcher = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "OmniCam-DNG-Post").apply {
            priority = (Thread.NORM_PRIORITY - 1).coerceAtLeast(Thread.MIN_PRIORITY)
        }
    }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + postDispatcher)
    private val queue = Channel<PostJob>(MAX_PENDING_JOBS)
    private val captureMutex = Mutex()
    private val idGenerator = AtomicLong(System.currentTimeMillis())
    private val jobsLock = Any()
    private val _jobs = MutableStateFlow<List<DngJobStatus>>(emptyList())
    val jobs: StateFlow<List<DngJobStatus>> = _jobs.asStateFlow()
    private val _cameraState = MutableStateFlow(DngCameraState())
    val cameraState: StateFlow<DngCameraState> = _cameraState.asStateFlow()
    private val _lastPhoto = MutableStateFlow<DngLastPhoto?>(null)
    val lastPhoto: StateFlow<DngLastPhoto?> = _lastPhoto.asStateFlow()

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var rawReader: ImageReader? = null
    private var previewBuilder: CaptureRequest.Builder? = null
    private var previewSurface: Surface? = null
    private var activeRoute: ValuableCameraRoute? = null
    private var activeCharacteristics: CameraCharacteristics? = null
    @Volatile private var latestPreviewResult: TotalCaptureResult? = null

    init {
        engine.clearStaleScratch()
        scope.launch {
            for (job in queue) process(job)
        }
    }

    fun createViewfinderSpec(
        route: ValuableCameraRoute,
        sessionKey: String = "",
    ): DngViewfinderSpec {
        require(route.access == CameraRouteAccess.DIRECT_CAMERA_DEVICE)
        val chars = cameraManager.getCameraCharacteristics(route.camera.id)
        val previewSize = choosePreviewSize(chars)
        val crop = RectF(0f, 0f, previewSize.width.toFloat(), previewSize.height.toFloat())
        return DngViewfinderSpec(
            surfaceRequest = ViewfinderSurfaceRequest(
                width = previewSize.width,
                height = previewSize.height,
                requestId = "dng-preview:${route.camera.id}:${previewSize.width}x${previewSize.height}:$sessionKey",
            ),
            transformationInfo = Camera2TransformationInfo.createFromCharacteristics(
                chars,
                crop.left,
                crop.top,
                crop.right,
                crop.bottom,
            ),
            previewSize = previewSize,
        )
    }

    @SuppressLint("MissingPermission")
    suspend fun bind(
        surface: Surface,
        spec: DngViewfinderSpec,
        route: ValuableCameraRoute,
    ): DngBindResult = withContext(Dispatchers.Main.immediate) {
        closeCurrent()
        if (route.access != CameraRouteAccess.DIRECT_CAMERA_DEVICE || !route.camera.rawSupported) {
            return@withContext DngBindResult.Failure(route.camera.id, "Direct RAW_SENSOR camera required")
        }
        val chars = runCatching { cameraManager.getCameraCharacteristics(route.camera.id) }
            .getOrElse { return@withContext DngBindResult.Failure(route.camera.id, it.message ?: "Camera unavailable") }
        val raw = rawCandidates(chars).firstOrNull()
            ?: return@withContext DngBindResult.Failure(route.camera.id, "No RAW_SENSOR output")
        return@withContext runCatching {
            val device = openCamera(route.camera.id)
            cameraDevice = device
            activeRoute = route
            activeCharacteristics = chars
            previewSurface = surface
            val zoom = zoomRange(chars)
            val fps = preferredPreviewFps(chars)
            _cameraState.value = DngCameraState(
                cameraId = route.camera.id,
                minZoomRatio = zoom.first,
                maxZoomRatio = zoom.second,
                zoomRatio = 1f.coerceIn(zoom.first, zoom.second),
                previewFps = fps,
            )
            configurePreviewOnlySession(device, surface, chars)
            DngBindResult.Success(route.camera.id, raw.size.width, raw.size.height)
        }.getOrElse { error ->
            closeCurrent()
            DngBindResult.Failure(route.camera.id, error.message ?: error::class.java.simpleName)
        }
    }

    fun setZoomRatio(requested: Float): Float {
        val chars = activeCharacteristics ?: return 1f
        val state = _cameraState.value
        val clamped = requested.coerceIn(state.minZoomRatio, state.maxZoomRatio)
        if (abs(clamped - state.zoomRatio) < 0.002f) return state.zoomRatio
        _cameraState.value = state.copy(zoomRatio = clamped)
        cameraHandler.post {
            val builder = previewBuilder ?: return@post
            val session = captureSession ?: return@post
            applyZoom(builder, chars, clamped)
            runCatching { session.setRepeatingRequest(builder.build(), previewCaptureCallback, cameraHandler) }
        }
        return clamped
    }

    suspend fun capture(
        mode: DngCaptureMode,
        tuning: DngCaptureTuning,
        onProgress: (String) -> Unit = {},
    ): DngCaptureResult {
        if (!captureMutex.tryLock()) return DngCaptureResult.Failure("RAW capture already running")
        try {
            if (activeJobCount() >= MAX_PENDING_JOBS) return DngCaptureResult.Failure("Processing queue full")
            val route = activeRoute ?: return DngCaptureResult.Failure("Camera not ready")
            val device = cameraDevice ?: return DngCaptureResult.Failure("Camera unavailable")
            val chars = activeCharacteristics ?: return DngCaptureResult.Failure("Camera metadata unavailable")
            val surface = previewSurface ?: return DngCaptureResult.Failure("Preview unavailable")
            val reference = latestPreviewResult ?: return DngCaptureResult.Failure("Waiting for exposure")
            val zoomSnapshot = _cameraState.value.zoomRatio
            val baseExposure = reference.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: DEFAULT_EXPOSURE_NS
            val baseIso = reference.get(CaptureResult.SENSOR_SENSITIVITY) ?: 100
            val focus = reference.get(CaptureResult.LENS_FOCUS_DISTANCE)
            val started = android.os.SystemClock.elapsedRealtime()

            onProgress("Preparing RAW")
            val configured = configureRawCaptureSession(device, surface, chars)
                ?: return DngCaptureResult.Failure("Camera rejected RAW capture session")

            return runCatching {
                onProgress("Capturing ${mode.frameCount} RAW")
                val burst = engine.captureToScratch(
                    device = device,
                    session = configured.session,
                    reader = configured.reader,
                    characteristics = chars,
                    plan = ComputationalRawEngine.BurstPlan(
                        frameCount = mode.frameCount,
                        exposureOffsetsEv = exposureOffsets(mode, tuning.highlightProtection),
                    ),
                    baseIso = baseIso,
                    baseExposureTimeNs = baseExposure,
                    lockedFocusDistanceDiopters = focus,
                    applyCommonSettings = { builder ->
                        builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                        builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                        builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
                        builder.set(CaptureRequest.CONTROL_CAPTURE_INTENT, CaptureRequest.CONTROL_CAPTURE_INTENT_STILL_CAPTURE)
                        setLensShadingMapIfSupported(builder, chars)
                        applyZoom(builder, chars, zoomSnapshot)
                    },
                    applySensorPixelMode = { builder ->
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            builder.set(
                                CaptureRequest.SENSOR_PIXEL_MODE,
                                if (configured.candidate.maximumResolutionMode) CameraMetadata.SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION
                                else CameraMetadata.SENSOR_PIXEL_MODE_DEFAULT,
                            )
                        }
                    },
                )
                val captureMillis = android.os.SystemClock.elapsedRealtime() - started
                val id = idGenerator.incrementAndGet()
                val job = PostJob(
                    id = id,
                    burst = burst,
                    mode = mode,
                    tuning = tuning.copy(),
                    zoomRatio = zoomSnapshot,
                )
                addJob(
                    DngJobStatus(
                        id = id,
                        mode = mode,
                        stage = DngJobStage.QUEUED,
                        message = "Queued",
                        width = burst.width,
                        height = burst.height,
                        frameCount = burst.frameCount,
                        zoomRatio = zoomSnapshot,
                    ),
                )
                if (!queue.trySend(job).isSuccess) {
                    removeJob(id)
                    engine.discardCaptured(burst)
                    error("Processing queue rejected capture")
                }
                onProgress("Shutter ready · DNG processing")
                runCatching { restorePreviewOnly(device, surface, chars) }
                DngCaptureResult.Queued(burst.width, burst.height, burst.frameCount, captureMillis)
            }.getOrElse { error ->
                runCatching { restorePreviewOnly(device, surface, chars) }
                DngCaptureResult.Failure(error.message ?: error::class.java.simpleName)
            }
        } finally {
            captureMutex.unlock()
        }
    }

    private suspend fun process(job: PostJob) {
        updateJob(job.id) { it.copy(stage = DngJobStage.FUSING, message = "Fusing RAW") }
        try {
            val fusion = withContext(Dispatchers.Default) {
                NativeRawFusion.merge(
                    burst = job.burst,
                    tuning = NativeFusionTuning(
                        denoiseStrength = job.tuning.denoiseStrength,
                        highlightProtection = job.tuning.highlightProtection,
                    ),
                )
            }
            val zoomed = if (job.zoomRatio > 1.0005f) {
                updateJob(job.id) { it.copy(stage = DngJobStage.RAW_SCALING, message = "Matching preview zoom") }
                withContext(Dispatchers.Default) { RawZoomProcessor.apply(fusion.merged, job.zoomRatio) }
            } else {
                fusion.merged
            }
            updateJob(job.id) { it.copy(stage = DngJobStage.SAVING, message = "Saving DNG") }
            val preview = withContext(Dispatchers.Default) {
                RawBitmapRenderer.render(
                    merged = zoomed,
                    highlightProtection = job.tuning.highlightProtection,
                    denoiseStrength = job.tuning.denoiseStrength,
                )
            }
            val saved = withContext(Dispatchers.IO) { saveDng(zoomed, job.mode, job.zoomRatio, preview) }
            _lastPhoto.value = DngLastPhoto(
                uri = saved.uri,
                displayName = saved.displayName,
                bitmap = preview,
                width = zoomed.width,
                height = zoomed.height,
                zoomRatio = job.zoomRatio,
            )
            updateJob(job.id) {
                it.copy(
                    stage = DngJobStage.SAVED,
                    message = "Saved DNG",
                    dngUri = saved.uri,
                )
            }
        } catch (error: Throwable) {
            updateJob(job.id) {
                it.copy(stage = DngJobStage.FAILED, message = error.message ?: error::class.java.simpleName)
            }
        } finally {
            engine.discardCaptured(job.burst)
            trimJobHistory()
        }
    }

    fun unbind() = closeCurrent()

    fun shutdown() {
        closeCurrent()
        queue.close()
        scope.cancel()
        postDispatcher.close()
        cameraThread.quitSafely()
        imageThread.quitSafely()
    }

    private suspend fun configurePreviewOnlySession(
        device: CameraDevice,
        surface: Surface,
        chars: CameraCharacteristics,
    ) {
        closeSessionOnly(closeRaw = true)
        val session = createSession(device, listOf(surface), "Preview session rejected")
        captureSession = session
        val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(surface)
            applyPreviewDefaults(this, chars)
        }
        previewBuilder = builder
        session.setRepeatingRequest(builder.build(), previewCaptureCallback, cameraHandler)
    }

    private suspend fun configureRawCaptureSession(
        device: CameraDevice,
        surface: Surface,
        chars: CameraCharacteristics,
    ): RawSession? {
        var lastError: Throwable? = null
        for (candidate in rawCandidates(chars)) {
            closeSessionOnly(closeRaw = true)
            val reader = ImageReader.newInstance(candidate.size.width, candidate.size.height, ImageFormat.RAW_SENSOR, 4)
            rawReader = reader
            val attempt = runCatching {
                val session = createSession(
                    device,
                    listOf(surface, reader.surface),
                    "Preview + RAW session rejected",
                )
                captureSession = session
                val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                    addTarget(surface)
                    applyPreviewDefaults(this, chars)
                }
                previewBuilder = builder
                session.setRepeatingRequest(builder.build(), previewCaptureCallback, cameraHandler)
                RawSession(session, reader, candidate)
            }
            attempt.onSuccess { return it }
            lastError = attempt.exceptionOrNull()
            runCatching { reader.close() }
            rawReader = null
        }
        if (lastError != null) android.util.Log.w("OmniCamDng", "RAW session fallback exhausted", lastError)
        return null
    }

    private suspend fun restorePreviewOnly(
        device: CameraDevice,
        surface: Surface,
        chars: CameraCharacteristics,
    ) {
        configurePreviewOnlySession(device, surface, chars)
    }

    private fun applyPreviewDefaults(builder: CaptureRequest.Builder, chars: CameraCharacteristics) {
        builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
        builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
        builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
        builder.set(CaptureRequest.CONTROL_CAPTURE_INTENT, CaptureRequest.CONTROL_CAPTURE_INTENT_PREVIEW)
        builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, 0)
        val fpsRange = preferredPreviewRange(chars)
        if (fpsRange != null) builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange)
        val antibanding = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_ANTIBANDING_MODES).orEmpty()
        if (antibanding.contains(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_AUTO)) {
            builder.set(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE, CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_AUTO)
        }
        if (chars.get(CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE) == true) {
            builder.set(CaptureRequest.CONTROL_AE_LOCK, false)
        }
        if (chars.get(CameraCharacteristics.CONTROL_AWB_LOCK_AVAILABLE) == true) {
            builder.set(CaptureRequest.CONTROL_AWB_LOCK, false)
        }
        val toneModes = chars.get(CameraCharacteristics.TONEMAP_AVAILABLE_TONE_MAP_MODES).orEmpty()
        when {
            toneModes.contains(CaptureRequest.TONEMAP_MODE_HIGH_QUALITY) -> builder.set(CaptureRequest.TONEMAP_MODE, CaptureRequest.TONEMAP_MODE_HIGH_QUALITY)
            toneModes.contains(CaptureRequest.TONEMAP_MODE_FAST) -> builder.set(CaptureRequest.TONEMAP_MODE, CaptureRequest.TONEMAP_MODE_FAST)
        }
        val nrModes = chars.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES).orEmpty()
        when {
            nrModes.contains(CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY) -> builder.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY)
            nrModes.contains(CaptureRequest.NOISE_REDUCTION_MODE_FAST) -> builder.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_FAST)
        }
        val edgeModes = chars.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES).orEmpty()
        when {
            edgeModes.contains(CaptureRequest.EDGE_MODE_HIGH_QUALITY) -> builder.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_HIGH_QUALITY)
            edgeModes.contains(CaptureRequest.EDGE_MODE_FAST) -> builder.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_FAST)
        }
        setContinuousAfIfSupported(builder, chars)
        applyZoom(builder, chars, _cameraState.value.zoomRatio)
    }

    private fun preferredPreviewRange(chars: CameraCharacteristics): Range<Int>? {
        val ranges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES).orEmpty()
        if (ranges.isEmpty()) return null
        ranges.firstOrNull { it.lower == 30 && it.upper == 30 }?.let { return it }
        ranges.filter { it.upper >= 30 && it.lower >= 24 }.minByOrNull { (it.upper - 30) * 10 + (30 - it.lower).coerceAtLeast(0) }
            ?.let { return it }
        ranges.filter { it.upper >= 30 }.maxByOrNull { it.lower }?.let { return it }
        return ranges.maxByOrNull { it.upper }
    }

    private fun preferredPreviewFps(chars: CameraCharacteristics): Int =
        preferredPreviewRange(chars)?.upper ?: 30

    private fun setLensShadingMapIfSupported(builder: CaptureRequest.Builder, chars: CameraCharacteristics) {
        val modes = chars.get(CameraCharacteristics.STATISTICS_INFO_AVAILABLE_LENS_SHADING_MAP_MODES).orEmpty()
        if (modes.contains(CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE_ON)) {
            builder.set(CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE, CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE_ON)
        }
    }

    private fun applyZoom(builder: CaptureRequest.Builder, chars: CameraCharacteristics, ratio: Float) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            chars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)?.let { range ->
                builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, ratio.coerceIn(range.lower, range.upper))
                return
            }
        }
        val active = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return
        val maxZoom = (chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f).coerceAtLeast(1f)
        val z = ratio.coerceIn(1f, maxZoom)
        val cropWidth = (active.width() / z).roundToInt().coerceAtLeast(2)
        val cropHeight = (active.height() / z).roundToInt().coerceAtLeast(2)
        val left = active.left + (active.width() - cropWidth) / 2
        val top = active.top + (active.height() - cropHeight) / 2
        builder.set(CaptureRequest.SCALER_CROP_REGION, Rect(left, top, left + cropWidth, top + cropHeight))
    }

    private fun zoomRange(chars: CameraCharacteristics): Pair<Float, Float> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            chars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)?.let { range ->
                return max(1f, range.lower) to max(1f, range.upper)
            }
        }
        return 1f to (chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f).coerceAtLeast(1f)
    }

    private fun exposureOffsets(mode: DngCaptureMode, highlight: Float): List<Float> {
        val h = highlight.coerceIn(0f, 1f)
        return when (mode) {
            DngCaptureMode.RAW -> listOf(-0.5f - 0.5f * h, 0f, 0f)
            DngCaptureMode.HDR -> listOf(-2.0f - h, -1.25f, -0.6f, 0f, 0f, 0f)
            DngCaptureMode.MAX -> listOf(-2.5f - h * 0.5f, -2f, -1.4f, -0.8f, -0.35f, 0f, 0f, 0f, 0f, 0f)
        }
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

    private fun saveDng(
        merged: ComputationalRawEngine.MergeResult,
        mode: DngCaptureMode,
        zoomRatio: Float,
        preview: Bitmap?,
    ): SavedDng {
        val resolver = appContext.contentResolver
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        val displayName = "OMNI_DNG_${mode.name}_${timestamp}.dng"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/x-adobe-dng")
            put(MediaStore.Images.Media.WIDTH, merged.width)
            put(MediaStore.Images.Media.HEIGHT, merged.height)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/OmniCam")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("Could not create DNG")
        try {
            resolver.openOutputStream(uri, "w")?.use { output ->
                DngCreator(merged.referenceCharacteristics, merged.referenceResult).use { creator ->
                    creator.setDescription(
                        "OmniCam DNG-only computational RAW ${mode.name}; preview-matched zoom ${String.format(Locale.US, "%.2f", zoomRatio)}x",
                    )
                    creator.setOrientation(dngOrientation(merged.referenceCharacteristics))
                    preview?.let { bitmap ->
                        runCatching { createDngThumbnail(bitmap) }.getOrNull()?.let { thumb ->
                            try {
                                creator.setThumbnail(thumb)
                            } finally {
                                if (thumb !== bitmap) thumb.recycle()
                            }
                        }
                    }
                    creator.writeByteBuffer(output, Size(merged.width, merged.height), merged.pixels16.duplicate(), 0)
                }
            } ?: error("DNG output unavailable")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            }
            return SavedDng(uri, displayName)
        } catch (error: Throwable) {
            resolver.delete(uri, null, null)
            throw error
        }
    }

    private fun createDngThumbnail(bitmap: Bitmap): Bitmap {
        val maxDimension = max(bitmap.width, bitmap.height)
        if (maxDimension <= DngCreator.MAX_THUMBNAIL_DIMENSION) return bitmap
        val scale = DngCreator.MAX_THUMBNAIL_DIMENSION.toFloat() / maxDimension
        return Bitmap.createScaledBitmap(
            bitmap,
            max(1, (bitmap.width * scale).roundToInt()),
            max(1, (bitmap.height * scale).roundToInt()),
            true,
        )
    }

    private fun dngOrientation(chars: CameraCharacteristics): Int = when ((chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0) % 360) {
        90 -> ExifInterface.ORIENTATION_ROTATE_90
        180 -> ExifInterface.ORIENTATION_ROTATE_180
        270 -> ExifInterface.ORIENTATION_ROTATE_270
        else -> ExifInterface.ORIENTATION_NORMAL
    }

    private fun rawCandidates(chars: CameraCharacteristics): List<RawCandidate> {
        val maps = ResolutionPolicy.maps(chars) ?: return emptyList()
        val out = mutableListOf<RawCandidate>()
        maps.maximumResolution?.let { map ->
            allRawSizes(map).sortedByDescending(::pixels).take(2).forEach { out += RawCandidate(it, true) }
        }
        allRawSizes(maps.normal).sortedByDescending(::pixels).take(3).forEach { out += RawCandidate(it, false) }
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
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return Size(1440, 1080)
        val sizes = runCatching { map.getOutputSizes(ImageFormat.PRIVATE) }.getOrNull().orEmpty()
        val bounded = sizes.filter { max(it.width, it.height) <= 1920 && pixels(it) <= 2_500_000L }.ifEmpty { sizes.toList() }
        val active = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        val aspect = active?.let { it.width().toFloat() / it.height().toFloat() } ?: 4f / 3f
        return bounded.minWithOrNull(
            compareBy<Size> { abs(it.width.toFloat() / it.height - aspect) }.thenByDescending(::pixels),
        ) ?: Size(1440, 1080)
    }

    private fun setContinuousAfIfSupported(builder: CaptureRequest.Builder, chars: CameraCharacteristics) {
        val modes = chars.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES).orEmpty()
        when {
            modes.contains(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE) -> builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            modes.contains(CaptureRequest.CONTROL_AF_MODE_AUTO) -> builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun openCamera(cameraId: String): CameraDevice = suspendCancellableCoroutine { continuation ->
        try {
            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    if (continuation.isActive) continuation.resume(camera) else camera.close()
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    if (continuation.isActive) continuation.resumeWithException(IllegalStateException("Camera disconnected"))
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    if (continuation.isActive) continuation.resumeWithException(IllegalStateException("Camera open error $error"))
                }
            }, cameraHandler)
        } catch (error: Throwable) {
            if (continuation.isActive) continuation.resumeWithException(error)
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun createSession(
        device: CameraDevice,
        surfaces: List<Surface>,
        failureMessage: String,
    ): CameraCaptureSession = withTimeout(SESSION_TIMEOUT_MS) {
        suspendCancellableCoroutine { continuation ->
            val callback = object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    if (continuation.isActive) continuation.resume(session) else session.close()
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    session.close()
                    if (continuation.isActive) continuation.resumeWithException(IllegalStateException(failureMessage))
                }
            }
            runCatching { device.createCaptureSession(surfaces, callback, cameraHandler) }
                .onFailure { if (continuation.isActive) continuation.resumeWithException(it) }
        }
    }

    private fun closeSessionOnly(closeRaw: Boolean) {
        runCatching { captureSession?.stopRepeating() }
        runCatching { captureSession?.abortCaptures() }
        runCatching { captureSession?.close() }
        captureSession = null
        previewBuilder = null
        if (closeRaw) {
            runCatching { rawReader?.close() }
            rawReader = null
        }
    }

    private fun closeCurrent() {
        closeSessionOnly(closeRaw = true)
        runCatching { cameraDevice?.close() }
        cameraDevice = null
        previewSurface = null
        activeRoute = null
        activeCharacteristics = null
        latestPreviewResult = null
        _cameraState.value = DngCameraState()
    }

    private fun addJob(status: DngJobStatus) = synchronized(jobsLock) {
        _jobs.value = (listOf(status) + _jobs.value).take(MAX_JOB_HISTORY)
    }

    private fun updateJob(id: Long, transform: (DngJobStatus) -> DngJobStatus) = synchronized(jobsLock) {
        _jobs.value = _jobs.value.map { if (it.id == id) transform(it) else it }
    }

    private fun removeJob(id: Long) = synchronized(jobsLock) {
        _jobs.value = _jobs.value.filterNot { it.id == id }
    }

    private fun activeJobCount(): Int = synchronized(jobsLock) { _jobs.value.count { !it.terminal } }

    private fun trimJobHistory() = synchronized(jobsLock) {
        val active = _jobs.value.filterNot { it.terminal }
        val done = _jobs.value.filter { it.terminal }.take(4)
        _jobs.value = (active + done).sortedByDescending { it.id }.take(MAX_JOB_HISTORY)
    }

    private fun pixels(size: Size): Long = size.width.toLong() * size.height.toLong()

    private data class RawCandidate(val size: Size, val maximumResolutionMode: Boolean)
    private data class RawSession(
        val session: CameraCaptureSession,
        val reader: ImageReader,
        val candidate: RawCandidate,
    )
    private data class PostJob(
        val id: Long,
        val burst: ComputationalRawEngine.CapturedBurst,
        val mode: DngCaptureMode,
        val tuning: DngCaptureTuning,
        val zoomRatio: Float,
    )
    private data class SavedDng(val uri: Uri, val displayName: String)

    private companion object {
        const val MAX_PENDING_JOBS = 2
        const val MAX_JOB_HISTORY = 6
        const val DEFAULT_EXPOSURE_NS = 16_666_667L
        const val SESSION_TIMEOUT_MS = 4_000L
    }
}
