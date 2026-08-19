package com.omnicam.app

import android.app.Application
import com.omnicam.app.update.DevUpdateManager
import com.omnicam.camera.camerax.DngOnlyCameraController
import com.omnicam.camera.capability.AndroidCameraCapabilityScanner
import com.omnicam.camera.capability.CameraCapabilityScanner

class OmniCamApplication : Application() {
    val appContainer: AppContainer by lazy { AppContainer(this) }
}

class AppContainer(application: Application) {
    val cameraCapabilityScanner: CameraCapabilityScanner = AndroidCameraCapabilityScanner(application)
    val dngCameraController = DngOnlyCameraController(application)
    val devUpdateManager = DevUpdateManager(application)
}
