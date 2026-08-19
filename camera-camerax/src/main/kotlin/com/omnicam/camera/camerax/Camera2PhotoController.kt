package com.omnicam.camera.camerax

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.OutputConfiguration
import android.media.ImageReader
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import android.util.Size
import android.view.Surface
import android.view.TextureView
import com.omnicam.camera.capability.CameraRouteAccess
import com.omnicam.camera.capability.ValuableCameraRoute
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

enum class PhotoAspectRatio(val width: Int, val height: Int) {
    FOUR_THREE(4, 3),
    SIXTEEN_NINE(16, 9),
}

enum class PhotoOutputFormat {
    HEIF,
    JPEG,
}

enum class CameraFlashMode {
    OFF,
    AUTO,
    ON,
    TORCH,
}

sealed interface PhotoCaptureResult {
    data class Success(
        val uri: Uri,
        val width: Int,
        val height: Int,
        val cameraId: String,
        val requestedFormat: PhotoOutputFormat,
        val actualFormat: PhotoOutputFormat,
        val usedFormatFallback: Boolean,
    ) : PhotoCaptureResult

    data class Failure(val message: String) : PhotoCaptureResult
}

/**
 * Exact Camera2 photo controller used by the main OmniCam camera UI.
 *
 * The controller intentionally keeps direct Camera2 and physical-via-logical routing because
 * vendor auxiliary cameras can be filtered by higher-level camera libraries. HEIF is requested
 * directly from the camera HAL when the active route advertises ImageFormat.HEIC; JPEG is used as
 * the compatibility fallback rather than transcoding a JPEG into HEIF and losing detail twice.
 */
class Camera2PhotoController(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val cameraManager = appContext.getSystemService(CameraManager::class.java)
    private val cameraThread = HandlerThread("OmniCam-Photo-Camera2").apply { start() }
    private val cameraHandler = Handler(cameraThread.looper)

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var previewSurface: Surface? = null
    private var imageReader: ImageReader? = null
    private var previewBuilder: CaptureRequest.Builder? = null
    private var controlCharacteristics: CameraCharacteristics? = null
    private var streamCharacteristics: CameraCharacteristics? = null
    private var activeRoute: ValuableCameraRoute? = null
    private var activeTextureView: TextureView? = null
    private var activePreviewSize: Size? = null
    private var activeImageFormat: Int = ImageFormat.JPEG
    private var requestedPhotoFormat: PhotoOutputFormat = PhotoOutputFormat.HEIF

    private var currentZoomRatio = 1f
    private var currentExposureCompensation = 0
    private var currentFlashMode = CameraFlashMode.OFF
    private var currentPhotoQuality = 100

    @SuppressLint("MissingPermission")
    suspend fun bind(
        textureView: TextureView,
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

        val preferredImageFormat = runCatching {
            val streamCameraId = if (route.access == CameraRouteAccess.PHYSICAL_VIA_LOGICAL) {
                route.camera.id
            } else {
                route.camera.id
            }
            val chars = cameraManager.getCameraCharacteristics(streamCameraId)
            chooseImageFormat(chars, outputFormat)
        }.getOrDefault(ImageFormat.JPEG)

        val preferredResult = bindAttempt(
            textureView = textureView,
            route = route,
            aspectRatio = aspectRatio,
            imageFormat = preferredImageFormat,
        )

        if (
            preferredResult is CameraBindResult.Failure &&
            outputFormat == PhotoOutputFormat.HEIF &&
            preferredImageFormat == ImageFormat.HEIC
        ) {
            closeCurrent()
            bindAttempt(
                textureView = textureView,
                route = route,
                aspectRatio = aspectRatio,
                imageFormat = ImageFormat.JPEG,
            )
        } else {
            preferredResult
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun bindAttempt(
        textureView: TextureView,
        route: ValuableCameraRoute,
        aspectRatio: PhotoAspectRatio,
        imageFormat: Int,
    ): CameraBindResult {
        return runCatching {
            val physicalId = route.camera.id.takeIf {
                route.access == CameraRouteAccess.PHYSICAL_VIA_LOGICAL
            }
            val deviceId = when (route.access) {
                CameraRouteAccess.DIRECT_CAMERA_DEVICE -> route.camera.id
                CameraRouteAccess.PHYSICAL_VIA_LOGICAL -> route.logicalCameraIds.firstOrNull()
                    ?: error("Physical camera ${route.camera.id} has no logical parent")
            }

            val streamChars = cameraManager.getCameraCharacteristics(physicalId ?: deviceId)
            val controlChars = cameraManager.getCameraCharacteristics(deviceId)
            streamCharacteristics = streamChars
            controlCharacteristics = controlChars

            val surfaceTexture = awaitSurfaceTexture(textureView)
            val previewSize = choosePreviewSize(streamChars, aspectRatio)
            val captureSize = chooseCaptureSize(streamChars, aspectRatio, imageFormat)
            surfaceTexture.setDefaultBufferSize(previewSize.width, previewSize.height)
            activePreviewSize = previewSize
            configureTransform(textureView, previewSize, streamChars)
            textureView.post { configureTransform(textureView, previewSize, streamChars) }

            val preview = Surface(surfaceTexture)
            val reader = ImageReader.newInstance(
                captureSize.width,
                captureSize.height,
                imageFormat,
                2,
            )

            previewSurface = preview
            imageReader = reader
            activeTextureView = textureView
            activeImageFormat = imageFormat

            val device = openCamera(deviceId)
            cameraDevice = device
            val session = createSession(
                device = device,
                preview = preview,
                still = reader.surface,
                physicalCameraId = physicalId,
            )
            captureSession = session

            val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(preview)
                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                setContinuousAfIfSupported(this, controlChars)
            }
            previewBuilder = builder
            activeRoute = route
            applyRepeatingSettings()
            session.setRepeatingRequest(builder.build(), null, cameraHandler)

            CameraBindResult.Success(
                requestedCameraId = route.camera.id,
                actualCameraId = if (physicalId == null) deviceId else "$deviceId->$physicalId",
                minZoomRatio = getMinZoom(streamChars),
                maxZoomRatio = getMaxZoom(streamChars),
            )
        }.getOrElse { error ->
            closeCurrent()
            CameraBindResult.Failure(
                cameraId = route.camera.id,
                reason = error.message ?: error::class.java.simpleName,
            )
        }
    }

    fun updatePreviewTransform(textureView: TextureView) {
        val previewSize = activePreviewSize ?: return
        val chars = streamCharacteristics ?: return
        configureTransform(textureView, previewSize, chars)
    }

    fun setZoomRatio(ratio: Float) {
        val chars = streamCharacteristics ?: return
        currentZoomRatio = ratio.coerceIn(getMinZoom(chars), getMaxZoom(chars))
        refreshRepeating()
    }

    fun setExposureCompensation(index: Int) {
        val range = controlCharacteristics
            ?.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
            ?: return
        currentExposureCompensation = index.coerceIn(range.lower, range.upper)
        refreshRepeating()
    }

    fun setFlashMode(mode: CameraFlashMode) {
        currentFlashMode = mode
        refreshRepeating()
    }

    fun focusAt(normalizedX: Float, normalizedY: Float) {
        val chars = controlCharacteristics ?: return
        val session = captureSession ?: return
        val builder = previewBuilder ?: return
        val activeArray = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return
        if ((chars.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0) <= 0) return

        val x = (activeArray.left + normalizedX.coerceIn(0f, 1f) * activeArray.width()).toInt()
        val y = (activeArray.top + normalizedY.coerceIn(0f, 1f) * activeArray.height()).toInt()
        val half = max(40, minOf(activeArray.width(), activeArray.height()) / 14)
        val focusRect = Rect(
            (x - half).coerceIn(activeArray.left, activeArray.right - 2),
            (y - half).coerceIn(activeArray.top, activeArray.bottom - 2),
            (x + half).coerceIn(activeArray.left + 2, activeArray.right),
            (y + half).coerceIn(activeArray.top + 2, activeArray.bottom),
        )
        val metering = MeteringRectangle(
            focusRect,
            MeteringRectangle.METERING_WEIGHT_MAX - 1,
        )

        cameraHandler.post {
            runCatching {
                builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
                builder.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(metering))
                if ((chars.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) ?: 0) > 0) {
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

    suspend fun capturePhoto(displayRotationDegrees: Int): PhotoCaptureResult =
        withContext(Dispatchers.Main.immediate) {
            val route = activeRoute
                ?: return@withContext PhotoCaptureResult.Failure("No camera route is active")
            val device = cameraDevice
                ?: return@withContext PhotoCaptureResult.Failure("Camera device is unavailable")
            val session = captureSession
                ?: return@withContext PhotoCaptureResult.Failure("Capture session is unavailable")
            val reader = imageReader
                ?: return@withContext PhotoCaptureResult.Failure("Still-image output is unavailable")
            val chars = controlCharacteristics
                ?: return@withContext PhotoCaptureResult.Failure("Camera metadata is unavailable")

            runCatching {
                val frame = withTimeout(10_000) {
                    suspendCancellableCoroutine<CapturedImage> { continuation ->
                        reader.setOnImageAvailableListener({ source ->
                            val image = runCatching { source.acquireNextImage() }.getOrNull()
                                ?: return@setOnImageAvailableListener
                            image.use {
                                val plane = it.planes.firstOrNull()
                                    ?: error("Encoded image contains no readable plane")
                                val buffer = plane.buffer
                                val data = ByteArray(buffer.remaining())
                                buffer.get(data)
                                if (continuation.isActive) {
                                    continuation.resume(
                                        CapturedImage(
                                            bytes = data,
                                            width = it.width,
                                            height = it.height,
                                        ),
                                    )
                                }
                            }
                            reader.setOnImageAvailableListener(null, null)
                        }, cameraHandler)

                        val request = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                            addTarget(reader.surface)
                            set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                            setContinuousAfIfSupported(this, chars)
                            applyRequestSettings(this, chars, includeTorch = false)
                            set(CaptureRequest.JPEG_QUALITY, currentPhotoQuality.toByte())
                            set(
                                CaptureRequest.JPEG_ORIENTATION,
                                calculateJpegOrientation(chars, displayRotationDegrees),
                            )
                        }

                        runCatching {
                            session.capture(request.build(), null, cameraHandler)
                        }.onFailure { error ->
                            reader.setOnImageAvailableListener(null, null)
                            if (continuation.isActive) continuation.resumeWithException(error)
                        }

                        continuation.invokeOnCancellation {
                            reader.setOnImageAvailableListener(null, null)
                        }
                    }
                }

                val actualFormat = if (activeImageFormat == ImageFormat.HEIC) {
                    PhotoOutputFormat.HEIF
                } else {
                    PhotoOutputFormat.JPEG
                }
                val uri = saveEncoded(frame.bytes, actualFormat)
                PhotoCaptureResult.Success(
                    uri = uri,
                    width = frame.width,
                    height = frame.height,
                    cameraId = route.camera.id,
                    requestedFormat = requestedPhotoFormat,
                    actualFormat = actualFormat,
                    usedFormatFallback = requestedPhotoFormat != actualFormat,
                )
            }.getOrElse { error ->
                PhotoCaptureResult.Failure(error.message ?: error::class.java.simpleName)
            }
        }

    fun unbind() {
        closeCurrent()
    }

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
        builder.set(
            CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION,
            currentExposureCompensation,
        )

        val streamChars = streamCharacteristics ?: chars
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            streamChars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)?.let { range ->
                builder.set(
                    CaptureRequest.CONTROL_ZOOM_RATIO,
                    currentZoomRatio.coerceIn(range.lower, range.upper),
                )
            }
        } else {
            applyLegacyCrop(builder, streamChars, currentZoomRatio)
        }

        val hasFlash = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        if (!hasFlash) {
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

    private fun applyLegacyCrop(
        builder: CaptureRequest.Builder,
        chars: CameraCharacteristics,
        zoomRatio: Float,
    ) {
        if (zoomRatio <= 1f) return
        val active = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return
        val maxZoom = chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f
        val zoom = zoomRatio.coerceIn(1f, maxZoom)
        val cropWidth = (active.width() / zoom).toInt()
        val cropHeight = (active.height() / zoom).toInt()
        val left = active.left + (active.width() - cropWidth) / 2
        val top = active.top + (active.height() - cropHeight) / 2
        builder.set(
            CaptureRequest.SCALER_CROP_REGION,
            Rect(left, top, left + cropWidth, top + cropHeight),
        )
    }

    private fun getMinZoom(chars: CameraCharacteristics): Float =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            chars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)?.lower ?: 1f
        } else {
            1f
        }

    private fun getMaxZoom(chars: CameraCharacteristics): Float =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            chars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)?.upper
                ?: chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM)
                ?: 1f
        } else {
            chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f
        }

    private fun calculateJpegOrientation(
        chars: CameraCharacteristics,
        displayRotationDegrees: Int,
    ): Int {
        val sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        val facing = chars.get(CameraCharacteristics.LENS_FACING)
        return if (facing == CameraCharacteristics.LENS_FACING_FRONT) {
            (sensorOrientation + displayRotationDegrees) % 360
        } else {
            (sensorOrientation - displayRotationDegrees + 360) % 360
        }
    }

    private suspend fun saveEncoded(
        bytes: ByteArray,
        format: PhotoOutputFormat,
    ): Uri = withContext(Dispatchers.IO) {
        val resolver = appContext.contentResolver
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        val extension = if (format == PhotoOutputFormat.HEIF) "heic" else "jpg"
        val mimeType = if (format == PhotoOutputFormat.HEIF) "image/heic" else "image/jpeg"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "OMNI_${timestamp}.$extension")
            put(MediaStore.Images.Media.MIME_TYPE, mimeType)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(
                    MediaStore.Images.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_DCIM + "/OmniCam",
                )
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }

        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("MediaStore could not create a destination")

        try {
            resolver.openOutputStream(uri, "w")?.use { output ->
                output.write(bytes)
                output.flush()
            } ?: error("MediaStore output stream is unavailable")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                resolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
                    null,
                    null,
                )
            }
            uri
        } catch (error: Throwable) {
            resolver.delete(uri, null, null)
            throw error
        }
    }

    private fun chooseImageFormat(
        chars: CameraCharacteristics,
        requested: PhotoOutputFormat,
    ): Int {
        if (requested == PhotoOutputFormat.JPEG || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return ImageFormat.JPEG
        }
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: return ImageFormat.JPEG
        val heicSizes = runCatching { map.getOutputSizes(ImageFormat.HEIC) }.getOrNull().orEmpty()
        return if (heicSizes.isNotEmpty()) ImageFormat.HEIC else ImageFormat.JPEG
    }

    private fun choosePreviewSize(
        chars: CameraCharacteristics,
        ratio: PhotoAspectRatio,
    ): Size {
        val sizes = chars
            .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?.getOutputSizes(SurfaceTexture::class.java)
            .orEmpty()
        if (sizes.isEmpty()) return Size(1280, 960)

        val maxPixels = 1920L * 1080L
        val matching = sizes.filter { size ->
            ratioDifference(size, ratio) < 0.035f && pixels(size) <= maxPixels
        }
        return matching.maxByOrNull(::pixels)
            ?: sizes.filter { pixels(it) <= maxPixels }.minByOrNull { ratioDifference(it, ratio) }
            ?: sizes.minBy { ratioDifference(it, ratio) }
    }

    private fun chooseCaptureSize(
        chars: CameraCharacteristics,
        ratio: PhotoAspectRatio,
        format: Int,
    ): Size {
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val sizes = runCatching { map?.getOutputSizes(format) }.getOrNull().orEmpty()
        if (sizes.isEmpty()) {
            val fallback = map?.getOutputSizes(ImageFormat.JPEG).orEmpty()
            if (fallback.isEmpty()) return Size(1920, 1440)
            val matchingFallback = fallback.filter { ratioDifference(it, ratio) < 0.035f }
            return (matchingFallback.ifEmpty { fallback.toList() }).maxBy(::pixels)
        }
        val matching = sizes.filter { ratioDifference(it, ratio) < 0.035f }
        return (matching.ifEmpty { sizes.toList() }).maxBy(::pixels)
    }

    private fun ratioDifference(size: Size, ratio: PhotoAspectRatio): Float {
        val actual = size.width.toFloat() / size.height.toFloat()
        val target = ratio.width.toFloat() / ratio.height.toFloat()
        return abs(actual - target)
    }

    private fun pixels(size: Size): Long = size.width.toLong() * size.height.toLong()

    /**
     * Correct the TextureView's default non-uniform scaling. This follows Android's Camera2
     * resizable-surface guidance: reverse the implicit X/Y stretch, apply one uniform scale, then
     * compensate for display rotation. The result may crop, but it never stretches faces/objects.
     */
    private fun configureTransform(
        textureView: TextureView,
        previewSize: Size,
        chars: CameraCharacteristics,
    ) {
        if (textureView.width <= 0 || textureView.height <= 0) return

        val surfaceRotation = textureView.display?.rotation ?: Surface.ROTATION_0
        val surfaceRotationDegrees = when (surfaceRotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        val windowSize = Size(textureView.width, textureView.height)
        val sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        val isRotationRequired = computeRelativeRotation(chars, surfaceRotationDegrees) % 180 != 0

        var scaleX: Float
        var scaleY: Float
        if (sensorOrientation == 0) {
            scaleX = if (!isRotationRequired) {
                windowSize.width.toFloat() / previewSize.height
            } else {
                windowSize.width.toFloat() / previewSize.width
            }
            scaleY = if (!isRotationRequired) {
                windowSize.height.toFloat() / previewSize.width
            } else {
                windowSize.height.toFloat() / previewSize.height
            }
        } else {
            scaleX = if (isRotationRequired) {
                windowSize.width.toFloat() / previewSize.height
            } else {
                windowSize.width.toFloat() / previewSize.width
            }
            scaleY = if (isRotationRequired) {
                windowSize.height.toFloat() / previewSize.width
            } else {
                windowSize.height.toFloat() / previewSize.height
            }
        }

        if (scaleX == 0f || scaleY == 0f) return
        val finalScale = max(scaleX, scaleY)
        val halfWidth = windowSize.width / 2f
        val halfHeight = windowSize.height / 2f
        val matrix = Matrix()

        if (isRotationRequired) {
            matrix.setScale(
                1f / scaleX * finalScale,
                1f / scaleY * finalScale,
                halfWidth,
                halfHeight,
            )
        } else {
            matrix.setScale(
                windowSize.height / windowSize.width.toFloat() / scaleY * finalScale,
                windowSize.width / windowSize.height.toFloat() / scaleX * finalScale,
                halfWidth,
                halfHeight,
            )
        }

        matrix.postRotate(-surfaceRotationDegrees.toFloat(), halfWidth, halfHeight)
        textureView.setTransform(matrix)
    }

    private fun computeRelativeRotation(
        chars: CameraCharacteristics,
        deviceOrientationDegrees: Int,
    ): Int {
        val sensorOrientationDegrees = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        val sign = if (
            chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
        ) 1 else -1
        return (sensorOrientationDegrees - deviceOrientationDegrees * sign + 360) % 360
    }

    private fun setContinuousAfIfSupported(
        builder: CaptureRequest.Builder,
        chars: CameraCharacteristics,
    ) {
        val modes = chars.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: intArrayOf()
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
                cameraManager.openCamera(
                    cameraId,
                    object : CameraDevice.StateCallback() {
                        override fun onOpened(camera: CameraDevice) {
                            if (continuation.isActive) continuation.resume(camera) else camera.close()
                        }

                        override fun onDisconnected(camera: CameraDevice) {
                            camera.close()
                            if (continuation.isActive) {
                                continuation.resumeWithException(
                                    IllegalStateException("Camera $cameraId disconnected"),
                                )
                            }
                        }

                        override fun onError(camera: CameraDevice, error: Int) {
                            camera.close()
                            if (continuation.isActive) {
                                continuation.resumeWithException(
                                    IllegalStateException(
                                        "Camera $cameraId open error ${cameraErrorName(error)} ($error)",
                                    ),
                                )
                            }
                        }
                    },
                    cameraHandler,
                )
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
                if (continuation.isActive) {
                    continuation.resumeWithException(
                        IllegalStateException("Camera2 preview/capture session configuration failed"),
                    )
                }
            }
        }

        try {
            @Suppress("DEPRECATION")
            if (physicalCameraId == null) {
                device.createCaptureSession(listOf(preview, still), callback, cameraHandler)
            } else {
                val previewOutput = OutputConfiguration(preview).apply {
                    setPhysicalCameraId(physicalCameraId)
                }
                val stillOutput = OutputConfiguration(still).apply {
                    setPhysicalCameraId(physicalCameraId)
                }
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

    private suspend fun awaitSurfaceTexture(textureView: TextureView): SurfaceTexture =
        suspendCancellableCoroutine { continuation ->
            textureView.surfaceTexture?.takeIf { textureView.isAvailable }?.let { surface ->
                continuation.resume(surface)
                return@suspendCancellableCoroutine
            }

            val listener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(
                    surface: SurfaceTexture,
                    width: Int,
                    height: Int,
                ) {
                    textureView.surfaceTextureListener = null
                    if (continuation.isActive) continuation.resume(surface)
                }

                override fun onSurfaceTextureSizeChanged(
                    surface: SurfaceTexture,
                    width: Int,
                    height: Int,
                ) = Unit

                override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = true

                override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
            }

            textureView.surfaceTextureListener = listener
            continuation.invokeOnCancellation {
                if (textureView.surfaceTextureListener === listener) {
                    textureView.surfaceTextureListener = null
                }
            }
        }

    private fun closeCurrent() {
        runCatching { captureSession?.stopRepeating() }
        runCatching { captureSession?.abortCaptures() }
        runCatching { captureSession?.close() }
        runCatching { cameraDevice?.close() }
        runCatching { imageReader?.close() }
        runCatching { previewSurface?.release() }

        captureSession = null
        cameraDevice = null
        imageReader = null
        previewSurface = null
        previewBuilder = null
        controlCharacteristics = null
        streamCharacteristics = null
        activeRoute = null
        activeTextureView = null
        activePreviewSize = null
        activeImageFormat = ImageFormat.JPEG
    }

    private fun cameraErrorName(error: Int): String = when (error) {
        CameraDevice.StateCallback.ERROR_CAMERA_IN_USE -> "CAMERA_IN_USE"
        CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE -> "MAX_CAMERAS_IN_USE"
        CameraDevice.StateCallback.ERROR_CAMERA_DISABLED -> "CAMERA_DISABLED"
        CameraDevice.StateCallback.ERROR_CAMERA_DEVICE -> "CAMERA_DEVICE"
        CameraDevice.StateCallback.ERROR_CAMERA_SERVICE -> "CAMERA_SERVICE"
        else -> "UNKNOWN"
    }

    private data class CapturedImage(
        val bytes: ByteArray,
        val width: Int,
        val height: Int,
    )
}
