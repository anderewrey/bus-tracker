// SPDX-FileCopyrightText: 2026 anderewrey
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.anderewrey.bustracker

import io.github.anderewrey.bustracker.net.cookieHint
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CookieHintTest {
    private val held = mapOf("ASP.NET_SessionId" to "abc", ".ASPXAUTH" to "token")

    @Test
    fun firstCookiesOfASessionAreFine() {
        assertNull(cookieHint(emptyMap(), "ASP.NET_SessionId", "abc", gone = false))
        assertNull(cookieHint(emptyMap(), ".ASPXAUTH", "token", gone = false))
    }

    @Test
    fun sameSessionIdAgainIsFine() {
        assertNull(cookieHint(held, "ASP.NET_SessionId", "abc", gone = false))
        assertNull(cookieHint(held, "SomeOtherCookie", "x", gone = false))
    }

    @Test
    fun newSessionIdMeansTheSessionIsGone() {
        assertNotNull(cookieHint(held, "ASP.NET_SessionId", "xyz", gone = false))
    }

    @Test
    fun clearedLoginCookieMeansTheSessionIsGone() {
        assertNotNull(cookieHint(held, ".ASPXAUTH", "", gone = true))
    }
}
