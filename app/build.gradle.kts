// SPDX-FileCopyrightText: 2026 anderewrey
// SPDX-License-Identifier: GPL-3.0-or-later

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Version and app ID come from git, nothing to edit by hand (DESIGN.md §8, Versions).
fun git(vararg args: String): String? = runCatching {
    providers.exec { commandLine("git", *args) }.standardOutput.asText.get().trim()
}.getOrNull()?.ifEmpty { null }

// GitHub Actions gives the ref (refs/heads/main, refs/pull/12/merge); locally, ask git
// (null when HEAD is detached).
val gitRef = providers.environmentVariable("GITHUB_REF").orNull ?: git("symbolic-ref", "-q", "HEAD")
val pullRequest = gitRef?.let { Regex("""^refs/pull/(\d+)/""").find(it)?.groupValues?.get(1) }
val official = gitRef == "refs/heads/main" && git("status", "--porcelain") == null

// Only a release build of a clean main is the official app. A pull request's build is its own
// app ("Bus Tracker PR 12"), any other build is "Bus Tracker dev": installing one never
// replaces the official app or its data.
val testSuffix = pullRequest?.let { ".pr$it" } ?: ".dev"
val testLabel = pullRequest?.let { "Bus Tracker PR $it" } ?: "Bus Tracker dev"

// The app's own key (DESIGN.md §8, Signing), named in ~/.gradle/gradle.properties or, on GitHub,
// in ORG_GRADLE_PROJECT_* variables. Without it, builds are signed with Android's debug key.
val keystore = providers.gradleProperty("busTrackerKeystore").orNull
    ?.let { file(it.replaceFirst(Regex("^~(?=/)"), System.getProperty("user.home"))) }

android {
    namespace = "io.github.anderewrey.bustracker"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.anderewrey.bustracker"
        minSdk = 29
        targetSdk = 37
        // Build time in minutes since 1970 (UTC): each new build installs over the last one,
        // whatever its branch or commit.
        versionCode = (System.currentTimeMillis() / 60_000).toInt()
        // Last version tag, commits since it, commit hash, "-dirty" for uncommitted changes:
        // 0.13-1-g5a71da1, and 0.13-0-gc987f74 on the tag itself. Only tags starting with a
        // digit count, not the build-N tags of the GitHub releases.
        versionName = git("describe", "--tags", "--long", "--always", "--dirty", "--match", "[0-9]*") ?: "unknown"
    }

    signingConfigs {
        if (keystore != null) create("app") {
            storeFile = keystore
            storePassword = providers.gradleProperty("busTrackerKeystorePassword").get()
            keyAlias = providers.gradleProperty("busTrackerKeyAlias").orNull ?: "bus-tracker"
            keyPassword = storePassword
        }
    }
    val appKey = signingConfigs.findByName("app") ?: signingConfigs.getByName("debug")

    buildTypes {
        // Debug: debuggable, not optimized; always a test app.
        debug {
            signingConfig = appKey
            applicationIdSuffix = testSuffix
            manifestPlaceholders["appLabel"] = testLabel
        }
        // Release: optimized by R8, not debuggable. Names are kept (no obfuscation), so logs and
        // crash traces stay readable.
        release {
            signingConfig = appKey
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (official) {
                manifestPlaceholders["appLabel"] = "Bus Tracker"
            } else {
                applicationIdSuffix = testSuffix
                manifestPlaceholders["appLabel"] = testLabel
            }
        }
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
