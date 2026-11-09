// SPDX-FileCopyrightText: 2026 anderewrey
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.anderewrey.bustracker.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

enum class ZoneRole { HEADS_UP, MAIN }

/**
 * A polygon drawn by the user on the phone, as (lat, lon) corners (DESIGN.md §6). Runs point to
 * it by [id], so one zone can serve several runs (e.g. the AM and PM runs of the same stop).
 */
data class Zone(val id: String, val name: String, val role: ZoneRole, val corners: List<Pair<Double, Double>>) {
    /** Ray casting point-in-polygon; fine in plain lat/lon at neighbourhood scale. */
    fun contains(lat: Double, lon: Double): Boolean {
        var hit = false
        for (i in corners.indices) {
            val (lat1, lon1) = corners[i]
            val (lat2, lon2) = corners[(i + 1) % corners.size]
            if ((lat1 > lat) != (lat2 > lat) && lon < lon1 + (lat - lat1) * (lon2 - lon1) / (lat2 - lat1)) hit = !hit
        }
        return hit
    }

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("role", role.name)
        .put("corners", JSONArray(corners.map { (lat, lon) -> JSONArray().put(lat).put(lon) }))

    companion object {
        fun fromJson(o: JSONObject): Zone {
            val c = o.getJSONArray("corners")
            return Zone(
                id = o.getString("id"),
                name = o.getString("name"),
                role = ZoneRole.entries.firstOrNull { it.name == o.optString("role") } ?: ZoneRole.MAIN,
                corners = (0 until c.length()).map { c.getJSONArray(it).let { p -> p.getDouble(0) to p.getDouble(1) } },
            )
        }
    }
}

/** The run's zones: its main zone, when it has one. Heads-up zones come later. */
fun zonesFor(run: Run, zones: List<Zone>): List<Zone> = listOfNotNull(zones.firstOrNull { it.id == run.mainZone })

/** True when a zone other than [exceptId] already has this name (case and surrounding spaces ignored). */
fun nameTaken(zones: List<Zone>, name: String, exceptId: String? = null): Boolean =
    zones.any { it.id != exceptId && it.name.trim().equals(name.trim(), ignoreCase = true) }

/** The zone a run gets when nothing was chosen for it yet: the first one containing its stop. */
fun defaultZone(run: Run, zones: List<Zone>): String? {
    val lat = run.lat ?: return null
    val lon = run.lon ?: return null
    return zones.firstOrNull { it.contains(lat, lon) }?.id
}

/** The zones, kept apart from the login so logging out doesn't lose what the user drew. */
class ZoneStore(context: Context) {
    private val prefs = context.getSharedPreferences("zones", Context.MODE_PRIVATE)

    var zones: List<Zone>
        get() {
            val a = JSONArray(prefs.getString("zones", "[]"))
            return (0 until a.length()).mapNotNull { runCatching { Zone.fromJson(a.getJSONObject(it)) }.getOrNull() }
        }
        set(value) {
            prefs.edit().putString("zones", JSONArray(value.map { it.toJson() }).toString()).apply()
        }
}
