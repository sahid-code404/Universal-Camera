package com.omnicam.camera.capability

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.os.Build
import android.util.Range
import android.util.Size
import com.omnicam.core.model.CameraDescriptor
import com.omnicam.core.model.DeviceCameraProfile
import com.omnicam.core.model.FloatRange
import com.omnicam.core.model.LensFacing
import com.omnicam.core.model.LogicalCameraGroup
import com.omnicam.core.model.OutputFormatCapability
import com.omnicam.core.model.Size2D
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.sqrt

interface CameraCapabilityScanner {
    suspend fun scan(): DeviceCameraProfile
}

class AndroidCameraCapabilityScanner(
    context: Context,
) : CameraCapabilityScanner {
    private val cameraManager = context.applicationContext.getSystemService(CameraManager::class.java)

    override suspend fun scan(): DeviceCameraProfile = withContext(Dispatchers.Default) {
        val listedIds = cameraManager.cameraIdList.toList()
        val parentLogicalIds = mutableMapOf<String, MutableSet<String>>()
        val listedCharacteristics = linkedMapOf<String, CameraCharacteristics>()

        listedIds.forEach { id ->
            val characteristics = cameraManager.getCameraCharacteristics(id)
            listedCharacteristics[id] = characteristics
            characteristics.physicalCameraIds.forEach { physicalId ->
                parentLogicalIds.getOrPut(physicalId) { linkedSetOf() }.add(id)
            }
        }

        val allIds = LinkedHashSet<String>().apply {
            addAll(listedIds)
            addAll(parentLogicalIds.keys)
        }

        val descriptors = allIds.mapNotNull { id ->
            val characteristics = listedCharacteristics[id] ?: readCharacteristicsSafely(id) ?: return@mapNotNull null
            readDescriptor(
                id = id,
                directlyListed = id in listedIds,
                parentLogicalIds = parentLogicalIds[id].orEmpty().toList().sorted(),
                characteristics = characteristics,
            )
        }.sortedWith(
            compareByDescending<CameraDescriptor> { it.directlyListed }
                .thenBy { it.lensFacing.ordinal }
                .thenBy { it.equivalentFocalLengthsMm.minOrNull() ?: Float.MAX_VALUE }
                .thenBy { it.id },
        )

        val logicalGroups = listedCharacteristics.mapNotNull { (id, characteristics) ->
            characteristics.physicalCameraIds.takeIf { it.isNotEmpty() }?.let {
                LogicalCameraGroup(id, it.toList().sorted())
            }
        }

        DeviceCameraProfile(
            manufacturer = Build.MANUFACTURER.orEmpty(),
            model = Build.MODEL.orEmpty(),
            sdkInt = Build.VERSION.SDK_INT,
            scannedAtEpochMillis = System.currentTimeMillis(),
            cameras = descriptors,
            logicalGroups = logicalGroups,
        )
    }

    private fun readCharacteristicsSafely(id: String): CameraCharacteristics? = try {
        cameraManager.getCameraCharacteristics(id)
    } catch (_: CameraAccessException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    } catch (_: SecurityException) {
        null
    }

    private fun readDescriptor(
        id: String,
        directlyListed: Boolean,
        parentLogicalIds: List<String>,
        characteristics: CameraCharacteristics,
    ): CameraDescriptor {
        val capabilities = characteristics[CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES]?.toSet().orEmpty()
        val physicalIds = characteristics.physicalCameraIds.toList().sorted()
        val facing = characteristics[CameraCharacteristics.LENS_FACING].toLensFacing()
        val sensorSize = characteristics[CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE]
        val focalLengths = characteristics[CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS]?.toList().orEmpty()
        val equivalents = focalLengths.mapNotNull { focalLength ->
            sensorSize?.let { size -> equivalentFocalLengthMm(focalLength, size.width, size.height) }
        }
        val depthOutput = CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_DEPTH_OUTPUT in capabilities
        val backwardCompatible = CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE in capabilities
        val monochrome = CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MONOCHROME in capabilities
        val classification = LensClassifier.classify(
            LensClassifier.Input(
                facing = facing,
                equivalentFocalLengthsMm = equivalents,
                depthOnly = depthOutput && !backwardCompatible,
                monochrome = monochrome,
            ),
        )

        val streamMap = characteristics[CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP]
        val outputFormats = streamMap?.outputFormats?.map { format ->
            OutputFormatCapability(
                format = format,
                formatName = imageFormatName(format),
                sizes = runCatching {
                    streamMap.getOutputSizes(format)?.map { size -> size.toModel() }.orEmpty()
                }.getOrDefault(emptyList()),
            )
        }.orEmpty()

        return CameraDescriptor(
            id = id,
            directlyListed = directlyListed,
            parentLogicalCameraIds = parentLogicalIds,
            lensFacing = facing,
            isLogical = CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA in capabilities,
            physicalCameraIds = physicalIds,
            hardwareLevel = hardwareLevelName(characteristics[CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL]),
            sensorPhysicalWidthMm = sensorSize?.width,
            sensorPhysicalHeightMm = sensorSize?.height,
            pixelArraySize = characteristics[CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE]?.toModel(),
            activeArraySize = characteristics[CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE]?.let { Size2D(it.width(), it.height()) },
            focalLengthsMm = focalLengths,
            equivalentFocalLengthsMm = equivalents,
            apertures = characteristics[CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES]?.toList().orEmpty(),
            minimumFocusDistanceDiopters = characteristics[CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE],
            opticalStabilizationAvailable = characteristics[CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION]
                ?.contains(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON) == true,
            flashAvailable = characteristics[CameraCharacteristics.FLASH_INFO_AVAILABLE] == true,
            rawSupported = CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW in capabilities,
            manualSensorSupported = CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR in capabilities,
            manualPostProcessingSupported = CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING in capabilities,
            burstCaptureSupported = CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BURST_CAPTURE in capabilities,
            yuvReprocessingSupported = CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_YUV_REPROCESSING in capabilities,
            privateReprocessingSupported = CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_PRIVATE_REPROCESSING in capabilities,
            depthOutputSupported = depthOutput,
            monochrome = monochrome,
            backwardCompatible = backwardCompatible,
            maxDigitalZoom = characteristics[CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM] ?: 1f,
            zoomRatioRange = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                characteristics[CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE]?.let { FloatRange(it.lower, it.upper) }
            } else {
                null
            },
            sensitivityRange = characteristics[CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE]?.toIntRange(),
            exposureTimeRangeNs = characteristics[CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE]?.toLongRange(),
            aeCompensationRange = characteristics[CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE]?.toIntRange(),
            targetFpsRanges = characteristics[CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES]
                ?.map { range -> range.toIntRange() }
                .orEmpty(),
            outputFormats = outputFormats,
            classification = classification,
        )
    }

    private fun equivalentFocalLengthMm(focalLengthMm: Float, sensorWidthMm: Float, sensorHeightMm: Float): Float {
        val sensorDiagonal = sqrt(sensorWidthMm * sensorWidthMm + sensorHeightMm * sensorHeightMm)
        if (sensorDiagonal <= 0f) return Float.NaN
        val fullFrameDiagonalMm = 43.266615f
        return focalLengthMm * fullFrameDiagonalMm / sensorDiagonal
    }

    private fun Int?.toLensFacing(): LensFacing = when (this) {
        CameraCharacteristics.LENS_FACING_FRONT -> LensFacing.FRONT
        CameraCharacteristics.LENS_FACING_BACK -> LensFacing.BACK
        CameraCharacteristics.LENS_FACING_EXTERNAL -> LensFacing.EXTERNAL
        else -> LensFacing.UNKNOWN
    }

    private fun Size.toModel() = Size2D(width, height)
    private fun Range<Int>.toIntRange() = lower..upper
    private fun Range<Long>.toLongRange() = lower..upper

    private fun hardwareLevelName(level: Int?): String = when (level) {
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "EXTERNAL"
        else -> "UNKNOWN"
    }

    private fun imageFormatName(format: Int): String = when (format) {
        ImageFormat.JPEG -> "JPEG"
        ImageFormat.YUV_420_888 -> "YUV_420_888"
        ImageFormat.RAW_SENSOR -> "RAW_SENSOR"
        ImageFormat.RAW10 -> "RAW10"
        ImageFormat.RAW12 -> "RAW12"
        ImageFormat.DEPTH16 -> "DEPTH16"
        ImageFormat.DEPTH_POINT_CLOUD -> "DEPTH_POINT_CLOUD"
        ImageFormat.PRIVATE -> "PRIVATE"
        ImageFormat.HEIC -> "HEIC"
        else -> "FORMAT_$format"
    }
}
