package com.omnicam.camera.camerax

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.params.StreamConfigurationMap
import android.os.Build
import android.util.Size

/** Capability-driven still-resolution policy shared by all SoCs/OEMs. */
internal object ResolutionPolicy {
    const val UPSCALE_SOURCE_LIMIT_PIXELS = 5_000_000L
    const val UPSCALE_OUTPUT_LIMIT_PIXELS = 20_000_000L

    data class MapSet(
        val normal: StreamConfigurationMap,
        val maximumResolution: StreamConfigurationMap?,
    )

    fun maps(characteristics: CameraCharacteristics): MapSet? {
        val normal = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: return null
        val maximum = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && supportsUltraHighResolution(characteristics)) {
            characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION)
        } else {
            null
        }
        return MapSet(normal, maximum)
    }

    fun supportsUltraHighResolution(characteristics: CameraCharacteristics): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        val caps = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES).orEmpty()
        return caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_ULTRA_HIGH_RESOLUTION_SENSOR)
    }

    fun shouldAdaptiveUpscale(size: Size): Boolean {
        val inputPixels = size.width.toLong() * size.height.toLong()
        val outputPixels = inputPixels * 4L
        return inputPixels in 1..UPSCALE_SOURCE_LIMIT_PIXELS &&
            outputPixels <= UPSCALE_OUTPUT_LIMIT_PIXELS
    }
}
