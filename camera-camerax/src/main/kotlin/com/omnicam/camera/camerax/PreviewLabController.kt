package com.omnicam.camera.camerax

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Range
import android.util.Size
import android.view.Surface
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs
import kotlin.math.max
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine

enum class PreviewLabMode(val shortLabel: String, val description: String) {
    SURFACE_PRIVATE("SURFACE", "Camera2 SurfaceView · PRIVATE"),
    TEXTURE_PRIVATE("TEXTURE", "Camera2 TextureView · PRIVATE"),
    YUV_READER("YUV", "Camera2 YUV_420_888 ImageReader"),
    JPEG_READER("JPEG", "Camera2 JPEG ImageReader"),
}

data class PreviewLabSpec(
    val cameraId: String,
    val mode: PreviewLabMode,
    val size: Size,
    val rotationDegrees: Int,
    val mirrorX: Boolean,
)

data class PreviewLabFrame(
    val bitmap: Bitmap,
    val timestampNanos: Long,
)

data class PreviewLabState(
    val mode: PreviewLabMode = PreviewLabMode.TEXTURE_PRIVATE,
    val status: String = "Idle",
    val size: Size? = null,
    val fps: Float = 0f,
    val frameCount: Long = 0L,
)

/**
 * Standalone Camera2 preview benchmark used to compare preview transport paths on real hardware.
 * It intentionally does not create a RAW output, so the preview method itself can be judged without
 * the RAW stream changing session limits or frame rate.
 */
class PreviewLabController(context: Context) {
    private val appContext = context.applicationContext
    private val cameraManager = appContext.getSystemService(CameraManager::class.java)
    private val cameraThread = HandlerThread("OmniCam-PreviewLab-Camera").apply { start() }
    private val imageThread = HandlerThread("OmniCam-PreviewLab-Image").apply { start() }
    private val cameraHandler = Handler(cameraThread.looper)
    private val imageHandler = Handler(imageThread.looper)

    private val _state = MutableStateFlow(PreviewLabState())
    val state: StateFlow<PreviewLabState> = _state.asStateFlow()
    private val _frame = MutableStateFlow<PreviewLabFrame?>(null)
    val frame: StateFlow<PreviewLabFrame?> = _frame.asStateFlow()

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var activeSpec: PreviewLabSpec? = null
    private var fpsWindowStartNs = 0L
    private var fpsFrames = 0L
    private var totalFrames = 0L

    fun createSpec(
        cameraId: String,
        mode: PreviewLabMode,
        targetAspect: Float = 4f / 3f,
        displayRotationDegrees: Int = 0,
    ): PreviewLabSpec {
        val chars = cameraManager.getCameraCharacteristics(cameraId)
        val size = chooseSize(chars, mode, targetAspect)
        val sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        val displayRotation = ((displayRotationDegrees % 360) + 360) % 360
        val front = chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
        val rotation = if (front) {
            (sensorOrientation + displayRotation) % 360
        } else {
            (sensorOrientation - displayRotation + 360) % 360
        }
        return PreviewLabSpec(cameraId, mode, size, rotation, front)
    }

    @SuppressLint("MissingPermission")
    suspend fun bindSurface(spec: PreviewLabSpec, surface: Surface) {
        require(spec.mode == PreviewLabMode.SURFACE_PRIVATE || spec.mode == PreviewLabMode.TEXTURE_PRIVATE)
        closeCurrent()
        activeSpec = spec
        resetCounters(spec)
        _state.value = PreviewLabState(spec.mode, "Opening camera…", spec.size)
        val chars = cameraManager.getCameraCharacteristics(spec.cameraId)
        val device = openCamera(spec.cameraId)
        cameraDevice = device
        val session = createSession(device, surface)
        captureSession = session
        val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(surface)
            applyPreviewDefaults(this, chars)
        }
        session.setRepeatingRequest(builder.build(), captureCallback, cameraHandler)
        _state.value = _state.value.copy(status = "Running")
    }

    @SuppressLint("MissingPermission")
    suspend fun bindReader(spec: PreviewLabSpec) {
        require(spec.mode == PreviewLabMode.YUV_READER || spec.mode == PreviewLabMode.JPEG_READER)
        closeCurrent()
        activeSpec = spec
        resetCounters(spec)
        _state.value = PreviewLabState(spec.mode, "Opening camera…", spec.size)
        val format = if (spec.mode == PreviewLabMode.JPEG_READER) ImageFormat.JPEG else ImageFormat.YUV_420_888
        val imageReader = ImageReader.newInstance(spec.size.width, spec.size.height, format, 2)
        reader = imageReader
        attachReader(imageReader, spec)

        val chars = cameraManager.getCameraCharacteristics(spec.cameraId)
        val device = openCamera(spec.cameraId)
        cameraDevice = device
        val session = createSession(device, imageReader.surface)
        captureSession = session
        val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(imageReader.surface)
            applyPreviewDefaults(this, chars)
            if (format == ImageFormat.JPEG) {
                set(CaptureRequest.JPEG_QUALITY, 88.toByte())
                set(CaptureRequest.JPEG_ORIENTATION, spec.rotationDegrees)
            }
        }
        session.setRepeatingRequest(builder.build(), captureCallback, cameraHandler)
        _state.value = _state.value.copy(status = "Running · waiting for first frame")
    }

    fun unbind() = closeCurrent()

    fun shutdown() {
        closeCurrent()
        cameraThread.quitSafely()
        imageThread.quitSafely()
    }

    private fun attachReader(imageReader: ImageReader, spec: PreviewLabSpec) {
        imageReader.setOnImageAvailableListener({ source ->
            val image = runCatching { source.acquireLatestImage() }.getOrNull()
                ?: return@setOnImageAvailableListener
            image.use { frame ->
                runCatching {
                    when (spec.mode) {
                        PreviewLabMode.YUV_READER -> decodeYuv(frame, spec.rotationDegrees, spec.mirrorX)
                        PreviewLabMode.JPEG_READER -> decodeJpeg(frame, spec.rotationDegrees, spec.mirrorX)
                        else -> error("Reader decoder used for ${spec.mode}")
                    }
                }.onSuccess { bitmap ->
                    val old = _frame.value?.bitmap
                    _frame.value = PreviewLabFrame(bitmap, frame.timestamp)
                    if (old !== bitmap && old != null && !old.isRecycled) runCatching { old.recycle() }
                    _state.value = _state.value.copy(status = "Running")
                }.onFailure { error ->
                    _state.value = _state.value.copy(status = "Frame decode failed: ${error.message}")
                }
            }
        }, imageHandler)
    }

    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult,
        ) {
            val now = System.nanoTime()
            if (fpsWindowStartNs == 0L) fpsWindowStartNs = now
            fpsFrames++
            totalFrames++
            val elapsed = now - fpsWindowStartNs
            if (elapsed >= 1_000_000_000L) {
                val fps = fpsFrames * 1_000_000_000f / elapsed.toFloat()
                _state.value = _state.value.copy(fps = fps, frameCount = totalFrames)
                fpsWindowStartNs = now
                fpsFrames = 0L
            }
        }
    }

    private fun resetCounters(spec: PreviewLabSpec) {
        fpsWindowStartNs = 0L
        fpsFrames = 0L
        totalFrames = 0L
        _frame.value?.bitmap?.let { if (!it.isRecycled) runCatching { it.recycle() } }
        _frame.value = null
        _state.value = PreviewLabState(spec.mode, "Starting", spec.size)
    }

    private fun applyPreviewDefaults(builder: CaptureRequest.Builder, chars: CameraCharacteristics) {
        builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
        builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
        builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
        builder.set(CaptureRequest.CONTROL_CAPTURE_INTENT, CaptureRequest.CONTROL_CAPTURE_INTENT_PREVIEW)
        builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
        choosePreviewFps(chars)?.let { builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
        val afModes = chars.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES).orEmpty()
        when {
            afModes.contains(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE) ->
                builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            afModes.contains(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO) ->
                builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
            afModes.contains(CaptureRequest.CONTROL_AF_MODE_AUTO) ->
                builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
        }
        val nr = chars.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES).orEmpty()
        if (nr.contains(CaptureRequest.NOISE_REDUCTION_MODE_FAST)) {
            builder.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_FAST)
        }
        val edge = chars.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES).orEmpty()
        if (edge.contains(CaptureRequest.EDGE_MODE_FAST)) {
            builder.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_FAST)
        }
        val tone = chars.get(CameraCharacteristics.TONEMAP_AVAILABLE_TONE_MAP_MODES).orEmpty()
        if (tone.contains(CaptureRequest.TONEMAP_MODE_FAST)) {
            builder.set(CaptureRequest.TONEMAP_MODE, CaptureRequest.TONEMAP_MODE_FAST)
        }
    }

    private fun choosePreviewFps(chars: CameraCharacteristics): Range<Int>? {
        val ranges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES).orEmpty()
        return ranges.firstOrNull { it.lower == 30 && it.upper == 30 }
            ?: ranges.filter { it.upper == 30 }.maxByOrNull { it.lower }
            ?: ranges.filter { it.upper >= 30 }
                .minWithOrNull(compareBy<Range<Int>> { abs(it.upper - 30) }.thenByDescending { it.lower })
            ?: ranges.maxByOrNull { it.upper }
    }

    private fun chooseSize(
        chars: CameraCharacteristics,
        mode: PreviewLabMode,
        targetAspect: Float,
    ): Size {
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: return Size(1280, 960)
        val sizes = when (mode) {
            PreviewLabMode.SURFACE_PRIVATE,
            PreviewLabMode.TEXTURE_PRIVATE ->
                runCatching { map.getOutputSizes(android.graphics.SurfaceTexture::class.java) }.getOrNull().orEmpty()
            PreviewLabMode.YUV_READER ->
                runCatching { map.getOutputSizes(ImageFormat.YUV_420_888) }.getOrNull().orEmpty()
            PreviewLabMode.JPEG_READER ->
                runCatching { map.getOutputSizes(ImageFormat.JPEG) }.getOrNull().orEmpty()
        }
        val bounded = sizes.filter {
            max(it.width, it.height) <= 1440 && it.width.toLong() * it.height.toLong() <= 1_800_000L
        }.ifEmpty {
            sizes.filter { max(it.width, it.height) <= 1920 }
        }.ifEmpty { sizes.toList() }
        return bounded.minWithOrNull(
            compareBy<Size> { abs(it.width.toFloat() / it.height - targetAspect) }
                .thenBy { abs(max(it.width, it.height) - 1280) }
                .thenByDescending { it.width.toLong() * it.height.toLong() },
        ) ?: sizes.firstOrNull() ?: Size(1280, 960)
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
    private suspend fun createSession(device: CameraDevice, surface: Surface): CameraCaptureSession =
        suspendCancellableCoroutine { continuation ->
            val callback = object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    if (continuation.isActive) continuation.resume(session) else session.close()
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    session.close()
                    if (continuation.isActive) {
                        continuation.resumeWithException(IllegalStateException("Preview session rejected by camera HAL"))
                    }
                }
            }
            runCatching { device.createCaptureSession(listOf(surface), callback, cameraHandler) }
                .onFailure { if (continuation.isActive) continuation.resumeWithException(it) }
        }

    private fun closeCurrent() {
        runCatching { captureSession?.stopRepeating() }
        runCatching { captureSession?.abortCaptures() }
        runCatching { captureSession?.close() }
        runCatching { cameraDevice?.close() }
        runCatching { reader?.close() }
        captureSession = null
        cameraDevice = null
        reader = null
        activeSpec = null
        _frame.value?.bitmap?.let { if (!it.isRecycled) runCatching { it.recycle() } }
        _frame.value = null
    }

    private fun decodeJpeg(image: Image, rotationDegrees: Int, mirrorX: Boolean): Bitmap {
        val buffer = image.planes[0].buffer.duplicate()
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: error("JPEG decoder returned null")
        return transform(decoded, rotationDegrees, mirrorX)
    }

    private fun decodeYuv(image: Image, rotationDegrees: Int, mirrorX: Boolean): Bitmap {
        val nv21 = yuv420888ToNv21(image)
        val output = ByteArrayOutputStream()
        val yuv = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
        check(yuv.compressToJpeg(Rect(0, 0, image.width, image.height), 92, output)) {
            "YUV to JPEG conversion failed"
        }
        val bytes = output.toByteArray()
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: error("YUV preview decoder returned null")
        return transform(decoded, rotationDegrees, mirrorX)
    }

    private fun yuv420888ToNv21(image: Image): ByteArray {
        val width = image.width
        val height = image.height
        val output = ByteArray(width * height + width * height / 2)
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val yBuffer = yPlane.buffer.duplicate()
        val uBuffer = uPlane.buffer.duplicate()
        val vBuffer = vPlane.buffer.duplicate()
        val yBase = yBuffer.position()
        val uBase = uBuffer.position()
        val vBase = vBuffer.position()

        var out = 0
        for (row in 0 until height) {
            val rowStart = yBase + row * yPlane.rowStride
            for (col in 0 until width) {
                output[out++] = yBuffer.get(rowStart + col * yPlane.pixelStride)
            }
        }
        for (row in 0 until height / 2) {
            val uRow = uBase + row * uPlane.rowStride
            val vRow = vBase + row * vPlane.rowStride
            for (col in 0 until width / 2) {
                output[out++] = vBuffer.get(vRow + col * vPlane.pixelStride)
                output[out++] = uBuffer.get(uRow + col * uPlane.pixelStride)
            }
        }
        return output
    }

    private fun transform(source: Bitmap, rotationDegrees: Int, mirrorX: Boolean): Bitmap {
        val rotation = ((rotationDegrees % 360) + 360) % 360
        if (rotation == 0 && !mirrorX) return source
        val matrix = Matrix().apply {
            if (rotation != 0) postRotate(rotation.toFloat())
            if (mirrorX) postScale(-1f, 1f)
        }
        val result = Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
        if (result !== source && !source.isRecycled) source.recycle()
        return result
    }
}
