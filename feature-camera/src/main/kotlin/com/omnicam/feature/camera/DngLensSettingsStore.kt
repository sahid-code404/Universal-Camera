package com.omnicam.feature.camera

import android.content.Context

/** Tiny persistent store for values that are intentionally per physical/direct camera route. */
internal class DngLensSettingsStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("omnicam_dng_camera", Context.MODE_PRIVATE)

    fun upscale(cameraId: String): Float =
        preferences.getFloat("upscale.$cameraId", 1f).coerceIn(MIN_UPSCALE, MAX_UPSCALE)

    fun setUpscale(cameraId: String, value: Float) {
        preferences.edit().putFloat("upscale.$cameraId", value.coerceIn(MIN_UPSCALE, MAX_UPSCALE)).apply()
    }

    fun aspect(): PreviewAspect = runCatching {
        PreviewAspect.valueOf(preferences.getString("preview.aspect", PreviewAspect.FOUR_THREE.name).orEmpty())
    }.getOrDefault(PreviewAspect.FOUR_THREE)

    fun setAspect(aspect: PreviewAspect) {
        preferences.edit().putString("preview.aspect", aspect.name).apply()
    }

    companion object {
        const val MIN_UPSCALE = 1f
        const val MAX_UPSCALE = 3f
    }
}
