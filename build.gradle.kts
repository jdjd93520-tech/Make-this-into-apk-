plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.aimassistdemo"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.aimassistdemo"
        minSdk = 23
        targetSdk = 35
        versionCode = 2
        versionName = "1.1-screen-tracker"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
