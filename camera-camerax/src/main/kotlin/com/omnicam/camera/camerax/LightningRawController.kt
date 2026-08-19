package com.omnicam.camera.camerax

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
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
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

data class LightningRawTuning(
    val denoiseStrength: Float = 0.88f,
    val highlightProtection: Float = 0.80f,
    val enhancedUpscale: Boolean = true,
)

data class LightningCameraState(
    val cameraId: String? = null,
    val minZoomRatio: Float = 1f,
    val maxZoomRatio: Float = 1f,
    val zoomRatio: Float = 1f,
)

sealed interface LightningCaptureResult {
    data class Queued(
        val width: Int,
        val height: Int,
        val frameCount: Int,
        val captureMillis: Long,
    ) : LightningCaptureResult

    data class Failure(val message: String) : LightningCaptureResult
}

enum class LightningJobStage {
    QUEUED,
    FUSING,
    SAVING,
    SAVED,
    FAILED,
}

data class LightningRawJobStatus(
    val id: Long,
    val preset: ComputationalRawPreset,
    val stage: LightningJobStage,
    val message: String,
    val width: Int,
    val height: Int,
    val frameCount: Int,
    val nativeFusion: Boolean = false,
    val dngUri: Uri? = null,
    val enhancedUri: Uri? = null,
    val enhancedWidth: Int = 0,
    val enhancedHeight: Int = 0,
) {
    val terminal: Boolean get() = stage == LightningJobStage.SAVED || stage == LightningJobStage.FAILED
}

/**
 * C1.7 user-facing camera engine.
 *
 * Preview is never explicitly stopped for a RAW burst. Camera2 gives the one-shot burst priority
 * over the repeating preview and then continues the repeating request, avoiding the old stop/restart
 * exposure flash. Full-resolution fusion and enhanced rendering run after capture in native code.
 */
class LightningRawController(context: Context) {
    private val appContext = context.applicationContext
    private val cameraManager = appContext.getSystemService(CameraManager::class.java)
    private val cameraThread = HandlerThread("OmniCam-C17-Camera").apply { start() }
    private val imageThread = HandlerThread("OmniCam-C17-RawCopy").apply { start() }
    private val cameraHandler = Handler(cameraThread.looper)
    private val imageHandler = Handler(imageThread.looper)
    private val scratchDir = java.io.File(appContext.cacheDir, "computational-raw")
    private val engine = ComputationalRawEngine(cameraHandler, imageHandler, scratchDir)
    private val enhancedProcessor = FastRawPhotoProcessor(appContext)

    private val coordinatorDispatcher = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "OmniCam-C17-PostQueue").apply {
            priority = (Thread.NORM_PRIORITY - 2).coerceAtLeast(Thread.MIN_PRIORITY)
        }
    }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + coordinatorDispatcher)
    private val queue = Channel<PostJob>(MAX_PENDING_JOBS)
    private val captureMutex = Mutex()
    private val idGenerator = AtomicLong(System.currentTimeMillis())
    private val jobsLock = Any()
    private val _jobs = MutableStateFlow<List<LightningRawJobStatus>>(emptyList())
    val jobs: StateFlow<List<LightningRawJobStatus>> = _jobs.asStateFlow()
    private val _cameraState = MutableStateFlow(LightningCameraState())
    val cameraState: StateFlow<LightningCameraState> = _cameraState.asStateFlow()

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
        scope.launch {
            for (job in queue) process(job)
        }
    }

    fun createViewfinderSpec(
        route: ValuableCameraRoute,
        sessionKey: String = "",
    ): ComputationalRawViewfinderSpec {
        require(route.access == CameraRouteAccess.DIRECT_CAMERA_DEVICE)
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
        return ComputationalRawViewfinderSpec(
            surfaceRequest = ViewfinderSurfaceRequest(
                width = previewSize.width,
                height = previewSize.height,
                requestId = "c17-native:${route.camera.id}:${previewSize.width}x${previewSize.height}:$sessionKey",
            ),
            transformationInfo = transformation,
            previewSize = previewSize,
        )
    }

    @SuppressLint("MissingPermission")
    suspend fun bind(
        surface: Surface,
        spec: ComputationalRawViewfinderSpec,
        route: ValuableCameraRoute,
    ): ComputationalRawBindResult = withContext(Dispatchers.Main.immediate) {
        closeCurrent()
        if (route.access != CameraRouteAccess.DIRECT_CAMERA_DEVICE || !route.camera.rawSupported) {
            return@withContext ComputationalRawBindResult.Failure(route.camera.id, "RAW_SENSOR direct route required")
        }
        val chars = runCatching { cameraManager.getCameraCharacteristics(route.camera.id) }
            .getOrElse { return@withContext ComputationalRawBindResult.Failure(route.camera.id, it.message ?: "Camera unavailable") }
        val candidates = rawCandidates(chars)
        if (candidates.isEmpty()) return@withContext ComputationalRawBindResult.Failure(route.camera.id, "No RAW_SENSOR size")

        var last: Throwable? = null
        for (candidate in candidates) {
            closeCurrent()
            val attempt = runCatching { bindAttempt(surface, route, chars, candidate) }
            attempt.onSuccess { return@withContext it }
            last = attempt.exceptionOrNull()
        }
        ComputationalRawBindResult.Failure(route.camera.id, last?.message ?: "RAW + preview session rejected")
    }

    @SuppressLint("MissingPermission")
    private suspend fun bindAttempt(
        previewSurface: Surface,
        route: ValuableCameraRoute,
        chars: CameraCharacteristics,
        candidate: RawCandidate,
    ): ComputationalRawBindResult.Success {
        val reader = ImageReader.newInstance(candidate.size.width, candidate.size.height, ImageFormat.RAW_SENSOR, 4)
        rawReader = reader
        activeRoute = route
        activeCharacteristics = chars
        activeCandidate = candidate
        latestPreviewResult = null

        val device = openCamera(route.camera.id)
        cameraDevice = device
        val session = createSession(device, previewSurface, reader.surface)
        captureSession = session
        val zoom = zoomRange(chars)
        _cameraState.value = LightningCameraState(route.camera.id, zoom.first, zoom.second, 1f.coerceIn(zoom.first, zoom.second))

        val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(previewSurface)
            applyPreviewDefaults(this, chars)
        }
        previewBuilder = builder
        session.setRepeatingRequest(builder.build(), previewCaptureCallback, cameraHandler)
        return ComputationalRawBindResult.Success(route.camera.id, candidate.size.width, candidate.size.height, candidate.maximumResolutionMode)
    }

    fun setZoomRatio(requested: Float): Float {
        val chars = activeCharacteristics ?: return 1f
        val state = _cameraState.value
        val clamped = requested.coerceIn(state.minZoomRatio, state.maxZoomRatio)
        if (kotlin.math.abs(clamped - state.zoomRatio) < 0.002f) return state.zoomRatio
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
        preset: ComputationalRawPreset,
        tuning: LightningRawTuning,
        onProgress: (String) -> Unit = {},
    ): LightningCaptureResult {
        if (!captureMutex.tryLock()) return LightningCaptureResult.Failure("RAW burst already capturing")
        try {
            if (activeJobCount() >= MAX_PENDING_JOBS) return LightningCaptureResult.Failure("Processing queue full")
            activeRoute ?: return LightningCaptureResult.Failure("Camera not ready")
            val device = cameraDevice ?: return LightningCaptureResult.Failure("Camera unavailable")
            val session = captureSession ?: return LightningCaptureResult.Failure("Session unavailable")
            val reader = rawReader ?: return LightningCaptureResult.Failure("RAW output unavailable")
            val chars = activeCharacteristics ?: return LightningCaptureResult.Failure("Camera metadata unavailable")
            val candidate = activeCandidate ?: return LightningCaptureResult.Failure("RAW size unavailable")

            val started = android.os.SystemClock.elapsedRealtime()
            return runCatching {
                val reference = latestPreviewResult ?: awaitReferenceResult(session)
                val baseExposure = reference.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: DEFAULT_EXPOSURE_NS
                val baseIso = reference.get(CaptureResult.SENSOR_SENSITIVITY) ?: 100
                val focus = reference.get(CaptureResult.LENS_FOCUS_DISTANCE)
                onProgress("Capturing ${preset.frameCount} RAW")

                val burst = engine.captureToScratch(
                    device = device,
                    session = session,
                    reader = reader,
                    characteristics = chars,
                    plan = ComputationalRawEngine.BurstPlan(
                        frameCount = preset.frameCount,
                        exposureOffsetsEv = exposureOffsets(preset, tuning.highlightProtection),
                    ),
                    baseIso = baseIso,
                    baseExposureTimeNs = baseExposure,
                    lockedFocusDistanceDiopters = focus,
                    applyCommonSettings = { builder ->
                        builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                        builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                        builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
                        builder.set(CaptureRequest.CONTROL_CAPTURE_INTENT, CaptureRequest.CONTROL_CAPTURE_INTENT_STILL_CAPTURE)
                        applyZoom(builder, chars, _cameraState.value.zoomRatio)
                    },
                    applySensorPixelMode = { builder ->
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            builder.set(
                                CaptureRequest.SENSOR_PIXEL_MODE,
                                if (candidate.maximumResolutionMode) CameraMetadata.SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION
                                else CameraMetadata.SENSOR_PIXEL_MODE_DEFAULT,
                            )
                        }
                    },
                )

                val captureMillis = android.os.SystemClock.elapsedRealtime() - started
                val id = idGenerator.incrementAndGet()
                val post = PostJob(id, burst, preset, tuning.copy(), candidate.maximumResolutionMode)
                addJob(
                    LightningRawJobStatus(
                        id = id,
                        preset = preset,
                        stage = LightningJobStage.QUEUED,
                        message = "Queued",
                        width = burst.width,
                        height = burst.height,
                        frameCount = burst.frameCount,
                    ),
                )
                if (!queue.trySend(post).isSuccess) {
                    removeJob(id)
                    engine.discardCaptured(burst)
                    error("Processing queue rejected capture")
                }
                onProgress("Processing in background · shutter ready")
                LightningCaptureResult.Queued(burst.width, burst.height, burst.frameCount, captureMillis)
            }.getOrElse { error ->
                runCatching { resumePreview() }
                LightningCaptureResult.Failure(error.message ?: error::class.java.simpleName)
            }
        } finally {
            captureMutex.unlock()
        }
    }

    private suspend fun process(job: PostJob) {
        updateJob(job.id) { it.copy(stage = LightningJobStage.FUSING, message = "Native RAW fusion") }
        try {
            val fusion = withContext(Dispatchers.Default) {
                NativeRawFusion.merge(
                    burst = job.burst,
                    tuning = NativeFusionTuning(job.tuning.denoiseStrength, job.tuning.highlightProtection),
                )
            }
            updateJob(job.id) {
                it.copy(
                    stage = LightningJobStage.SAVING,
                    message = if (job.tuning.enhancedUpscale) "Saving RAW + HD+" else "Saving RAW",
                    nativeFusion = fusion.native,
                )
            }

            val outputs = supervisorScope {
                val dng = async(Dispatchers.IO) { saveMergedDng(fusion.merged, job.preset) }
                val enhanced = if (job.tuning.enhancedUpscale) {
                    async(Dispatchers.Default) {
                        runCatching {
                            enhancedProcessor.processAndSave(
                                merged = fusion.merged,
                                preset = job.preset,
                                highlightProtection = job.tuning.highlightProtection,
                                denoiseStrength = job.tuning.denoiseStrength,
                                upscale = true,
                            )
                        }
                    }
                } else null
                dng.await() to enhanced?.await()
            }
            val dngUri = outputs.first
            val enhancedResult = outputs.second?.getOrNull()
            val enhancedError = outputs.second?.exceptionOrNull()?.message
            updateJob(job.id) {
                it.copy(
                    stage = LightningJobStage.SAVED,
                    message = when {
                        enhancedResult != null -> "Saved RAW + ${enhancedResult.width}×${enhancedResult.height} HD+"
                        enhancedError != null -> "Saved RAW · HD+ failed"
                        else -> "Saved RAW"
                    },
                    nativeFusion = fusion.native,
                    dngUri = dngUri,
                    enhancedUri = enhancedResult?.uri,
                    enhancedWidth = enhancedResult?.width ?: 0,
                    enhancedHeight = enhancedResult?.height ?: 0,
                )
            }
        } catch (error: Throwable) {
            updateJob(job.id) {
                it.copy(stage = LightningJobStage.FAILED, message = error.message ?: error::class.java.simpleName)
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
        coordinatorDispatcher.close()
        cameraThread.quitSafely()
        imageThread.quitSafely()
    }

    private fun applyPreviewDefaults(builder: CaptureRequest.Builder, chars: CameraCharacteristics) {
        builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
        builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
        builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
        builder.set(CaptureRequest.CONTROL_CAPTURE_INTENT, CaptureRequest.CONTROL_CAPTURE_INTENT_PREVIEW)
        if (chars.get(CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE) == true) {
            builder.set(CaptureRequest.CONTROL_AE_LOCK, false)
        }
        if (chars.get(CameraCharacteristics.CONTROL_AWB_LOCK_AVAILABLE) == true) {
            builder.set(CaptureRequest.CONTROL_AWB_LOCK, false)
        }
        val toneModes = chars.get(CameraCharacteristics.TONEMAP_AVAILABLE_TONE_MAP_MODES).orEmpty()
        if (toneModes.contains(CaptureRequest.TONEMAP_MODE_FAST)) builder.set(CaptureRequest.TONEMAP_MODE, CaptureRequest.TONEMAP_MODE_FAST)
        val nrModes = chars.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES).orEmpty()
        if (nrModes.contains(CaptureRequest.NOISE_REDUCTION_MODE_FAST)) builder.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_FAST)
        val edgeModes = chars.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES).orEmpty()
        if (edgeModes.contains(CaptureRequest.EDGE_MODE_FAST)) builder.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_FAST)
        setContinuousAfIfSupported(builder, chars)
        applyZoom(builder, chars, _cameraState.value.zoomRatio)
    }

    private fun applyZoom(builder: CaptureRequest.Builder, chars: CameraCharacteristics, ratio: Float) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val range = chars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
            if (range != null) {
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
            chars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)?.let {
                return 1f.coerceAtLeast(it.lower) to it.upper.coerceAtLeast(1f)
            }
        }
        return 1f to (chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f).coerceAtLeast(1f)
    }

    private fun exposureOffsets(preset: ComputationalRawPreset, highlight: Float): List<Float> {
        val h = highlight.coerceIn(0f, 1f)
        return when (preset) {
            ComputationalRawPreset.QUALITY -> listOf(-1.0f - h, -0.5f - 0.5f * h, 0f, 0f, 0f, 0f)
            ComputationalRawPreset.HDR -> listOf(-2.0f - h, -1.25f - 0.5f * h, -0.75f, 0f, 0f, 0f, 0f, 0f)
            ComputationalRawPreset.MAX -> listOf(-2.5f - 0.5f * h, -2f, -1.25f, -0.75f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
        }
    }

    private val previewCaptureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            latestPreviewResult = result
        }
    }

    private suspend fun awaitReferenceResult(session: CameraCaptureSession): TotalCaptureResult = withTimeout(1_500) {
        val builder = previewBuilder ?: error("Preview request unavailable")
        suspendCancellableCoroutine { continuation ->
            val callback = object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                    latestPreviewResult = result
                    if (continuation.isActive) continuation.resume(result)
                }
            }
            runCatching { session.capture(builder.build(), callback, cameraHandler) }
                .onFailure { if (continuation.isActive) continuation.resumeWithException(it) }
        }
    }

    private fun resumePreview() {
        val session = captureSession ?: return
        val builder = previewBuilder ?: return
        cameraHandler.post {
            runCatching { session.setRepeatingRequest(builder.build(), previewCaptureCallback, cameraHandler) }
        }
    }

    private fun saveMergedDng(merged: ComputationalRawEngine.MergeResult, preset: ComputationalRawPreset): Uri {
        val resolver = appContext.contentResolver
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "OMNI_RAW_${preset.name}_${timestamp}.dng")
            put(MediaStore.Images.Media.MIME_TYPE, "image/x-adobe-dng")
            put(MediaStore.Images.Media.WIDTH, merged.width)
            put(MediaStore.Images.Media.HEIGHT, merged.height)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/OmniCam/C-RAW")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("Could not create DNG")
        try {
            resolver.openOutputStream(uri, "w")?.use { output ->
                android.hardware.camera2.DngCreator(merged.referenceCharacteristics, merged.referenceResult).use { creator ->
                    creator.setDescription("OmniCam C1.7 native multi-frame RAW ${preset.name}")
                    creator.writeByteBuffer(output, Size(merged.width, merged.height), merged.pixels16.duplicate(), 0)
                }
            } ?: error("DNG output unavailable")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            }
            return uri
        } catch (error: Throwable) {
            resolver.delete(uri, null, null)
            throw error
        }
    }

    private fun addJob(status: LightningRawJobStatus) = synchronized(jobsLock) {
        _jobs.value = (listOf(status) + _jobs.value).take(MAX_JOB_HISTORY)
    }

    private fun updateJob(id: Long, transform: (LightningRawJobStatus) -> LightningRawJobStatus) = synchronized(jobsLock) {
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
        return bounded.minWithOrNull(compareBy<Size> { kotlin.math.abs(it.width.toFloat() / it.height - aspect) }.thenByDescending(::pixels))
            ?: Size(1440, 1080)
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
    private suspend fun createSession(device: CameraDevice, preview: Surface, raw: Surface): CameraCaptureSession =
        suspendCancellableCoroutine { continuation ->
            val callback = object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    if (continuation.isActive) continuation.resume(session) else session.close()
                }
                override fun onConfigureFailed(session: CameraCaptureSession) {
                    session.close()
                    if (continuation.isActive) continuation.resumeWithException(IllegalStateException("Preview + RAW session rejected"))
                }
            }
            runCatching { device.createCaptureSession(listOf(preview, raw), callback, cameraHandler) }
                .onFailure { if (continuation.isActive) continuation.resumeWithException(it) }
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
        _cameraState.value = LightningCameraState()
    }

    private fun pixels(size: Size): Long = size.width.toLong() * size.height.toLong()

    private data class RawCandidate(val size: Size, val maximumResolutionMode: Boolean)
    private data class PostJob(
        val id: Long,
        val burst: ComputationalRawEngine.CapturedBurst,
        val preset: ComputationalRawPreset,
        val tuning: LightningRawTuning,
        val maximumResolutionMode: Boolean,
    )

    private companion object {
        const val MAX_PENDING_JOBS = 3
        const val MAX_JOB_HISTORY = 7
        const val DEFAULT_EXPOSURE_NS = 16_666_667L
    }
}
