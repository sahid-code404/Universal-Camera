package com.omnicam.camera.camerax

import android.annotation.SuppressLint
import android.content.Context
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

@OptIn(ExperimentalCamera2Interop::class)
class CameraXPreviewController(
    context: Context,
) {
    private val appContext = context.applicationContext
    private var provider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null
    private var boundCamera: Camera? = null
    private var boundCameraId: String? = null

    @SuppressLint("MissingPermission")
    suspend fun bind(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        cameraId: String,
    ): CameraBindResult = withContext(Dispatchers.Main.immediate) {
        val cameraProvider = awaitProvider()
        provider = cameraProvider

        val selector = CameraSelector.Builder()
            .addCameraFilter { cameraInfos ->
                cameraInfos.filter { cameraInfo ->
                    runCatching { Camera2CameraInfo.from(cameraInfo).cameraId == cameraId }
                        .getOrDefault(false)
                }
            }
            .build()

        val preview = Preview.Builder().build().also {
            it.surfaceProvider = previewView.surfaceProvider
        }
        val capture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()

        runCatching {
            cameraProvider.unbindAll()
            val camera = cameraProvider.bindToLifecycle(
                lifecycleOwner,
                selector,
                preview,
                capture,
            )
            imageCapture = capture
            boundCamera = camera
            boundCameraId = cameraId

            val actualId = runCatching { Camera2CameraInfo.from(camera.cameraInfo).cameraId }
                .getOrDefault(cameraId)
            val zoomState = camera.cameraInfo.zoomState.value
            CameraBindResult.Success(
                requestedCameraId = cameraId,
                actualCameraId = actualId,
                minZoomRatio = zoomState?.minZoomRatio ?: 1f,
                maxZoomRatio = zoomState?.maxZoomRatio ?: 1f,
            )
        }.getOrElse { error ->
            imageCapture = null
            boundCamera = null
            boundCameraId = null
            CameraBindResult.Failure(
                cameraId = cameraId,
                reason = error.message ?: error::class.java.simpleName,
            )
        }
    }

    suspend fun captureProbe(): CaptureProbeResult = withContext(Dispatchers.Main.immediate) {
        val capture = imageCapture
            ?: return@withContext CaptureProbeResult.Failure("No camera is currently bound")
        val cameraId = boundCameraId
            ?: return@withContext CaptureProbeResult.Failure("Camera ID is unavailable")

        suspendCancellableCoroutine { continuation ->
            capture.takePicture(
                ContextCompat.getMainExecutor(appContext),
                object : ImageCapture.OnImageCapturedCallback() {
                    override fun onCaptureSuccess(image: ImageProxy) {
                        val result = CaptureProbeResult.Success(
                            cameraId = cameraId,
                            width = image.width,
                            height = image.height,
                            format = image.format,
                        )
                        image.close()
                        if (continuation.isActive) continuation.resume(result)
                    }

                    override fun onError(exception: ImageCaptureException) {
                        if (continuation.isActive) {
                            continuation.resume(
                                CaptureProbeResult.Failure(
                                    exception.message ?: exception::class.java.simpleName,
                                ),
                            )
                        }
                    }
                },
            )
        }
    }

    suspend fun setZoomRatio(ratio: Float): Result<Unit> = withContext(Dispatchers.Main.immediate) {
        val camera = boundCamera ?: return@withContext Result.failure(
            IllegalStateException("No camera is currently bound"),
        )
        runCatching {
            camera.cameraControl.setZoomRatio(ratio).get()
            Unit
        }
    }

    fun unbind() {
        provider?.unbindAll()
        imageCapture = null
        boundCamera = null
        boundCameraId = null
    }

    private suspend fun awaitProvider(): ProcessCameraProvider = suspendCancellableCoroutine { continuation ->
        val future = ProcessCameraProvider.getInstance(appContext)
        future.addListener(
            {
                runCatching { future.get() }
                    .onSuccess { if (continuation.isActive) continuation.resume(it) }
                    .onFailure { if (continuation.isActive) continuation.cancel(it) }
            },
            ContextCompat.getMainExecutor(appContext),
        )
        continuation.invokeOnCancellation { future.cancel(true) }
    }
}

sealed interface CameraBindResult {
    data class Success(
        val requestedCameraId: String,
        val actualCameraId: String,
        val minZoomRatio: Float,
        val maxZoomRatio: Float,
    ) : CameraBindResult

    data class Failure(
        val cameraId: String,
        val reason: String,
    ) : CameraBindResult
}

sealed interface CaptureProbeResult {
    data class Success(
        val cameraId: String,
        val width: Int,
        val height: Int,
        val format: Int,
    ) : CaptureProbeResult

    data class Failure(
        val reason: String,
    ) : CaptureProbeResult
}
