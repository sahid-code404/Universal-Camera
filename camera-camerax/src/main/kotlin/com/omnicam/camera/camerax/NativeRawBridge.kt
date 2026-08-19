package com.omnicam.camera.camerax

import android.graphics.Bitmap
import java.nio.ByteBuffer

/** JNI bridge for OmniCam's low-latency native RAW pipeline. */
internal object NativeRawBridge {
    val available: Boolean
    val loadFailure: String?

    init {
        val loaded = runCatching { System.loadLibrary("omnicam_raw") }
        available = loaded.isSuccess
        loadFailure = loaded.exceptionOrNull()?.message
    }

    @JvmStatic
    external fun mergeRaw16(
        paths: Array<String>,
        width: Int,
        height: Int,
        exposureTimesNs: LongArray,
        isoValues: IntArray,
        blackLevels: IntArray,
        whiteLevels: IntArray,
        noiseSlopes: FloatArray,
        noiseOffsets: FloatArray,
        denoiseStrength: Float,
        highlightProtection: Float,
        outputRaw16: ByteBuffer,
        alignmentOut: IntArray,
        statsOut: LongArray,
    ): Int

    @JvmStatic
    external fun renderEnhanced(
        mergedRaw16: ByteBuffer,
        width: Int,
        height: Int,
        blackLevels: IntArray,
        whiteLevel: Int,
        cfaArrangement: Int,
        whiteBalance: FloatArray,
        colorMatrix: FloatArray,
        highlightProtection: Float,
        denoiseStrength: Float,
        outputBitmap: Bitmap,
    ): Int

    /**
     * Center-crop a Bayer RAW frame by [zoomRatio] and interpolate each CFA plane independently
     * back into the original RAW dimensions. This keeps a standards-compatible native-size DNG
     * canvas while making its field of view match the processed preview digital zoom.
     */
    @JvmStatic
    external fun resampleRawZoom(
        inputRaw16: ByteBuffer,
        width: Int,
        height: Int,
        zoomRatio: Float,
        outputRaw16: ByteBuffer,
    ): Int
}
