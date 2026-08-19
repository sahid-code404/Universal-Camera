package com.omnicam.camera.camerax

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.params.ColorSpaceTransform
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.heifwriter.HeifWriter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Phase C2 CPU RAW processor.
 *
 * This is deliberately independent from Android's JPEG/YUV ISP path. It starts from the fused
 * RAW16 Bayer buffer produced by C1, performs an edge-aware Bayer demosaic, applies the Camera2
 * white-balance gains and sensor->linear-sRGB color transform reported with the reference capture,
 * then performs scene-adaptive HDR compression and writes a full-resolution HEIF image.
 *
 * C2 is still an experimental software ISP. It does not yet claim local optical-flow alignment,
 * joint demosaic/super-resolution, or a calibrated 10-bit HDR output pipeline.
 */
class ComputationalRawProcessor(private val context: Context) {
    data class Result(
        val uri: Uri,
        val width: Int,
        val height: Int,
        val processingMillis: Long,
        val colorTransformFromCamera: Boolean,
        val whiteBalanceFromCamera: Boolean,
    )

    fun processAndSave(
        merged: ComputationalRawEngine.MergeResult,
        preset: ComputationalRawPreset,
    ): Result {
        val started = android.os.SystemClock.elapsedRealtime()
        val renderer = RawRenderer(merged)
        val bitmap = renderer.render(preset)
        return try {
            val rotation = merged.referenceCharacteristics
                .get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
            val uri = saveHeif(bitmap, preset, rotation)
            Result(
                uri = uri,
                width = merged.width,
                height = merged.height,
                processingMillis = android.os.SystemClock.elapsedRealtime() - started,
                colorTransformFromCamera = renderer.hasCameraColorTransform,
                whiteBalanceFromCamera = renderer.hasCameraWhiteBalance,
            )
        } finally {
            bitmap.recycle()
        }
    }

    private fun saveHeif(bitmap: Bitmap, preset: ComputationalRawPreset, rotation: Int): Uri {
        val resolver = context.contentResolver
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "OMNI_C2_${preset.name}_${timestamp}.heic")
            put(MediaStore.Images.Media.MIME_TYPE, "image/heic")
            put(MediaStore.Images.Media.WIDTH, bitmap.width)
            put(MediaStore.Images.Media.HEIGHT, bitmap.height)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/OmniCam/C-RAW")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("MediaStore could not create the C2 HEIF output")
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
                    .setQuality(100)
                    .setRotation(((rotation % 360) + 360) % 360)
                    .setGridEnabled(true)
                    .build()
                    .use { writer ->
                        writer.start()
                        writer.addBitmap(bitmap)
                        writer.stop(30_000)
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

    private class RawRenderer(private val merged: ComputationalRawEngine.MergeResult) {
        private val width = merged.width
        private val height = merged.height
        private val raw: ByteBuffer = merged.pixels16.duplicate()
            .order(ByteOrder.nativeOrder())
        private val characteristics = merged.referenceCharacteristics
        private val result = merged.referenceResult
        private val whiteLevel = merged.whiteLevel.toFloat().coerceAtLeast(1f)
        private val black = readBlackLevels(characteristics)
        private val arrangement = characteristics.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT)
            ?: CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_RGGB
        private val gains = result.get(CaptureResult.COLOR_CORRECTION_GAINS)
        private val transform = result.get(CaptureResult.COLOR_CORRECTION_TRANSFORM)
        val hasCameraWhiteBalance: Boolean = gains != null
        val hasCameraColorTransform: Boolean = transform != null

        private val gainR = gains?.red ?: 1f
        private val gainGe = gains?.greenEven ?: 1f
        private val gainGo = gains?.greenOdd ?: gainGe
        private val gainB = gains?.blue ?: 1f
        private val matrix = transform?.toFloatMatrix() ?: IDENTITY_MATRIX

        fun render(preset: ComputationalRawPreset): Bitmap {
            val exposure = estimateDisplayExposure()
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val row = IntArray(width)
            val compression = when (preset) {
                ComputationalRawPreset.QUALITY -> 2.8f
                ComputationalRawPreset.HDR -> 7.0f
                ComputationalRawPreset.MAX -> 10.0f
            }
            val shadowLift = when (preset) {
                ComputationalRawPreset.QUALITY -> 0.02f
                ComputationalRawPreset.HDR -> 0.055f
                ComputationalRawPreset.MAX -> 0.075f
            }

            for (y in 0 until height) {
                for (x in 0 until width) {
                    val sensor = demosaic(x, y)
                    val greenGain = if ((y and 1) == 0) gainGe else gainGo
                    val wr = sensor[0] * gainR
                    val wg = sensor[1] * greenGain
                    val wb = sensor[2] * gainB

                    var r = matrix[0] * wr + matrix[1] * wg + matrix[2] * wb
                    var g = matrix[3] * wr + matrix[4] * wg + matrix[5] * wb
                    var b = matrix[6] * wr + matrix[7] * wg + matrix[8] * wb
                    r = max(0f, r)
                    g = max(0f, g)
                    b = max(0f, b)

                    val luma = max(0f, 0.2126f * r + 0.7152f * g + 0.0722f * b)
                    val scaledLuma = luma * exposure
                    val toneLuma = if (scaledLuma <= 0f) 0f else {
                        (ln(1.0 + compression * scaledLuma) / ln(1.0 + compression)).toFloat()
                    }
                    val localScale = if (scaledLuma > 1e-6f) toneLuma / scaledLuma else exposure
                    r *= exposure * localScale
                    g *= exposure * localScale
                    b *= exposure * localScale

                    // Gentle shadow lift after highlight compression. It is mode-dependent and
                    // deliberately bounded so black remains black and highlights are not flattened.
                    r = liftShadows(r, shadowLift)
                    g = liftShadows(g, shadowLift)
                    b = liftShadows(b, shadowLift)

                    row[x] = (0xff shl 24) or
                        (linearToSrgb8(r) shl 16) or
                        (linearToSrgb8(g) shl 8) or
                        linearToSrgb8(b)
                }
                bitmap.setPixels(row, 0, width, 0, y, width, 1)
            }
            return bitmap
        }

        private fun estimateDisplayExposure(): Float {
            val values = ArrayList<Float>(4096)
            val step = max(12, min(width, height) / 64)
            var y = step
            while (y < height - step) {
                var x = step
                while (x < width - step) {
                    val sensor = demosaic(x, y)
                    val greenGain = if ((y and 1) == 0) gainGe else gainGo
                    val wr = sensor[0] * gainR
                    val wg = sensor[1] * greenGain
                    val wb = sensor[2] * gainB
                    val r = max(0f, matrix[0] * wr + matrix[1] * wg + matrix[2] * wb)
                    val g = max(0f, matrix[3] * wr + matrix[4] * wg + matrix[5] * wb)
                    val b = max(0f, matrix[6] * wr + matrix[7] * wg + matrix[8] * wb)
                    values += 0.2126f * r + 0.7152f * g + 0.0722f * b
                    x += step
                }
                y += step
            }
            if (values.isEmpty()) return 1f
            values.sort()
            val p50 = values[(values.lastIndex * 0.50f).roundToInt()].coerceAtLeast(0.005f)
            val p98 = values[(values.lastIndex * 0.98f).roundToInt()].coerceAtLeast(0.02f)
            val midExposure = 0.32f / p50
            val highlightExposure = 0.96f / p98
            return min(midExposure, highlightExposure * 1.35f).coerceIn(0.45f, 5.0f)
        }

        /** Edge-aware Bayer interpolation. Output is normalized sensor RGB before WB/color matrix. */
        private fun demosaic(x: Int, y: Int): FloatArray {
            return when (colorAt(x, y)) {
                Channel.R -> {
                    val r = sample(x, y)
                    val gh = average(sample(x - 1, y), sample(x + 1, y))
                    val gv = average(sample(x, y - 1), sample(x, y + 1))
                    val gradH = abs(sample(x - 2, y) - sample(x + 2, y))
                    val gradV = abs(sample(x, y - 2) - sample(x, y + 2))
                    val g = if (gradH <= gradV) gh else gv
                    val b = average4(
                        sample(x - 1, y - 1), sample(x + 1, y - 1),
                        sample(x - 1, y + 1), sample(x + 1, y + 1),
                    )
                    floatArrayOf(r, g, b)
                }
                Channel.B -> {
                    val b = sample(x, y)
                    val gh = average(sample(x - 1, y), sample(x + 1, y))
                    val gv = average(sample(x, y - 1), sample(x, y + 1))
                    val gradH = abs(sample(x - 2, y) - sample(x + 2, y))
                    val gradV = abs(sample(x, y - 2) - sample(x, y + 2))
                    val g = if (gradH <= gradV) gh else gv
                    val r = average4(
                        sample(x - 1, y - 1), sample(x + 1, y - 1),
                        sample(x - 1, y + 1), sample(x + 1, y + 1),
                    )
                    floatArrayOf(r, g, b)
                }
                Channel.G -> {
                    val g = sample(x, y)
                    val horizontalIsRed = colorAt(x - 1, y) == Channel.R || colorAt(x + 1, y) == Channel.R
                    val r = if (horizontalIsRed) {
                        average(sample(x - 1, y), sample(x + 1, y))
                    } else {
                        average(sample(x, y - 1), sample(x, y + 1))
                    }
                    val b = if (horizontalIsRed) {
                        average(sample(x, y - 1), sample(x, y + 1))
                    } else {
                        average(sample(x - 1, y), sample(x + 1, y))
                    }
                    floatArrayOf(r, g, b)
                }
            }
        }

        private fun sample(x: Int, y: Int): Float {
            val cx = x.coerceIn(0, width - 1)
            val cy = y.coerceIn(0, height - 1)
            val value = raw.getShort((cy * width + cx) * 2).toInt() and 0xffff
            val blackLevel = black[(cy and 1) * 2 + (cx and 1)].toFloat()
            return ((value - blackLevel) / (whiteLevel - blackLevel).coerceAtLeast(1f)).coerceIn(0f, 1f)
        }

        private fun colorAt(x: Int, y: Int): Channel {
            val px = x and 1
            val py = y and 1
            return when (arrangement) {
                CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GRBG -> when {
                    py == 0 && px == 0 -> Channel.G
                    py == 0 -> Channel.R
                    py == 1 && px == 0 -> Channel.B
                    else -> Channel.G
                }
                CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GBRG -> when {
                    py == 0 && px == 0 -> Channel.G
                    py == 0 -> Channel.B
                    py == 1 && px == 0 -> Channel.R
                    else -> Channel.G
                }
                CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_BGGR -> when {
                    py == 0 && px == 0 -> Channel.B
                    py == 0 -> Channel.G
                    py == 1 && px == 0 -> Channel.G
                    else -> Channel.R
                }
                else -> when {
                    py == 0 && px == 0 -> Channel.R
                    py == 0 -> Channel.G
                    py == 1 && px == 0 -> Channel.G
                    else -> Channel.B
                }
            }
        }

        private enum class Channel { R, G, B }

        companion object {
            private val IDENTITY_MATRIX = floatArrayOf(
                1f, 0f, 0f,
                0f, 1f, 0f,
                0f, 0f, 1f,
            )

            private fun readBlackLevels(chars: CameraCharacteristics): IntArray {
                val pattern = chars.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)
                    ?: return intArrayOf(0, 0, 0, 0)
                return intArrayOf(
                    pattern.getOffsetForIndex(0, 0),
                    pattern.getOffsetForIndex(1, 0),
                    pattern.getOffsetForIndex(0, 1),
                    pattern.getOffsetForIndex(1, 1),
                )
            }

            private fun ColorSpaceTransform.toFloatMatrix(): FloatArray {
                val out = FloatArray(9)
                var index = 0
                for (row in 0..2) {
                    for (column in 0..2) {
                        val rational = getElement(column, row)
                        out[index++] = if (rational.denominator == 0) 0f
                        else rational.numerator.toFloat() / rational.denominator.toFloat()
                    }
                }
                return out
            }

            private fun average(a: Float, b: Float): Float = (a + b) * 0.5f
            private fun average4(a: Float, b: Float, c: Float, d: Float): Float = (a + b + c + d) * 0.25f

            private fun liftShadows(value: Float, amount: Float): Float {
                val v = value.coerceAtLeast(0f)
                return (v + amount * v * (1f - min(v, 1f))).coerceAtLeast(0f)
            }

            private fun linearToSrgb8(value: Float): Int {
                val v = value.coerceIn(0f, 1f)
                val encoded = if (v <= 0.0031308f) 12.92f * v
                else 1.055f * v.pow(1f / 2.4f) - 0.055f
                return (encoded * 255f + 0.5f).toInt().coerceIn(0, 255)
            }
        }
    }
}