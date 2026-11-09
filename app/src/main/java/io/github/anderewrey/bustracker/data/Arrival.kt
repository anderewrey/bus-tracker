// SPDX-FileCopyrightText: 2026 anderewrey
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.anderewrey.bustracker.data

import io.github.anderewrey.bustracker.monitor.distanceM

/** Within this distance of the school's position, a parked bus counts as being at the school. */
const val AT_SCHOOL_M = 100.0
/** Readings in a row at the same spot, inside that circle, that mean the bus is parked at the school. */
const val PARKED_READINGS = 3
/** Readings closer together than this are the same spot (GPS jitter is a few metres). */
const val SAME_SPOT_M = 15.0

/**
 * Tells, reading after reading, when the bus has reached the school and is parked there.
 * It only counts once the bus was seen away from the school in this monitoring, so a PM run
 * whose bus waits at the school before leaving is not stopped at its start.
 */
class SchoolArrival(private val schools: List<Pair<Double, Double>>) {
    private var seenAway = false
    private val spots = ArrayList<Pair<Double, Double>>()

    /** Adds a bus position; true when the last [PARKED_READINGS] were at one spot near a school. */
    fun add(lat: Double, lon: Double): Boolean {
        val atSchool = schools.any { (sLat, sLon) -> distanceM(lat, lon, sLat, sLon) <= AT_SCHOOL_M }
        if (!atSchool) {
            seenAway = true
            spots.clear()
            return false
        }
        if (!seenAway) return false
        if (spots.isNotEmpty() && distanceM(lat, lon, spots.first().first, spots.first().second) > SAME_SPOT_M) spots.clear()
        spots += lat to lon
        return spots.size >= PARKED_READINGS
    }
}
