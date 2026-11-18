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
import io.github.anderewrey.bustracker.monitor.streetAt
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
                answer = "Asking…"
                scope.launch {
                    val found = addressesAt(context, ll[0], ll[1], 5)
                    answer = when {
                        found == null -> "No answer (no geocoder on this phone, an error, or more than 5 s)"
                        found.isEmpty() -> "No candidate"
                        else -> found.mapIndexed { i, a ->
                            "${i + 1}. feature: ${a.featureName}\n" +
                                "   thoroughfare: ${a.thoroughfare}\n" +
                                "   subThoroughfare: ${a.subThoroughfare}\n" +
                                "   line 0: ${if (a.maxAddressLineIndex >= 0) a.getAddressLine(0) else null}"
                        }.joinToString("\n", prefix = "Shown: ${streetAt(context, ll[0], ll[1])}\n")
                    }
                }
            }) { Text("Look up") }
        }
        if (answer.isNotEmpty()) Text(answer, fontFamily = FontFamily.Monospace, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
    }
}
