package com.omnicam.core.model

data class Size2D(
    val width: Int,
    val height: Int,
) {
    val megapixels: Double
        get() = width.toDouble() * height.toDouble() / 1_000_000.0
}

data class FloatRange(
    val min: Float,
    val max: Float,
)

enum class LensFacing {
    FRONT,
    BACK,
    EXTERNAL,
    UNKNOWN,
}

enum class LensRole {
    ULTRA_WIDE,
    WIDE,
    TELEPHOTO,
    LONG_TELEPHOTO,
    FRONT,
    MONOCHROME,
    DEPTH,
    AUXILIARY,
    UNKNOWN,
}

data class LensClassification(
    val role: LensRole,
    val confidence: Float,
    val reason: String,
)

data class OutputFormatCapability(
    val format: Int,
    val formatName: String,
    val sizes: List<Size2D>,
)

data class CameraDescriptor(
    val id: String,
    val directlyListed: Boolean,
    val parentLogicalCameraIds: List<String>,
    val lensFacing: LensFacing,
    val isLogical: Boolean,
    val physicalCameraIds: List<String>,
    val hardwareLevel: String,
    val sensorPhysicalWidthMm: Float?,
    val sensorPhysicalHeightMm: Float?,
    val pixelArraySize: Size2D?,
    val activeArraySize: Size2D?,
    val focalLengthsMm: List<Float>,
    val equivalentFocalLengthsMm: List<Float>,
    val apertures: List<Float>,
    val minimumFocusDistanceDiopters: Float?,
    val opticalStabilizationAvailable: Boolean,
    val flashAvailable: Boolean,
    val rawSupported: Boolean,
    val manualSensorSupported: Boolean,
    val manualPostProcessingSupported: Boolean,
    val burstCaptureSupported: Boolean,
    val yuvReprocessingSupported: Boolean,
    val privateReprocessingSupported: Boolean,
    val depthOutputSupported: Boolean,
    val monochrome: Boolean,
    val backwardCompatible: Boolean,
    val maxDigitalZoom: Float,
    val zoomRatioRange: FloatRange?,
    val sensitivityRange: IntRange?,
    val exposureTimeRangeNs: LongRange?,
    val aeCompensationRange: IntRange?,
    val targetFpsRanges: List<IntRange>,
    val outputFormats: List<OutputFormatCapability>,
    val classification: LensClassification,
)

data class LogicalCameraGroup(
    val logicalCameraId: String,
    val physicalCameraIds: List<String>,
)

data class LegacyCameraDescriptor(
    val index: Int,
    val lensFacing: LensFacing,
    val orientationDegrees: Int,
)

enum class PublicCameraExposureAssessment {
    MULTIPLE_CAMERA2_IDS,
    LOGICAL_MULTI_CAMERA_EXPOSED,
    LEGACY_API_SEES_ADDITIONAL_CAMERAS,
    AUXILIARY_NOT_EXPOSED_BY_STANDARD_DISCOVERY,
    UNKNOWN,
}

data class DeviceCameraProfile(
    val schemaVersion: Int = 2,
    val manufacturer: String,
    val model: String,
    val sdkInt: Int,
    val scannedAtEpochMillis: Long,
    val cameras: List<CameraDescriptor>,
    val logicalGroups: List<LogicalCameraGroup>,
    val legacyCameraCount: Int? = null,
    val legacyCameras: List<LegacyCameraDescriptor> = emptyList(),
    val concurrentCameraIdSets: List<List<String>> = emptyList(),
    val publicExposureAssessment: PublicCameraExposureAssessment = PublicCameraExposureAssessment.UNKNOWN,
)
