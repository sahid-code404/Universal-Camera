import java.util.Base64

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val devKeySource = rootProject.file(".github/dev-signing/omnicam-dev.jks.b64")
val devKeyFile = layout.buildDirectory.file("generated/dev-signing/omnicam-dev.jks").get().asFile
if (devKeySource.exists()) {
    devKeyFile.parentFile.mkdirs()
    devKeyFile.writeBytes(Base64.getDecoder().decode(devKeySource.readText().trim()))
}

android {
    namespace = "com.omnicam.app"
    compileSdk = 37

    defaultConfig {
        // Temporary compatibility identity while vendor-filtered Snapdragon auxiliary access is validated.
        applicationId = "org.codeaurora.snapcam"
        minSdk = 28
        targetSdk = 37
        versionCode = providers.environmentVariable("OMNICAM_VERSION_CODE").orNull?.toIntOrNull() ?: 9200
        versionName = providers.environmentVariable("OMNICAM_VERSION_NAME").orNull ?: "0.2.0-dev"
        buildConfigField("String", "DEV_UPDATE_REPO", "\"sahid-code404/Universal-Camera\"")
        buildConfigField("String", "DEV_UPDATE_TAG", "\"dev-latest\"")
    }

    signingConfigs {
        create("development") {
            storeFile = devKeyFile
            storePassword = "omnicam-dev"
            keyAlias = "omnicam-dev"
            keyPassword = "omnicam-dev"
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("development")
        }
        release {
            isMinifyEnabled = false
            // Intentionally not signed with the public development key.
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
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
