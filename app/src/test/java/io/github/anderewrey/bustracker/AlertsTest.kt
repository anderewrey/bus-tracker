// SPDX-FileCopyrightText: 2026 anderewrey
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.anderewrey.bustracker

import io.github.anderewrey.bustracker.net.alertsFor
import io.github.anderewrey.bustracker.net.bulletinText
import io.github.anderewrey.bustracker.net.delayMinutes
import io.github.anderewrey.bustracker.net.latestFor
import io.github.anderewrey.bustracker.net.noticeLabel
import io.github.anderewrey.bustracker.net.parseBusAlerts
import io.github.anderewrey.bustracker.net.sameHalfDay
import org.jsoup.Jsoup
import java.time.LocalTime
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertsTest {
    // GetBusNotifications' answer on 2026-11-05, as the portal's Alerts page got it.
    private val real = """{"d":{"draw":1,"recordsTotal":2,"recordsFiltered":2,"data":[""" +
        """{"AppliesTo":"2026-11-05","RouteRun":"B102 (B102 XYZ_ABC_PU)","Zone":null,"Operator":"Operator Co","Action":"Delayed 15 minutes from original time ","AffectsSchools":"School A ,School B","TransfersSchools":"","Comment":"Bus is running 15 minutes late due to a vehicle related incident/  Le bus accuse un retard de 15 minutes en raison d’un incident lié au véhicule.","IsCreateTimeVisible":false,"CreateTime":"\/Date(1793878581250)\/","CreateTimeDisplay":"2026-11-05 7:36 AM"},""" +
        """{"AppliesTo":"2026-11-05","RouteRun":"B102 (B102 XYZ_ABC_PU)","Zone":null,"Operator":"Operator Co","Action":"Delayed 30 Minutes from original time  ","AffectsSchools":"School A ,School B","TransfersSchools":"","Comment":"Bus is  running  30 minutes behind do to a vehicle related incident, bus as resumed service and continuing its route /  \nLe bus accuse un retard de 30 minutes en raison d'un incident technique ; il a repris son service et poursuit son trajet.","IsCreateTimeVisible":false,"CreateTime":"\/Date(1793880152073)\/","CreateTimeDisplay":"2026-11-05 8:02 AM"}""" +
        """],"StartRecord":0,"PageSize":100,"error":null}}"""

    @Test
    fun realAnswerIsParsed() {
        val alerts = parseBusAlerts(real)
        assertEquals(2, alerts.size)
        val a = alerts[0]
        assertEquals("B102", a.bus)
        assertEquals("Delayed 15 minutes from original time", a.action)
        assertEquals("2026-11-05 7:36 AM", a.created)
        assertEquals(1793878581250L, a.createdMs)
        assertTrue(a.comment.startsWith("Bus is running 15 minutes late"))
        assertNotEquals(alerts[0].id, alerts[1].id)
    }

    @Test
    fun busNumberMatchesAsAWholeWord() {
        val alerts = parseBusAlerts(real)
        assertEquals(2, alertsFor(alerts, "B102").size)
        assertEquals(2, alertsFor(alerts, "b102").size)
        assertEquals(0, alertsFor(alerts, "B10").size)
        assertEquals(0, alertsFor(alerts, "B1020").size)
        assertEquals(0, alertsFor(alerts, "B101").size)
    }

    @Test
    fun newestNoticeReplacesTheOlderOne() {
        val alerts = parseBusAlerts(real)
        assertEquals("2026-11-05 8:02 AM", latestFor(alerts, "B102")!!.created)
        // The page's order doesn't matter: the posting time decides.
        assertEquals("2026-11-05 8:02 AM", latestFor(alerts.reversed(), "B102")!!.created)
        assertNull(latestFor(alerts, "B101"))
    }

    @Test
    fun delayIsRead() {
        val alerts = parseBusAlerts(real)
        assertEquals(15, delayMinutes(alerts[0].action))
        assertEquals(30, delayMinutes(alerts[1].action))
        assertEquals("delayed 30 minutes", noticeLabel(alerts[1]))
        assertNull(delayMinutes("Cancelled"))
        assertEquals("Cancelled", noticeLabel(alerts[0].copy(action = "Cancelled")))
    }

    @Test
    fun morningNoticeIsNotAboutTheAfternoonRun() {
        // Posted 7:36 local time, at UTC-4.
        val alert = parseBusAlerts(real)[0]
        val local = ZoneOffset.ofHours(-4)
        assertTrue(sameHalfDay(alert, LocalTime.of(7, 33), local))
        assertFalse(sameHalfDay(alert, LocalTime.of(14, 39), local))
        assertTrue(sameHalfDay(alert.copy(createdMs = null), LocalTime.of(14, 39), local))
    }

    @Test
    fun emptyAnswer() {
        assertTrue(parseBusAlerts("""{"d":{"draw":1,"recordsTotal":0,"recordsFiltered":0,"data":[],"StartRecord":0,"PageSize":100,"error":null}}""").isEmpty())
    }

    @Test
    fun bulletinWithoutTheCloseButton() {
        val text = "Information about the vehicle associated to your child's transportation is unavailable at this time. Please try again later."
        val withButton = Jsoup.parse("""<span id="x_lblBulletin">$text <button type="button" class="close">×</button></span>""")
        assertEquals(text, bulletinText(withButton))
        val plain = Jsoup.parse("""<span id="x_lblBulletin">$text×</span>""")
        assertEquals(text, bulletinText(plain))
    }
}
