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
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout

/**
 * Computational RAW acquisition engine.
 *
 * RAW images are paired with TotalCaptureResult by SENSOR_TIMESTAMP and staged immediately into
 * tightly packed file-backed RAW16 buffers so Camera2/ImageReader buffers are released quickly.
 * C1.5 delegates alignment and fusion to ComputationalRawMerger, which performs sparse two-stage
 * Bayer alignment, noise-aware temporal weighting and a multi-core full-resolution merge.
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

                // Most RAW_SENSOR devices use two tightly packed bytes per sample. When row padding
                // exists we still copy complete rows in one channel write instead of per-pixel I/O.
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
        val fusion = ComputationalRawMerger.merge(
            frames = frames.map { frame ->
                ComputationalRawMerger.Frame(
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
            characteristics = characteristics,
        )
        val reference = frames[fusion.referenceIndex]
        return MergeResult(
            width = reference.width,
            height = reference.height,
            pixels16 = fusion.pixels16,
            referenceResult = reference.result,
            referenceCharacteristics = characteristics,
            frameCount = frames.size,
            acceptedSamples = fusion.acceptedSamples,
            rejectedSamples = fusion.rejectedSamples,
            alignments = fusion.alignments,
            whiteLevel = reference.whiteLevel,
        )
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
        const val BURST_TIMEOUT_MS = 30_000L
        const val FRAME_DURATION_MARGIN_NS = 1_000_000L
    }
}