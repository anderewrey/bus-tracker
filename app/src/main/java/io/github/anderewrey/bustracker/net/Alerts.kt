// SPDX-FileCopyrightText: 2026 anderewrey
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.anderewrey.bustracker.net

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit

const val ALERTS_PATH = "/Alerts.aspx/GetBusNotifications"

/**
 * What the portal's Alerts page posts for its "Transportation Alerts" table. RangeType "" is what
 * the page sends for Today ("1" is Future). Checked in a browser on 2026-11-05: no login needed.
 */
private const val ALERTS_BODY =
    """{"alertCondition":{"RangeType":""},"dataTableData":{"draw":1,"start":0,"length":100,""" +
        """"columns":[],"order":[],"search":{"value":"","regex":false},"SortFieldName":"RouteRun"}}"""

/** One row of the portal's Transportation Alerts, e.g. B102 "Delayed 15 minutes from original time". */
data class BusAlert(
    val routeRun: String,
    val action: String,
    val comment: String,
    val appliesTo: String,
    val created: String,
    val createdMs: Long?,
    val operator: String,
    val schools: String,
) {
    /** The bus number, e.g. "B102" from "B102 (B102 XYZ_ABC_PU)". */
    val bus: String get() = routeRun.trim().substringBefore(' ').substringBefore('(')

    /** Stays the same while the notice is listed; a new notice for the same bus gets a new one. */
    val id: String get() = "${createdMs ?: created} $routeRun"
}

private val DATE_MS = Regex("Date\\((\\d+)\\)")

/** The page method's answer: {d: {data: [{RouteRun, Action, Comment, …}]}}. */
fun parseBusAlerts(json: String): List<BusAlert> {
    val data = JSONObject(json).optJSONObject("d")?.optJSONArray("data") ?: return emptyList()
    return (0 until data.length()).mapNotNull { data.optJSONObject(it) }.map { o ->
        fun s(name: String) = if (o.isNull(name)) "" else o.optString(name).trim()
        BusAlert(
            routeRun = s("RouteRun"),
            action = s("Action"),
            comment = s("Comment"),
            appliesTo = s("AppliesTo"),
            created = s("CreateTimeDisplay"),
            createdMs = DATE_MS.find(s("CreateTime"))?.groupValues?.get(1)?.toLongOrNull(),
            operator = s("Operator"),
            schools = s("AffectsSchools"),
        )
    }
}

/** The notices about one bus: its number as a whole word, so B10 never matches B102. */
fun alertsFor(alerts: List<BusAlert>, bus: String): List<BusAlert> =
    alerts.filter { it.bus.equals(bus.trim(), ignoreCase = true) }

/**
 * The notice that counts for one bus: the last one posted. A newer notice replaces the older
 * ones (on 2026-11-05, "Delayed 30 minutes" at 8:02 replaced "Delayed 15 minutes" at 7:36);
 * the page lists them by run, not by time.
 */
fun latestFor(alerts: List<BusAlert>, bus: String): BusAlert? =
    alertsFor(alerts, bus).withIndex().maxWithOrNull(compareBy<IndexedValue<BusAlert>>({ it.value.createdMs ?: Long.MIN_VALUE }, { it.index }))?.value

private val DELAY = Regex("""\bdelayed\s+(\d+)\s*min""", RegexOption.IGNORE_CASE)

/** The minutes of a "Delayed 30 Minutes from original time" notice; null for any other wording. */
fun delayMinutes(action: String): Int? = DELAY.find(action)?.groupValues?.get(1)?.toIntOrNull()

/** What the run's title gets in red (DESIGN.md §7.3): "delayed 30 minutes", or the notice's own words. */
fun noticeLabel(alert: BusAlert): String = delayMinutes(alert.action)?.let { "delayed $it minutes" } ?: alert.action

/**
 * Whether the notice was posted in the same half-day as a run planned at [planned] (before or
 * after noon), so a morning notice still listed in the afternoon doesn't touch the afternoon run.
 * True when either time is unknown.
 */
fun sameHalfDay(alert: BusAlert, planned: LocalTime?, zone: ZoneId = ZoneId.systemDefault()): Boolean {
    val ms = alert.createdMs ?: return true
    if (planned == null) return true
    val posted = Instant.ofEpochMilli(ms).atZone(zone).toLocalTime()
    return (posted.hour < 12) == (planned.hour < 12)
}

private val alertsHttp =OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).build()

/** Today's transportation alerts for every bus of the portal at [base]. */
fun fetchBusAlerts(base: String): List<BusAlert> {
    val req = Request.Builder().url(base + ALERTS_PATH)
        .header("User-Agent", UA)
        .post(ALERTS_BODY.toRequestBody("application/json; charset=utf-8".toMediaType()))
        .build()
    alertsHttp.newCall(req).execute().use { resp ->
        if (!resp.isSuccessful) throw IllegalStateException("alerts: HTTP ${resp.code}")
        return parseBusAlerts(resp.body.string())
    }
}
