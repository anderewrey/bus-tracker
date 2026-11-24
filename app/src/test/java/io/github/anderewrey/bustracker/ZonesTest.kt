// SPDX-FileCopyrightText: 2026 anderewrey
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.anderewrey.bustracker

import io.github.anderewrey.bustracker.data.Run
import io.github.anderewrey.bustracker.data.Zone
import io.github.anderewrey.bustracker.data.ZoneRole
import io.github.anderewrey.bustracker.data.alarmArmed
import io.github.anderewrey.bustracker.data.carryConfiguration
import io.github.anderewrey.bustracker.data.defaultZone
import io.github.anderewrey.bustracker.data.nameTaken
import io.github.anderewrey.bustracker.data.zonesFor
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ZonesTest {
    // A made-up square zone, about 1.1 km a side.
    private val square = Zone("z1", "Square", ZoneRole.MAIN, listOf(45.00 to -63.00, 45.01 to -63.00, 45.01 to -62.99, 45.00 to -62.99))

    private fun run(lat: Double?, lon: Double?, key: String = "B1 PM", mainZone: String? = null) =
        Run(key, "B1", "PM Dropoff", "3:05 PM", "THIS ST & THAT ST", lat, lon, emptyList(), mainZone = mainZone)

    @Test
    fun headsUpZoneComesFirstThenTheMainZone() {
        val heads = square.copy(id = "h1", name = "Heads", role = ZoneRole.HEADS_UP)
        val r = run(45.005, -62.995, mainZone = "z1").copy(headsUpZone = "h1")
        assertEquals(listOf("Heads", "Square"), zonesFor(r, listOf(square, heads)).map { it.name })
    }

    @Test
    fun alarmOnlyAfterTheHeadsUpWhenAsked() {
        val heads = square.copy(id = "h1", name = "Heads", role = ZoneRole.HEADS_UP)
        val zones = listOf(square, heads)
        val plain = run(45.005, -62.995, mainZone = "z1").copy(headsUpZone = "h1")
        // Off (the default): the alarm rings whether or not the heads-up sounded.
        assertTrue(alarmArmed(plain, zones, headsUpSounded = false))
        val chained = plain.copy(alarmAfterHeadsUp = true)
        assertFalse(alarmArmed(chained, zones, headsUpSounded = false))
        assertTrue(alarmArmed(chained, zones, headsUpSounded = true))
        // Asked for, but no heads-up zone (removed, or gone): nothing to wait for.
        assertTrue(alarmArmed(chained.copy(headsUpZone = null), zones, headsUpSounded = false))
        assertTrue(alarmArmed(chained.copy(headsUpZone = "gone"), zones, headsUpSounded = false))
    }

    @Test
    fun headsUpSettingsAreSavedAndCarriedOverRescans() {
        val r = run(45.005, -62.995, mainZone = "z1").copy(headsUpZone = "h1", alarmAfterHeadsUp = true)
        val back = Run.fromJson(r.toJson())
        assertEquals("h1", back.headsUpZone)
        assertTrue(back.alarmAfterHeadsUp)
        // A run saved before heads-up zones: none, and the alarm as before.
        val old = r.toJson().apply { remove("headsUpZone"); remove("alarmAfterHeadsUp") }
        assertNull(Run.fromJson(old).headsUpZone)
        assertFalse(Run.fromJson(old).alarmAfterHeadsUp)
        val fresh = run(45.005, -62.995)
        val carried = carryConfiguration(listOf(fresh), emptyList(), listOf(r)).single()
        assertEquals("h1", carried.headsUpZone)
        assertTrue(carried.alarmAfterHeadsUp)
    }

    @Test
    fun insideAndOutside() {
        assertTrue(square.contains(45.005, -62.995))
        assertFalse(square.contains(45.02, -62.995))
        assertFalse(square.contains(45.005, -63.01))
    }

    @Test
    fun aRunUsesItsChosenZoneOnly() {
        val other = square.copy(id = "z2", name = "Other")
        assertEquals(listOf("Square"), zonesFor(run(45.005, -62.995, mainZone = "z1"), listOf(other, square)).map { it.name })
        // Its stop being inside a zone doesn't make that zone its own: the choice does.
        assertTrue(zonesFor(run(45.005, -62.995), listOf(square)).isEmpty())
        assertTrue(zonesFor(run(45.005, -62.995, mainZone = "gone"), listOf(square)).isEmpty())
    }

    @Test
    fun aNewRunGetsTheZoneAroundItsStop() {
        assertEquals("z1", defaultZone(run(45.005, -62.995), listOf(square)))
        assertNull(defaultZone(run(45.05, -62.995), listOf(square)))
        assertNull(defaultZone(run(null, null), listOf(square)))
        // Only a run seen for the first time; a known run keeps its choice, even none.
        val known = run(45.005, -62.995, key = "B1 AM")
        val saved = carryConfiguration(listOf(known, run(45.005, -62.995)), emptyList(), listOf(known), listOf(square))
        assertNull(saved[0].mainZone)
        assertEquals("z1", saved[1].mainZone)
    }

    @Test
    fun zoneSurvivesTheJsonRoundTrip() {
        assertEquals(square, Zone.fromJson(JSONObject(square.toJson().toString())))
        val r = run(45.005, -62.995, mainZone = "z1")
        assertEquals(r, Run.fromJson(JSONObject(r.toJson().toString())))
    }

    @Test
    fun zoneNamesAreUnique() {
        val zones = listOf(square)
        assertTrue(nameTaken(zones, " ${square.name.uppercase()} "))
        assertFalse(nameTaken(zones, square.name, exceptId = square.id)) // the zone being edited keeps its name
        assertFalse(nameTaken(zones, "Another name"))
    }

    @Test
    fun dropoffAndPickupRuns() {
        assertTrue(run(null, null).isDropoff)
        assertFalse(run(null, null).copy(stopType = "AM Pickup").isDropoff)
    }
}
