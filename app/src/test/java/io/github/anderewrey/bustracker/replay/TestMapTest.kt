// SPDX-FileCopyrightText: 2026 anderewrey
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.anderewrey.bustracker.replay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration

/** The test data is what DESIGN.md §8, Tests says. */
class TestMapTest {
    @Test
    fun theStopIsInHomeZone() {
        assertTrue(TestMap.home.contains(TestMap.stop.first, TestMap.stop.second))
    }

    @Test
    fun oneCheckEvery30s() {
        for ((a, b) in loadTrip("morning.trip").zipWithNext()) assertEquals(30L, Duration.between(a.check, b.check).seconds)
    }
}
