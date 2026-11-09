// SPDX-FileCopyrightText: 2026 anderewrey
// SPDX-License-Identifier: GPL-3.0-or-later

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "io.github.anderewrey.bustracker"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.anderewrey.bustracker"
        minSdk = 29
        targetSdk = 37
        versionCode = 13
        versionName = "0.13"
    }

    // One APK per processor, so MapLibre's native code is there only once in each:
    // arm64-v8a for the phone, x86_64 for the emulator on the PC.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "x86_64")
            isUniversalApk = false
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("com.squareup.okhttp3:okhttp:5.5.1")
    implementation("org.jsoup:jsoup:1.24.1")
    implementation("org.maplibre.gl:android-sdk:13.7.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
}
