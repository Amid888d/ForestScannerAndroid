plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.forestscanner"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.forestscanner"
        minSdk = 23
        targetSdk = 32
        versionCode = 1
        versionName = "1.0"
    }
}
