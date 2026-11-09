// SPDX-FileCopyrightText: 2026 anderewrey
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.anderewrey.bustracker.net

import io.github.anderewrey.bustracker.AppLog
import okhttp3.Cookie
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.util.concurrent.TimeUnit

/** Talks to a BusPlanner portal the way the portal's own pages do (ported from busprobe.py). */

// Paths on the portal given at login (see portalAddress).
const val PAGE = "/Subscriptions/WheresMyBus?showParentPopup=True"
const val INFO_PAGE = "/Subscriptions/ChildTransportInfo"
const val SCHOOL_PAGE = "/SchoolInfo"
const val LOGIN = "/Login?ReturnUrl=%2fSubscriptions%2fWheresMyBus&LoginType=Subscriber"
const val LOGIN_USER = "ctl00\$MainContent\$glogin\$lLogin\$UserName"
const val LOGIN_PASSWORD = "ctl00\$MainContent\$glogin\$lLogin\$Password"
const val LOGIN_BUTTON = "ctl00\$MainContent\$glogin\$lLogin\$btnLogin"
const val SCRIPT_MANAGER = "ctl00\$ctl00\$scriptManager"
const val STUDENTS_DDL = "ctl00\$ctl00\$MainContent\$ddlStudents"
const val MAP_PANEL = "ctl00\$ctl00\$MainContent\$NestContent\$UpdatePanel_Map"
/** The Where's My Bus page's Refresh button: the only request that brings a new bus position. */
const val MAP_REFRESH = "ctl00\$ctl00\$MainContent\$NestContent\$MapRefresh"
const val BUS_FIELD = "ctl00\$ctl00\$MainContent\$NestContent\$hfBusLocation"
const val STOP_FIELD = "ctl00\$ctl00\$MainContent\$NestContent\$hfBusStopLocations"
const val POSITION_FIELD = "ctl00\$ctl00\$MainContent\$NestContent\$hfTransportationPosition"
// The prototype's desktop browser identity: with a phone one, the timer refresh kept returning the first position.
internal const val UA ="Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36"

class SessionExpired(message: String) : Exception(message)
class LoginFailed(message: String) : Exception(message)

data class Student(val guid: String, val name: String)

/** One row of ChildTransportInfo, e.g. AM Pickup 7:33 AM at 12 THIS ST on B101. */
data class StopRow(
    val stopType: String,
    val time: String,
    val location: String,
    val route: String?,
    val lat: Double?,
    val lon: Double?,
)

data class School(val name: String, val lat: Double, val lon: Double)

data class Reading(
    val busLat: Double?,
    val busLon: Double?,
    val stopLat: Double?,
    val stopLon: Double?,
    val runLabel: String?,
    val updated: String?,
    val reported: String?,
    val bulletin: String?,
)

/** Hidden fields and student dropdown of one ASP.NET page. */
private class PageState(val url: String) {
    val fields = HashMap<String, String>()
    var students: List<Student> = emptyList()
    var selected: String? = null
}

private data class Resp(val status: Int, val location: String, val body: String)

/** One login on the portal at [base] ("https://host", from portalAddress). */
class BusPlannerClient(val base: String) {
    private val cookies = LinkedHashMap<String, String>()
    private val http = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .callTimeout(20, TimeUnit.SECONDS)
        .build()
    private val map = PageState(base + PAGE)
    private val info = PageState(base + INFO_PAGE)
    /** The "vehicle unavailable" message of the last full Where's My Bus page, if any. */
    private var pageBulletin: String? = null

    val students: List<Student> get() = map.students

    @Synchronized
    private fun open(url: String, form: Map<String, String>? = null, ajax: Boolean = false): Resp {
        val req = Request.Builder().url(url)
            .header("User-Agent", UA)
            .header("Cookie", cookies.entries.joinToString("; ") { "${it.key}=${it.value}" })
        if (form != null) {
            val body = FormBody.Builder()
            form.forEach { (k, v) -> body.add(k, v) }
            req.post(body.build())
        }
        if (ajax) {
            req.header("X-MicrosoftAjax", "Delta=true")
            req.header("X-Requested-With", "XMLHttpRequest")
        }
        http.newCall(req.build()).execute().use { resp ->
            for (sc in resp.headers("Set-Cookie")) {
                val c = Cookie.parse(resp.request.url, sc) ?: continue
                val gone = c.value.isEmpty() || c.expiresAt < System.currentTimeMillis()
                cookieHint(cookies, c.name, c.value, gone)?.let { if (sessionLost == null) sessionLost = it }
                if (gone) cookies.remove(c.name) else cookies[c.name] = c.value
            }
            return Resp(resp.code, resp.header("Location") ?: "", resp.body.string())
        }
    }

    /** Set when an answer's cookies show the server forgot the session (see cookieHint); cleared by login. */
    private var sessionLost: String? = null

    private fun request(page: PageState, form: Map<String, String>? = null, ajax: Boolean = false): String {
        val (status, location, body) = open(page.url, form, ajax)
        AppLog.log("${if (form == null) "GET" else "POST"} ${page.url.substringAfter(base).substringBefore('?')}: HTTP $status, ${body.length / 1024} KB")
        sessionLost?.let {
            AppLog.log("session lost: $it")
            throw SessionExpired(it)
        }
        if (status in 301..303) {
            if ("/Login" in location) throw SessionExpired("redirected to login")
            throw IllegalStateException("unexpected redirect to $location")
        }
        if (status >= 400) throw IllegalStateException("HTTP $status")
        return body
    }

    /** Same form post as the portal's Log In button (no CAPTCHA on login). */
    fun login(user: String, password: String) {
        cookies.clear()
        // A new session needs the page loaded again before any Refresh postback.
        resetMap()
        info.fields.clear()
        val (pageStatus, _, html) = open(base + LOGIN)
        val page = Jsoup.parse(html)
        if (pageStatus != 200 || page.getElementsByAttributeValue("name", LOGIN_USER).isEmpty()) {
            AppLog.log("login page: HTTP $pageStatus, ${if (pageStatus == 200) "no login form" else "not loaded"}")
            throw LoginFailed("No BusPlanner login page at this address")
        }
        val form = HashMap(hiddenFields(page))
        form["__EVENTTARGET"] = LOGIN_BUTTON
        form["__EVENTARGUMENT"] = ""
        form[LOGIN_USER] = user
        form[LOGIN_PASSWORD] = password
        val (status, location, _) = open(base + LOGIN, form)
        val ok = ".ASPXAUTH" in cookies && status in 301..303 && "/Login" !in location
        AppLog.log("login: ${if (ok) "accepted" else "refused"} (HTTP $status)")
        // A login hands out new cookies on purpose.
        sessionLost = null
        if (!ok) {
            throw LoginFailed("BusPlanner did not accept the login")
        }
    }

    private fun absorb(page: PageState, html: String): Document {
        val doc = Jsoup.parse(html)
        page.fields.putAll(formFields(doc))
        val (students, selected) = studentOptions(doc)
        if (students.isNotEmpty()) {
            page.students = students
            page.selected = selected
        }
        return doc
    }

    private fun form(page: PageState, target: String): HashMap<String, String> {
        // The whole form, as the browser posts it: with only the hidden fields, the timer
        // refresh kept answering with the position the page was loaded with.
        val form = HashMap(page.fields)
        form["__EVENTTARGET"] = target
        form["__EVENTARGUMENT"] = ""
        page.selected?.let { form[STUDENTS_DDL] = it }
        return form
    }

    private fun load(page: PageState): Document {
        val html = request(page)
        if ("name=\"$STUDENTS_DDL" !in html) throw SessionExpired("page has no student list (not logged in?)")
        return absorb(page, html)
    }

    /** Full postback, like changing the dropdown in the portal. */
    private fun select(page: PageState, guid: String): Document? {
        if (guid == page.selected) return null
        page.selected = guid
        return absorb(page, request(page, form(page, STUDENTS_DDL)))
    }

    /**
     * Selects the student, then asks for the bus position the way the portal's Refresh button
     * does: one async postback, no page load. The page's timer is disabled in the page itself,
     * and its postback only repeats the data the page was loaded with; the Refresh button makes
     * the server fetch a new position (checked in a browser on 2026-11-04).
     * The page is loaded only when there is none yet, or with [reload] (after an error).
     */
    fun refresh(guid: String, reload: Boolean = false): Reading {
        if (reload) resetMap()
        val loaded = map.students.isEmpty()
        if (loaded) {
            pageBulletin = bulletinText(load(map))
            AppLog.log("form fields sent: ${map.fields.keys.joinToString { it.substringAfterLast('$') }}")
        }
        select(map, guid)?.let { pageBulletin = bulletinText(it) }
        // A submit button posts its own name and value, with an empty event target.
        val form = form(map, "")
        form[MAP_REFRESH] = "Refresh"
        form[SCRIPT_MANAGER] = "$MAP_PANEL|$MAP_REFRESH"
        form["__ASYNCPOST"] = "true"
        val labels = ArrayList<String>()
        var bulletin: String? = null
        val answer = request(map, form, ajax = true)
        val entries = runCatching { parseDelta(answer) }.getOrDefault(emptyList())
        // Seen 2026-11-05/06: a 2 KB answer with no update at all, for a whole period; only a new
        // login brought positions back. Keeping the page's own (old) position would show a bus
        // standing still, so it counts as a dropped session: log in again, load the page again.
        if (entries.none { it.kind in setOf("updatePanel", "hiddenField", "pageRedirect", "error") }) {
            val page = Jsoup.parse(answer)
            val text = (page.title() + " · " + page.body().text()).take(200)
            AppLog.log("refresh answer is not an update (${answer.length} bytes): $text")
            throw SessionExpired("BusPlanner sent no update")
        }
        AppLog.log(
            (if (loaded) "page loaded, " else "") + "refresh: " +
                entries.groupingBy { it.kind }.eachCount().entries.joinToString { "${it.value} ${it.key}" }
        )
        for (entry in entries) {
            when (entry.kind) {
                "hiddenField" -> map.fields[entry.id] = entry.content
                "updatePanel" -> {
                    val doc = absorb(map, entry.content)
                    labels += timeLabels(doc)
                    bulletinText(doc)?.let { bulletin = it }
                }
                "pageRedirect" -> throw SessionExpired("server asked to redirect")
                "error" -> throw IllegalStateException("server error: ${entry.content}")
            }
        }
        val r = reading(map.fields, labels, bulletin)
        // The timer refresh does not always resend the bulletin; the full page has it.
        return if (r.bulletin == null && r.busLat == null) r.copy(bulletin = pageBulletin) else r
    }

    /** Every student on the account with the rows of their ChildTransportInfo page. */
    fun scan(): Map<Student, List<StopRow>> {
        var doc = load(info)
        val out = LinkedHashMap<Student, List<StopRow>>()
        for (student in info.students) {
            select(info, student.guid)?.let { doc = it }
            out[student] = transportRows(doc, info.fields[POSITION_FIELD])
        }
        resetMap()
        return out
    }

    /** The school's position from the SchoolInfo page: choosing it in the school dropdown, by its GUID. */
    fun schoolPosition(name: String): School? {
        val page = PageState(base + SCHOOL_PAGE)
        val doc = absorb(page, request(page))
        val select = doc.selectFirst("select[name\$=cbSchool]") ?: return null
        val option = select.select("option").firstOrNull { sameName(it.text(), name) } ?: return null
        val form = HashMap(page.fields)
        form[select.attr("name")] = option.attr("value")
        form["__EVENTTARGET"] = select.attr("name")
        form["__EVENTARGUMENT"] = ""
        val raw = hiddenFields(Jsoup.parse(request(page, form))).entries
            .firstOrNull { it.key.endsWith("hfSchoolPosition") }?.value ?: return null
        return parseSchoolPosition(name, raw)
    }

    private fun resetMap() {
        // The server may keep one selected student per session: reload the map page next time.
        map.fields.clear()
        map.students = emptyList()
        map.selected = null
    }
}

private fun sameName(a: String, b: String): Boolean {
    val x = a.trim().lowercase()
    val y = b.trim().lowercase()
    return x.isNotEmpty() && y.isNotEmpty() && (x == y || x.contains(y) || y.contains(x))
}

/** hfSchoolPosition is JSON with ^ in place of quotes; the position sits in a nested object. */
fun parseSchoolPosition(name: String, raw: String): School? {
    val text = raw.replace('^', '"')
    val lat = Regex("\"Latitude\"\\s*:\\s*(-?[0-9.]+)").find(text)?.groupValues?.get(1)?.toDoubleOrNull()
    val lon = Regex("\"Longitude\"\\s*:\\s*(-?[0-9.]+)").find(text)?.groupValues?.get(1)?.toDoubleOrNull()
    return if (lat != null && lon != null) School(name, lat, lon) else null
}

data class DeltaEntry(val kind: String, val id: String, val content: String)

/** MS Ajax delta format: length|type|id|content| repeated; content may contain '|'. */
/**
 * What one Set-Cookie says about the session, given the cookies held before it: a new server
 * session id, or the login cookie cleared, means the server forgot the session, even when the
 * answer itself looks normal. Null when nothing is wrong (the first cookies of a session too).
 */
fun cookieHint(held: Map<String, String>, name: String, value: String, gone: Boolean): String? {
    val before = held[name] ?: return null
    return when {
        name == ".ASPXAUTH" && gone -> "the server cleared the login cookie"
        name == "ASP.NET_SessionId" && !gone && value != before -> "the server started a new session"
        else -> null
    }
}

fun parseDelta(text: String): List<DeltaEntry> {
    val out = ArrayList<DeltaEntry>()
    var i = 0
    while (i < text.length) {
        val j = text.indexOf('|', i)
        if (j < 0) break
        val length = text.substring(i, j).trim().toInt()
        val k = text.indexOf('|', j + 1)
        val m = text.indexOf('|', k + 1)
        out += DeltaEntry(text.substring(j + 1, k), text.substring(k + 1, m), text.substring(m + 1, m + 1 + length))
        i = m + 1 + length + 1
    }
    return out
}

fun hiddenFields(doc: Document): Map<String, String> =
    doc.select("input[type=hidden][name]").associate { it.attr("name") to it.attr("value") }

/** Every field a browser would post with the form: inputs (but no buttons), checked boxes, selects, text areas. */
fun formFields(doc: Document): Map<String, String> {
    val out = LinkedHashMap<String, String>()
    for (e in doc.select("input[name]")) {
        val type = e.attr("type").lowercase().ifEmpty { "text" }
        if (type in setOf("submit", "button", "image", "reset", "file")) continue
        if (type in setOf("checkbox", "radio") && !e.hasAttr("checked")) continue
        out[e.attr("name")] = if (type in setOf("checkbox", "radio")) e.attr("value").ifEmpty { "on" } else e.attr("value")
    }
    for (s in doc.select("select[name]")) {
        val options = s.select("option")
        val chosen = options.firstOrNull { it.hasAttr("selected") } ?: options.firstOrNull() ?: continue
        out[s.attr("name")] = chosen.attr("value")
    }
    for (t in doc.select("textarea[name]")) out[t.attr("name")] = t.text()
    return out
}

/** "Updated Time" then "Latest Reported Time": the only labels without an id in the map panel. */
fun timeLabels(doc: Document): List<String> =
    doc.select("label:not([id])").map { it.text().trim() }.filter { it.isNotEmpty() }

/** The bulletin's text, without the "×" of the close button the portal puts inside it. */
fun bulletinText(doc: Document): String? {
    val label = doc.selectFirst("[id\$=lblBulletin]")?.clone() ?: return null
    label.select("button, a, [class*=close], [data-dismiss], [data-bs-dismiss]").remove()
    return label.text().trim().trimEnd('×', ' ').takeIf { it.isNotEmpty() }
}

fun reading(fields: Map<String, String>, labels: List<String>, bulletin: String?): Reading {
    val bus = JSONArray(fields[BUS_FIELD]?.takeIf { it.isNotBlank() } ?: "[]").optJSONObject(0)
    val stop = JSONArray(fields[STOP_FIELD]?.takeIf { it.isNotBlank() } ?: "[]").optJSONObject(0)
    return Reading(
        busLat = bus?.optDouble("Latitude")?.takeUnless { it.isNaN() },
        busLon = bus?.optDouble("Longitude")?.takeUnless { it.isNaN() },
        stopLat = stop?.optDouble("Latitude")?.takeUnless { it.isNaN() },
        stopLon = stop?.optDouble("Longitude")?.takeUnless { it.isNaN() },
        runLabel = stop?.optString("Label")?.takeIf { it.isNotBlank() },
        updated = labels.getOrNull(0),
        reported = labels.getOrNull(1),
        bulletin = bulletin,
    )
}

private val STOP_TYPE_ID = Regex("^(.*rpTransportation_rTransportation_.*)lblStopType_(\\d+)$")

/**
 * The stop rows of ChildTransportInfo, with coordinates from hfTransportationPosition.
 * Each row's labels share an id prefix and index: <prefix>lblStopType_<i>, <prefix>lblArrivalValue_<i>, …
 */
fun transportRows(doc: Document, positionJson: String?): List<StopRow> {
    val positions = JSONArray(positionJson?.takeIf { it.isNotBlank() } ?: "[]")
    return doc.getElementsByAttributeValueMatching("id", STOP_TYPE_ID.toPattern()).mapNotNull { e ->
        val m = STOP_TYPE_ID.find(e.id()) ?: return@mapNotNull null
        val (prefix, i) = m.destructured
        fun label(name: String) = doc.getElementById("$prefix${name}_$i")?.text()?.trim()
        val time = label("lblArrivalValue") ?: ""
        val place = label("lblDetailLocationValue") ?: ""
        // Positions only exist for the stops near home; match them by address and AM/PM.
        val pos = (0 until positions.length()).map { positions.getJSONObject(it) }.firstOrNull {
            it.optString("Description").equals(place, ignoreCase = true) &&
                it.optString("Time").takeLast(2) == time.takeLast(2)
        }
        StopRow(
            stopType = e.text().trim(),
            time = time,
            location = place,
            route = label("RunRouteInfo")?.takeIf { it.isNotEmpty() },
            lat = pos?.optString("Latitude")?.toDoubleOrNull(),
            lon = pos?.optString("Longitude")?.toDoubleOrNull(),
        )
    }
}

/** Students of the dropdown, and the selected one (the first when none is marked). */
fun studentOptions(doc: Document): Pair<List<Student>, String?> {
    val options = doc.select("select[name=$STUDENTS_DDL] option")
    val students = options.map { Student(it.attr("value"), it.text().trim()) }
    val selected = options.firstOrNull { it.hasAttr("selected") }?.attr("value") ?: students.firstOrNull()?.guid
    return students to selected
}
