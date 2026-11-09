// SPDX-FileCopyrightText: 2026 anderewrey
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.anderewrey.bustracker.ui

import android.app.Activity
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.IntentCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import io.github.anderewrey.bustracker.data.AppSettings
import io.github.anderewrey.bustracker.data.Store
import io.github.anderewrey.bustracker.monitor.Ringing
import io.github.anderewrey.bustracker.monitor.channelId
import io.github.anderewrey.bustracker.monitor.remakeChannel
import io.github.anderewrey.bustracker.monitor.ringSettings
import io.github.anderewrey.bustracker.monitor.setQuietOverride
import io.github.anderewrey.bustracker.monitor.soundTitle
import io.github.anderewrey.bustracker.monitor.testNotice
import io.github.anderewrey.bustracker.monitor.testRing

/** Settings that apply to all runs: sound, bus alerts and the account. */
@Composable
fun SettingsScreen(onBack: () -> Unit, onLogout: () -> Unit) {
    val context = LocalContext.current
    val settings = remember { AppSettings(context) }
    // Bumped after each change, and when coming back from Android's settings, to read everything again.
    var version by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        version++
        onPauseOrDispose {}
    }

    Column(
        Modifier.safeDrawingPadding().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(Modifier.fillMaxWidth()) {
            TextButton(onClick = onBack) { Text("Back") }
        }
        Text("Settings", style = MaterialTheme.typography.headlineSmall)

        Section("Main zone alarm")
        RingingRows(Ringing.MAIN_ZONE, version, onChanged = { version++ }, onTest = { testRing(context) })

        Section("Bus alerts")
        val noVehicle = remember(version) { settings.noVehicleWarning }
        val notices = remember(version) { settings.busNotices }
        val quiet = remember(version) { settings.quietOverride }
        SwitchRow("\"No vehicle\" warning at monitoring start", noVehicle) {
            settings.noVehicleWarning = it
            version++
        }
        SwitchRow("BusPlanner late or cancelled notices", notices) {
            settings.busNotices = it
            version++
        }
        Text(
            "Checked with every bus position while a run is monitored. A new notice about the run's bus rings until you silence it.",
            style = MaterialTheme.typography.bodySmall,
        )
        RingingRows(Ringing.NOTICE, version, onChanged = { version++ }, onTest = { testNotice(context) })

        Section("Quiet mode")
        SwitchRow("Quiet mode override", quiet) {
            setQuietOverride(context, it)
            version++
        }
        Text(
            "The alarm and the BusPlanner notices ring as alarms: Do Not Disturb lets them through, at the alarm volume.",
            style = MaterialTheme.typography.bodySmall,
        )

        Section("Account")
        val store = Store(context)
        Text(store.email ?: "")
        Text(store.portal?.removePrefix("https://") ?: "", style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = onLogout) { Text("Log out") }
    }
}

/** Sound and vibration of one ringing alert, a test, and Android's own settings for it. */
@Composable
private fun RingingRows(type: Ringing, version: Int, onChanged: () -> Unit, onTest: () -> Unit) {
    val context = LocalContext.current
    val current = remember(version) { ringSettings(context, type) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val picked: Uri? = result.data?.let { IntentCompat.getParcelableExtra(it, RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java) }
        remakeChannel(context, type) { it.copy(sound = picked) }
        onChanged()
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Sound")
            Text(soundTitle(context, current.sound), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        OutlinedButton(onClick = {
            picker.launch(
                Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
                    .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALL)
                    .putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "${type.title} sound")
                    .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                    .putExtra(RingtoneManager.EXTRA_RINGTONE_DEFAULT_URI, Settings.System.DEFAULT_NOTIFICATION_URI)
                    .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                    .putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, current.sound)
            )
        }) { Text("Change") }
    }
    // Back to the phone's own notification sound, the one that follows the phone's settings.
    if (current.sound != Settings.System.DEFAULT_NOTIFICATION_URI) {
        TextButton(onClick = {
            remakeChannel(context, type) { it.copy(sound = Settings.System.DEFAULT_NOTIFICATION_URI) }
            onChanged()
        }) { Text("Use default") }
    }
    SwitchRow("Vibration", current.vibrate) { on ->
        remakeChannel(context, type) { it.copy(vibrate = on) }
        onChanged()
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onTest) { Text("Test") }
        OutlinedButton(onClick = {
            context.startActivity(
                Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    .putExtra(Settings.EXTRA_CHANNEL_ID, channelId(context, type))
            )
        }) { Text("Android settings") }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
