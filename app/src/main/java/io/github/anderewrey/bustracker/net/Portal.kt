// SPDX-FileCopyrightText: 2026 anderewrey
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.anderewrey.bustracker.net

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

class BadPortal(message: String) : Exception(message)

/**
 * The BusPlanner portal's address as typed on the login screen, reduced to its origin
 * ("https://host", or "https://host:port"): any https address, a bare host gets https,
 * and a path pasted from the browser is dropped. Plain http is refused, so the password
 * never travels unencrypted.
 */
fun portalAddress(input: String): String {
    val text = input.trim()
    if (text.isEmpty()) throw BadPortal("Enter the portal address")
    val scheme = Regex("^([a-zA-Z][a-zA-Z0-9+.-]*)://").find(text)?.groupValues?.get(1)?.lowercase()
    if (scheme == "http") throw BadPortal("Use an https:// address")
    if (scheme != null && scheme != "https") throw BadPortal("Not a web address")
    val url = (if (scheme == null) "https://$text" else text).toHttpUrlOrNull()
    if (url == null || '.' !in url.host) throw BadPortal("Not a web address")
    return if (url.port == 443) "https://${url.host}" else "https://${url.host}:${url.port}"
}
