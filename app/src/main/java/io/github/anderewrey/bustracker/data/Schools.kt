// SPDX-FileCopyrightText: 2026 anderewrey
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.anderewrey.bustracker.data

import android.content.Context
import io.github.anderewrey.bustracker.net.School
import org.json.JSONObject
import java.time.LocalDateTime
import java.text.Normalizer
import java.util.Locale

/** The smallest and largest "at school" circle the School page allows, in metres (DESIGN.md §5.3). */
const val MIN_SCHOOL_RADIUS_M = 25.0
const val MAX_SCHOOL_RADIUS_M = 300.0

/**
 * The user's correction of one school, by the name its stops have on BusPlanner. It belongs to the
 * school, so every run going there uses it, and rescans never overwrite it.
 * - [lat], [lon]: where the bus really parks; null: BusPlanner's position.
 * - [radius]: the "at school" circle.
 * - [match]: the school chosen in BusPlanner's school list when its name matched several.
 */
data class SchoolFix(
    val name: String,
    val lat: Double? = null,
    val lon: Double? = null,
    val radius: Double = AT_SCHOOL_M,
    val match: String? = null,
) {
    val moved: Boolean get() = lat != null && lon != null

    fun toJson(): JSONObject = JSONObject()
        .put("lat", lat ?: JSONObject.NULL).put("lon", lon ?: JSONObject.NULL)
        .put("radius", radius).put("match", match ?: JSONObject.NULL)

    companion object {
        fun fromJson(name: String, o: JSONObject) = SchoolFix(
            name = name,
            lat = if (o.isNull("lat")) null else o.getDouble("lat"),
            lon = if (o.isNull("lon")) null else o.getDouble("lon"),
            radius = o.optDouble("radius", AT_SCHOOL_M),
            match = if (o.isNull("match")) null else o.getString("match"),
        )
    }
}

enum class PositionSource(val label: String) {
    BUSPLANNER("BusPlanner"),
    CORRECTED("corrected"),
    UNKNOWN("position unknown"),
}

/** A school as the app uses it: BusPlanner's position or the user's correction, and its circle. */
data class SchoolSpot(val name: String, val lat: Double?, val lon: Double?, val radius: Double, val source: PositionSource) {
    val known: Boolean get() = lat != null && lon != null
}

/**
 * Every school the app knows ([names]: the runs' schools; and those BusPlanner gave a position
 * for), each with the position to use: the correction first, then BusPlanner's.
 */
fun schoolSpots(names: Collection<String>, busPlanner: List<School>, fixes: Map<String, SchoolFix>): List<SchoolSpot> {
    val all = LinkedHashSet<String>()
    names.filter { it.isNotBlank() }.forEach { all += it }
    busPlanner.forEach { all += it.name }
    return all.map { name ->
        val fix = fixes.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
        val bp = busPlanner.firstOrNull { it.name.equals(name, ignoreCase = true) }
        when {
            fix != null && fix.moved -> SchoolSpot(name, fix.lat, fix.lon, fix.radius, PositionSource.CORRECTED)
            bp != null -> SchoolSpot(name, bp.lat, bp.lon, fix?.radius ?: AT_SCHOOL_M, PositionSource.BUSPLANNER)
            else -> SchoolSpot(name, null, null, fix?.radius ?: AT_SCHOOL_M, PositionSource.UNKNOWN)
        }
    }
}

/** A closed ring of points around (lat, lon) at [radius] metres, to draw the "at school" circle. */
fun circlePoints(lat: Double, lon: Double, radius: Double, steps: Int = 64): List<Pair<Double, Double>> {
    val dLat = radius / 111_320.0
    val dLon = radius / (111_320.0 * Math.cos(Math.toRadians(lat)))
    return (0..steps).map { i ->
        val a = 2 * Math.PI * i / steps
        lat + dLat * Math.cos(a) to lon + dLon * Math.sin(a)
    }
}

/** Lower case, no accents, single spaces: "École  Mer et Monde" and "ecole mer et monde" are the same. */
fun plainName(s: String): String =
    Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
        .lowercase(Locale.ROOT).replace(Regex("\\s+"), " ").trim()

/** How a school's name was found in BusPlanner's school list. */
sealed class SchoolMatch {
    /** One school to use: the one chosen before, the only exact name, or the only name containing it. */
    data class Found(val option: String) : SchoolMatch()

    /** Several possible schools: no guess, the user chooses (School page). */
    data class Several(val options: List<String>) : SchoolMatch()

    object None : SchoolMatch()
}

/**
 * Finds [name] in BusPlanner's school list ([options], as shown there), ignoring case and accents
 * (DESIGN.md §5.3). [chosen] is the user's earlier choice, used while the list still has it.
 */
fun matchSchool(options: List<String>, name: String, chosen: String? = null): SchoolMatch {
    chosen?.let { c -> options.firstOrNull { it.trim() == c.trim() }?.let { return SchoolMatch.Found(it) } }
    val want = plainName(name)
    if (want.isEmpty()) return SchoolMatch.None
    val exact = options.filter { plainName(it) == want }
    if (exact.size == 1) return SchoolMatch.Found(exact[0])
    if (exact.size > 1) return SchoolMatch.Several(exact)
    val near = options.filter { o -> plainName(o).let { it.isNotEmpty() && (it.contains(want) || want.contains(it)) } }
    return when (near.size) {
        0 -> SchoolMatch.None
        1 -> SchoolMatch.Found(near[0])
        else -> SchoolMatch.Several(near)
    }
}

/**
 * The schools' corrections and where the bus was last seen parked at each, kept apart from the
 * login like the zones: logging out keeps them.
 */
class SchoolStore(context: Context) {
    private val prefs = context.getSharedPreferences("schools", Context.MODE_PRIVATE)

    var fixes: Map<String, SchoolFix>
        get() {
            val o = JSONObject(prefs.getString("fixes", "{}"))
            return o.keys().asSequence().mapNotNull { k -> runCatching { k to SchoolFix.fromJson(k, o.getJSONObject(k)) }.getOrNull() }.toMap()
        }
        set(value) {
            val o = JSONObject()
            value.forEach { (k, v) -> o.put(k, v.toJson()) }
            prefs.edit().putString("fixes", o.toString()).apply()
        }

    /** Where the "parked at the school" rule last saw the bus stop, by school name. */
    var parked: Map<String, Pair<Double, Double>>
        get() {
            val o = JSONObject(prefs.getString("parked", "{}"))
            return o.keys().asSequence().mapNotNull { k ->
                runCatching { o.getJSONObject(k).let { p -> k to (p.getDouble("lat") to p.getDouble("lon")) } }.getOrNull()
            }.toMap()
        }
        set(value) {
            val o = JSONObject()
            value.forEach { (k, v) -> o.put(k, JSONObject().put("lat", v.first).put("lon", v.second)) }
            prefs.edit().putString("parked", o.toString()).apply()
        }

    /** When the bus was last seen parked at each school; absent for spots learned before 2026-11-23. */
    var parkedAt: Map<String, LocalDateTime>
        get() {
            val o = JSONObject(prefs.getString("parkedAt", "{}"))
            return o.keys().asSequence().mapNotNull { k -> runCatching { k to LocalDateTime.parse(o.getString(k)) }.getOrNull() }.toMap()
        }
        set(value) {
            prefs.edit().putString("parkedAt", JSONObject(value.mapValues { it.value.toString() }).toString()).apply()
        }
}
