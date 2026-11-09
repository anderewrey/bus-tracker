// SPDX-FileCopyrightText: 2026 anderewrey
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.anderewrey.bustracker.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
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
import io.github.anderewrey.bustracker.data.MONITOR_MINUTES
import io.github.anderewrey.bustracker.data.Run
import io.github.anderewrey.bustracker.data.Zone
import io.github.anderewrey.bustracker.data.ZoneRole
import io.github.anderewrey.bustracker.data.autoText
import io.github.anderewrey.bustracker.data.nameTaken
import io.github.anderewrey.bustracker.data.zonesFor
import io.github.anderewrey.bustracker.monitor.Scheduler
import io.github.anderewrey.bustracker.monitor.testRing
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale

/**
 * Everything about one run on one page (DESIGN.md §5.1). This first version shows the whole
 * layout; the parts not built yet are greyed out and say so.
 */
@Composable
fun RunScreen(run: Run, onBack: () -> Unit, onChanges: () -> Unit) {
    val context = LocalContext.current
    val auto by Session.autoStart.collectAsState()
    val changes by Session.changes.collectAsState()
    val allZones by Session.zones.collectAsState()
    val runs by Session.runs.collectAsState()
    val pending = changes.firstOrNull { it.needsChoice && it.new?.key == run.key }
    val main = zonesFor(run, allZones).firstOrNull()
    // The zone screen: open with null for a new zone, or with the zone to change.
    var editing by remember { mutableStateOf(false) }
    var editZone by remember { mutableStateOf<Zone?>(null) }
    // A changed zone that other runs use too, waiting for "all runs" or "this run only".
    var sharedSave by remember { mutableStateOf<Zone?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }

    if (editing) {
        BackHandler { editing = false }
        ZoneEditorScreen(run, editZone, taken = { nameTaken(allZones, it, editZone?.id) }, onCancel = { editing = false }) { name, corners ->
            val zone = Zone(editZone?.id ?: "", name, ZoneRole.MAIN, corners)
            val base = editZone
            if (base != null && Session.runsUsing(base.id).any { it.key != run.key }) {
                sharedSave = zone
            } else {
                val others = Session.saveZone(context, run.key, zone)
                notice = if (others.isEmpty()) null else "Also the main zone of ${others.joinToString()}: its stop is inside."
                editing = false
            }
        }
        sharedSave?.let { zone ->
            val others = Session.runsUsing(zone.id).filter { it.key != run.key }.joinToString { it.key }
            // A copy for this run only is a second zone: it needs a name of its own.
            val copyNameTaken = nameTaken(allZones, zone.name)
            AlertDialog(
                onDismissRequest = { sharedSave = null },
                title = { Text("${editZone?.name ?: zone.name} is also used by $others") },
                text = {
                    Text(
                        "Change it for all runs using it, or keep the old one for them and save this as a new zone for ${run.key} only?" +
                            if (copyNameTaken) "\n\nFor ${run.key} only, give it another name first (Cancel, then rename)." else ""
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        Session.saveZone(context, run.key, zone)
                        sharedSave = null
                        editing = false
                    }) { Text("Change for all runs") }
                },
                dismissButton = {
                    Row {
                        TextButton(onClick = { sharedSave = null }) { Text("Cancel") }
                        TextButton(enabled = !copyNameTaken, onClick = {
                            Session.saveZone(context, run.key, zone, copy = true)
                            sharedSave = null
                            editing = false
                        }) { Text("This run only") }
                    }
                },
            )
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
        Text("${run.bus} · ${run.plannedLine}", style = MaterialTheme.typography.headlineSmall)

        Section("About this run")
        Info("Bus", run.bus)
        Info("Stop", "${run.stopType} · ${run.time}")
        Info("Address", run.address)
        Info("Children", run.students.joinToString(" · ") { it.name })
        Info("School", run.school.ifEmpty { "known after the next rescan" })
        // Only while a stop change waits for an answer; once answered (keep or new) nothing shows.
        if (pending != null) TextButton(onClick = onChanges) {
            Text(
                "Stop changed: choose a configuration ›",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.titleSmall,
            )
        }

        Section("Monitoring period")
        Text(
            if (run.periodRange.isEmpty()) "No planned time: the run can't be monitored by itself."
            else "${run.periodRange} · $MONITOR_MINUTES min before and after ${run.plannedLine.substringAfter(' ')}"
        )
        NotBuilt("Changing the minutes")

        Section("Automatic start")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            for (day in DayOfWeek.entries) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(day.getDisplayName(TextStyle.SHORT, Locale.ENGLISH), style = MaterialTheme.typography.bodySmall)
                    Checkbox(
                        checked = day in run.days,
                        onCheckedChange = { on -> Session.setDays(context, run.key, if (on) run.days + day else run.days - day) },
                    )
                }
            }
        }
        Text(
            if (!auto.on) "Automatic start is ${autoText(auto)} for all runs."
            else Scheduler.nextText(context, run.key)?.let { "Next automatic start: $it" } ?: "No automatic start.",
            style = MaterialTheme.typography.bodySmall,
        )

        Section("Main zone")
        Text(
            main?.let { "${it.name}: the alarm rings when the bus enters it." } ?: "None: no alarm for this run.",
            style = MaterialTheme.typography.bodyLarge,
        )
        main?.let { z ->
            val others = runs.filter { it.mainZone == z.id && it.key != run.key }
            if (others.isNotEmpty()) Text("Also used by ${others.joinToString { it.key }}", style = MaterialTheme.typography.bodySmall)
        }
        notice?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                editZone = null
                notice = null
                editing = true
            }) { Text("Draw new") }
            if (main != null) OutlinedButton(onClick = {
                editZone = main
                notice = null
                editing = true
            }) { Text("Edit") }
            val choices = allZones.filter { it.id != main?.id }
            if (choices.isNotEmpty()) Box {
                var open by remember { mutableStateOf(false) }
                OutlinedButton(onClick = { open = true }) { Text("Choose") }
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    choices.forEach { z ->
                        DropdownMenuItem(text = { Text(z.name) }, onClick = {
                            Session.setMainZone(context, run.key, z.id)
                            notice = null
                            open = false
                        })
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (main != null) TextButton(onClick = {
                Session.setMainZone(context, run.key, null)
                notice = null
            }) { Text("Remove") }
            OutlinedButton(onClick = { testRing(context) }) { Text("Test alarm") }
        }
        NotBuilt("Heads-up zones")

        Section("End of monitoring")
        val choices = listOf(
            "Bus parked at the school" to !run.isDropoff,
            "Bus leaves ${main?.name ?: "a zone"}" to run.isDropoff,
            "Bus enters a zone" to false,
            "Only at the end of the period" to false,
        )
        for ((label, selected) in choices) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = selected, onClick = null, enabled = false, modifier = Modifier.padding(12.dp))
                Text(label, color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text(
            "The Alarm switch and the period's end always apply.",
            style = MaterialTheme.typography.bodySmall,
        )
        NotBuilt("Choosing: for now it follows the stop type (pickup: school, dropoff: leaving the zone)")
    }
}

@Composable
internal fun Section(title: String) {
    HorizontalDivider(Modifier.padding(top = 16.dp, bottom = 8.dp))
    Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
}

@Composable
internal fun NotBuilt(what: String) {
    Text("$what: not built yet.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
}

@Composable
private fun Info(label: String, value: String) {
    Row {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(72.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
