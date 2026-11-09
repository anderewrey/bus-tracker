// SPDX-FileCopyrightText: 2026 anderewrey
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.anderewrey.bustracker

import io.github.anderewrey.bustracker.data.AutoStart
import io.github.anderewrey.bustracker.data.Run
import io.github.anderewrey.bustracker.data.WORK_DAYS
import io.github.anderewrey.bustracker.data.autoText
import java.time.LocalDate
import io.github.anderewrey.bustracker.data.carryConfiguration
import io.github.anderewrey.bustracker.data.daysText
import io.github.anderewrey.bustracker.data.diffRuns
import io.github.anderewrey.bustracker.data.PeriodChoices
import io.github.anderewrey.bustracker.data.alarmOn
import io.github.anderewrey.bustracker.data.nextStart
import io.github.anderewrey.bustracker.data.periodOn
import io.github.anderewrey.bustracker.data.switchPeriod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.DayOfWeek.FRIDAY
import java.time.DayOfWeek.MONDAY
import java.time.DayOfWeek.SATURDAY
import java.time.DayOfWeek.SUNDAY
import java.time.DayOfWeek.WEDNESDAY
import java.time.LocalDateTime

class ScheduleTest {
    private fun run(key: String, time: String, days: Set<DayOfWeek> = WORK_DAYS) =
        Run(key, "B101", if (key.endsWith("AM")) "AM Pickup" else "PM Dropoff", time, "THIS ST & THAT ST", 44.7381, -63.3044, emptyList(), days = days)

    private val am = run("B101 AM", "7:33 AM")
    private val pm = run("B101 PM", "2:39 PM")

    // 2026-11-04 is a Wednesday.
    private fun at(day: Int, h: Int, m: Int) = LocalDateTime.of(2026, 11, day, h, m)

    @Test
    fun sameDayAfternoon() {
        val n = nextStart(listOf(am, pm), at(4, 13, 50))!!
        assertEquals(at(4, 14, 9), n.at)
        assertEquals(listOf("B101 PM"), n.keys)
    }

    @Test
    fun nextMorning() {
        assertEquals(at(5, 7, 3), nextStart(listOf(am, pm), at(4, 14, 9))!!.at)
    }

    @Test
    fun fridayEveningGoesToMonday() {
        assertEquals(at(9, 7, 3), nextStart(listOf(am, pm), at(6, 18, 0))!!.at)
    }

    @Test
    fun ownDaysPerRun() {
        val n = nextStart(listOf(am.copy(days = setOf(FRIDAY)), pm.copy(days = setOf(SATURDAY))), at(4, 13, 0))!!
        assertEquals(at(6, 7, 3), n.at)
    }

    @Test
    fun noDaysNoStart() {
        assertNull(nextStart(listOf(am.copy(days = emptySet())), at(4, 13, 0)))
    }

    @Test
    fun daysLabels() {
        assertEquals("Mon–Fri", daysText(WORK_DAYS))
        assertEquals("Every day", daysText(DayOfWeek.entries.toSet()))
        assertEquals("Mon, Wed, Fri", daysText(setOf(MONDAY, WEDNESDAY, FRIDAY)))
        assertEquals("Sat, Sun", daysText(setOf(SATURDAY, SUNDAY)))
        assertEquals("Never", daysText(emptySet()))
    }

    @Test
    fun switchedOffNoStart() {
        assertNull(nextStart(listOf(am, pm), at(4, 13, 0), AutoStart(on = false)))
    }

    @Test
    fun offUntilResumesThatMorning() {
        // Off until Monday Nov 16: nothing this week nor next week, then Monday 7:03.
        val auto = AutoStart(on = false, resumeOn = LocalDate.of(2026, 11, 16))
        assertEquals(at(16, 7, 3), nextStart(listOf(am, pm), at(4, 13, 0), auto)!!.at)
        assertEquals("off until Mon Nov 16", autoText(auto))
    }

    @Test
    fun offUntilIsBackOnThatDay() {
        val auto = AutoStart(on = false, resumeOn = LocalDate.of(2026, 11, 9))
        assertEquals(auto, auto.settled(LocalDate.of(2026, 11, 8)))
        assertEquals(AutoStart(), auto.settled(LocalDate.of(2026, 11, 9)))
    }

    @Test
    fun switchIsAboutThePeriodUnderWayThenTheNextOne() {
        assertEquals(at(4, 7, 3), switchPeriod(am, at(4, 6, 0), null)!!.start)
        assertEquals(at(4, 7, 3), switchPeriod(am, at(4, 7, 40), null)!!.start)
        // Over at 8:03, or earlier once watching ended by itself (bus parked at the school).
        assertEquals(at(5, 7, 3), switchPeriod(am, at(4, 8, 3), null)!!.start)
        assertEquals(at(5, 7, 3), switchPeriod(am, at(4, 7, 40), ended = at(4, 7, 3))!!.start)
        // Friday afternoon: Monday is next.
        assertEquals(at(9, 7, 3), switchPeriod(am, at(6, 15, 0), null)!!.start)
        assertNull(switchPeriod(am.copy(days = emptySet()), at(4, 6, 0), null))
    }

    @Test
    fun switchedOffSkipsThatPeriodOnly() {
        val off = PeriodChoices(skipped = mapOf(am.key to at(5, 7, 3)))
        assertFalse(alarmOn(am, am.periodOn(LocalDate.of(2026, 11, 5))!!, AutoStart(), off))
        assertTrue(alarmOn(am, am.periodOn(LocalDate.of(2026, 11, 6))!!, AutoStart(), off))
        // The automatic start goes to the afternoon run, then Friday morning.
        assertEquals(listOf(pm.key), nextStart(listOf(am, pm), at(4, 18, 0), AutoStart(), off)!!.keys)
        assertEquals(at(6, 7, 3), nextStart(listOf(am), at(4, 18, 0), AutoStart(), off)!!.at)
    }

    @Test
    fun switchedOnDuringHolidaysWatchesThatPeriodOnly() {
        val holidays = AutoStart(on = false)
        val on = PeriodChoices(forced = mapOf(am.key to at(5, 7, 3)))
        assertFalse(alarmOn(am, am.periodOn(LocalDate.of(2026, 11, 5))!!, holidays, PeriodChoices()))
        assertTrue(alarmOn(am, am.periodOn(LocalDate.of(2026, 11, 5))!!, holidays, on))
        assertEquals(at(5, 7, 3), nextStart(listOf(am, pm), at(4, 18, 0), holidays, on)!!.at)
        assertNull(nextStart(listOf(am, pm), at(5, 9, 0), holidays, on))
    }

    @Test
    fun daysSurviveATimeChange() {
        val past = listOf(am.copy(days = setOf(MONDAY)))
        val scanned = listOf(run("B101 AM", "7:41 AM"))
        val saved = carryConfiguration(scanned, diffRuns(past, scanned, at(4, 13, 0)), past)
        assertEquals(setOf(MONDAY), saved[0].days)
    }
}
