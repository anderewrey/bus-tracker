// SPDX-FileCopyrightText: 2026 anderewrey
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.anderewrey.bustracker.data

import org.json.JSONArray
import org.json.JSONObject

/** The official app's id; test builds add a suffix (DESIGN.md §8, Versions). */
const val OFFICIAL_APP_ID = "io.github.anderewrey.bustracker"

/** Only apps signed with the app's key hold it, so only they can read the official app's configuration. */
const val EXPORT_PERMISSION = "io.github.anderewrey.bustracker.permission.EXPORT_CONFIG"

/** The version of the export's layout; an import refuses another one. */
const val TRANSFER_FORMAT = 1

/** The app's preference files, each owned by one store. */
const val STORE_FILE = "bustracker"
const val ZONES_FILE = "zones"
const val SCHOOLS_FILE = "schools"
const val SETTINGS_FILE = "settings"

/** The preference files copied: login and runs, zones, schools, settings. */
val TRANSFER_FILES = listOf(STORE_FILE, ZONES_FILE, SCHOOLS_FILE, SETTINGS_FILE)

/**
 * What a copy leaves out: the password, encrypted with a key only its own app can use (it travels
 * apart, decrypted, and is encrypted again on arrival), and the notification channels' versions,
 * which belong to each app's own channels.
 */
fun transferSkips(file: String, key: String): Boolean =
    (file == STORE_FILE && key == "password") || (file == SETTINGS_FILE && key.startsWith("channel_"))

/** One preference file as JSON, each value with its type, so it comes back as the same type. */
fun prefsToJson(file: String, all: Map<String, *>): JSONObject {
    val o = JSONObject()
    all.forEach { (k, v) ->
        if (transferSkips(file, k)) return@forEach
        val typed = when (v) {
            is String -> JSONObject().put("s", v)
            is Boolean -> JSONObject().put("b", v)
            is Int -> JSONObject().put("i", v)
            is Long -> JSONObject().put("l", v)
            is Float -> JSONObject().put("f", v.toDouble())
            is Set<*> -> JSONObject().put("set", JSONArray(v.map { it.toString() }))
            else -> null
        }
        typed?.let { o.put(k, it) }
    }
    return o
}

/** A value written by [prefsToJson], back as its type; null for anything else. */
sealed class PrefValue {
    data class S(val v: String) : PrefValue()
    data class B(val v: Boolean) : PrefValue()
    data class I(val v: Int) : PrefValue()
    data class L(val v: Long) : PrefValue()
    data class F(val v: Float) : PrefValue()
    data class StringSet(val v: Set<String>) : PrefValue()
}

fun prefsFromJson(file: String, o: JSONObject): Map<String, PrefValue> = o.keys().asSequence().mapNotNull { k ->
    if (transferSkips(file, k)) return@mapNotNull null
    val t = o.optJSONObject(k) ?: return@mapNotNull null
    val v = when {
        t.has("s") -> PrefValue.S(t.getString("s"))
        t.has("b") -> PrefValue.B(t.getBoolean("b"))
        t.has("i") -> PrefValue.I(t.getInt("i"))
        t.has("l") -> PrefValue.L(t.getLong("l"))
        t.has("f") -> PrefValue.F(t.getDouble("f").toFloat())
        t.has("set") -> t.getJSONArray("set").let { a -> PrefValue.StringSet((0 until a.length()).map { a.getString(it) }.toSet()) }
        else -> null
    }
    v?.let { k to it }
}.toMap()
