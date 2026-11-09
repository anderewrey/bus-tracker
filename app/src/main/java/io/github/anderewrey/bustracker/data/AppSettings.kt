// SPDX-FileCopyrightText: 2026 anderewrey
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.anderewrey.bustracker.data

import android.content.Context

/**
 * The global settings (DESIGN.md §5.2). Kept apart from the login and the runs, so logging out
 * and in again keeps them, like the phone keeps the notification channels.
 */
class AppSettings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** One notification when monitoring starts while BusPlanner has no vehicle for the run. */
    var noVehicleWarning: Boolean
        get() = prefs.getBoolean("noVehicleWarning", true)
        set(value) = prefs.edit().putBoolean("noVehicleWarning", value).apply()

    /** BusPlanner's late or cancelled notices for a monitored bus ring. */
    var busNotices: Boolean
        get() = prefs.getBoolean("busNotices", true)
        set(value) = prefs.edit().putBoolean("busNotices", value).apply()

    /** The ringing alerts play as alarms, which Do Not Disturb lets through. */
    var quietOverride: Boolean
        get() = prefs.getBoolean("quietOverride", false)
        set(value) = prefs.edit().putBoolean("quietOverride", value).apply()

    /** The version of a ringing channel's id; each sound or quiet-mode change makes a new channel. */
    fun channelVersion(base: String): Int = prefs.getInt("channel_$base", 0)

    fun setChannelVersion(base: String, version: Int) {
        prefs.edit().putInt("channel_$base", version).apply()
    }
}
