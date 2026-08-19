package com.omnicam.camera.camerax

import android.util.Size
import androidx.camera.viewfinder.core.TransformationInfo
import androidx.camera.viewfinder.core.ViewfinderSurfaceRequest

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
