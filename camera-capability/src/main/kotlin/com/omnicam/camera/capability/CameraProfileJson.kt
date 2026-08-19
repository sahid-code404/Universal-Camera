package com.omnicam.camera.capability

import com.omnicam.core.model.CameraDescriptor
import com.omnicam.core.model.DeviceCameraProfile
import org.json.JSONArray
import org.json.JSONObject

fun DeviceCameraProfile.toSanitizedJson(): String {
    val root = JSONObject()
        .put("schemaVersion", schemaVersion)
        .put("manufacturer", manufacturer)
        .put("model", model)
        .put("sdkInt", sdkInt)
        .put("clientPackageName", clientPackageName)
        .put("scannedAtEpochMillis", scannedAtEpochMillis)
        .put("publicExposureAssessment", publicExposureAssessment.name)
        .put("numericCameraIdProbeReadableIds", JSONArray(numericCameraIdProbeReadableIds))
        .put(
            "legacyCameraApi",
            JSONObject()
                .put("cameraCount", legacyCameraCount)
                .put(
                    "cameras",
                    JSONArray().apply {
                        legacyCameras.forEach { camera ->
                            put(
                                JSONObject()
                                    .put("index", camera.index)
                                    .put("lensFacing", camera.lensFacing.name)
                                    .put("orientationDegrees", camera.orientationDegrees),
                            )
                        }
                    },
                ),
        )
        .put(
            "concurrentCameraIdSets",
            JSONArray().apply {
                concurrentCameraIdSets.forEach { ids -> put(JSONArray(ids)) }
            },
        )
        .put(
            "logicalGroups",
            JSONArray().apply {
                logicalGroups.forEach { group ->
                    put(
                        JSONObject()
                            .put("logicalCameraId", group.logicalCameraId)
                            .put("physicalCameraIds", JSONArray(group.physicalCameraIds)),
                    )
                }
            },
        )
        .put(
            "cameras",
            JSONArray().apply {
                cameras.forEach { put(it.toJson()) }
            },
        )

    return root.toString(2)
}

private fun CameraDescriptor.toJson(): JSONObject = JSONObject()
    .put("id", id)
    .put("directlyListed", directlyListed)
    .put("parentLogicalCameraIds", JSONArray(parentLogicalCameraIds))
    .put("lensFacing", lensFacing.name)
    .put("isLogical", isLogical)
    .put("physicalCameraIds", JSONArray(physicalCameraIds))
    .put("hardwareLevel", hardwareLevel)
    .put("sensorPhysicalWidthMm", sensorPhysicalWidthMm)
    .put("sensorPhysicalHeightMm", sensorPhysicalHeightMm)
    .put("pixelArraySize", pixelArraySize?.let { JSONObject().put("width", it.width).put("height", it.height) })
    .put("activeArraySize", activeArraySize?.let { JSONObject().put("width", it.width).put("height", it.height) })
    .put("focalLengthsMm", JSONArray(focalLengthsMm))
    .put("equivalentFocalLengthsMm", JSONArray(equivalentFocalLengthsMm))
    .put("apertures", JSONArray(apertures))
    .put("minimumFocusDistanceDiopters", minimumFocusDistanceDiopters)
    .put("opticalStabilizationAvailable", opticalStabilizationAvailable)
    .put("flashAvailable", flashAvailable)
    .put("rawSupported", rawSupported)
    .put("manualSensorSupported", manualSensorSupported)
    .put("manualPostProcessingSupported", manualPostProcessingSupported)
    .put("burstCaptureSupported", burstCaptureSupported)
    .put("yuvReprocessingSupported", yuvReprocessingSupported)
    .put("privateReprocessingSupported", privateReprocessingSupported)
    .put("depthOutputSupported", depthOutputSupported)
    .put("monochrome", monochrome)
    .put("backwardCompatible", backwardCompatible)
    .put("maxDigitalZoom", maxDigitalZoom)
    .put("zoomRatioRange", zoomRatioRange?.let { JSONObject().put("min", it.min).put("max", it.max) })
    .put("sensitivityRange", sensitivityRange?.let { JSONObject().put("min", it.first).put("max", it.last) })
    .put("exposureTimeRangeNs", exposureTimeRangeNs?.let { JSONObject().put("min", it.first).put("max", it.last) })
    .put("aeCompensationRange", aeCompensationRange?.let { JSONObject().put("min", it.first).put("max", it.last) })
    .put(
        "targetFpsRanges",
        JSONArray().apply {
            targetFpsRanges.forEach { put(JSONObject().put("min", it.first).put("max", it.last)) }
        },
    )
    .put(
        "outputFormats",
        JSONArray().apply {
            outputFormats.forEach { output ->
                put(
                    JSONObject()
                        .put("format", output.format)
                        .put("formatName", output.formatName)
                        .put(
                            "sizes",
                            JSONArray().apply {
                                output.sizes.forEach { size ->
                                    put(JSONObject().put("width", size.width).put("height", size.height))
                                }
                            },
                        ),
                )
            }
        },
    )
    .put(
        "classification",
        JSONObject()
            .put("role", classification.role.name)
            .put("confidence", classification.confidence)
            .put("reason", classification.reason),
    )
