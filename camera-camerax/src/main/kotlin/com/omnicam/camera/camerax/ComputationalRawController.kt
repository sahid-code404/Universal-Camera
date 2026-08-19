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
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.max
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

/** User-facing computational RAW presets. */
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
    /** Burst is safely file-backed; fusion/save continues without holding the shutter. */
    data class Queued(
        val jobId: Long,
        val width: Int,
        val height: Int,
        val frameCount: Int,
        val preset: ComputationalRawPreset,
        val captureElapsedMillis: Long,
        val maximumResolutionMode: Boolean,
    ) : ComputationalRawCaptureResult

    data class Failure(val message: String) : ComputationalRawCaptureResult
}

enum class ComputationalRawJobStage {
    QUEUED,
    PROCESSING,
    SAVING,
    SAVED,
    FAILED,
}

data class ComputationalRawJobStatus(
    val id: Long,
    val preset: ComputationalRawPreset,
    val width: Int,
    val height: Int,
    val frameCount: Int,
    val maximumResolutionMode: Boolean,
    val captureElapsedMillis: Long,
    val stage: ComputationalRawJobStage,
    val message: String,
    val processingElapsedMillis: Long = 0L,
    val uri: Uri? = null,
    val acceptedSamples: Long = 0L,
    val rejectedSamples: Long = 0L,
    val alignments: List<ComputationalRawEngine.Alignment> = emptyList(),
) {
    val terminal: Boolean get() = stage == ComputationalRawJobStage.SAVED || stage == ComputationalRawJobStage.FAILED
}

/**
 * Realtime C-RAW controller.
 *
 * Capture is split into two independent stages:
 *  1) sensor burst + immediate RAW16 scratch copy while the camera owns the frames;
 *  2) alignment/fusion/DNG save on a process-level serial background queue.
 *
 * Preview is resumed before stage 2 starts and the shutter becomes available again. The queue is
 * intentionally serial so several 12-frame jobs cannot compete for every CPU core and starve the
 * viewfinder. RealtimeRawMerger still uses a few low-priority worker cores inside each job.
 */
class ComputationalRawController(context: Context) {
    private val appContext = context.applicationContext
    private val cameraManager = appContext.getSystemService(CameraManager::class.java)
    private val cameraThread = HandlerThread("OmniCam-C16-Camera").apply { start() }
    private val imageThread = HandlerThread("OmniCam-C16-RawCopy").apply { start() }
    private val cameraHandler = Handler(cameraThread.looper)
    private val imageHandler = Handler(imageThread.looper)
    private val scratchDir = java.io.File(appContext.cacheDir, "computational-raw")
    private val engine = ComputationalRawEngine(
        cameraHandler = cameraHandler,
        imageHandler = imageHandler,
        scratchDir = scratchDir,
    )

    private val coordinatorDispatcher = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "OmniCam-C16-PostQueue").apply {
            priority = (Thread.NORM_PRIORITY - 2).coerceAtLeast(Thread.MIN_PRIORITY)
        }
    }.asCoroutineDispatcher()
    private val processingScope = CoroutineScope(SupervisorJob() + coordinatorDispatcher)
    private val processingQueue = Channel<PostJob>(capacity = MAX_PENDING_JOBS)
    private val captureMutex = Mutex()
    private val jobIdGenerator = AtomicLong(System.currentTimeMillis())
    private val jobsLock = Any()
    private val _jobs = MutableStateFlow<List<ComputationalRawJobStatus>>(emptyList())
    val jobs: StateFlow<List<ComputationalRawJobStatus>> = _jobs.asStateFlow()

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var rawReader: ImageReader? = null
    private var previewBuilder: CaptureRequest.Builder? = null
    private var activeRoute: ValuableCameraRoute? = null
    private var activeCharacteristics: CameraCharacteristics? = null
    private var activeCandidate: RawCandidate? = null
    @Volatile private var latestPreviewResult: TotalCaptureResult? = null

    init {
        engine.clearStaleScratch()
        processingScope.launch {
            for (job in processingQueue) processBackgroundJob(job)
        }
    }

    fun createViewfinderSpec(
        route: ValuableCameraRoute,
        sessionKey: String = "",
    ): ComputationalRawViewfinderSpec {
        require(route.access == CameraRouteAccess.DIRECT_CAMERA_DEVICE) {
            "Computational RAW currently requires an independently openable RAW camera route"
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
                append("c16-bg:")
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
            val attempt = runCatching { bindAttempt(surface, route, chars, candidate) }
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

    /**
     * Returns as soon as the RAW burst has been copied into scratch files and queued. Alignment,
     * fusion and DNG writing continue independently in the background.
     */
    suspend fun capture(
        preset: ComputationalRawPreset,
        onProgress: (String) -> Unit = {},
    ): ComputationalRawCaptureResult {
        if (!captureMutex.tryLock()) {
            return ComputationalRawCaptureResult.Failure("A C-RAW sensor burst is already being captured")
        }
        try {
            if (activeJobCount() >= MAX_PENDING_JOBS) {
                return ComputationalRawCaptureResult.Failure(
                    "Background RAW queue is full. Wait for one job to finish saving.",
                )
            }

            activeRoute ?: return ComputationalRawCaptureResult.Failure("No computational RAW camera is active")
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
                val baseExposure = reference.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: DEFAULT_EXPOSURE_NS
                val baseIso = reference.get(CaptureResult.SENSOR_SENSITIVITY) ?: DEFAULT_ISO
                val focus = reference.get(CaptureResult.LENS_FOCUS_DISTANCE)

                onProgress(
                    "Capturing ${preset.frameCount} full-resolution RAW frames · ISO $baseIso · ${formatExposure(baseExposure)}",
                )

                // Only the sensor acquisition stage pauses the repeating request. Fusion never owns
                // the Camera2 session and therefore cannot freeze the preview.
                runCatching { session.stopRepeating() }
                val burst = try {
                    engine.captureToScratch(
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

                val captureMillis = android.os.SystemClock.elapsedRealtime() - started
                val jobId = jobIdGenerator.incrementAndGet()
                val postJob = PostJob(
                    id = jobId,
                    burst = burst,
                    preset = preset,
                    maximumResolutionMode = candidate.maximumResolutionMode,
                    captureElapsedMillis = captureMillis,
                )
                addJob(
                    ComputationalRawJobStatus(
                        id = jobId,
                        preset = preset,
                        width = burst.width,
                        height = burst.height,
                        frameCount = burst.frameCount,
                        maximumResolutionMode = candidate.maximumResolutionMode,
                        captureElapsedMillis = captureMillis,
                        stage = ComputationalRawJobStage.QUEUED,
                        message = "Queued for background RAW fusion",
                    ),
                )
                if (!processingQueue.trySend(postJob).isSuccess) {
                    removeJob(jobId)
                    engine.discardCaptured(burst)
                    error("Background RAW queue could not accept the capture")
                }

                onProgress(
                    "${preset.frameCount} RAW captured in ${String.format(Locale.US, "%.1fs", captureMillis / 1000.0)} · processing in background · shutter ready",
                )
                ComputationalRawCaptureResult.Queued(
                    jobId = jobId,
                    width = burst.width,
                    height = burst.height,
                    frameCount = burst.frameCount,
                    preset = preset,
                    captureElapsedMillis = captureMillis,
                    maximumResolutionMode = candidate.maximumResolutionMode,
                )
            }.getOrElse { error ->
                runCatching { resumePreview() }
                ComputationalRawCaptureResult.Failure(error.message ?: error::class.java.simpleName)
            }
        } finally {
            captureMutex.unlock()
        }
    }

    private suspend fun processBackgroundJob(job: PostJob) {
        val started = android.os.SystemClock.elapsedRealtime()
        updateJob(job.id) {
            it.copy(
                stage = ComputationalRawJobStage.PROCESSING,
                message = "Background: fast Bayer alignment + temporal denoise",
            )
        }
        try {
            val merged = engine.mergeCaptured(job.burst)
            updateJob(job.id) {
                it.copy(
                    stage = ComputationalRawJobStage.SAVING,
                    message = "Background: writing merged DNG",
                    acceptedSamples = merged.acceptedSamples,
                    rejectedSamples = merged.rejectedSamples,
                    alignments = merged.alignments,
                )
            }
            val uri = withContext(Dispatchers.IO) { saveMergedDng(merged, job.preset) }
            val elapsed = android.os.SystemClock.elapsedRealtime() - started
            updateJob(job.id) {
                it.copy(
                    stage = ComputationalRawJobStage.SAVED,
                    message = "Saved merged computational DNG",
                    processingElapsedMillis = elapsed,
                    uri = uri,
                    acceptedSamples = merged.acceptedSamples,
                    rejectedSamples = merged.rejectedSamples,
                    alignments = merged.alignments,
                )
            }
        } catch (error: Throwable) {
            val elapsed = android.os.SystemClock.elapsedRealtime() - started
            updateJob(job.id) {
                it.copy(
                    stage = ComputationalRawJobStage.FAILED,
                    message = error.message ?: error::class.java.simpleName,
                    processingElapsedMillis = elapsed,
                )
            }
        } finally {
            engine.discardCaptured(job.burst)
            trimJobHistory()
        }
    }

    fun unbind() = closeCurrent()

    fun shutdown() {
        closeCurrent()
        processingQueue.close()
        processingScope.cancel()
        coordinatorDispatcher.close()
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
            runCatching { session.setRepeatingRequest(builder.build(), previewCaptureCallback, cameraHandler) }
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
                        "OmniCam C-RAW ${preset.name}: ${merged.frameCount}-frame realtime background RAW fusion",
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

    private fun addJob(status: ComputationalRawJobStatus) {
        synchronized(jobsLock) {
            _jobs.value = (listOf(status) + _jobs.value).take(MAX_JOB_HISTORY)
        }
    }

    private fun updateJob(id: Long, transform: (ComputationalRawJobStatus) -> ComputationalRawJobStatus) {
        synchronized(jobsLock) {
            _jobs.value = _jobs.value.map { if (it.id == id) transform(it) else it }
        }
    }

    private fun removeJob(id: Long) {
        synchronized(jobsLock) {
            _jobs.value = _jobs.value.filterNot { it.id == id }
        }
    }

    private fun activeJobCount(): Int = synchronized(jobsLock) {
        _jobs.value.count { !it.terminal }
    }

    private fun trimJobHistory() {
        synchronized(jobsLock) {
            val active = _jobs.value.filterNot { it.terminal }
            val terminal = _jobs.value.filter { it.terminal }.take(MAX_COMPLETED_HISTORY)
            _jobs.value = (active + terminal).sortedByDescending { it.id }.take(MAX_JOB_HISTORY)
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
        runCatching { device.createCaptureSession(listOf(preview, raw), callback, cameraHandler) }
            .onFailure {
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

    private data class PostJob(
        val id: Long,
        val burst: ComputationalRawEngine.CapturedBurst,
        val preset: ComputationalRawPreset,
        val maximumResolutionMode: Boolean,
        val captureElapsedMillis: Long,
    )

    private companion object {
        const val RAW_READER_MAX_IMAGES = 4
        const val PREVIEW_MAX_LONG_EDGE = 1920
        const val PREVIEW_MAX_PIXELS = 2_500_000L
        const val DEFAULT_EXPOSURE_NS = 16_666_667L
        const val DEFAULT_ISO = 100
        const val MAX_PENDING_JOBS = 3
        const val MAX_COMPLETED_HISTORY = 4
        const val MAX_JOB_HISTORY = MAX_PENDING_JOBS + MAX_COMPLETED_HISTORY
    }
}
