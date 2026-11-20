// SPDX-FileCopyrightText: 2026 anderewrey
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.anderewrey.bustracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShownStatusTest {
    private fun status(byHand: Boolean, at: Long) = RunStatus(null, "1.1 km · This Street", null, byHand = byHand, at = at)

    @Test fun aMonitoredRunShowsItsLastStatus() {
        val s = status(byHand = false, at = 100)
        assertEquals(s, shownStatus(s, monitored = true, mapOpenedAt = null))
        assertEquals(s, shownStatus(s, monitored = true, mapOpenedAt = 500))
    }

    @Test fun onceMonitoringStopsNothingIsShown() {
        // Also a check that ended after the stop: it isn't a tap on ↻.
        assertNull(shownStatus(status(byHand = false, at = 900), monitored = false, mapOpenedAt = null))
        assertNull(shownStatus(status(byHand = false, at = 900), monitored = false, mapOpenedAt = 500))
    }

    @Test fun aRefreshByHandShowsOnTheMapItWasMadeOn() {
        val s = status(byHand = true, at = 600)
        assertEquals(s, shownStatus(s, monitored = false, mapOpenedAt = 500))
    }

    @Test fun aRefreshFromAnEarlierMapIsGoneOnTheNextOne() {
        assertNull(shownStatus(status(byHand = true, at = 400), monitored = false, mapOpenedAt = 500))
    }

    @Test fun offTheMapOnlyMonitoringShows() {
        assertNull(shownStatus(status(byHand = true, at = 600), monitored = false, mapOpenedAt = null))
        assertNull(shownStatus(null, monitored = true, mapOpenedAt = 500))
    }
}
