// SPDX-FileCopyrightText: 2026 anderewrey
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.anderewrey.bustracker.monitor

/** One geocoder candidate: its street (thoroughfare) and its first address line. */
data class Place(val street: String?, val line: String?)

/**
 * The street to show for a bus position, from the geocoder's candidates in its own order
 * (DESIGN.md §7.2). Near a bus stop the first candidate is often the stop itself, with no street
 * and a line like "This St After That St (1234), Town…"; the next ones are houses, often on the
 * cross street. The bus is on the street a stop's name gives first, so among the candidates'
 * streets the one whose name shows up earliest in the stop's name wins; with none in it, the
 * first street; with no street at all, the stop's name (the line before its first comma).
 */
fun chooseStreet(places: List<Place>): String? {
    val first = places.firstOrNull() ?: return null
    first.street?.takeIf { it.isNotBlank() }?.let { return it }
    val label = first.line?.substringBefore(',')?.trim()?.takeIf { it.isNotEmpty() }
    val streets = places.mapNotNull { it.street?.takeIf(String::isNotBlank) }.distinct()
    if (streets.isEmpty()) return label
    if (label != null) {
        streets.mapNotNull { s -> positionIn(label, s)?.let { s to it } }.minByOrNull { it.second }?.let { return it.first }
    }
    return streets.first()
}

/**
 * Where the street's name first shows up in [label], by its words of 4 letters or more (whole
 * words, any case): short words ("St", "Rue", "The") and street types written out in one and
 * abbreviated in the other ("Street" / "St") would match anything or nothing.
 */
private fun positionIn(label: String, street: String): Int? =
    street.split(' ', '-').filter { it.length >= 4 }
        .mapNotNull { word -> Regex("""\b${Regex.escape(word)}\b""", RegexOption.IGNORE_CASE).find(label)?.range?.first }
        .minOrNull()
