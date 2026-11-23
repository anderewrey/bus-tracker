// SPDX-FileCopyrightText: 2026 anderewrey
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.anderewrey.bustracker.data

import io.github.anderewrey.bustracker.monitor.distanceM

/** Within this distance of the school's position, a parked bus counts as being at the school (the default circle). */
const val AT_SCHOOL_M = 100.0
/** Readings in a row at the same spot, inside that circle, that mean the bus is parked at the school. */
const val PARKED_READINGS = 3
/** Readings closer together than this are the same spot (GPS jitter is a few metres). */
const val SAME_SPOT_M = 15.0

/**
 * Tells, reading after reading, when the bus has reached the school and is parked there.
 * It only counts once the bus was seen away from the school in this monitoring, so a PM run
 * whose bus waits at the school before leaving is not stopped at its start. Schools with no
 * known position are ignored.
 */
class SchoolArrival(private val schools: List<SchoolSpot>, private val needAway: Boolean = true) {
    private var seenAway = false
    private val spots = ArrayList<Pair<Double, Double>>()

    /** The school the bus is parked at, once [add] returned true. */
    var parkedAt: SchoolSpot? = null
        private set

    /** Adds a bus position; true when the last [PARKED_READINGS] were at one spot near a school. */
    fun add(lat: Double, lon: Double): Boolean {
        val school = schools.firstOrNull { s -> s.known && distanceM(lat, lon, s.lat!!, s.lon!!) <= s.radius }
        if (school == null) {
            seenAway = true
            spots.clear()
            return false
        }
        // Learning a parking spot doesn't need it (user, 2026-11-23): a bus waiting at the school
        // before an afternoon run is parked too. Ending watching does.
        if (needAway && !seenAway) return false
        if (spots.isNotEmpty() && distanceM(lat, lon, spots.first().first, spots.first().second) > SAME_SPOT_M) spots.clear()
        spots += lat to lon
        if (spots.size < PARKED_READINGS) return false
        parkedAt = school
        return true
    }

    /** Where the bus parked: the middle of the readings at the spot. */
    val parkedSpot: Pair<Double, Double>?
        get() = if (parkedAt == null || spots.isEmpty()) null else spots.map { it.first }.average() to spots.map { it.second }.average()
}

/**
 * The run's end-of-monitoring rule (DESIGN.md §5.1), reading after reading. [add] returns why
 * watching ends, or null. A zone rule with no zone, and [EndRule.PERIOD_END], never end it: only
 * the Alarm switch and the period's end do.
 */
class EndWatch(val rule: EndRule, private val zone: Zone?, schools: List<SchoolSpot>) {
    val school = if (rule == EndRule.SCHOOL) SchoolArrival(schools) else null
    private var seenInside = false
    private var seenOutside = false

    fun add(lat: Double, lon: Double): String? = when (rule) {
        EndRule.SCHOOL -> if (school!!.add(lat, lon)) "bus parked at the school${school.parkedAt?.let { " (${it.name})" } ?: ""}" else null
        EndRule.LEAVES_ZONE -> zone?.let { z ->
            val inside = z.contains(lat, lon)
            if (inside) seenInside = true
            if (!inside && seenInside) "bus left ${z.name}" else null
        }
        // Like the school rule: only once the bus was seen outside, so a bus already in the zone at the start doesn't end it.
        EndRule.ENTERS_ZONE -> zone?.let { z ->
            val inside = z.contains(lat, lon)
            if (!inside) seenOutside = true
            if (inside && seenOutside) "bus entered ${z.name}" else null
        }
        EndRule.PERIOD_END -> null
    }
}
