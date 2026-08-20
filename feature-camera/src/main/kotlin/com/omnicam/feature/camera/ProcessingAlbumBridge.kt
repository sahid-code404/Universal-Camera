package com.omnicam.feature.camera

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import java.io.RandomAccessFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Gallery integration plus DNG orientation repair.
 *
 * OmniCam does not publish temporary JPEG processing cards anymore. RAW processing progress belongs
 * in the camera UI, while the final DNG is written directly into DCIM/OmniCam by the camera layer.
 */
internal object ProcessingAlbumBridge {
    private const val APP_GALLERY_CATEGORY = "android.intent.category.APP_GALLERY"

    /**
     * Open a real gallery application directly instead of sending every tap through Android's app
     * chooser. We prefer the device vendor gallery, then Google Photos, and finally fall back to the
     * platform gallery selector. This keeps the album button deterministic on Xiaomi/Poco, Motorola,
     * Samsung and other common devices without making the user choose an app after every capture.
     */
    fun openAlbum(context: Context) {
        val flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP

        for (packageName in preferredGalleryPackages()) {
            val galleryIntent = Intent.makeMainSelectorActivity(
                Intent.ACTION_MAIN,
                APP_GALLERY_CATEGORY,
            ).apply {
                setPackage(packageName)
                addFlags(flags)
            }
            if (runCatching { context.startActivity(galleryIntent) }.isSuccess) return

            val launchIntent = runCatching {
                context.packageManager.getLaunchIntentForPackage(packageName)
            }.getOrNull()
            if (launchIntent != null) {
                launchIntent.addFlags(flags)
                if (runCatching { context.startActivity(launchIntent) }.isSuccess) return
            }
        }

        val platformGallery = Intent.makeMainSelectorActivity(
            Intent.ACTION_MAIN,
            APP_GALLERY_CATEGORY,
        ).apply { addFlags(flags) }
        if (runCatching { context.startActivity(platformGallery) }.isSuccess) return

        // Very old / unusual gallery implementations may only advertise image collection viewing.
        val fallback = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "image/*")
            addFlags(flags)
        }
        runCatching { context.startActivity(fallback) }
    }

    private fun preferredGalleryPackages(): List<String> {
        val maker = Build.MANUFACTURER.orEmpty().lowercase()
        val vendorFirst = when {
            maker.contains("xiaomi") || maker.contains("poco") || maker.contains("redmi") -> listOf(
                "com.miui.gallery",
                "com.google.android.apps.photos",
            )
            maker.contains("motorola") -> listOf(
                "com.motorola.MotGallery2",
                "com.motorola.gallery",
                "com.google.android.apps.photos",
            )
            maker.contains("samsung") -> listOf(
                "com.sec.android.gallery3d",
                "com.google.android.apps.photos",
            )
            maker.contains("oneplus") -> listOf(
                "com.oneplus.gallery",
                "com.oplus.gallery",
                "com.google.android.apps.photos",
            )
            maker.contains("oppo") || maker.contains("realme") -> listOf(
                "com.coloros.gallery3d",
                "com.oplus.gallery",
                "com.google.android.apps.photos",
            )
            else -> listOf("com.google.android.apps.photos")
        }
        return (vendorFirst + listOf(
            "com.google.android.apps.photos",
            "com.miui.gallery",
            "com.sec.android.gallery3d",
            "com.motorola.MotGallery2",
            "com.android.gallery3d",
        )).distinct()
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
