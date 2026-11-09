// SPDX-FileCopyrightText: 2026 anderewrey
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.anderewrey.bustracker

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.io.OutputStream
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * What the app is doing. Every line goes to a file per day in the app's storage (kept 7 days),
 * and the Log screen shows the lines since it was last cleared. Never log passwords or cookies.
 */
object AppLog {
    private const val MAX_LINES = 5000
    private const val KEEP_DAYS = 7L
    private val TIME = DateTimeFormatter.ofPattern("HH:mm:ss")

    private var dir: File? = null

    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines

    /** Opens the log folder, drops files older than a week and shows today's lines. */
    @Synchronized
    fun init(context: Context) {
        if (dir != null) return
        val d = File(context.filesDir, "logs").apply { mkdirs() }
        dir = d
        val oldest = LocalDate.now().minusDays(KEEP_DAYS - 1).toString()
        d.listFiles()?.filter { it.name.removeSuffix(".txt") < oldest }?.forEach { it.delete() }
        val today = fileOf(LocalDate.now())
        if (today.exists()) _lines.value = today.readLines().takeLast(MAX_LINES)
    }

    private fun fileOf(day: LocalDate) = File(dir, "$day.txt")

    @Synchronized
    fun log(message: String) {
        val line = "${LocalTime.now().format(TIME)}  $message"
        _lines.value = (_lines.value + line).takeLast(MAX_LINES)
        dir?.let { runCatching { fileOf(LocalDate.now()).appendText(line + "\n") } }
    }

    /** Empties the Log screen only; the files stay. */
    @Synchronized
    fun clearScreen() {
        _lines.value = emptyList()
    }

    /** Writes every kept day file into one zip. */
    @Synchronized
    fun exportZip(out: OutputStream) {
        ZipOutputStream(out).use { zip ->
            for (f in dir?.listFiles()?.sortedBy { it.name }.orEmpty()) {
                zip.putNextEntry(ZipEntry("bustracker-log-${f.name}"))
                f.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }
}
