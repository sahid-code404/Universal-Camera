package com.omnicam.camera.camerax

import android.graphics.Bitmap
import android.graphics.Matrix
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureResult
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Creates an in-memory display preview from the RAW buffer. No second image file is written. */
internal object RawBitmapRenderer {
    fun render(
        merged: ComputationalRawEngine.MergeResult,
        highlightProtection: Float,
        denoiseStrength: Float,
    ): Bitmap? {
        if (!NativeRawBridge.available) return null
        val scale = min(1.0, sqrt(MAX_PREVIEW_PIXELS / (merged.width.toDouble() * merged.height.toDouble())))
        val width = even((merged.width * scale).roundToInt()).coerceAtLeast(2)
        val height = even((merged.height * scale).roundToInt()).coerceAtLeast(2)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val characteristics = merged.referenceCharacteristics
        val result = merged.referenceResult
        val code = NativeRawBridge.renderEnhanced(
            mergedRaw16 = merged.pixels16.duplicate(),
            width = merged.width,
            height = merged.height,
            blackLevels = staticBlackLevels(characteristics),
            whiteLevel = merged.whiteLevel,
            cfaArrangement = characteristics.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT)
                ?: CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_RGGB,
            whiteBalance = whiteBalance(result),
            colorMatrix = colorMatrix(result),
            highlightProtection = highlightProtection.coerceIn(0f, 1f),
            denoiseStrength = denoiseStrength.coerceIn(0f, 1f),
            outputBitmap = bitmap,
        )
        if (code != 0) {
            bitmap.recycle()
            return null
        }
        val rotation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        if (rotation % 360 == 0) return bitmap
        val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (rotated !== bitmap) bitmap.recycle()
        return rotated
    }

    private fun whiteBalance(result: android.hardware.camera2.TotalCaptureResult): FloatArray {
        result.get(CaptureResult.COLOR_CORRECTION_GAINS)?.let { gains ->
            return floatArrayOf(gains.red, gains.greenEven, gains.greenOdd, gains.blue)
        }
        result.get(CaptureResult.SENSOR_NEUTRAL_COLOR_POINT)?.let { neutral ->
            if (neutral.size >= 3) {
                val r = reciprocal(neutral[0].toFloat())
                val g = reciprocal(neutral[1].toFloat())
                val b = reciprocal(neutral[2].toFloat())
                val normalizer = g.coerceAtLeast(0.001f)
                return floatArrayOf(r / normalizer, 1f, 1f, b / normalizer)
            }
        }
        return floatArrayOf(1f, 1f, 1f, 1f)
    }

    private fun colorMatrix(result: android.hardware.camera2.TotalCaptureResult): FloatArray {
        val transform = result.get(CaptureResult.COLOR_CORRECTION_TRANSFORM)
            ?: return IDENTITY_MATRIX.copyOf()
        return FloatArray(9) { index ->
            val row = index / 3
            val column = index % 3
            transform.getElement(column, row).toFloat()
        }
    }

    private fun staticBlackLevels(characteristics: CameraCharacteristics): IntArray {
        val pattern = characteristics.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)
            ?: return intArrayOf(0, 0, 0, 0)
        return intArrayOf(
            pattern.getOffsetForIndex(0, 0),
            pattern.getOffsetForIndex(1, 0),
            pattern.getOffsetForIndex(0, 1),
            pattern.getOffsetForIndex(1, 1),
        )
    }

    private fun reciprocal(value: Float): Float = if (value > 0.00001f) 1f / value else 1f
    private fun even(value: Int): Int = value - (value and 1)

    private const val MAX_PREVIEW_PIXELS = 2_500_000.0
    private val IDENTITY_MATRIX = floatArrayOf(
        1f, 0f, 0f,
        0f, 1f, 0f,
        0f, 0f, 1f,
    )
}
