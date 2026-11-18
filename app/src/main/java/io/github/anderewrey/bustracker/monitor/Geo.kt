// SPDX-FileCopyrightText: 2026 anderewrey
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.anderewrey.bustracker.monitor

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.os.Build
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
 * Street name at a point, from the phone's built-in Geocoder; null when unavailable. Asks for 5
 * candidates: near a bus stop the first one is the stop, not a street (DESIGN.md §7.2).
 */
suspend fun streetAt(context: Context, lat: Double, lon: Double): String? =
    addressesAt(context, lat, lon, 5)?.let { found ->
        chooseStreet(found.map { Place(it.thoroughfare, if (it.maxAddressLineIndex >= 0) it.getAddressLine(0) else it.featureName) })
    }

/**
 * Up to [max] candidates from the phone's built-in Geocoder, in its own order; null when there
 * is no geocoder, it failed, or it took more than 5 s.
 */
suspend fun addressesAt(context: Context, lat: Double, lon: Double, max: Int): List<Address>? {
    if (!Geocoder.isPresent()) return null
    val geocoder = Geocoder(context, Locale.getDefault())
    return withTimeoutOrNull(5000) {
        if (Build.VERSION.SDK_INT >= 33) {
            suspendCancellableCoroutine { cont ->
                geocoder.getFromLocation(lat, lon, max, object : Geocoder.GeocodeListener {
                    override fun onGeocode(addresses: MutableList<Address>) = cont.resume(addresses.toList())
                    override fun onError(errorMessage: String?) = cont.resume(null)
                })
            }
        } else {
            @Suppress("DEPRECATION")
            runCatching { geocoder.getFromLocation(lat, lon, max)?.toList() }.getOrNull()
        }
    }
}
