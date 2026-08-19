package com.omnicam.camera.camerax

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * C1.6 realtime-friendly Bayer merger.
 *
 * The hot full-resolution path deliberately avoids sqrt/exp and floating-point normalization for
 * every candidate sample. Camera2's noise model is converted once into tiny per-frame/per-CFA LUTs,
 * then the pixel loop uses fixed-point arithmetic, integer motion gates and robust equal weighting.
 * Equal weighting is statistically appropriate for the same-exposure frames that dominate QUALITY
 * and the base stack in HDR/MAX, while short bracket frames are used mainly for highlights.
 *
 * Processing uses only a small number of low-priority worker threads. This is slightly less
 * aggressive than occupying every CPU core, but keeps camera preview, UI and the next RAW burst
 * responsive while a previous shot is being fused in the background.
 */
internal object RealtimeRawMerger {
    data class Frame(
        val width: Int,
        val height: Int,
        val file: File,
        val result: TotalCaptureResult,
        val exposureTimeNs: Long,
        val iso: Int,
        val blackLevels: IntArray,
        val whiteLevel: Int,
    )

    data class Result(
        val referenceIndex: Int,
        val pixels16: ByteBuffer,
        val acceptedSamples: Long,
        val rejectedSamples: Long,
        val alignments: List<ComputationalRawEngine.Alignment>,
    )

    private class MappedFrame(
        val frame: Frame,
        val buffer: ByteBuffer,
        val scaleToReferenceQ16: Int,
        val sameExposure: Boolean,
        val motionThresholdLut: Array<IntArray>,
    ) {
        private val range = IntArray(4) { index ->
            (frame.whiteLevel - frame.blackLevels[index]).coerceAtLeast(1)
        }
        private val normalizeMultiplierQ20 = LongArray(4) { index ->
            ((FULL_SCALE.toLong() shl NORMALIZE_SHIFT) / range[index].toLong())
        }

        fun fork(): MappedFrame = MappedFrame(
            frame = frame,
            buffer = buffer.duplicate().order(ByteOrder.nativeOrder()),
            scaleToReferenceQ16 = scaleToReferenceQ16,
            sameExposure = sameExposure,
            motionThresholdLut = motionThresholdLut,
        )

        fun raw(x: Int, y: Int): Int =
            buffer.getShort((y * frame.width + x) * BYTES_PER_PIXEL).toInt() and 0xffff

        fun normalizedQ16(x: Int, y: Int): Int {
            val cfa = cfaIndex(x, y)
            val signal = (raw(x, y) - frame.blackLevels[cfa]).coerceAtLeast(0)
            return ((signal.toLong() * normalizeMultiplierQ20[cfa]) shr NORMALIZE_SHIFT)
                .toInt().coerceIn(0, FULL_SCALE)
        }

        fun encodeFromNormalizedQ16(value: Int, x: Int, y: Int): Int {
            val cfa = cfaIndex(x, y)
            val encodedSignal = ((value.toLong() * range[cfa].toLong() + HALF_SCALE) shr 16).toInt()
            return (frame.blackLevels[cfa] + encodedSignal).coerceIn(0, 65535)
        }
    }

    private data class Stripe(
        val startY: Int,
        val bytes: ByteArray,
        val accepted: Long,
        val rejected: Long,
    )

    private data class SearchResult(
        val dx: Int,
        val dy: Int,
        val score: Long,
        val secondScore: Long,
        val samples: Int,
    )

    fun merge(frames: List<Frame>, characteristics: CameraCharacteristics): Result {
        require(frames.size >= 3)
        val referenceIndex = chooseReference(frames)
        val referenceFrame = frames[referenceIndex]
        require(frames.all { it.width == referenceFrame.width && it.height == referenceFrame.height })

        val refProduct = exposureProduct(referenceFrame)
        val arrangement = characteristics.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT)
            ?: CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_RGGB
        val refNoise = noiseProfile(referenceFrame)

        val mapped = frames.map { frame ->
            val product = exposureProduct(frame)
            val scale = (refProduct / product).coerceIn(0.125, 16.0)
            val scaleQ16 = (scale * Q16).roundToInt().coerceAtLeast(1)
            val channel = FileChannel.open(frame.file.toPath())
            try {
                val buffer = channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size())
                    .order(ByteOrder.nativeOrder())
                MappedFrame(
                    frame = frame,
                    buffer = buffer,
                    scaleToReferenceQ16 = scaleQ16,
                    sameExposure = scale in SAME_EXPOSURE_MIN..SAME_EXPOSURE_MAX,
                    motionThresholdLut = buildMotionThresholdLut(
                        referenceNoise = refNoise,
                        candidateNoise = noiseProfile(frame),
                        scale = scale,
                        arrangement = arrangement,
                    ),
                )
            } finally {
                channel.close()
            }
        }

        val reference = mapped[referenceIndex]
        val alignments = mapped.mapIndexed { index, candidate ->
            if (index == referenceIndex) ComputationalRawEngine.Alignment(0, 0, 1f)
            else estimateTranslation(reference, candidate)
        }

        val available = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val workerCount = min(MAX_BACKGROUND_MERGE_THREADS, max(1, available / 2))
            .coerceAtMost(referenceFrame.height)
        val stripeHeight = (referenceFrame.height + workerCount - 1) / workerCount
        val executor = Executors.newFixedThreadPool(workerCount) { runnable ->
            Thread(runnable, "OmniCam-C16-RAW").apply {
                priority = (Thread.NORM_PRIORITY - 2).coerceAtLeast(Thread.MIN_PRIORITY)
            }
        }

        val stripes = try {
            val tasks = (0 until workerCount).mapNotNull { worker ->
                val startY = worker * stripeHeight
                if (startY >= referenceFrame.height) return@mapNotNull null
                val endY = min(referenceFrame.height, startY + stripeHeight)
                Callable {
                    mergeStripe(
                        mapped = mapped.map { it.fork() },
                        referenceIndex = referenceIndex,
                        alignments = alignments,
                        startY = startY,
                        endY = endY,
                    )
                }
            }
            executor.invokeAll(tasks).map { it.get() }.sortedBy { it.startY }
        } finally {
            executor.shutdown()
        }

        val output = ByteBuffer.allocateDirect(
            referenceFrame.width * referenceFrame.height * BYTES_PER_PIXEL,
        ).order(ByteOrder.nativeOrder())
        var accepted = 0L
        var rejected = 0L
        stripes.forEach { stripe ->
            output.put(stripe.bytes)
            accepted += stripe.accepted
            rejected += stripe.rejected
        }
        output.flip()

        return Result(
            referenceIndex = referenceIndex,
            pixels16 = output,
            acceptedSamples = accepted,
            rejectedSamples = rejected,
            alignments = alignments,
        )
    }

    private fun mergeStripe(
        mapped: List<MappedFrame>,
        referenceIndex: Int,
        alignments: List<ComputationalRawEngine.Alignment>,
        startY: Int,
        endY: Int,
    ): Stripe {
        val reference = mapped[referenceIndex]
        val width = reference.frame.width
        val bytes = ByteArray((endY - startY) * width * BYTES_PER_PIXEL)
        val writer = ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder())
        var accepted = 0L
        var rejected = 0L

        for (y in startY until endY) {
            for (x in 0 until width) {
                val ref = reference.normalizedQ16(x, y)
                var weighted = ref.toLong() * REFERENCE_WEIGHT
                var weightSum = REFERENCE_WEIGHT

                for (i in mapped.indices) {
                    if (i == referenceIndex) continue
                    val candidate = mapped[i]
                    val alignment = alignments[i]
                    if (alignment.confidence < HARD_ALIGNMENT_REJECT_CONFIDENCE) {
                        rejected++
                        continue
                    }
                    val sx = x + alignment.dx
                    val sy = y + alignment.dy
                    if (sx !in 0 until candidate.frame.width || sy !in 0 until candidate.frame.height) {
                        rejected++
                        continue
                    }

                    // Shorter bracket frames are useful for highlight recovery but become noisy when
                    // amplified into ordinary shadows. Same-exposure temporal frames always remain.
                    if (!candidate.sameExposure && ref < HIGHLIGHT_USE_Q16) continue

                    val source = candidate.normalizedQ16(sx, sy)
                    if (source >= SOURCE_CLIP_Q16) continue
                    var normalized = ((source.toLong() * candidate.scaleToReferenceQ16 + HALF_SCALE) shr 16)
                        .toInt()
                    normalized = normalized.coerceIn(0, FULL_SCALE * 2)

                    val delta = abs(normalized - ref)
                    val cfa = cfaIndex(x, y)
                    val bucket = (ref ushr LUT_BUCKET_SHIFT).coerceIn(0, LUT_SIZE - 1)
                    val threshold = candidate.motionThresholdLut[cfa][bucket]
                    if (delta > threshold) {
                        rejected++
                        continue
                    }

                    var weight = if (candidate.sameExposure) SAME_EXPOSURE_WEIGHT else BRACKET_WEIGHT
                    if (alignment.confidence < MEDIUM_ALIGNMENT_CONFIDENCE) weight = max(1, weight / 2)
                    if (delta > threshold / 2) weight = max(1, weight / 2)
                    if (source < SOURCE_BLACK_Q16) weight = max(1, weight / 2)

                    weighted += normalized.toLong() * weight
                    weightSum += weight
                    accepted++
                }

                val merged = (weighted / weightSum.coerceAtLeast(1)).toInt().coerceIn(0, FULL_SCALE)
                writer.putShort(reference.encodeFromNormalizedQ16(merged, x, y).toShort())
            }
            if ((y and YIELD_ROW_MASK) == 0) Thread.yield()
        }
        return Stripe(startY, bytes, accepted, rejected)
    }

    private fun estimateTranslation(
        reference: MappedFrame,
        candidate: MappedFrame,
    ): ComputationalRawEngine.Alignment {
        val coarse = search(
            reference = reference,
            candidate = candidate,
            centerDx = 0,
            centerDy = 0,
            radius = COARSE_RADIUS,
            sampleStep = COARSE_SAMPLE_STEP,
        )
        val refined = search(
            reference = reference,
            candidate = candidate,
            centerDx = coarse.dx,
            centerDy = coarse.dy,
            radius = REFINE_RADIUS,
            sampleStep = REFINE_SAMPLE_STEP,
        )
        if (refined.samples <= 0 || refined.score == Long.MAX_VALUE) {
            return ComputationalRawEngine.Alignment(refined.dx, refined.dy, 0f)
        }
        val meanResidual = refined.score.toDouble() / refined.samples.toDouble() / FULL_SCALE.toDouble()
        val residualConfidence = (1.0 - meanResidual / ALIGNMENT_BAD_RESIDUAL)
            .coerceIn(0.0, 1.0)
        val separation = if (refined.secondScore > 0 && refined.secondScore < Long.MAX_VALUE) {
            ((refined.secondScore - refined.score).toDouble() / refined.secondScore.toDouble())
                .coerceIn(0.0, 1.0)
        } else 0.0
        val confidence = max(residualConfidence, separation * 1.3).coerceIn(0.0, 1.0)
        return ComputationalRawEngine.Alignment(refined.dx, refined.dy, confidence.toFloat())
    }

    private fun search(
        reference: MappedFrame,
        candidate: MappedFrame,
        centerDx: Int,
        centerDy: Int,
        radius: Int,
        sampleStep: Int,
    ): SearchResult {
        var bestScore = Long.MAX_VALUE
        var secondScore = Long.MAX_VALUE
        var bestDx = even(centerDx)
        var bestDy = even(centerDy)
        var bestSamples = 0

        var dy = even(centerDy - radius)
        val maxDy = even(centerDy + radius)
        while (dy <= maxDy) {
            var dx = even(centerDx - radius)
            val maxDx = even(centerDx + radius)
            while (dx <= maxDx) {
                var error = 0L
                var samples = 0
                var y = even(ALIGNMENT_BORDER)
                val stopY = reference.frame.height - ALIGNMENT_BORDER - 2
                while (y < stopY) {
                    var x = even(ALIGNMENT_BORDER)
                    val stopX = reference.frame.width - ALIGNMENT_BORDER - 2
                    while (x < stopX) {
                        val cx = x + dx
                        val cy = y + dy
                        if (cx >= 0 && cy >= 0 && cx + 1 < candidate.frame.width && cy + 1 < candidate.frame.height) {
                            val a = blockLumaQ16(reference, x, y)
                            val source = blockLumaQ16(candidate, cx, cy)
                            val b = ((source.toLong() * candidate.scaleToReferenceQ16 + HALF_SCALE) shr 16)
                                .toInt()
                            if (a in ALIGNMENT_DARK_Q16..ALIGNMENT_LIGHT_Q16 &&
                                b in ALIGNMENT_DARK_Q16..ALIGNMENT_LIGHT_Q16
                            ) {
                                error += abs(a - b).toLong()
                                samples++
                            }
                        }
                        x += sampleStep
                    }
                    y += sampleStep
                }
                val score = if (samples >= MIN_ALIGNMENT_SAMPLES) error / samples else Long.MAX_VALUE
                if (score < bestScore) {
                    secondScore = bestScore
                    bestScore = score
                    bestDx = dx
                    bestDy = dy
                    bestSamples = samples
                } else if (score < secondScore) {
                    secondScore = score
                }
                dx += CFA_PERIOD
            }
            dy += CFA_PERIOD
        }
        return SearchResult(bestDx, bestDy, bestScore, secondScore, bestSamples)
    }

    private fun blockLumaQ16(frame: MappedFrame, x: Int, y: Int): Int =
        (frame.normalizedQ16(x, y) +
            frame.normalizedQ16(x + 1, y) +
            frame.normalizedQ16(x, y + 1) +
            frame.normalizedQ16(x + 1, y + 1)) ushr 2

    private fun buildMotionThresholdLut(
        referenceNoise: Array<android.util.Pair<Double, Double>>?,
        candidateNoise: Array<android.util.Pair<Double, Double>>?,
        scale: Double,
        arrangement: Int,
    ): Array<IntArray> {
        val out = Array(4) { IntArray(LUT_SIZE) }
        for (cfa in 0 until 4) {
            val noiseChannel = noiseChannelForCfaIndex(arrangement, cfa)
            val refPair = referenceNoise?.getOrNull(noiseChannel)
            val candidatePair = candidateNoise?.getOrNull(noiseChannel)
            for (bucket in 0 until LUT_SIZE) {
                val refSignal = bucket.toDouble() / (LUT_SIZE - 1).toDouble()
                val candidateSignal = (refSignal / scale.coerceAtLeast(0.125)).coerceIn(0.0, 1.0)
                val refVariance = noiseVariance(refPair, refSignal)
                val candidateVariance = noiseVariance(candidatePair, candidateSignal) * scale * scale
                val sigma = sqrt((refVariance + candidateVariance).coerceAtLeast(MIN_VARIANCE))
                val threshold = max(
                    MIN_MOTION_THRESHOLD,
                    NOISE_SIGMA_MULTIPLIER * sigma + refSignal * BRIGHT_MOTION_ALLOWANCE,
                )
                out[cfa][bucket] = (threshold * FULL_SCALE).roundToInt()
                    .coerceIn(MIN_THRESHOLD_Q16, MAX_THRESHOLD_Q16)
            }
        }
        return out
    }

    private fun noiseVariance(
        pair: android.util.Pair<Double, Double>?,
        signal: Double,
    ): Double = if (pair != null) {
        (pair.first * signal.coerceAtLeast(0.0) + pair.second).coerceAtLeast(MIN_VARIANCE)
    } else {
        (FALLBACK_READ_VARIANCE + FALLBACK_SHOT_FACTOR * signal.coerceAtLeast(0.0))
            .coerceAtLeast(MIN_VARIANCE)
    }

    private fun noiseProfile(frame: Frame): Array<android.util.Pair<Double, Double>>? =
        frame.result.get(CaptureResult.SENSOR_NOISE_PROFILE)

    private fun chooseReference(frames: List<Frame>): Int =
        frames.indices.maxWithOrNull(
            compareBy<Int> { frames[it].exposureTimeNs }
                .thenByDescending { -frames[it].iso },
        ) ?: 0

    private fun exposureProduct(frame: Frame): Double =
        frame.exposureTimeNs.toDouble().coerceAtLeast(1.0) * frame.iso.toDouble().coerceAtLeast(1.0)

    private fun cfaIndex(x: Int, y: Int): Int = ((y and 1) shl 1) or (x and 1)

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
            else -> when {
                py == 0 && px == 0 -> 0
                py == 0 -> 1
                py == 1 && px == 0 -> 2
                else -> 3
            }
        }
    }

    private fun even(value: Int): Int = value - (value and 1)

    private companion object {
        const val BYTES_PER_PIXEL = 2
        const val CFA_PERIOD = 2
        const val NORMALIZE_SHIFT = 20
        const val FULL_SCALE = 65535
        const val HALF_SCALE = 32768L
        const val Q16 = 65536.0

        const val MAX_BACKGROUND_MERGE_THREADS = 3
        const val YIELD_ROW_MASK = 63

        const val REFERENCE_WEIGHT = 4
        const val SAME_EXPOSURE_WEIGHT = 4
        const val BRACKET_WEIGHT = 1
        const val SAME_EXPOSURE_MIN = 0.82
        const val SAME_EXPOSURE_MAX = 1.22
        const val HIGHLIGHT_USE_Q16 = 50_000
        const val SOURCE_CLIP_Q16 = 64_000
        const val SOURCE_BLACK_Q16 = 160

        const val HARD_ALIGNMENT_REJECT_CONFIDENCE = 0.08f
        const val MEDIUM_ALIGNMENT_CONFIDENCE = 0.30f
        const val COARSE_RADIUS = 12
        const val REFINE_RADIUS = 2
        const val COARSE_SAMPLE_STEP = 48
        const val REFINE_SAMPLE_STEP = 20
        const val ALIGNMENT_BORDER = 64
        const val MIN_ALIGNMENT_SAMPLES = 128
        const val ALIGNMENT_BAD_RESIDUAL = 0.10
        const val ALIGNMENT_DARK_Q16 = 650
        const val ALIGNMENT_LIGHT_Q16 = 62_000

        const val LUT_SIZE = 256
        const val LUT_BUCKET_SHIFT = 8
        const val MIN_VARIANCE = 1e-9
        const val FALLBACK_READ_VARIANCE = 0.000025
        const val FALLBACK_SHOT_FACTOR = 0.0016
        const val NOISE_SIGMA_MULTIPLIER = 3.5
        const val MIN_MOTION_THRESHOLD = 0.018
        const val BRIGHT_MOTION_ALLOWANCE = 0.035
        const val MIN_THRESHOLD_Q16 = 900
        const val MAX_THRESHOLD_Q16 = 12_000
    }
}
