// SPDX-FileCopyrightText: 2026 anderewrey
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.anderewrey.bustracker.monitor

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.provider.Settings
import io.github.anderewrey.bustracker.AppLog
import io.github.anderewrey.bustracker.data.AppSettings

const val TRACKING_CHANNEL = "tracking"
/** Schedule changes and the "no vehicle" warning: they sound once. */
const val BUS_ALERT_CHANNEL = "bus_alerts"

/**
 * The alerts that ring until acknowledged. Android fixes a channel's sound once it exists, so a
 * new sound or quiet-mode setting makes a new channel with a versioned id (DESIGN.md §7.1); the
 * old one is deleted. Version 0 is the id earlier versions created.
 */
enum class Ringing(val base: String, val title: String, val description: String) {
    MAIN_ZONE("main_zone", "Main zone", "Rings until acknowledged when the bus enters the main zone"),
    NOTICE("bus_notices", "BusPlanner notices", "Rings until acknowledged when BusPlanner reports a late or cancelled bus"),
}

fun channelId(context: Context, type: Ringing): String {
    val v = AppSettings(context).channelVersion(type.base)
    return if (v == 0) type.base else "${type.base}_v$v"
}

/** What the user can change about a ringing channel from the app. */
data class RingSettings(val sound: Uri?, val vibrate: Boolean)

fun ensureChannels(context: Context) {
    val nm = context.getSystemService(NotificationManager::class.java)
    val tracking = NotificationChannel(TRACKING_CHANNEL, "Tracking", NotificationManager.IMPORTANCE_LOW)
    tracking.description = "Live bus status while a run is monitored"
    tracking.setSound(null, null)
    nm.createNotificationChannel(tracking)
    val alerts = NotificationChannel(BUS_ALERT_CHANNEL, "Bus alerts", NotificationManager.IMPORTANCE_DEFAULT)
    alerts.description = "BusPlanner reports no vehicle, or the bus schedule changed"
    nm.createNotificationChannel(alerts)
    for (type in Ringing.entries) {
        val id = channelId(context, type)
        if (nm.getNotificationChannel(id) == null) {
            nm.createNotificationChannel(ringingChannel(context, type, id, RingSettings(null, true), NotificationManager.IMPORTANCE_HIGH))
        }
    }
}

private fun ringingChannel(context: Context, type: Ringing, id: String, s: RingSettings, importance: Int): NotificationChannel {
    val quiet = AppSettings(context).quietOverride
    val ch = NotificationChannel(id, type.title, importance)
    ch.description = type.description
    ch.enableVibration(s.vibrate)
    // As an alarm, the sound plays at the alarm volume and Do Not Disturb lets it through.
    val usage = if (quiet) AudioAttributes.USAGE_ALARM else AudioAttributes.USAGE_NOTIFICATION
    ch.setSound(
        s.sound ?: Settings.System.DEFAULT_NOTIFICATION_URI,
        AudioAttributes.Builder().setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).setUsage(usage).build(),
    )
    ch.setBypassDnd(quiet)
    return ch
}

/** The channel's sound and vibration as they are now, including changes made in Android's settings. */
fun ringSettings(context: Context, type: Ringing): RingSettings {
    ensureChannels(context)
    val ch = context.getSystemService(NotificationManager::class.java).getNotificationChannel(channelId(context, type))
    return RingSettings(ch?.sound, ch?.shouldVibrate() ?: true)
}

/** The sound's name as the phone shows it, e.g. "Default (Spaceline)". */
fun soundTitle(context: Context, sound: Uri?): String =
    if (sound == null) "None" else runCatching { RingtoneManager.getRingtone(context, sound)?.getTitle(context) }.getOrNull() ?: "Unknown"

/**
 * Makes the channel again with [change] applied, keeping what the user set in Android's settings
 * (sound, vibration, importance) for everything else.
 */
fun remakeChannel(context: Context, type: Ringing, change: (RingSettings) -> RingSettings = { it }) {
    ensureChannels(context)
    val nm = context.getSystemService(NotificationManager::class.java)
    val settings = AppSettings(context)
    val oldId = channelId(context, type)
    val old = nm.getNotificationChannel(oldId)
    val next = change(RingSettings(old?.sound, old?.shouldVibrate() ?: true))
    // A sound from the phone's files must be readable by the system UI that plays it.
    next.sound?.takeIf { it.scheme == "content" && it.authority != "settings" }?.let {
        runCatching { context.grantUriPermission("com.android.systemui", it, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }
    val importance = old?.importance?.takeIf { it != NotificationManager.IMPORTANCE_UNSPECIFIED } ?: NotificationManager.IMPORTANCE_HIGH
    val version = settings.channelVersion(type.base) + 1
    settings.setChannelVersion(type.base, version)
    val id = channelId(context, type)
    nm.createNotificationChannel(ringingChannel(context, type, id, next, importance))
    nm.deleteNotificationChannel(oldId)
    AppLog.log("channel $id: sound ${next.sound ?: "none"}, vibration ${if (next.vibrate) "on" else "off"}, quiet mode override ${if (settings.quietOverride) "on" else "off"}")
}

/** Turns the quiet mode override on or off for every ringing alert. */
fun setQuietOverride(context: Context, on: Boolean) {
    AppSettings(context).quietOverride = on
    for (type in Ringing.entries) remakeChannel(context, type)
}
