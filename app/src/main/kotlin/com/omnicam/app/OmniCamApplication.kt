package com.omnicam.app

import android.app.Application
import com.omnicam.app.update.DevUpdateManager
import com.omnicam.camera.camerax.Camera2AuxPreviewController
import com.omnicam.camera.camerax.Camera2PhotoController
import com.omnicam.camera.camerax.ComputationalRawController
import com.omnicam.camera.camerax.LightningRawController
import com.omnicam.camera.capability.AndroidCameraCapabilityScanner
import com.omnicam.camera.capability.CameraCapabilityScanner
import com.omnicam.feature.camera.DataStoreLensPreferencesStore
import com.omnicam.feature.camera.LensPreferencesStore

class OmniCamApplication : Application() {
    val appContainer: AppContainer by lazy { AppContainer(this) }
}

class AppContainer(application: Application) {
    val cameraCapabilityScanner: CameraCapabilityScanner = AndroidCameraCapabilityScanner(application)
    val lightningRawController = LightningRawController(application)

    // Kept internally for diagnostics/regression only. The launcher exposes only Lightning C1.7.
    val camera2AuxPreviewController = Camera2AuxPreviewController(application)
    val camera2PhotoController = Camera2PhotoController(application)
    val computationalRawController = ComputationalRawController(application)
    val lensPreferencesStore: LensPreferencesStore = DataStoreLensPreferencesStore(application)
    val devUpdateManager = DevUpdateManager(application)
}
