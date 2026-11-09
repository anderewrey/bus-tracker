// SPDX-FileCopyrightText: 2026 anderewrey
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.anderewrey.bustracker

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.io.OutputStream
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * What the app is doing. Every line goes to a file per day in the app's storage (kept 7 days),
 * and the Debug screen shows the lines since it was last cleared. Never log passwords or cookies.
 */
object AppLog {
    private const val MAX_LINES = 5000
    private const val KEEP_DAYS = 7L
    private val TIME = DateTimeFormatter.ofPattern("HH:mm:ss")

    private val BUILT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    private var dir: File? = null

    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines

    /** This build, short: "Bus Tracker dev 0.13-1-g5a71da1, built 2026-11-09 16:34 UTC". */
    var version = ""
        private set

    /** This build, with the app ID and versionCode, for the log files and exports. */
    var build = ""
        private set

    /** Opens the log folder, drops files older than a week and shows today's lines. */
    @Synchronized
    fun init(context: Context) {
        if (dir != null) return
        readBuild(context)
        val d = File(context.filesDir, "logs").apply { mkdirs() }
        dir = d
        val oldest = LocalDate.now().minusDays(KEEP_DAYS - 1).toString()
        d.listFiles()?.filter { it.name.removeSuffix(".txt") < oldest }?.forEach { it.delete() }
        val today = fileOf(LocalDate.now())
        if (today.exists()) _lines.value = today.readLines().takeLast(MAX_LINES)
        // Each start of the app says which build wrote the lines after it.
        log("app start: $build")
    }

    // The version comes from git and the versionCode is the build time in minutes (DESIGN.md §8, Versions).
    private fun readBuild(context: Context) {
        val pm = context.packageManager
        val info = if (Build.VERSION.SDK_INT >= 33) pm.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
        else @Suppress("DEPRECATION") pm.getPackageInfo(context.packageName, 0)
        val code = info.longVersionCode
        val built = Instant.ofEpochSecond(code * 60).atOffset(ZoneOffset.UTC).format(BUILT)
        version = "${context.applicationInfo.loadLabel(pm)} ${info.versionName}, built $built UTC"
        build = "$version (${context.packageName}, versionCode $code, Android ${Build.VERSION.RELEASE})"
    }

    private fun fileOf(day: LocalDate) = File(dir, "$day.txt")

    @Synchronized
    fun log(message: String) {
        val line = "${LocalTime.now().format(TIME)}  $message"
        _lines.value = (_lines.value + line).takeLast(MAX_LINES)
        dir?.let { runCatching { fileOf(LocalDate.now()).appendText(line + "\n") } }
    }

    /** Empties the Debug screen only; the files stay. */
    @Synchronized
    fun clearScreen() {
        _lines.value = emptyList()
    }

    /** Writes every kept day file into one zip, after a file saying which build exported it. */
    @Synchronized
    fun exportZip(out: OutputStream) {
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("bustracker-build.txt"))
            zip.write("$build\n".toByteArray())
            zip.closeEntry()
            for (f in dir?.listFiles()?.sortedBy { it.name }.orEmpty()) {
                zip.putNextEntry(ZipEntry("bustracker-log-${f.name}"))
                f.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }
}
