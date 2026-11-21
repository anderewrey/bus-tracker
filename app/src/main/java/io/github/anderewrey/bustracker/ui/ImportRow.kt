// SPDX-FileCopyrightText: 2026 anderewrey
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.anderewrey.bustracker.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.anderewrey.bustracker.importFromOfficial
import io.github.anderewrey.bustracker.isTestBuild
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "Import from Bus Tracker", in test builds only (DESIGN.md §8, Configuration import): copies the
 * official app's configuration over this one's. With [confirm], asks first, as this build's own
 * runs, zones and settings are replaced. [onDone] runs after a successful import.
 */
@Composable
fun ImportRow(confirm: Boolean, onDone: () -> Unit) {
    val context = LocalContext.current
    if (!isTestBuild(context)) return
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var asking by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }

    fun run() {
        busy = true
        result = null
        scope.launch {
            try {
                result = withContext(Dispatchers.IO) { importFromOfficial(context) }
                failed = false
                onDone()
            } catch (e: Exception) {
                result = e.message ?: e.javaClass.simpleName
                failed = true
            } finally {
                busy = false
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            "A test build. It can copy the official Bus Tracker's sign-in, runs, zones, schools and settings; the official app isn't changed.",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedButton(enabled = !busy, onClick = { if (confirm) asking = true else run() }) {
            Text(if (busy) "Importing…" else "Import from Bus Tracker")
        }
        result?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    if (asking) AlertDialog(
        onDismissRequest = { asking = false },
        title = { Text("Replace this build's configuration?") },
        text = { Text("Its sign-in, runs, zones, schools and settings are replaced by the official Bus Tracker's. Watching stops first.") },
        confirmButton = {
            TextButton(onClick = {
                asking = false
                run()
            }) { Text("Import") }
        },
        dismissButton = { TextButton(onClick = { asking = false }) { Text("Cancel") } },
    )
}
