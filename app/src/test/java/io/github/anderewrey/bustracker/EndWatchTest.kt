// SPDX-FileCopyrightText: 2026 anderewrey
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.anderewrey.bustracker

import io.github.anderewrey.bustracker.data.EndRule
import io.github.anderewrey.bustracker.data.EndWatch
import io.github.anderewrey.bustracker.data.PeriodChoices
import io.github.anderewrey.bustracker.data.Run
import io.github.anderewrey.bustracker.data.Zone
import io.github.anderewrey.bustracker.data.ZoneRole
import io.github.anderewrey.bustracker.data.moved
import io.github.anderewrey.bustracker.data.periodOn
import io.github.anderewrey.bustracker.data.validMinutes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import org.json.JSONObject

class EndWatchTest {
    // A square around (44.740, -63.300), about 220 m wide.
    private val zone = Zone("z", "Hillside", ZoneRole.MAIN, listOf(44.739 to -63.301, 44.739 to -63.299, 44.741 to -63.299, 44.741 to -63.301))
    private val inside = 44.740 to -63.300
    private val outside = 44.750 to -63.300

    private fun run(type: String = "AM Pickup", time: String = "7:33 AM") =
        Run("B101 AM", "B101", type, time, "12 THIS ST", 44.740, -63.300, emptyList(), mainZone = "z")

    @Test
    fun defaultsFollowTheStopType() {
        assertEquals(EndRule.SCHOOL, run().endOfWatch)
        assertEquals(EndRule.LEAVES_ZONE, run("PM Dropoff", "2:39 PM").endOfWatch)
        assertEquals(EndRule.PERIOD_END, run().copy(endRule = EndRule.PERIOD_END).endOfWatch)
        assertEquals("z", run().endZoneId)
        assertEquals("other", run().copy(endZone = "other").endZoneId)
    }

    @Test
    fun leavingTheZoneOnlyAfterBeingInIt() {
        val w = EndWatch(EndRule.LEAVES_ZONE, zone, emptyList())
        assertNull(w.add(outside.first, outside.second))
        assertNull(w.add(inside.first, inside.second))
        assertEquals("bus left Hillside", w.add(outside.first, outside.second))
    }

    @Test
    fun enteringTheZoneOnlyAfterBeingOutside() {
        val w = EndWatch(EndRule.ENTERS_ZONE, zone, emptyList())
        // Already inside at the start: not an entry.
        assertNull(w.add(inside.first, inside.second))
        assertNull(w.add(outside.first, outside.second))
        assertEquals("bus entered Hillside", w.add(inside.first, inside.second))
    }

    @Test
    fun noZoneOrPeriodEndNeverEndsIt() {
        val none = EndWatch(EndRule.LEAVES_ZONE, null, emptyList())
        val period = EndWatch(EndRule.PERIOD_END, zone, emptyList())
        for (p in listOf(inside, outside, inside, outside)) {
            assertNull(none.add(p.first, p.second))
            assertNull(period.add(p.first, p.second))
        }
    }

    @Test
    fun periodMinutes() {
        val r = run().copy(before = 15, after = 45)
        assertEquals(LocalTime.of(7, 18), r.start)
        assertEquals(LocalTime.of(8, 18), r.end)
        assertEquals("7:18–8:18 AM", r.periodRange)
        // Kept on its day.
        assertEquals(LocalTime.MIN, run(time = "12:20 AM").copy(before = 60).start)
        assertEquals(LocalTime.of(23, 59), run(time = "11:30 PM").copy(after = 60).end)
        assertTrue(validMinutes(0, 10))
        assertFalse(validMinutes(0, 5))
        assertFalse(validMinutes(125, 30))
        assertFalse(validMinutes(-5, 30))
    }

    @Test
    fun configurationIsSavedAndRead() {
        val r = run().copy(before = 20, after = 40, endRule = EndRule.ENTERS_ZONE, endZone = "other")
        assertEquals(r, Run.fromJson(r.toJson()))
        // A run saved before: the defaults.
        val old = JSONObject(run().toJson().toString()).apply { remove("before"); remove("after"); remove("endRule"); remove("endZone") }
        val back = Run.fromJson(old)
        assertEquals(30, back.before)
        assertEquals(30, back.after)
        assertNull(back.endRule)
    }

    @Test
    fun switchChoicesFollowTheNewPeriod() {
        val day = LocalDate.of(2026, 11, 17)
        val old = run()
        val new = old.copy(before = 10)
        val oldStart = old.periodOn(day)!!.start
        val c = PeriodChoices(skipped = mapOf(old.key to oldStart), ended = mapOf(old.key to oldStart.minusDays(1)))
        val m = c.moved(old, new)
        assertEquals(new.periodOn(day)!!.start, m.skipped[old.key])
        assertEquals(new.periodOn(day.minusDays(1))!!.start, m.ended[old.key])
    }
}
