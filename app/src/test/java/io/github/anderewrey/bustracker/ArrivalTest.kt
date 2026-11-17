// SPDX-FileCopyrightText: 2026 anderewrey
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.anderewrey.bustracker

import io.github.anderewrey.bustracker.data.AT_SCHOOL_M
import io.github.anderewrey.bustracker.data.PositionSource
import io.github.anderewrey.bustracker.data.SchoolArrival
import io.github.anderewrey.bustracker.data.SchoolSpot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArrivalTest {
    private val school = listOf(SchoolSpot("Sample School", 44.7481159, -63.2802613, AT_SCHOOL_M, PositionSource.BUSPLANNER))
    // Where the bus was parked on 2026-11-05 at 7:56, about 83 m from BusPlanner's school point.
    private val parked = 44.74868 to -63.27958

    @Test
    fun parkedThreeReadingsAfterComingFromAwayMeansAtSchool() {
        val a = SchoolArrival(school)
        assertFalse(a.add(44.73897, -63.30251)) // This St, after the stop
        assertFalse(a.add(parked.first, parked.second))
        assertFalse(a.add(parked.first + 0.00002, parked.second)) // GPS jitter, ~2 m
        assertTrue(a.add(parked.first, parked.second + 0.00002))
        assertEquals("Sample School", a.parkedAt?.name)
        assertEquals(parked.first, a.parkedSpot!!.first, 0.00002)
    }

    @Test
    fun aSmallerCircleLeavesTheParkedBusOutside() {
        // The bus parks ~83 m from BusPlanner's point: outside a 50 m circle, inside a moved one.
        val small = SchoolArrival(listOf(school[0].copy(radius = 50.0)))
        small.add(44.73897, -63.30251)
        repeat(4) { assertFalse(small.add(parked.first, parked.second)) }
        val moved = SchoolArrival(listOf(school[0].copy(lat = parked.first, lon = parked.second, radius = 50.0)))
        moved.add(44.73897, -63.30251)
        repeat(2) { assertFalse(moved.add(parked.first, parked.second)) }
        assertTrue(moved.add(parked.first, parked.second))
    }

    @Test
    fun aSchoolWithNoPositionNeverEndsIt() {
        val a = SchoolArrival(listOf(SchoolSpot("Unknown", null, null, AT_SCHOOL_M, PositionSource.UNKNOWN)))
        repeat(5) { assertFalse(a.add(parked.first, parked.second)) }
    }

    @Test
    fun aBusWaitingAtTheSchoolBeforeLeavingIsNotStopped() {
        // PM run: the bus is at the school when monitoring starts.
        val a = SchoolArrival(school)
        repeat(6) { assertFalse(a.add(parked.first, parked.second)) }
        assertFalse(a.add(44.74459, -63.28318)) // left the school
    }

    @Test
    fun movingNearTheSchoolRestartsTheCount() {
        val a = SchoolArrival(school)
        a.add(44.73897, -63.30251)
        assertFalse(a.add(44.74860, -63.28017)) // ~40 m from the parking spot
        assertFalse(a.add(parked.first, parked.second))
        assertFalse(a.add(parked.first, parked.second))
        assertTrue(a.add(parked.first, parked.second))
    }

    @Test
    fun stoppedOutsideTheCircleIsNotTheSchool() {
        val a = SchoolArrival(school)
        repeat(5) { assertFalse(a.add(44.74459, -63.28318)) } // ~600 m away
        assertEquals(false, SchoolArrival(emptyList()).add(parked.first, parked.second))
    }
}
