// SPDX-FileCopyrightText: 2026 anderewrey
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.anderewrey.bustracker.ui

import androidx.activity.compose.BackHandler
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
import androidx.compose.material3.OutlinedButton
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
import io.github.anderewrey.bustracker.data.ZoneRole
import io.github.anderewrey.bustracker.data.nameTaken

/**
 * Everything the app knows, by kind (DESIGN.md §5.3): schools, runs and zones. Each
 * school opens its School page and each run its page. A zone opens the zone screen, to change it
 * for every run using it; Draw new zone adds one; a zone no run uses can be deleted. Anything
 * waiting for an answer shows in red at the top.
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
    // The zone screen: open with null for a new zone, or with the zone to change.
    var editing by remember { mutableStateOf(false) }
    var editZone by remember { mutableStateOf<Zone?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }

    if (editing) {
        BackHandler { editing = false }
        val base = editZone
        // A new zone opens on the first run's stop; a zone being changed, on itself.
        val center = if (base == null) runs.firstOrNull { it.lat != null && it.lon != null }?.let { it.lat!! to it.lon!! } else null
        ZoneEditorScreen(
            center, base?.let { "Zone · ${it.name}" } ?: "New zone", base,
            taken = { nameTaken(zones, it, base?.id) }, onCancel = { editing = false },
        ) { name, corners ->
            val others = Session.saveZone(context, Zone(base?.id ?: "", name, base?.role ?: ZoneRole.MAIN, corners))
            notice = if (others.isEmpty()) null else "$name is now the main zone of ${others.joinToString()}: its stop is inside."
            editing = false
        }
        return
    }

    Column(
        Modifier.safeDrawingPadding().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(Modifier.fillMaxWidth()) {
            TextButton(onClick = onBack) { Text("Back") }
        }
        Text("Schools, runs and zones", style = MaterialTheme.typography.headlineSmall)

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
        if (zones.isEmpty()) Text("None yet.")
        zones.forEach { zone ->
            val users = runs.filter { it.mainZone == zone.id || it.endZone == zone.id || it.headsUpZone == zone.id }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(
                    Modifier.weight(1f).clickable {
                        editZone = zone
                        editing = true
                    }.padding(vertical = 6.dp),
                ) {
                    Text("${zone.name} ›", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        if (users.isEmpty()) "Used by no run" else "Used by ${users.joinToString { it.key }}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (users.isEmpty()) TextButton(onClick = { deleting = zone }) { Text("Delete") }
            }
        }
        notice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        OutlinedButton(onClick = {
            editZone = null
            editing = true
        }) { Text("Draw new zone") }
        Text(
            "A change here applies to every run using the zone. To give a run its own zone, or another one, use the run's page.",
            style = MaterialTheme.typography.bodySmall,
        )
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
