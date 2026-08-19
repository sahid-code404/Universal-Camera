package com.omnicam.camera.camerax

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureResult
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal data class NativeFusionTuning(
    val denoiseStrength: Float,
    val highlightProtection: Float,
)

internal data class NativeFusionResult(
    val merged: ComputationalRawEngine.MergeResult,
    val native: Boolean,
)

/** Native-first fusion adapter with the proven Kotlin merger as a compatibility fallback. */
internal object NativeRawFusion {
    fun merge(
        burst: ComputationalRawEngine.CapturedBurst,
        tuning: NativeFusionTuning,
    ): NativeFusionResult {
        val frames = burst.frames
        require(frames.size >= 3)
        val width = frames.first().width
        val height = frames.first().height
        require(frames.all { it.width == width && it.height == height })

        if (NativeRawBridge.available) {
            runCatching { nativeMerge(frames, burst.characteristics, tuning) }
                .getOrNull()
                ?.let { return NativeFusionResult(it, native = true) }
        }

        val fallback = RealtimeRawMerger.merge(
            frames = frames.map { frame ->
                RealtimeRawMerger.Frame(
                    width = frame.width,
                    height = frame.height,
                    file = frame.file,
                    result = frame.result,
                    exposureTimeNs = frame.exposureTimeNs,
                    iso = frame.iso,
                    blackLevels = frame.blackLevels,
                    whiteLevel = frame.whiteLevel,
                )
            },
            characteristics = burst.characteristics,
        )
        val reference = frames[fallback.referenceIndex]
        return NativeFusionResult(
            merged = ComputationalRawEngine.MergeResult(
                width = reference.width,
                height = reference.height,
                pixels16 = fallback.pixels16,
                referenceResult = reference.result,
                referenceCharacteristics = burst.characteristics,
                frameCount = frames.size,
                acceptedSamples = fallback.acceptedSamples,
                rejectedSamples = fallback.rejectedSamples,
                alignments = fallback.alignments,
                whiteLevel = reference.whiteLevel,
            ),
            native = false,
        )
    }

    private fun nativeMerge(
        frames: List<ComputationalRawEngine.CapturedRawFrame>,
        characteristics: CameraCharacteristics,
        tuning: NativeFusionTuning,
    ): ComputationalRawEngine.MergeResult {
        val width = frames.first().width
        val height = frames.first().height
        val outputBytes = Math.multiplyExact(Math.multiplyExact(width, height), 2)
        val output = ByteBuffer.allocateDirect(outputBytes).order(ByteOrder.nativeOrder())
        val paths = frames.map { it.file.absolutePath }.toTypedArray()
        val exposures = LongArray(frames.size) { frames[it].exposureTimeNs }
        val iso = IntArray(frames.size) { frames[it].iso }
        val black = IntArray(frames.size * 4)
        val white = IntArray(frames.size) { frames[it].whiteLevel }
        val slopes = FloatArray(frames.size * 4) { -1f }
        val offsets = FloatArray(frames.size * 4) { -1f }
        val arrangement = characteristics.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT)
            ?: CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_RGGB

        frames.forEachIndexed { frameIndex, frame ->
            for (cfa in 0 until 4) black[frameIndex * 4 + cfa] = frame.blackLevels[cfa]
            val noise = frame.result.get(CaptureResult.SENSOR_NOISE_PROFILE)
            for (cfa in 0 until 4) {
                val channel = noiseChannelForCfaIndex(arrangement, cfa)
                val pair = noise?.getOrNull(channel)
                slopes[frameIndex * 4 + cfa] = pair?.first?.toFloat() ?: -1f
                offsets[frameIndex * 4 + cfa] = pair?.second?.toFloat() ?: -1f
            }
        }

        val alignmentOut = IntArray(frames.size * 3)
        val stats = LongArray(3)
        val code = NativeRawBridge.mergeRaw16(
            paths = paths,
            width = width,
            height = height,
            exposureTimesNs = exposures,
            isoValues = iso,
            blackLevels = black,
            whiteLevels = white,
            noiseSlopes = slopes,
            noiseOffsets = offsets,
            denoiseStrength = tuning.denoiseStrength.coerceIn(0f, 1f),
            highlightProtection = tuning.highlightProtection.coerceIn(0f, 1f),
            outputRaw16 = output,
            alignmentOut = alignmentOut,
            statsOut = stats,
        )
        check(code == 0) { "Native RAW fusion failed with code $code" }

        val referenceIndex = stats[2].toInt().coerceIn(frames.indices)
        val reference = frames[referenceIndex]
        val alignments = List(frames.size) { index ->
            ComputationalRawEngine.Alignment(
                dx = alignmentOut[index * 3],
                dy = alignmentOut[index * 3 + 1],
                confidence = (alignmentOut[index * 3 + 2] / 32767f).coerceIn(0f, 1f),
            )
        }
        output.position(0)
        output.limit(outputBytes)
        return ComputationalRawEngine.MergeResult(
            width = width,
            height = height,
            pixels16 = output,
            referenceResult = reference.result,
            referenceCharacteristics = characteristics,
            frameCount = frames.size,
            acceptedSamples = stats[0],
            rejectedSamples = stats[1],
            alignments = alignments,
            whiteLevel = reference.whiteLevel,
        )
    }

    private fun noiseChannelForCfaIndex(arrangement: Int, cfa: Int): Int {
        val px = cfa and 1
        val py = (cfa ushr 1) and 1
        return when (arrangement) {
            CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GRBG -> when {
                py == 0 && px == 0 -> 1
                py == 0 -> 0
                py == 1 && px == 0 -> 3
                else -> 2
            }
            CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GBRG -> when {
                py == 0 && px == 0 -> 1
                py == 0 -> 3
                py == 1 && px == 0 -> 0
                else -> 2
            }
            CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_BGGR -> when {
                py == 0 && px == 0 -> 3
                py == 0 -> 1
                py == 1 && px == 0 -> 2
                else -> 0
            }
            else -> cfa
        }
    }
}
