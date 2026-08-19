package com.omnicam.camera.camerax

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureResult
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.heifwriter.HeifWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Native C1.7 renderer: sensor RAW -> controlled HDR tone -> optional HD+ companion HEIF. */
internal class FastRawPhotoProcessor(context: Context) {
    private val appContext = context.applicationContext

    data class Result(
        val uri: Uri,
        val width: Int,
        val height: Int,
        val elapsedMillis: Long,
        val native: Boolean,
    )

    fun processAndSave(
        merged: ComputationalRawEngine.MergeResult,
        preset: ComputationalRawPreset,
        highlightProtection: Float,
        denoiseStrength: Float,
        upscale: Boolean,
    ): Result {
        val started = android.os.SystemClock.elapsedRealtime()
        if (!NativeRawBridge.available) {
            val legacy = ComputationalRawProcessor(appContext).processAndSave(merged, preset)
            return Result(
                uri = legacy.uri,
                width = legacy.width,
                height = legacy.height,
                elapsedMillis = android.os.SystemClock.elapsedRealtime() - started,
                native = false,
            )
        }

        val basePixels = merged.width.toDouble() * merged.height.toDouble()
        val scale = if (upscale) {
            min(2.0, sqrt(MAX_ENHANCED_PIXELS / basePixels)).coerceAtLeast(1.0)
        } else {
            1.0
        }
        val targetWidth = even((merged.width * scale).roundToInt()).coerceAtLeast(2)
        val targetHeight = even((merged.height * scale).roundToInt()).coerceAtLeast(2)
        val bitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
        try {
            val characteristics = merged.referenceCharacteristics
            val result = merged.referenceResult
            val black = staticBlackLevels(characteristics)
            val wb = whiteBalance(result)
            val matrix = colorMatrix(result)
            val arrangement = characteristics.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT)
                ?: CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_RGGB
            val code = NativeRawBridge.renderEnhanced(
                mergedRaw16 = merged.pixels16.duplicate(),
                width = merged.width,
                height = merged.height,
                blackLevels = black,
                whiteLevel = merged.whiteLevel,
                cfaArrangement = arrangement,
                whiteBalance = wb,
                colorMatrix = matrix,
                highlightProtection = highlightProtection.coerceIn(0f, 1f),
                denoiseStrength = denoiseStrength.coerceIn(0f, 1f),
                outputBitmap = bitmap,
            )
            check(code == 0) { "Native enhanced RAW render failed with code $code" }
            val rotation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
            val uri = saveHeif(bitmap, preset, rotation)
            return Result(
                uri = uri,
                width = targetWidth,
                height = targetHeight,
                elapsedMillis = android.os.SystemClock.elapsedRealtime() - started,
                native = true,
            )
        } finally {
            bitmap.recycle()
        }
    }

    private fun saveHeif(bitmap: Bitmap, preset: ComputationalRawPreset, rotation: Int): Uri {
        val resolver = appContext.contentResolver
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "OMNI_HD_${preset.name}_${timestamp}.heic")
            put(MediaStore.Images.Media.MIME_TYPE, "image/heic")
            put(MediaStore.Images.Media.WIDTH, bitmap.width)
            put(MediaStore.Images.Media.HEIGHT, bitmap.height)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/OmniCam/C-RAW")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("MediaStore could not create the enhanced RAW output")
        try {
            val pfd = resolver.openFileDescriptor(uri, "rw")
                ?: error("MediaStore HEIF file descriptor is unavailable")
            pfd.use { descriptor ->
                HeifWriter.Builder(
                    descriptor.fileDescriptor,
                    bitmap.width,
                    bitmap.height,
                    HeifWriter.INPUT_MODE_BITMAP,
                )
                    .setMaxImages(1)
                    .setPrimaryIndex(0)
                    .setQuality(98)
                    .setRotation(((rotation % 360) + 360) % 360)
                    .setGridEnabled(true)
                    .build()
                    .use { writer ->
                        writer.start()
                        writer.addBitmap(bitmap)
                        writer.stop(45_000)
                    }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                resolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
                    null,
                    null,
                )
            }
            return uri
        } catch (error: Throwable) {
            resolver.delete(uri, null, null)
            throw error
        }
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

    private companion object {
        const val MAX_ENHANCED_PIXELS = 24_000_000.0
        val IDENTITY_MATRIX = floatArrayOf(
            1f, 0f, 0f,
            0f, 1f, 0f,
            0f, 0f, 1f,
        )
    }
}
