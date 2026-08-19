package com.omnicam.app

import android.app.Application
import com.omnicam.app.update.DevUpdateManager
import com.omnicam.camera.camerax.Camera2AuxPreviewController
import com.omnicam.camera.capability.AndroidCameraCapabilityScanner
import com.omnicam.camera.capability.CameraCapabilityScanner
import com.omnicam.feature.camera.DataStoreLensPreferencesStore
import com.omnicam.feature.camera.LensPreferencesStore

class OmniCamApplication : Application() {
    val appContainer: AppContainer by lazy { AppContainer(this) }
}

class AppContainer(application: Application) {
    val cameraCapabilityScanner: CameraCapabilityScanner = AndroidCameraCapabilityScanner(application)
    val camera2AuxPreviewController: Camera2AuxPreviewController = Camera2AuxPreviewController(application)
    val lensPreferencesStore: LensPreferencesStore = DataStoreLensPreferencesStore(application)
    val devUpdateManager = DevUpdateManager(application)
}
