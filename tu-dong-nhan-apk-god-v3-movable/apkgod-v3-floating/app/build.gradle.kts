plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.apkgod.autoclicker"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.apkgod.autoclicker"
        minSdk = 30
        targetSdk = 37
        versionCode = 3
        versionName = "3.0.0"
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug { isMinifyEnabled = false }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.13.0")
    // Unbundled ML Kit: much smaller APK; Google Play services downloads the OCR model when needed.
    implementation("com.google.android.gms:play-services-mlkit-text-recognition:19.0.1")
}
