package com.omnicam.camera.camerax

import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.os.Handler
import android.util.Range
import java.util.Collections
import java.util.WeakHashMap
import kotlin.math.abs

/**
 * Temporary hardware-validation guard for the production Surface preview.
 *
 * Preview Lab preferred a fixed 30/30 AE FPS range, while the production RAW+preview controller
 * regressed to preferring 10/30 or 15/30. On some Qualcomm/Xiaomi HALs that lets AE stretch the
 * preview exposure toward 1/10-1/15 s, producing the bright, washed and smeared preview seen on
 * device. This guard restores the same 30-fps preference that looked correct in Preview Lab.
 *
 * It is intentionally isolated so it can be removed once the controller is refactored to keep the
 * RAW stream out of the normal preview session.
 */
object PreviewExposureGuard {
    private val patchedBuilders = Collections.newSetFromMap(
        WeakHashMap<CaptureRequest.Builder, Boolean>(),
    )

    fun apply(controller: LightningRawController): Boolean {
        return runCatching {
            val type = controller.javaClass
            val chars = type.privateField("activeCharacteristics").get(controller) as? CameraCharacteristics
                ?: return false
            val builder = type.privateField("previewBuilder").get(controller) as? CaptureRequest.Builder
                ?: return false

            synchronized(patchedBuilders) {
                if (patchedBuilders.contains(builder)) return true
            }

            val session = type.privateField("captureSession").get(controller) as? CameraCaptureSession
                ?: return false
            val handler = type.privateField("cameraHandler").get(controller) as? Handler
                ?: return false
            val callback = type.privateField("previewCaptureCallback").get(controller)
                as? CameraCaptureSession.CaptureCallback
                ?: return false

            val selected = preferredPreviewRange(chars) ?: return false
            builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, selected)

            val compensation = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
            if (compensation != null && compensation.contains(0)) {
                builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, 0)
            }

            session.setRepeatingRequest(builder.build(), callback, handler)
            synchronized(patchedBuilders) { patchedBuilders.add(builder) }
            true
        }.getOrDefault(false)
    }

    private fun preferredPreviewRange(chars: CameraCharacteristics): Range<Int>? {
        val ranges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES).orEmpty()
        return ranges.firstOrNull { it.lower == 30 && it.upper == 30 }
            ?: ranges.filter { it.upper == 30 }.maxByOrNull { it.lower }
            ?: ranges.filter { it.upper >= 30 }
                .minWithOrNull(
                    compareBy<Range<Int>> { abs(it.upper - 30) }
                        .thenByDescending { it.lower },
                )
            ?: ranges.maxByOrNull { it.upper }
    }

    private fun Class<*>.privateField(name: String) = getDeclaredField(name).apply {
        isAccessible = true
    }
}
