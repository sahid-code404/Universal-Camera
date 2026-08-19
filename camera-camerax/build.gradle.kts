plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.omnicam.camera.camerax"
    compileSdk = 37

    defaultConfig {
        minSdk = 28
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":core-model"))
    implementation(project(":camera-capability"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.androidx.camera.viewfinder.core)
    implementation(libs.androidx.heifwriter)
    implementation(libs.kotlinx.coroutines.android)
}
