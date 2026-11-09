// SPDX-FileCopyrightText: 2026 anderewrey
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.anderewrey.bustracker

import io.github.anderewrey.bustracker.net.BadPortal
import io.github.anderewrey.bustracker.net.portalAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PortalTest {
    @Test
    fun bareHostGetsHttps() {
        assertEquals("https://example.mybusplanner.ca", portalAddress("  example.mybusplanner.ca "))
        assertEquals("https://busplanner.com", portalAddress("busplanner.com"))
    }

    @Test
    fun pathFromTheBrowserIsDropped() {
        assertEquals("https://example.mybusplanner.ca", portalAddress("https://example.mybusplanner.ca/Login?ReturnUrl=%2f"))
        assertEquals("https://example.mybusplanner.ca", portalAddress("HTTPS://Example.MyBusPlanner.ca/"))
    }

    @Test
    fun otherPortIsKept() {
        assertEquals("https://portal.example.org:8443", portalAddress("portal.example.org:8443"))
    }

    @Test
    fun plainHttpAndNonsenseAreRefused() {
        assertEquals("Use an https:// address", assertThrows(BadPortal::class.java) { portalAddress("http://busplanner.com") }.message)
        assertThrows(BadPortal::class.java) { portalAddress("") }
        assertThrows(BadPortal::class.java) { portalAddress("ftp://busplanner.com") }
        assertThrows(BadPortal::class.java) { portalAddress("busplanner") }
        assertThrows(BadPortal::class.java) { portalAddress("not an address") }
    }
}
