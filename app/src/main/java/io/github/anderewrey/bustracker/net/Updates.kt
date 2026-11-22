// SPDX-FileCopyrightText: 2026 anderewrey
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.anderewrey.bustracker.net

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** GitHub's latest full release: pre-releases are never "latest" (DESIGN.md §8, Updates). */
const val LATEST_RELEASE_API = "https://api.github.com/repos/anderewrey/bus-tracker/releases/latest"

/** The official app's APK of that release, the same link as the site's Download button. */
const val LATEST_APK = "https://github.com/anderewrey/bus-tracker/releases/latest/download/bus-tracker.apk"

/**
 * An official version on GitHub: its name ("0.19") and the number of its tag (build-29922755).
 * The tag is the versionCode of the pre-release it was promoted from; the promoted APK is built
 * again later, so its own versionCode is a little higher.
 */
data class LatestRelease(val name: String, val code: Long)

/** The release from GitHub's answer; null when it isn't a build-N release. */
fun parseLatestRelease(json: String): LatestRelease? {
    val o = JSONObject(json)
    val code = Regex("""^build-(\d+)$""").find(o.optString("tag_name"))?.groupValues?.get(1)?.toLongOrNull() ?: return null
    val name = o.optString("name").ifBlank { o.optString("tag_name") }
    return LatestRelease(name, code)
}

/**
 * Whether the release is newer than the installed app. Its tag number is the build time of its
 * pre-release, so the installed official version (built again after it) is never "older" than its
 * own release, and any release made after the installed build is newer.
 */
fun isNewer(latest: LatestRelease, installedCode: Long): Boolean = latest.code > installedCode

private val updatesHttp = OkHttpClient.Builder()
    .connectTimeout(10, TimeUnit.SECONDS)
    .readTimeout(10, TimeUnit.SECONDS)
    .build()

/** Asks GitHub for the latest official version. Sends nothing about the user. */
fun fetchLatestRelease(): LatestRelease? {
    val req = Request.Builder().url(LATEST_RELEASE_API)
        .header("Accept", "application/vnd.github+json")
        .header("User-Agent", "BusTracker")
        .build()
    updatesHttp.newCall(req).execute().use { resp ->
        if (resp.code == 404) return null
        if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
        return parseLatestRelease(resp.body.string())
    }
}
