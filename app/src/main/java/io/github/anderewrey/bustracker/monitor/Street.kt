// SPDX-FileCopyrightText: 2026 anderewrey
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.anderewrey.bustracker.monitor

/** One geocoder candidate: its street (thoroughfare) and its first address line. */
data class Place(val street: String?, val line: String?)

/**
 * The street text for a bus position, from the geocoder's candidates in its own order
 * (DESIGN.md §7.2). A hint for the user, not a fact: every distinct street name among the
 * candidates, the most frequent first (ties in the geocoder's order), joined by " / ". With no
 * street at all (near a bus stop the first candidate is often the stop itself), the first
 * candidate's address line before its first comma.
 */
fun chooseStreet(places: List<Place>): String? {
    val streets = places.mapNotNull { it.street?.trim()?.takeIf(String::isNotEmpty) }
    if (streets.isEmpty()) return places.firstOrNull()?.line?.substringBefore(',')?.trim()?.takeIf { it.isNotEmpty() }
    val counts = streets.groupingBy { it }.eachCount()
    // distinct() keeps the geocoder's order; sortedByDescending is stable, so ties keep it.
    return streets.distinct().sortedByDescending { counts.getValue(it) }.joinToString(" / ")
}
