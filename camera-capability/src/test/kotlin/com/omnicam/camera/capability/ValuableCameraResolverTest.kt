package com.omnicam.camera.capability

import com.omnicam.core.model.CameraDescriptor
import com.omnicam.core.model.DeviceCameraProfile
import com.omnicam.core.model.LensClassification
import com.omnicam.core.model.LensFacing
import com.omnicam.core.model.LensRole
import com.omnicam.core.model.LogicalCameraGroup
import com.omnicam.core.model.Size2D
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ValuableCameraResolverTest {
    @Test
    fun `snapdragon style aliases collapse to distinct useful lenses`() {
        val profile = DeviceCameraProfile(
            manufacturer = "Vendor",
            model = "QualcommStyle",
            sdkInt = 36,
            scannedAtEpochMillis = 0,
            cameras = listOf(
                camera("0", 25.6f, 4.74f, 6.4f, 4.8f, LensRole.WIDE, parents = listOf("61")),
                camera("100", 25.6f, 4.74f, 6.4f, 4.8f, LensRole.WIDE),
                camera("20", 24.0f, 1.94f, 2.8f, 2.1f, LensRole.WIDE, parents = listOf("61")),
                camera("21", 15.6f, 1.65f, 3.2f, 2.4f, LensRole.ULTRA_WIDE),
                camera("22", 23.1f, 1.94f, 2.7f, 2.0f, LensRole.WIDE),
                camera("61", 25.6f, 4.74f, 6.4f, 4.8f, LensRole.WIDE, logical = true, physical = listOf("0", "20")),
                camera("1", 28.1f, 3.74f, 5.0f, 3.7f, LensRole.FRONT, facing = LensFacing.FRONT),
                camera("101", 28.1f, 3.74f, 5.0f, 3.7f, LensRole.FRONT, facing = LensFacing.FRONT),
            ),
            logicalGroups = listOf(LogicalCameraGroup("61", listOf("0", "20"))),
        )

        val result = ValuableCameraResolver.resolve(profile)

        assertEquals(listOf("21", "22", "20", "0"), result.rearRoutes.map { it.camera.id })
        assertEquals(listOf("1"), result.frontRoutes.map { it.camera.id })
        assertTrue(result.excludedRoutes.any {
            it.camera.id == "61" && it.reason == CameraExclusionReason.LOGICAL_AGGREGATOR
        })
        assertTrue(result.excludedRoutes.any {
            it.camera.id == "100" &&
                it.reason == CameraExclusionReason.DUPLICATE_VENDOR_ALIAS &&
                it.duplicateOfCameraId == "0"
        })
        assertTrue(result.excludedRoutes.any {
            it.camera.id == "101" && it.reason == CameraExclusionReason.DUPLICATE_VENDOR_ALIAS
        })
    }

    @Test
    fun `physical members behind a logical camera remain valuable routes`() {
        val result = ValuableCameraResolver.resolve(FakeDeviceProfiles.logicalTripleCameraPhone)
        val rear = result.rearRoutes

        assertEquals(listOf("0a", "0b", "0c"), rear.map { it.camera.id })
        assertTrue(rear.all { it.access == CameraRouteAccess.PHYSICAL_VIA_LOGICAL })
        assertTrue(result.excludedRoutes.any {
            it.camera.id == "0" && it.reason == CameraExclusionReason.LOGICAL_AGGREGATOR
        })
    }

    private fun camera(
        id: String,
        equivalentMm: Float,
        nativeMm: Float,
        sensorWidthMm: Float,
        sensorHeightMm: Float,
        role: LensRole,
        facing: LensFacing = LensFacing.BACK,
        parents: List<String> = emptyList(),
        logical: Boolean = false,
        physical: List<String> = emptyList(),
    ) = CameraDescriptor(
        id = id,
        directlyListed = true,
        parentLogicalCameraIds = parents,
        lensFacing = facing,
        isLogical = logical,
        physicalCameraIds = physical,
        hardwareLevel = "LEVEL_3",
        sensorPhysicalWidthMm = sensorWidthMm,
        sensorPhysicalHeightMm = sensorHeightMm,
        pixelArraySize = Size2D(4000, 3000),
        activeArraySize = Size2D(3984, 2988),
        focalLengthsMm = listOf(nativeMm),
        equivalentFocalLengthsMm = listOf(equivalentMm),
        apertures = listOf(1.8f),
        minimumFocusDistanceDiopters = 10f,
        opticalStabilizationAvailable = false,
        flashAvailable = facing == LensFacing.BACK,
        rawSupported = true,
        manualSensorSupported = true,
        manualPostProcessingSupported = true,
        burstCaptureSupported = true,
        yuvReprocessingSupported = false,
        privateReprocessingSupported = false,
        depthOutputSupported = false,
        monochrome = false,
        backwardCompatible = true,
        maxDigitalZoom = 8f,
        zoomRatioRange = null,
        sensitivityRange = 50..6400,
        exposureTimeRangeNs = 100_000L..30_000_000_000L,
        aeCompensationRange = -12..12,
        targetFpsRanges = listOf(15..30, 30..30),
        outputFormats = emptyList(),
        classification = LensClassification(role, 1f, "test"),
    )
}
