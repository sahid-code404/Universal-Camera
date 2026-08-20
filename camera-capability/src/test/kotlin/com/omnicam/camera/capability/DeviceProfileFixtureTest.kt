package com.omnicam.camera.capability

import com.omnicam.core.model.LensRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceProfileFixtureTest {
    @Test
    fun `logical triple camera keeps physical members distinct from directly listed cameras`() {
        val profile = FakeDeviceProfiles.logicalTripleCameraPhone
        val logical = profile.cameras.single { it.id == "0" }
        val physicalMembers = profile.cameras.filter { "0" in it.parentLogicalCameraIds }

        assertTrue(logical.isLogical)
        assertEquals(listOf("0a", "0b", "0c"), logical.physicalCameraIds)
        assertEquals(3, physicalMembers.size)
        assertTrue(physicalMembers.all { !it.directlyListed })
    }

    @Test
    fun `triple camera fixture represents ultra wide wide and long telephoto`() {
        val roles = FakeDeviceProfiles.logicalTripleCameraPhone.cameras
            .filter { "0" in it.parentLogicalCameraIds }
            .map { it.classification.role }
            .toSet()

        assertEquals(setOf(LensRole.ULTRA_WIDE, LensRole.WIDE, LensRole.LONG_TELEPHOTO), roles)
    }

    @Test
    fun `budget fixture does not invent advanced capabilities`() {
        val camera = FakeDeviceProfiles.singleCameraBudgetPhone.cameras.single()

        assertFalse(camera.rawSupported)
        assertFalse(camera.manualSensorSupported)
        assertFalse(camera.isLogical)
    }

    @Test
    fun `raw manual fixture exposes only explicitly declared advanced capabilities`() {
        val camera = FakeDeviceProfiles.rawManualPhone.cameras.single()

        assertTrue(camera.rawSupported)
        assertTrue(camera.manualSensorSupported)
        assertTrue(camera.manualPostProcessingSupported)
    }
}
