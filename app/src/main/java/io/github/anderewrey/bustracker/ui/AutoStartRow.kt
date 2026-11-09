// SPDX-FileCopyrightText: 2026 anderewrey
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.anderewrey.bustracker.ui

import android.app.DatePickerDialog
import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
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
import io.github.anderewrey.bustracker.data.AutoStart
import io.github.anderewrey.bustracker.data.Run
import io.github.anderewrey.bustracker.data.autoText
import io.github.anderewrey.bustracker.data.daysText
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/** The switch over every run's automatic start, e.g. off for school holidays, and the days of all runs. */
@Composable
fun AutoStartRow(runs: List<Run>, onDays: () -> Unit) {
    val context = LocalContext.current
    val auto by Session.autoStart.collectAsState()
    var asking by remember { mutableStateOf(false) }
    val days = runs.map { it.days }.distinct().let { if (it.size <= 1) daysText(it.firstOrNull() ?: emptySet()) else "Custom per run" }

    Card(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Automatic start", style = MaterialTheme.typography.titleSmall)
                Text(
                    (if (auto.on) days else "${autoText(auto).replaceFirstChar { it.uppercase() }} · $days") + " ›",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable(onClick = onDays).padding(vertical = 4.dp),
                )
            }
            Switch(checked = auto.on, onCheckedChange = { on ->
                if (on) Session.setAutoStart(context, AutoStart()) else asking = true
            })
        }
    }

    if (asking) AlertDialog(
        onDismissRequest = { asking = false },
        title = { Text("Turn automatic start off") },
        text = { Text("With a date, runs start by themselves again that morning, e.g. after a school holiday.") },
        confirmButton = {
            TextButton(onClick = {
                asking = false
                pickResumeDay(context) { Session.setAutoStart(context, AutoStart(on = false, resumeOn = it)) }
            }) { Text("Off until…") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { asking = false }) { Text("Cancel") }
                TextButton(onClick = {
                    asking = false
                    Session.setAutoStart(context, AutoStart(on = false))
                }) { Text("Off") }
            }
        },
    )
}

/** Android's date picker, from tomorrow on, opened on next Monday. */
private fun pickResumeDay(context: Context, onPicked: (LocalDate) -> Unit) {
    val today = LocalDate.now()
    val monday = today.with(TemporalAdjusters.next(DayOfWeek.MONDAY))
    DatePickerDialog(
        context,
        { _, y, m, d -> onPicked(LocalDate.of(y, m + 1, d)) },
        monday.year, monday.monthValue - 1, monday.dayOfMonth,
    ).apply {
        datePicker.minDate = today.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        setTitle("Automatic start again on")
    }.show()
}
