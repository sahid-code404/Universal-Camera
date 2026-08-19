package com.omnicam.camera.camerax

/** Kotlin/JVM helper for nullable primitive arrays returned by Camera2 metadata. */
internal fun IntArray?.orEmpty(): IntArray = this ?: intArrayOf()
