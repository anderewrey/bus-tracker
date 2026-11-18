// SPDX-FileCopyrightText: 2026 anderewrey
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.anderewrey.bustracker

import io.github.anderewrey.bustracker.monitor.Place
import io.github.anderewrey.bustracker.monitor.chooseStreet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StreetTest {
    private fun stop(name: String, m: Double) = Place(null, m, "$name, Porters Lake, NS B3E 1A1, Canada")
    private fun house(n: Int, street: String, m: Double) = Place(street, m, "$n $street, Porters Lake, NS B3E 1A1, Canada")

    @Test fun oneStreetName() {
        val places = listOf(stop("This St Before That St (7)", 10.0), house(10, "This Street", 22.0), house(12, "This Street", 19.0))
        assertEquals("This Street", chooseStreet(places))
    }

    @Test fun twoStreetsWithinTheMarginNearestFirst() {
        // In the geocoder's order That Street comes first, but This Street is nearer.
        val places = listOf(house(1, "That Street", 30.0), house(2, "This Street", 12.0), house(3, "This Street", 38.0))
        assertEquals("This Street / That Street", chooseStreet(places))
    }

    @Test fun aStreetBeyondTheMarginIsLeftOut() {
        // As seen 2026-11-18 at a stop: a house on a nearby street 59 m away, the bus's street at 15 m.
        val places = listOf(
            stop("This St After That St (2325)", 11.0),
            house(3582, "This Street", 54.0), house(3563, "This Street", 47.0),
            house(3575, "The Other Street", 59.0), house(3584, "This Street", 15.0),
        )
        assertEquals("This Street", chooseStreet(places))
    }

    @Test fun justInsideAndJustOutside() {
        val places = listOf(house(1, "This Street", 10.0), house(2, "That Street", 34.9), house(3, "The Other Street", 35.0))
        assertEquals("This Street / That Street", chooseStreet(places))
    }

    @Test fun aStreetWithNoPositionIsLeftOut() {
        val places = listOf(Place("That Street", null, "1 That Street, Porters Lake"), house(2, "This Street", 40.0))
        assertEquals("This Street", chooseStreet(places))
    }

    @Test fun noCandidateWithAStreetKeepsTheFirstLineBeforeItsComma() {
        assertEquals("This St After That St (1234)", chooseStreet(listOf(stop("This St After That St (1234)", 8.0), Place(null, 30.0, "Porters Lake, NS"))))
    }

    @Test fun nothing() {
        assertNull(chooseStreet(emptyList()))
    }
}
