package com.omnicam.camera.camerax

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Rect
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.MeteringRectangle
import android.media.ImageReader
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import android.util.Range
import android.util.Size
import com.omnicam.camera.capability.CameraRouteAccess
import com.omnicam.camera.capability.ValuableCameraRoute
import com.omnicam.core.model.LensFacing
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

data class LightningRawTuning(
    val denoiseStrength: Float = 0.88f,
    val highlightProtection: Float = 0.80f,
    val upscaleFactor: Float = 1f,
    val aspectRatio: Float? = 4f / 3f,
)

data class LightningCameraState(
    val cameraId: String? = null,
    val minZoomRatio: Float = 1f,
    val maxZoomRatio: Float = 1f,
    val zoomRatio: Float = 1f,
    val flashAvailable: Boolean = false,
    val flashMode: LightningFlashMode = LightningFlashMode.OFF,
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

enum class LightningJobStage { QUEUED, FUSING, SAVING, SAVED, FAILED }

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
    val outputWidth: Int = 0,
    val outputHeight: Int = 0,
    val upscaleFactor: Float = 1f,
) {
    val terminal: Boolean get() = stage == LightningJobStage.SAVED || stage == LightningJobStage.FAILED
}

/** Hardware JPEG live preview + native RAW_SENSOR capture saved only as DNG. */
class LightningRawController(context: Context) {
    private val appContext = context.applicationContext
    private val cameraManager = appContext.getSystemService(CameraManager::class.java)
    private val cameraThread = HandlerThread("OmniCam-C20-Camera").apply { start() }
    private val imageThread = HandlerThread("OmniCam-C20-RawCopy").apply { start() }
    private val previewThread = HandlerThread("OmniCam-C20-JpegPreview").apply { start() }
    private val cameraHandler = Handler(cameraThread.looper)
    private val imageHandler = Handler(imageThread.looper)
    private val previewHandler = Handler(previewThread.looper)
    private val scratchDir = File(appContext.cacheDir, "computational-raw")
    private val engine = ComputationalRawEngine(cameraHandler, imageHandler, scratchDir)

    private val coordinatorDispatcher = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "OmniCam-C20-DngQueue").apply {
            priority = (Thread.NORM_PRIORITY - 1).coerceAtLeast(Thread.MIN_PRIORITY)
        }
    }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + coordinatorDispatcher)
    private val queue = Channel<PostJob>(MAX_PENDING_JOBS)
    private val captureMutex = Mutex()
    private val idGenerator = AtomicLong(System.currentTimeMillis())
    private val focusGeneration = AtomicLong(0)
    private val jobsLock = Any()

    private val _jobs = MutableStateFlow<List<LightningRawJobStatus>>(emptyList())
    val jobs: StateFlow<List<LightningRawJobStatus>> = _jobs.asStateFlow()
    private val _cameraState = MutableStateFlow(LightningCameraState())
    val cameraState: StateFlow<LightningCameraState> = _cameraState.asStateFlow()
    private val _previewFrame = MutableStateFlow<JpegPreviewFrame?>(null)
    val previewFrame: StateFlow<JpegPreviewFrame?> = _previewFrame.asStateFlow()
    private val _focusState = MutableStateFlow(LightningFocusState())
    val focusState: StateFlow<LightningFocusState> = _focusState.asStateFlow()

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var rawReader: ImageReader? = null
    private var previewReader: ImageReader? = null
    private var previewBuilder: CaptureRequest.Builder? = null
    private var activeRoute: ValuableCameraRoute? = null
    private var activeCharacteristics: CameraCharacteristics? = null
    private var activeCandidate: RawCandidate? = null
    private var activeSpec: ComputationalRawViewfinderSpec? = null
    private var requestedFlashMode = LightningFlashMode.OFF
    @Volatile private var latestPreviewResult: TotalCaptureResult? = null
    @Volatile private var latestPreviewBitmap: Bitmap? = null

    init {
        engine.clearStaleScratch()
        scope.launch { for (job in queue) process(job) }
    }

    fun createViewfinderSpec(
        route: ValuableCameraRoute,
        targetAspect: Float?,
        displayRotationDegrees: Int = 0,
        sessionKey: String = "",
    ): ComputationalRawViewfinderSpec {
        require(route.access == CameraRouteAccess.DIRECT_CAMERA_DEVICE)
        val chars = cameraManager.getCameraCharacteristics(route.camera.id)
        val previewSize = choosePreviewSize(chars, targetAspect)
        val sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        val displayRotation = ((displayRotationDegrees % 360) + 360) % 360
        val front = route.camera.lensFacing == LensFacing.FRONT
        val rotation = if (front) {
            (sensorOrientation + displayRotation) % 360
        } else {
            (sensorOrientation - displayRotation + 360) % 360
        }
        @Suppress("UNUSED_VARIABLE") val ignoredSessionKey = sessionKey
        return ComputationalRawViewfinderSpec(
            previewSize = previewSize,
            rotationDegrees = rotation,
            mirrorX = front,
            targetAspect = targetAspect,
        )
    }

    @SuppressLint("MissingPermission")
    suspend fun bind(
        spec: ComputationalRawViewfinderSpec,
        route: ValuableCameraRoute,
    ): ComputationalRawBindResult = withContext(Dispatchers.Main.immediate) {
        closeCurrent()
        if (route.access != CameraRouteAccess.DIRECT_CAMERA_DEVICE || !route.camera.rawSupported) {
            return@withContext ComputationalRawBindResult.Failure(route.camera.id, "Direct RAW_SENSOR route required")
        }
        val chars = runCatching { cameraManager.getCameraCharacteristics(route.camera.id) }
            .getOrElse {
                return@withContext ComputationalRawBindResult.Failure(
                    route.camera.id,
                    it.message ?: "Camera unavailable",
                )
            }
        val candidates = rawCandidates(chars)
        if (candidates.isEmpty()) {
            return@withContext ComputationalRawBindResult.Failure(route.camera.id, "No RAW_SENSOR size")
        }

        var last: Throwable? = null
        for (candidate in candidates) {
            closeCurrent()
            val attempt = runCatching { bindAttempt(spec, route, chars, candidate) }
            attempt.onSuccess { return@withContext it }
            last = attempt.exceptionOrNull()
        }
        ComputationalRawBindResult.Failure(route.camera.id, last?.message ?: "JPEG preview + RAW session rejected")
    }

    @SuppressLint("MissingPermission")
    private suspend fun bindAttempt(
        spec: ComputationalRawViewfinderSpec,
        route: ValuableCameraRoute,
        chars: CameraCharacteristics,
        candidate: RawCandidate,
    ): ComputationalRawBindResult.Success {
        val jpeg = ImageReader.newInstance(
            spec.previewSize.width,
            spec.previewSize.height,
            ImageFormat.JPEG,
            2,
        )
        val raw = ImageReader.newInstance(candidate.size.width, candidate.size.height, ImageFormat.RAW_SENSOR, 4)
        previewReader = jpeg
        rawReader = raw
        activeRoute = route
        activeCharacteristics = chars
        activeCandidate = candidate
        activeSpec = spec
        latestPreviewResult = null
        latestPreviewBitmap = null
        attachPreviewReader(jpeg, spec)

        val device = openCamera(route.camera.id)
        cameraDevice = device
        val session = createSession(device, jpeg.surface, raw.surface)
        captureSession = session
        val zoom = zoomRange(chars)
        val flashAvailable = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        val effectiveFlash = if (flashAvailable) requestedFlashMode else LightningFlashMode.OFF
        _cameraState.value = LightningCameraState(
            cameraId = route.camera.id,
            minZoomRatio = zoom.first,
            maxZoomRatio = zoom.second,
            zoomRatio = 1f.coerceIn(zoom.first, zoom.second),
            flashAvailable = flashAvailable,
            flashMode = effectiveFlash,
        )

        val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(jpeg.surface)
            set(CaptureRequest.JPEG_QUALITY, JPEG_PREVIEW_QUALITY)
            set(CaptureRequest.JPEG_ORIENTATION, spec.rotationDegrees)
            applyProcessedPreviewDefaults(this, chars)
            applyPreviewFlash(this, chars, effectiveFlash)
        }
        previewBuilder = builder
        session.setRepeatingRequest(builder.build(), previewCaptureCallback, cameraHandler)
        return ComputationalRawBindResult.Success(
            route.camera.id,
            candidate.size.width,
            candidate.size.height,
            candidate.maximumResolutionMode,
        )
    }

    private fun attachPreviewReader(reader: ImageReader, spec: ComputationalRawViewfinderSpec) {
        reader.setOnImageAvailableListener({ source ->
            val image = source.acquireLatestImage() ?: return@setOnImageAvailableListener
            image.use { frame ->
                runCatching {
                    JpegPreviewDecoder.decode(frame, spec.rotationDegrees, spec.mirrorX)
                }.onSuccess { bitmap ->
                    latestPreviewBitmap = bitmap
                    _previewFrame.value = JpegPreviewFrame(bitmap, frame.timestamp)
                }
            }
        }, previewHandler)
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

    fun setFlashMode(mode: LightningFlashMode): LightningFlashMode {
        requestedFlashMode = mode
        val state = _cameraState.value
        val effective = if (state.flashAvailable) mode else LightningFlashMode.OFF
        _cameraState.value = state.copy(flashMode = effective)
        val chars = activeCharacteristics ?: return effective
        cameraHandler.post {
            val builder = previewBuilder ?: return@post
            val session = captureSession ?: return@post
            applyPreviewFlash(builder, chars, effective)
            runCatching { session.setRepeatingRequest(builder.build(), previewCaptureCallback, cameraHandler) }
        }
        return effective
    }

    fun focusAt(normalizedX: Float, normalizedY: Float): Boolean {
        val chars = activeCharacteristics ?: return false
        val spec = activeSpec ?: return false
        val session = captureSession ?: return false
        val builder = previewBuilder ?: return false
        if ((chars.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0) <= 0) return false

        val displayX = normalizedX.coerceIn(0f, 1f)
        val displayY = normalizedY.coerceIn(0f, 1f)
        val sensorPoint = displayToSensor(displayX, displayY, spec)
        val bounds = meteringBounds(chars, spec, _cameraState.value.zoomRatio)
        val centerX = bounds.left + (sensorPoint.first * bounds.width()).roundToInt()
        val centerY = bounds.top + (sensorPoint.second * bounds.height()).roundToInt()
        val half = (min(bounds.width(), bounds.height()) * 0.075f).roundToInt().coerceAtLeast(24)
        val left = (centerX - half).coerceIn(bounds.left, max(bounds.left, bounds.right - 2))
        val top = (centerY - half).coerceIn(bounds.top, max(bounds.top, bounds.bottom - 2))
        val right = (centerX + half).coerceIn(left + 1, bounds.right)
        val bottom = (centerY + half).coerceIn(top + 1, bounds.bottom)
        val region = MeteringRectangle(Rect(left, top, right, bottom), MeteringRectangle.METERING_WEIGHT_MAX)
        val generation = focusGeneration.incrementAndGet()
        _focusState.value = LightningFocusState(displayX, displayY, LightningFocusStatus.SCANNING)

        cameraHandler.post {
            runCatching {
                builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
                builder.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(region))
                if ((chars.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) ?: 0) > 0) {
                    builder.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(region))
                }
                builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_CANCEL)
                session.capture(builder.build(), previewCaptureCallback, cameraHandler)
                builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
                session.capture(builder.build(), previewCaptureCallback, cameraHandler)
                builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
                session.setRepeatingRequest(builder.build(), previewCaptureCallback, cameraHandler)
            }.onFailure {
                _focusState.value = LightningFocusState(displayX, displayY, LightningFocusStatus.FAILED)
            }
        }
        cameraHandler.postDelayed({ restoreContinuousFocus(generation) }, FOCUS_TIMEOUT_MS)
        return true
    }

    suspend fun capture(
        preset: ComputationalRawPreset,
        tuning: LightningRawTuning,
        onProgress: (String) -> Unit = {},
    ): LightningCaptureResult {
        if (!captureMutex.tryLock()) return LightningCaptureResult.Failure("RAW burst already capturing")
        var temporaryTorch = false
        try {
            if (activeJobCount() >= MAX_PENDING_JOBS) return LightningCaptureResult.Failure("Processing queue full")
            activeRoute ?: return LightningCaptureResult.Failure("Camera not ready")
            val device = cameraDevice ?: return LightningCaptureResult.Failure("Camera unavailable")
            val session = captureSession ?: return LightningCaptureResult.Failure("Session unavailable")
            val reader = rawReader ?: return LightningCaptureResult.Failure("RAW output unavailable")
            val chars = activeCharacteristics ?: return LightningCaptureResult.Failure("Camera metadata unavailable")
            val candidate = activeCandidate ?: return LightningCaptureResult.Failure("RAW size unavailable")
            val capturedZoom = _cameraState.value.zoomRatio
            val started = android.os.SystemClock.elapsedRealtime()

            return runCatching {
                val initial = latestPreviewResult ?: awaitReferenceResult(session)
                val flashMode = _cameraState.value.flashMode
                val flashDuringBurst = shouldIlluminateBurst(flashMode, initial)
                if (flashDuringBurst && flashMode != LightningFlashMode.TORCH) {
                    onProgress("Preparing flash")
                    applyPreviewFlashNow(LightningFlashMode.TORCH)
                    temporaryTorch = true
                    delay(FLASH_METERING_DELAY_MS)
                }

                val reference = latestPreviewResult ?: awaitReferenceResult(session)
                val baseExposure = reference.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: DEFAULT_EXPOSURE_NS
                val baseIso = reference.get(CaptureResult.SENSOR_SENSITIVITY) ?: 100
                val focus = reference.get(CaptureResult.LENS_FOCUS_DISTANCE)
                onProgress("Capturing ${preset.frameCount} native RAW")
                runCatching { session.stopRepeating() }

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
                    applyCommonSettings = { captureBuilder ->
                        captureBuilder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                        captureBuilder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                        captureBuilder.set(CaptureRequest.CONTROL_CAPTURE_INTENT, CaptureRequest.CONTROL_CAPTURE_INTENT_STILL_CAPTURE)
                        captureBuilder.set(
                            CaptureRequest.FLASH_MODE,
                            if (flashDuringBurst) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF,
                        )
                        applyZoom(captureBuilder, chars, capturedZoom)
                    },
                    applySensorPixelMode = { captureBuilder ->
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            captureBuilder.set(
                                CaptureRequest.SENSOR_PIXEL_MODE,
                                if (candidate.maximumResolutionMode) CameraMetadata.SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION
                                else CameraMetadata.SENSOR_PIXEL_MODE_DEFAULT,
                            )
                        }
                    },
                )

                if (temporaryTorch) {
                    applyPreviewFlashNow(requestedFlashMode)
                    temporaryTorch = false
                } else {
                    resumePreview()
                }

                val captureMillis = android.os.SystemClock.elapsedRealtime() - started
                val id = idGenerator.incrementAndGet()
                val post = PostJob(
                    id = id,
                    burst = burst,
                    preset = preset,
                    tuning = tuning.copy(),
                    zoomRatio = capturedZoom,
                    thumbnail = snapshotThumbnail(),
                )
                addJob(
                    LightningRawJobStatus(
                        id = id,
                        preset = preset,
                        stage = LightningJobStage.QUEUED,
                        message = "Queued DNG",
                        width = burst.width,
                        height = burst.height,
                        frameCount = burst.frameCount,
                        upscaleFactor = tuning.upscaleFactor.coerceIn(1f, MAX_UPSCALE_FACTOR),
                    ),
                )
                if (!queue.trySend(post).isSuccess) {
                    removeJob(id)
                    post.thumbnail?.recycle()
                    engine.discardCaptured(burst)
                    error("Processing queue rejected capture")
                }
                onProgress("DNG processing · shutter ready")
                LightningCaptureResult.Queued(burst.width, burst.height, burst.frameCount, captureMillis)
            }.getOrElse { error ->
                if (temporaryTorch) {
                    try {
                        applyPreviewFlashNow(requestedFlashMode)
                    } catch (_: Throwable) {
                        resumePreview()
                    }
                    temporaryTorch = false
                } else {
                    resumePreview()
                }
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
                it.copy(stage = LightningJobStage.SAVING, message = "Writing DNG", nativeFusion = fusion.native)
            }
            val transformed = withContext(Dispatchers.Default) {
                RawDngTransform.apply(
                    merged = fusion.merged,
                    zoomRatio = job.zoomRatio,
                    targetAspect = job.tuning.aspectRatio,
                    upscaleFactor = job.tuning.upscaleFactor,
                )
            }
            val uri = withContext(Dispatchers.IO) {
                saveDng(fusion.merged, transformed, job)
            }
            updateJob(job.id) {
                it.copy(
                    stage = LightningJobStage.SAVED,
                    message = "Saved ${transformed.width}×${transformed.height} DNG",
                    nativeFusion = fusion.native,
                    dngUri = uri,
                    outputWidth = transformed.width,
                    outputHeight = transformed.height,
                    upscaleFactor = transformed.scale,
                )
            }
        } catch (error: Throwable) {
            updateJob(job.id) {
                it.copy(stage = LightningJobStage.FAILED, message = error.message ?: error::class.java.simpleName)
            }
        } finally {
            job.thumbnail?.recycle()
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
        previewThread.quitSafely()
    }

    private fun snapshotThumbnail(): Bitmap? {
        val source = latestPreviewBitmap ?: return null
        val maxSide = max(source.width, source.height)
        if (maxSide <= 256) return source.copy(Bitmap.Config.ARGB_8888, false)
        val scale = 256f / maxSide
        return runCatching {
            Bitmap.createScaledBitmap(
                source,
                (source.width * scale).roundToInt().coerceAtLeast(1),
                (source.height * scale).roundToInt().coerceAtLeast(1),
                true,
            )
        }.getOrNull()
    }

    private fun applyProcessedPreviewDefaults(builder: CaptureRequest.Builder, chars: CameraCharacteristics) {
        builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
        builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
        builder.set(CaptureRequest.CONTROL_CAPTURE_INTENT, CaptureRequest.CONTROL_CAPTURE_INTENT_PREVIEW)
        if (chars.get(CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE) == true) {
            builder.set(CaptureRequest.CONTROL_AE_LOCK, false)
        }
        if (chars.get(CameraCharacteristics.CONTROL_AWB_LOCK_AVAILABLE) == true) {
            builder.set(CaptureRequest.CONTROL_AWB_LOCK, false)
        }
        choosePreviewFps(chars)?.let { builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
        val antibanding = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_ANTIBANDING_MODES).orEmpty()
        if (antibanding.contains(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_AUTO)) {
            builder.set(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE, CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_AUTO)
        }
        val toneModes = chars.get(CameraCharacteristics.TONEMAP_AVAILABLE_TONE_MAP_MODES).orEmpty()
        if (toneModes.contains(CaptureRequest.TONEMAP_MODE_FAST)) {
            builder.set(CaptureRequest.TONEMAP_MODE, CaptureRequest.TONEMAP_MODE_FAST)
        }
        val nrModes = chars.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES).orEmpty()
        when {
            nrModes.contains(CaptureRequest.NOISE_REDUCTION_MODE_FAST) ->
                builder.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_FAST)
            nrModes.contains(CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY) ->
                builder.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY)
        }
        val edgeModes = chars.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES).orEmpty()
        when {
            edgeModes.contains(CaptureRequest.EDGE_MODE_FAST) -> builder.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_FAST)
            edgeModes.contains(CaptureRequest.EDGE_MODE_HIGH_QUALITY) -> builder.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_HIGH_QUALITY)
        }
        setContinuousAfIfSupported(builder, chars)
        applyZoom(builder, chars, _cameraState.value.zoomRatio)
    }

    private fun applyPreviewFlash(
        builder: CaptureRequest.Builder,
        chars: CameraCharacteristics,
        mode: LightningFlashMode,
    ) {
        val available = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        if (!available) {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
            return
        }
        val aeModes = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES).orEmpty()
        when (mode) {
            LightningFlashMode.OFF -> {
                builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
            }
            LightningFlashMode.AUTO -> {
                builder.set(
                    CaptureRequest.CONTROL_AE_MODE,
                    if (aeModes.contains(CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH)) {
                        CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH
                    } else {
                        CaptureRequest.CONTROL_AE_MODE_ON
                    },
                )
                builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
            }
            LightningFlashMode.ON -> {
                builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
            }
            LightningFlashMode.TORCH -> {
                builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH)
            }
        }
    }

    private suspend fun applyPreviewFlashNow(mode: LightningFlashMode) {
        val chars = activeCharacteristics ?: return
        val effective = if (_cameraState.value.flashAvailable) mode else LightningFlashMode.OFF
        suspendCancellableCoroutine { continuation ->
            cameraHandler.post {
                val builder = previewBuilder
                val session = captureSession
                if (builder == null || session == null) {
                    if (continuation.isActive) continuation.resume(Unit)
                    return@post
                }
                runCatching {
                    applyPreviewFlash(builder, chars, effective)
                    session.setRepeatingRequest(builder.build(), previewCaptureCallback, cameraHandler)
                }.onSuccess {
                    if (continuation.isActive) continuation.resume(Unit)
                }.onFailure {
                    if (continuation.isActive) continuation.resumeWithException(it)
                }
            }
        }
    }

    private fun shouldIlluminateBurst(mode: LightningFlashMode, result: TotalCaptureResult): Boolean {
        if (!_cameraState.value.flashAvailable) return false
        return when (mode) {
            LightningFlashMode.OFF -> false
            LightningFlashMode.ON, LightningFlashMode.TORCH -> true
            LightningFlashMode.AUTO -> {
                val aeState = result.get(CaptureResult.CONTROL_AE_STATE)
                aeState == CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED ||
                    (result.get(CaptureResult.SENSOR_SENSITIVITY) ?: 0) >= AUTO_FLASH_ISO ||
                    (result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 0L) >= AUTO_FLASH_EXPOSURE_NS
            }
        }
    }

    private fun choosePreviewFps(chars: CameraCharacteristics): Range<Int>? {
        val ranges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES).orEmpty()
        return ranges.firstOrNull { it.lower <= 15 && it.upper == 30 && it.lower >= 10 }
            ?: ranges.firstOrNull { it.lower <= 24 && it.upper == 30 }
            ?: ranges.firstOrNull { it.lower == 30 && it.upper == 30 }
            ?: ranges.filter { it.upper >= 30 }
                .minWithOrNull(compareBy<Range<Int>> { abs(it.upper - 30) }.thenByDescending { it.lower })
            ?: ranges.maxByOrNull { it.upper }
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
            chars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)?.let {
                return 1f.coerceAtLeast(it.lower) to it.upper.coerceAtLeast(1f)
            }
        }
        return 1f to (chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f).coerceAtLeast(1f)
    }

    private fun exposureOffsets(preset: ComputationalRawPreset, highlight: Float): List<Float> {
        val h = highlight.coerceIn(0f, 1f)
        return when (preset) {
            ComputationalRawPreset.QUALITY -> listOf(-0.7f - 0.5f * h, 0f, 0f, 0f)
            ComputationalRawPreset.HDR -> listOf(-2.0f - 0.7f * h, -1f - 0.4f * h, 0f, 0f, 0f, 0f)
            ComputationalRawPreset.MAX -> listOf(-2.5f - 0.5f * h, -1.6f, -0.8f, 0f, 0f, 0f, 0f, 0f)
        }
    }

    private val previewCaptureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult,
        ) {
            latestPreviewResult = result
            val focus = _focusState.value
            if (focus.status == LightningFocusStatus.SCANNING) {
                when (result.get(CaptureResult.CONTROL_AF_STATE)) {
                    CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED,
                    CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED,
                    -> _focusState.value = focus.copy(status = LightningFocusStatus.FOCUSED)
                    CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED ->
                        _focusState.value = focus.copy(status = LightningFocusStatus.FAILED)
                }
            }
        }
    }

    private fun restoreContinuousFocus(generation: Long) {
        if (focusGeneration.get() != generation) return
        val chars = activeCharacteristics ?: return
        val builder = previewBuilder ?: return
        val session = captureSession ?: return
        runCatching {
            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_CANCEL)
            session.capture(builder.build(), previewCaptureCallback, cameraHandler)
            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
            setContinuousAfIfSupported(builder, chars)
            session.setRepeatingRequest(builder.build(), previewCaptureCallback, cameraHandler)
        }
        val state = _focusState.value
        if (state.status == LightningFocusStatus.SCANNING) {
            _focusState.value = state.copy(status = LightningFocusStatus.FAILED)
        }
        cameraHandler.postDelayed({
            if (focusGeneration.get() == generation) _focusState.value = LightningFocusState()
        }, FOCUS_INDICATOR_HOLD_MS)
    }

    private fun displayToSensor(
        displayX: Float,
        displayY: Float,
        spec: ComputationalRawViewfinderSpec,
    ): Pair<Float, Float> {
        var x = displayX
        val y = displayY
        if (spec.mirrorX) x = 1f - x
        return when (((spec.rotationDegrees % 360) + 360) % 360) {
            90 -> y to (1f - x)
            180 -> (1f - x) to (1f - y)
            270 -> (1f - y) to x
            else -> x to y
        }
    }

    private fun meteringBounds(
        chars: CameraCharacteristics,
        spec: ComputationalRawViewfinderSpec,
        zoom: Float,
    ): Rect {
        val active = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            ?: Rect(0, 0, spec.previewSize.width, spec.previewSize.height)
        var bounds = cropRectToAspect(active, spec.targetAspect)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R && zoom > 1.001f) {
            val width = (bounds.width() / zoom).roundToInt().coerceAtLeast(2)
            val height = (bounds.height() / zoom).roundToInt().coerceAtLeast(2)
            val left = bounds.left + (bounds.width() - width) / 2
            val top = bounds.top + (bounds.height() - height) / 2
            bounds = Rect(left, top, left + width, top + height)
        }
        return bounds
    }

    private fun cropRectToAspect(source: Rect, targetAspect: Float?): Rect {
        val aspect = targetAspect?.takeIf { it.isFinite() && it > 0f } ?: return Rect(source)
        val current = source.width().toFloat() / source.height()
        return if (current > aspect) {
            val width = (source.height() * aspect).roundToInt().coerceAtLeast(2)
            val left = source.left + (source.width() - width) / 2
            Rect(left, source.top, left + width, source.bottom)
        } else if (current < aspect) {
            val height = (source.width() / aspect).roundToInt().coerceAtLeast(2)
            val top = source.top + (source.height() - height) / 2
            Rect(source.left, top, source.right, top + height)
        } else {
            Rect(source)
        }
    }

    private suspend fun awaitReferenceResult(session: CameraCaptureSession): TotalCaptureResult = withTimeout(1_500) {
        val builder = previewBuilder ?: error("Preview request unavailable")
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

    private fun saveDng(
        merged: ComputationalRawEngine.MergeResult,
        transformed: RawDngTransform.Result,
        job: PostJob,
    ): Uri {
        val resolver = appContext.contentResolver
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "OMNI_${job.preset.name}_${timestamp}.dng")
            put(MediaStore.Images.Media.MIME_TYPE, "image/x-adobe-dng")
            put(MediaStore.Images.Media.WIDTH, transformed.width)
            put(MediaStore.Images.Media.HEIGHT, transformed.height)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/OmniCam")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("Could not create DNG")
        val temp = File.createTempFile("omnicam_output_", ".dng", appContext.cacheDir)
        try {
            AdaptiveDngWriter.write(
                file = temp,
                merged = merged,
                transformed = transformed,
                thumbnail = job.thumbnail,
                description = "OmniCam C2.0 ${job.preset.name}; zoom=${String.format(Locale.US, "%.2f", job.zoomRatio)}x; " +
                    "upscale=${String.format(Locale.US, "%.2f", transformed.scale)}x; " +
                    "crop=${transformed.cropLeft},${transformed.cropTop}; JPEG-preview",
            )
            resolver.openOutputStream(uri, "w")?.use { output ->
                temp.inputStream().buffered().use { input -> input.copyTo(output, 1024 * 1024) }
            } ?: error("DNG output unavailable")
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
        } finally {
            temp.delete()
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
        } else {
            emptyArray()
        }
        return (regular.asList() + high.asList()).distinctBy { "${it.width}x${it.height}" }
    }

    private fun choosePreviewSize(chars: CameraCharacteristics, targetAspect: Float?): Size {
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: error("No camera stream configuration map")
        val sizes = runCatching { map.getOutputSizes(ImageFormat.JPEG) }.getOrNull().orEmpty()
        require(sizes.isNotEmpty()) { "Camera does not expose JPEG output" }
        val active = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        val aspect = targetAspect?.takeIf { it.isFinite() && it > 0f }
            ?: active?.let { it.width().toFloat() / it.height().toFloat() }
            ?: 4f / 3f
        val bounded = sizes.filter {
            max(it.width, it.height) <= 1600 &&
                min(it.width, it.height) >= 480 &&
                pixels(it) <= 1_600_000L
        }.ifEmpty {
            sizes.filter { pixels(it) <= 2_500_000L }.ifEmpty { sizes.toList() }
        }
        return bounded.minWithOrNull(
            compareBy<Size> { abs(it.width.toFloat() / it.height - aspect) }
                .thenBy { jpegFrameCost(map, it) }
                .thenBy { abs(pixels(it) - 1_000_000L) },
        ) ?: sizes.minBy(::pixels)
    }

    private fun jpegFrameCost(map: android.hardware.camera2.params.StreamConfigurationMap, size: Size): Long {
        val minFrame = runCatching { map.getOutputMinFrameDuration(ImageFormat.JPEG, size) }.getOrDefault(0L)
        val stall = runCatching { map.getOutputStallDuration(ImageFormat.JPEG, size) }.getOrDefault(0L)
        return minFrame.coerceAtLeast(0L) + stall.coerceAtLeast(0L)
    }

    private fun setContinuousAfIfSupported(builder: CaptureRequest.Builder, chars: CameraCharacteristics) {
        val modes = chars.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES).orEmpty()
        when {
            modes.contains(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE) ->
                builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            modes.contains(CaptureRequest.CONTROL_AF_MODE_AUTO) ->
                builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
            else -> builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
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
                    if (continuation.isActive) {
                        continuation.resumeWithException(IllegalStateException("Camera disconnected"))
                    }
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    if (continuation.isActive) {
                        continuation.resumeWithException(IllegalStateException("Camera open error $error"))
                    }
                }
            }, cameraHandler)
        } catch (error: Throwable) {
            if (continuation.isActive) continuation.resumeWithException(error)
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun createSession(
        device: CameraDevice,
        preview: android.view.Surface,
        raw: android.view.Surface,
    ): CameraCaptureSession = suspendCancellableCoroutine { continuation ->
        val callback = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                if (continuation.isActive) continuation.resume(session) else session.close()
            }

            override fun onConfigureFailed(session: CameraCaptureSession) {
                session.close()
                if (continuation.isActive) {
                    continuation.resumeWithException(IllegalStateException("JPEG preview + RAW session rejected"))
                }
            }
        }
        runCatching { device.createCaptureSession(listOf(preview, raw), callback, cameraHandler) }
            .onFailure { if (continuation.isActive) continuation.resumeWithException(it) }
    }

    private fun closeCurrent() {
        focusGeneration.incrementAndGet()
        runCatching { captureSession?.stopRepeating() }
        runCatching { captureSession?.abortCaptures() }
        runCatching { captureSession?.close() }
        runCatching { cameraDevice?.close() }
        runCatching { previewReader?.close() }
        runCatching { rawReader?.close() }
        captureSession = null
        cameraDevice = null
        previewReader = null
        rawReader = null
        previewBuilder = null
        activeRoute = null
        activeCharacteristics = null
        activeCandidate = null
        activeSpec = null
        latestPreviewResult = null
        latestPreviewBitmap = null
        _previewFrame.value = null
        _focusState.value = LightningFocusState()
        _cameraState.value = LightningCameraState(flashMode = requestedFlashMode)
    }

    private fun pixels(size: Size): Long = size.width.toLong() * size.height.toLong()

    private data class RawCandidate(val size: Size, val maximumResolutionMode: Boolean)

    private data class PostJob(
        val id: Long,
        val burst: ComputationalRawEngine.CapturedBurst,
        val preset: ComputationalRawPreset,
        val tuning: LightningRawTuning,
        val zoomRatio: Float,
        val thumbnail: Bitmap?,
    )

    private companion object {
        const val MAX_PENDING_JOBS = 3
        const val MAX_JOB_HISTORY = 7
        const val DEFAULT_EXPOSURE_NS = 16_666_667L
        const val MAX_UPSCALE_FACTOR = 3f
        const val AUTO_FLASH_ISO = 800
        const val AUTO_FLASH_EXPOSURE_NS = 28_000_000L
        const val FLASH_METERING_DELAY_MS = 180L
        const val FOCUS_TIMEOUT_MS = 1_600L
        const val FOCUS_INDICATOR_HOLD_MS = 700L
        const val JPEG_PREVIEW_QUALITY: Byte = 88.toByte()
    }
}
