package com.omnicam.camera.camerax

import android.graphics.Bitmap
import android.util.Size

/** Shared RAW types used by the single production camera. */
enum class ComputationalRawPreset(
    val label: String,
    val frameCount: Int,
    val exposureOffsetsEv: List<Float>,
) {
    QUALITY("RAW · 4 frames", 4, listOf(-0.8f, 0f, 0f, 0f)),
    HDR("HDR · 6 frames", 6, listOf(-2f, -1f, 0f, 0f, 0f, 0f)),
    MAX("MAX · 8 frames", 8, listOf(-2.5f, -1.5f, -0.7f, 0f, 0f, 0f, 0f, 0f)),
}

enum class LightningFlashMode(val label: String) {
    OFF("OFF"),
    AUTO("AUTO"),
    ON("ON"),
    TORCH("TORCH"),
}

enum class LightningFocusStatus {
    IDLE,
    SCANNING,
    FOCUSED,
    FAILED,
}

data class LightningFocusState(
    val normalizedX: Float = 0.5f,
    val normalizedY: Float = 0.5f,
    val status: LightningFocusStatus = LightningFocusStatus.IDLE,
)

data class ComputationalRawViewfinderSpec(
    val previewSize: Size,
    val rotationDegrees: Int,
    val mirrorX: Boolean,
    val targetAspect: Float?,
)

data class JpegPreviewFrame(
    val bitmap: Bitmap,
    val timestampNanos: Long,
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
