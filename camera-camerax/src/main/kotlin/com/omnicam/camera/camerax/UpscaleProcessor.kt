package com.omnicam.camera.camerax

import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import java.io.OutputStream

/**
 * Small, dependency-free processed-image upscaler.
 *
 * It deliberately does not claim to recreate sensor detail. The goal is a fast, deterministic
 * 2x output path for camera HALs that only expose a binned/low-resolution processed stream.
 * Luma and chroma are both scaled in their native planes to avoid RGB round trips before HEIF.
 */
internal object UpscaleProcessor {
    data class I420Frame(
        val bytes: ByteArray,
        val width: Int,
        val height: Int,
    )

    fun scale2x(frame: I420Frame): I420Frame {
        val srcW = frame.width and -2
        val srcH = frame.height and -2
        require(srcW >= 2 && srcH >= 2) { "I420 frame is too small" }

        val dstW = srcW * 2
        val dstH = srcH * 2
        val srcYSize = srcW * srcH
        val srcUvW = srcW / 2
        val srcUvH = srcH / 2
        val srcUvSize = srcUvW * srcUvH
        val dstYSize = dstW * dstH
        val dstUvW = dstW / 2
        val dstUvH = dstH / 2
        val dstUvSize = dstUvW * dstUvH
        val out = ByteArray(dstYSize + dstUvSize * 2)

        scalePlane2x(
            source = frame.bytes,
            sourceOffset = 0,
            sourceWidth = srcW,
            sourceHeight = srcH,
            destination = out,
            destinationOffset = 0,
        )
        scalePlane2x(
            source = frame.bytes,
            sourceOffset = srcYSize,
            sourceWidth = srcUvW,
            sourceHeight = srcUvH,
            destination = out,
            destinationOffset = dstYSize,
        )
        scalePlane2x(
            source = frame.bytes,
            sourceOffset = srcYSize + srcUvSize,
            sourceWidth = srcUvW,
            sourceHeight = srcUvH,
            destination = out,
            destinationOffset = dstYSize + dstUvSize,
        )
        return I420Frame(out, dstW, dstH)
    }

    /**
     * Fast separable 2x interpolation. Existing samples land on even coordinates and half-pixel
     * samples are averaged from their nearest neighbours. It is intentionally conservative and
     * avoids haloing/oversharpening that would manufacture detail around edges.
     */
    private fun scalePlane2x(
        source: ByteArray,
        sourceOffset: Int,
        sourceWidth: Int,
        sourceHeight: Int,
        destination: ByteArray,
        destinationOffset: Int,
    ) {
        val dstWidth = sourceWidth * 2
        val dstHeight = sourceHeight * 2

        fun sample(x: Int, y: Int): Int {
            val cx = x.coerceIn(0, sourceWidth - 1)
            val cy = y.coerceIn(0, sourceHeight - 1)
            return source[sourceOffset + cy * sourceWidth + cx].toInt() and 0xff
        }

        for (dy in 0 until dstHeight) {
            val sy = dy / 2
            val yOdd = (dy and 1) != 0
            val row = destinationOffset + dy * dstWidth
            for (dx in 0 until dstWidth) {
                val sx = dx / 2
                val xOdd = (dx and 1) != 0
                val value = when {
                    !xOdd && !yOdd -> sample(sx, sy)
                    xOdd && !yOdd -> (sample(sx, sy) + sample(sx + 1, sy) + 1) / 2
                    !xOdd && yOdd -> (sample(sx, sy) + sample(sx, sy + 1) + 1) / 2
                    else -> (
                        sample(sx, sy) +
                            sample(sx + 1, sy) +
                            sample(sx, sy + 1) +
                            sample(sx + 1, sy + 1) + 2
                        ) / 4
                }
                destination[row + dx] = value.toByte()
            }
        }
    }

    fun toNv21(frame: I420Frame): ByteArray {
        val ySize = frame.width * frame.height
        val uvW = frame.width / 2
        val uvH = frame.height / 2
        val uvSize = uvW * uvH
        val uOffset = ySize
        val vOffset = ySize + uvSize
        val out = ByteArray(ySize + uvSize * 2)
        frame.bytes.copyInto(out, 0, 0, ySize)
        var dst = ySize
        for (i in 0 until uvSize) {
            out[dst++] = frame.bytes[vOffset + i]
            out[dst++] = frame.bytes[uOffset + i]
        }
        return out
    }

    fun writeJpeg(
        frame: I420Frame,
        quality: Int,
        output: OutputStream,
    ) {
        val nv21 = toNv21(frame)
        val image = YuvImage(nv21, ImageFormat.NV21, frame.width, frame.height, null)
        check(image.compressToJpeg(Rect(0, 0, frame.width, frame.height), quality.coerceIn(1, 100), output)) {
            "Android YUV JPEG encoder failed"
        }
    }

    fun rotate(frame: I420Frame, degrees: Int): I420Frame {
        val normalized = ((degrees % 360) + 360) % 360
        if (normalized == 0) return frame
        require(normalized == 90 || normalized == 180 || normalized == 270)

        val ySize = frame.width * frame.height
        val uvW = frame.width / 2
        val uvH = frame.height / 2
        val uvSize = uvW * uvH
        val rotatedY = rotatePlane(frame.bytes, 0, frame.width, frame.height, normalized)
        val rotatedU = rotatePlane(frame.bytes, ySize, uvW, uvH, normalized)
        val rotatedV = rotatePlane(frame.bytes, ySize + uvSize, uvW, uvH, normalized)
        val outW = if (normalized == 90 || normalized == 270) frame.height else frame.width
        val outH = if (normalized == 90 || normalized == 270) frame.width else frame.height
        return I420Frame(rotatedY + rotatedU + rotatedV, outW, outH)
    }

    private fun rotatePlane(
        source: ByteArray,
        offset: Int,
        width: Int,
        height: Int,
        degrees: Int,
    ): ByteArray {
        val outWidth = if (degrees == 90 || degrees == 270) height else width
        val outHeight = if (degrees == 90 || degrees == 270) width else height
        val out = ByteArray(outWidth * outHeight)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val value = source[offset + y * width + x]
                val (dx, dy) = when (degrees) {
                    90 -> (height - 1 - y) to x
                    180 -> (width - 1 - x) to (height - 1 - y)
                    else -> y to (width - 1 - x)
                }
                out[dy * outWidth + dx] = value
            }
        }
        return out
    }
}
