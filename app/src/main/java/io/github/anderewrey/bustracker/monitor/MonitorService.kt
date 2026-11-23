// SPDX-FileCopyrightText: 2026 anderewrey
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.anderewrey.bustracker.monitor

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import io.github.anderewrey.bustracker.AppLog
import io.github.anderewrey.bustracker.MainActivity
import io.github.anderewrey.bustracker.R
import io.github.anderewrey.bustracker.RunStatus
import io.github.anderewrey.bustracker.Session
import io.github.anderewrey.bustracker.data.AppSettings
import io.github.anderewrey.bustracker.data.Run
import io.github.anderewrey.bustracker.data.RunChange
import io.github.anderewrey.bustracker.data.Store
import java.time.LocalDate
import io.github.anderewrey.bustracker.data.periodOn
import io.github.anderewrey.bustracker.net.BusAlert
import io.github.anderewrey.bustracker.net.LoginFailed
import io.github.anderewrey.bustracker.net.alertsFor
import io.github.anderewrey.bustracker.net.delayMinutes
import io.github.anderewrey.bustracker.net.fetchBusAlerts
import io.github.anderewrey.bustracker.net.latestFor
import io.github.anderewrey.bustracker.net.sameHalfDay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDateTime
import java.time.LocalTime

const val EXTRA_RUN = "run"
/** The notification Silence stops; the run's zone alert when absent. */
const val EXTRA_NOTIFICATION = "notification"
/** On the intent that opens the app from a ringing main-zone alarm: opening it silences it. */
const val EXTRA_SILENCE = "silence"
const val EXTRA_SCREEN = "screen"
const val SCREEN_CHANGES = "changes"
private const val CHANGES_ID = 0x5C4E
/**
 * How long the main-zone alarm takes the place of the tracking line: then the line comes back
 * and any sound left stops (DESIGN.md §7.2). A volume key stops the sound sooner.
 */
const val RING_MS = 60_000L
/** Time between two checks of a monitored run; the map's countdown ring shows it. */
const val POLL_MS = 30_000L
private const val MAX_WAKE_MS = 3 * 60 * 60 * 1000L

private fun alertId(key: String) = key.hashCode() + 1

/** The run's BusPlanner notice: one per run, so a newer notice replaces the older one. */
private fun noticeId(key: String) = key.hashCode() + 3

/** The run's heads-up notification: apart from the run's own, so the alarm can't replace it. */
private fun headsUpId(key: String) = key.hashCode() + 5

/** The run's own notification: the tracking line while watching, the main-zone alarm while it rings. */
private fun runNotificationId(key: String) = key.hashCode()

/** Asks the service to act on [key]'s notification (Silence, or the notification cleared by the user). */
private fun serviceIntent(context: Context, action: String, key: String, id: Int): PendingIntent =
    PendingIntent.getService(
        context, id,
        Intent(context, MonitorService::class.java).setAction(action).putExtra(EXTRA_RUN, key).putExtra(EXTRA_NOTIFICATION, id),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

/**
 * A ringing alert: rings like a ringtone (insistent) until it is opened, silenced, swiped away or
 * the notification shade is pulled down. None of these stops monitoring.
 * [inPlace]: the run's own notification turned into the alarm (main zone): opening it silences it
 * too, and clearing it brings the tracking line back instead of removing it.
 */
private fun ringingAlert(
    context: Context, type: Ringing, key: String, id: Int, title: String, text: String,
    long: String? = null, inPlace: Boolean = false,
): Notification {
    val open = PendingIntent.getActivity(
        context, id,
        Intent(context, MainActivity::class.java).putExtra(EXTRA_RUN, key).putExtra(EXTRA_SILENCE, inPlace)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    val silence = serviceIntent(context, MonitorService.ACTION_SILENCE, key, id)
    val b = Notification.Builder(context, channelId(context, type))
        .setSmallIcon(R.drawable.ic_bus)
        .setContentTitle(title)
        .setContentText(text)
        .setContentIntent(open)
        .setAutoCancel(!inPlace)
        .addAction(Notification.Action.Builder(null, "Open map", open).build())
        .addAction(Notification.Action.Builder(null, "Silence", silence).build())
    if (inPlace) b.setDeleteIntent(serviceIntent(context, MonitorService.ACTION_DISMISSED, key, id))
    if (long != null) b.setStyle(Notification.BigTextStyle().bigText(long))
    // Do Not Disturb lets alarms through; the channel's sound is an alarm too (Channels.kt).
    if (AppSettings(context).quietOverride) b.setCategory(Notification.CATEGORY_ALARM)
    val n = b.build()
    n.flags = n.flags or Notification.FLAG_INSISTENT
    return n
}

/** The main-zone alarm, posted in place of the run's tracking notification (one notification per run). */
fun zoneAlert(context: Context, key: String, title: String, text: String): Notification =
    ringingAlert(context, Ringing.MAIN_ZONE, key, runNotificationId(key), title, text, inPlace = true)

/** A BusPlanner notice about a monitored bus: the action, then the operator's comment in full. */
fun noticeAlert(context: Context, key: String, alert: BusAlert): Notification {
    val title = "${alert.bus}: ${alert.action}"
    val details = listOf(alert.routeRun, alert.created.substringAfter(' ').let { "posted $it" }).joinToString(" · ")
    return ringingAlert(context, Ringing.NOTICE, key, noticeId(key), title, alert.comment.ifEmpty { details }, "${alert.comment}\n\n$details".trim())
}

/**
 * The heads-up zone's notification (DESIGN.md §6.1): sounds once (not insistent), on its own
 * channel; opening it opens the run's map. It stays until swiped away.
 */
fun headsUpAlert(context: Context, key: String, title: String, text: String): Notification {
    val id = headsUpId(key)
    val open = PendingIntent.getActivity(
        context, id,
        Intent(context, MainActivity::class.java).putExtra(EXTRA_RUN, key)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    val b = Notification.Builder(context, channelId(context, Ringing.HEADS_UP))
        .setSmallIcon(R.drawable.ic_bus)
        .setContentTitle(title)
        .setContentText(text)
        .setContentIntent(open)
        .setAutoCancel(true)
    if (AppSettings(context).quietOverride) b.setCategory(Notification.CATEGORY_ALARM)
    return b.build()
}

/** Posts a test heads-up, so its sound can be checked too. */
fun testHeadsUp(context: Context) {
    ensureChannels(context)
    context.getSystemService(NotificationManager::class.java)
        .notify(headsUpId("test"), headsUpAlert(context, "test", "Test: the bus entered the heads-up zone", "This is how the heads-up sounds"))
    AppLog.log("test heads-up posted")
}

/** Posts a test alert, so the sound can be checked before a real trip. */
fun testRing(context: Context) {
    ensureChannels(context)
    val test = ringingAlert(context, Ringing.MAIN_ZONE, "test", alertId("test"), "Test: the bus entered the main zone", "This is how the main-zone alert rings")
    context.getSystemService(NotificationManager::class.java).notify(alertId("test"), test)
    AppLog.log("test alert posted")
}

/** Posts a test BusPlanner notice, so its sound can be checked too. */
fun testNotice(context: Context) {
    ensureChannels(context)
    val alert = BusAlert("B101 (test)", "Delayed 15 minutes from original time", "This is how a BusPlanner notice rings.", "", "", 0L, "", "")
    context.getSystemService(NotificationManager::class.java).notify(noticeId("test"), noticeAlert(context, "test", alert))
    AppLog.log("test notice posted")
}

/** Survives the service: turning the Alarm switch off right after the start must not cancel the rescan. */
private val rescanScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

@Volatile
private var rescanning = false

/**
 * The daily rescan, along with the first monitoring period of the day. It runs apart from the
 * monitoring, so stopping it (Alarm switch off) doesn't cancel the rescan (seen 2026-11-11).
 * A failure is only logged: monitoring goes on with the saved runs, and the next period tries again.
 */
private fun rescanIfDue(context: Context) {
    if (rescanning || Store(context).lastScan == LocalDate.now()) return
    rescanning = true
    rescanScope.launch {
        try {
            val found = Session.scan(context, "daily").changes
            if (found.isNotEmpty()) notifyChanges(context, found)
        } catch (e: Exception) {
            AppLog.log("daily rescan failed: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            rescanning = false
        }
    }
}

private fun notifyChanges(context: Context, found: List<RunChange>) {
    val open = PendingIntent.getActivity(
        context, CHANGES_ID,
        Intent(context, MainActivity::class.java).putExtra(EXTRA_SCREEN, SCREEN_CHANGES)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    val text = if (found.size == 1) "${found[0].title}: see what changed" else "${found.size} runs changed: ${found.joinToString { it.title }}"
    val n = Notification.Builder(context, BUS_ALERT_CHANNEL)
        .setSmallIcon(R.drawable.ic_bus)
        .setContentTitle("Bus schedule changed")
        .setContentText(text)
        .setStyle(Notification.BigTextStyle().bigText(text))
        .setContentIntent(open)
        .setAutoCancel(true)
        .build()
    context.getSystemService(NotificationManager::class.java).notify(CHANGES_ID, n)
    AppLog.log("schedule change notification posted")
}

/** The run's heads-up and alarm marks, kept by Session for the day. */
private class SessionMarks(private val context: Context, private val key: String) : PeriodMarks {
    override val rang get() = Session.hasRung(key)
    override val headsUp get() = Session.hasHeadsUp(key)
    override fun markRang() = Session.markRang(context, key)
    override fun markHeadsUp() = Session.markHeadsUp(context, key)
}

/** Polls BusPlanner for the monitored runs and keeps one notification per run. */
class MonitorService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    /** Each watched run's decisions for its period (Watcher.kt): zones, end of watching, the period's end. */
    private val watchers = LinkedHashMap<String, Watcher>()
    private var wakeLock: PowerManager.WakeLock? = null
    private var loop: Job? = null
    private var foregroundKey: String? = null
    /**
     * Runs whose notification is the main-zone alarm, with when it rang (elapsedRealtime). The
     * checks don't update it (that would stop the sound) for [RING_MS]; then it goes back to the
     * tracking line by itself, as the app can't tell when the sound was stopped with a volume key
     * or by pulling the shade down (seen 2026-11-18).
     */
    private val ringing = HashMap<String, Long>()
    /** Runs just started: their first check tells whether BusPlanner has a vehicle at all. */
    private val firstCheck = HashSet<String>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannels(this)
        // A street found after its check: the tracking line shows it at once, in silence. A
        // ringing alarm is left alone (showStatus), as an update would stop its sound.
        scope.launch {
            Session.streetFound.collect { key ->
                if (key !in watchers) return@collect
                val run = Store(this@MonitorService).runs.firstOrNull { it.key == key } ?: return@collect
                Session.status.value[key]?.let { showStatus(run, it) }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val key = intent?.getStringExtra(EXTRA_RUN)
        val run = key?.let { k -> Store(this).runs.firstOrNull { it.key == k } }
        when (intent?.action) {
            ACTION_START -> if (run != null) start(run)
            // From the Alarm switch (Session.setAlarm), which already saved the choice.
            ACTION_STOP -> if (key != null) {
                // Turning the switch off answers a ringing alarm too.
                ringing.remove(key)
                stop(key, "Alarm switch turned off", byUser = true)
            }
            // Turn off on the tracking notification: same as the switch.
            ACTION_STOP_WATCHING -> if (key != null) {
                if (run != null) Session.setAlarm(this, run, false) else stop(key, "turned off from the notification", byUser = true)
            }
            ACTION_SILENCE -> if (key != null) {
                val id = intent.getIntExtra(EXTRA_NOTIFICATION, runNotificationId(key))
                if (id == runNotificationId(key)) backToTracking(key, run) else getSystemService(NotificationManager::class.java).cancel(id)
                AppLog.log("$key: alarm silenced, monitoring goes on")
            }
            // The run's notification cleared by the user (swipe, Clear all): watching goes on and it
            // comes back; if it was ringing, clearing it answers the alarm.
            ACTION_DISMISSED -> if (key != null) {
                if (ringing.contains(key)) AppLog.log("$key: alarm cleared, monitoring goes on")
                backToTracking(key, run)
            }
        }
        if (watchers.isEmpty()) stopSelf()
        return START_NOT_STICKY
    }

    /**
     * The run's notification goes back to the silent tracking line, which stops the alarm's
     * sound. When the run is no longer watched (the alarm was left behind at the end of
     * monitoring), it is removed.
     */
    private fun backToTracking(key: String, run: Run?) {
        ringing.remove(key)
        val nm = getSystemService(NotificationManager::class.java)
        if (key in watchers && run != null) {
            nm.notify(runNotificationId(key), notification(run, Session.status.value[key] ?: RunStatus(null, "", null)))
        } else {
            nm.cancel(runNotificationId(key))
        }
    }

    /** Shows the run's latest status, unless its notification is the alarm that just rang. */
    private fun showStatus(run: Run, status: RunStatus) {
        val rang = ringing[run.key]
        if (rang != null) {
            if (SystemClock.elapsedRealtime() - rang < RING_MS) return
            AppLog.log("${run.key}: alarm rang ${RING_MS / 1000} s, back to the tracking line")
            ringing.remove(run.key)
        }
        getSystemService(NotificationManager::class.java).notify(runNotificationId(run.key), notification(run, status))
    }

    private fun start(run: Run) {
        val now = LocalDateTime.now()
        val period = run.periodOn(now.toLocalDate())
        if (period == null || now !in period) {
            // Only the Alarm switch, the automatic start and the app's catch-up start a run, all
            // inside its period. Android still wants startForeground after startForegroundService.
            AppLog.log("${run.key} not started: outside its monitoring period")
            val shown = foregroundKey?.let { k -> Store(this).runs.firstOrNull { it.key == k } }
            if (shown != null) {
                promote(shown.key, notification(shown, Session.status.value[shown.key] ?: RunStatus(null, "", null)))
            } else {
                promote(run.key, notification(run, RunStatus(null, "", null)))
                stopForeground(STOP_FOREGROUND_REMOVE)
                foregroundKey = null
                stopSelf()
            }
            return
        }
        // A new period starts with a new login: the session left from earlier may be gone on the server.
        if (watchers.isEmpty()) Session.renewLogin("monitoring period start")
        // Stops at the period's end, or later after a BusPlanner delay notice (checkNotices).
        val watcher = Watcher(run, Session.zones.value, Session.schools.value, period, SessionMarks(this, run.key))
        watchers[run.key] = watcher
        Session.setWatchEnd(run.key, period.end)
        ringing.remove(run.key)
        firstCheck += run.key
        applyEvents(run, watcher.started)
        val status = Session.status.value[run.key] ?: RunStatus(null, "Starting…", null)
        promote(run.key, notification(run, status))
        Session.setActive(watchers.keys.toSet())
        AppLog.log("monitoring ${run.key} started, until ${watcher.until.toLocalTime().withNano(0)}")
        acquireWakeLock()
        if (loop?.isActive != true) loop = scope.launch { poll() }
        rescanIfDue(applicationContext)
    }

    /**
     * Ends watching a run. [byUser]: the Alarm switch was turned off (Session.setAlarm saved it);
     * otherwise it ended by itself and the switch moves on to the run's next period.
     */
    private fun stop(key: String, reason: String, byUser: Boolean = false) {
        val watcher = watchers.remove(key)
        if (watcher != null) {
            AppLog.log("monitoring $key stopped: $reason")
            if (!byUser) Session.markEnded(this, key, watcher.periodStart)
        }
        Session.setWatchEnd(key, null)
        // The notice stays on the run's card for the rest of the day (user, 2026-11-21).
        Session.setActive(watchers.keys.toSet())
        // An alarm that just rang stays until answered, even once watching has ended (a dropoff run
        // can end at the next check after the bus entered the zone). An older one goes away in
        // silence: keeping it would make Android ring it again (seen 2026-11-18 at 7:55).
        val rang = ringing.remove(key)
        val keepAlarm = rang != null && SystemClock.elapsedRealtime() - rang < RING_MS
        if (keepAlarm) AppLog.log("$key: alarm just rang, left until answered")
        if (foregroundKey == key) {
            val next = watchers.keys.firstOrNull()
            val run = next?.let { k -> Store(this).runs.firstOrNull { it.key == k } }
            if (run != null) {
                promote(run.key, notification(run, Session.status.value[run.key] ?: RunStatus(null, "", null)))
            } else {
                stopForeground(if (keepAlarm) STOP_FOREGROUND_DETACH else STOP_FOREGROUND_REMOVE)
                foregroundKey = null
            }
        }
        if (!keepAlarm) getSystemService(NotificationManager::class.java).cancel(runNotificationId(key))
        if (watchers.isEmpty()) {
            loop?.cancel()
            releaseWakeLock()
            stopSelf()
        }
    }

    /** Keeps the processor awake while a run is monitored, so the 30 s polling goes on with the screen off. */
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BusTracker:monitoring")
            .apply { acquire(MAX_WAKE_MS) }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun promote(key: String, n: Notification) {
        startForeground(runNotificationId(key), n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        foregroundKey = key
    }

    private suspend fun poll() {
        try {
            pollRounds()
        } finally {
            Session.setNextCheck(null)
        }
    }

    private suspend fun pollRounds() {
        var manual = false
        while (scope.isActive && watchers.isNotEmpty()) {
            Session.setNextCheck(null)
            val now = LocalDateTime.now()
            for (key in watchers.keys.toList()) {
                val run = Store(this).runs.firstOrNull { it.key == key }
                if (run == null) {
                    stop(key, "run no longer on the account after a rescan, see Changes")
                    continue
                }
                // First, so a period lengthened on the run's page doesn't end at its old end.
                applyEvents(run, watchers.getValue(key).beforeCheck(run, Session.zones.value, Session.schools.value, now))
                if (key !in watchers) continue
                val status = read(run, manual)
                watchers[key]?.let { applyEvents(run, it.check(run, Session.zones.value, status.reading), status) }
                if (key in watchers) {
                    showStatus(run, status)
                    if (firstCheck.remove(key)) warnIfNoVehicle(run, status)
                }
            }
            checkNotices()
            if (watchers.isEmpty()) break
            // A tap on the map's Refresh during the round was for this round: drop it.
            while (Session.checkNowRequests.tryReceive().isSuccess) Unit
            Session.setNextCheck(SystemClock.elapsedRealtime() + POLL_MS)
            // The map's Refresh starts the next round now, and the 30 s from there.
            manual = withTimeoutOrNull(POLL_MS) { Session.checkNowRequests.receive() } != null
        }
    }

    /**
     * Applies what the run's [Watcher] decided, in order: the Log line, then the sound, the
     * notification or the end of watching. [status]: the check the events come from, for the
     * notification's text.
     */
    private fun applyEvents(run: Run, events: List<WatchEvent>, status: RunStatus? = null) {
        for (event in events) {
            if (run.key !in watchers) return
            event.line(run.key)?.let { AppLog.log(it) }
            val bus = status?.reading?.runLabel?.substringBefore(' ') ?: run.bus
            when (event) {
                is WatchEvent.PeriodChanged -> Session.setWatchEnd(run.key, event.end)
                is WatchEvent.Delayed -> Session.setWatchEnd(run.key, event.end)
                is WatchEvent.PeriodOver -> stop(run.key, "monitoring period ended")
                // Beside the run's own notification: the alarm can't replace it.
                is WatchEvent.HeadsUp -> getSystemService(NotificationManager::class.java)
                    .notify(headsUpId(run.key), headsUpAlert(this, run.key, "$bus entered ${event.zone.name}", status?.line ?: ""))
                // In place of the tracking notification: one notification per run, not two. The
                // alarm rings until answered, for [RING_MS] at most, or until the bus leaves the zone.
                is WatchEvent.Ring -> {
                    ringing[run.key] = SystemClock.elapsedRealtime()
                    getSystemService(NotificationManager::class.java)
                        .notify(runNotificationId(run.key), zoneAlert(this, run.key, "$bus entered ${event.zone.name}", status?.line ?: ""))
                }
                // The alarm was about the bus being in the zone: it goes away with it (user, 2026-11-18).
                is WatchEvent.LeftMainZone -> if (run.key in ringing) {
                    AppLog.log("${run.key}: alarm removed, the bus left the zone")
                    backToTracking(run.key, run)
                }
                is WatchEvent.End -> {
                    if (event.parkedAt != null && event.spot != null) Session.markParked(this, event.parkedAt.name, event.spot)
                    stop(run.key, event.reason)
                }
                is WatchEvent.Zones, is WatchEvent.EndRuleSet, is WatchEvent.HeadsUpAlreadySounded,
                is WatchEvent.BlockedBeforeHeadsUp, is WatchEvent.AlreadyRang -> Unit
            }
        }
    }

    /** The ids of the last answer from the alerts page, to log it only when it changes. */
    private var lastNotices: Set<String>? = null

    /**
     * BusPlanner's transportation alerts, once per round of checks: each new notice about a
     * monitored bus rings once. A failure is only logged; monitoring goes on.
     */
    private suspend fun checkNotices() {
        val runs = watchers.keys.mapNotNull { k -> Store(this).runs.firstOrNull { it.key == k } }
        if (runs.isEmpty()) return
        val portal = Store(this).portal ?: return
        val all = try {
            withContext(Dispatchers.IO) { fetchBusAlerts(portal) }
        } catch (e: Exception) {
            AppLog.log("bus alerts: error ${e.message ?: e.javaClass.simpleName}")
            return
        }
        val ids = all.map { it.id }.toSet()
        if (ids != lastNotices) {
            lastNotices = ids
            AppLog.log("bus alerts: ${all.size} today" + all.joinToString("") { "\n  ${it.routeRun} · ${it.created} · ${it.action} · ${it.comment}" })
        }
        val store = Store(this)
        val seen = store.seenNotices
        val on = AppSettings(this).busNotices
        for (run in runs) {
            // Only the bus's newest notice counts; older ones were replaced by it and never ring.
            val newest = latestFor(all, run.bus)
            // A morning notice still listed in the afternoon isn't about the afternoon run.
            val latest = newest?.takeIf { sameHalfDay(it, run.planned) }
            if (newest != null && latest == null && newest.id !in seen) {
                AppLog.log("${run.key}: BusPlanner notice \"${newest.action}\" posted ${newest.created} is about the other half-day, ignored")
            }
            Session.setNotice(run.key, latest)
            if (latest == null) continue
            applyDelay(run, latest)
            alertsFor(all, run.bus).filter { it.id !in seen && it.id != latest.id }.forEach {
                AppLog.log("${run.key}: BusPlanner notice \"${it.action}\" posted ${it.created} replaced by the one posted ${latest.created}, not rung")
            }
            if (latest.id in seen) continue
            if (!on) {
                AppLog.log("${run.key}: BusPlanner notice \"${latest.action}\" not rung (turned off in Settings)")
                continue
            }
            AppLog.log("${run.key}: BusPlanner notice \"${latest.action}\" posted ${latest.created}, ringing")
            // One notification per run: a newer notice takes the older one's place in the shade.
            getSystemService(NotificationManager::class.java).notify(noticeId(run.key), noticeAlert(this, run.key, latest))
        }
        // Only what the page still lists: the list starts empty again each day.
        store.seenNotices = seen.intersect(ids) + runs.flatMap { alertsFor(all, it.bus) }.map { it.id }
    }

    /**
     * A "Delayed N minutes" notice moves the end of watching to the period's planned end + N
     * (DESIGN.md §7.3). The newest notice decides, so 15 then 30 gives +30.
     */
    private fun applyDelay(run: Run, alert: BusAlert) {
        val minutes = delayMinutes(alert.action) ?: return
        val event = watchers[run.key]?.delay(run, minutes) ?: return
        applyEvents(run, listOf(event))
    }

    /** One notification when monitoring starts while BusPlanner has no vehicle for the run. */
    private fun warnIfNoVehicle(run: Run, status: RunStatus) {
        if (!AppSettings(this).noVehicleWarning) return
        val r = status.reading ?: return
        val message = r.bulletin ?: return
        if (r.busLat != null) return
        AppLog.log("${run.key}: no vehicle at monitoring start, warning posted")
        // A tap opens the app on the run list, where the run's card says "No vehicle" (user, 2026-11-21).
        val open = PendingIntent.getActivity(
            this, run.key.hashCode() + 2,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = Notification.Builder(this, BUS_ALERT_CHANNEL)
            .setSmallIcon(R.drawable.ic_bus)
            .setContentTitle("${run.bus}: BusPlanner reports no vehicle")
            .setContentText(message)
            .setStyle(Notification.BigTextStyle().bigText(message))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(run.key.hashCode() + 2, n)
    }

    private suspend fun read(run: Run, refreshButton: Boolean): RunStatus = try {
        Session.poll(this, run, refreshButton)
    } catch (_: LoginFailed) {
        // A refused password must not be retried every 30 s.
        for (key in watchers.keys.toList()) stop(key, "login refused")
        RunStatus(null, "Login failed. Log out and log in again.", null)
    }

    private fun notification(run: Run, status: RunStatus): Notification {
        val open = PendingIntent.getActivity(
            this, run.key.hashCode(),
            Intent(this, MainActivity::class.java).putExtra(EXTRA_RUN, run.key)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopWatching = serviceIntent(this, ACTION_STOP_WATCHING, run.key, runNotificationId(run.key))
        // Cleared by the user (Android 14+ lets them): it comes back, watching goes on (user, 2026-11-14).
        val dismissed = serviceIntent(this, ACTION_DISMISSED, run.key, runNotificationId(run.key))
        return Notification.Builder(this, WATCHING_CHANNEL)
            .setSmallIcon(R.drawable.ic_bus)
            .setContentTitle(status.reading?.runLabel ?: run.key)
            .setContentText(status.line)
            .setContentIntent(open)
            .setDeleteIntent(dismissed)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .addAction(Notification.Action.Builder(null, "Open map", open).build())
            .addAction(Notification.Action.Builder(null, "Stop watching", stopWatching).build())
            .build()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        for (key in watchers.keys.toList()) stop(key, "Android's foreground time limit reached")
    }

    override fun onDestroy() {
        scope.cancel()
        releaseWakeLock()
        watchers.clear()
        Session.setActive(emptySet())
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "io.github.anderewrey.bustracker.START"
        const val ACTION_STOP = "io.github.anderewrey.bustracker.STOP"
        const val ACTION_SILENCE = "io.github.anderewrey.bustracker.SILENCE"
        const val ACTION_STOP_WATCHING = "io.github.anderewrey.bustracker.STOP_WATCHING"
        const val ACTION_DISMISSED = "io.github.anderewrey.bustracker.DISMISSED"

        fun start(context: Context, key: String) {
            context.startForegroundService(
                Intent(context, MonitorService::class.java).setAction(ACTION_START).putExtra(EXTRA_RUN, key)
            )
        }

        /** The app was opened from the run's ringing alarm: that answers it. */
        fun silence(context: Context, key: String) {
            context.startService(Intent(context, MonitorService::class.java).setAction(ACTION_SILENCE).putExtra(EXTRA_RUN, key))
        }

        fun stop(context: Context, key: String) {
            context.startService(Intent(context, MonitorService::class.java).setAction(ACTION_STOP).putExtra(EXTRA_RUN, key))
        }
    }
}
