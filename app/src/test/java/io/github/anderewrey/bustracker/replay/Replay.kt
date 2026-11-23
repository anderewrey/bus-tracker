// SPDX-FileCopyrightText: 2026 anderewrey
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.anderewrey.bustracker.replay

import io.github.anderewrey.bustracker.data.Run
import io.github.anderewrey.bustracker.data.SchoolSpot
import io.github.anderewrey.bustracker.data.Zone
import io.github.anderewrey.bustracker.data.periodOn
import io.github.anderewrey.bustracker.monitor.PeriodMarks
import io.github.anderewrey.bustracker.monitor.Watcher
import io.github.anderewrey.bustracker.monitor.WatchEvent
import io.github.anderewrey.bustracker.net.Reading
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** A school morning: Monday 2026-11-23. */
val DAY: LocalDate = LocalDate.of(2026, 11, 23)

/** The run of the test map: B101's morning pickup at This Street & The Other Street, watched 6:57–7:57. */
fun morningRun(headsUp: Boolean = true, alarmAfterHeadsUp: Boolean = false) = Run(
    key = "B101 AM", bus = "B101", stopType = "AM Pickup", time = "7:27 AM", address = TestMap.address,
    lat = TestMap.stop.first, lon = TestMap.stop.second, students = emptyList(),
    mainZone = TestMap.home.id, school = "Sample School",
    headsUpZone = if (headsUp) TestMap.headsUp.id else null, alarmAfterHeadsUp = alarmAfterHeadsUp,
)

/** What happens around the checks, at a given time. */
sealed interface Act {
    val at: LocalTime

    /** The checks from [at] until [until] (excluded) fail: timeouts, failed address lookups. */
    data class NetworkDown(override val at: LocalTime, val until: LocalTime) : Act

    /** The user turns the run's Alarm switch [on] or off. */
    data class AlarmSwitch(override val at: LocalTime, val on: Boolean) : Act

    /** BusPlanner posts "Delayed [minutes] minutes" for the bus, seen after the check at [at]. */
    data class Delay(override val at: LocalTime, val minutes: Int) : Act
}

/** The heads-up and alarm marks, as Session keeps them for the day. */
class MemoryMarks : PeriodMarks {
    override var rang = false
    override var headsUp = false
    override fun markRang() { rang = true }
    override fun markHeadsUp() { headsUp = true }

    /** Session.rearm: the Alarm switch off and on lets both sound again. */
    fun rearm() {
        rang = false
        headsUp = false
    }
}

/**
 * A trip replayed through [Watcher] the way MonitorService runs it (DESIGN.md §8, Trip replays):
 * one check every 30 s at the trip's check times, from its first to its last, plus [acts].
 * [run] can change between checks (a page edit); [zones] and [schools] are the test map's.
 */
class Scenario(
    val trip: List<Fix>,
    val run: Run,
    val acts: List<Act> = emptyList(),
    val zones: List<Zone> = TestMap.zones,
    val schools: List<SchoolSpot> = TestMap.schools,
)

private val HMS = DateTimeFormatter.ofPattern("HH:mm:ss")
private val BUSPLANNER_CLOCK = DateTimeFormatter.ofPattern("h:mm:ss a", Locale.US)

/** The page's answer to a check: the bus at [fix], its "updated" time 40 s behind the clock (DESIGN.md §2.2). */
fun reading(fix: Fix) = Reading(
    busLat = fix.lat, busLon = fix.lon, stopLat = TestMap.stop.first, stopLon = TestMap.stop.second,
    runLabel = "B101 AM Pickup", updated = fix.check.minusSeconds(40).format(BUSPLANNER_CLOCK),
    reported = fix.gps.format(BUSPLANNER_CLOCK), bulletin = null,
)

/**
 * Replays [scenario]; returns what happened, one line per event: the check's time, then the Log
 * line the app writes (`07:26:10 B101 AM: bus entered main zone Home zone, ringing`). Lines
 * starting with `*` are the scenario's own acts (`07:22:00 * Alarm switch off`).
 */
fun replay(scenario: Scenario): List<String> {
    val run = scenario.run
    val key = run.key
    val period = requireNotNull(run.periodOn(DAY)) { "the run has no planned time" }
    val marks = MemoryMarks()
    val out = ArrayList<String>()
    var watcher: Watcher? = null
    var last: Reading? = null
    val pending = scenario.acts.filterNot { it is Act.NetworkDown }.sortedBy { it.at }.toMutableList()

    fun record(at: LocalTime, events: List<WatchEvent>) {
        for (e in events) {
            val line = when (e) {
                is WatchEvent.End -> "monitoring $key stopped: ${e.reason}"
                is WatchEvent.PeriodOver -> "monitoring $key stopped: monitoring period ended"
                else -> e.line(key)
            }
            out += "${at.format(HMS)} $line"
            if (e is WatchEvent.End || e is WatchEvent.PeriodOver) watcher = null
        }
    }

    fun start(at: LocalTime) {
        if (DAY.atTime(at) !in period) return
        val w = Watcher(run, scenario.zones, scenario.schools, period, marks)
        watcher = w
        record(at, w.started)
        out += "${at.format(HMS)} monitoring $key started, until ${w.until.toLocalTime().withNano(0)}"
    }

    /** Session.setAlarm: off stops watching (by the user); on lets the run ring again if it rang, and starts it. */
    fun switch(act: Act.AlarmSwitch) {
        out += "${act.at.format(HMS)} * Alarm switch ${if (act.on) "on" else "off"}"
        if (!act.on) {
            if (watcher != null) out += "${act.at.format(HMS)} monitoring $key stopped: Alarm switch turned off"
            watcher = null
            return
        }
        if (marks.rang) {
            marks.rearm()
            out += "${act.at.format(HMS)} $key: alarm re-armed"
        }
        if (watcher == null) start(act.at)
    }

    start(scenario.trip.first().check)
    for (fix in scenario.trip) {
        // Switches turned before this check.
        pending.filterIsInstance<Act.AlarmSwitch>().filter { it.at <= fix.check }.forEach {
            pending.remove(it)
            switch(it)
        }
        val w = watcher
        if (w != null) {
            record(fix.check, w.beforeCheck(run, scenario.zones, scenario.schools, DAY.atTime(fix.check)))
        }
        val down = scenario.acts.any { it is Act.NetworkDown && fix.check >= it.at && fix.check < it.until }
        // A failed check keeps the last reading in the status (Session.poll), and the service
        // passes it to the watcher again.
        if (!down) last = reading(fix) else if (watcher != null) out += "${fix.check.format(HMS)} * network error"
        watcher?.let { record(fix.check, it.check(run, scenario.zones, last)) }
        // BusPlanner's notices are read after the round of checks.
        pending.filterIsInstance<Act.Delay>().filter { it.at <= fix.check }.forEach { d ->
            pending.remove(d)
            out += "${fix.check.format(HMS)} * BusPlanner notice: Delayed ${d.minutes} minutes"
            watcher?.delay(run, d.minutes)?.let { record(fix.check, listOf(it)) }
        }
    }
    return out
}

/** The lines of [replay] that aren't the start's, for shorter expectations. */
fun List<String>.events(): List<String> = filterNot { " zones of " in it || "watching ends by itself" in it || " started, until " in it }
