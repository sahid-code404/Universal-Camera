package com.omnicam.camera.camerax

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.Image

/** Decodes camera HAL JPEG frames for the live preview without per-pixel Kotlin color conversion. */
internal object JpegPreviewDecoder {
    fun decode(image: Image, rotationDegrees: Int, mirrorX: Boolean): Bitmap {
        val plane = image.planes.single()
        val buffer = plane.buffer.duplicate()
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        val base = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: error("Camera JPEG decoder returned null")

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
