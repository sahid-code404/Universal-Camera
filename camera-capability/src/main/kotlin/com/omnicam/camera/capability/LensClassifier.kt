package com.omnicam.camera.capability

import com.omnicam.core.model.LensClassification
import com.omnicam.core.model.LensFacing
import com.omnicam.core.model.LensRole

object LensClassifier {
    data class Input(
        val facing: LensFacing,
        val equivalentFocalLengthsMm: List<Float>,
        val depthOnly: Boolean,
        val monochrome: Boolean,
    )

    fun classify(input: Input): LensClassification {
        if (input.depthOnly) {
            return LensClassification(LensRole.DEPTH, 0.98f, "Depth output without normal backward-compatible capture")
        }
        if (input.monochrome) {
            return LensClassification(LensRole.MONOCHROME, 0.98f, "Camera reports MONOCHROME capability")
        }
        if (input.facing == LensFacing.FRONT) {
            return LensClassification(LensRole.FRONT, 1.0f, "Camera reports front-facing lens")
        }
        if (input.facing == LensFacing.EXTERNAL) {
            return LensClassification(LensRole.AUXILIARY, 0.9f, "Camera reports external lens facing")
        }

        val equivalent = input.equivalentFocalLengthsMm.filter { it.isFinite() && it > 0f }.minOrNull()
            ?: return LensClassification(
                LensRole.UNKNOWN,
                0.2f,
                "Insufficient public optical metadata to estimate 35mm-equivalent focal length",
            )

        return when {
            equivalent < 22f -> LensClassification(
                LensRole.ULTRA_WIDE,
                0.95f,
                "Estimated 35mm-equivalent focal length is %.1f mm".format(equivalent),
            )
            equivalent <= 50f -> LensClassification(
                LensRole.WIDE,
                if (equivalent <= 40f) 0.95f else 0.78f,
                "Estimated 35mm-equivalent focal length is %.1f mm".format(equivalent),
            )
            equivalent <= 105f -> LensClassification(
                LensRole.TELEPHOTO,
                0.9f,
                "Estimated 35mm-equivalent focal length is %.1f mm".format(equivalent),
            )
            else -> LensClassification(
                LensRole.LONG_TELEPHOTO,
                0.94f,
                "Estimated 35mm-equivalent focal length is %.1f mm".format(equivalent),
            )
        }
    }
}
