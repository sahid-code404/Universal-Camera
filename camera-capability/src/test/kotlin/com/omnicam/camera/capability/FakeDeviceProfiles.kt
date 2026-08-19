package com.omnicam.camera.capability

import com.omnicam.core.model.CameraDescriptor
import com.omnicam.core.model.DeviceCameraProfile
import com.omnicam.core.model.LensClassification
import com.omnicam.core.model.LensFacing
import com.omnicam.core.model.LensRole
import com.omnicam.core.model.LogicalCameraGroup

/** Synthetic device profiles used only by unit tests. */
object FakeDeviceProfiles {
    val singleCameraBudgetPhone = DeviceCameraProfile(
        manufacturer = "TestVendor",
        model = "SingleCam",
        sdkInt = 28,
        scannedAtEpochMillis = 0,
        cameras = listOf(camera(id = "0", equivalentMm = 27f, role = LensRole.WIDE)),
        logicalGroups = emptyList(),
    )

    val logicalTripleCameraPhone = DeviceCameraProfile(
        manufacturer = "TestVendor",
        model = "TripleCam",
        sdkInt = 36,
        scannedAtEpochMillis = 0,
        cameras = listOf(
            camera(
                id = "0",
                equivalentMm = 24f,
                role = LensRole.WIDE,
                physicalCameraIds = listOf("0a", "0b", "0c"),
                isLogical = true,
            ),
            camera(
                id = "0a",
                equivalentMm = 13f,
                role = LensRole.ULTRA_WIDE,
                directlyListed = false,
                parentLogicalCameraIds = listOf("0"),
            ),
            camera(
                id = "0b",
                equivalentMm = 24f,
                role = LensRole.WIDE,
                directlyListed = false,
                parentLogicalCameraIds = listOf("0"),
            ),
            camera(
                id = "0c",
                equivalentMm = 120f,
                role = LensRole.LONG_TELEPHOTO,
                directlyListed = false,
                parentLogicalCameraIds = listOf("0"),
            ),
            camera(id = "1", equivalentMm = 24f, role = LensRole.FRONT, facing = LensFacing.FRONT),
        ),
        logicalGroups = listOf(LogicalCameraGroup("0", listOf("0a", "0b", "0c"))),
    )

    val rawManualPhone = DeviceCameraProfile(
        manufacturer = "TestVendor",
        model = "RawManual",
        sdkInt = 36,
        scannedAtEpochMillis = 0,
        cameras = listOf(
            camera(
                id = "0",
                equivalentMm = 25f,
                role = LensRole.WIDE,
                raw = true,
                manual = true,
            ),
        ),
        logicalGroups = emptyList(),
    )

    private fun camera(
        id: String,
        equivalentMm: Float,
        role: LensRole,
        facing: LensFacing = LensFacing.BACK,
        directlyListed: Boolean = true,
        parentLogicalCameraIds: List<String> = emptyList(),
        physicalCameraIds: List<String> = emptyList(),
        isLogical: Boolean = false,
        raw: Boolean = false,
        manual: Boolean = false,
    ) = CameraDescriptor(
        id = id,
        directlyListed = directlyListed,
        parentLogicalCameraIds = parentLogicalCameraIds,
        lensFacing = facing,
        isLogical = isLogical,
        physicalCameraIds = physicalCameraIds,
        hardwareLevel = "FULL",
        sensorPhysicalWidthMm = 7.0f,
        sensorPhysicalHeightMm = 5.2f,
        pixelArraySize = null,
        activeArraySize = null,
        focalLengthsMm = listOf(5f),
        equivalentFocalLengthsMm = listOf(equivalentMm),
        apertures = listOf(1.8f),
        minimumFocusDistanceDiopters = 10f,
        opticalStabilizationAvailable = false,
        flashAvailable = facing == LensFacing.BACK,
        rawSupported = raw,
        manualSensorSupported = manual,
        manualPostProcessingSupported = manual,
        burstCaptureSupported = true,
        yuvReprocessingSupported = false,
        privateReprocessingSupported = false,
        depthOutputSupported = false,
        monochrome = false,
        backwardCompatible = true,
        maxDigitalZoom = 8f,
        zoomRatioRange = null,
        sensitivityRange = if (manual) 50..6400 else null,
        exposureTimeRangeNs = if (manual) 100_000L..30_000_000_000L else null,
        aeCompensationRange = -12..12,
        targetFpsRanges = listOf(15..30, 30..30),
        outputFormats = emptyList(),
        classification = LensClassification(role, 1f, "Synthetic test fixture"),
    )
}
