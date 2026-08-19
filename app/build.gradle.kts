plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.omnicam.app"
    compileSdk = 37

    defaultConfig {
        // TEMPORARY DIAGNOSTIC IDENTITY ONLY.
        // Some Qualcomm/Xiaomi camera providers historically expose auxiliary
        // cameras only to allowlisted client package names such as this one.
        // Do not merge this applicationId into OmniCam production.
        applicationId = "org.codeaurora.snapcam"
        minSdk = 28
        targetSdk = 37
        versionCode = 9002
        versionName = "0.1.0-snapcam-live-lens-test"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":camera-capability"))
    implementation(project(":camera-camerax"))
    implementation(project(":feature-camera"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
