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
import io.github.anderewrey.bustracker.data.EndRule
import io.github.anderewrey.bustracker.data.clock
import io.github.anderewrey.bustracker.data.validMinutes
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role
import io.github.anderewrey.bustracker.data.Run
import io.github.anderewrey.bustracker.data.Zone
import io.github.anderewrey.bustracker.data.ZoneRole
import io.github.anderewrey.bustracker.data.autoText
import io.github.anderewrey.bustracker.data.nameTaken
import io.github.anderewrey.bustracker.data.headsUpZoneOf
import io.github.anderewrey.bustracker.data.mainZoneOf
import io.github.anderewrey.bustracker.monitor.Scheduler
import io.github.anderewrey.bustracker.monitor.testHeadsUp
import io.github.anderewrey.bustracker.monitor.testRing
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale

/**
 * Everything about one run on one page (DESIGN.md §5.1): about the run, its monitoring period,
 * automatic start, heads-up and main zones, and end of monitoring.
 */
@Composable
fun RunScreen(run: Run, onBack: () -> Unit, onChanges: () -> Unit) {
    val context = LocalContext.current
    val auto by Session.autoStart.collectAsState()
    val changes by Session.changes.collectAsState()
    val allZones by Session.zones.collectAsState()
    val runs by Session.runs.collectAsState()
    val schools by Session.schools.collectAsState()
    val pending = changes.firstOrNull { it.needsChoice && it.new?.key == run.key }
    val main = mainZoneOf(run, allZones)
    val headsUp = headsUpZoneOf(run, allZones)
    // The zone screen: open with null for a new zone, or with the zone to change.
    var editing by remember { mutableStateOf(false) }
    // Which of the run's zones the zone screen is for.
    var editingHeadsUp by remember { mutableStateOf(false) }
    var editZone by remember { mutableStateOf<Zone?>(null) }
    // A changed zone that other runs use too, waiting for "all runs" or "this run only".
    var sharedSave by remember { mutableStateOf<Zone?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }

    if (editing) {
        BackHandler { editing = false }
        val stop = if (run.lat != null && run.lon != null) run.lat to run.lon else null
        val kind = if (editingHeadsUp) "heads-up zone" else "main zone"
        val title = editZone?.let { "${kind.replaceFirstChar { c -> c.uppercase() }} · ${it.name}" } ?: "New $kind · ${run.key}"
        // A heads-up zone is drawn with the main zone shown faintly, so it goes where the bus comes from.
        val faint = if (editingHeadsUp) main else null
        ZoneEditorScreen(stop, title, editZone, taken = { nameTaken(allZones, it, editZone?.id) }, onCancel = { editing = false }, faint = faint) { name, corners ->
            val zone = Zone(editZone?.id ?: "", name, if (editingHeadsUp) ZoneRole.HEADS_UP else ZoneRole.MAIN, corners)
            val base = editZone
            if (base != null && Session.runsUsing(base.id).any { it.key != run.key }) {
                sharedSave = zone
            } else {
                val others = Session.saveZone(context, run.key, zone, headsUp = editingHeadsUp)
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
                        Session.saveZone(context, run.key, zone, headsUp = editingHeadsUp)
                        sharedSave = null
                        editing = false
                    }) { Text("Change for all runs") }
                },
                dismissButton = {
                    Row {
                        TextButton(onClick = { sharedSave = null }) { Text("Cancel") }
                        TextButton(enabled = !copyNameTaken, onClick = {
                            Session.saveZone(context, run.key, zone, copy = true, headsUp = editingHeadsUp)
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
        BackHeader(onBack, "${run.bus} · ${run.plannedLine}")

        Section("About this run")
        Info("Bus", run.bus)
        Info("Stop", "${run.stopType} · ${run.time}")
        Info("Address", run.address)
        Info("School", run.school.ifEmpty { "known after the next rescan" })
        // Only while a stop change waits for an answer; once answered (keep or new) nothing shows.
        if (pending != null) TextButton(onClick = onChanges) {
            Text(
                "Stop changed: choose a configuration ›",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.titleSmall,
            )
        }

        Section("Watching period")
        if (run.periodRange.isEmpty()) {
            Text("No planned time: the run can't be watched by itself.")
        } else {
            Text(run.periodRange, style = MaterialTheme.typography.bodyLarge)
            val planned = run.planned?.let { clock(it) } ?: run.time
            MinutesRow("before $planned", run.before, canDown = validMinutes(run.before - STEP, run.after), canUp = validMinutes(run.before + STEP, run.after)) {
                Session.setPeriodMinutes(context, run.key, run.before + it, run.after)
            }
            MinutesRow("after $planned", run.after, canDown = validMinutes(run.before, run.after - STEP), canUp = validMinutes(run.before, run.after + STEP)) {
                Session.setPeriodMinutes(context, run.key, run.before, run.after + it)
            }
            if (run.before != MONITOR_MINUTES || run.after != MONITOR_MINUTES) TextButton(onClick = {
                Session.setPeriodMinutes(context, run.key, MONITOR_MINUTES, MONITOR_MINUTES)
            }) { Text("Default: $MONITOR_MINUTES min before and after") }
            Text("A change applies at once, also while the run is watched.", style = MaterialTheme.typography.bodySmall)
        }

        Section("Watching days")
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

        // In the order the bus meets them: the heads-up zone, then the main zone (DESIGN.md §6.1).
        Section("Heads-up zone")
        Text(
            headsUp?.let { "${it.name}: a sound once when the bus enters it." } ?: "None: no heads-up.",
            style = MaterialTheme.typography.bodyLarge,
        )
        headsUp?.let { z ->
            val others = runs.filter { (it.headsUpZone == z.id || it.mainZone == z.id) && it.key != run.key }
            if (others.isNotEmpty()) Text("Also used by ${others.joinToString { it.key }}", style = MaterialTheme.typography.bodySmall)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                editZone = null
                editingHeadsUp = true
                notice = null
                editing = true
            }) { Text("Draw new") }
            if (headsUp != null) OutlinedButton(onClick = {
                editZone = headsUp
                editingHeadsUp = true
                notice = null
                editing = true
            }) { Text("Edit") }
            val choices = allZones.filter { it.id != headsUp?.id && it.id != main?.id }
            if (choices.isNotEmpty()) Box {
                var open by remember { mutableStateOf(false) }
                OutlinedButton(onClick = { open = true }) { Text("Choose") }
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    choices.forEach { z ->
                        DropdownMenuItem(text = { Text(z.name) }, onClick = {
                            Session.setHeadsUpZone(context, run.key, z.id)
                            notice = null
                            open = false
                        })
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (headsUp != null) TextButton(onClick = { Session.setHeadsUpZone(context, run.key, null) }) { Text("Remove") }
            OutlinedButton(onClick = { testHeadsUp(context) }) { Text("Test sound") }
        }
        if (headsUp != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = run.alarmAfterHeadsUp, onCheckedChange = { Session.setAlarmAfterHeadsUp(context, run.key, it) })
                Text("Alarm only after the heads-up")
            }
            Text(
                if (run.alarmAfterHeadsUp) "The main zone rings only once the bus came through ${headsUp.name}. If no position falls in it, the alarm doesn't ring: draw it 500 m long or more."
                else "The main zone rings whether or not the bus came through ${headsUp.name}.",
                style = MaterialTheme.typography.bodySmall,
            )
        }

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
                editingHeadsUp = false
                notice = null
                editing = true
            }) { Text("Draw new") }
            if (main != null) OutlinedButton(onClick = {
                editZone = main
                editingHeadsUp = false
                notice = null
                editing = true
            }) { Text("Edit") }
            val choices = allZones.filter { it.id != main?.id && it.id != headsUp?.id }
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

        Section("End of watching")
        Text("Watching ends by itself when:", style = MaterialTheme.typography.bodyMedium)
        val endZone = allZones.firstOrNull { it.id == run.endZoneId }
        val school = schools.firstOrNull { it.name.equals(run.school, ignoreCase = true) }
        for (rule in EndRule.entries) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().selectable(selected = run.endOfWatch == rule, role = Role.RadioButton) {
                    Session.setEndRule(context, run.key, rule, run.endZone)
                },
            ) {
                RadioButton(selected = run.endOfWatch == rule, onClick = null, modifier = Modifier.padding(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        when (rule) {
                            EndRule.LEAVES_ZONE -> "Bus leaves ${endZone?.name ?: "a zone"}"
                            EndRule.ENTERS_ZONE -> "Bus enters ${endZone?.name ?: "a zone"}"
                            else -> rule.label
                        }
                    )
                    val default = if (run.isDropoff) EndRule.LEAVES_ZONE else EndRule.SCHOOL
                    val note = when {
                        rule == EndRule.SCHOOL && school?.known != true -> "School position unknown: set it on the overview page"
                        rule == EndRule.SCHOOL -> "${school!!.name}, within ${school.radius.toInt()} m"
                        (rule == EndRule.LEAVES_ZONE || rule == EndRule.ENTERS_ZONE) && endZone == null -> "No zone: draw or choose one"
                        rule == default -> "Default for a ${if (run.isDropoff) "dropoff" else "pickup"} run"
                        else -> null
                    }
                    note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
        // The zone of "leaves" or "enters": the main zone unless another one is chosen.
        if ((run.endOfWatch == EndRule.LEAVES_ZONE || run.endOfWatch == EndRule.ENTERS_ZONE) && allZones.isNotEmpty()) Box {
            var open by remember { mutableStateOf(false) }
            OutlinedButton(onClick = { open = true }) { Text("Zone: ${endZone?.name ?: "none"}${if (run.endZone == null) " (main zone)" else ""}") }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                DropdownMenuItem(text = { Text("The main zone (${main?.name ?: "none"})") }, onClick = {
                    Session.setEndRule(context, run.key, run.endOfWatch, null)
                    open = false
                })
                allZones.forEach { z ->
                    DropdownMenuItem(text = { Text(z.name) }, onClick = {
                        Session.setEndRule(context, run.key, run.endOfWatch, z.id)
                        open = false
                    })
                }
            }
        }
        Text("The Alarm switch and the period's end always apply.", style = MaterialTheme.typography.bodySmall)
    }
}

/** Minutes watched before or after the planned time, by steps of 5. */
@Composable
private fun MinutesRow(label: String, minutes: Int, canDown: Boolean, canUp: Boolean, onStep: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(enabled = canDown, onClick = { onStep(-STEP) }) { Text("−") }
        Text("$minutes min", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 12.dp).width(64.dp))
        OutlinedButton(enabled = canUp, onClick = { onStep(STEP) }) { Text("+") }
        Text(label, modifier = Modifier.padding(start = 12.dp))
    }
}

private const val STEP = 5

@Composable
private fun Info(label: String, value: String) {
    Row {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(72.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
