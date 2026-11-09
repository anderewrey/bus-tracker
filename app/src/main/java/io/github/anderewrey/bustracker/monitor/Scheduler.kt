// SPDX-FileCopyrightText: 2026 anderewrey
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.anderewrey.bustracker.monitor

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import io.github.anderewrey.bustracker.AppLog
import io.github.anderewrey.bustracker.MainActivity
import io.github.anderewrey.bustracker.R
import io.github.anderewrey.bustracker.Session
import io.github.anderewrey.bustracker.data.Store
import io.github.anderewrey.bustracker.data.autoText
import io.github.anderewrey.bustracker.data.alarmOn
import io.github.anderewrey.bustracker.data.nextStart
import io.github.anderewrey.bustracker.data.switchPeriod
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private const val ALARM_ID = 0x5C4F
private const val EXTRA_KEYS = "keys"
private val WHEN = DateTimeFormatter.ofPattern("EEE yyyy-MM-dd H:mm", Locale.ENGLISH)

/**
 * Starts monitoring by itself at each run's monitoring-period start, on the run's days. One exact
 * alarm is set for the next start only, and set again after it fires and whenever runs change.
 * Only an exact alarm lets Android start the monitoring service while the app is in the background.
 */
object Scheduler {
    private fun alarm(context: Context, keys: List<String>): PendingIntent = PendingIntent.getBroadcast(
        context, ALARM_ID,
        Intent(context, StartAlarmReceiver::class.java).putExtra(EXTRA_KEYS, keys.toTypedArray()),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    fun reschedule(context: Context) {
        val am = context.getSystemService(AlarmManager::class.java)
        val auto = Session.settleAutoStart(context)
        val next = nextStart(Store(context).runs, LocalDateTime.now(), auto, Store(context).choices)
        if (next == null) {
            am.cancel(alarm(context, emptyList()))
            AppLog.log("no automatic start scheduled (automatic start ${autoText(auto)})")
            return
        }
        val at = next.at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val pi = alarm(context, next.keys)
        val exact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
        if (exact) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } else {
            // Without exact alarms Android may refuse to start the service; the receiver then asks the user to.
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
        AppLog.log(
            "next automatic start: ${next.keys.joinToString()} at ${next.at.format(WHEN)}" +
                (if (auto.on) "" else " (automatic start ${autoText(auto)})") +
                if (exact) "" else " (exact alarms not allowed, may be late)"
        )
    }

    fun cancel(context: Context) {
        context.getSystemService(AlarmManager::class.java).cancel(alarm(context, emptyList()))
        AppLog.log("automatic starts cancelled")
    }

    /** The next start as shown on the screens, e.g. "Thu 2026-11-05 7:03"; null when none. */
    fun nextText(context: Context, key: String): String? {
        val run = Store(context).runs.firstOrNull { it.key == key } ?: return null
        return nextStart(listOf(run), LocalDateTime.now(), Session.autoStart.value, Session.choices.value)?.at?.format(WHEN)
    }
}

/** The alarm at a period start: starts the runs that are due, then sets the next alarm. */
class StartAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val now = LocalDateTime.now()
        val runs = Store(context).runs
        val auto = Session.settleAutoStart(context)
        val choices = Store(context).choices
        for (key in intent.getStringArrayExtra(EXTRA_KEYS).orEmpty()) {
            val run = runs.firstOrNull { it.key == key }
            val period = run?.let { switchPeriod(it, now, choices.ended[key]) }
            when {
                run == null -> AppLog.log("automatic start of $key skipped: run no longer on the account")
                period == null || now !in period -> AppLog.log("automatic start of $key skipped: not in one of its periods")
                !alarmOn(run, period, auto, choices) -> AppLog.log("automatic start of $key skipped: Alarm switch off (automatic start ${autoText(auto)})")
                key in Session.active.value -> AppLog.log("automatic start of $key: already monitored")
                else -> try {
                    AppLog.log("automatic start of $key")
                    MonitorService.start(context, key)
                } catch (e: Exception) {
                    AppLog.log("automatic start of $key refused by Android: ${e.message ?: e.javaClass.simpleName}")
                    notStarted(context, key)
                }
            }
        }
        Scheduler.reschedule(context)
    }

    /** Asks the user to start it, since Android didn't let the app do it. */
    private fun notStarted(context: Context, key: String) {
        ensureChannels(context)
        val open = PendingIntent.getActivity(
            context, key.hashCode() + 4,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = Notification.Builder(context, BUS_ALERT_CHANNEL)
            .setSmallIcon(R.drawable.ic_bus)
            .setContentTitle("$key: monitoring didn't start")
            .setContentText("Tap to open the app: it starts it.")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(key.hashCode() + 4, n)
    }
}

/**
 * Sets the alarm again when Android dropped it (reboot) or the clock changed. Never starts
 * monitoring from here: Android forbids that kind of service right after boot.
 */
class RescheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        AppLog.log("automatic starts set again after ${intent.action?.substringAfterLast('.') ?: "?"}")
        Scheduler.reschedule(context)
    }
}
