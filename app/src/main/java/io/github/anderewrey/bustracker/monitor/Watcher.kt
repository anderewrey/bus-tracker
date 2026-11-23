// SPDX-FileCopyrightText: 2026 anderewrey
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.anderewrey.bustracker.monitor

import io.github.anderewrey.bustracker.data.EndRule
import io.github.anderewrey.bustracker.data.EndWatch
import io.github.anderewrey.bustracker.data.Period
import io.github.anderewrey.bustracker.data.Run
import io.github.anderewrey.bustracker.data.SchoolSpot
import io.github.anderewrey.bustracker.data.Zone
import io.github.anderewrey.bustracker.data.alarmArmed
import io.github.anderewrey.bustracker.data.headsUpZoneOf
import io.github.anderewrey.bustracker.data.mainZoneOf
import io.github.anderewrey.bustracker.data.periodOn
import io.github.anderewrey.bustracker.net.Reading
import java.time.LocalDateTime

/**
 * What the heads-up sound and the alarm already did in the run's period. They outlive a
 * [Watcher]: the app keeps them for the day (Session), so a restart doesn't sound again, and
 * the Alarm switch off and on clears them (Session.rearm).
 */
interface PeriodMarks {
    val rang: Boolean
    val headsUp: Boolean
    fun markRang()
    fun markHeadsUp()
}

/**
 * What a check, a configuration change or a BusPlanner notice decided for a watched run. The
 * service applies it (sound, notification, end of watching) and writes [line] to the Log;
 * the lines are the ones the owner reads, so they don't change with the code.
 */
sealed interface WatchEvent {
    /** The Log line, without the run's key; null when the service writes its own. */
    fun line(key: String): String?

    /** At the start: the run's zones, in the order the bus meets them. */
    data class Zones(val headsUp: Zone?, val main: Zone?, val alarmAfterHeadsUp: Boolean) : WatchEvent {
        override fun line(key: String) = "zones of $key: " + listOfNotNull(
            headsUp?.let { "${it.name} (heads-up${if (alarmAfterHeadsUp) ", alarm only after it" else ""})" },
            main?.let { "${it.name} (main)" },
        ).joinToString().ifEmpty { "none" }
    }

    /** At the start, and when the run's page changes it: what ends watching by itself (DESIGN.md §5.1). */
    data class EndRuleSet(val rule: EndRule, val zone: Zone?, val schools: List<SchoolSpot>) : WatchEvent {
        override fun line(key: String) = "$key: watching ends by itself: " + when (rule) {
            EndRule.SCHOOL -> if (schools.any { it.known }) "${rule.label} (${schools.filter { it.known }.joinToString { "${it.name}, ${it.radius.toInt()} m" }})"
            else "${rule.label}, but the school's position is unknown: only at the period's end"
            EndRule.LEAVES_ZONE, EndRule.ENTERS_ZONE -> if (zone != null) "${rule.label} (${zone.name})" else "${rule.label}, but there is no zone: only at the period's end"
            EndRule.PERIOD_END -> rule.label
        }
    }

    /** The period's minutes changed on the run's page while it is watched. */
    data class PeriodChanged(val end: LocalDateTime, val old: LocalDateTime) : WatchEvent {
        override fun line(key: String) = "$key: period changed, watching until ${end.toLocalTime()} instead of ${old.toLocalTime()}"
    }

    /** A "Delayed N minutes" notice moved the end of watching (DESIGN.md §7.3). */
    data class Delayed(val minutes: Int, val end: LocalDateTime, val planned: LocalDateTime) : WatchEvent {
        override fun line(key: String) =
            "$key: BusPlanner says delayed $minutes minutes, watching until ${end.toLocalTime()} instead of ${planned.toLocalTime()}"
    }

    /** The bus entered the heads-up zone: a sound, once per period. */
    data class HeadsUp(val zone: Zone) : WatchEvent {
        override fun line(key: String) = "$key: bus entered heads-up zone ${zone.name}, sounding once"
    }

    /** The bus entered the heads-up zone again, after it sounded this period: silence. */
    data class HeadsUpAlreadySounded(val zone: Zone) : WatchEvent {
        override fun line(key: String) = "$key: bus in heads-up zone ${zone.name}, already sounded this period"
    }

    /** With "Alarm only after the heads-up": the bus in the main zone before the heads-up only crosses it. Once per period. */
    data class BlockedBeforeHeadsUp(val zone: Zone) : WatchEvent {
        override fun line(key: String) = "$key: bus in main zone ${zone.name} before the heads-up zone, no alarm"
    }

    /** The bus entered the main zone: the alarm rings. */
    data class Ring(val zone: Zone) : WatchEvent {
        override fun line(key: String) = "$key: bus entered main zone ${zone.name}, ringing"
    }

    /** The bus entered the main zone again, after the alarm rang this period: silence. */
    data class AlreadyRang(val zone: Zone) : WatchEvent {
        override fun line(key: String) = "$key: bus in main zone ${zone.name}, already rang this period (Alarm switch off and on to ring again)"
    }

    /** The bus left the main zone: a ringing alarm goes away with it. */
    data class LeftMainZone(val zone: Zone) : WatchEvent {
        override fun line(key: String) = "$key: bus left main zone ${zone.name}"
    }

    /**
     * The run's end-of-monitoring rule says watching ends. [parkedAt] and [spot]: the school the
     * bus parked at and where, for the school's corrected position. The service logs the stop.
     */
    data class End(val reason: String, val parkedAt: SchoolSpot? = null, val spot: Pair<Double, Double>? = null) : WatchEvent {
        override fun line(key: String): String? = null
    }

    /** The period, delays included, is over. The service logs the stop. */
    data object PeriodOver : WatchEvent {
        override fun line(key: String): String? = null
    }
}

/**
 * The decisions about one watched run in one monitoring period, without Android: the heads-up
 * zone, the main zone's alarm, the end of watching and the period's end (DESIGN.md §5, §6.1,
 * §7.3). MonitorService feeds it each check and applies what it returns; the tests replay whole
 * trips through it (DESIGN.md §8, Trip replays).
 *
 * [run], [zones] and [schools] are passed again at each call, as the user can change them while
 * the run is watched.
 */
class Watcher(run: Run, zones: List<Zone>, schools: List<SchoolSpot>, period: Period, private val marks: PeriodMarks) {
    val key = run.key
    /** The start of the period being watched. */
    var periodStart: LocalDateTime = period.start
        private set
    /** When watching ends: the period's end, plus any BusPlanner delay. */
    var until: LocalDateTime = period.end
        private set
    /** Minutes a BusPlanner delay notice added to the period. */
    private var delayMin = 0
    private var inMainZone = false
    private var inHeadsUpZone = false
    /** Whether "bus in the main zone before the heads-up" was logged this period. */
    private var blockedLogged = false
    private lateinit var endWatch: EndWatch
    /** The rule and zone [endWatch] was made for. */
    private var endWatchFor: Pair<EndRule, String?>? = null

    /** What the start decided, for the Log: the end rule, then the zones. */
    val started: List<WatchEvent> = listOf(
        setEndWatch(run, zones, schools),
        WatchEvent.Zones(headsUpZoneOf(run, zones), mainZoneOf(run, zones), run.alarmAfterHeadsUp),
    )

    /**
     * The run's end-of-monitoring rule, for the zone and schools it is about: the school's
     * corrected position and circle first (DESIGN.md §5.3). A school with no known position
     * never ends it: only the period's end and the Alarm switch do.
     */
    private fun setEndWatch(run: Run, zones: List<Zone>, schools: List<SchoolSpot>): WatchEvent {
        val rule = run.endOfWatch
        val zone = zones.firstOrNull { it.id == run.endZoneId }
        // The run's own school; every known school only for a run saved before 0.9, until the next scan.
        val own = schools.filter { run.school.isEmpty() || it.name.equals(run.school, ignoreCase = true) }
        endWatch = EndWatch(rule, zone, own)
        endWatchFor = rule to run.endZoneId
        return WatchEvent.EndRuleSet(rule, zone, own)
    }

    /**
     * Before each check. First, a change made on the run's page applies at once: the period's
     * minutes (its end, plus any BusPlanner delay) and the end-of-monitoring rule, so a period
     * lengthened there doesn't end at its old end. Then [WatchEvent.PeriodOver] once [now] is
     * past the end of watching.
     */
    fun beforeCheck(run: Run, zones: List<Zone>, schools: List<SchoolSpot>, now: LocalDateTime): List<WatchEvent> {
        val events = ArrayList<WatchEvent>()
        val period = run.periodOn(periodStart.toLocalDate())
        if (period != null) {
            val end = period.end.plusMinutes(delayMin.toLong())
            if (until != end) {
                events += WatchEvent.PeriodChanged(end, until)
                until = end
                periodStart = period.start
            }
            if (endWatchFor != (run.endOfWatch to run.endZoneId)) events += setEndWatch(run, zones, schools)
        }
        if (now.isAfter(until)) events += WatchEvent.PeriodOver
        return events
    }

    /**
     * One check's bus position (null: none). In this order: the heads-up zone, the main zone,
     * then the end of watching; after [WatchEvent.End], the watcher is done.
     *
     * Note: after a failed check, the app passes the previous check's position again (Session.poll
     * keeps the last reading in the status).
     */
    fun check(run: Run, zones: List<Zone>, reading: Reading?): List<WatchEvent> {
        val lat = reading?.busLat ?: return emptyList()
        val lon = reading.busLon ?: return emptyList()
        return listOfNotNull(checkHeadsUp(run, zones, lat, lon), checkZones(run, zones, lat, lon), checkEnd(lat, lon))
    }

    /**
     * The heads-up zone (DESIGN.md §6.1): when the bus enters it, a sound once, beside the run's
     * notification; at most once per period (the Alarm switch off and on lets it sound again).
     * It never stops or replaces the alarm.
     */
    private fun checkHeadsUp(run: Run, zones: List<Zone>, lat: Double, lon: Double): WatchEvent? {
        val zone = headsUpZoneOf(run, zones) ?: return null
        val inside = zone.contains(lat, lon)
        val wasInside = inHeadsUpZone
        inHeadsUpZone = inside
        if (!inside || wasInside) return null
        if (marks.headsUp) return WatchEvent.HeadsUpAlreadySounded(zone)
        marks.markHeadsUp()
        return WatchEvent.HeadsUp(zone)
    }

    /**
     * Rings when the bus enters the run's main zone, once per monitoring period: a restart doesn't
     * ring again, unless the Alarm switch was turned off and on. What ends watching is the run's
     * end-of-monitoring rule (checkEnd).
     */
    private fun checkZones(run: Run, zones: List<Zone>, lat: Double, lon: Double): WatchEvent? {
        val main = mainZoneOf(run, zones) ?: return null
        val inside = main.contains(lat, lon)
        val wasInside = inMainZone
        // Alarm only after the heads-up (user, 2026-11-22): before it, the bus inside the main zone
        // only crosses it. It isn't counted as inside, so the first check inside after the
        // heads-up sounded rings.
        if (inside && !wasInside && !alarmArmed(run, zones, marks.headsUp)) {
            if (blockedLogged) return null
            blockedLogged = true
            return WatchEvent.BlockedBeforeHeadsUp(main)
        }
        if (inside && !wasInside) {
            inMainZone = true
            if (marks.rang) return WatchEvent.AlreadyRang(main)
            marks.markRang()
            return WatchEvent.Ring(main)
        }
        if (!inside && wasInside) {
            inMainZone = false
            return WatchEvent.LeftMainZone(main)
        }
        return null
    }

    /**
     * Ends watching when the run's end-of-monitoring rule says so (DESIGN.md §5.1): e.g. the bus
     * parked at the school (three readings in a row at one spot inside its circle, after it was
     * seen away from it), or the bus leaving the main zone (children dropped off).
     */
    private fun checkEnd(lat: Double, lon: Double): WatchEvent? {
        val reason = endWatch.add(lat, lon) ?: return null
        val school = endWatch.school
        val parkedAt = school?.parkedAt
        val spot = school?.parkedSpot
        return if (parkedAt != null && spot != null) WatchEvent.End(reason, parkedAt, spot) else WatchEvent.End(reason)
    }

    /**
     * A "Delayed N minutes" notice moves the end of watching to the period's planned end + N
     * (DESIGN.md §7.3). The newest notice decides, so 15 then 30 gives +30. Null: nothing changes.
     */
    fun delay(run: Run, minutes: Int): WatchEvent? {
        val planned = run.periodOn(periodStart.toLocalDate())?.end ?: return null
        val end = planned.plusMinutes(minutes.toLong())
        if (until == end) return null
        delayMin = minutes
        until = end
        return WatchEvent.Delayed(minutes, end, planned)
    }
}
