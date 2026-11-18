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

    @Test fun aStreetFirstIsKept() {
        assertEquals("That Street", chooseStreet(listOf(house(12, "That Street"), house(5, "This Street"))))
    }

    @Test fun nearAStopTheStreetItIsOnWinsOverTheCrossStreet() {
        // As seen 2026-11-18: three houses on the cross street before one on the bus's street.
        val places = listOf(
            stop("This St After That St (1234)"),
            house(286, "That Street"), house(291, "That Street"), house(290, "That Street"),
            house(1111, "This Street"),
        )
        assertEquals("This Street", chooseStreet(places))
    }

    @Test fun shortWordsDoNotDecide() {
        val places = listOf(stop("The Other St Before This St (42)"), house(3, "This Street"), house(9, "The Other Street"))
        assertEquals("The Other Street", chooseStreet(places))
    }

    @Test fun aStopWithOnlyItsOwnStreetAround() {
        val places = listOf(stop("This St Before That St (7)"), house(10, "This Street"), house(12, "This Street"))
        assertEquals("This Street", chooseStreet(places))
    }

    @Test fun noStreetInTheStopNameTakesTheFirstStreet() {
        assertEquals("That Street", chooseStreet(listOf(stop("Stop 7"), house(1, "That Street"), house(2, "This Street"))))
    }

    @Test fun noStreetAtAllKeepsTheStopName() {
        assertEquals("This St After That St (1234)", chooseStreet(listOf(stop("This St After That St (1234)"))))
    }

    @Test fun nothing() {
        assertNull(chooseStreet(emptyList()))
    }
}
