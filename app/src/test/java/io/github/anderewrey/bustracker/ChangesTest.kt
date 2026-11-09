// SPDX-FileCopyrightText: 2026 anderewrey
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.anderewrey.bustracker

import io.github.anderewrey.bustracker.data.ChangeKind
import io.github.anderewrey.bustracker.data.Run
import io.github.anderewrey.bustracker.data.carryConfiguration
import io.github.anderewrey.bustracker.data.changeKinds
import io.github.anderewrey.bustracker.data.diffRuns
import io.github.anderewrey.bustracker.data.mergeChanges
import io.github.anderewrey.bustracker.net.Student
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class ChangesTest {
    @Test
    fun schoolChangeOnlyBetweenTwoKnownSchools() {
        val r = Run("B101 AM", "B101", "AM Pickup", "7:33 AM", "THIS ST & THAT ST", 44.73810, -63.30434, emptyList())
        val a = r.copy(school = "Sample School")
        assertEquals(emptySet<ChangeKind>(), changeKinds(r, a)) // a run saved before 0.9 gets its school
        assertEquals(setOf(ChangeKind.SCHOOL), changeKinds(a, a.copy(school = "Another School")))
    }

    private val a = Student("a", "Student A")
    private val b = Student("b", "Student B")
    private val t1 = LocalDateTime.of(2026, 11, 5, 7, 5)
    private val t2 = t1.plusDays(1)

    // This Street and The Other Street, Porters Lake NS (OpenStreetMap), about 110 m apart.
    private val home = 44.737825 to -63.3050693
    private val elsewhere = 44.7381987 to -63.3039294

    private fun am(time: String = "7:33 AM", address: String = "12 THIS ST", at: Pair<Double, Double> = home, bus: String = "B101", students: List<Student> = listOf(a, b)) =
        Run("$bus AM", bus, "AM Pickup", time, address, at.first, at.second, students)

    private fun pm() = Run("B101 PM", "B101", "PM Dropoff", "2:39 PM", "12 THIS ST", home.first, home.second, listOf(a, b))

    @Test
    fun sameScanHasNoChange() {
        assertTrue(diffRuns(listOf(am(), pm()), listOf(am(), pm()), t1).isEmpty())
    }

    @Test
    fun timeChange() {
        val c = diffRuns(listOf(am(), pm()), listOf(am(time = "7:41 AM"), pm()), t1).single()
        assertEquals(setOf(ChangeKind.TIME), c.kinds)
        assertFalse(c.needsChoice)
        assertEquals("B101 · Pickup 7:33 · 12 THIS ST", c.past!!.stopLine)
        assertEquals("B101 · Pickup 7:41 · 12 THIS ST", c.new!!.stopLine)
    }

    @Test
    fun movedStopKeepsThePastZonesUntilAnswered() {
        val past = listOf(am(), pm()).map { it.copy(mainZone = "z1") }
        val scanned = listOf(am(address = "12 THE OTHER ST", at = elsewhere), pm())
        val c = diffRuns(past, scanned, t1).single()
        assertEquals(setOf(ChangeKind.STOP), c.kinds)
        assertTrue(c.needsChoice)

        // The moved run keeps its main zone (and days) until the user answers.
        val saved = carryConfiguration(scanned, listOf(c), past)
        assertEquals("z1", saved[0].mainZone)
        assertEquals("z1", saved[1].mainZone)
    }

    @Test
    fun sameAddressSmallShiftIsTheSameStop() {
        val nudged = home.first + 0.0001 to home.second // about 11 m
        assertTrue(diffRuns(listOf(am()), listOf(am(at = nudged)), t1).isEmpty())
    }

    @Test
    fun replacingBusIsPairedWithThePastRun() {
        val c = diffRuns(listOf(am(), pm()), listOf(am(bus = "B202"), pm()), t1).single()
        assertEquals(setOf(ChangeKind.BUS), c.kinds)
        assertEquals("B101 AM", c.past!!.key)
        assertEquals("B202 AM", c.new!!.key)
        assertTrue(c.needsChoice)
    }

    @Test
    fun addedAndRemovedRuns() {
        val changes = diffRuns(listOf(am(students = listOf(a))), listOf(pm()), t1)
        assertEquals(listOf(setOf(ChangeKind.REMOVED), setOf(ChangeKind.ADDED)), changes.map { it.kinds })
    }

    @Test
    fun keptConfigurationSurvivesLaterScans() {
        val past = listOf(am().copy(mainZone = "z1"))
        val moved = listOf(am(address = "12 THE OTHER ST", at = elsewhere))
        val saved = carryConfiguration(moved, diffRuns(past, moved, t1), past)
        val later = listOf(am(time = "7:40 AM", address = "12 THE OTHER ST", at = elsewhere))
        val again = carryConfiguration(later, diffRuns(saved, later, t2), saved)
        assertEquals("z1", again[0].mainZone)
    }

    @Test
    fun waitingChangeIsMergedWithTheNextOne() {
        val first = diffRuns(listOf(am()), listOf(am(time = "7:41 AM")), t1)
        val second = diffRuns(listOf(am(time = "7:41 AM")), listOf(am(time = "7:45 AM", address = "12 THE OTHER ST", at = elsewhere)), t2)
        val merged = mergeChanges(first, second).single()
        assertEquals("7:33 AM", merged.past!!.time)
        assertEquals("7:45 AM", merged.new!!.time)
        assertEquals(setOf(ChangeKind.TIME, ChangeKind.STOP), merged.kinds)

        val back = diffRuns(listOf(am(time = "7:41 AM")), listOf(am()), t2)
        assertTrue(mergeChanges(first, back).isEmpty())
    }
}
