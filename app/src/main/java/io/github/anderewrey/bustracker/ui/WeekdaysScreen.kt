// SPDX-FileCopyrightText: 2026 anderewrey
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.anderewrey.bustracker.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import io.github.anderewrey.bustracker.Session
import io.github.anderewrey.bustracker.data.Run
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale


/** The days of every run at once; a day some runs have and others don't shows as mixed. */
@Composable
fun AllDaysScreen(runs: List<Run>, onBack: () -> Unit) {
    val context = LocalContext.current
    DaysPage(
        title = "All runs",
        text = "Each run starts by itself at the start of its monitoring period, on these days. " +
            "A run's own page can still change its days.",
        state = { day ->
            val count = runs.count { day in it.days }
            when (count) {
                0 -> ToggleableState.Off
                runs.size -> ToggleableState.On
                else -> ToggleableState.Indeterminate
            }
        },
        // A mixed day becomes a day of every run.
        onToggle = { day -> Session.setDayForAll(context, day, on = !runs.all { day in it.days }) },
        footer = null,
        onBack = onBack,
    )
}

@Composable
private fun DaysPage(
    title: String,
    text: String,
    state: (DayOfWeek) -> ToggleableState,
    onToggle: (DayOfWeek) -> Unit,
    footer: String?,
    onBack: () -> Unit,
) {
    Column(Modifier.safeDrawingPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth()) {
            TextButton(onClick = onBack) { Text("Back") }
            Text("Automatic start", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 6.dp))
        }
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(text)
        for (day in DayOfWeek.entries) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TriStateCheckbox(state = state(day), onClick = { onToggle(day) })
                Text(day.getDisplayName(TextStyle.FULL, Locale.ENGLISH))
            }
        }
        footer?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
    }
}
