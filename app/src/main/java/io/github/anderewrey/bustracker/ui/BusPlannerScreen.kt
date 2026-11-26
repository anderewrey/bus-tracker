// SPDX-FileCopyrightText: 2026 anderewrey
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.anderewrey.bustracker.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.anderewrey.bustracker.Session
import io.github.anderewrey.bustracker.data.Store
import io.github.anderewrey.bustracker.data.clock
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * Everything that comes from BusPlanner (DESIGN.md §5.2, user 2026-11-21): the account, Reload
 * from BusPlanner with its last result, and the schedule changes waiting. How the phone reacts
 * stays in Settings; today's notices show on each run's card.
 */
@Composable
fun BusPlannerScreen(onBack: () -> Unit, onShowChanges: () -> Unit, onLogout: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val changes by Session.changes.collectAsState()
    val store = remember { Store(context) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    // Bumped after a reload, to read the last result again.
    var version by remember { mutableIntStateOf(0) }

    Column(
        Modifier.safeDrawingPadding().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        BackHeader(onBack, "BusPlanner")

        Section("Account")
        Text(store.email ?: "")
        Text(store.portal?.removePrefix("https://") ?: "", style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = onLogout) { Text("Log out") }

        Section("Reload from BusPlanner")
        Text(
            "Reads the runs, stops and times again, and shows what changed. It also runs by itself once a day.",
            style = MaterialTheme.typography.bodySmall,
        )
        val last = remember(version) { store.lastReload }
        Text(
            last?.let { (at, why, found) ->
                val day = if (at.toLocalDate() == LocalDate.now()) "today" else at.toLocalDate().toString()
                val how = if (why == "requested") "by hand" else why
                "Last: $day ${clock(at)} ($how) · " + if (found == 0) "no change" else "$found change(s)"
            } ?: "Not reloaded yet",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(enabled = !busy, onClick = {
            busy = true
            message = null
            scope.launch {
                try {
                    val found = Session.scan(context).changes
                    Session.refreshNotices(context)
                    failed = false
                    message = if (found.isEmpty()) "No change" else "${found.size} change(s) found"
                    if (found.isNotEmpty()) onShowChanges()
                } catch (e: Exception) {
                    failed = true
                    message = "Reload failed: ${e.message ?: e.javaClass.simpleName}"
                } finally {
                    busy = false
                    version++
                }
            }
        }) { Text(if (busy) "Reloading…" else "Reload") }
        message?.let { Text(it, color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) }

        Section("Schedule changes")
        if (changes.isEmpty()) {
            Text("None waiting.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            TextButton(onClick = onShowChanges) { Text("${changes.size} waiting · See changes ›") }
        }
    }
}
