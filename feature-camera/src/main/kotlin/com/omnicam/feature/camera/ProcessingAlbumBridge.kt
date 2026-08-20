package com.omnicam.feature.camera

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.RandomAccessFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Makes a just-shot photo visible in the system gallery immediately while the real RAW fusion is
 * still running. Android has no public cross-gallery equivalent of iPhone Photos' private
 * "Processing" state, so OmniCam publishes a temporary visible card in the fixed OmniCam album and
 * removes it as soon as the final DNG is ready. The final file remains RAW/DNG only.
 */
internal object ProcessingAlbumBridge {
    suspend fun createPlaceholder(context: Context, id: Long): Uri? = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "OMNI_PROCESSING_$id.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.WIDTH, 720)
            put(MediaStore.Images.Media.HEIGHT, 540)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/OmniCam")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return@withContext null
        try {
            val bitmap = Bitmap.createBitmap(720, 540, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.rgb(20, 20, 22))
            val title = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textAlign = Paint.Align.CENTER
                textSize = 46f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            }
            val sub = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(185, 185, 190)
                textAlign = Paint.Align.CENTER
                textSize = 27f
            }
            canvas.drawText("Processing RAW…", 360f, 255f, title)
            canvas.drawText("OmniCam · final DNG is being prepared", 360f, 310f, sub)
            resolver.openOutputStream(uri, "w")?.use {
                bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it)
            }
            bitmap.recycle()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            }
            uri
        } catch (error: Throwable) {
            resolver.delete(uri, null, null)
            null
        }
    }

    suspend fun removePlaceholder(context: Context, uri: Uri?) = withContext(Dispatchers.IO) {
        if (uri != null) runCatching { context.contentResolver.delete(uri, null, null) }
    }

    fun openAlbum(context: Context) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "image/*")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
    }

    suspend fun fixDngOrientation(
        context: Context,
        uri: Uri,
        cameraId: String,
        displayRotationDegrees: Int,
    ) = withContext(Dispatchers.IO) {
        val manager = context.getSystemService(CameraManager::class.java)
        val chars = manager.getCameraCharacteristics(cameraId)
        val sensor = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        val facing = chars.get(CameraCharacteristics.LENS_FACING)
        val device = ((displayRotationDegrees % 360) + 360) % 360
        val clockwise = if (facing == CameraCharacteristics.LENS_FACING_FRONT) {
            (sensor + device) % 360
        } else {
            (sensor - device + 360) % 360
        }
        val exifOrientation = when (clockwise) {
            90 -> 6
            180 -> 3
            270 -> 8
            else -> 1
        }

        // Also set the MediaStore orientation hint for gallery apps that use the database column.
        runCatching {
            context.contentResolver.update(
                uri,
                ContentValues().apply { put(MediaStore.Images.Media.ORIENTATION, clockwise) },
                null,
                null,
            )
        }

        // DNG is TIFF. Patch the standard Orientation tag in the first IFD so RAW editors that
        // ignore MediaStore metadata still rotate the image correctly.
        runCatching {
            context.contentResolver.openFileDescriptor(uri, "rw")?.use { pfd ->
                RandomAccessFile("/proc/self/fd/${pfd.fd}", "rw").use { raf ->
                    patchTiffOrientation(raf, exifOrientation)
                }
            }
        }
    }

    private fun patchTiffOrientation(raf: RandomAccessFile, orientation: Int) {
        if (raf.length() < 8) return
        raf.seek(0)
        val b0 = raf.readUnsignedByte()
        val b1 = raf.readUnsignedByte()
        val little = when {
            b0 == 'I'.code && b1 == 'I'.code -> true
            b0 == 'M'.code && b1 == 'M'.code -> false
            else -> return
        }
        if (readU16(raf, little) != 42) return
        val ifdOffset = readU32(raf, little)
        if (ifdOffset <= 0 || ifdOffset >= raf.length() - 2) return
        raf.seek(ifdOffset)
        val count = readU16(raf, little)
        repeat(count.coerceAtMost(4096)) {
            val entry = raf.filePointer
            val tag = readU16(raf, little)
            val type = readU16(raf, little)
            val itemCount = readU32(raf, little)
            readU32(raf, little)
            if (tag == 274 && itemCount >= 1) {
                raf.seek(entry + 2)
                writeU16(raf, little, 3)
                writeU32(raf, little, 1)
                writeU16(raf, little, orientation)
                writeU16(raf, little, 0)
                return
            }
            if (type !in 1..12) return@repeat
        }
    }

    private fun readU16(raf: RandomAccessFile, little: Boolean): Int {
        val a = raf.readUnsignedByte()
        val b = raf.readUnsignedByte()
        return if (little) a or (b shl 8) else (a shl 8) or b
    }

    private fun readU32(raf: RandomAccessFile, little: Boolean): Long {
        val a = raf.readUnsignedByte().toLong()
        val b = raf.readUnsignedByte().toLong()
        val c = raf.readUnsignedByte().toLong()
        val d = raf.readUnsignedByte().toLong()
        return if (little) a or (b shl 8) or (c shl 16) or (d shl 24)
        else (a shl 24) or (b shl 16) or (c shl 8) or d
    }

    private fun writeU16(raf: RandomAccessFile, little: Boolean, value: Int) {
        if (little) {
            raf.write(value and 0xff)
            raf.write((value ushr 8) and 0xff)
        } else {
            raf.write((value ushr 8) and 0xff)
            raf.write(value and 0xff)
        }
    }

    private fun writeU32(raf: RandomAccessFile, little: Boolean, value: Long) {
        if (little) {
            raf.write((value and 0xff).toInt())
            raf.write(((value ushr 8) and 0xff).toInt())
            raf.write(((value ushr 16) and 0xff).toInt())
            raf.write(((value ushr 24) and 0xff).toInt())
        } else {
            raf.write(((value ushr 24) and 0xff).toInt())
            raf.write(((value ushr 16) and 0xff).toInt())
            raf.write(((value ushr 8) and 0xff).toInt())
            raf.write((value and 0xff).toInt())
        }
    }
}