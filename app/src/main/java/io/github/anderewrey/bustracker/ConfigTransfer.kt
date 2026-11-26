// SPDX-FileCopyrightText: 2026 anderewrey
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.anderewrey.bustracker

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import io.github.anderewrey.bustracker.data.EXPORT_PERMISSION
import io.github.anderewrey.bustracker.data.OFFICIAL_APP_ID
import io.github.anderewrey.bustracker.data.transferSkips
import io.github.anderewrey.bustracker.data.SETTINGS_FILE
import io.github.anderewrey.bustracker.data.PrefValue
import io.github.anderewrey.bustracker.data.Store
import io.github.anderewrey.bustracker.data.TRANSFER_FILES
import io.github.anderewrey.bustracker.data.TRANSFER_FORMAT
import io.github.anderewrey.bustracker.data.prefsFromJson
import io.github.anderewrey.bustracker.data.prefsToJson
import io.github.anderewrey.bustracker.monitor.MonitorService
import io.github.anderewrey.bustracker.monitor.RingSettings
import io.github.anderewrey.bustracker.monitor.Ringing
import io.github.anderewrey.bustracker.monitor.Scheduler
import io.github.anderewrey.bustracker.monitor.remakeChannel
import io.github.anderewrey.bustracker.monitor.ringSettings
import org.json.JSONObject

private const val EXPORT_METHOD = "export"
private const val EXPORT_KEY = "config"

/** "Bus Tracker dev" and "Bus Tracker PR N": they can import the official app's configuration. */
fun isTestBuild(context: Context): Boolean = context.packageName != OFFICIAL_APP_ID

/** Everything an import copies (DESIGN.md §8, Configuration import), as JSON. */
fun exportConfig(context: Context): JSONObject {
    val prefs = JSONObject()
    TRANSFER_FILES.forEach { f -> prefs.put(f, prefsToJson(f, context.getSharedPreferences(f, Context.MODE_PRIVATE).all)) }
    val ringing = JSONObject()
    Ringing.entries.forEach { type ->
        val r = ringSettings(context, type)
        ringing.put(type.base, JSONObject().put("sound", r.sound?.toString() ?: JSONObject.NULL).put("vibrate", r.vibrate))
    }
    return JSONObject()
        .put("format", TRANSFER_FORMAT)
        .put("prefs", prefs)
        .put("password", Store(context).password ?: JSONObject.NULL)
        .put("ringing", ringing)
}

/**
 * The official app's side: answers "export" to apps holding [EXPORT_PERMISSION], which Android
 * gives only to apps signed with the same key. Android doesn't check permissions on call(), so
 * it is checked here.
 */
class ConfigExport : ContentProvider() {
    override fun onCreate() = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val ctx = context ?: return null
        if (method != EXPORT_METHOD) return null
        if (ctx.checkCallingPermission(EXPORT_PERMISSION) != PackageManager.PERMISSION_GRANTED) {
            throw SecurityException("$EXPORT_PERMISSION needed")
        }
        AppLog.log("configuration exported to ${callingPackage ?: "another app"}")
        return Bundle().apply { putString(EXPORT_KEY, exportConfig(ctx).toString()) }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}

class ImportFailed(message: String) : Exception(message)

/**
 * A test build's side: copies the official app's sign-in, runs, zones, schools and settings over
 * this app's own, which are replaced. Watching stops first; automatic starts follow the copy.
 * Runs off the main thread. Returns a line for the user.
 */
fun importFromOfficial(context: Context): String {
    val bundle = try {
        context.contentResolver.call(Uri.parse("content://$OFFICIAL_APP_ID.export"), EXPORT_METHOD, null, null)
    } catch (e: SecurityException) {
        throw ImportFailed("Bus Tracker refused: it is signed with another key.")
    } catch (e: IllegalArgumentException) {
        null
    }
    val text = bundle?.getString(EXPORT_KEY)
        ?: throw ImportFailed("Bus Tracker isn't installed, or its version can't export yet: update it first.")
    val o = JSONObject(text)
    val format = o.optInt("format")
    if (format != TRANSFER_FORMAT) throw ImportFailed("Bus Tracker's export is format $format, this build reads $TRANSFER_FORMAT: update both.")

    Session.runs.value.forEach { MonitorService.stop(context, it.key) }
    Session.logout(context)
    val prefs = o.getJSONObject("prefs")
    TRANSFER_FILES.forEach { f ->
        val e = context.getSharedPreferences(f, Context.MODE_PRIVATE).edit()
        // The channel versions stay: they name this app's own channels (transferSkips).
        if (f == SETTINGS_FILE) context.getSharedPreferences(f, Context.MODE_PRIVATE).all.keys.filterNot { transferSkips(f, it) }.forEach { e.remove(it) }
        else e.clear()
        prefsFromJson(f, prefs.optJSONObject(f) ?: JSONObject()).forEach { (k, v) ->
            when (v) {
                is PrefValue.S -> e.putString(k, v.v)
                is PrefValue.B -> e.putBoolean(k, v.v)
                is PrefValue.I -> e.putInt(k, v.v)
                is PrefValue.L -> e.putLong(k, v.v)
                is PrefValue.F -> e.putFloat(k, v.v)
                is PrefValue.StringSet -> e.putStringSet(k, v.v)
            }
        }
        e.commit()
    }
    val store = Store(context)
    val password = if (o.isNull("password")) null else o.getString("password")
    val portal = store.portal
    val email = store.email
    if (password != null && portal != null && email != null) store.saveLogin(portal, email, password)

    // Sounds and vibration live in the notification channels: made again here with the official app's.
    val ringing = o.optJSONObject("ringing")
    Ringing.entries.forEach { type ->
        val r = ringing?.optJSONObject(type.base)
        remakeChannel(context, type) { now ->
            if (r == null) now else RingSettings(if (r.isNull("sound")) null else Uri.parse(r.getString("sound")), r.optBoolean("vibrate", true))
        }
    }

    Session.init(context)
    Scheduler.reschedule(context)
    val runs = Session.runs.value.size
    val zones = Session.zones.value.size
    AppLog.log("configuration imported from Bus Tracker: $runs runs, $zones zones${if (store.hasLogin) "" else ", no sign-in"}")
    return "Imported: $runs runs, $zones zones." + if (store.hasLogin) "" else " Sign in again: the password couldn't be copied."
}
