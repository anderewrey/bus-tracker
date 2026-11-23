// SPDX-FileCopyrightText: 2026 anderewrey
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.anderewrey.bustracker.replay

import io.github.anderewrey.bustracker.data.PositionSource
import io.github.anderewrey.bustracker.data.SchoolSpot
import io.github.anderewrey.bustracker.data.Zone
import org.json.JSONObject
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/** A test resource under `app/src/test/resources/replay/`. */
internal fun resource(name: String): String =
    requireNotNull(TestMap::class.java.getResourceAsStream("/replay/$name")) { "no test resource replay/$name" }
        .bufferedReader().use { it.readText() }

/**
 * The test map (`test-map.json`, DESIGN.md §8, Tests): a made-up stop, zones and school around
 * This Street at Porters Lake, Nova Scotia.
 */
object TestMap {
    private val json = JSONObject(resource("test-map.json"))

    /** The stop at This Street & The Other Street. */
    val stop: Pair<Double, Double> = json.getJSONObject("stop").let { it.getDouble("lat") to it.getDouble("lon") }
    val address: String = json.getJSONObject("stop").getString("address")

    val zones: List<Zone> = json.getJSONArray("zones").let { a -> (0 until a.length()).map { Zone.fromJson(a.getJSONObject(it)) } }
    /** Home zone, the main zone: This Street, the loop, and the corner of Highway 7. */
    val home: Zone = zones.single { it.id == "home" }
    /** Highway 7 west, the heads-up zone. */
    val headsUp: Zone = zones.single { it.id == "hwy7" }

    val schools: List<SchoolSpot> = json.getJSONArray("schools").let { a ->
        (0 until a.length()).map {
            val s = a.getJSONObject(it)
            SchoolSpot(s.getString("name"), s.getDouble("lat"), s.getDouble("lon"), s.getDouble("radius"), PositionSource.BUSPLANNER)
        }
    }
}

/**
 * One BusPlanner check of a trip: when the app checked, the bus's GPS time ("Latest Reported
 * Time", 0 to 60 s behind), where it was, and where that is in words.
 */
data class Fix(val check: LocalTime, val gps: LocalTime, val lat: Double, val lon: Double, val where: String)

private val HMS = DateTimeFormatter.ofPattern("HH:mm:ss")

/**
 * A trip file (`*.trip`): one check per line, `check gps lat lon where`; `#` starts a comment.
 * A `[zone]` at the end of a line is only a note for the reader.
 */
fun loadTrip(name: String): List<Fix> = resource(name).lines()
    .map { it.substringBefore('#').trim() }
    .filter { it.isNotEmpty() }
    .map { line ->
        val f = line.split(Regex("\\s+"), limit = 5)
        Fix(LocalTime.parse(f[0], HMS), LocalTime.parse(f[1], HMS), f[2].toDouble(), f[3].toDouble(), f.getOrElse(4) { "" }.substringBefore(" [").trim())
    }
