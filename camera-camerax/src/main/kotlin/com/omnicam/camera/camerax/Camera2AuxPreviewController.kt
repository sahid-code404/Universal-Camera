package com.omnicam.camera.camerax

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.Surface
import android.view.TextureView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Direct Camera2 preview path used by the auxiliary-camera compatibility probe.
 *
 * CameraX may apply its own camera availability filtering before a CameraSelector is evaluated.
 * This controller intentionally bypasses CameraX and opens the exact CameraManager ID requested
 * by the caller. It remains a diagnostic/compatibility path until physical-device validation is
 * complete.
 */
class Camera2AuxPreviewController(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val cameraManager = appContext.getSystemService(CameraManager::class.java)
    private val cameraThread = HandlerThread("OmniCam-Camera2-Aux").apply { start() }
    private val cameraHandler = Handler(cameraThread.looper)

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var previewSurface: Surface? = null
    private var activeTextureView: TextureView? = null
    private var boundCameraId: String? = null

    @SuppressLint("MissingPermission")
    suspend fun bind(
        textureView: TextureView,
        cameraId: String,
    ): CameraBindResult = withContext(Dispatchers.Main.immediate) {
        closeCurrent()

        runCatching {
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            val surfaceTexture = awaitSurfaceTexture(textureView)
            val previewSize = choosePreviewSize(characteristics)
            surfaceTexture.setDefaultBufferSize(previewSize.width, previewSize.height)

            val surface = Surface(surfaceTexture)
            previewSurface = surface
            activeTextureView = textureView

            val device = openCamera(cameraId)
            cameraDevice = device

            val session = createPreviewSession(device, surface)
            captureSession = session

            val request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(surface)
                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                setContinuousAfIfSupported(this, characteristics)
            }.build()
            session.setRepeatingRequest(request, null, cameraHandler)
            boundCameraId = cameraId

            val minZoom = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                characteristics.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)?.lower ?: 1f
            } else {
                1f
            }
            val maxZoom = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                characteristics.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)?.upper
                    ?: characteristics.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM)
                    ?: 1f
            } else {
                characteristics.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f
            }

            CameraBindResult.Success(
                requestedCameraId = cameraId,
                actualCameraId = cameraId,
                minZoomRatio = minZoom,
                maxZoomRatio = maxZoom,
            )
        }.getOrElse { error ->
            closeCurrent()
            CameraBindResult.Failure(
                cameraId = cameraId,
                reason = buildString {
                    append(error::class.java.simpleName)
                    error.message?.takeIf { it.isNotBlank() }?.let {
                        append(": ")
                        append(it)
                    }
                },
            )
        }
    }

    /**
     * Keeps the probe entirely in memory. At this stage it verifies that the direct Camera2
     * preview is delivering pixels; a dedicated still-capture session is a later hardware gate.
     */
    suspend fun captureProbe(): CaptureProbeResult = withContext(Dispatchers.Main.immediate) {
        val cameraId = boundCameraId
            ?: return@withContext CaptureProbeResult.Failure("No Camera2 device is currently bound")
        val textureView = activeTextureView
            ?: return@withContext CaptureProbeResult.Failure("Preview surface is unavailable")

        val bitmap = textureView.bitmap
            ?: return@withContext CaptureProbeResult.Failure("Preview has not produced a frame yet")
        val result = CaptureProbeResult.Success(
            cameraId = cameraId,
            width = bitmap.width,
            height = bitmap.height,
            format = PixelFormat.RGBA_8888,
        )
        bitmap.recycle()
        result
    }

    fun unbind() {
        closeCurrent()
    }

    private fun closeCurrent() {
        runCatching { captureSession?.stopRepeating() }
        runCatching { captureSession?.abortCaptures() }
        runCatching { captureSession?.close() }
        runCatching { cameraDevice?.close() }
        runCatching { previewSurface?.release() }
        captureSession = null
        cameraDevice = null
        previewSurface = null
        activeTextureView = null
        boundCameraId = null
    }

    private fun choosePreviewSize(characteristics: CameraCharacteristics): Size {
        val sizes = characteristics
            .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?.getOutputSizes(SurfaceTexture::class.java)
            .orEmpty()

        if (sizes.isEmpty()) return Size(1280, 720)

        val maxPreviewPixels = 1920L * 1080L
        return sizes
            .filter { size -> size.width.toLong() * size.height.toLong() <= maxPreviewPixels }
            .maxByOrNull { size -> size.width.toLong() * size.height.toLong() }
            ?: sizes.minBy { size -> size.width.toLong() * size.height.toLong() }
    }

    private fun setContinuousAfIfSupported(
        builder: CaptureRequest.Builder,
        characteristics: CameraCharacteristics,
    ) {
        val modes = characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: intArrayOf()
        when {
            modes.contains(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE) ->
                builder.set(
                    CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE,
                )

            modes.contains(CaptureRequest.CONTROL_AF_MODE_AUTO) ->
                builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
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
                            if (continuation.isActive) {
                                continuation.resume(camera)
                            } else {
                                camera.close()
                            }
                        }

                        override fun onDisconnected(camera: CameraDevice) {
                            camera.close()
                            if (continuation.isActive) {
                                continuation.resumeWithException(
                                    IllegalStateException("Camera $cameraId disconnected while opening"),
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

    private suspend fun createPreviewSession(
        device: CameraDevice,
        surface: Surface,
    ): CameraCaptureSession = suspendCancellableCoroutine { continuation ->
        try {
            @Suppress("DEPRECATION")
            device.createCaptureSession(
                listOf(surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        if (continuation.isActive) {
                            continuation.resume(session)
                        } else {
                            session.close()
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        session.close()
                        if (continuation.isActive) {
                            continuation.resumeWithException(
                                IllegalStateException("Camera2 preview session configuration failed"),
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

    private suspend fun awaitSurfaceTexture(textureView: TextureView): SurfaceTexture =
        suspendCancellableCoroutine { continuation ->
            textureView.surfaceTexture?.takeIf { textureView.isAvailable }?.let {
                continuation.resume(it)
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

    private fun cameraErrorName(error: Int): String = when (error) {
        CameraDevice.StateCallback.ERROR_CAMERA_IN_USE -> "CAMERA_IN_USE"
        CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE -> "MAX_CAMERAS_IN_USE"
        CameraDevice.StateCallback.ERROR_CAMERA_DISABLED -> "CAMERA_DISABLED"
        CameraDevice.StateCallback.ERROR_CAMERA_DEVICE -> "CAMERA_DEVICE"
        CameraDevice.StateCallback.ERROR_CAMERA_SERVICE -> "CAMERA_SERVICE"
        else -> "UNKNOWN"
    }
}
