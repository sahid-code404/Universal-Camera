package com.omnicam.camera.camerax

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Center-crops merged RAW for preview-matched zoom/aspect and optionally scales the Bayer mosaic.
 * Scaling preserves CFA parity by interpolating only among samples of the same Bayer plane.
 */
internal object RawDngTransform {
    data class Result(
        val width: Int,
        val height: Int,
        val pixels16: ByteBuffer,
        val cropLeft: Int,
        val cropTop: Int,
        val scale: Float,
    )

    fun apply(
        merged: ComputationalRawEngine.MergeResult,
        zoomRatio: Float,
        targetAspect: Float?,
        upscaleFactor: Float,
    ): Result {
        val sourceWidth = merged.width and -2
        val sourceHeight = merged.height and -2
        val zoom = zoomRatio.coerceAtLeast(1f)
        var cropWidth = evenFloor((sourceWidth / zoom).roundToInt()).coerceAtLeast(4)
        var cropHeight = evenFloor((sourceHeight / zoom).roundToInt()).coerceAtLeast(4)

        targetAspect?.takeIf { it.isFinite() && it > 0f }?.let { aspect ->
            val current = cropWidth.toFloat() / cropHeight
            if (current > aspect) {
                cropWidth = evenFloor((cropHeight * aspect).roundToInt()).coerceAtLeast(4)
            } else if (current < aspect) {
                cropHeight = evenFloor((cropWidth / aspect).roundToInt()).coerceAtLeast(4)
            }
        }

        cropWidth = min(cropWidth, sourceWidth)
        cropHeight = min(cropHeight, sourceHeight)
        val left = evenFloor((sourceWidth - cropWidth) / 2)
        val top = evenFloor((sourceHeight - cropHeight) / 2)
        val factor = upscaleFactor.coerceIn(1f, 3f)
        val outputWidth = evenFloor((cropWidth * factor).roundToInt()).coerceAtLeast(4)
        val outputHeight = evenFloor((cropHeight * factor).roundToInt()).coerceAtLeast(4)

        if (left == 0 && top == 0 && cropWidth == sourceWidth && cropHeight == sourceHeight && factor <= 1.001f) {
            val duplicate = merged.pixels16.duplicate().order(ByteOrder.nativeOrder())
            duplicate.position(0)
            duplicate.limit(sourceWidth * sourceHeight * 2)
            return Result(sourceWidth, sourceHeight, duplicate, 0, 0, 1f)
        }

        val source = merged.pixels16.duplicate().order(ByteOrder.nativeOrder())
        val output = ByteBuffer.allocateDirect(outputWidth * outputHeight * 2).order(ByteOrder.nativeOrder())
        val out = output.asShortBuffer()

        val sourcePlaneWidth = cropWidth / 2
        val sourcePlaneHeight = cropHeight / 2
        val outputPlaneWidth = outputWidth / 2
        val outputPlaneHeight = outputHeight / 2

        fun sampleSameCfa(cfaX: Int, cfaY: Int, planeX: Float, planeY: Float): Int {
            val x0 = floor(planeX).toInt().coerceIn(0, sourcePlaneWidth - 1)
            val y0 = floor(planeY).toInt().coerceIn(0, sourcePlaneHeight - 1)
            val x1 = min(x0 + 1, sourcePlaneWidth - 1)
            val y1 = min(y0 + 1, sourcePlaneHeight - 1)
            val fx = (planeX - x0).coerceIn(0f, 1f)
            val fy = (planeY - y0).coerceIn(0f, 1f)

            fun value(px: Int, py: Int): Float {
                val sx = left + cfaX + px * 2
                val sy = top + cfaY + py * 2
                return (source.getShort((sy * merged.width + sx) * 2).toInt() and 0xffff).toFloat()
            }

            val a = value(x0, y0)
            val b = value(x1, y0)
            val c = value(x0, y1)
            val d = value(x1, y1)
            val topMix = a + (b - a) * fx
            val bottomMix = c + (d - c) * fx
            return (topMix + (bottomMix - topMix) * fy).roundToInt().coerceIn(0, 65535)
        }

        for (oy in 0 until outputHeight) {
            val cfaY = oy and 1
            val planeYIndex = oy / 2
            val planeY = if (outputPlaneHeight <= 1) 0f
            else planeYIndex.toFloat() * (sourcePlaneHeight - 1) / max(1, outputPlaneHeight - 1)
            for (ox in 0 until outputWidth) {
                val cfaX = ox and 1
                val planeXIndex = ox / 2
                val planeX = if (outputPlaneWidth <= 1) 0f
                else planeXIndex.toFloat() * (sourcePlaneWidth - 1) / max(1, outputPlaneWidth - 1)
                out.put(oy * outputWidth + ox, sampleSameCfa(cfaX, cfaY, planeX, planeY).toShort())
            }
        }
        output.position(0)
        output.limit(outputWidth * outputHeight * 2)
        return Result(outputWidth, outputHeight, output, left, top, factor)
    }

    private fun evenFloor(value: Int): Int = value and -2
}
