package com.omnicam.camera.camerax

import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.Image
import kotlin.math.max

/** Converts a YUV_420_888 camera frame directly to an upright ARGB bitmap. */
internal object YuvPreviewConverter {
    fun toBitmap(image: Image, rotationDegrees: Int, mirrorX: Boolean): Bitmap {
        val crop = image.cropRect
        val width = crop.width()
        val height = crop.height()
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val yBuffer = yPlane.buffer
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer
        val pixels = IntArray(width * height)

        for (row in 0 until height) {
            val sourceY = crop.top + row
            val uvY = sourceY / 2
            val yRow = sourceY * yPlane.rowStride
            val uRow = uvY * uPlane.rowStride
            val vRow = uvY * vPlane.rowStride
            for (col in 0 until width) {
                val sourceX = crop.left + col
                val uvX = sourceX / 2
                val y = yBuffer.get(yRow + sourceX * yPlane.pixelStride).toInt() and 0xff
                val u = uBuffer.get(uRow + uvX * uPlane.pixelStride).toInt() and 0xff
                val v = vBuffer.get(vRow + uvX * vPlane.pixelStride).toInt() and 0xff

                val c = max(0, y - 16)
                val d = u - 128
                val e = v - 128
                val r = ((298 * c + 409 * e + 128) shr 8).coerceIn(0, 255)
                val g = ((298 * c - 100 * d - 208 * e + 128) shr 8).coerceIn(0, 255)
                val b = ((298 * c + 516 * d + 128) shr 8).coerceIn(0, 255)
                pixels[row * width + col] = (0xff shl 24) or (r shl 16) or (g shl 8) or b
            }
        }

        val base = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        val rotation = ((rotationDegrees % 360) + 360) % 360
        if (rotation == 0 && !mirrorX) return base

        val matrix = Matrix().apply {
            if (rotation != 0) postRotate(rotation.toFloat())
            if (mirrorX) postScale(-1f, 1f)
        }
        val transformed = Bitmap.createBitmap(base, 0, 0, base.width, base.height, matrix, true)
        if (transformed !== base) base.recycle()
        return transformed
    }
}
