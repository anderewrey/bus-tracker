// SPDX-FileCopyrightText: 2026 anderewrey
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.anderewrey.bustracker.ui

import android.os.SystemClock
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.anderewrey.bustracker.monitor.POLL_MS
import kotlinx.coroutines.delay

/**
 * The map's Refresh button. While the run is monitored, a ring empties over the 30 s until the
 * next automatic check, with the seconds left in the middle; it spins during a check. Not
 * monitored: a plain refresh symbol. A tap checks now (DESIGN.md §4).
 */
@Composable
fun RefreshRing(monitored: Boolean, nextCheck: Long?, refreshing: Boolean, onClick: () -> Unit) {
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(monitored, nextCheck) {
        while (monitored && nextCheck != null) {
            now = SystemClock.elapsedRealtime()
            delay(50)
        }
    }
    val checking = refreshing || (monitored && nextCheck == null)
    val left = if (monitored && nextCheck != null) (nextCheck - now).coerceIn(0, POLL_MS) else null
    Box(
        Modifier
            .padding(4.dp)
            .size(48.dp)
            .clip(CircleShape)
            .clickable(enabled = !checking, onClick = onClick)
            .semantics { contentDescription = "Refresh" },
        contentAlignment = Alignment.Center,
    ) {
        when {
            checking -> CircularProgressIndicator(Modifier.fillMaxSize().padding(6.dp), strokeWidth = 3.dp)
            left != null -> {
                CircularProgressIndicator(
                    progress = { left.toFloat() / POLL_MS },
                    modifier = Modifier.fillMaxSize().padding(6.dp),
                    strokeWidth = 3.dp,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
                Text(((left + 999) / 1000).toString(), style = MaterialTheme.typography.labelLarge)
            }
            else -> Text("↻", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
        }
    }
}
