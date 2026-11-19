// SPDX-FileCopyrightText: 2026 anderewrey
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.anderewrey.bustracker.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.anderewrey.bustracker.Session
import io.github.anderewrey.bustracker.monitor.addressesAt
import io.github.anderewrey.bustracker.monitor.distanceM
import io.github.anderewrey.bustracker.monitor.GEOCODER_CANDIDATES
import io.github.anderewrey.bustracker.monitor.GEOCODER_TIMEOUT_MS
import io.github.anderewrey.bustracker.monitor.STREET_UNKNOWN
import io.github.anderewrey.bustracker.monitor.streetFrom
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Debug screen: what the phone's geocoder answers at a point, all its candidates, to see why a
 * status line names a bus stop instead of a street (DESIGN.md §7.2). On the screen only, never
 * in the Log.
 */
@Composable
fun GeocoderTest(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    var point by remember { mutableStateOf("") }
    var answer by remember { mutableStateOf("") }
    var lookup by remember { mutableStateOf<Job?>(null) }
    Column(modifier) {
        OutlinedButton(onClick = {
            open = !open
            // The last bus position of any run, as a starting point.
            if (open && point.isEmpty()) point = Session.status.value.values
                .firstNotNullOfOrNull { s -> s.reading?.takeIf { it.busLat != null && it.busLon != null } }
                ?.let { String.format(Locale.US, "%.5f,%.5f", it.busLat, it.busLon) } ?: ""
        }) { Text("Test geocoder") }
        if (!open) return@Column
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                point, { point = it }, Modifier.weight(1f), singleLine = true,
                label = { Text("Latitude,longitude") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
            )
            TextButton(onClick = {
                val ll = point.split(',').mapNotNull { it.trim().toDoubleOrNull() }
                if (ll.size != 2) {
                    answer = "Type latitude,longitude, e.g. 44.65292,-63.64984"
                    return@TextButton
                }
                answer = "Finding…"
                // Off the main thread (addressesAt never blocks it); a new Look up replaces the last one.
                lookup?.cancel()
                lookup = scope.launch {
                    val found = addressesAt(context, ll[0], ll[1], GEOCODER_CANDIDATES)
                    answer = when {
                        found == null -> "No answer (no geocoder on this phone, an error, or more than ${GEOCODER_TIMEOUT_MS / 1000} s)"
                        found.isEmpty() -> "No candidate"
                        else -> found.mapIndexed { i, a ->
                            "${i + 1}. feature: ${a.featureName}\n" +
                                "   thoroughfare: ${a.thoroughfare}\n" +
                                "   subThoroughfare: ${a.subThoroughfare}\n" +
                                "   line 0: ${if (a.maxAddressLineIndex >= 0) a.getAddressLine(0) else null}\n" +
                                "   at: " + if (a.hasLatitude() && a.hasLongitude()) String.format(
                                    Locale.US, "%.5f,%.5f, %.0f m", a.latitude, a.longitude, distanceM(ll[0], ll[1], a.latitude, a.longitude),
                                ) else "unknown"
                        }.joinToString("\n", prefix = "Shown: ${streetFrom(found, ll[0], ll[1]) ?: STREET_UNKNOWN}\n")
                    }
                }
            }) { Text("Look up") }
        }
        if (answer.isNotEmpty()) Text(answer, fontFamily = FontFamily.Monospace, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
    }
}
