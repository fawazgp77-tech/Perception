plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.example.mobilityassist"
    compileSdk = 35
    buildToolsVersion = "35.0.0"
    defaultConfig {
        applicationId = "com.example.mobilityassist"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "2.0-demo"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    signingConfigs {
        getByName("debug") {
            val localDebugKey = rootProject.file(".toolchain/debug.keystore")
            if (localDebugKey.exists()) storeFile = localDebugKey
        }
    }
    kotlinOptions { jvmTarget = "17" }
    androidResources { noCompress += "tflite" }
}
dependencies {
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    val cameraX = "1.4.1"
    implementation("androidx.camera:camera-core:$cameraX")
    implementation("androidx.camera:camera-camera2:$cameraX")
    implementation("androidx.camera:camera-lifecycle:$cameraX")
    implementation("androidx.camera:camera-view:$cameraX")
    implementation("com.google.mediapipe:tasks-vision:0.10.21")
    testImplementation("junit:junit:4.13.2")
}
