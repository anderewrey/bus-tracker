// SPDX-FileCopyrightText: 2026 anderewrey
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.anderewrey.bustracker.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.anderewrey.bustracker.Session
import io.github.anderewrey.bustracker.data.ChangeKind
import io.github.anderewrey.bustracker.data.Run
import io.github.anderewrey.bustracker.data.RunChange
import java.time.format.DateTimeFormatter

private val FOUND = DateTimeFormatter.ofPattern("MM-dd H:mm")

/** What rescans found different: the whole past stop and the whole new one, no map. */
@Composable
fun ChangesScreen(changes: List<RunChange>, onBack: () -> Unit) {
    val context = LocalContext.current
    Column(Modifier.safeDrawingPadding().padding(16.dp)) {
        Row(Modifier.fillMaxWidth()) {
            TextButton(onClick = onBack) { Text("Back") }
            Text("Changes", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 6.dp))
        }
        if (changes.isEmpty()) Text("No change waiting.", Modifier.padding(vertical = 8.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(changes, key = { it.id }) { c ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(c.title, style = MaterialTheme.typography.titleMedium)
                        Text("found ${c.found.format(FOUND)}", style = MaterialTheme.typography.bodySmall)
                        Text("Past: " + (c.past?.let { line(it, c) } ?: "none"))
                        Text("New: " + (c.new?.let { line(it, c) } ?: "none"))
                        Text(note(c), style = MaterialTheme.typography.bodySmall)
                        // Stacked: both long labels don't fit side by side on a phone.
                        if (c.needsChoice) {
                            Button(onClick = { Session.answer(context, c, keep = true) }, Modifier.fillMaxWidth()) {
                                Text("Keep same configuration")
                            }
                            OutlinedButton(onClick = { Session.answer(context, c, keep = false) }, Modifier.fillMaxWidth()) {
                                Text("New configuration")
                            }
                        } else {
                            Button(onClick = { Session.answer(context, c, keep = true) }) { Text("OK") }
                        }
                    }
                }
            }
        }
    }
}

/** The whole stop; the children too when they are part of the change. */
private fun line(run: Run, c: RunChange): String =
    run.stopLine +
        (if (ChangeKind.STUDENTS in c.kinds) " · ${run.students.joinToString(", ") { it.name }}" else "") +
        (if (ChangeKind.SCHOOL in c.kinds) " · ${run.school}" else "")

private fun note(c: RunChange): String = when {
    c.needsChoice -> "Until you choose, the run keeps the zones of the past stop. " +
        "A new configuration takes the zones of the new stop."
    ChangeKind.ADDED in c.kinds -> "The new run is on the run list."
    ChangeKind.REMOVED in c.kinds -> "The run is no longer monitored."
    ChangeKind.TIME in c.kinds -> "The new time is already applied."
    else -> "Already applied."
}
