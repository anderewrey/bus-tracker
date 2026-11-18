// SPDX-FileCopyrightText: 2026 anderewrey
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.anderewrey.bustracker

import io.github.anderewrey.bustracker.monitor.Place
import io.github.anderewrey.bustracker.monitor.chooseStreet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StreetTest {
    private fun stop(name: String) = Place(null, "$name, Porters Lake, NS B3E 1A1, Canada")
    private fun house(n: Int, street: String) = Place(street, "$n $street, Porters Lake, NS B3E 1A1, Canada")

    @Test fun oneStreetName() {
        assertEquals("This Street", chooseStreet(listOf(stop("This St Before That St (7)"), house(10, "This Street"), house(12, "This Street"))))
    }

    @Test fun severalNamesMostFrequentFirst() {
        // As seen 2026-11-18 near a stop: three houses on the cross street, then one on the bus's street.
        val places = listOf(
            stop("This St After That St (1234)"),
            house(286, "That Street"), house(291, "That Street"), house(290, "That Street"),
            house(1111, "This Street"),
        )
        assertEquals("That Street / This Street", chooseStreet(places))
    }

    @Test fun tiesKeepTheGeocodersOrder() {
        val places = listOf(house(3, "This Street"), house(9, "The Other Street"), house(5, "This Street"), house(7, "The Other Street"))
        assertEquals("This Street / The Other Street", chooseStreet(places))
    }

    @Test fun noStreetAtAllKeepsTheFirstLineBeforeItsComma() {
        assertEquals("This St After That St (1234)", chooseStreet(listOf(stop("This St After That St (1234)"), Place(null, "Porters Lake, NS"))))
    }

    @Test fun nothing() {
        assertNull(chooseStreet(emptyList()))
    }
}
