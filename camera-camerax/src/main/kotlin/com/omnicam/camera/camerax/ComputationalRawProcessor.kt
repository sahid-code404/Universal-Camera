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
import android.util.Rational
import androidx.heifwriter.HeifWriter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Phase C2.1 CPU software ISP.
 *
 * C2.1 fixes two important mistakes from the first C2 prototype:
 *  - white-balance gains are applied to the individual Bayer samples before demosaic, matching
 *    Camera2's documented RAW color pipeline;
 *  - RAW denoising is driven by SENSOR_NOISE_PROFILE when available and stays on same-CFA samples,
 *    so shadows can be cleaned without smearing color edges with an RGB-domain blur.
 *
 * The fused C1 Bayer buffer remains the source. This processor never asks the OEM JPEG/YUV ISP for
 * the final photo.
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
        val renderer = RawRenderer(merged, preset)
        val bitmap = renderer.render()
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
            put(MediaStore.Images.Media.DISPLAY_NAME, "OMNI_C21_${preset.name}_${timestamp}.heic")
            put(MediaStore.Images.Media.MIME_TYPE, "image/heic")
            put(MediaStore.Images.Media.WIDTH, bitmap.width)
            put(MediaStore.Images.Media.HEIGHT, bitmap.height)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/OmniCam/C-RAW")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("MediaStore could not create the C2.1 HEIF output")
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

    private class RawRenderer(
        private val merged: ComputationalRawEngine.MergeResult,
        private val preset: ComputationalRawPreset,
    ) {
        private val width = merged.width
        private val height = merged.height
        private val raw: ByteBuffer = merged.pixels16.duplicate().order(ByteOrder.nativeOrder())
        private val characteristics = merged.referenceCharacteristics
        private val result = merged.referenceResult
        private val whiteLevel = merged.whiteLevel.toFloat().coerceAtLeast(1f)
        private val black = readBlackLevels(characteristics)
        private val arrangement = characteristics.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT)
            ?: CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_RGGB

        private val captureGains = result.get(CaptureResult.COLOR_CORRECTION_GAINS)
        private val neutralPoint = result.get(CaptureResult.SENSOR_NEUTRAL_COLOR_POINT)
        private val wb = buildWhiteBalance(captureGains, neutralPoint)

        private val captureTransform = result.get(CaptureResult.COLOR_CORRECTION_TRANSFORM)
        private val captureMatrix = captureTransform?.toFloatMatrix()?.takeIf(::isSaneMatrix)
        private val fallbackMatrix = buildStaticCameraToSrgb(characteristics)
        private val matrix = captureMatrix ?: fallbackMatrix ?: IDENTITY_MATRIX

        private val noiseProfile = result.get(CaptureResult.SENSOR_NOISE_PROFILE)
        private val iso = result.get(CaptureResult.SENSOR_SENSITIVITY) ?: 100

        val hasCameraWhiteBalance: Boolean = captureGains != null || neutralPoint != null
        val hasCameraColorTransform: Boolean = captureMatrix != null || fallbackMatrix != null

        /**
         * Full-resolution, white-balanced, same-CFA denoised RAW plane. One FloatArray costs about
         * 48 MB at 12 MP; that is intentional for the quality-first C-RAW lab and avoids multiple
         * full RGB float images being resident at once.
         */
        private val preparedRaw: FloatArray by lazy(LazyThreadSafetyMode.NONE) { prepareRaw() }

        fun render(): Bitmap {
            // Build/denoise RAW before exposure statistics so the tone decision is not driven by
            // isolated hot pixels or chroma noise.
            preparedRaw
            val exposure = estimateDisplayExposure()
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val row = IntArray(width)
            val shoulder = when (preset) {
                ComputationalRawPreset.QUALITY -> 2.4f
                ComputationalRawPreset.HDR -> 3.8f
                ComputationalRawPreset.MAX -> 5.0f
            }
            val saturation = when (preset) {
                ComputationalRawPreset.QUALITY -> 1.10f
                ComputationalRawPreset.HDR -> 1.15f
                ComputationalRawPreset.MAX -> 1.18f
            }
            val contrast = when (preset) {
                ComputationalRawPreset.QUALITY -> 1.04f
                ComputationalRawPreset.HDR -> 1.06f
                ComputationalRawPreset.MAX -> 1.07f
            }

            for (y in 0 until height) {
                for (x in 0 until width) {
                    val sensor = demosaic(x, y)
                    var r = matrix[0] * sensor[0] + matrix[1] * sensor[1] + matrix[2] * sensor[2]
                    var g = matrix[3] * sensor[0] + matrix[4] * sensor[1] + matrix[5] * sensor[2]
                    var b = matrix[6] * sensor[0] + matrix[7] * sensor[1] + matrix[8] * sensor[2]
                    r = max(0f, r)
                    g = max(0f, g)
                    b = max(0f, b)

                    r *= exposure
                    g *= exposure
                    b *= exposure

                    val linearLuma = max(0f, luma(r, g, b))
                    val mappedLuma = filmic(linearLuma, shoulder)
                    val toneScale = if (linearLuma > 1e-7f) mappedLuma / linearLuma else 1f
                    r *= toneScale
                    g *= toneScale
                    b *= toneScale

                    // Preserve the camera matrix's hue while adding only a restrained vibrance.
                    // Raw sensor color without an OEM look is naturally flatter than a phone JPEG;
                    // this restores perceptual color without clipping channels independently.
                    val l = luma(r, g, b)
                    val chroma = (max(r, max(g, b)) - min(r, min(g, b))).coerceIn(0f, 1f)
                    val vibrance = saturation + (1f - chroma) * 0.06f
                    r = l + (r - l) * vibrance
                    g = l + (g - l) * vibrance
                    b = l + (b - l) * vibrance

                    val sr = applyDisplayContrast(linearToSrgb(r), contrast)
                    val sg = applyDisplayContrast(linearToSrgb(g), contrast)
                    val sb = applyDisplayContrast(linearToSrgb(b), contrast)
                    row[x] = (0xff shl 24) or
                        ((sr * 255f + 0.5f).toInt().coerceIn(0, 255) shl 16) or
                        ((sg * 255f + 0.5f).toInt().coerceIn(0, 255) shl 8) or
                        (sb * 255f + 0.5f).toInt().coerceIn(0, 255)
                }
                bitmap.setPixels(row, 0, width, 0, y, width, 1)
            }
            return bitmap
        }

        /** Normalize, Bayer-WB, hot-pixel reject and denoise only against same-CFA neighbours. */
        private fun prepareRaw(): FloatArray {
            val out = FloatArray(width * height)
            val denoiseStrength = when (preset) {
                ComputationalRawPreset.QUALITY -> 1.75f
                ComputationalRawPreset.HDR -> 2.10f
                ComputationalRawPreset.MAX -> 2.35f
            }
            val offsets = intArrayOf(
                -2, 0,
                2, 0,
                0, -2,
                0, 2,
                -2, -2,
                2, -2,
                -2, 2,
                2, 2,
            )

            for (y in 0 until height) {
                for (x in 0 until width) {
                    val center = normalizedWbSample(x, y)
                    val sigma = noiseSigma(x, y, center).coerceAtLeast(0.0025f)
                    val edgeThreshold = (sigma * denoiseStrength + 0.0025f).coerceAtMost(0.16f)

                    var weighted = center * 2.25f
                    var weightSum = 2.25f
                    val cardinals = FloatArray(4)
                    var cardinalCount = 0
                    var offsetIndex = 0
                    while (offsetIndex < offsets.size) {
                        val nx = x + offsets[offsetIndex]
                        val ny = y + offsets[offsetIndex + 1]
                        offsetIndex += 2
                        if (nx !in 0 until width || ny !in 0 until height) continue
                        val neighbour = normalizedWbSample(nx, ny)
                        val delta = abs(neighbour - center)
                        val q = delta / edgeThreshold
                        val similarity = 1f / (1f + q * q * q * q)
                        val spatial = if (nx == x || ny == y) 1f else 0.72f
                        val w = similarity * spatial
                        weighted += neighbour * w
                        weightSum += w
                        if ((nx == x || ny == y) && cardinalCount < cardinals.size) {
                            cardinals[cardinalCount++] = neighbour
                        }
                    }

                    var filtered = weighted / weightSum.coerceAtLeast(1e-5f)
                    if (cardinalCount >= 3) {
                        val median = median(cardinals, cardinalCount)
                        // A single RAW hot pixel can be much brighter/darker than all same-color
                        // neighbours. Replace only clear statistical outliers; real edges survive.
                        if (abs(center - median) > max(0.035f, sigma * 6.0f)) filtered = median
                    }
                    out[y * width + x] = filtered.coerceAtLeast(0f)
                }
            }
            return out
        }

        private fun normalizedWbSample(x: Int, y: Int): Float {
            val index = y * width + x
            val rawValue = raw.getShort(index * 2).toInt() and 0xffff
            val cfa = cfaIndex(x, y)
            val blackLevel = black[cfa].toFloat()
            val normalized = ((rawValue - blackLevel) / (whiteLevel - blackLevel).coerceAtLeast(1f))
                .coerceIn(0f, 1f)
            val gain = when (colorAt(x, y)) {
                Channel.R -> wb[0]
                Channel.B -> wb[3]
                Channel.G -> if ((y and 1) == 0) wb[1] else wb[2]
            }
            return normalized * gain
        }

        private fun noiseSigma(x: Int, y: Int, whiteBalancedSignal: Float): Float {
            val cfa = cfaIndex(x, y)
            val gain = when (colorAt(x, y)) {
                Channel.R -> wb[0]
                Channel.B -> wb[3]
                Channel.G -> if ((y and 1) == 0) wb[1] else wb[2]
            }.coerceAtLeast(0.001f)
            val unbalanced = (whiteBalancedSignal / gain).coerceIn(0f, 1f)
            val pair = noiseProfile?.getOrNull(cfa)
            if (pair != null) {
                val s = pair.first?.toFloat() ?: 0f
                val o = pair.second?.toFloat() ?: 0f
                if (s >= 0f && o >= 0f) {
                    return sqrt(max(0f, s * unbalanced + o)) * gain
                }
            }
            // Conservative fallback when an OEM does not expose NoiseProfile.
            val isoScale = sqrt((iso.coerceAtLeast(50) / 100f).coerceAtLeast(0.5f))
            return (0.0045f + 0.0040f * isoScale) * gain
        }

        private fun estimateDisplayExposure(): Float {
            val values = ArrayList<Float>(4096)
            val step = max(12, min(width, height) / 64)
            var y = step
            while (y < height - step) {
                var x = step
                while (x < width - step) {
                    val sensor = demosaic(x, y)
                    val r = max(0f, matrix[0] * sensor[0] + matrix[1] * sensor[1] + matrix[2] * sensor[2])
                    val g = max(0f, matrix[3] * sensor[0] + matrix[4] * sensor[1] + matrix[5] * sensor[2])
                    val b = max(0f, matrix[6] * sensor[0] + matrix[7] * sensor[1] + matrix[8] * sensor[2])
                    values += luma(r, g, b)
                    x += step
                }
                y += step
            }
            if (values.isEmpty()) return 1f
            values.sort()
            val p50 = values[(values.lastIndex * 0.50f).roundToInt()].coerceAtLeast(0.004f)
            val p99 = values[(values.lastIndex * 0.99f).roundToInt()].coerceAtLeast(0.02f)
            val midTarget = when (preset) {
                ComputationalRawPreset.QUALITY -> 0.19f
                ComputationalRawPreset.HDR -> 0.21f
                ComputationalRawPreset.MAX -> 0.22f
            }
            val midExposure = midTarget / p50
            val highlightExposure = 1.35f / p99
            return min(midExposure, highlightExposure * 1.55f).coerceIn(0.35f, 6.0f)
        }

        /** Edge-directed green interpolation; color planes are already Bayer-WB and denoised. */
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
            return preparedRaw[cy * width + cx]
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

        private fun cfaIndex(x: Int, y: Int): Int = (y and 1) * 2 + (x and 1)
        private enum class Channel { R, G, B }

        companion object {
            private val IDENTITY_MATRIX = floatArrayOf(
                1f, 0f, 0f,
                0f, 1f, 0f,
                0f, 0f, 1f,
            )

            private val XYZ_D50_TO_D65 = floatArrayOf(
                0.9555766f, -0.0230393f, 0.0631636f,
                -0.0282895f, 1.0099416f, 0.0210077f,
                0.0122982f, -0.0204830f, 1.3299098f,
            )
            private val XYZ_D65_TO_SRGB = floatArrayOf(
                3.2404542f, -1.5371385f, -0.4985314f,
                -0.9692660f, 1.8760108f, 0.0415560f,
                0.0556434f, -0.2040259f, 1.0572252f,
            )

            private fun buildWhiteBalance(
                gains: android.hardware.camera2.params.RggbChannelVector?,
                neutral: Array<Rational>?,
            ): FloatArray {
                if (gains != null) {
                    return floatArrayOf(
                        gains.red.coerceIn(0.05f, 16f),
                        gains.greenEven.coerceIn(0.05f, 16f),
                        gains.greenOdd.coerceIn(0.05f, 16f),
                        gains.blue.coerceIn(0.05f, 16f),
                    )
                }
                if (neutral != null && neutral.size >= 3) {
                    val r = neutral[0].toFloatSafe().coerceAtLeast(1e-4f)
                    val g = neutral[1].toFloatSafe().coerceAtLeast(1e-4f)
                    val b = neutral[2].toFloatSafe().coerceAtLeast(1e-4f)
                    return floatArrayOf(
                        (g / r).coerceIn(0.05f, 16f),
                        1f,
                        1f,
                        (g / b).coerceIn(0.05f, 16f),
                    )
                }
                return floatArrayOf(1f, 1f, 1f, 1f)
            }

            private fun buildStaticCameraToSrgb(chars: CameraCharacteristics): FloatArray? {
                val forward = chars.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX1)?.toFloatMatrix()
                    ?: return null
                val calibration = chars.get(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM1)
                    ?.toFloatMatrix() ?: IDENTITY_MATRIX
                val invCalibration = invert3x3(calibration) ?: return null
                val xyzToSrgb = multiply3x3(XYZ_D65_TO_SRGB, XYZ_D50_TO_D65)
                return multiply3x3(xyzToSrgb, multiply3x3(forward, invCalibration))
                    .takeIf(::isSaneMatrix)
            }

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
                var i = 0
                for (row in 0..2) {
                    for (column in 0..2) {
                        val value = getElement(column, row)
                        out[i++] = if (value.denominator == 0) 0f
                        else value.numerator.toFloat() / value.denominator.toFloat()
                    }
                }
                return out
            }

            private fun Rational.toFloatSafe(): Float =
                if (denominator == 0) 0f else numerator.toFloat() / denominator.toFloat()

            private fun isSaneMatrix(m: FloatArray): Boolean {
                if (m.size != 9 || m.any { !it.isFinite() || abs(it) > 8f }) return false
                val determinant =
                    m[0] * (m[4] * m[8] - m[5] * m[7]) -
                        m[1] * (m[3] * m[8] - m[5] * m[6]) +
                        m[2] * (m[3] * m[7] - m[4] * m[6])
                return abs(determinant) > 1e-4f
            }

            private fun multiply3x3(a: FloatArray, b: FloatArray): FloatArray {
                val out = FloatArray(9)
                for (r in 0..2) for (c in 0..2) {
                    var sum = 0f
                    for (k in 0..2) sum += a[r * 3 + k] * b[k * 3 + c]
                    out[r * 3 + c] = sum
                }
                return out
            }

            private fun invert3x3(m: FloatArray): FloatArray? {
                val a = m[0]; val b = m[1]; val c = m[2]
                val d = m[3]; val e = m[4]; val f = m[5]
                val g = m[6]; val h = m[7]; val i = m[8]
                val det = a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g)
                if (!det.isFinite() || abs(det) < 1e-6f) return null
                val inv = 1f / det
                return floatArrayOf(
                    (e * i - f * h) * inv, (c * h - b * i) * inv, (b * f - c * e) * inv,
                    (f * g - d * i) * inv, (a * i - c * g) * inv, (c * d - a * f) * inv,
                    (d * h - e * g) * inv, (b * g - a * h) * inv, (a * e - b * d) * inv,
                )
            }

            private fun median(values: FloatArray, count: Int): Float {
                val copy = values.copyOf(count)
                copy.sort()
                return if ((count and 1) == 1) copy[count / 2]
                else (copy[count / 2 - 1] + copy[count / 2]) * 0.5f
            }

            private fun average(a: Float, b: Float): Float = (a + b) * 0.5f
            private fun average4(a: Float, b: Float, c: Float, d: Float): Float =
                (a + b + c + d) * 0.25f

            private fun luma(r: Float, g: Float, b: Float): Float =
                0.2126f * r + 0.7152f * g + 0.0722f * b

            /** Extended Reinhard/filmic shoulder with an explicit scene-white point. */
            private fun filmic(value: Float, whitePoint: Float): Float {
                val x = max(0f, value)
                val w2 = whitePoint * whitePoint
                val mapped = x * (1f + x / w2) / (1f + x)
                val whiteMapped = whitePoint * (1f + whitePoint / w2) / (1f + whitePoint)
                return (mapped / whiteMapped.coerceAtLeast(1e-5f)).coerceIn(0f, 1f)
            }

            private fun linearToSrgb(value: Float): Float {
                val v = value.coerceIn(0f, 1f)
                return if (v <= 0.0031308f) 12.92f * v
                else 1.055f * v.toDouble().pow(1.0 / 2.4).toFloat() - 0.055f
            }

            private fun applyDisplayContrast(value: Float, contrast: Float): Float {
                val centered = (value - 0.5f) * contrast + 0.5f
                return centered.coerceIn(0f, 1f)
            }
        }
    }
}
