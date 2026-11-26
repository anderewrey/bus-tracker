// SPDX-FileCopyrightText: 2026 anderewrey
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.anderewrey.bustracker.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** A page's top: Back on its own line, then the page's title. */
@Composable
internal fun BackHeader(onBack: () -> Unit, title: String) {
    Row(Modifier.fillMaxWidth()) {
        TextButton(onClick = onBack) { Text("Back") }
    }
    Text(title, style = MaterialTheme.typography.headlineSmall)
}

/** A section's title on a page, under a divider. */
@Composable
internal fun Section(title: String) {
    HorizontalDivider(Modifier.padding(top = 16.dp, bottom = 8.dp))
    Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
}
