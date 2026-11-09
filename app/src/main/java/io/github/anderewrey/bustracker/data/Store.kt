// SPDX-FileCopyrightText: 2026 anderewrey
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.anderewrey.bustracker.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import io.github.anderewrey.bustracker.net.School
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import java.time.LocalDate
import java.time.LocalDateTime
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Login, runs and the last scan report, kept in app-private preferences. */
class Store(context: Context) {
    private val prefs = context.getSharedPreferences("bustracker", Context.MODE_PRIVATE)

    /** The BusPlanner portal, "https://host" (see portalAddress); none for a login saved before 0.13. */
    val portal: String? get() = prefs.getString("portal", null)

    val email: String? get() = prefs.getString("email", null)

    val password: String?
        get() = prefs.getString("password", null)?.let { runCatching { decrypt(it) }.getOrNull() }

    /** All three are needed to work; a login saved before the portal field has no portal. */
    val hasLogin: Boolean get() = portal != null && email != null && password != null

    fun saveLogin(portal: String, email: String, password: String) {
        prefs.edit().putString("portal", portal).putString("email", email).putString("password", encrypt(password)).apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    var runs: List<Run>
        get() {
            val a = JSONArray(prefs.getString("runs", "[]"))
            return (0 until a.length()).map { Run.fromJson(a.getJSONObject(it)) }
        }
        set(value) {
            prefs.edit().putString("runs", JSONArray(value.map { it.toJson() }).toString()).apply()
        }

    var schools: List<School>
        get() {
            val a = JSONArray(prefs.getString("schools", "[]"))
            return (0 until a.length()).map {
                val o = a.getJSONObject(it)
                School(o.getString("name"), o.getDouble("lat"), o.getDouble("lon"))
            }
        }
        set(value) {
            val a = JSONArray(value.map { JSONObject().put("name", it.name).put("lat", it.lat).put("lon", it.lon) })
            prefs.edit().putString("schools", a.toString()).apply()
        }

    /** Changes found by rescans that the user has not acknowledged yet. */
    var changes: List<RunChange>
        get() {
            val a = JSONArray(prefs.getString("changes", "[]"))
            return (0 until a.length()).mapNotNull { runCatching { RunChange.fromJson(a.getJSONObject(it)) }.getOrNull() }
        }
        set(value) {
            prefs.edit().putString("changes", JSONArray(value.map { it.toJson() }).toString()).apply()
        }

    /** The day of the last successful scan, for the daily rescan. */
    var lastScan: LocalDate?
        get() = prefs.getString("lastScan", null)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        set(value) {
            prefs.edit().putString("lastScan", value?.toString()).apply()
        }

    /** When each run's main-zone alarm last rang; a run rings once per monitoring period (one a day). */
    var rang: Map<String, LocalDateTime>
        get() = times("rang") { s ->
            // Up to 0.10 only the day was kept.
            runCatching { LocalDateTime.parse(s) }.getOrElse { LocalDate.parse(s).atStartOfDay() }
        }
        set(value) = putTimes("rang", value)

    /** The Alarm switch's choices for single periods; entries older than two days are dropped. */
    var choices: PeriodChoices
        get() = PeriodChoices(times("skipped"), times("forced"), times("ended"))
        set(value) {
            val keep = LocalDateTime.now().minusDays(2)
            fun recent(m: Map<String, LocalDateTime>) = m.filterValues { it.isAfter(keep) }
            putTimes("skipped", recent(value.skipped))
            putTimes("forced", recent(value.forced))
            putTimes("ended", recent(value.ended))
        }

    private fun times(name: String, parse: (String) -> LocalDateTime = LocalDateTime::parse): Map<String, LocalDateTime> {
        val o = JSONObject(prefs.getString(name, "{}"))
        return o.keys().asSequence().mapNotNull { k -> runCatching { k to parse(o.getString(k)) }.getOrNull() }.toMap()
    }

    private fun putTimes(name: String, value: Map<String, LocalDateTime>) {
        prefs.edit().putString(name, JSONObject(value.mapValues { it.value.toString() }).toString()).apply()
    }

    /** The BusPlanner notices that already rang, so a restarted app doesn't ring them again. */
    var seenNotices: Set<String>
        get() = prefs.getStringSet("seenNotices", emptySet())!!.toSet()
        set(value) {
            prefs.edit().putStringSet("seenNotices", value).apply()
        }

    /** The switch over all automatic starts; on when never set. */
    var autoStart: AutoStart
        get() = AutoStart(
            prefs.getBoolean("autoOn", true),
            prefs.getString("autoResumeOn", null)?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
        )
        set(value) {
            prefs.edit().putBoolean("autoOn", value.on).putString("autoResumeOn", value.resumeOn?.toString()).apply()
        }

    /** Drops what older versions kept and no longer use (the v0.1 scan report). */
    fun dropObsolete() {
        if (prefs.contains("scanReport")) prefs.edit().remove("scanReport").apply()
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val out = cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    private fun decrypt(stored: String): String {
        val data = Base64.decode(stored, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data, 0, IV_BYTES))
        return String(cipher.doFinal(data, IV_BYTES, data.size - IV_BYTES), Charsets.UTF_8)
    }

    private companion object {
        const val ALIAS = "busplanner_password"
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
    }
}
