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

/** Default minutes watched before and after the planned stop time. */
const val MONITOR_MINUTES = 30
/** The minutes before and after can each be set from 0 to this (DESIGN.md §5.1). */
const val MAX_MONITOR_MINUTES = 120

/** What ends watching a run by itself, besides the Alarm switch and the period's end (DESIGN.md §5.1). */
enum class EndRule(val label: String) {
    SCHOOL("Bus parked at the school"),
    LEAVES_ZONE("Bus leaves a zone"),
    ENTERS_ZONE("Bus enters a zone"),
    PERIOD_END("Only at the end of the period"),
}

private val TIME_IN = DateTimeFormatter.ofPattern("h:mm a", Locale.US)

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
    /** Minutes watched before the planned stop time. */
    val before: Int = MONITOR_MINUTES,
    /** Minutes watched after the planned stop time. */
    val after: Int = MONITOR_MINUTES,
    /** What ends watching by itself; null: the stop type's default ([endOfWatch]). */
    val endRule: EndRule? = null,
    /** The zone of [EndRule.LEAVES_ZONE] or [EndRule.ENTERS_ZONE]; null: the run's main zone. */
    val endZone: String? = null,
    /**
     * The run's heads-up zone (DESIGN.md §6.1, user 2026-11-22): a sound once when the bus enters
     * it; null: none. Kept across rescans like the main zone.
     */
    val headsUpZone: String? = null,
    /** The main zone's alarm rings only once the heads-up zone has sounded in this period. */
    val alarmAfterHeadsUp: Boolean = false,
) {
    val planned: LocalTime? get() = parseTime(time)
    /** The period's start; never before midnight (the period stays on its day). */
    val start: LocalTime? get() = planned?.let { p -> if (p.toSecondOfDay() / 60 < before) LocalTime.MIN else p.minusMinutes(before.toLong()) }
    /** The period's end; never past 23:59. */
    val end: LocalTime? get() = planned?.let { p -> if (p.toSecondOfDay() / 60 + after > 24 * 60 - 1) LocalTime.of(23, 59) else p.plusMinutes(after.toLong()) }

    /** The rule that ends watching: the one chosen, or the stop type's default (pickup: school, dropoff: leaving the main zone). */
    val endOfWatch: EndRule get() = endRule ?: if (isDropoff) EndRule.LEAVES_ZONE else EndRule.SCHOOL

    /** The zone [endOfWatch] is about, for a zone rule. */
    val endZoneId: String? get() = endZone ?: mainZone

    /**
     * The children get off the bus at this run's stop (e.g. "PM Dropoff"); otherwise they get on
     * there and off at the school. Tells which rule ends monitoring.
     */
    val isDropoff: Boolean
        get() = stopType.contains("Dropoff", ignoreCase = true)

    /** e.g. "Pickup 7:33 AM". */
    val plannedLine: String
        get() = "${stopType.substringAfter(' ', stopType)} ${planned?.let { clock(it) } ?: time}"

    /** e.g. "7:03–8:03 AM"; empty when the planned time is unknown. */
    val periodRange: String
        get() = if (planned == null) "" else clockRange(start!!, end!!)

    /** e.g. "watching 7:03–8:03 AM"; empty when the planned time is unknown. */
    val periodLine: String
        get() = if (planned == null) "" else "watching $periodRange"

    /** The whole stop on one line, e.g. "B101 · Pickup 7:33 AM · 12 THIS ST". */
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
        .put("students", JSONArray(students.map { JSONObject().put("guid", it.guid) }))
        .put("mainZone", mainZone ?: JSONObject.NULL)
        .put("days", JSONArray(days.map { it.value }.sorted()))
        .put("school", school)
        .put("before", before).put("after", after)
        .put("endRule", endRule?.name ?: JSONObject.NULL)
        .put("endZone", endZone ?: JSONObject.NULL)
        .put("headsUpZone", headsUpZone ?: JSONObject.NULL)
        .put("alarmAfterHeadsUp", alarmAfterHeadsUp)

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
                // Runs saved before 2026-11-22 also have a name: ignored, and dropped at the next save.
                students = (0 until s.length()).map { Student(s.getJSONObject(it).getString("guid")) },
                // Absent from runs saved by 0.11 and earlier; isNull is also true for a missing key.
                mainZone = if (o.isNull("mainZone")) null else o.getString("mainZone"),
                // Absent from runs saved by 0.6 and earlier: work days.
                days = o.optJSONArray("days")?.let { a -> (0 until a.length()).map { DayOfWeek.of(a.getInt(it)) }.toSet() } ?: WORK_DAYS,
                // Absent from runs saved by 0.8 and earlier.
                school = o.optString("school", ""),
                // Absent from runs saved before 2026-11-17: the defaults.
                before = o.optInt("before", MONITOR_MINUTES),
                after = o.optInt("after", MONITOR_MINUTES),
                endRule = if (o.isNull("endRule")) null else EndRule.entries.firstOrNull { it.name == o.getString("endRule") },
                endZone = if (o.isNull("endZone")) null else o.getString("endZone"),
                // Absent from runs saved before 2026-11-22: no heads-up zone.
                headsUpZone = if (o.isNull("headsUpZone")) null else o.getString("headsUpZone"),
                alarmAfterHeadsUp = o.optBoolean("alarmAfterHeadsUp", false),
            )
        }
    }
}

/** The shortest period allowed, so before and after can't both be 0. */
const val MIN_PERIOD_MINUTES = 10

/** Whether these minutes before and after make a valid period. */
fun validMinutes(before: Int, after: Int): Boolean =
    before in 0..MAX_MONITOR_MINUTES && after in 0..MAX_MONITOR_MINUTES && before + after >= MIN_PERIOD_MINUTES

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
