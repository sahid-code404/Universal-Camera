plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.omnicam.camera.camerax"
    compileSdk = 37
    ndkVersion = "27.2.12479018"

    defaultConfig {
        minSdk = 28
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
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
    implementation(libs.kotlinx.coroutines.android)
}
