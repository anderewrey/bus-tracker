// SPDX-FileCopyrightText: 2026 anderewrey
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.anderewrey.bustracker

import android.app.Application

/** Starts the log file before any screen or the monitoring service writes to it. */
class BusTrackerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLog.init(this)
        Session.init(this)
    }
}
