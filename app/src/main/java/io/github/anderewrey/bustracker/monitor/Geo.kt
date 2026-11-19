// SPDX-FileCopyrightText: 2026 anderewrey
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.anderewrey.bustracker.monitor

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.os.Build
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val p1 = Math.toRadians(lat1)
    val p2 = Math.toRadians(lat2)
    val dp = p2 - p1
    val dl = Math.toRadians(lon2 - lon1)
    val h = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
    return 2 * 6371000 * asin(sqrt(h))
}

fun formatDistance(m: Double): String =
    if (m < 1000) "${(m / 10).roundToInt() * 10} m" else String.format(Locale.US, "%.1f km", m / 1000)

/**
 * Candidates asked per lookup. Google fills ranks 8 to 10 with a postal code or an area, but ranks
 * 6 and 7 often hold an address nearer the bus than the first 5 (DESIGN.md §7.2).
 */
const val GEOCODER_CANDIDATES = 10

/** The longest a street lookup may take; after it the street is "unknown" (DESIGN.md §7.2). */
const val GEOCODER_TIMEOUT_MS = 10_000L

/**
 * Street name(s) at a point, from the phone's built-in Geocoder: no name when there is none, and
 * expired after [GEOCODER_TIMEOUT_MS]. Asks for
 * [GEOCODER_CANDIDATES]: near a bus stop the first one is the stop, not a street (DESIGN.md §7.2). Safe
 * to call from the main thread: it never blocks it.
 */
suspend fun streetAt(context: Context, lat: Double, lon: Double): StreetAnswer {
    val answer = lookup(context, lat, lon, GEOCODER_CANDIDATES, GEOCODER_TIMEOUT_MS) ?: return StreetAnswer(null, expired = true)
    return StreetAnswer(answer.addresses?.let { streetFrom(it, lat, lon) })
}

/** The street text from the geocoder's candidates for the point [lat], [lon] (chooseStreet). */
fun streetFrom(found: List<Address>, lat: Double, lon: Double): String? =
    chooseStreet(found.map {
        Place(
            it.thoroughfare,
            if (it.hasLatitude() && it.hasLongitude()) distanceM(lat, lon, it.latitude, it.longitude) else null,
            if (it.maxAddressLineIndex >= 0) it.getAddressLine(0) else it.featureName,
        )
    })

/**
 * Where Android 10 to 12's blocking Geocoder call runs: never the main thread, and not tied to the
 * caller, so a call given up on finishes by itself in the background and its answer is dropped.
 */
private val blockingGeocoder = CoroutineScope(SupervisorJob() + Dispatchers.IO)

/**
 * Up to [max] candidates from the phone's built-in Geocoder, in its own order; null when there
 * is no geocoder, it failed, or it took more than [timeoutMs]. Never blocks the calling thread:
 * Android 13+ answers through a listener (given up on at once), Android 10 to 12 runs the
 * blocking call on a background thread.
 */
suspend fun addressesAt(
    context: Context, lat: Double, lon: Double, max: Int, timeoutMs: Long = GEOCODER_TIMEOUT_MS,
): List<Address>? = lookup(context, lat, lon, max, timeoutMs)?.addresses

/** The geocoder's answer, [addresses] null when it has none or failed; the whole is null when it took more than the timeout. */
private class Answered(val addresses: List<Address>?)

private suspend fun lookup(context: Context, lat: Double, lon: Double, max: Int, timeoutMs: Long): Answered? {
    if (!Geocoder.isPresent()) return Answered(null)
    val geocoder = Geocoder(context.applicationContext, Locale.getDefault())
    return withTimeoutOrNull(timeoutMs) {
        Answered(if (Build.VERSION.SDK_INT >= 33) {
            suspendCancellableCoroutine { cont ->
                geocoder.getFromLocation(lat, lon, max, object : Geocoder.GeocodeListener {
                    override fun onGeocode(addresses: MutableList<Address>) {
                        if (cont.isActive) cont.resume(addresses.toList())
                    }
                    override fun onError(errorMessage: String?) {
                        if (cont.isActive) cont.resume(null)
                    }
                })
            }
        } else {
            val answer = CompletableDeferred<List<Address>?>()
            blockingGeocoder.launch {
                @Suppress("DEPRECATION")
                answer.complete(runCatching { geocoder.getFromLocation(lat, lon, max)?.toList() }.getOrNull())
            }
            answer.await()
        })
    }
}
