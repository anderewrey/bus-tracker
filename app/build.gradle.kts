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
        // Last tag, commits since it, commit hash, "-dirty" for uncommitted changes:
        // 0.13-1-g5a71da1, and 0.13-0-gc987f74 on the tag itself.
        versionName = git("describe", "--tags", "--long", "--always", "--dirty") ?: "unknown"

        // Only a clean build of main is the official app. A pull request's build is its own
        // app ("Bus Tracker PR 12"), any other build is "Bus Tracker dev": installing one
        // never replaces the official app or its data.
        when {
            official -> manifestPlaceholders["appLabel"] = "Bus Tracker"
            pullRequest != null -> {
                applicationIdSuffix = ".pr$pullRequest"
                manifestPlaceholders["appLabel"] = "Bus Tracker PR $pullRequest"
            }
            else -> {
                applicationIdSuffix = ".dev"
                manifestPlaceholders["appLabel"] = "Bus Tracker dev"
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
