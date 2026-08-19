package com.omnicam.camera.camerax

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Applies preview-equivalent digital zoom directly in the Bayer domain for DNG-only output. */
internal object RawZoomProcessor {
    fun apply(
        merged: ComputationalRawEngine.MergeResult,
        zoomRatio: Float,
    ): ComputationalRawEngine.MergeResult {
        val zoom = zoomRatio.coerceIn(1f, 8f)
        if (zoom <= 1.0005f) return merged
        require(NativeRawBridge.available) {
            "Native RAW zoom unavailable: ${NativeRawBridge.loadFailure ?: "library load failed"}"
        }
        val outputBytes = Math.multiplyExact(Math.multiplyExact(merged.width, merged.height), 2)
        val output = ByteBuffer.allocateDirect(outputBytes).order(ByteOrder.nativeOrder())
        val code = NativeRawBridge.resampleRawZoom(
            inputRaw16 = merged.pixels16.duplicate(),
            width = merged.width,
            height = merged.height,
            zoomRatio = zoom,
            outputRaw16 = output,
        )
        check(code == 0) { "Native RAW zoom failed with code $code" }
        output.position(0)
        output.limit(outputBytes)
        return merged.copy(pixels16 = output)
    }
}
