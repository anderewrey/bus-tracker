// SPDX-FileCopyrightText: 2026 anderewrey
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.anderewrey.bustracker.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import io.github.anderewrey.bustracker.R
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import io.github.anderewrey.bustracker.Session
import io.github.anderewrey.bustracker.data.Period
import io.github.anderewrey.bustracker.data.Run
import io.github.anderewrey.bustracker.data.alarmOn
import io.github.anderewrey.bustracker.data.periodOn
import io.github.anderewrey.bustracker.data.switchPeriod
import io.github.anderewrey.bustracker.net.noticeLabel
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private val TIME = DateTimeFormatter.ofPattern("H:mm")
private val DAY = DateTimeFormatter.ofPattern("EEE", Locale.ENGLISH)

/** The Alarm switch's state for one run, as the screens show it (DESIGN.md §5). */
data class AlarmState(val period: Period?, val on: Boolean, val status: String)

/** Read again every 30 s, so the switch moves on to the next period by itself. */
@Composable
fun rememberAlarmState(run: Run): AlarmState {
    val active by Session.active.collectAsState()
    val choices by Session.choices.collectAsState()
    val auto by Session.autoStart.collectAsState()
    val rang by Session.rang.collectAsState()
    val ends by Session.watchEnd.collectAsState()
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            now = LocalDateTime.now()
        }
    }
    val watching = run.key in active
    val period = if (watching) run.periodOn(now.toLocalDate()) else switchPeriod(run, now, choices.ended[run.key])
    val on = watching || (period != null && alarmOn(run, period, auto, choices))
    val until = ends[run.key]?.let { " until ${it.format(TIME)}" } ?: ""
    val rangAt = rang[run.key]?.takeIf { it.toLocalDate() == now.toLocalDate() }
    val status = when {
        period == null -> "No days set"
        watching && rangAt != null -> "Rang ${rangAt.format(TIME)} · watching$until"
        watching -> "Watching$until"
        else -> {
            val day = if (period.start.toLocalDate() == now.toLocalDate()) "" else "${period.start.format(DAY)} "
            "$day${period.start.format(TIME)}–${period.end.format(TIME)}"
        }
    }
    return AlarmState(period, on, status)
}

/**
 * The run card's Alarm row: the bell switch on the right and, only while it says something the
 * card needs, a short status on the left ("Watching until 8:03", "Rang 7:31 · …", "No days
 * set"). The period's times are on the run's page, not on the card (user, 2026-11-06).
 */
@Composable
fun AlarmRow(run: Run, modifier: Modifier = Modifier) {
    val s = rememberAlarmState(run)
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        val active by Session.active.collectAsState()
        if (s.period == null || run.key in active) {
            Text(s.status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        AlarmSwitch(run, s)
    }
}

/** The map header's switch: the same bell switch, no text. */
@Composable
fun AlarmSwitchCompact(run: Run) = AlarmSwitch(run, rememberAlarmState(run))

/** The Alarm switch itself: a bell in its thumb says what it is, no label. */
@Composable
private fun AlarmSwitch(run: Run, s: AlarmState) {
    val context = LocalContext.current
    Switch(
        checked = s.on,
        enabled = s.period != null,
        onCheckedChange = { Session.setAlarm(context, run, it) },
        thumbContent = {
            Icon(painterResource(R.drawable.ic_alarm), contentDescription = "Alarm", modifier = Modifier.size(SwitchDefaults.IconSize))
        },
        modifier = Modifier.padding(horizontal = 4.dp),
    )
}

/** The run's title, with the BusPlanner notice in red at its end, e.g. "B102 · Pickup 7:33 · delayed 30 minutes". */
@Composable
fun RunTitle(run: Run, title: String, style: TextStyle) {
    val notices by Session.notices.collectAsState()
    val notice = notices[run.key]
    Text(
        buildAnnotatedString {
            append(title)
            if (notice != null) {
                append(" · ")
                withStyle(SpanStyle(color = MaterialTheme.colorScheme.error)) { append(noticeLabel(notice)) }
            }
        },
        style = style,
    )
}
