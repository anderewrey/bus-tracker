// SPDX-FileCopyrightText: 2026 anderewrey
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.anderewrey.bustracker

import io.github.anderewrey.bustracker.data.clock
import io.github.anderewrey.bustracker.data.clockRange
import io.github.anderewrey.bustracker.data.clockSeconds
import io.github.anderewrey.bustracker.data.dayClock
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.LocalTime

class ClockTest {
    @Test
    fun twelveHourStyle() {
        assertEquals("7:33 AM", clock(LocalTime.of(7, 33)))
        assertEquals("2:39 PM", clock(LocalTime.of(14, 39)))
        assertEquals("12:05 PM", clock(LocalTime.of(12, 5)))
        assertEquals("12:05 AM", clock(LocalTime.of(0, 5)))
        assertEquals("2:51:12 PM", clockSeconds(LocalTime.of(14, 51, 12)))
        assertEquals("Mon Nov 16, 7:03 AM", dayClock(LocalDateTime.of(2026, 11, 16, 7, 3)))
    }

    @Test
    fun rangeNamesAmOrPmOnceUnlessItCrossesNoon() {
        assertEquals("7:03–8:03 AM", clockRange(LocalTime.of(7, 3), LocalTime.of(8, 3)))
        assertEquals("2:09–3:09 PM", clockRange(LocalTime.of(14, 9), LocalTime.of(15, 9)))
        assertEquals("11:30 AM–12:30 PM", clockRange(LocalTime.of(11, 30), LocalTime.of(12, 30)))
    }
}
