plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.mcvoice.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.mcvoice.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"
        // ที่อยู่หน้าเว็บที่แอปจะเปิด (แก้ที่นี่ถ้าเปลี่ยนรีเลย์หรือห้อง)
        buildConfigField("String", "START_URL", "\"https://mc-voice-relay.onrender.com/?room=room-sb37a7\"")
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}
