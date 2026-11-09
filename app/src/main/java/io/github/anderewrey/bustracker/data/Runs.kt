// SPDX-FileCopyrightText: 2026 anderewrey
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.anderewrey.bustracker.data

import io.github.anderewrey.bustracker.net.StopRow
import io.github.anderewrey.bustracker.net.Student
import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

const val MONITOR_MINUTES = 30L

private val TIME_IN = DateTimeFormatter.ofPattern("h:mm a", Locale.US)
private val TIME_OUT = DateTimeFormatter.ofPattern("H:mm", Locale.US)

/**
 * One trip of one bus at the stop near home, shared by the students on it.
 * The key is the bus number plus AM/PM, e.g. "B101 AM".
 */
data class Run(
    val key: String,
    val bus: String,
    val stopType: String,
    val time: String,
    val address: String,
    val lat: Double?,
    val lon: Double?,
    val students: List<Student>,
    /**
     * The id of the run's main zone (DESIGN.md §6), kept across rescans like the days, even when
     * the stop moved, until the user picks "New configuration"; null: no main zone, no alarm.
     */
    val mainZone: String? = null,
    /** The days monitoring starts by itself at the period's start; empty means never. */
    val days: Set<DayOfWeek> = WORK_DAYS,
    /**
     * The run's school, as named by its school stop (AM Dropoff or PM Pickup) on the same route;
     * a route serves one school. Empty in runs saved before 0.9, until the next scan.
     */
    val school: String = "",
) {
    val planned: LocalTime? get() = parseTime(time)
    val start: LocalTime? get() = planned?.minusMinutes(MONITOR_MINUTES)
    val end: LocalTime? get() = planned?.plusMinutes(MONITOR_MINUTES)

    /**
     * The children get off the bus at this run's stop (e.g. "PM Dropoff"); otherwise they get on
     * there and off at the school. Tells which rule ends monitoring.
     */
    val isDropoff: Boolean
        get() = stopType.contains("Dropoff", ignoreCase = true)

    /** e.g. "Pickup 7:33". */
    val plannedLine: String
        get() = "${stopType.substringAfter(' ', stopType)} ${planned?.format(TIME_OUT) ?: time}"

    /** e.g. "7:03–8:03"; empty when the planned time is unknown. */
    val periodRange: String
        get() = if (planned == null) "" else "${start!!.format(TIME_OUT)}–${end!!.format(TIME_OUT)}"

    /** e.g. "monitoring 7:03–8:03"; empty when the planned time is unknown. */
    val periodLine: String
        get() = if (planned == null) "" else "monitoring $periodRange"

    /** The whole stop on one line, e.g. "B101 · Pickup 7:33 · 12 THIS ST". */
    val stopLine: String
        get() = listOf(bus, plannedLine, address).filter { it.isNotBlank() }.joinToString(" · ")

    fun isInPeriod(now: LocalTime): Boolean {
        val s = start ?: return false
        val e = end ?: return false
        return !now.isBefore(s) && now.isBefore(e)
    }

    fun toJson(): JSONObject = JSONObject()
        .put("key", key).put("bus", bus).put("stopType", stopType).put("time", time)
        .put("address", address).put("lat", lat ?: JSONObject.NULL).put("lon", lon ?: JSONObject.NULL)
        .put("students", JSONArray(students.map { JSONObject().put("guid", it.guid).put("name", it.name) }))
        .put("mainZone", mainZone ?: JSONObject.NULL)
        .put("days", JSONArray(days.map { it.value }.sorted()))
        .put("school", school)

    companion object {
        fun fromJson(o: JSONObject): Run {
            val s = o.getJSONArray("students")
            return Run(
                key = o.getString("key"),
                bus = o.getString("bus"),
                stopType = o.getString("stopType"),
                time = o.getString("time"),
                address = o.getString("address"),
                lat = if (o.isNull("lat")) null else o.getDouble("lat"),
                lon = if (o.isNull("lon")) null else o.getDouble("lon"),
                students = (0 until s.length()).map { Student(s.getJSONObject(it).getString("guid"), s.getJSONObject(it).getString("name")) },
                // Absent from runs saved by 0.11 and earlier; isNull is also true for a missing key.
                mainZone = if (o.isNull("mainZone")) null else o.getString("mainZone"),
                // Absent from runs saved by 0.6 and earlier: work days.
                days = o.optJSONArray("days")?.let { a -> (0 until a.length()).map { DayOfWeek.of(a.getInt(it)) }.toSet() } ?: WORK_DAYS,
                // Absent from runs saved by 0.8 and earlier.
                school = o.optString("school", ""),
            )
        }
    }
}

fun parseTime(text: String): LocalTime? =
    runCatching { LocalTime.parse(text.trim().uppercase(Locale.US), TIME_IN) }.getOrNull()

/** The stops a parent waits at: the morning pickup and the afternoon dropoff. */
private fun isHomeStop(row: StopRow): Boolean {
    val t = row.stopType.lowercase(Locale.US)
    return ("am" in t && "pickup" in t) || ("pm" in t && "drop" in t)
}

/** The schools, as named by the stops that are not near home (AM dropoff, PM pickup). */
fun schoolNames(scan: Map<Student, List<StopRow>>): Set<String> =
    scan.values.flatten().filterNot(::isHomeStop).map { it.location }.filter { it.isNotBlank() }.toSet()

/** Groups every student's home stops into runs; students on the same bus and half-day share one. */
fun buildRuns(scan: Map<Student, List<StopRow>>): List<Run> {
    val runs = LinkedHashMap<String, Run>()
    for ((student, rows) in scan) {
        for (row in rows.filter(::isHomeStop)) {
            val bus = row.route?.takeIf { it.isNotBlank() } ?: "?"
            val half = half(row)
            val key = "$bus $half"
            // The same child's school stop of the same half-day, on the same route.
            val school = rows.firstOrNull { !isHomeStop(it) && half(it) == half && it.route == row.route }
                ?.location?.trim().orEmpty()
            val existing = runs[key]
            runs[key] = existing?.copy(
                students = existing.students + student,
                school = existing.school.ifEmpty { school },
            ) ?: Run(key, bus, row.stopType, row.time, row.location, row.lat, row.lon, listOf(student), school = school)
        }
    }
    return runs.values.sortedBy { it.planned }
}

private fun half(row: StopRow) = if (row.stopType.trim().uppercase(Locale.US).startsWith("PM")) "PM" else "AM"
