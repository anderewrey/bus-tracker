// SPDX-FileCopyrightText: 2026 anderewrey
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.anderewrey.bustracker.replay

import io.github.anderewrey.bustracker.data.ZoneRole
import io.github.anderewrey.bustracker.monitor.distanceM
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The test map and the morning trip agree with each other (DESIGN.md §8, Trip replays). */
class TestMapTest {
    private val morning = loadTrip("morning.trip")

    @Test
    fun theZonesAreWhereTheSetupSays() {
        assertEquals(ZoneRole.MAIN, TestMap.home.role)
        assertEquals(ZoneRole.HEADS_UP, TestMap.headsUp.role)
        // The stop, This Street at Highway 7, and the loop's far end are in Home zone.
        assertTrue(TestMap.home.contains(TestMap.stop.first, TestMap.stop.second))
        assertTrue(TestMap.home.contains(44.738723, -63.305718))
        assertTrue(TestMap.home.contains(44.738910, -63.303096))
        // Sample School is about 2 km west of the stop.
        val school = TestMap.schools.single()
        val d = distanceM(TestMap.stop.first, TestMap.stop.second, school.lat!!, school.lon!!)
        assertTrue("$d m", d in 1800.0..2400.0)
    }

    /** A `[home]` or `[heads-up]` note in the trip file is only for the reader: it must match the zones. */
    @Test
    fun theTripsZoneNotesMatchTheZones() {
        val lines = resource("morning.trip").lines().filter { it.isNotBlank() && !it.startsWith("#") }
        assertEquals(morning.size, lines.size)
        for ((fix, line) in morning.zip(lines)) {
            assertEquals(line, line.endsWith("[home]"), TestMap.home.contains(fix.lat, fix.lon))
            assertEquals(line, line.endsWith("[heads-up]"), TestMap.headsUp.contains(fix.lat, fix.lon))
        }
    }

    @Test
    fun oneCheckEvery30sWithTheGpsTimeUpTo60sBehind() {
        for ((a, b) in morning.zipWithNext()) assertEquals(30L, java.time.Duration.between(a.check, b.check).seconds)
        for (f in morning) assertTrue(f.toString(), java.time.Duration.between(f.gps, f.check).seconds in 0..60)
        for ((a, b) in morning.zipWithNext()) assertTrue("$b", !b.gps.isBefore(a.gps))
    }
}
