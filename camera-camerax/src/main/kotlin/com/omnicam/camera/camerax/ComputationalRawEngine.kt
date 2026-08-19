package com.omnicam.camera.camerax

import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.util.Range
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout

/**
 * Phase C1 computational RAW engine.
 *
 * RAW images are paired with TotalCaptureResult by SENSOR_TIMESTAMP, packed immediately into
 * file-backed RAW16 scratch buffers, aligned on the Bayer grid, motion/outlier rejected, then
 * fused into one 16-bit Bayer buffer at the reference exposure. C1 deliberately stays in the RAW
 * domain; custom demosaic/color/tone-map and multi-frame super-resolution are later stages.
 */
class ComputationalRawEngine(
    private val cameraHandler: Handler,
    private val imageHandler: Handler,
    private val scratchDir: File,
) {
    data class BurstPlan(
        val frameCount: Int = 8,
        val exposureOffsetsEv: List<Float> = emptyList(),
    ) {
        init {
            require(frameCount in MIN_BURST_FRAMES..MAX_BURST_FRAMES)
            require(exposureOffsetsEv.isEmpty() || exposureOffsetsEv.size == frameCount)
        }
    }

    data class Alignment(
        val dx: Int,
        val dy: Int,
        val confidence: Float,
    )

    data class MergeResult(
        val width: Int,
        val height: Int,
        val pixels16: ByteBuffer,
        val referenceResult: TotalCaptureResult,
        val referenceCharacteristics: CameraCharacteristics,
        val frameCount: Int,
        val acceptedSamples: Long,
        val rejectedSamples: Long,
        val alignments: List<Alignment>,
        val whiteLevel: Int,
    )

    private data class RawPayload(
        val timestampNs: Long,
        val width: Int,
        val height: Int,
        val file: File,
    )

    private data class CapturedRawFrame(
        val width: Int,
        val height: Int,
        val file: File,
        val result: TotalCaptureResult,
        val exposureTimeNs: Long,
        val iso: Int,
        val blackLevels: IntArray,
        val whiteLevel: Int,
    )

    private class RawAccessor(
        private val buffer: MappedByteBuffer,
        val width: Int,
        val height: Int,
    ) {
        init {
            buffer.order(ByteOrder.nativeOrder())
        }

        fun get(x: Int, y: Int): Int =
            buffer.getShort((y * width + x) * BYTES_PER_RAW_PIXEL).toInt() and 0xffff
    }

    suspend fun captureAndMerge(
        device: CameraDevice,
        session: CameraCaptureSession,
        reader: ImageReader,
        characteristics: CameraCharacteristics,
        plan: BurstPlan,
        baseIso: Int,
        baseExposureTimeNs: Long,
        lockedFocusDistanceDiopters: Float?,
        applyCommonSettings: (CaptureRequest.Builder) -> Unit,
        applySensorPixelMode: (CaptureRequest.Builder) -> Unit,
    ): MergeResult {
        require(reader.imageFormat == ImageFormat.RAW_SENSOR)
        scratchDir.mkdirs()
        val frames = captureBurst(
            device = device,
            session = session,
            reader = reader,
            characteristics = characteristics,
            plan = plan,
            baseIso = baseIso,
            baseExposureTimeNs = baseExposureTimeNs,
            lockedFocusDistanceDiopters = lockedFocusDistanceDiopters,
            applyCommonSettings = applyCommonSettings,
            applySensorPixelMode = applySensorPixelMode,
        )
        return try {
            merge(frames, characteristics)
        } finally {
            frames.forEach { runCatching { it.file.delete() } }
        }
    }

    private suspend fun captureBurst(
        device: CameraDevice,
        session: CameraCaptureSession,
        reader: ImageReader,
        characteristics: CameraCharacteristics,
        plan: BurstPlan,
        baseIso: Int,
        baseExposureTimeNs: Long,
        lockedFocusDistanceDiopters: Float?,
        applyCommonSettings: (CaptureRequest.Builder) -> Unit,
        applySensorPixelMode: (CaptureRequest.Builder) -> Unit,
    ): List<CapturedRawFrame> = withTimeout(BURST_TIMEOUT_MS) {
        suspendCancellableCoroutine { continuation ->
            val lock = Any()
            val payloadsByTimestamp = mutableMapOf<Long, RawPayload>()
            val resultsByTimestamp = mutableMapOf<Long, TotalCaptureResult>()
            val completed = mutableListOf<CapturedRawFrame>()
            val targetCount = plan.frameCount
            val blackLevels = staticBlackLevels(characteristics)
            val whiteLevel = characteristics.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL) ?: 65535
            var terminal = false

            fun cleanupLocked(deleteCompleted: Boolean) {
                reader.setOnImageAvailableListener(null, null)
                payloadsByTimestamp.values.forEach { runCatching { it.file.delete() } }
                payloadsByTimestamp.clear()
                resultsByTimestamp.clear()
                if (deleteCompleted) {
                    completed.forEach { runCatching { it.file.delete() } }
                    completed.clear()
                }
            }

            fun fail(error: Throwable) {
                var shouldResume = false
                synchronized(lock) {
                    if (!terminal) {
                        terminal = true
                        cleanupLocked(deleteCompleted = true)
                        shouldResume = continuation.isActive
                    }
                }
                if (shouldResume) continuation.resumeWithException(error)
            }

            fun pairLocked(timestamp: Long): List<CapturedRawFrame>? {
                if (terminal) return null
                val payload = payloadsByTimestamp[timestamp] ?: return null
                val result = resultsByTimestamp[timestamp] ?: return null
                payloadsByTimestamp.remove(timestamp)
                resultsByTimestamp.remove(timestamp)
                completed += CapturedRawFrame(
                    width = payload.width,
                    height = payload.height,
                    file = payload.file,
                    result = result,
                    exposureTimeNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: baseExposureTimeNs,
                    iso = result.get(CaptureResult.SENSOR_SENSITIVITY) ?: baseIso,
                    blackLevels = blackLevels.copyOf(),
                    whiteLevel = whiteLevel,
                )
                if (completed.size < targetCount) return null
                terminal = true
                cleanupLocked(deleteCompleted = false)
                return completed.take(targetCount)
            }

            fun deliverIfComplete(frames: List<CapturedRawFrame>?) {
                if (frames != null && continuation.isActive) continuation.resume(frames)
            }

            reader.setOnImageAvailableListener({ source ->
                val image = runCatching { source.acquireNextImage() }.getOrNull()
                    ?: return@setOnImageAvailableListener
                if (!continuation.isActive) {
                    image.close()
                    return@setOnImageAvailableListener
                }
                val payload = runCatching { stageRawImage(image) }
                image.close()
                payload.onSuccess { staged ->
                    var completion: List<CapturedRawFrame>? = null
                    var deleteStaged = false
                    synchronized(lock) {
                        if (terminal || !continuation.isActive) {
                            deleteStaged = true
                        } else {
                            payloadsByTimestamp[staged.timestampNs] = staged
                            completion = pairLocked(staged.timestampNs)
                        }
                    }
                    if (deleteStaged) staged.file.delete()
                    deliverIfComplete(completion)
                }.onFailure(::fail)
            }, imageHandler)

            val exposureRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
                ?: Range(100_000L, 1_000_000_000L)
            val isoRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
                ?: Range(50, 6400)
            val iso = baseIso.coerceIn(isoRange.lower, isoRange.upper)
            val offsets = plan.exposureOffsetsEv.ifEmpty { defaultOffsets(plan.frameCount) }
            val awbLockSupported = characteristics.get(CameraCharacteristics.CONTROL_AWB_LOCK_AVAILABLE) == true

            val requests = offsets.map { ev ->
                val exposureScale = 2.0.pow(ev.toDouble())
                val exposure = (baseExposureTimeNs.toDouble() * exposureScale).roundToLong()
                    .coerceIn(exposureRange.lower, exposureRange.upper)
                device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                    addTarget(reader.surface)
                    applyCommonSettings(this)
                    set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                    set(CaptureRequest.SENSOR_SENSITIVITY, iso)
                    set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposure)
                    val maxFrameDuration = characteristics.get(CameraCharacteristics.SENSOR_INFO_MAX_FRAME_DURATION)
                        ?: exposure
                    set(
                        CaptureRequest.SENSOR_FRAME_DURATION,
                        max(exposure, min(maxFrameDuration, exposure + FRAME_DURATION_MARGIN_NS)),
                    )
                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                    lockedFocusDistanceDiopters?.let {
                        set(CaptureRequest.LENS_FOCUS_DISTANCE, it.coerceAtLeast(0f))
                    }
                    if (awbLockSupported) set(CaptureRequest.CONTROL_AWB_LOCK, true)
                    applySensorPixelMode(this)
                }.build()
            }

            val callback = object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult,
                ) {
                    if (!continuation.isActive) return
                    val timestamp = result.get(CaptureResult.SENSOR_TIMESTAMP)
                    if (timestamp == null) {
                        fail(IllegalStateException("RAW burst result did not contain SENSOR_TIMESTAMP"))
                        return
                    }
                    var completion: List<CapturedRawFrame>? = null
                    synchronized(lock) {
                        if (!terminal) {
                            resultsByTimestamp[timestamp] = result
                            completion = pairLocked(timestamp)
                        }
                    }
                    deliverIfComplete(completion)
                }

                override fun onCaptureFailed(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    failure: CaptureFailure,
                ) {
                    fail(IllegalStateException("Computational RAW burst failed: reason ${failure.reason}"))
                }
            }

            runCatching { session.captureBurst(requests, callback, cameraHandler) }
                .onFailure(::fail)

            continuation.invokeOnCancellation {
                synchronized(lock) {
                    if (!terminal) {
                        terminal = true
                        cleanupLocked(deleteCompleted = true)
                    }
                }
            }
        }
    }

    private fun stageRawImage(image: Image): RawPayload {
        require(image.format == ImageFormat.RAW_SENSOR)
        val plane = image.planes.single()
        val width = image.width
        val height = image.height
        val timestamp = image.timestamp
        val file = File.createTempFile("omnicam_craw_", ".raw16", scratchDir)
        try {
            FileOutputStream(file).channel.use { channel ->
                val source = plane.buffer.duplicate().order(ByteOrder.nativeOrder())
                val base = source.position()
                val limit = source.limit()
                val rowStride = plane.rowStride
                val pixelStride = plane.pixelStride
                require(pixelStride >= BYTES_PER_RAW_PIXEL) {
                    "Unsupported RAW_SENSOR pixel stride $pixelStride"
                }
                val packedRow = ByteBuffer.allocateDirect(width * BYTES_PER_RAW_PIXEL)
                    .order(ByteOrder.nativeOrder())
                for (y in 0 until height) {
                    val rowStart = base + y * rowStride
                    if (pixelStride == BYTES_PER_RAW_PIXEL && rowStart + width * BYTES_PER_RAW_PIXEL <= limit) {
                        val row = source.duplicate()
                        row.position(rowStart)
                        row.limit(rowStart + width * BYTES_PER_RAW_PIXEL)
                        val slice = row.slice()
                        while (slice.hasRemaining()) channel.write(slice)
                    } else {
                        packedRow.clear()
                        for (x in 0 until width) {
                            val offset = rowStart + x * pixelStride
                            packedRow.putShort(if (offset + 1 < limit) source.getShort(offset) else 0)
                        }
                        packedRow.flip()
                        while (packedRow.hasRemaining()) channel.write(packedRow)
                    }
                }
            }
            return RawPayload(timestamp, width, height, file)
        } catch (error: Throwable) {
            file.delete()
            throw error
        }
    }

    private fun merge(
        frames: List<CapturedRawFrame>,
        characteristics: CameraCharacteristics,
    ): MergeResult {
        require(frames.size >= MIN_BURST_FRAMES)
        val referenceIndex = frames.indices.maxByOrNull { frames[it].exposureTimeNs } ?: 0
        val reference = frames[referenceIndex]
        require(frames.all { it.width == reference.width && it.height == reference.height }) {
            "RAW burst dimensions changed during capture"
        }

        val accessors = frames.map { frame ->
            FileChannel.open(frame.file.toPath()).use { channel ->
                RawAccessor(
                    channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size()),
                    frame.width,
                    frame.height,
                )
            }
        }
        val alignments = frames.mapIndexed { index, frame ->
            if (index == referenceIndex) Alignment(0, 0, 1f)
            else estimateTranslation(reference, accessors[referenceIndex], frame, accessors[index])
        }

        val output = ByteBuffer.allocateDirect(reference.width * reference.height * BYTES_PER_RAW_PIXEL)
            .order(ByteOrder.nativeOrder())
        var accepted = 0L
        var rejected = 0L
        val refWhite = reference.whiteLevel.toFloat()
        val refExposure = reference.exposureTimeNs.toDouble().coerceAtLeast(1.0)
        val refIso = reference.iso.toDouble().coerceAtLeast(1.0)
        val referenceAccessor = accessors[referenceIndex]

        for (y in 0 until reference.height) {
            for (x in 0 until reference.width) {
                val refBlack = blackLevel(reference.blackLevels, x, y).toFloat()
                val refRaw = referenceAccessor.get(x, y)
                val refLinear = ((refRaw - refBlack) / (refWhite - refBlack).coerceAtLeast(1f))
                    .coerceIn(0f, 1f)
                var weighted = refLinear.toDouble()
                var weightSum = 1.0

                for (i in frames.indices) {
                    if (i == referenceIndex) continue
                    val frame = frames[i]
                    val alignment = alignments[i]
                    if (alignment.confidence < MIN_ALIGNMENT_CONFIDENCE) {
                        rejected++
                        continue
                    }
                    val sx = x + alignment.dx
                    val sy = y + alignment.dy
                    if (sx !in 0 until frame.width || sy !in 0 until frame.height) {
                        rejected++
                        continue
                    }
                    val frameBlack = blackLevel(frame.blackLevels, sx, sy).toFloat()
                    val value = accessors[i].get(sx, sy)
                    val linear = ((value - frameBlack) /
                        (frame.whiteLevel - frameBlack).coerceAtLeast(1f)).coerceIn(0f, 1f)
                    val normalized = linear.toDouble() *
                        (refExposure / frame.exposureTimeNs.toDouble().coerceAtLeast(1.0)) *
                        (refIso / frame.iso.toDouble().coerceAtLeast(1.0))
                    val delta = abs(normalized - refLinear.toDouble())
                    val motionThreshold = BASE_MOTION_THRESHOLD + refLinear * BRIGHT_MOTION_ALLOWANCE
                    if (delta > motionThreshold) {
                        rejected++
                        continue
                    }
                    val exposureWeight = when {
                        linear < 0.01f -> 0.15
                        linear > 0.97f -> 0.08
                        linear > 0.90f -> 0.45
                        else -> 1.0
                    }
                    val confidenceWeight = alignment.confidence
                        .coerceIn(MIN_ALIGNMENT_CONFIDENCE, 1f)
                        .toDouble()
                    val weight = exposureWeight * confidenceWeight
                    weighted += normalized * weight
                    weightSum += weight
                    accepted++
                }

                val mergedLinear = (weighted / weightSum).coerceIn(0.0, 1.0)
                val encoded = (refBlack + mergedLinear * (refWhite - refBlack))
                    .roundToLong().coerceIn(0L, 65535L).toInt()
                output.putShort(encoded.toShort())
            }
        }
        output.flip()

        return MergeResult(
            width = reference.width,
            height = reference.height,
            pixels16 = output,
            referenceResult = reference.result,
            referenceCharacteristics = characteristics,
            frameCount = frames.size,
            acceptedSamples = accepted,
            rejectedSamples = rejected,
            alignments = alignments,
            whiteLevel = reference.whiteLevel,
        )
    }

    /** C1 keeps even-pixel shifts only so the 2×2 Bayer CFA phase cannot be swapped. */
    private fun estimateTranslation(
        reference: CapturedRawFrame,
        referenceAccessor: RawAccessor,
        candidate: CapturedRawFrame,
        candidateAccessor: RawAccessor,
    ): Alignment {
        var bestScore = Double.POSITIVE_INFINITY
        var secondScore = Double.POSITIVE_INFINITY
        var bestDx = 0
        var bestDy = 0
        for (dy in -ALIGNMENT_SEARCH_RADIUS..ALIGNMENT_SEARCH_RADIUS step CFA_PERIOD) {
            for (dx in -ALIGNMENT_SEARCH_RADIUS..ALIGNMENT_SEARCH_RADIUS step CFA_PERIOD) {
                var error = 0.0
                var count = 0
                var y = ALIGNMENT_BORDER
                while (y < reference.height - ALIGNMENT_BORDER) {
                    var x = ALIGNMENT_BORDER
                    while (x < reference.width - ALIGNMENT_BORDER) {
                        val cx = x + dx
                        val cy = y + dy
                        if (cx in 0 until candidate.width && cy in 0 until candidate.height) {
                            val refBlack = blackLevel(reference.blackLevels, x, y)
                            val candidateBlack = blackLevel(candidate.blackLevels, cx, cy)
                            val a = referenceAccessor.get(x, y) - refBlack
                            val b = candidateAccessor.get(cx, cy) - candidateBlack
                            val scale = reference.exposureTimeNs.toDouble() * reference.iso /
                                (candidate.exposureTimeNs.toDouble().coerceAtLeast(1.0) *
                                    candidate.iso.coerceAtLeast(1))
                            error += abs(a - b * scale)
                            count++
                        }
                        x += ALIGNMENT_SAMPLE_STEP
                    }
                    y += ALIGNMENT_SAMPLE_STEP
                }
                val score = if (count > 0) error / count else Double.POSITIVE_INFINITY
                if (score < bestScore) {
                    secondScore = bestScore
                    bestScore = score
                    bestDx = dx
                    bestDy = dy
                } else if (score < secondScore) {
                    secondScore = score
                }
            }
        }
        val separation = if (secondScore.isFinite() && secondScore > 0.0) {
            ((secondScore - bestScore) / secondScore).coerceIn(0.0, 1.0)
        } else 0.0
        return Alignment(bestDx, bestDy, max(MIN_ALIGNMENT_CONFIDENCE, separation.toFloat()))
    }

    private fun staticBlackLevels(characteristics: CameraCharacteristics): IntArray {
        val pattern = characteristics.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)
        return if (pattern == null) {
            intArrayOf(0, 0, 0, 0)
        } else {
            intArrayOf(
                pattern.getOffsetForIndex(0, 0),
                pattern.getOffsetForIndex(1, 0),
                pattern.getOffsetForIndex(0, 1),
                pattern.getOffsetForIndex(1, 1),
            )
        }
    }

    private fun blackLevel(levels: IntArray, x: Int, y: Int): Int =
        levels[(y and 1) * CFA_PERIOD + (x and 1)]

    private fun defaultOffsets(frameCount: Int): List<Float> = List(frameCount) { index ->
        when {
            index == 0 -> -2f
            index <= 2 -> -1f
            else -> 0f
        }
    }

    private companion object {
        const val MIN_BURST_FRAMES = 3
        const val MAX_BURST_FRAMES = 12
        const val BYTES_PER_RAW_PIXEL = 2
        const val CFA_PERIOD = 2
        const val BURST_TIMEOUT_MS = 30_000L
        const val FRAME_DURATION_MARGIN_NS = 1_000_000L
        const val ALIGNMENT_SEARCH_RADIUS = 8
        const val ALIGNMENT_BORDER = 48
        const val ALIGNMENT_SAMPLE_STEP = 8
        const val MIN_ALIGNMENT_CONFIDENCE = 0.15f
        const val BASE_MOTION_THRESHOLD = 0.035
        const val BRIGHT_MOTION_ALLOWANCE = 0.08
    }
}
