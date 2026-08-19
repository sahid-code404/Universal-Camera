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
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Fast C1.5 RAW-domain merger.
 *
 * The original prototype intentionally favored correctness/readability over speed and weighted
 * low-separation alignments very weakly. On real handheld bursts that left too much of the noisy
 * reference frame in the result and made a 12 MP / 12-frame merge expensive.
 *
 * This implementation keeps the same full-resolution Bayer output but changes the hot path:
 *  - alignment uses exposure-normalized 2x2 Bayer blocks on a sparse coarse pyramid, then a small
 *    refined search around the best displacement;
 *  - alignment confidence is based mainly on absolute normalized residual (flat/noisy scenes no
 *    longer collapse to the old 0.15 floor simply because several shifts score similarly);
 *  - the full-resolution merge is divided into independent row stripes and processed on multiple
 *    CPU cores;
 *  - Camera2 SENSOR_NOISE_PROFILE, when present, drives inverse-variance temporal weighting and
 *    motion thresholds, so clean same-exposure frames contribute strongly in noisy shadows;
 *  - shorter bracket frames are skipped in non-highlight pixels where they add little SNR.
 *
 * No RGB conversion or spatial blur is performed here. Output remains a full-resolution 16-bit
 * Bayer computational DNG source.
 */
internal object ComputationalRawMerger {
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

    private data class MappedFrame(
        val frame: Frame,
        val buffer: ByteBuffer,
        val noiseProfile: Array<android.util.Pair<Double, Double>>?,
    ) {
        fun fork(): MappedFrame = copy(buffer = buffer.duplicate().order(ByteOrder.nativeOrder()))

        fun raw(x: Int, y: Int): Int =
            buffer.getShort((y * frame.width + x) * BYTES_PER_PIXEL).toInt() and 0xffff
    }

    private data class Stripe(
        val startY: Int,
        val bytes: ByteArray,
        val accepted: Long,
        val rejected: Long,
    )

    fun merge(frames: List<Frame>, characteristics: CameraCharacteristics): Result {
        require(frames.size >= 3)
        val referenceIndex = chooseReference(frames)
        val reference = frames[referenceIndex]
        require(frames.all { it.width == reference.width && it.height == reference.height })

        val mapped = frames.map { frame ->
            val channel = FileChannel.open(frame.file.toPath())
            try {
                MappedFrame(
                    frame = frame,
                    buffer = channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size())
                        .order(ByteOrder.nativeOrder()),
                    noiseProfile = frame.result.get(CaptureResult.SENSOR_NOISE_PROFILE),
                )
            } finally {
                channel.close()
            }
        }

        val referenceMapped = mapped[referenceIndex]
        val alignments = mapped.mapIndexed { index, candidate ->
            if (index == referenceIndex) {
                ComputationalRawEngine.Alignment(0, 0, 1f)
            } else {
                estimateTranslation(referenceMapped, candidate)
            }
        }

        val available = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val workerCount = min(MAX_MERGE_THREADS, max(2, available - 1))
            .coerceAtMost(reference.height)
        val stripeHeight = (reference.height + workerCount - 1) / workerCount
        val executor = Executors.newFixedThreadPool(workerCount) { runnable ->
            Thread(runnable, "OmniCam-RAW-Merge").apply { priority = Thread.NORM_PRIORITY }
        }

        val stripes = try {
            val tasks = (0 until workerCount).mapNotNull { worker ->
                val startY = worker * stripeHeight
                if (startY >= reference.height) return@mapNotNull null
                val endY = min(reference.height, startY + stripeHeight)
                Callable {
                    mergeStripe(
                        mapped = mapped.map { it.fork() },
                        referenceIndex = referenceIndex,
                        alignments = alignments,
                        characteristics = characteristics,
                        startY = startY,
                        endY = endY,
                    )
                }
            }
            executor.invokeAll(tasks).map { it.get() }.sortedBy { it.startY }
        } finally {
            executor.shutdown()
        }

        val output = ByteBuffer.allocateDirect(reference.width * reference.height * BYTES_PER_PIXEL)
            .order(ByteOrder.nativeOrder())
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
        characteristics: CameraCharacteristics,
        startY: Int,
        endY: Int,
    ): Stripe {
        val reference = mapped[referenceIndex]
        val width = reference.frame.width
        val out = ByteArray((endY - startY) * width * BYTES_PER_PIXEL)
        val writer = ByteBuffer.wrap(out).order(ByteOrder.nativeOrder())
        val arrangement = characteristics.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT)
            ?: CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_RGGB
        val refExposureProduct = reference.frame.exposureTimeNs.toDouble().coerceAtLeast(1.0) *
            reference.frame.iso.toDouble().coerceAtLeast(1.0)
        var accepted = 0L
        var rejected = 0L

        for (y in startY until endY) {
            for (x in 0 until width) {
                val refLinear = normalized(reference, x, y)
                val refVariance = variance(reference, refLinear, x, y, arrangement)
                val refWeight = inverseVarianceWeight(refVariance)
                var weighted = refLinear * refWeight
                var weightSum = refWeight

                for (i in mapped.indices) {
                    if (i == referenceIndex) continue
                    val frame = mapped[i]
                    val alignment = alignments[i]
                    if (alignment.confidence < HARD_ALIGNMENT_REJECT_CONFIDENCE) {
                        rejected++
                        continue
                    }

                    val sx = x + alignment.dx
                    val sy = y + alignment.dy
                    if (sx !in 0 until frame.frame.width || sy !in 0 until frame.frame.height) {
                        rejected++
                        continue
                    }

                    val exposureProduct = frame.frame.exposureTimeNs.toDouble().coerceAtLeast(1.0) *
                        frame.frame.iso.toDouble().coerceAtLeast(1.0)
                    val normalizationScale = refExposureProduct / exposureProduct

                    // Short bracket exposures are valuable for clipped highlights but add mostly
                    // amplified shot/read noise in ordinary shadows and midtones.
                    if (normalizationScale > SHORT_EXPOSURE_SCALE_THRESHOLD && refLinear < HIGHLIGHT_USE_THRESHOLD) {
                        continue
                    }

                    val sourceLinear = normalized(frame, sx, sy)
                    val normalized = sourceLinear * normalizationScale
                    val candidateVariance = variance(frame, sourceLinear, sx, sy, arrangement) *
                        normalizationScale * normalizationScale
                    val combinedSigma = sqrt((refVariance + candidateVariance).coerceAtLeast(MIN_VARIANCE))
                    val threshold = max(
                        MIN_MOTION_THRESHOLD,
                        MOTION_SIGMA_MULTIPLIER * combinedSigma + refLinear * BRIGHT_MOTION_ALLOWANCE,
                    )
                    val delta = abs(normalized - refLinear)
                    if (delta > threshold) {
                        rejected++
                        continue
                    }

                    val exposureWeight = when {
                        sourceLinear >= 0.985 -> 0.03
                        sourceLinear >= 0.95 -> 0.18
                        sourceLinear <= 0.002 -> 0.20
                        else -> 1.0
                    }
                    val confidenceWeight = alignment.confidence
                        .coerceIn(MIN_USEFUL_ALIGNMENT_WEIGHT, 1f)
                        .toDouble()
                    val robustWeight = (1.0 - (delta / threshold).coerceIn(0.0, 1.0).let { it * it })
                        .coerceAtLeast(0.08)
                    val weight = inverseVarianceWeight(candidateVariance) *
                        exposureWeight * confidenceWeight * robustWeight

                    weighted += normalized * weight
                    weightSum += weight
                    accepted++
                }

                val merged = (weighted / weightSum.coerceAtLeast(1e-9)).coerceIn(0.0, 1.0)
                val black = blackLevel(reference.frame.blackLevels, x, y).toDouble()
                val white = reference.frame.whiteLevel.toDouble()
                val encoded = (black + merged * (white - black).coerceAtLeast(1.0))
                    .roundToInt().coerceIn(0, 65535)
                writer.putShort(encoded.toShort())
            }
        }
        return Stripe(startY, out, accepted, rejected)
    }

    private fun chooseReference(frames: List<Frame>): Int {
        return frames.indices.maxWithOrNull(
            compareBy<Int> { frames[it].exposureTimeNs }
                .thenByDescending { -frames[it].iso },
        ) ?: 0
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
            radius = COARSE_SEARCH_RADIUS,
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

        val residualConfidence = exp(-refined.score / ALIGNMENT_RESIDUAL_SCALE)
        val separation = if (refined.secondScore.isFinite() && refined.secondScore > 1e-9) {
            ((refined.secondScore - refined.score) / refined.secondScore).coerceIn(0.0, 1.0)
        } else 0.0
        val confidence = max(residualConfidence, separation * 1.5)
            .coerceIn(0.02, 1.0)
            .toFloat()
        return ComputationalRawEngine.Alignment(refined.dx, refined.dy, confidence)
    }

    private data class SearchResult(
        val dx: Int,
        val dy: Int,
        val score: Double,
        val secondScore: Double,
    )

    private fun search(
        reference: MappedFrame,
        candidate: MappedFrame,
        centerDx: Int,
        centerDy: Int,
        radius: Int,
        sampleStep: Int,
    ): SearchResult {
        var bestScore = Double.POSITIVE_INFINITY
        var secondScore = Double.POSITIVE_INFINITY
        var bestDx = centerDx
        var bestDy = centerDy
        val refProduct = reference.frame.exposureTimeNs.toDouble().coerceAtLeast(1.0) *
            reference.frame.iso.toDouble().coerceAtLeast(1.0)
        val candidateProduct = candidate.frame.exposureTimeNs.toDouble().coerceAtLeast(1.0) *
            candidate.frame.iso.toDouble().coerceAtLeast(1.0)
        val scale = refProduct / candidateProduct

        val startDy = even(centerDy - radius)
        val endDy = even(centerDy + radius)
        val startDx = even(centerDx - radius)
        val endDx = even(centerDx + radius)
        var dy = startDy
        while (dy <= endDy) {
            var dx = startDx
            while (dx <= endDx) {
                var error = 0.0
                var count = 0
                var y = even(ALIGNMENT_BORDER)
                val maxY = reference.frame.height - ALIGNMENT_BORDER - 2
                while (y < maxY) {
                    var x = even(ALIGNMENT_BORDER)
                    val maxX = reference.frame.width - ALIGNMENT_BORDER - 2
                    while (x < maxX) {
                        val cx = x + dx
                        val cy = y + dy
                        if (cx >= 0 && cy >= 0 && cx + 1 < candidate.frame.width && cy + 1 < candidate.frame.height) {
                            val a = blockLuma(reference, x, y)
                            val b = blockLuma(candidate, cx, cy) * scale
                            if (a > ALIGNMENT_DARK_FLOOR && b > ALIGNMENT_DARK_FLOOR &&
                                a < ALIGNMENT_CLIP_CEILING && b < ALIGNMENT_CLIP_CEILING
                            ) {
                                error += abs(a - b)
                                count++
                            }
                        }
                        x += sampleStep
                    }
                    y += sampleStep
                }
                val score = if (count >= MIN_ALIGNMENT_SAMPLES) error / count else Double.POSITIVE_INFINITY
                if (score < bestScore) {
                    secondScore = bestScore
                    bestScore = score
                    bestDx = dx
                    bestDy = dy
                } else if (score < secondScore) {
                    secondScore = score
                }
                dx += CFA_PERIOD
            }
            dy += CFA_PERIOD
        }
        return SearchResult(bestDx, bestDy, bestScore, secondScore)
    }

    private fun blockLuma(frame: MappedFrame, x: Int, y: Int): Double {
        val p00 = normalized(frame, x, y)
        val p10 = normalized(frame, x + 1, y)
        val p01 = normalized(frame, x, y + 1)
        val p11 = normalized(frame, x + 1, y + 1)
        return (p00 + p10 + p01 + p11) * 0.25
    }

    private fun normalized(frame: MappedFrame, x: Int, y: Int): Double {
        val black = blackLevel(frame.frame.blackLevels, x, y).toDouble()
        val white = frame.frame.whiteLevel.toDouble()
        return ((frame.raw(x, y) - black) / (white - black).coerceAtLeast(1.0))
            .coerceIn(0.0, 1.0)
    }

    private fun variance(
        frame: MappedFrame,
        signal: Double,
        x: Int,
        y: Int,
        arrangement: Int,
    ): Double {
        val profile = frame.noiseProfile
        if (profile != null && profile.isNotEmpty()) {
            val index = noiseChannelIndex(arrangement, x, y).coerceIn(0, profile.lastIndex)
            val pair = profile[index]
            return (pair.first * signal.coerceAtLeast(0.0) + pair.second)
                .coerceAtLeast(MIN_VARIANCE)
        }
        return (FALLBACK_READ_VARIANCE + FALLBACK_SHOT_FACTOR * signal.coerceAtLeast(0.0))
            .coerceAtLeast(MIN_VARIANCE)
    }

    private fun inverseVarianceWeight(variance: Double): Double =
        (1.0 / variance.coerceAtLeast(MIN_VARIANCE)).coerceIn(MIN_WEIGHT, MAX_WEIGHT)

    private fun noiseChannelIndex(arrangement: Int, x: Int, y: Int): Int {
        val px = x and 1
        val py = y and 1
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

    private fun blackLevel(levels: IntArray, x: Int, y: Int): Int =
        levels[(y and 1) * CFA_PERIOD + (x and 1)]

    private fun even(value: Int): Int = if ((value and 1) == 0) value else value - 1

    private const val BYTES_PER_PIXEL = 2
    private const val CFA_PERIOD = 2
    private const val MAX_MERGE_THREADS = 4

    private const val COARSE_SEARCH_RADIUS = 10
    private const val COARSE_SAMPLE_STEP = 32
    private const val REFINE_RADIUS = 2
    private const val REFINE_SAMPLE_STEP = 16
    private const val ALIGNMENT_BORDER = 64
    private const val MIN_ALIGNMENT_SAMPLES = 64
    private const val ALIGNMENT_DARK_FLOOR = 0.015
    private const val ALIGNMENT_CLIP_CEILING = 0.94
    private const val ALIGNMENT_RESIDUAL_SCALE = 0.055
    private const val HARD_ALIGNMENT_REJECT_CONFIDENCE = 0.10f
    private const val MIN_USEFUL_ALIGNMENT_WEIGHT = 0.45f

    private const val SHORT_EXPOSURE_SCALE_THRESHOLD = 1.45
    private const val HIGHLIGHT_USE_THRESHOLD = 0.78
    private const val MIN_MOTION_THRESHOLD = 0.018
    private const val MOTION_SIGMA_MULTIPLIER = 3.5
    private const val BRIGHT_MOTION_ALLOWANCE = 0.035

    private const val MIN_VARIANCE = 1e-7
    private const val FALLBACK_READ_VARIANCE = 0.000018
    private const val FALLBACK_SHOT_FACTOR = 0.0018
    private const val MIN_WEIGHT = 1.0
    private const val MAX_WEIGHT = 96.0
}