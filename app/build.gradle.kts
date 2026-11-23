plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.screenwatcher"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.screenwatcher"
        minSdk = 26
        targetSdk = 34
        versionCode = 4
        versionName = "1.4"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // OCR vẫn dùng ML Kit; nhận diện/so khớp ảnh chuyển sang MobileCLIP2-S2 ONNX.
    implementation("com.google.mlkit:text-recognition:16.0.0")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.20.0")
}
