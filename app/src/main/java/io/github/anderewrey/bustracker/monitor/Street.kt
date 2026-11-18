// SPDX-FileCopyrightText: 2026 anderewrey
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.anderewrey.bustracker.monitor

import java.util.Locale

const val FINDING_STREET = "finding street…"
const val STREET_UNKNOWN = "street unknown"

/** Where a run's street lookup stands; it never holds up a check (DESIGN.md §7.2). */
sealed interface StreetState {
    data object Finding : StreetState
    data class Found(val name: String) : StreetState
    data object Unknown : StreetState
}

/** A run's street lookup, for the bus position [at] (see [positionKey]). */
data class StreetLookup(val at: String, val state: StreetState)

/** A bus position as the street lookups key it: to 5 decimals, about 1 m. */
fun positionKey(lat: Double, lon: Double): String = String.format(Locale.US, "%.5f,%.5f", lat, lon)

/** A lookup's answer counts only while the run's bus is still where it was asked for. */
fun stillCurrent(answerFor: String, current: StreetLookup?): Boolean = current?.at == answerFor

/**
 * The status line's place part: the school's name when the bus is at the school (no lookup),
 * else where the street lookup for the current position stands; null with neither.
 */
fun placeText(school: String?, street: StreetState?): String? = school ?: when (street) {
    StreetState.Finding -> FINDING_STREET
    is StreetState.Found -> street.name
    StreetState.Unknown -> STREET_UNKNOWN
    null -> null
}

/** Streets whose candidate is within this distance of the nearest one are all shown. */
const val STREET_MARGIN_M = 25.0

/**
 * One geocoder candidate: its street (thoroughfare), how far its own position is from the point
 * asked (null when the geocoder gave none), and its first address line.
 */
data class Place(val street: String?, val distanceM: Double?, val line: String?)

/**
 * The street text for a bus position (DESIGN.md §7.2), a hint for the user, not a fact. Among
 * the candidates with a street and a position, the nearest one sets d; every street within
 * d + [STREET_MARGIN_M] is shown, nearest first, each name once, joined by " / ". With no such
 * candidate (near a bus stop the first one is often the stop, with no street), the first
 * candidate's address line before its first comma.
 */
fun chooseStreet(places: List<Place>): String? {
    val near = places.mapNotNull { p ->
        val street = p.street?.trim()?.takeIf { it.isNotEmpty() }
        if (street != null && p.distanceM != null) street to p.distanceM else null
    }
    if (near.isEmpty()) return places.firstOrNull()?.line?.substringBefore(',')?.trim()?.takeIf { it.isNotEmpty() }
    val d = near.minOf { it.second }
    return near.filter { it.second < d + STREET_MARGIN_M }.sortedBy { it.second }.map { it.first }.distinct().joinToString(" / ")
}
