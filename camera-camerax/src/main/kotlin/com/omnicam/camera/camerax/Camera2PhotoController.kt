package com.omnicam.camera.camerax

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.OutputConfiguration
import android.media.ImageReader
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import android.util.Size
import android.view.Surface
import android.view.TextureView
import com.omnicam.camera.capability.CameraRouteAccess
import com.omnicam.camera.capability.ValuableCameraRoute
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

enum class PhotoAspectRatio(val width: Int, val height: Int) {
    FOUR_THREE(4, 3),
    SIXTEEN_NINE(16, 9),
}

enum class CameraFlashMode {
    OFF,
    AUTO,
    ON,
    TORCH,
}

sealed interface PhotoCaptureResult {
    data class Success(
        val uri: Uri,
        val width: Int,
        val height: Int,
        val cameraId: String,
    ) : PhotoCaptureResult

    data class Failure(val message: String) : PhotoCaptureResult
}

/**
 * Phase-2 still-camera controller.
 *
 * Uses exact Camera2 routing so useful auxiliary lenses are not lost behind CameraX filtering.
 * Both directly-openable camera IDs and standards-based physical-via-logical routes are supported.
 */
class Camera2PhotoController(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val cameraManager = appContext.getSystemService(CameraManager::class.java)
    private val thread = HandlerThread("OmniCam-Photo-Camera2").apply { start() }
    private val handler = Handler(thread.looper)

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var previewSurface: Surface? = null
    private var imageReader: ImageReader? = null
    private var previewBuilder: CaptureRequest.Builder? = null
    private var controlCharacteristics: CameraCharacteristics? = null
    private var streamCharacteristics: CameraCharacteristics? = null
    private var activeRoute: ValuableCameraRoute? = null
    private var activeTextureView: TextureView? = null
    private var currentZoomRatio = 1f
    private var currentExposureCompensation = 0
    private var currentFlashMode = CameraFlashMode.OFF
    private var currentAspectRatio = PhotoAspectRatio.FOUR_THREE

    @SuppressLint("MissingPermission")
    suspend fun bind(
        textureView: TextureView,
        route: ValuableCameraRoute,
        aspectRatio: PhotoAspectRatio,
    ): CameraBindResult = withContext(Dispatchers.Main.immediate) {
        closeCurrent()
        currentAspectRatio = aspectRatio
        currentZoomRatio = 1f
        currentExposureCompensation = 0

        runCatching {
            val physicalId = route.camera.id.takeIf { route.access == CameraRouteAccess.PHYSICAL_VIA_LOGICAL }
            val deviceId = when (route.access) {
                CameraRouteAccess.DIRECT_CAMERA_DEVICE -> route.camera.id
                CameraRouteAccess.PHYSICAL_VIA_LOGICAL -> route.logicalCameraIds.firstOrNull()
                    ?: error("Physical camera ${route.camera.id} has no logical parent")
            }
            val streamChars = cameraManager.getCameraCharacteristics(physicalId ?: deviceId)
            val controlChars = cameraManager.getCameraCharacteristics(deviceId)
            streamCharacteristics = streamChars
            controlCharacteristics = controlChars

            val surfaceTexture = awaitSurfaceTexture(textureView)
            val previewSize = choosePreviewSize(streamChars, aspectRatio)
            val captureSize = chooseCaptureSize(streamChars, aspectRatio)
            surfaceTexture.setDefaultBufferSize(previewSize.width, previewSize.height)
            configureTransform(textureView, previewSize, controlChars)

            val preview = Surface(surfaceTexture)
            val reader = ImageReader.newInstance(
                captureSize.width,
                captureSize.height,
                ImageFormat.JPEG,
                2,
            )
            previewSurface = preview
            imageReader = reader
            activeTextureView = textureView

            val device = openCamera(deviceId)
            cameraDevice = device
            val session = createSession(
                device = device,
                preview = preview,
                jpeg = reader.surface,
                physicalCameraId = physicalId,
            )
            captureSession = session

            val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(preview)
                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                setContinuousAfIfSupported(this, controlChars)
            }
            previewBuilder = builder
            activeRoute = route
            applyRepeatingSettings()
            session.setRepeatingRequest(builder.build(), null, handler)

            val minZoom = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                streamChars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)?.lower ?: 1f
            } else {
                1f
            }
            val maxZoom = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                streamChars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)?.upper
                    ?: streamChars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM)
                    ?: 1f
            } else {
                streamChars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f
            }

            CameraBindResult.Success(
                requestedCameraId = route.camera.id,
                actualCameraId = if (physicalId == null) deviceId else "$deviceIdâ†’$physicalId",
                minZoomRatio = minZoom,
                maxZoomRatio = maxZoom,
            )
        }.getOrElse { error ->
            closeCurrent()
            CameraBindResult.Failure(
                cameraId = route.camera.id,
                reason = error.message ?: error::class.java.simpleName,
            )
        }
    }

    fun setZoomRatio(ratio: Float) {
        val chars = streamCharacteristics ?: return
        val min = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            chars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)?.lower ?: 1f
        } else 1f
        val max = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            chars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)?.upper
                ?: chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM)
                ?: 1f
        } else {
            chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f
        }
        currentV›ÛÛT˜][ÈH˜][Ë˜ÛÙ\˜ÙR[ŠZ[‹X^
Bˆ™Yœ™\Ú™\X][™Ê
BˆB‚ˆ[ˆÙ]^ÜÝ\™PÛÛ\[œØ][ÛŠ[™^ˆ[
HÂˆ˜[˜[™ÙHHÛÛ›ÛÚ\˜XÝ\š\ÝXÜÏË™Ù]
Ø[Y\˜PÚ\˜XÝ\š\ÝXÜËÓÓ•“ÓÐQWÐÓÓTS”ÐUSÓ—ÔS‘ÑJHÎˆ™]\›‚ˆÝ\œ™[^ÜÝ\™PÛÛ\[œØ][ÛˆH[™^˜ÛÙ\˜ÙR[Š˜[™ÙK›ÝÙ\‹˜[™ÙK\\ŠBˆ™Yœ™\Ú™\X][™Ê
BˆB‚ˆ[ˆÙ]›\Ú[ÙJ[ÙNˆØ[Y\˜Q›\Ú[ÙJHÂˆÝ\œ™[›\Ú[ÙHH[ÙBˆ™Yœ™\Ú™\X][™Ê
BˆB‚ˆ[ˆ›ØÝ\Ð]
›Ü›X[^™Yˆ›Ø]›Ü›X[^™YNˆ›Ø]
HÂˆ˜[Ú\œÈHÛÛ›ÛÚ\˜XÝ\š\ÝXÜÈÎˆ™]\›‚ˆ˜[Ù\ÜÚ[ÛˆHØ\\™TÙ\ÜÚ[ÛˆÎˆ™]\›‚ˆ˜[Z[\ˆH™]šY]ÐZ[\ˆÎˆ™]\›‚ˆ˜[XÝ]™HHÚ\œË™Ù]
Ø[Y\˜PÚ\˜XÝ\š\ÝXÜË”ÑS”ÓÔ—ÒS‘“×ÐPÕU‘WÐT”VWÔÒV‘JHÎˆ™]\›‚ˆYˆ

Ú\œË™Ù]
Ø[Y\˜PÚ\˜XÝ\š\ÝXÜËÓÓ•“ÓÓPVÔ‘QÒSÓ”×ÐQŠHÎˆ
HH
H™]\›‚‚ˆ˜[H
XÝ]™K›Y
È›Ü›X[^™Y˜ÛÙ\˜ÙR[Š‹YŠH
ˆXÝ]™KÚY

JKÒ[

Bˆ˜[HH
XÝ]™KÜ
È›Ü›X[^™YK˜ÛÙ\˜ÙR[Š‹YŠH
ˆXÝ]™KšZYÚ

JKÒ[

Bˆ˜[[ˆHX^
Z[“ÙŠXÝ]™KÚY

KXÝ]™KšZYÚ

JHÈM
Bˆ˜[™XÝH™XÝ
ˆ
H[ŠK˜ÛÙ\˜ÙR[ŠXÝ]™K›YXÝ]™KœšYÚHŠKˆ
HH[ŠK˜ÛÙ\˜ÙR[ŠXÝ]™KÜXÝ]™K˜›ÝÛHHŠKˆ

È[ŠK˜ÛÙ\˜ÙR[ŠXÝ]™K›Y
È‹XÝ]™KœšYÚ
Kˆ
H
È[ŠK˜ÛÙ\˜ÙR[ŠXÝ]™KÜ
È‹XÝ]™K˜›ÝÛJKˆ
Bˆ˜[Y]\š[™ÈHY]\š[™Ô™XÝ[™ÛJ™XÝY]\š[™Ô™XÝ[™ÛK“QUT’S‘×ÕÑRQÒÓPVHJBˆ[™\‹œÜÝÂˆ[Ø]Ú[™ÈÂˆZ[\‹œÙ]
Ø\\™T™\]Y\ÝÓÓ•“ÓÐQ—ÓSÑKØ\\™T™\]Y\ÝÓÓ•“ÓÐQ—ÓSÑWÐUUÊBˆZ[\‹œÙ]
Ø\\™T™\]Y\ÝÓÓ•“ÓÐQ—Ô‘QÒSÓ”Ë\œ˜^SÙŠY]\š[™ÊJBˆYˆ

Ú\œË™Ù]
Ø[Y\˜PÚ\˜XÝ\š\ÝXÜËÓÓ•“ÓÓPVÔ‘QÒSÓ”×ÐQJHÎˆ
Hˆ
HÂˆZ[\‹œÙ]
Ø\\™T™\]Y\ÝÓÓ•“ÓÐQWÔ‘QÒSÓ”Ë\œ˜^SÙŠY]\š[™ÊJBˆBˆZ[\‹œÙ]
Ø\\™T™\]Y\ÝÓÓ•“ÓÐQ—Õ’QÑÑT‹Ø\\™T™\]Y\ÝÓÓ•“ÓÐQ—Õ’QÑÑT—ÔÕT•
BˆÙ\ÜÚ[Û‹˜Ø\\™JZ[\‹˜Z[

K[[™\ŠBˆZ[\‹œÙ]
Ø\\™T™\]Y\ÝÓÓ•“ÓÐQ—Õ’QÑÑT‹Ø\\™T™\]Y\ÝÓÓ•“ÓÐQ—Õ’QÑÑT—ÒQJBˆ\T™\X][™ÔÙ][™ÜÊ
BˆÙ\ÜÚ[Û‹œÙ]™\X][™Ô™\]Y\Ý
Z[\‹˜Z[

K[[™\ŠBˆBˆBˆB‚ˆÝ\Ü[™[ˆØ\\™TÝÊ\Ü^T›Ý][Û‘YÜ™Y\Îˆ[
NˆÝÐØ\\™T™\Ý[HÚ]ÛÛ^
\Ü]Ú\œË“XZ[‹š[[YYX]JHÂˆ˜[›Ý]HHXÝ]™T›Ý]BˆÎˆ™]\›Ú]ÛÛ^ÝÐØ\\™T™\Ý[‘˜Z[\™J“›ÈØ[Y\˜H›Ý]H\ÈXÝ]™HŠBˆ˜[]šXÙHHØ[Y\˜Q]šXÙBˆÎˆ™]\›Ú]ÛÛ^ÝÐØ\\™T™\Ý[‘˜Z[\™JØ[Y\˜H]šXÙH\È[˜]˜Z[X›HŠBˆ˜[Ù\ÜÚ[ÛˆHØ\\™TÙ\ÜÚ[Û‚ˆÎˆ™]\›Ú]ÛÛ^ÝÐØ\\™T™\Ý[‘˜Z[\™JØ\\™HÙ\ÜÚ[Ûˆ\È[˜]˜Z[X›HŠBˆ˜[™XY\ˆH[XYÙT™XY\‚ˆÎˆ™]\›Ú]ÛÛ^ÝÐØ\\™T™\Ý[‘˜Z[\™J’”QÈÝ]]\È[˜]˜Z[X›HŠBˆ˜[Ú\œÈHÛÛ›ÛÚ\˜XÝ\š\ÝXÜÂˆÎˆ™]\›Ú]ÛÛ^ÝÐØ\\™T™\Ý[‘˜Z[\™JØ[Y\˜HY]Y]H\È[˜]˜Z[X›HŠB‚ˆ[Ø]Ú[™ÈÂˆ˜[ž]\ÈHÚ][Y[Ý]
LÌ
HÂˆÝ\Ü[™Ø[˜Ù[X›PÛÜ›Ý][™Ož]P\œ˜^OˆÈÛÛ[X][ÛˆO‚ˆ™XY\‹œÙ]Û’[XYÙP]˜Z[X›S\Ý[™\ŠÈÛÝ\˜ÙHO‚ˆ˜[[XYÙHH[Ø]Ú[™ÈÈÛÝ\˜ÙK˜XÜ]Z\™S™^[XYÙJ
HK™Ù]Ü“[

HÎˆ™]\›Ù]Û’[XYÙP]˜Z[X›S\Ý[™\‚ˆ[XYÙK\ÙHÂˆ˜[Y™™\ˆH]œ[™\Ë™š\œÝ

K˜Y™™\‚ˆ˜[]HHž]P\œ˜^JY™™\‹œ™[XZ[š[™Ê
JBˆY™™\‹™Ù]
]JBˆYˆ
ÛÛ[X][Û‹š\ÐXÝ]™JHÛÛ[X][Û‹œ™\Ý[YJ]JBˆBˆ™XY\‹œÙ]Û’[XYÙP]˜Z[X›S\Ý[™\Š[[
BˆK[™\ŠB‚ˆ˜[™\]Y\ÝH]šXÙK˜Ü™X]PØ\\™T™\]Y\Ý
Ø[Y\˜Q]šXÙK•STUWÔÕSÐÐTT‘JK˜\HÂˆY\™Ù]
™XY\‹œÝ\™˜XÙJBˆÙ]
Ø\\™T™\]Y\ÝÓÓ•“ÓÓSÑKØ\\™T™\]Y\ÝÓÓ•“ÓÓSÑWÐUUÊBˆÙ]ÛÛ[[Ý\ÐY’Y”Ý\ÜY
\ËÚ\œÊBˆÙ]
Ø\\™T™\]Y\ÝÓÓ•“ÓÐQWÑVÔÕT‘WÐÓÓT