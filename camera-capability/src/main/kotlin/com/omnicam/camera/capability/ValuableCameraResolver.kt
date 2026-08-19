package com.omnicam.camera.capability

import com.omnicam.core.model.CameraDescriptor
import com.omnicam.core.model.DeviceCameraProfile
import com.omnicam.core.model.LensFacing
import com.omnicam.core.model.LensRole
import kotlin.math.roundToInt

enum class CameraRouteAccess {
    DIRECT_CAMERA_DEVICE,
    PHYSICAL_VIA_LOGICAL,
}

enum class CameraExclusionReason {
    LOGICAL_AGGREGATOR,
    DEPTH_ONLY,
    UNKNOWN_FACING,
    NOT_PHOTOGRAPHICALLY_ROUTABLE,
    DUPLICATE_VENDOR_ALIAS,
}

data class ValuableCameraRoute(
    val camera: CameraDescriptor,
    val access: CameraRouteAccess,
    val logicalCameraIds: List<String> = emptyList(),
)

data class ExcludedCameraRoute(
    val camera: CameraDescriptor,
    val reason: CameraExclusionReason,
    val duplicateOfCameraId: String? = null,
)

data class ValuableCameraResolution(
    val valuableRoutes: List<ValuableCameraRoute>,
    val excludedRoutes: List<ExcludedCameraRoute>,
) {
    val rearRoutes: List<ValuableCameraRoute>
        get() = valuableRoutes.filter { it.camera.lensFacing == LensFacing.BACK }

    val frontRoutes: List<ValuableCameraRoute>
        get() = valuableRoutes.filter { it.camera.lensFacing == LensFacing.FRONT }
}

/**
 * Converts the raw Camera2 graph into user-facing photographic lens routes.
 *
 * Vendor camera HALs can publish multiple Camera2 IDs for the same physical sensor, logical
 * aggregators, and private/alternate pipelines. A camera app should not show every raw ID as if it
 * were a separate piece of glass. This resolver deliberately uses capabilities and optical
 * fingerprints instead of model names or hard-coded Qualcomm/Xiaomi ID tables.
 */
object ValuableCameraResolver {
    fun resolve(profile: DeviceCameraProfile): ValuableCameraResolution {
        val excluded = mutableListOf<ExcludedCameraRoute>()
        val candidates = mutableListOf<ValuableCameraRoute>()

        profile.cameras.forEach { camera ->
            when {
                camera.isLogical -> excluded += ExcludedCameraRoute(
                    camera = camera,
                    reason = CameraExclusionReason.LOGICAL_AGGREGATOR,
                )

                camera.classification.role == LensRole.DEPTH -> excluded += ExcludedCameraRoute(
                    camera = camera,
                    reason = CameraExclusionReason.DEPTH_ONLY,
                )

                camera.lensFacing == LensFacing.UNKNOWN -> excluded += ExcludedCameraRoute(
                    camera = camera,
                    reason = CameraExclusionReason.UNKNOWN_FACING,
                )

                camera.directlyListed && camera.backwardCompatible -> candidates += ValuableCameraRoute(
                    camera = camera,
                    access = CameraRouteAccess.DIRECT_CAMERA_DEVICE,
                    logicalCameraIds = camera.parentLogicalCameraIds,
                )

                !camera.directlyListed && camera.parentLogicalCameraIds.isNotEmpty() -> candidates += ValuableCameraRoute(
                    camera = camera,
                    access = CameraRouteAccess.PHYSICAL_VIA_LOGICAL,
                    logicalCameraIds = camera.parentLogicalCameraIds,
                )

                else -> excluded += ExcludedCameraRoute(
                    camera = camera,
                    reason = CameraExclusionReason.NOT_PHOTOGRAPHICALLY_ROUTABLE,
                )
            }
        }

        val valuable = mutableListOf<ValuableCameraRoute>()
        candidates
            .groupBy { route -> strongOpticalFingerprint(route.camera) ?: "id:${route.camera.id}" }
            .values
            .forEach { group ->
                val preferred = group.sortedWith(routePreferenceComparator).first()
                valuable += preferred
                group.filterNot { it.camera.id == preferred.camera.id }.forEach { duplicate ->
                    excluded += ExcludedCameraRoute(
                        camera = duplicate.camera,
                        reason = CameraExclusionReason.DUPLICATE_VENDOR_ALIAS,
                        duplicateOfCameraId = preferred.camera.id,
                    )
                }
            }

        return ValuableCameraResolution(
            valuableRoutes = valuable.sortedWith(defaultRouteComparator),
            excludedRoutes = excluded.sortedBy { it.camera.id },
        )
    }

    private val routePreferenceComparator = Comparator<ValuableCameraRoute> { left, right ->
        val scoreCompare = routePreferenceScore(right).compareTo(routePreferenceScore(left))
        if (scoreCompare != 0) return@Comparator scoreCompare

        val leftNumeric = left.camera.id.toLongOrNull()
        val rightNumeric = right.camera.id.toLongOrNull()
        when {
            leftNumeric != null && rightNumeric != null -> leftNumeric.compareTo(rightNumeric)
            leftNumeric != null -> -1
            rightNumeric != null -> 1
            else -> left.camera.id.compareTo(right.camera.id)
        }
    }

    private fun routePreferenceScore(route: ValuableCameraRoute): Int = buildList {
        add(if (route.access == CameraRouteAccess.DIRECT_CAMERA_DEVICE) 100 else 50)
        add(if (route.camera.parentLogicalCameraIds.isNotEmpty()) 30 else 0)
        add(if (route.camera.rawSupported) 6 else 0)
        add(if (route.camera.manualSensorSupported) 4 else 0)
        add(if (route.camera.burstCaptureSupported) 2 else 0)
    }.sum()

    private val defaultRouteComparator = Comparator<ValuableCameraRoute> { left, right ->
        val facing = facingRank(left.camera.lensFacing).compareTo(facingRank(right.camera.lensFacing))
        if (facing != 0) return@Comparator facing

        val leftEq = left.camera.equivalentFocalLengthsMm.minOrNull() ?: Float.MAX_VALUE
        val rightEq = right.camera.equivalentFocalLengthsMm.minOrNull() ?: Float.MAX_VALUE
        val focal = leftEq.compareTo(rightEq)
        if (focal != 0) return@Comparator focal

        routePreferenceComparator.compare(left, right)
    }

    private fun facingRank(facing: LensFacing): Int = when (facing) {
        LensFacing.BACK -> 0
        LensFacing.FRONT -> 1
        LensFacing.EXTERNAL -> 2
        LensFacing.UNKNOWN -> 3
    }

    /**
     * Alias de-duplication is intentionally conservative. We only collapse routes when enough
     * public optical metadata exists to strongly indicate that they describe the same sensor.
     * Missing metadata keeps both routes visible rather than accidentally hiding real hardware.
     */
    private fun strongOpticalFingerprint(camera: CameraDescriptor): String? {
        val sensorWidth = camera.sensorPhysicalWidthMm ?: return null
        val sensorHeight = camera.sensorPhysicalHeightMm ?: return null
        if (camera.focalLengthsMm.isEmpty()) return null

        val pixelSize = camera.pixelArraySize ?: camera.activeArraySize
        val nativeFocals = camera.focalLengthsMm.sorted().joinToString(",") { quantize(it, 100f).toString() }
        val equivalentFocals = camera.equivalentFocalLengthsMm.sorted()
            .joinToString(",") { quantize(it, 10f).toString() }
        val apertures = camera.apertures.sorted().joinToString(",") { quantize(it, 100f).toString() }

        return buildString {
            append(camera.lensFacing.name)
            append('|')
            append(quantize(sensorWidth, 100f))
            append('x')
            append(quantize(sensorHeight, 100f))
            append('|')
            if (pixelSize != null) append("${pixelSize.width}x${pixelSize.height}") else append("no-pixels")
            append('|')
            append(nativeFocals)
            append('|')
            append(equivalentFocals)
            append('|')
            append(apertures)
        }
    }

    private fun quantize(value: Float, scale: Float): Int = (value * scale).roundToInt()
}
