// SPDX-FileCopyrightText: 2026 anderewrey
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.anderewrey.bustracker

import io.github.anderewrey.bustracker.data.PrefValue
import io.github.anderewrey.bustracker.data.prefsFromJson
import io.github.anderewrey.bustracker.data.prefsToJson
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class TransferTest {
    @Test
    fun everyTypeComesBackAsItself() {
        val all = mapOf(
            "runs" to "[]", "autoOn" to true, "n" to 3, "big" to 5_000_000_000L, "f" to 1.5f,
            "seenNotices" to setOf("a", "b"),
        )
        // Through text, as it travels between the apps.
        val back = prefsFromJson("bustracker", JSONObject(prefsToJson("bustracker", all).toString()))
        assertEquals(
            mapOf(
                "runs" to PrefValue.S("[]"), "autoOn" to PrefValue.B(true), "n" to PrefValue.I(3),
                "big" to PrefValue.L(5_000_000_000L), "f" to PrefValue.F(1.5f), "seenNotices" to PrefValue.StringSet(setOf("a", "b")),
            ),
            back,
        )
    }

    @Test
    fun passwordAndChannelVersionsStay() {
        val login = prefsToJson("bustracker", mapOf("password" to "sealed", "email" to "a@example.com"))
        assertFalse(login.has("password"))
        assertEquals(setOf("email"), login.keys().asSequence().toSet())
        val settings = prefsToJson("settings", mapOf("channel_main_zone" to 4, "quietOverride" to true))
        assertEquals(setOf("quietOverride"), settings.keys().asSequence().toSet())
        // Not in another file: "password" is only skipped in the login's.
        assertEquals(setOf("password"), prefsToJson("zones", mapOf("password" to "x")).keys().asSequence().toSet())
    }

    @Test
    fun anImportIgnoresWhatItMustNotWrite() {
        val o = JSONObject().put("password", JSONObject().put("s", "x")).put("email", JSONObject().put("s", "a"))
        assertEquals(mapOf("email" to PrefValue.S("a")), prefsFromJson("bustracker", o))
    }
}
