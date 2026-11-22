// SPDX-FileCopyrightText: 2026 anderewrey
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.anderewrey.bustracker

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import io.github.anderewrey.bustracker.data.Store
import io.github.anderewrey.bustracker.net.LatestRelease
import io.github.anderewrey.bustracker.net.fetchLatestRelease
import io.github.anderewrey.bustracker.net.isNewer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.time.LocalDate

/**
 * A newer official version on GitHub, shown on the run list (user, 2026-11-22: no Play Store, so
 * nothing else tells a parent). Asked once a day, when the app comes to the screen; only the
 * official app asks, test builds don't (DESIGN.md §8, Updates).
 */
object Updates {
    private const val OFFICIAL_ID = "io.github.anderewrey.bustracker"

    private val _available = MutableStateFlow<LatestRelease?>(null)
    val available: StateFlow<LatestRelease?> = _available

    private fun installedCode(context: Context): Long {
        val pm = context.packageManager
        val info = if (Build.VERSION.SDK_INT >= 33) pm.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
        else @Suppress("DEPRECATION") pm.getPackageInfo(context.packageName, 0)
        return info.longVersionCode
    }

    private fun official(context: Context): Boolean =
        context.packageName == OFFICIAL_ID && context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0

    /** What the last check found, compared with what is installed now (it may have been updated since). */
    fun init(context: Context) {
        if (!official(context)) return
        _available.value = Store(context).latestRelease?.takeIf { isNewer(it, installedCode(context)) }
    }

    /** Asks GitHub, at most once a day. */
    suspend fun checkDaily(context: Context) {
        if (!official(context)) return
        val store = Store(context)
        val today = LocalDate.now()
        if (store.updateCheckedOn == today) return
        val latest = try {
            withContext(Dispatchers.IO) { fetchLatestRelease() }
        } catch (e: Exception) {
            AppLog.log("update check: error ${e.message ?: e.javaClass.simpleName}")
            return
        }
        store.updateCheckedOn = today
        store.latestRelease = latest
        val newer = latest?.takeIf { isNewer(it, installedCode(context)) }
        _available.value = newer
        AppLog.log(if (newer != null) "update check: ${newer.name} is out (build-${newer.code})" else "update check: up to date")
    }
}
