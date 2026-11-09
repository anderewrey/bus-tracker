// SPDX-FileCopyrightText: 2026 anderewrey
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.anderewrey.bustracker.data

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/** The days a run starts by itself when nothing was configured: Monday to Friday. */
val WORK_DAYS: Set<DayOfWeek> = DayOfWeek.entries.filter { it.value <= 5 }.toSet()

/**
 * The switch over every run's automatic start: on, off, or off until a day (included: runs
 * start by themselves again that day), e.g. for school holidays.
 */
data class AutoStart(val on: Boolean = true, val resumeOn: LocalDate? = null) {
    fun allows(date: LocalDate): Boolean = on || (resumeOn != null && !date.isBefore(resumeOn))

    /** Back on once the resume day has come. */
    fun settled(today: LocalDate): AutoStart = if (!on && resumeOn != null && !today.isBefore(resumeOn)) AutoStart() else this
}

/** One monitoring period of a run, on one day. */
data class Period(val start: LocalDateTime, val end: LocalDateTime) {
    operator fun contains(t: LocalDateTime): Boolean = !t.isBefore(start) && t.isBefore(end)
}

/** The run's monitoring period on [date]; null without a planned time. */
fun Run.periodOn(date: LocalDate): Period? {
    val s = start ?: return null
    val e = end ?: return null
    return Period(date.atTime(s), date.atTime(e))
}

/**
 * The Alarm switch's choices for single periods (DESIGN.md §5), by run key, each kept as the
 * start of the period it is about:
 * - [skipped]: switched off; that period doesn't watch.
 * - [forced]: switched on while the automatic start is off (holidays); that period watches anyway.
 * - [ended]: watching ended by itself (children off the bus, period over); the switch moves on.
 */
data class PeriodChoices(
    val skipped: Map<String, LocalDateTime> = emptyMap(),
    val forced: Map<String, LocalDateTime> = emptyMap(),
    val ended: Map<String, LocalDateTime> = emptyMap(),
)

/**
 * The period the run's Alarm switch is about: the one under way, or else the next one on the
 * run's days. A period that already [ended] by itself is over. Null when the run has no days or
 * no planned time.
 */
fun switchPeriod(run: Run, now: LocalDateTime, ended: LocalDateTime?): Period? =
    (0L..7L).asSequence()
        .map { now.toLocalDate().plusDays(it) }
        .filter { it.dayOfWeek in run.days }
        .mapNotNull { run.periodOn(it) }
        .firstOrNull { it.end.isAfter(now) && it.start != ended }

/**
 * Whether the run watches during [period], one of its days' periods: yes by default, no once
 * switched off; while the automatic start is off, only when switched on.
 */
fun alarmOn(run: Run, period: Period, auto: AutoStart, choices: PeriodChoices): Boolean =
    choices.skipped[run.key] != period.start &&
        (auto.allows(period.start.toLocalDate()) || choices.forced[run.key] == period.start)

/** The next automatic start: when, and which runs start then. */
data class NextStart(val at: LocalDateTime, val keys: List<String>)

/**
 * The earliest monitoring-period start after [now], on one of its run's days, whose Alarm
 * switch is on ([alarmOn]); null when there is none.
 */
fun nextStart(runs: List<Run>, now: LocalDateTime, auto: AutoStart = AutoStart(), choices: PeriodChoices = PeriodChoices()): NextStart? {
    // Looks far enough ahead to reach the resume day, then one more week.
    val ahead = 7L + (auto.resumeOn?.let { ChronoUnit.DAYS.between(now.toLocalDate(), it).coerceAtLeast(0) } ?: 0L)
    val starts = runs.mapNotNull { run ->
        (0L..ahead).asSequence()
            .map { now.toLocalDate().plusDays(it) }
            .filter { it.dayOfWeek in run.days }
            .mapNotNull { run.periodOn(it) }
            .firstOrNull { it.start.isAfter(now) && alarmOn(run, it, auto, choices) }
            ?.let { it.start to run.key }
    }
    val first = starts.minOfOrNull { it.first } ?: return null
    return NextStart(first, starts.filter { it.first == first }.map { it.second })
}

private fun short(d: DayOfWeek) = d.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)

private val RESUME = DateTimeFormatter.ofPattern("EEE MMM d", Locale.ENGLISH)

/** e.g. "on", "off", "off until Mon Nov 9". */
fun autoText(auto: AutoStart): String = when {
    auto.on -> "on"
    auto.resumeOn != null -> "off until ${auto.resumeOn.format(RESUME)}"
    else -> "off"
}

/** e.g. "Mon–Fri", "Every day", "Mon, Wed, Fri", "Never". */
fun daysText(days: Set<DayOfWeek>): String {
    if (days.isEmpty()) return "Never"
    if (days.size == 7) return "Every day"
    val sorted = days.sortedBy { it.value }
    // Runs of consecutive days, three or more shown as a range.
    val groups = ArrayList<MutableList<DayOfWeek>>()
    for (d in sorted) {
        val last = groups.lastOrNull()
        if (last != null && last.last().value + 1 == d.value) last += d else groups += mutableListOf(d)
    }
    return groups.joinToString(", ") { g -> if (g.size >= 3) "${short(g.first())}–${short(g.last())}" else g.joinToString(", ") { short(it) } }
}
