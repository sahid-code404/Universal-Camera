package com.omnicam.camera.camerax

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class ComputationalRawPlanTest {
    @Test
    fun acceptsPracticalBurstSizes() {
        assertEquals(8, ComputationalRawEngine.BurstPlan().frameCount)
        assertEquals(12, ComputationalRawEngine.BurstPlan(frameCount = 12).frameCount)
    }

    @Test
    fun rejectsUnboundedBurstSizes() {
        expectIllegalArgument { ComputationalRawEngine.BurstPlan(frameCount = 2) }
        expectIllegalArgument { ComputationalRawEngine.BurstPlan(frameCount = 13) }
    }

    @Test
    fun exposurePlanMustMatchFrameCountWhenProvided() {
        expectIllegalArgument {
            ComputationalRawEngine.BurstPlan(frameCount = 8, exposureOffsetsEv = listOf(-1f, 0f))
        }
    }

    private fun expectIllegalArgument(block: () -> Unit) {
        try {
            block()
            fail("Expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }
}
