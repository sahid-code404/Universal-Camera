package com.omnicam.camera.capability

import com.omnicam.core.model.LensFacing
import com.omnicam.core.model.LensRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LensClassifierTest {
    @Test
    fun `front camera is always classified as front`() {
        val result = LensClassifier.classify(
            LensClassifier.Input(LensFacing.FRONT, listOf(24f), depthOnly = false, monochrome = false),
        )
        assertEquals(LensRole.FRONT, result.role)
        assertEquals(1f, result.confidence)
    }

    @Test
    fun `13mm equivalent classifies as ultra wide`() {
        val result = classifyBack(13f)
        assertEquals(LensRole.ULTRA_WIDE, result.role)
        assertTrue(result.confidence >= 0.9f)
    }

    @Test
    fun `26mm equivalent classifies as wide`() {
        assertEquals(LensRole.WIDE, classifyBack(26f).role)
    }

    @Test
    fun `70mm equivalent classifies as telephoto`() {
        assertEquals(LensRole.TELEPHOTO, classifyBack(70f).role)
    }

    @Test
    fun `120mm equivalent classifies as long telephoto`() {
        assertEquals(LensRole.LONG_TELEPHOTO, classifyBack(120f).role)
    }

    @Test
    fun `multi-focal logical camera prefers normal wide representative`() {
        val result = LensClassifier.classify(
            LensClassifier.Input(
                facing = LensFacing.BACK,
                equivalentFocalLengthsMm = listOf(13f, 26f, 120f),
                depthOnly = false,
                monochrome = false,
            ),
        )
        assertEquals(LensRole.WIDE, result.role)
    }

    @Test
    fun `missing optical metadata remains unknown instead of being guessed`() {
        val result = LensClassifier.classify(
            LensClassifier.Input(LensFacing.BACK, emptyList(), depthOnly = false, monochrome = false),
        )
        assertEquals(LensRole.UNKNOWN, result.role)
    }

    @Test
    fun `depth-only camera is classified as depth`() {
        val result = LensClassifier.classify(
            LensClassifier.Input(LensFacing.BACK, listOf(26f), depthOnly = true, monochrome = false),
        )
        assertEquals(LensRole.DEPTH, result.role)
    }

    private fun classifyBack(focalLength: Float) = LensClassifier.classify(
        LensClassifier.Input(LensFacing.BACK, listOf(focalLength), depthOnly = false, monochrome = false),
    )
}
