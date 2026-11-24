// SPDX-FileCopyrightText: 2026 anderewrey
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.anderewrey.bustracker

import io.github.anderewrey.bustracker.data.PositionSource
import io.github.anderewrey.bustracker.data.SchoolFix
import io.github.anderewrey.bustracker.data.SchoolMatch
import io.github.anderewrey.bustracker.data.circlePoints
import io.github.anderewrey.bustracker.data.fitSpots
import io.github.anderewrey.bustracker.data.SchoolArrival
import io.github.anderewrey.bustracker.data.SchoolSpot
import io.github.anderewrey.bustracker.data.matchSchool
import io.github.anderewrey.bustracker.data.plainName
import io.github.anderewrey.bustracker.data.schoolSpots
import io.github.anderewrey.bustracker.monitor.distanceM
import io.github.anderewrey.bustracker.net.School
import org.junit.Assert.assertEquals
import org.junit.Test

class SchoolsTest {
    @Test
    fun fitBothSpotsHoldsTheTwoWithRoomLeft() {
        // Two made-up spots about 29 m apart: centred between them, 14.5 + 20 m rounded up to 5.
        val am = 45.00000 to -63.00000
        val pm = 45.00026 to -63.00000
        val (lat, lon, r) = fitSpots(am, pm)
        assertEquals(45.00013, lat, 1e-9)
        assertEquals(-63.0, lon, 1e-9)
        assertEquals(35.0, r, 0.0)
        // The same spot twice: the smallest circle the slider allows is 25 m.
        assertEquals(25.0, fitSpots(am, am).third, 0.0)
    }

    @Test
    fun learningCountsABusAlreadyWaitingAtTheSchool() {
        val school = SchoolSpot("Sample School", 45.0, -63.0, 100.0, PositionSource.BUSPLANNER)
        // An afternoon run: the bus is parked at the school from the first reading.
        val learner = SchoolArrival(listOf(school), needAway = false)
        assertEquals(false, learner.add(45.0001, -63.0))
        assertEquals(false, learner.add(45.0001, -63.0))
        assertEquals(true, learner.add(45.0001, -63.0))
        // The end rule still waits for the bus to have been away first.
        val end = SchoolArrival(listOf(school))
        repeat(3) { assertEquals(false, end.add(45.0001, -63.0)) }
    }

    @Test
    fun namesIgnoreCaseAccentsAndSpaces() {
        assertEquals("ecole sample", plainName("  École   SAMPLE "))
    }

    @Test
    fun matching() {
        val list = listOf("Sample School", "Sample School Annex", "École Other", "Other Elementary")
        // One exact name wins over names containing it.
        assertEquals(SchoolMatch.Found("Sample School"), matchSchool(list, "sample school"))
        assertEquals(SchoolMatch.Found("École Other"), matchSchool(list, "Ecole other"))
        // Several possible names: no guess.
        assertEquals(SchoolMatch.Several(listOf("Sample School", "Sample School Annex")), matchSchool(list, "Sample"))
        // One name containing it: that one.
        assertEquals(SchoolMatch.Found("Other Elementary"), matchSchool(list, "Other Elementary School"))
        assertEquals(SchoolMatch.None, matchSchool(list, "Nowhere"))
        // The user's choice, while BusPlanner still lists it.
        assertEquals(SchoolMatch.Found("Sample School Annex"), matchSchool(list, "Sample", chosen = "Sample School Annex"))
        assertEquals(SchoolMatch.Several(listOf("Sample School", "Sample School Annex")), matchSchool(list, "Sample", chosen = "Gone"))
    }

    @Test
    fun theCorrectionComesFirst() {
        val bp = listOf(School("Sample School", 44.7481159, -63.2802613))
        val spots = schoolSpots(
            listOf("Sample School", "Unknown School", ""),
            bp,
            mapOf(
                "Sample School" to SchoolFix("Sample School", 44.74868, -63.27958, 60.0),
                "Unknown School" to SchoolFix("Unknown School", radius = 150.0),
            ),
        )
        assertEquals(2, spots.size)
        assertEquals(PositionSource.CORRECTED, spots[0].source)
        assertEquals(44.74868, spots[0].lat!!, 1e-9)
        assertEquals(60.0, spots[0].radius, 0.0)
        assertEquals(PositionSource.UNKNOWN, spots[1].source)
        assertEquals(150.0, spots[1].radius, 0.0)
        // Only the circle changed: BusPlanner's position, with that circle.
        val circleOnly = schoolSpots(listOf("Sample School"), bp, mapOf("Sample School" to SchoolFix("Sample School", radius = 200.0)))[0]
        assertEquals(PositionSource.BUSPLANNER, circleOnly.source)
        assertEquals(200.0, circleOnly.radius, 0.0)
    }

    @Test
    fun circleIsAtTheRadius() {
        val ring = circlePoints(44.748, -63.280, 100.0)
        assertEquals(ring.first(), ring.last())
        ring.forEach { (lat, lon) -> assertEquals(100.0, distanceM(44.748, -63.280, lat, lon), 1.0) }
    }
}
