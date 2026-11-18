// SPDX-FileCopyrightText: 2026 anderewrey
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.anderewrey.bustracker.monitor

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
