// SPDX-FileCopyrightText: 2026 anderewrey
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.anderewrey.bustracker.data

import android.content.SharedPreferences
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime

// Small value maps kept in app preferences as JSON, shared by the stores.

/** A map of times by key ({"B101 AM": "2026-11-23T07:30:00"}); an entry that doesn't parse is dropped. */
internal fun SharedPreferences.times(name: String, parse: (String) -> LocalDateTime = LocalDateTime::parse): Map<String, LocalDateTime> {
    val o = JSONObject(getString(name, "{}"))
    return o.keys().asSequence().mapNotNull { k -> runCatching { k to parse(o.getString(k)) }.getOrNull() }.toMap()
}

internal fun SharedPreferences.putTimes(name: String, value: Map<String, LocalDateTime>) {
    edit().putString(name, JSONObject(value.mapValues { it.value.toString() }).toString()).apply()
}

/** A day, or null when absent or unreadable. */
internal fun SharedPreferences.localDate(name: String): LocalDate? =
    getString(name, null)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

internal fun SharedPreferences.putLocalDate(name: String, value: LocalDate?) {
    edit().putString(name, value?.toString()).apply()
}

/** A map of positions by key ({"Sample School": {"lat": …, "lon": …}}). */
internal fun SharedPreferences.latLons(name: String): Map<String, Pair<Double, Double>> {
    val o = JSONObject(getString(name, "{}"))
    return o.keys().asSequence().mapNotNull { k ->
        runCatching { o.getJSONObject(k).let { p -> k to (p.getDouble("lat") to p.getDouble("lon")) } }.getOrNull()
    }.toMap()
}

internal fun SharedPreferences.putLatLons(name: String, value: Map<String, Pair<Double, Double>>) {
    val o = JSONObject()
    value.forEach { (k, v) -> o.put(k, JSONObject().put("lat", v.first).put("lon", v.second)) }
    edit().putString(name, o.toString()).apply()
}
