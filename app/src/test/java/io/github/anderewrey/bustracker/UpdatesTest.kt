// SPDX-FileCopyrightText: 2026 anderewrey
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.anderewrey.bustracker

import io.github.anderewrey.bustracker.net.LatestRelease
import io.github.anderewrey.bustracker.net.isNewer
import io.github.anderewrey.bustracker.net.parseLatestRelease
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdatesTest {
    @Test
    fun latestReleaseFromGitHub() {
        val json = """{"tag_name":"build-29922755","name":"0.18","prerelease":false,"assets":[]}"""
        assertEquals(LatestRelease("0.18", 29922755), parseLatestRelease(json))
    }

    @Test
    fun aReleaseNotMadeByTheCiIsIgnored() {
        assertNull(parseLatestRelease("""{"tag_name":"0.14","name":"0.14"}"""))
    }

    @Test
    fun theInstalledVersionIsNotOlderThanItsOwnRelease() {
        // 0.18: promoted from build-29922755, built again as versionCode 29922765.
        val release = LatestRelease("0.18", 29922755)
        assertFalse(isNewer(release, 29922765))
        // 0.17 installed (versionCode 29922713): 0.18 is newer.
        assertTrue(isNewer(release, 29922713))
    }
}
