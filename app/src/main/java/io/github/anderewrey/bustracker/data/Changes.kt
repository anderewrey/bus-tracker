// SPDX-FileCopyrightText: 2026 anderewrey
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.anderewrey.bustracker.data

import io.github.anderewrey.bustracker.monitor.distanceM
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDateTime
import java.util.Locale

/** A stop whose position moved less than this, with the same address, is the same stop. */
const val SAME_STOP_M = 30.0

enum class ChangeKind(val label: String) {
    TIME("time changed"),
    STOP("stop moved"),
    BUS("bus replaced"),
    STUDENTS("children changed"),
    SCHOOL("school changed"),
    ADDED("new run"),
    REMOVED("run removed"),
}

/**
 * What a rescan found different for one run, shown as the whole past stop and the whole new one.
 * A moved stop or a replacing run waits for the user to keep its configuration or start a new one.
 */
data class RunChange(
    val id: String,
    val found: LocalDateTime,
    val past: Run?,
    val new: Run?,
    val kinds: Set<ChangeKind>,
) {
    val needsChoice: Boolean
        get() = past != null && new != null && (ChangeKind.STOP in kinds || ChangeKind.BUS in kinds)

    /** The run only: the screen shows the whole stops without picking out which field changed. */
    val title: String
        get() = (new ?: past)!!.key

    /** For the log, e.g. "time changed, stop moved". */
    val kindsText: String
        get() = kinds.sortedBy { it.ordinal }.joinToString(", ") { it.label }

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("found", found.toString())
        .put("past", past?.toJson() ?: JSONObject.NULL).put("new", new?.toJson() ?: JSONObject.NULL)
        .put("kinds", JSONArray(kinds.map { it.name }))

    companion object {
        fun fromJson(o: JSONObject): RunChange {
            val k = o.getJSONArray("kinds")
            return RunChange(
                id = o.getString("id"),
                found = LocalDateTime.parse(o.getString("found")),
                past = if (o.isNull("past")) null else Run.fromJson(o.getJSONObject("past")),
                new = if (o.isNull("new")) null else Run.fromJson(o.getJSONObject("new")),
                kinds = (0 until k.length()).mapNotNull { i -> ChangeKind.entries.firstOrNull { it.name == k.getString(i) } }.toSet(),
            )
        }
    }
}

private fun half(run: Run) = run.key.substringAfterLast(' ')

private fun guids(run: Run) = run.students.map { it.guid }.toSet()

fun stopMoved(past: Run, new: Run): Boolean {
    if (!past.address.trim().equals(new.address.trim(), ignoreCase = true)) return true
    if (past.lat == null || past.lon == null || new.lat == null || new.lon == null) return false
    return distanceM(past.lat, past.lon, new.lat, new.lon) > SAME_STOP_M
}

/** What differs between the past and the new version of a run; empty when nothing does. */
fun changeKinds(past: Run?, new: Run?): Set<ChangeKind> = when {
    past == null && new == null -> emptySet()
    past == null -> setOf(ChangeKind.ADDED)
    new == null -> setOf(ChangeKind.REMOVED)
    else -> buildSet {
        if (past.key != new.key) add(ChangeKind.BUS)
        if (past.planned != new.planned || (past.planned == null && past.time != new.time)) add(ChangeKind.TIME)
        if (stopMoved(past, new)) add(ChangeKind.STOP)
        if (guids(past) != guids(new)) add(ChangeKind.STUDENTS)
        // A run saved before 0.9 has no school yet: getting one is not a change.
        if (past.school.isNotEmpty() && new.school.isNotEmpty() && !past.school.equals(new.school, ignoreCase = true)) add(ChangeKind.SCHOOL)
    }
}

/**
 * Compares the saved runs with a fresh scan. Runs are matched by key (bus and AM/PM); a run that
 * disappeared is matched with a new run of the same half-day carrying one of its children, as the
 * bus that replaces it.
 */
fun diffRuns(past: List<Run>, new: List<Run>, found: LocalDateTime): List<RunChange> {
    val newByKey = new.associateBy { it.key }
    val pastKeys = past.map { it.key }.toSet()
    val unmatched = new.filter { it.key !in pastKeys }.toMutableList()
    val pairs = ArrayList<Pair<Run?, Run?>>()
    for (p in past) {
        val n = newByKey[p.key] ?: unmatched.firstOrNull { half(it) == half(p) && (guids(it) intersect guids(p)).isNotEmpty() }
        unmatched.remove(n)
        pairs += p to n
    }
    unmatched.forEach { pairs += null to it }
    return pairs.mapIndexedNotNull { i, (p, n) ->
        val kinds = changeKinds(p, n)
        if (kinds.isEmpty()) null else RunChange("${found}#$i", found, p, n, kinds)
    }.sortedBy { (it.new ?: it.past)!!.planned }
}

/**
 * Adds a rescan's changes to those still waiting on the Changes screen. When a waiting change
 * already led to the run that changed again, both become one change from the oldest past to the
 * latest new; one that went back to its past values disappears.
 */
fun mergeChanges(waiting: List<RunChange>, fresh: List<RunChange>): List<RunChange> {
    val out = waiting.toMutableList()
    for (c in fresh) {
        val pastKey = c.past?.key
        val i = if (pastKey == null) -1 else out.indexOfFirst { it.new?.key == pastKey }
        if (i < 0) {
            out += c
            continue
        }
        val first = out.removeAt(i)
        val kinds = changeKinds(first.past, c.new)
        if (kinds.isNotEmpty()) out.add(i, c.copy(past = first.past, kinds = kinds))
    }
    return out
}

/**
 * The new runs as saved after a rescan. A run keeps its configuration (days and main zone), also
 * when its stop moved or its bus was replaced, so a rescan never changes the alarm by itself;
 * "New configuration" on the Changes screen resets it. A run seen for the first time gets the
 * zone that contains its stop, if any ([defaultZone]).
 */
fun carryConfiguration(new: List<Run>, changes: List<RunChange>, past: List<Run>, zones: List<Zone> = emptyList()): List<Run> {
    val pastByKey = past.associateBy { it.key }
    val changedBy = changes.filter { it.new != null }.associateBy { it.new!!.key }
    return new.map { run ->
        val c = changedBy[run.key]
        val from = c?.past ?: pastByKey[run.key] ?: return@map run.copy(mainZone = defaultZone(run, zones))
        run.copy(days = from.days, mainZone = from.mainZone)
    }
}

/** A short log line for a change. */
fun RunChange.logLine(): String =
    String.format(Locale.US, "change %s (%s): past [%s] new [%s]", title, kindsText, past?.stopLine ?: "none", new?.stopLine ?: "none")
