plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "mx.ebecerra.remoteshutter"
    compileSdk = 35

    defaultConfig {
        applicationId = "mx.ebecerra.remoteshutter"
        // The Garmin Connect IQ companion SDK 2.x is a binder client for
        // Garmin Connect Mobile. It needs nothing exotic, so we can keep the
        // floor high and the code simple.
        minSdk = 31
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    // ComponentActivity gives us a LifecycleOwner for CameraX without
    // dragging AppCompat/Compose into a single-screen app.
    implementation("androidx.activity:activity-ktx:1.9.3")

    // CameraX: preview + still capture + video recording, one API for all.
    val camerax = "1.4.1"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-video:$camerax")
    implementation("androidx.camera:camera-view:$camerax")

    // Pure-JVM unit tests for the wire protocol (no emulator needed).
    testImplementation("junit:junit:4.13.2")

    // Garmin Connect IQ Mobile SDK for Android (Maven Central).
    implementation("com.garmin.connectiq:ciq-companion-app-sdk:2.4.0@aar")
}
