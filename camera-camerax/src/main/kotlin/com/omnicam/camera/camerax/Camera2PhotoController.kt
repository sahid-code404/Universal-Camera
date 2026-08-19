package com.omnicam.camera.camerax

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.DngCreator
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.StreamConfigurationMap
import android.media.Image
import android.media.ImageReader
import android.media.MediaCodecList
import android.media.MediaFormat
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
import androidx.heifwriter.HeifWriter
import com.omnicam.camera.capability.CameraRouteAccess
import com.omnicam.camera.capability.ValuableCameraRoute
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

enum class PhotoAspectRatio(
    val width: Int,
    val height: Int,
    val label: String,
) {
    ONE_ONE(1, 1, "1:1"),
    THREE_TWO(3, 2, "3:2"),
    FOUR_THREE(4, 3, "4:3"),
    SIXTEEN_NINE(16, 9, "16:9"),
    TWO_ONE(2, 1, "2:1"),
}

enum class PhotoOutputFormat {
    HEIF,
    JPEG,
    DNG,
}

enum class HeifEncodingPath {
    NONE,
    NATIVE_CAMERA,
    SOFTWARE_HEVC,
}

data class ProControls(
    val enabled: Boolean = false,
    val iso: Int? = null,
    val exposureTimeNs: Long? = null,
    val focusDistanceDiopters: Float? = null,
)

data class CameraViewfinderSpec(
    val surfaceRequest: ViewfinderSurfaceRequest,
    val transformationInfo: TransformationInfo,
    val previewSize: Size,
    val visibleSourceCrop: RectF,
)

enum class CameraFlashMode {
    OFF,
    AUTO,
    ON,
    TORCH,
}

sealed interface PhotoCaptureResult {
    data class Processing(
        val cameraId: String,
        val requestedFormat: PhotoOutputFormat,
        val message: String,
    ) : PhotoCaptureResult

    data class Success(
        val uri: Uri,
        val width: Int,
        val height: Int,
        val cameraId: String,
        val requestedFormat: PhotoOutputFormat,
        val actualFormat: PhotoOutputFormat,
        val usedFormatFallback: Boolean,
        val heifEncodingPath: HeifEncodingPath = HeifEncodingPath.NONE,
        val sourceWidth: Int = width,
        val sourceHeight: Int = height,
        val upscaled: Boolean = false,
        val usedMaximumResolutionMode: Boolean = false,
    ) : PhotoCaptureResult

    data class Failure(val message: String) : PhotoCaptureResult
}

/**
 * Camera2 photo controller.
 *
 * Resolution architecture:
 * 1. Prefer a genuine maximum-resolution sensor stream when Android exposes one.
 * 2. Otherwise use the largest normal/high-resolution public Camera2 stream.
 * 3. When that processed stream is genuinely small (<=5 MP), optionally perform a transparent
 *    2x-dimension digital upscale in the background. Upscaling is reported separately from native
 *    resolution and is never used for DNG/RAW.
 */
class Camera2PhotoController(context: Context) {
    private val appContext = context.applicationContext
    private val cameraManager = appContext.getSystemService(CameraManager::class.java)
    private val cameraThread = HandlerThread("OmniCam-Photo-Camera2").apply { start() }
    private val cameraHandler = Handler(cameraThread.looper)
    private val saveScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var previewSurface: Surface? = null
    private var imageReader: ImageReader? = null
    private var previewBuilder: CaptureRequest.Builder? = null
    private var controlCharacteristics: CameraCharacteristics? = null
    private var streamCharacteristics: CameraCharacteristics? = null
    private var activeRoute: ValuableCameraRoute? = null
    private var activePreviewSize: Size? = null
    private var activeAspectRatio = PhotoAspectRatio.FOUR_THREE
    private var activeCandidate = StillCandidate(StillPipeline.JPEG, Size(1920, 1440))
    private var requestedPhotoFormat = PhotoOutputFormat.HEIF

    private var currentZoomRatio = 1f
    private var currentExposureCompensation = 0
    private var currentFlashMode = CameraFlashMode.OFF
    private var currentPhotoQuality = 100
    private var currentProControls = ProControls()
    private var adaptiveUpscalingEnabled = true

    fun setAdaptiveUpscalingEnabled(enabled: Boolean) {
        adaptiveUpscalingEnabled = enabled
    }

    fun createViewfinderSpec(
        route: ValuableCameraRoute,
        aspectRatio: PhotoAspectRatio,
        sessionKey: String = "",
    ): CameraViewfinderSpec {
        val ids = resolveRoute(route)
        val chars = cameraManager.getCameraCharacteristics(ids.physicalId ?: ids.deviceId)
        val previewSize = choosePreviewSize(chars)
        val crop = centerCropRect(previewSize, aspectRatio)
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
                append(route.camera.id)
                append(':')
                append(aspectRatio.name)
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
        return CameraViewfinderSpec(request, transformation, previewSize, crop)
    }

    @SuppressLint("MissingPermission")
    suspend fun bind(
        surface: Surface,
        spec: CameraViewfinderSpec,
        route: ValuableCameraRoute,
        aspectRatio: PhotoAspectRatio,
        outputFormat: PhotoOutputFormat = PhotoOutputFormat.HEIF,
        quality: Int = 100,
    ): CameraBindResult = withContext(Dispatchers.Main.immediate) {
        closeCurrent()
        currentZoomRatio = 1f
        currentExposureCompensation = 0
        currentPhotoQuality = quality.coerceIn(1, 100)
        requestedPhotoFormat = outputFormat
        activeAspectRatio = aspectRatio

        val ids = resolveRoute(route)
        val streamChars = runCatching {
            cameraManager.getCameraCharacteristics(ids.physicalId ?: ids.deviceId)
        }.getOrNull() ?: return@withContext CameraBindResult.Failure(
            route.camera.id,
            "Camera characteristics are unavailable",
        )

        val candidates = buildStillCandidates(streamChars, route, outputFormat, aspectRatio)
        var lastFailure: CameraBindResult.Failure? = null
        for (candidate in candidates) {
            closeCurrent()
            val result = bindAttempt(surface, spec, route, candidate)
            if (result is CameraBindResult.Success) return@withContext result
            lastFailure = result as CameraBindResult.Failure
        }
        lastFailure ?: CameraBindResult.Failure(
            route.camera.id,
            "No compatible still-image pipeline is available",
        )
    }

    @SuppressLint("MissingPermission")
    private suspend fun bindAttempt(
        surface: Surface,
        spec: CameraViewfinderSpec,
        route: ValuableCameraRoute,
        candidate: StillCandidate,
    ): CameraBindResult = runCatching {
        val ids = resolveRoute(route)
        val streamChars = cameraManager.getCameraCharacteristics(ids.physicalId ?: ids.deviceId)
        val controlChars = cameraManager.getCameraCharacteristics(ids.deviceId)
        streamCharacteristics = streamChars
        controlCharacteristics = controlChars
        activePreviewSize = spec.previewSize

        val reader = ImageReader.newInstance(
            candidate.size.width,
            candidate.size.height,
            candidate.pipeline.imageFormat,
            2,
        )
        previewSurface = surface
        imageReader = reader
        activeCandidate = candidate
        activeRoute = route

        val device = openCamera(ids.deviceId)
        cameraDevice = device
        val session = createSession(device, surface, reader.surface, ids.physicalId)
        captureSession = session

        val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(surface)
            set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
            setContinuousAfIfSupported(this, controlChars)
        }
        previewBuilder = builder
        applyRepeatingSettings()
        session.setRepeatingRequest(builder.build(), null, cameraHandler)

        CameraBindResult.Success(
            requestedCameraId = route.camera.id,
            actualCameraId = if (ids.physicalId == null) ids.deviceId else "${ids.deviceId}->${ids.physicalId}",
            minZoomRatio = getMinZoom(streamChars),
            maxZoomRatio = getMaxZoom(streamChars),
        )
    }.getOrElse { error ->
        closeCurrent()
        CameraBindResult.Failure(route.camera.id, error.message ?: error::class.java.simpleName)
    }

    fun setZoomRatio(ratio: Float) {
        val chars = streamCharacteristics ?: return
        currentZoomRatio = ratio.coerceIn(getMinZoom(chars), getMaxZoom(chars))
        refreshRepeating()
    }

    fun setExposureCompensation(index: Int) {
        val range = controlCharacteristics?.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE) ?: return
        currentExposureCompensation = index.coerceIn(range.lower, range.upper)
        refreshRepeating()
    }

    fun setFlashMode(mode: CameraFlashMode) {
        currentFlashMode = mode
        refreshRepeating()
    }

    fun setProControls(controls: ProControls) {
        currentProControls = controls
        refreshRepeating()
    }

    fun focusAtSurface(
        sourceX: Float,
        sourceY: Float,
        sourceWidth: Int,
        sourceHeight: Int,
    ) {
        if (currentProControls.enabled || sourceWidth <= 0 || sourceHeight <= 0) return
        val streamChars = streamCharacteristics ?: return
        val controlChars = controlCharacteristics ?: streamChars
        val session = captureSession ?: return
        val builder = previewBuilder ?: return
        if ((controlChars.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0) <= 0) return

        val previewSize = activePreviewSize ?: Size(sourceWidth, sourceHeight)
        val sensorRect = effectivePreviewSensorRect(streamChars, previewSize) ?: return
        val nx = (sourceX / sourceWidth.toFloat()).coerceIn(0f, 1f)
        val ny = (sourceY / sourceHeight.toFloat()).coerceIn(0f, 1f)
        val x = (sensorRect.left + nx * sensorRect.width()).roundToInt()
        val y = (sensorRect.top + ny * sensorRect.height()).roundToInt()
        val half = max(32, min(sensorRect.width(), sensorRect.height()) / 24)
        val rect = Rect(
            (x - half).coerceIn(sensorRect.left, sensorRect.right - 2),
            (y - half).coerceIn(sensorRect.top, sensorRect.bottom - 2),
            (x + half).coerceIn(sensorRect.left + 2, sensorRect.right),
            (y + half).coerceIn(sensorRect.top + 2, sensorRect.bottom),
        )
        val metering = MeteringRectangle(rect, MeteringRectangle.METERING_WEIGHT_MAX - 1)

        cameraHandler.post {
            runCatching {
                builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
                builder.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(metering))
                if ((controlChars.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) ?: 0) > 0) {
                    builder.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(metering))
                }
                builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
                session.capture(builder.build(), null, cameraHandler)
                builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
                applyRepeatingSettings()
                session.setRepeatingRequest(builder.build(), null, cameraHandler)
            }
        }
    }

    suspend fun capturePhoto(
        displayRotationDegrees: Int,
        onFinalized: (PhotoCaptureResult) -> Unit = {},
    ): PhotoCaptureResult = withContext(Dispatchers.Main.immediate) {
        val route = activeRoute ?: return@withContext PhotoCaptureResult.Failure("No camera route is active")
        val device = cameraDevice ?: return@withContext PhotoCaptureResult.Failure("Camera device is unavailable")
        val session = captureSession ?: return@withContext PhotoCaptureResult.Failure("Capture session is unavailable")
        val reader = imageReader ?: return@withContext PhotoCaptureResult.Failure("Still-image output is unavailable")
        val chars = controlCharacteristics ?: return@withContext PhotoCaptureResult.Failure("Camera metadata is unavailable")
        val streamChars = streamCharacteristics ?: chars
        val candidate = activeCandidate
        val rotation = calculateImageOrientation(chars, displayRotationDegrees)
        val requested = requestedPhotoFormat
        val aspect = activeAspectRatio
        val quality = currentPhotoQuality

        runCatching {
            if (candidate.pipeline == StillPipeline.RAW_DNG) {
                val raw = captureRawFrame(device, session, reader, chars, streamChars, candidate.pixelMode)
                val processing = PhotoCaptureResult.Processing(
                    route.camera.id,
                    requested,
                    "RAW ${raw.width}x${raw.height} captured · writing DNG",
                )
                saveScope.launch {
                    val finalResult = runCatching {
                        val uri = saveDng(raw)
                        PhotoCaptureResult.Success(
                            uri = uri,
                            width = raw.width,
                            height = raw.height,
                            cameraId = route.camera.id,
                            requestedFormat = requested,
                            actualFormat = PhotoOutputFormat.DNG,
                            usedFormatFallback = requested != PhotoOutputFormat.DNG,
                            usedMaximumResolutionMode = candidate.pixelMode == SensorPixelMode.MAXIMUM_RESOLUTION,
                        )
                    }.getOrElse { error ->
                        runCatching { raw.image.close() }
                        PhotoCaptureResult.Failure(error.message ?: error::class.java.simpleName)
                    }
                    withContext(Dispatchers.Main.immediate) { onFinalized(finalResult) }
                }
                return@runCatching processing
            }

            val frame = captureStandardFrame(
                device,
                session,
                reader,
                chars,
                candidate.pipeline,
                rotation,
                candidate.pixelMode,
            )
            val willUpscale = candidate.upscale && frame.isYuv
            val processing = PhotoCaptureResult.Processing(
                route.camera.id,
                requested,
                if (willUpscale) {
                    "Captured ${frame.width}x${frame.height} · adaptive 2x upscale in background"
                } else if (candidate.pipeline == StillPipeline.SOFTWARE_HEIF) {
                    "Captured ${frame.width}x${frame.height} · encoding HEIF in background"
                } else {
                    "Captured ${frame.width}x${frame.height} · saving photo"
                },
            )

            saveScope.launch {
                val finalResult = runCatching {
                    finalizeStandardCapture(
                        frame = frame,
                        candidate = candidate,
                        aspect = aspect,
                        rotation = rotation,
                        quality = quality,
                        cameraId = route.camera.id,
                        requested = requested,
                    )
                }.getOrElse { error ->
                    PhotoCaptureResult.Failure(error.message ?: error::class.java.simpleName)
                }
                withContext(Dispatchers.Main.immediate) { onFinalized(finalResult) }
            }
            processing
        }.getOrElse { error ->
            PhotoCaptureResult.Failure(error.message ?: error::class.java.simpleName)
        }
    }

    private suspend fun finalizeStandardCapture(
        frame: CapturedImage,
        candidate: StillCandidate,
        aspect: PhotoAspectRatio,
        rotation: Int,
        quality: Int,
        cameraId: String,
        requested: PhotoOutputFormat,
    ): PhotoCaptureResult.Success {
        return when (candidate.pipeline) {
            StillPipeline.SOFTWARE_HEIF,
            StillPipeline.SOFTWARE_JPEG,
            -> {
                val cropped = centerCropI420(frame, aspect)
                val sourceWidth = cropped.width
                val sourceHeight = cropped.height
                val processed = if (candidate.upscale && adaptiveUpscalingEnabled) {
                    val up = UpscaleProcessor.scale2x(
                        UpscaleProcessor.I420Frame(cropped.bytes, cropped.width, cropped.height),
                    )
                    CapturedImage(up.bytes, up.width, up.height, isYuv = true)
                } else {
                    cropped
                }
                val actual = candidate.pipeline.outputFormat
                val saved = when (candidate.pipeline) {
                    StillPipeline.SOFTWARE_HEIF -> SavedImage(
                        saveYuvAsHeif(processed, rotation, quality),
                        processed.width,
                        processed.height,
                    )
                    StillPipeline.SOFTWARE_JPEG -> {
                        val rotated = UpscaleProcessor.rotate(
                            UpscaleProcessor.I420Frame(processed.bytes, processed.width, processed.height),
                            rotation,
                        )
                        SavedImage(
                            saveYuvAsJpeg(rotated, quality),
                            rotated.width,
                            rotated.height,
                        )
                    }
                    else -> error("Unexpected software pipeline")
                }
                PhotoCaptureResult.Success(
                    uri = saved.uri,
                    width = saved.width,
                    height = saved.height,
                    cameraId = cameraId,
                    requestedFormat = requested,
                    actualFormat = actual,
                    usedFormatFallback = requested != actual,
                    heifEncodingPath = if (candidate.pipeline == StillPipeline.SOFTWARE_HEIF) {
                        HeifEncodingPath.SOFTWARE_HEVC
                    } else {
                        HeifEncodingPath.NONE
                    },
                    sourceWidth = sourceWidth,
                    sourceHeight = sourceHeight,
                    upscaled = processed.width != sourceWidth || processed.height != sourceHeight,
                    usedMaximumResolutionMode = candidate.pixelMode == SensorPixelMode.MAXIMUM_RESOLUTION,
                )
            }

            StillPipeline.NATIVE_HEIF,
            StillPipeline.JPEG,
            -> {
                val actual = candidate.pipeline.outputFormat
                val uri = saveEncoded(frame.bytes, actual, frame.width, frame.height)
                PhotoCaptureResult.Success(
                    uri = uri,
                    width = frame.width,
                    height = frame.height,
                    cameraId = cameraId,
                    requestedFormat = requested,
                    actualFormat = actual,
                    usedFormatFallback = requested != actual,
                    heifEncodingPath = if (candidate.pipeline == StillPipeline.NATIVE_HEIF) {
                        HeifEncodingPath.NATIVE_CAMERA
                    } else {
                        HeifEncodingPath.NONE
                    },
                    usedMaximumResolutionMode = candidate.pixelMode == SensorPixelMode.MAXIMUM_RESOLUTION,
                )
            }

            StillPipeline.RAW_DNG -> error("RAW is finalized separately")
        }
    }

    private suspend fun captureStandardFrame(
        device: CameraDevice,
        session: CameraCaptureSession,
        reader: ImageReader,
        chars: CameraCharacteristics,
        pipeline: StillPipeline,
        rotation: Int,
        pixelMode: SensorPixelMode,
    ): CapturedImage = withTimeout(12_000) {
        suspendCancellableCoroutine { continuation ->
            reader.setOnImageAvailableListener({ source ->
                val image = runCatching { source.acquireNextImage() }.getOrNull()
                    ?: return@setOnImageAvailableListener
                image.use {
                    val captured = if (
                        pipeline == StillPipeline.SOFTWARE_HEIF ||
                        pipeline == StillPipeline.SOFTWARE_JPEG
                    ) {
                        packYuv420(it)
                    } else {
                        val plane = it.planes.firstOrNull() ?: error("Encoded image has no plane")
                        val buffer = plane.buffer
                        val bytes = ByteArray(buffer.remaining())
                        buffer.get(bytes)
                        CapturedImage(bytes, it.width, it.height, false)
                    }
                    if (continuation.isActive) continuation.resume(captured)
                }
                reader.setOnImageAvailableListener(null, null)
            }, cameraHandler)

            val request = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(reader.surface)
                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                setContinuousAfIfSupported(this, chars)
                applyRequestSettings(this, chars, includeTorch = false)
                applySensorPixelMode(this, pixelMode)
                if (pipeline != StillPipeline.SOFTWARE_HEIF && pipeline != StillPipeline.SOFTWARE_JPEG) {
                    set(CaptureRequest.JPEG_QUALITY, currentPhotoQuality.toByte())
                    set(CaptureRequest.JPEG_ORIENTATION, rotation)
                }
            }
            runCatching { session.capture(request.build(), null, cameraHandler) }
                .onFailure { error ->
                    reader.setOnImageAvailableListener(null, null)
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
            continuation.invokeOnCancellation { reader.setOnImageAvailableListener(null, null) }
        }
    }

    private suspend fun captureRawFrame(
        device: CameraDevice,
        session: CameraCaptureSession,
        reader: ImageReader,
        chars: CameraCharacteristics,
        streamChars: CameraCharacteristics,
        pixelMode: SensorPixelMode,
    ): RawFrame = withTimeout(12_000) {
        suspendCancellableCoroutine { continuation ->
            var image: Image? = null
            var result: TotalCaptureResult? = null

            fun finishIfReady() {
                val readyImage = image
                val readyResult = result
                if (readyImage != null && readyResult != null && continuation.isActive) {
                    image = null
                    reader.setOnImageAvailableListener(null, null)
                    continuation.resume(
                        RawFrame(readyImage, readyResult, streamChars, readyImage.width, readyImage.height),
                    )
                }
            }

            reader.setOnImageAvailableListener({ source ->
                val acquired = runCatching { source.acquireNextImage() }.getOrNull()
                    ?: return@setOnImageAvailableListener
                if (!continuation.isActive) {
                    acquired.close()
                    return@setOnImageAvailableListener
                }
                image?.close()
                image = acquired
                finishIfReady()
            }, cameraHandler)

            val request = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(reader.surface)
                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                applyRequestSettings(this, chars, includeTorch = false)
                applySensorPixelMode(this, pixelMode)
            }
            val callback = object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    captureResult: TotalCaptureResult,
                ) {
                    result = captureResult
                    finishIfReady()
                }

                override fun onCaptureFailed(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    failure: CaptureFailure,
                ) {
                    reader.setOnImageAvailableListener(null, null)
                    image?.close()
                    image = null
                    if (continuation.isActive) {
                        continuation.resumeWithException(
                            IllegalStateException("RAW capture failed: reason ${failure.reason}"),
                        )
                    }
                }
            }
            runCatching { session.capture(request.build(), callback, cameraHandler) }
                .onFailure { error ->
                    reader.setOnImageAvailableListener(null, null)
                    image?.close()
                    image = null
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
            continuation.invokeOnCancellation {
                reader.setOnImageAvailableListener(null, null)
                image?.close()
                image = null
            }
        }
    }

    fun unbind() = closeCurrent()

    private fun refreshRepeating() {
        val session = captureSession ?: return
        val builder = previewBuilder ?: return
        cameraHandler.post {
            runCatching {
                applyRepeatingSettings()
                session.setRepeatingRequest(builder.build(), null, cameraHandler)
            }
        }
    }

    private fun applyRepeatingSettings() {
        val builder = previewBuilder ?: return
        val chars = controlCharacteristics ?: return
        applyRequestSettings(builder, chars, includeTorch = true)
    }

    private fun applyRequestSettings(
        builder: CaptureRequest.Builder,
        chars: CameraCharacteristics,
        includeTorch: Boolean,
    ) {
        val streamChars = streamCharacteristics ?: chars
        val pro = currentProControls
        val isoRange = streamChars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        val exposureRange = streamChars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)

        if (pro.enabled && isoRange != null && exposureRange != null) {
            val iso = (pro.iso ?: isoRange.lower).coerceIn(isoRange.lower, isoRange.upper)
            val exposure = (pro.exposureTimeNs ?: exposureRange.lower)
                .coerceIn(exposureRange.lower, exposureRange.upper)
            val maxFrame = streamChars.get(CameraCharacteristics.SENSOR_INFO_MAX_FRAME_DURATION)
                ?: Long.MAX_VALUE
            val frameDuration = max(exposure, min(33_333_333L, maxFrame)).coerceAtMost(maxFrame)
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            builder.set(CaptureRequest.SENSOR_SENSITIVITY, iso)
            builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposure)
            builder.set(CaptureRequest.SENSOR_FRAME_DURATION, frameDuration)
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            streamChars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)
                ?.takeIf { it > 0f }
                ?.let { minFocus ->
                    builder.set(
                        CaptureRequest.LENS_FOCUS_DISTANCE,
                        (pro.focusDistanceDiopters ?: 0f).coerceIn(0f, minFocus),
                    )
                }
            builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
            builder.set(
                CaptureRequest.FLASH_MODE,
                if (
                    currentFlashMode == CameraFlashMode.TORCH && includeTorch &&
                    chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                ) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF,
            )
        } else {
            builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, currentExposureCompensation)
            setContinuousAfIfSupported(builder, chars)
            applyAutoFlash(builder, chars, includeTorch)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            streamChars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)?.let { range ->
                builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, currentZoomRatio.coerceIn(range.lower, range.upper))
            }
        } else {
            calculateLegacyZoomCrop(streamChars, currentZoomRatio)?.let {
                builder.set(CaptureRequest.SCALER_CROP_REGION, it)
            }
        }
    }

    private fun applySensorPixelMode(builder: CaptureRequest.Builder, mode: SensorPixelMode) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.set(
                CaptureRequest.SENSOR_PIXEL_MODE,
                if (mode == SensorPixelMode.MAXIMUM_RESOLUTION) {
                    CameraMetadata.SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION
                } else {
                    CameraMetadata.SENSOR_PIXEL_MODE_DEFAULT
                },
            )
        }
    }

    private fun applyAutoFlash(
        builder: CaptureRequest.Builder,
        chars: CameraCharacteristics,
        includeTorch: Boolean,
    ) {
        if (chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) != true) {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            return
        }
        when (currentFlashMode) {
            CameraFlashMode.OFF -> {
                builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
            }
            CameraFlashMode.AUTO -> {
                builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH)
                builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
            }
            CameraFlashMode.ON -> {
                builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH)
                builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
            }
            CameraFlashMode.TORCH -> {
                builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                builder.set(
                    CaptureRequest.FLASH_MODE,
                    if (includeTorch) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF,
                )
            }
        }
    }

    private fun buildStillCandidates(
        chars: CameraCharacteristics,
        route: ValuableCameraRoute,
        requested: PhotoOutputFormat,
        ratio: PhotoAspectRatio,
    ): List<StillCandidate> {
        val maps = ResolutionPolicy.maps(chars) ?: return emptyList()
        val result = mutableListOf<StillCandidate>()

        fun add(
            pipeline: StillPipeline,
            map: StreamConfigurationMap,
            pixelMode: SensorPixelMode,
            cropAfterCapture: Boolean,
            allowUpscale: Boolean,
        ) {
            val sizes = allOutputSizes(map, pipeline.imageFormat)
            val ranked = if (cropAfterCapture) {
                sizes.sortedByDescending(::pixels)
            } else {
                rankSizesForAspect(sizes, ratio)
            }
            ranked.take(MAX_SESSION_SIZE_ATTEMPTS_PER_PIPELINE).forEach { size ->
                result += StillCandidate(
                    pipeline = pipeline,
                    size = size,
                    pixelMode = pixelMode,
                    upscale = allowUpscale && adaptiveUpscalingEnabled && ResolutionPolicy.shouldAdaptiveUpscale(size),
                )
            }
        }

        val maxMap = maps.maximumResolution
        if (requested == PhotoOutputFormat.DNG) {
            if (route.access == CameraRouteAccess.DIRECT_CAMERA_DEVICE && route.camera.rawSupported) {
                if (maxMap != null) {
                    add(StillPipeline.RAW_DNG, maxMap, SensorPixelMode.MAXIMUM_RESOLUTION, true, false)
                }
                add(StillPipeline.RAW_DNG, maps.normal, SensorPixelMode.DEFAULT, true, false)
            }
            add(StillPipeline.JPEG, maps.normal, SensorPixelMode.DEFAULT, false, false)
            return result.distinctCandidates()
        }

        if (requested == PhotoOutputFormat.JPEG) {
            if (maxMap != null) {
                add(StillPipeline.JPEG, maxMap, SensorPixelMode.MAXIMUM_RESOLUTION, false, false)
            }
            val normalJpeg = allOutputSizes(maps.normal, ImageFormat.JPEG)
            val bestNormal = normalJpeg.maxByOrNull(::pixels)
            if (
                bestNormal != null &&
                adaptiveUpscalingEnabled &&
                ResolutionPolicy.shouldAdaptiveUpscale(bestNormal) &&
                allOutputSizes(maps.normal, ImageFormat.YUV_420_888).isNotEmpty()
            ) {
                add(StillPipeline.SOFTWARE_JPEG, maps.normal, SensorPixelMode.DEFAULT, true, true)
            }
            add(StillPipeline.JPEG, maps.normal, SensorPixelMode.DEFAULT, false, false)
            return result.distinctCandidates()
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (maxMap != null) {
                add(StillPipeline.NATIVE_HEIF, maxMap, SensorPixelMode.MAXIMUM_RESOLUTION, false, false)
            }
            add(StillPipeline.NATIVE_HEIF, maps.normal, SensorPixelMode.DEFAULT, false, false)
        }
        if (hasHevcEncoder()) {
            if (maxMap != null) {
                add(StillPipeline.SOFTWARE_HEIF, maxMap, SensorPixelMode.MAXIMUM_RESOLUTION, true, false)
            }
            add(StillPipeline.SOFTWARE_HEIF, maps.normal, SensorPixelMode.DEFAULT, true, true)
        }
        add(StillPipeline.JPEG, maps.normal, SensorPixelMode.DEFAULT, false, false)
        return result.distinctCandidates()
    }

    private fun List<StillCandidate>.distinctCandidates(): List<StillCandidate> =
        distinctBy { "${it.pipeline}:${it.pixelMode}:${it.size.width}x${it.size.height}:${it.upscale}" }

    private fun allOutputSizes(map: StreamConfigurationMap, format: Int): List<Size> {
        val regular = runCatching { map.getOutputSizes(format) }.getOrNull().orEmpty()
        val high = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            runCatching { map.getHighResolutionOutputSizes(format) }.getOrNull().orEmpty()
        } else emptyArray()
        return (regular.asList() + high.asList()).distinctBy { "${it.width}x${it.height}" }
    }

    private fun rankSizesForAspect(sizes: List<Size>, ratio: PhotoAspectRatio): List<Size> {
        val matching = sizes.filter { ratioDifference(it, ratio) <= STILL_ASPECT_TOLERANCE }
            .sortedByDescending(::pixels)
        val keys = matching.mapTo(mutableSetOf()) { "${it.width}x${it.height}" }
        val remaining = sizes.filterNot { "${it.width}x${it.height}" in keys }
            .sortedWith(compareBy<Size> { ratioDifference(it, ratio) }.thenByDescending(::pixels))
        return matching + remaining
    }

    private fun choosePreviewSize(chars: CameraCharacteristics): Size {
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val sizes = runCatching { map?.getOutputSizes(ImageFormat.PRIVATE) }.getOrNull().orEmpty()
            .ifEmpty { runCatching { map?.getOutputSizes(SurfaceTexture::class.java) }.getOrNull().orEmpty() }
        if (sizes.isEmpty()) return Size(1440, 1080)
        val active = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        val targetAspect = active?.let { it.width().toFloat() / it.height().toFloat() } ?: 4f / 3f
        val bounded = sizes.filter {
            pixels(it) <= PREVIEW_MAX_PIXELS && max(it.width, it.height) <= PREVIEW_MAX_LONG_EDGE
        }.ifEmpty { sizes.toList() }
        val native = bounded.filter {
            abs(it.width.toFloat() / it.height.toFloat() - targetAspect) <= PREVIEW_ASPECT_TOLERANCE
        }
        return native.maxByOrNull(::pixels)
            ?: bounded.minWithOrNull(
                compareBy<Size> { abs(it.width.toFloat() / it.height.toFloat() - targetAspect) }
                    .thenByDescending(::pixels),
            )
            ?: sizes.first()
    }

    private fun hasHevcEncoder(): Boolean = runCatching {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
            info.isEncoder && info.supportedTypes.any {
                it.equals(MediaFormat.MIMETYPE_VIDEO_HEVC, ignoreCase = true)
            }
        }
    }.getOrDefault(false)

    private fun packYuv420(image: Image): CapturedImage {
        require(image.format == ImageFormat.YUV_420_888)
        val width = image.width and -2
        val height = image.height and -2
        val uvWidth = width / 2
        val uvHeight = height / 2
        val out = ByteArray(width * height + uvWidth * uvHeight * 2)
        var offset = 0
        offset = copyPlane(image.planes[0], width, height, out, offset)
        offset = copyPlane(image.planes[1], uvWidth, uvHeight, out, offset)
        copyPlane(image.planes[2], uvWidth, uvHeight, out, offset)
        return CapturedImage(out, width, height, true)
    }

    private fun copyPlane(
        plane: Image.Plane,
        width: Int,
        height: Int,
        destination: ByteArray,
        destinationOffset: Int,
    ): Int {
        val buffer = plane.buffer.duplicate()
        val base = buffer.position()
        val limit = buffer.limit()
        var dst = destinationOffset
        if (plane.pixelStride == 1) {
            for (row in 0 until height) {
                val start = base + row * plane.rowStride
                if (start + width <= limit) {
                    buffer.position(start)
                    buffer.get(destination, dst, width)
                } else destination.fill(0, dst, dst + width)
                dst += width
            }
            return dst
        }
        for (row in 0 until height) {
            val start = base + row * plane.rowStride
            for (column in 0 until width) {
                val index = start + column * plane.pixelStride
                destination[dst++] = if (index < limit) buffer.get(index) else 0
            }
        }
        return dst
    }

    private fun centerCropI420(frame: CapturedImage, ratio: PhotoAspectRatio): CapturedImage {
        if (!frame.isYuv) return frame
        val sourceWidth = frame.width and -2
        val sourceHeight = frame.height and -2
        val target = ratio.width.toFloat() / ratio.height.toFloat()
        val source = sourceWidth.toFloat() / sourceHeight.toFloat()
        if (abs(source - target) < 0.004f) return frame
        var cropWidth = sourceWidth
        var cropHeight = sourceHeight
        if (source > target) cropWidth = (sourceHeight * target).roundToInt()
        else cropHeight = (sourceWidth / target).roundToInt()
        cropWidth = (cropWidth and -2).coerceAtLeast(2)
        cropHeight = (cropHeight and -2).coerceAtLeast(2)
        val left = (((sourceWidth - cropWidth) / 2) and -2).coerceAtLeast(0)
        val top = (((sourceHeight - cropHeight) / 2) and -2).coerceAtLeast(0)
        val out = ByteArray(cropWidth * cropHeight * 3 / 2)
        copyI420Plane(frame.bytes, 0, sourceWidth, left, top, cropWidth, cropHeight, out, 0)
        val sourceUvW = sourceWidth / 2
        val sourceUvH = sourceHeight / 2
        val cropUvW = cropWidth / 2
        val cropUvH = cropHeight / 2
        val sourceU = sourceWidth * sourceHeight
        val sourceV = sourceU + sourceUvW * sourceUvH
        val outU = cropWidth * cropHeight
        val outV = outU + cropUvW * cropUvH
        copyI420Plane(frame.bytes, sourceU, sourceUvW, left / 2, top / 2, cropUvW, cropUvH, out, outU)
        copyI420Plane(frame.bytes, sourceV, sourceUvW, left / 2, top / 2, cropUvW, cropUvH, out, outV)
        return CapturedImage(out, cropWidth, cropHeight, true)
    }

    private fun copyI420Plane(
        source: ByteArray,
        sourceOffset: Int,
        sourceStride: Int,
        left: Int,
        top: Int,
        width: Int,
        height: Int,
        destination: ByteArray,
        destinationOffset: Int,
    ) {
        for (row in 0 until height) {
            val from = sourceOffset + (top + row) * sourceStride + left
            val to = destinationOffset + row * width
            source.copyInto(destination, to, from, from + width)
        }
    }

    private suspend fun saveYuvAsHeif(frame: CapturedImage, rotation: Int, quality: Int): Uri {
        val uri = createMediaStoreDestination(PhotoOutputFormat.HEIF, frame.width, frame.height)
        val resolver = appContext.contentResolver
        try {
            val pfd = resolver.openFileDescriptor(uri, "rw")
                ?: error("MediaStore HEIF file descriptor is unavailable")
            pfd.use { descriptor ->
                HeifWriter.Builder(
                    descriptor.fileDescriptor,
                    frame.width,
                    frame.height,
                    HeifWriter.INPUT_MODE_BUFFER,
                )
                    .setMaxImages(1)
                    .setPrimaryIndex(0)
                    .setQuality(quality.coerceIn(1, 100))
                    .setRotation(rotation)
                    .setGridEnabled(true)
                    .build()
                    .use { writer ->
                        writer.start()
                        writer.addYuvBuffer(ImageFormat.YUV_420_888, frame.bytes)
                        writer.stop(20_000)
                    }
            }
            publishMediaStoreItem(uri)
            return uri
        } catch (error: Throwable) {
            resolver.delete(uri, null, null)
            throw error
        }
    }

    private suspend fun saveYuvAsJpeg(frame: UpscaleProcessor.I420Frame, quality: Int): Uri {
        val uri = createMediaStoreDestination(PhotoOutputFormat.JPEG, frame.width, frame.height)
        val resolver = appContext.contentResolver
        try {
            resolver.openOutputStream(uri, "w")?.use { output ->
                UpscaleProcessor.writeJpeg(frame, quality, output)
                output.flush()
            } ?: error("MediaStore JPEG output stream is unavailable")
            publishMediaStoreItem(uri)
            return uri
        } catch (error: Throwable) {
            resolver.delete(uri, null, null)
            throw error
        }
    }

    private suspend fun saveEncoded(
        bytes: ByteArray,
        format: PhotoOutputFormat,
        width: Int,
        height: Int,
    ): Uri {
        val uri = createMediaStoreDestination(format, width, height)
        val resolver = appContext.contentResolver
        try {
            resolver.openOutputStream(uri, "w")?.use {
                it.write(bytes)
                it.flush()
            } ?: error("MediaStore output stream is unavailable")
            publishMediaStoreItem(uri)
            return uri
        } catch (error: Throwable) {
            resolver.delete(uri, null, null)
            throw error
        }
    }

    private fun saveDng(frame: RawFrame): Uri {
        val uri = createMediaStoreDestination(PhotoOutputFormat.DNG, frame.width, frame.height)
        val resolver = appContext.contentResolver
        try {
            resolver.openOutputStream(uri, "w")?.use { output ->
                frame.image.use { image ->
                    DngCreator(frame.characteristics, frame.result).use { it.writeImage(output, image) }
                }
            } ?: error("MediaStore DNG output stream is unavailable")
            publishMediaStoreItem(uri)
            return uri
        } catch (error: Throwable) {
            runCatching { frame.image.close() }
            resolver.delete(uri, null, null)
            throw error
        }
    }

    private fun createMediaStoreDestination(
        format: PhotoOutputFormat,
        width: Int,
        height: Int,
    ): Uri {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        val extension = when (format) {
            PhotoOutputFormat.HEIF -> "heic"
            PhotoOutputFormat.JPEG -> "jpg"
            PhotoOutputFormat.DNG -> "dng"
        }
        val mime = when (format) {
            PhotoOutputFormat.HEIF -> "image/heic"
            PhotoOutputFormat.JPEG -> "image/jpeg"
            PhotoOutputFormat.DNG -> "image/x-adobe-dng"
        }
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "OMNI_${timestamp}.$extension")
            put(MediaStore.Images.Media.MIME_TYPE, mime)
            put(MediaStore.Images.Media.WIDTH, width)
            put(MediaStore.Images.Media.HEIGHT, height)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/OmniCam")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        return appContext.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("MediaStore could not create a destination")
    }

    private fun publishMediaStoreItem(uri: Uri) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appContext.contentResolver.update(
                uri,
                ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
                null,
                null,
            )
        }
    }

    private fun calculateImageOrientation(chars: CameraCharacteristics, displayRotationDegrees: Int): Int {
        val sensor = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        return if (chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT) {
            (sensor + displayRotationDegrees) % 360
        } else {
            (sensor - displayRotationDegrees + 360) % 360
        }
    }

    private fun calculateLegacyZoomCrop(chars: CameraCharacteristics, zoomRatio: Float): Rect? {
        val active = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return null
        if (zoomRatio <= 1f) return Rect(active)
        val zoom = zoomRatio.coerceIn(
            1f,
            chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f,
        )
        val width = (active.width() / zoom).roundToInt().coerceAtLeast(2)
        val height = (active.height() / zoom).roundToInt().coerceAtLeast(2)
        val left = active.left + (active.width() - width) / 2
        val top = active.top + (active.height() - height) / 2
        return Rect(left, top, left + width, top + height)
    }

    private fun effectivePreviewSensorRect(chars: CameraCharacteristics, previewSize: Size): Rect? {
        val active = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return null
        val base = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            calculateLegacyZoomCrop(chars, currentZoomRatio) ?: Rect(active)
        } else Rect(active)
        return centerCropRect(base, previewSize.width.toFloat() / previewSize.height.toFloat())
    }

    private fun centerCropRect(size: Size, ratio: PhotoAspectRatio): RectF {
        val w = size.width.toFloat()
        val h = size.height.toFloat()
        val target = ratio.width.toFloat() / ratio.height.toFloat()
        val source = w / h
        return if (source > target) {
            val cropW = h * target
            val left = (w - cropW) / 2f
            RectF(left, 0f, left + cropW, h)
        } else {
            val cropH = w / target
            val top = (h - cropH) / 2f
            RectF(0f, top, w, top + cropH)
        }
    }

    private fun centerCropRect(source: Rect, targetAspect: Float): Rect {
        val current = source.width().toFloat() / source.height().toFloat()
        if (abs(current - targetAspect) < 0.004f) return Rect(source)
        return if (current > targetAspect) {
            val width = (source.height() * targetAspect).roundToInt().coerceAtLeast(2)
            val left = source.left + (source.width() - width) / 2
            Rect(left, source.top, left + width, source.bottom)
        } else {
            val height = (source.width() / targetAspect).roundToInt().coerceAtLeast(2)
            val top = source.top + (source.height() - height) / 2
            Rect(source.left, top, source.right, top + height)
        }
    }

    private fun ratioDifference(size: Size, ratio: PhotoAspectRatio): Float = abs(
        size.width.toFloat() / size.height.toFloat() - ratio.width.toFloat() / ratio.height.toFloat(),
    )

    private fun pixels(size: Size): Long = size.width.toLong() * size.height.toLong()

    private fun getMinZoom(chars: CameraCharacteristics): Float =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            chars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)?.lower ?: 1f
        } else 1f

    private fun getMaxZoom(chars: CameraCharacteristics): Float =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            chars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)?.upper
                ?: chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM)
                ?: 1f
        } else chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f

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

    private fun resolveRoute(route: ValuableCameraRoute): RouteIds = when (route.access) {
        CameraRouteAccess.DIRECT_CAMERA_DEVICE -> RouteIds(route.camera.id, null)
        CameraRouteAccess.PHYSICAL_VIA_LOGICAL -> RouteIds(
            route.logicalCameraIds.firstOrNull()
                ?: error("Physical camera ${route.camera.id} has no logical parent"),
            route.camera.id,
        )
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

    private suspend fun createSession(
        device: CameraDevice,
        preview: Surface,
        still: Surface,
        physicalCameraId: String?,
    ): CameraCaptureSession = suspendCancellableCoroutine { continuation ->
        val callback = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                if (continuation.isActive) continuation.resume(session) else session.close()
            }
            override fun onConfigureFailed(session: CameraCaptureSession) {
                session.close()
                if (continuation.isActive) continuation.resumeWithException(
                    IllegalStateException("Camera2 preview/capture session configuration failed"),
                )
            }
        }
        try {
            @Suppress("DEPRECATION")
            if (physicalCameraId == null) {
                device.createCaptureSession(listOf(preview, still), callback, cameraHandler)
            } else {
                val previewOutput = OutputConfiguration(preview).apply { setPhysicalCameraId(physicalCameraId) }
                val stillOutput = OutputConfiguration(still).apply { setPhysicalCameraId(physicalCameraId) }
                device.createCaptureSessionByOutputConfigurations(
                    listOf(previewOutput, stillOutput),
                    callback,
                    cameraHandler,
                )
            }
        } catch (error: Throwable) {
            if (continuation.isActive) continuation.resumeWithException(error)
        }
    }

    private fun closeCurrent() {
        runCatching { captureSession?.stopRepeating() }
        runCatching { captureSession?.abortCaptures() }
        runCatching { captureSession?.close() }
        runCatching { cameraDevice?.close() }
        runCatching { imageReader?.close() }
        captureSession = null
        cameraDevice = null
        imageReader = null
        previewBuilder = null
        controlCharacteristics = null
        streamCharacteristics = null
        activeRoute = null
        activePreviewSize = null
    }

    private enum class StillPipeline(val imageFormat: Int, val outputFormat: PhotoOutputFormat) {
        NATIVE_HEIF(ImageFormat.HEIC, PhotoOutputFormat.HEIF),
        SOFTWARE_HEIF(ImageFormat.YUV_420_888, PhotoOutputFormat.HEIF),
        SOFTWARE_JPEG(ImageFormat.YUV_420_888, PhotoOutputFormat.JPEG),
        JPEG(ImageFormat.JPEG, PhotoOutputFormat.JPEG),
        RAW_DNG(ImageFormat.RAW_SENSOR, PhotoOutputFormat.DNG),
    }

    private enum class SensorPixelMode { DEFAULT, MAXIMUM_RESOLUTION }

    private data class StillCandidate(
        val pipeline: StillPipeline,
        val size: Size,
        val pixelMode: SensorPixelMode = SensorPixelMode.DEFAULT,
        val upscale: Boolean = false,
    )

    private data class CapturedImage(
        val bytes: ByteArray,
        val width: Int,
        val height: Int,
        val isYuv: Boolean,
    )

    private data class SavedImage(val uri: Uri, val width: Int, val height: Int)

    private data class RawFrame(
        val image: Image,
        val result: TotalCaptureResult,
        val characteristics: CameraCharacteristics,
        val width: Int,
        val height: Int,
    )

    private data class RouteIds(val deviceId: String, val physicalId: String?)

    private companion object {
        const val PREVIEW_MAX_PIXELS = 2_500_000L
        const val PREVIEW_MAX_LONG_EDGE = 1920
        const val PREVIEW_ASPECT_TOLERANCE = 0.05f
        const val STILL_ASPECT_TOLERANCE = 0.035f
        const val MAX_SESSION_SIZE_ATTEMPTS_PER_PIPELINE = 3
    }
}
