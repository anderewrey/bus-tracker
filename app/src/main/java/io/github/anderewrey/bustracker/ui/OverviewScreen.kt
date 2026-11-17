// SPDX-FileCopyrightText: 2026 anderewrey
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.anderewrey.bustracker.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.anderewrey.bustracker.Session
import io.github.anderewrey.bustracker.data.PositionSource
import io.github.anderewrey.bustracker.data.Run
import io.github.anderewrey.bustracker.data.Zone

/**
 * Everything the app knows, by kind (DESIGN.md §5.3): children, schools, runs and zones. Each
 * school opens its School page and each run its page; a zone no run uses can be deleted.
 * Anything waiting for an answer shows in red at the top.
 */
@Composable
fun OverviewScreen(onBack: () -> Unit, onChanges: () -> Unit, onSchool: (String) -> Unit, onRun: (Run) -> Unit) {
    val context = LocalContext.current
    val runs by Session.runs.collectAsState()
    val schools by Session.schools.collectAsState()
    val options by Session.schoolOptions.collectAsState()
    val zones by Session.zones.collectAsState()
    val changes by Session.changes.collectAsState()
    var deleting by remember { mutableStateOf<Zone?>(null) }

    Column(
        Modifier.safeDrawingPadding().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(Modifier.fillMaxWidth()) {
            TextButton(onClick = onBack) { Text("Back") }
        }
        Text("Children, schools, runs and zones", style = MaterialTheme.typography.headlineSmall)

        // Waiting for an answer.
        val stopChanges = changes.count { it.needsChoice }
        if (stopChanges > 0) TextButton(onClick = onChanges) {
            Text(
                if (stopChanges == 1) "Stop changed: choose a configuration ›" else "$stopChanges stop changes: choose a configuration ›",
                color = MaterialTheme.colorScheme.error,
            )
        }
        options.keys.forEach { name ->
            TextButton(onClick = { onSchool(name) }) { Text("$name: choose the right school ›", color = MaterialTheme.colorScheme.error) }
        }

        Section("Children")
        val children = runs.flatMap { it.students }.distinctBy { it.guid }
        if (children.isEmpty()) Text("None")
        children.forEach { child ->
            val theirs = runs.filter { r -> r.students.any { it.guid == child.guid } }
            Line(
                child.name,
                listOf(theirs.map { it.school }.filter { it.isNotBlank() }.distinct().joinToString(), theirs.joinToString { it.key })
                    .filter { it.isNotBlank() }.joinToString(" · "),
            )
        }
        Text("From BusPlanner, read-only.", style = MaterialTheme.typography.bodySmall)

        Section("Schools")
        if (schools.isEmpty()) Text("None known yet: they come with the next rescan.")
        schools.forEach { school ->
            val going = runs.filter { it.school.equals(school.name, ignoreCase = true) }
            val source = if (school.name in options) "choose the right school" else school.source.label
            Line(
                "${school.name} ›",
                listOf("$source · ${school.radius.toInt()} m circle", going.joinToString { it.key }).filter { it.isNotBlank() }.joinToString(" · "),
                warn = school.source == PositionSource.UNKNOWN,
            ) { onSchool(school.name) }
        }

        Section("Runs")
        runs.forEach { run -> Line("${run.bus} · ${run.plannedLine} ›", run.address) { onRun(run) } }

        Section("Zones")
        if (zones.isEmpty()) Text("None: draw one on a run's page.")
        zones.forEach { zone ->
            val users = runs.filter { it.mainZone == zone.id || it.endZone == zone.id }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                    Text(zone.name, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        if (users.isEmpty()) "Used by no run" else "Used by ${users.joinToString { it.key }}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (users.isEmpty()) TextButton(onClick = { deleting = zone }) { Text("Delete") }
            }
        }
        Text("Draw and change zones on a run's page.", style = MaterialTheme.typography.bodySmall)
    }

    deleting?.let { zone ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete ${zone.name}?") },
            text = { Text("No run uses it. This can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    Session.deleteZone(context, zone.id)
                    deleting = null
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

/** One line of a list: a title, details under it, and a tap when it opens something. */
@Composable
private fun Line(title: String, details: String, warn: Boolean = false, onClick: (() -> Unit)? = null) {
    Column(
        (if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).fillMaxWidth().padding(vertical = 6.dp),
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        if (details.isNotBlank()) Text(
            details, style = MaterialTheme.typography.bodySmall,
            color = if (warn) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
