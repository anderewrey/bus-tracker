// SPDX-FileCopyrightText: 2026 anderewrey
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.anderewrey.bustracker

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import io.github.anderewrey.bustracker.data.Run
import io.github.anderewrey.bustracker.data.RunChange
import io.github.anderewrey.bustracker.data.Store
import io.github.anderewrey.bustracker.data.buildRuns
import io.github.anderewrey.bustracker.data.carryConfiguration
import io.github.anderewrey.bustracker.data.diffRuns
import io.github.anderewrey.bustracker.data.logLine
import io.github.anderewrey.bustracker.data.mergeChanges
import io.github.anderewrey.bustracker.data.AutoStart
import io.github.anderewrey.bustracker.data.WORK_DAYS
import io.github.anderewrey.bustracker.data.autoText
import io.github.anderewrey.bustracker.data.daysText
import io.github.anderewrey.bustracker.monitor.Scheduler
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import io.github.anderewrey.bustracker.data.schoolNames
import io.github.anderewrey.bustracker.monitor.distanceM
import io.github.anderewrey.bustracker.monitor.formatDistance
import io.github.anderewrey.bustracker.monitor.streetAt
import io.github.anderewrey.bustracker.net.BusPlannerClient
import io.github.anderewrey.bustracker.data.Period
import io.github.anderewrey.bustracker.data.Zone
import io.github.anderewrey.bustracker.data.ZoneStore
import io.github.anderewrey.bustracker.data.defaultZone
import java.util.UUID
import io.github.anderewrey.bustracker.data.PeriodChoices
import io.github.anderewrey.bustracker.data.alarmOn
import io.github.anderewrey.bustracker.data.periodOn
import io.github.anderewrey.bustracker.data.switchPeriod
import io.github.anderewrey.bustracker.monitor.MonitorService
import io.github.anderewrey.bustracker.net.BusAlert
import io.github.anderewrey.bustracker.net.LoginFailed
import io.github.anderewrey.bustracker.net.Reading
import io.github.anderewrey.bustracker.net.SessionExpired
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.Duration
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Within this distance of a school's position, the bus is shown as being at the school. */
const val SCHOOL_RADIUS_M = 150.0

/** An "updated" time further behind the clock than this, repeated twice, means stale data. */
private const val STALE_S = 90L
/** An "updated" time further behind than this is shown in red, with its age. */
const val OLD_S = 120L
const val WAITING_NETWORK = "Waiting for network"
/** A request that did not reach the portal (address lookup, timeout, connection) although Android says online. */
const val NETWORK_PROBLEM = "Network problem, retrying"
/** Network failures in a row after which the next check loads the page again, in case it is not only the network. */
private const val NETWORK_RELOAD_AFTER = 2
/** With no request for this long (between periods, or Android pausing the app), log in again first. */
private const val IDLE_MS = 5 * 60_000L
/** A login again for suspicious data (not a certain sign) happens at most this often. */
private const val SOFT_RELOGIN_MS = 5 * 60_000L
private val CLOCK = DateTimeFormatter.ofPattern("h:mm:ss a", Locale.US)
private val PERIOD = DateTimeFormatter.ofPattern("EEE H:mm", Locale.ENGLISH)

/** The runs saved by a scan, and what it found different from the previous scan. */
data class ScanResult(val runs: List<Run>, val changes: List<RunChange>)

/** Live status of one run, shared by the service, the notification and the screens. */
data class RunStatus(val reading: Reading?, val line: String, val checkedAt: LocalTime?, val old: Boolean = false)

/** The one BusPlanner session of the app; logs in again by itself when the server drops it. */
object Session {
    /** The client of the portal in use; a new one (logged out) when the portal changes. */
    private var client: BusPlannerClient? = null
    private val lock = Mutex()
    private var loggedIn = false
    /** Why the next request logs in first although logged in (period start, suspicious data), or null. */
    private var renewReason: String? = null
    /** elapsedRealtime of the last request that worked, for IDLE_MS. */
    private var lastRequestAt = 0L
    private var lastSoftRelogin = 0L

    /** The next request logs in first: a session left from earlier may be gone on the server. */
    fun renewLogin(reason: String) {
        renewReason = reason
    }

    private fun clientFor(portal: String): BusPlannerClient =
        client?.takeIf { it.base == portal } ?: BusPlannerClient(portal).also {
            client = it
            loggedIn = false
        }
    private val streets = HashMap<String, Pair<String, String?>>()
    /** Runs whose last check failed or came back empty: the next check loads the page first. */
    private val needsReload = HashSet<String>()
    /** Network failures in a row, per run. */
    private val networkFailures = HashMap<String, Int>()
    /** BusPlanner's "updated" time seen by each run's last check, and how many checks in a row repeated it. */
    private val lastUpdated = HashMap<String, String?>()
    private val stale = HashMap<String, Int>()

    private val _status = MutableStateFlow<Map<String, RunStatus>>(emptyMap())
    val status: StateFlow<Map<String, RunStatus>> = _status

    private val _active = MutableStateFlow<Set<String>>(emptySet())
    val active: StateFlow<Set<String>> = _active

    private val _runs = MutableStateFlow<List<Run>>(emptyList())
    val runs: StateFlow<List<Run>> = _runs

    private val _changes = MutableStateFlow<List<RunChange>>(emptyList())
    val changes: StateFlow<List<RunChange>> = _changes

    private val _autoStart = MutableStateFlow(AutoStart())
    val autoStart: StateFlow<AutoStart> = _autoStart

    /** When each run's alarm last rang; see [hasRung]. */
    private val _rang = MutableStateFlow<Map<String, LocalDateTime>>(emptyMap())
    val rang: StateFlow<Map<String, LocalDateTime>> = _rang

    /** The zones drawn on the phone (DESIGN.md §6); kept across logouts. */
    private val _zones = MutableStateFlow<List<Zone>>(emptyList())
    val zones: StateFlow<List<Zone>> = _zones

    /** The Alarm switch's choices for single periods (DESIGN.md §5). */
    private val _choices = MutableStateFlow(PeriodChoices())
    val choices: StateFlow<PeriodChoices> = _choices

    /** When watching ends for each watched run: its period's end, later after a delay notice. */
    private val _watchEnd = MutableStateFlow<Map<String, LocalDateTime>>(emptyMap())
    val watchEnd: StateFlow<Map<String, LocalDateTime>> = _watchEnd

    /** The BusPlanner notice that counts for each watched run (DESIGN.md §7.3), shown in red on its title. */
    private val _notices = MutableStateFlow<Map<String, BusAlert>>(emptyMap())
    val notices: StateFlow<Map<String, BusAlert>> = _notices

    fun setActive(keys: Set<String>) {
        _active.value = keys
    }

    fun setWatchEnd(key: String, end: LocalDateTime?) {
        _watchEnd.value = if (end == null) _watchEnd.value - key else _watchEnd.value + (key to end)
    }

    fun setNotice(key: String, alert: BusAlert?) {
        if (_notices.value[key] == alert) return
        _notices.value = if (alert == null) _notices.value - key else _notices.value + (key to alert)
    }

    /**
     * The period the run's Alarm switch is about now: while watching, today's (even past its end,
     * after a delay notice); otherwise the one under way or the next one.
     */
    fun switchPeriodOf(run: Run, now: LocalDateTime = LocalDateTime.now()): Period? =
        if (run.key in _active.value) run.periodOn(now.toLocalDate())
        else switchPeriod(run, now, _choices.value.ended[run.key])

    fun isAlarmOn(run: Run, period: Period): Boolean = alarmOn(run, period, _autoStart.value, _choices.value)

    private fun saveChoices(context: Context, choices: PeriodChoices) {
        val store = Store(context)
        store.choices = choices
        _choices.value = store.choices
    }

    /**
     * The Alarm switch (DESIGN.md §5). Off: this period doesn't watch, and watching stops if it
     * was. On: this period watches (also during holidays), starting now if it is under way, and
     * the alarm may ring again.
     */
    fun setAlarm(context: Context, run: Run, on: Boolean) {
        val now = LocalDateTime.now()
        val p = switchPeriodOf(run, now) ?: return
        val c = _choices.value
        saveChoices(
            context,
            if (on) c.copy(
                skipped = c.skipped - run.key,
                forced = if (_autoStart.value.allows(p.start.toLocalDate())) c.forced - run.key else c.forced + (run.key to p.start),
            ) else c.copy(skipped = c.skipped + (run.key to p.start), forced = c.forced - run.key),
        )
        AppLog.log("${run.key}: Alarm switch ${if (on) "on" else "off"} for the period ${p.start.format(PERIOD)}–${p.end.toLocalTime()}")
        if (on) {
            if (hasRung(run.key)) rearm(context, run.key)
            if (now in p && run.key !in _active.value) MonitorService.start(context, run.key)
        } else if (run.key in _active.value) {
            MonitorService.stop(context, run.key)
        }
        Scheduler.reschedule(context)
    }

    /** Watching ended by itself: the run's switch moves on to its next period. */
    fun markEnded(context: Context, key: String, periodStart: LocalDateTime) {
        saveChoices(context, _choices.value.let { it.copy(ended = it.ended + (key to periodStart)) })
    }

    /**
     * Starts the runs whose period is under way with the switch on but which aren't watching,
     * e.g. after the phone was off at the period start. Called when the app comes to the front.
     */
    fun catchUp(context: Context) {
        val now = LocalDateTime.now()
        for (run in _runs.value) {
            if (run.key in _active.value) continue
            val p = switchPeriod(run, now, _choices.value.ended[run.key]) ?: continue
            if (now in p && isAlarmOn(run, p)) {
                AppLog.log("${run.key}: period under way and not watching, starting it")
                try {
                    MonitorService.start(context, run.key)
                } catch (e: Exception) {
                    AppLog.log("${run.key}: start refused by Android: ${e.message ?: e.javaClass.simpleName}")
                }
            }
        }
    }

    /**
     * When the monitoring loop's next round starts (SystemClock.elapsedRealtime); null while a
     * round is running or nothing is monitored. The map's countdown ring follows it.
     */
    private val _nextCheck = MutableStateFlow<Long?>(null)
    val nextCheck: StateFlow<Long?> = _nextCheck

    fun setNextCheck(at: Long?) {
        _nextCheck.value = at
    }

    /** The map's Refresh while monitored: the loop checks now and its 30 s start again from there. */
    val checkNowRequests = Channel<Unit>(Channel.CONFLATED)

    fun checkNow() {
        checkNowRequests.trySend(Unit)
    }

    private fun setStatus(key: String, status: RunStatus) {
        _status.value = _status.value + (key to status)
    }

    private suspend fun <T> withLogin(context: Context, block: (BusPlannerClient) -> T): T =
        withContext(Dispatchers.IO) { lock.withLock { loggedInCall(context, block) } }

    private fun <T> loggedInCall(context: Context, block: (BusPlannerClient) -> T): T {
        val store = Store(context)
        val portal = store.portal ?: throw LoginFailed("portal address missing, log in again")
        val email = store.email ?: throw LoginFailed("not logged in")
        val password = store.password ?: throw LoginFailed("password missing, log in again")
        val client = clientFor(portal)
        val idle = SystemClock.elapsedRealtime() - lastRequestAt
        val why = renewReason ?: if (lastRequestAt != 0L && idle > IDLE_MS) "nothing asked for ${idle / 60_000} min" else null
        if (!loggedIn || why != null) {
            if (loggedIn) AppLog.log("logging in again first: $why")
            client.login(email, password)
            loggedIn = true
            renewReason = null
        }
        val result = try {
            block(client)
        } catch (_: SessionExpired) {
            AppLog.log("session dropped by the server, logging in again")
            client.login(email, password)
            block(client)
        }
        lastRequestAt = SystemClock.elapsedRealtime()
        return result
    }

    /** Checks the login, then scans the account; saves both only when everything worked. */
    suspend fun loginAndScan(context: Context, portal: String, email: String, password: String): List<Run> {
        withContext(Dispatchers.IO) {
            lock.withLock {
                val client = clientFor(portal)
                loggedIn = false
                client.login(email, password)
                loggedIn = true
            }
        }
        Store(context).saveLogin(portal, email, password)
        return scan(context, "login").runs
    }

    /** Loads what the screens show from the saved state; called once when the app process starts. */
    fun init(context: Context) {
        val store = Store(context)
        _runs.value = store.runs
        _changes.value = store.changes
        _rang.value = store.rang
        _choices.value = store.choices
        _zones.value = ZoneStore(context).zones
        settleAutoStart(context)
    }

    /**
     * Scans the account and compares it with the saved runs: planned times are applied at once,
     * every difference waits on the Changes screen, and a moved stop or a replacing run keeps
     * the past stop's zones until the user answers.
     */
    suspend fun scan(context: Context, why: String = "requested"): ScanResult {
        AppLog.log("scan started ($why)")
        val result = withLogin(context) { it.scan() }
        val scanned = buildRuns(result)
        val names = schoolNames(result)
        val schools = names.mapNotNull { name ->
            runCatching { withLogin(context) { it.schoolPosition(name) } }
                .onFailure { AppLog.log("school $name: ${it.message ?: it.javaClass.simpleName}") }
                .getOrNull()
        }
        val store = Store(context)
        val past = store.runs
        // The first scan after logging in has nothing to compare with.
        val fresh = if (past.isEmpty()) emptyList() else diffRuns(past, scanned, LocalDateTime.now().withNano(0))
        val runs = carryConfiguration(scanned, fresh, past, _zones.value)
        store.runs = runs
        store.schools = schools
        store.lastScan = LocalDate.now()
        if (fresh.isNotEmpty()) store.changes = mergeChanges(store.changes, fresh)
        _runs.value = runs
        _changes.value = store.changes
        fresh.forEach { AppLog.log(it.logLine()) }
        AppLog.log(if (past.isEmpty()) "scan: first scan, nothing to compare" else "scan: ${fresh.size} change(s) since the last scan")
        runs.forEach { r -> AppLog.log("${r.key} main zone: ${_zones.value.firstOrNull { it.id == r.mainZone }?.name ?: "none"}") }
        for ((student, rows) in result) {
            AppLog.log("scan ${student.name}: ${rows.size} stops")
            for (r in rows) {
                AppLog.log("  ${r.stopType} · ${r.time} · ${r.location} · route ${r.route ?: "?"} · position ${if (r.lat != null) "yes" else "no"}")
            }
        }
        for (name in names) {
            val s = schools.firstOrNull { it.name == name }
            AppLog.log("school $name: " + if (s != null) String.format(Locale.US, "%.6f,%.6f", s.lat, s.lon) else "position not found")
        }
        if (result.isEmpty()) AppLog.log("scan: no student found on the account")
        Scheduler.reschedule(context)
        return ScanResult(runs, fresh)
    }

    /** Whether the run's alarm already rang in today's monitoring period. */
    fun hasRung(key: String): Boolean = _rang.value[key]?.toLocalDate() == LocalDate.now()

    fun markRang(context: Context, key: String) {
        val store = Store(context)
        store.rang = store.rang + (key to LocalDateTime.now().withNano(0))
        _rang.value = store.rang
    }

    /** Lets the run's alarm ring again: at the next check if the bus is in the zone, or when it next enters. */
    fun rearm(context: Context, key: String) {
        val store = Store(context)
        store.rang = store.rang - key
        _rang.value = store.rang
        AppLog.log("$key: alarm re-armed")
    }

    /** The runs whose main zone is [zoneId]. */
    fun runsUsing(zoneId: String): List<Run> = _runs.value.filter { it.mainZone == zoneId }

    /** Makes [zoneId] the run's main zone; null: no main zone, no alarm. The zone itself stays. */
    fun setMainZone(context: Context, key: String, zoneId: String?) {
        val store = Store(context)
        store.runs = store.runs.map { if (it.key == key) it.copy(mainZone = zoneId) else it }
        _runs.value = store.runs
        AppLog.log("$key main zone: ${_zones.value.firstOrNull { it.id == zoneId }?.name ?: "none"}")
    }

    /**
     * Saves a zone from the zone screen as [key]'s main zone.
     * - A new zone: also becomes the main zone of every other run without one whose stop is
     *   inside it (the AM and PM runs of one stop). Returns those runs' keys.
     * - A changed zone, [copy] false: every run using it gets the new shape and name.
     * - [copy] true: a new zone for this run only; the others keep the old one.
     */
    fun saveZone(context: Context, key: String, zone: Zone, copy: Boolean = false): List<String> {
        val zs = ZoneStore(context)
        val existing = zs.zones.any { it.id == zone.id }
        val saved = if (copy || !existing) zone.copy(id = UUID.randomUUID().toString()) else zone
        zs.zones = if (existing && !copy) zs.zones.map { if (it.id == zone.id) saved else it } else zs.zones + saved
        _zones.value = zs.zones
        AppLog.log(
            "zone ${saved.name} ${if (existing && !copy) "changed" else "saved"} for $key, ${saved.corners.size} corners: " +
                saved.corners.joinToString(" ") { (lat, lon) -> String.format(Locale.US, "%.6f,%.6f", lat, lon) }
        )
        setMainZone(context, key, saved.id)
        if (existing) return emptyList()
        val others = _runs.value.filter { r ->
            r.key != key && r.mainZone == null && r.lat != null && r.lon != null && saved.contains(r.lat, r.lon)
        }
        others.forEach { setMainZone(context, it.key, saved.id) }
        return others.map { it.key }
    }

    /** Turns every automatic start on or off, or off until a day. */
    fun setAutoStart(context: Context, auto: AutoStart) {
        Store(context).autoStart = auto
        _autoStart.value = auto
        AppLog.log("automatic start: " + autoText(auto))
        Scheduler.reschedule(context)
    }

    /** The saved switch, turned back on once its resume day has come. */
    fun settleAutoStart(context: Context): AutoStart {
        val store = Store(context)
        val saved = store.autoStart
        val now = saved.settled(LocalDate.now())
        if (now != saved) {
            store.autoStart = now
            AppLog.log("automatic start back on, as planned")
        }
        _autoStart.value = now
        return now
    }

    /** Adds or removes one day for every run at once. */
    fun setDayForAll(context: Context, day: DayOfWeek, on: Boolean) {
        val store = Store(context)
        store.runs = store.runs.map { it.copy(days = if (on) it.days + day else it.days - day) }
        _runs.value = store.runs
        AppLog.log("all runs: ${day.name.lowercase()} ${if (on) "added" else "removed"}")
        Scheduler.reschedule(context)
    }

    /** Sets the days a run starts by itself. */
    fun setDays(context: Context, key: String, days: Set<DayOfWeek>) {
        val store = Store(context)
        store.runs = store.runs.map { if (it.key == key) it.copy(days = days) else it }
        _runs.value = store.runs
        AppLog.log("$key starts by itself: ${daysText(days)}")
        Scheduler.reschedule(context)
    }

    /**
     * Acknowledges a change. For a moved stop or a replacing run, [keep] keeps the configuration
     * of the past stop (days and main zone); otherwise the run starts again from its new stop:
     * work days, and the zone containing the new stop, if any.
     */
    fun answer(context: Context, change: RunChange, keep: Boolean) {
        val store = Store(context)
        if (change.needsChoice && !keep) {
            store.runs = store.runs.map {
                if (it.key == change.new!!.key) it.copy(mainZone = defaultZone(it, _zones.value), days = WORK_DAYS) else it
            }
        }
        store.changes = store.changes.filterNot { it.id == change.id }
        _runs.value = store.runs
        _changes.value = store.changes
        Scheduler.reschedule(context)
        AppLog.log(
            "change ${change.title} acknowledged" +
                if (change.needsChoice) if (keep) ": same configuration kept" else ": new configuration" else ""
        )
    }

    /**
     * Asks BusPlanner for the run's bus and builds its status line. Each check is one request,
     * the portal's Refresh button; the page is loaded only at the first check and after a failed
     * or empty one. An unchanged GPS time is normal (a parked bus).
     */
    suspend fun poll(context: Context, run: Run, refreshButton: Boolean = false): RunStatus {
        val now = LocalTime.now()
        // No network (e.g. just after the phone woke up): wait for it, it is not the session's fault.
        if (!online(context)) {
            if (_status.value[run.key]?.line != WAITING_NETWORK) AppLog.log("poll ${run.key}: no network, waiting")
            val status = RunStatus(_status.value[run.key]?.reading, WAITING_NETWORK, now, old = true)
            setStatus(run.key, status)
            return status
        }
        val retry = run.key in needsReload
        val status = try {
            var r = withLogin(context) { it.refresh(run.students.first().guid, reload = retry) }
            logPoll(run, r, if (refreshButton) " (refresh button)" else if (retry) " (page loaded again after a failed or empty check)" else "")
            // Safety net: the Refresh postback should always bring a newer "updated" time. A bus
            // with no time at all, or the same time twice while well behind the clock, is not live
            // data: after 2 such checks, log in again and load the page (at most every 5 minutes).
            val noTime = r.busLat != null && r.updated == null
            val stuck = r.updated != null && r.updated == lastUpdated[run.key] && secondsBehind(r.updated) > STALE_S
            if (!retry && (noTime || stuck)) stale[run.key] = (stale[run.key] ?: 0) + 1 else stale.remove(run.key)
            val clock = SystemClock.elapsedRealtime()
            if ((stale[run.key] ?: 0) >= 2 && (lastSoftRelogin == 0L || clock - lastSoftRelogin > SOFT_RELOGIN_MS)) {
                stale.remove(run.key)
                lastSoftRelogin = clock
                renewLogin(if (noTime) "a bus with no update time for 2 checks" else "update time stuck for 2 checks")
                r = withLogin(context) { it.refresh(run.students.first().guid, reload = true) }
                logPoll(run, r, " (logged in again, page loaded again)")
            }
            lastUpdated[run.key] = r.updated
            networkFailures.remove(run.key)
            logOtherStop(run, r)
            val blank = r.busLat == null && r.stopLat == null && r.updated == null && r.bulletin == null
            if (blank) needsReload += run.key else needsReload -= run.key
            val old = r.busLat != null && (r.updated == null || secondsBehind(r.updated) > OLD_S)
            RunStatus(r, line(context, run, r), now, old)
        } catch (e: LoginFailed) {
            AppLog.log("poll ${run.key}: login refused")
            throw e
        } catch (e: IOException) {
            // Seen 2026-11-09: a few seconds of failed address lookups and timeouts while Android
            // still said online. The portal's session is fine: the next check is the plain
            // Refresh again, and only a second failure in a row loads the page again.
            val failures = (networkFailures[run.key] ?: 0) + 1
            networkFailures[run.key] = failures
            AppLog.log("poll ${run.key}: network error ${e.javaClass.simpleName}: ${e.message}")
            if (failures >= NETWORK_RELOAD_AFTER) needsReload += run.key
            RunStatus(_status.value[run.key]?.reading, NETWORK_PROBLEM, now, old = true)
        } catch (e: Exception) {
            val message = e.message ?: e.javaClass.simpleName
            AppLog.log("poll ${run.key}: error $message")
            needsReload += run.key
            RunStatus(_status.value[run.key]?.reading, "Error: $message", now, old = true)
        }
        setStatus(run.key, status)
        return status
    }

    /** True when Android has a network with internet access. */
    private fun online(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return true
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /** How far BusPlanner's "updated" time (e.g. "3:58:52 PM") is behind the phone's clock; 0 when unknown. */
    private fun secondsBehind(updated: String): Long {
        val at = runCatching { LocalTime.parse(updated.trim().uppercase(Locale.US), CLOCK) }.getOrNull() ?: return 0
        return Duration.between(at, LocalTime.now()).seconds.coerceAtLeast(0)
    }

    /** Runs whose live page showed another stop than their own, logged once per run while the app runs. */
    private val otherStopLogged = HashSet<String>()

    /** A hint for the Log: the page's stop isn't the run's own (another half-day's stop). */
    private fun logOtherStop(run: Run, r: Reading) {
        val lat = run.lat ?: return
        val lon = run.lon ?: return
        val pLat = r.stopLat ?: return
        val pLon = r.stopLon ?: return
        val d = distanceM(lat, lon, pLat, pLon)
        if (d > 50 && otherStopLogged.add(run.key)) {
            AppLog.log("poll ${run.key}: BusPlanner's page shows another stop, ${formatDistance(d)} from the run's own; the run's own is used")
        }
    }

    private fun logPoll(run: Run, r: Reading, how: String) {
        AppLog.log(
            "poll ${run.key}$how: bus ${r.busLat?.let { String.format(Locale.US, "%.5f,%.5f", it, r.busLon) } ?: "none"}, " +
                "GPS ${r.reported ?: "?"}, updated ${r.updated ?: "?"}" + (r.bulletin?.let { ", bulletin: $it" } ?: "")
        )
    }

    private suspend fun line(context: Context, run: Run, r: Reading): String {
        // Short: the map shows BusPlanner's full message after a tap on ↻; the "no vehicle" warning has it too.
        if (r.busLat == null || r.busLon == null) return if (r.bulletin != null) "No vehicle" else "No bus position"
        val parts = ArrayList<String>()
        // The run's own stop: the live page is per child and shows one stop for the AM and PM runs alike.
        val sLat = run.lat ?: r.stopLat
        val sLon = run.lon ?: r.stopLon
        if (sLat != null && sLon != null) parts += formatDistance(distanceM(r.busLat, r.busLon, sLat, sLon))
        val school = Store(context).schools.firstOrNull { distanceM(r.busLat, r.busLon, it.lat, it.lon) <= SCHOOL_RADIUS_M }
        (school?.name ?: street(context, run.key, r.busLat, r.busLon))?.let { parts += it }
        r.reported?.let { parts += "GPS $it" }
        // Old data is shown as such (in red on the screens), never as a live position.
        when {
            r.updated == null -> parts += "not live"
            secondsBehind(r.updated) > OLD_S -> parts += "updated ${secondsBehind(r.updated) / 60} min ago"
        }
        return parts.joinToString(" · ")
    }

    /** Looks the street up again only when the bus moved. */
    private suspend fun street(context: Context, key: String, lat: Double, lon: Double): String? {
        val at = String.format(Locale.US, "%.5f,%.5f", lat, lon)
        streets[key]?.let { (where, name) -> if (where == at) return name }
        val name = streetAt(context, lat, lon)
        AppLog.log("street at $at: ${name ?: "unknown"}")
        streets[key] = at to name
        return name
    }

    fun logout(context: Context) {
        Scheduler.cancel(context)
        loggedIn = false
        client = null
        Store(context).clear()
        _status.value = emptyMap()
        _runs.value = emptyList()
        _changes.value = emptyList()
        _autoStart.value = AutoStart()
        _rang.value = emptyMap()
        _choices.value = PeriodChoices()
        _watchEnd.value = emptyMap()
        _notices.value = emptyMap()
        needsReload.clear()
        networkFailures.clear()
        lastUpdated.clear()
        stale.clear()
        AppLog.log("logged out")
    }
}
