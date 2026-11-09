// SPDX-FileCopyrightText: 2026 anderewrey
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.anderewrey.bustracker.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import io.github.anderewrey.bustracker.AppLog
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalTime
import io.github.anderewrey.bustracker.data.zonesFor
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory.fillColor
import org.maplibre.android.style.layers.PropertyFactory.fillOpacity
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.geojson.Feature
import org.maplibre.geojson.Polygon
import org.maplibre.android.style.layers.PropertyFactory.visibility
import java.time.format.DateTimeFormatter
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import io.github.anderewrey.bustracker.Session
import io.github.anderewrey.bustracker.data.Run
import org.maplibre.android.MapLibre
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.Alignment
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point

private val CHECKED = DateTimeFormatter.ofPattern("H:mm:ss")

/** Keyless OpenStreetMap vector style. */
internal const val STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"

/** Drives a MapView from the screen's lifecycle, and destroys it when the screen goes away. */
@Composable
internal fun MapLifecycle(mapView: MapView) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer) // replays ON_START and ON_RESUME
        onDispose {
            lifecycle.removeObserver(observer)
            mapView.onPause()
            mapView.onStop()
            mapView.onDestroy()
        }
    }
}

/** Hides the city's bus stops, which crowd the map; the layer names come from the OpenFreeMap style. */
internal fun hideTransit(s: Style): List<String> {
    val hidden = s.layers.filter { "transit" in it.id || "bus" in it.id }
    hidden.forEach { it.setProperties(visibility(Property.NONE)) }
    return hidden.map { it.id }
}

@Composable
fun MapScreen(run: Run, onBack: () -> Unit) {
    val context = LocalContext.current
    val status by Session.status.collectAsState()
    val current = status[run.key]
    val active by Session.active.collectAsState()
    val nextCheck by Session.nextCheck.collectAsState()
    val style = remember { mutableStateOf<Style?>(null) }
    val mapRef = remember { mutableStateOf<MapLibreMap?>(null) }
    // The view keeps fitting the bus, the stop and the run's whole zones until the user moves the map by hand; Fit view resumes it.
    val follow = remember { mutableStateOf(true) }
    val runZones = remember(run) { zonesFor(run, Session.zones.value) }
    val zoneCorners = remember(run) { runZones.flatMap { z -> z.corners.map { (lat, lon) -> LatLng(lat, lon) } } }
    val scope = rememberCoroutineScope()
    // BusPlanner's "no vehicle" message pops up at the top of the map only as the answer to a
    // tap on ↻ (user, 2026-11-06); the header just says "No vehicle". A snackbar rather than a
    // toast: Android 12+ cuts toasts to two lines. Goes away by itself after 8 s.
    val snackbar = remember { SnackbarHostState() }
    var askedAt by remember { mutableStateOf<LocalTime?>(null) }
    LaunchedEffect(current?.checkedAt) {
        val asked = askedAt ?: return@LaunchedEffect
        val checked = current?.checkedAt ?: return@LaunchedEffect
        if (checked.isBefore(asked)) return@LaunchedEffect
        askedAt = null
        val bulletin = current.reading?.takeIf { it.busLat == null }?.bulletin ?: return@LaunchedEffect
        withTimeoutOrNull(BULLETIN_MS) { snackbar.showSnackbar(bulletin, withDismissAction = true, duration = SnackbarDuration.Indefinite) }
    }
    var refreshing by remember { mutableStateOf(false) }

    val mapView = remember {
        MapLibre.getInstance(context)
        MapView(context).apply {
            onCreate(null)
            getMapAsync { map ->
                // The run's stop; with no position known yet, the whole world until the bus shows up.
                val lat = run.lat ?: current?.reading?.stopLat
                val lon = run.lon ?: current?.reading?.stopLon
                map.cameraPosition = CameraPosition.Builder()
                    .target(LatLng(lat ?: 0.0, lon ?: 0.0)).zoom(if (lat != null && lon != null) 14.0 else 2.0).build()
                map.addOnCameraMoveStartedListener { reason ->
                    if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) follow.value = false
                }
                mapRef.value = map
                map.setStyle(Style.Builder().fromUri(STYLE_URL)) { s ->
                    val hidden = hideTransit(s)
                    AppLog.log("map style loaded, hidden layers: ${hidden.joinToString().ifEmpty { "none" }}")
                    val zones = runZones.map { z ->
                        Feature.fromGeometry(Polygon.fromLngLats(listOf((z.corners + z.corners.first()).map { (lat, lon) -> Point.fromLngLat(lon, lat) })))
                    }
                    s.addSource(GeoJsonSource("zones", FeatureCollection.fromFeatures(zones)))
                    s.addLayer(FillLayer("zones-fill", "zones").withProperties(fillColor("#F2A900"), fillOpacity(0.15f)))
                    s.addLayer(LineLayer("zones-line", "zones").withProperties(lineColor("#C78A00"), lineWidth(2f)))
                    s.addSource(GeoJsonSource("stop"))
                    s.addSource(GeoJsonSource("bus"))
                    s.addLayer(
                        CircleLayer("stop-dot", "stop").withProperties(
                            circleRadius(7f), circleColor("#1565C0"),
                            circleStrokeColor("#FFFFFF"), circleStrokeWidth(2f),
                        )
                    )
                    s.addLayer(
                        CircleLayer("bus-dot", "bus").withProperties(
                            circleRadius(9f), circleColor("#F2A900"),
                            circleStrokeColor("#1F1F1F"), circleStrokeWidth(2f),
                        )
                    )
                    style.value = s
                }
            }
        }
    }

    MapLifecycle(mapView)

    // Moves the dots whenever a new reading arrives, and keeps them and the zones in view while following.
    LaunchedEffect(style.value, current, follow.value) {
        val s = style.value ?: return@LaunchedEffect
        val r = current?.reading
        // The run's own stop: the live page is per child and shows one stop for the AM and PM runs alike.
        val stopLat = run.lat ?: r?.stopLat
        val stopLon = run.lon ?: r?.stopLon
        val stop = if (stopLat != null && stopLon != null) LatLng(stopLat, stopLon) else null
        val bus = if (r?.busLat != null && r.busLon != null) LatLng(r.busLat, r.busLon) else null
        (s.getSource("stop") as? GeoJsonSource)?.setGeoJson(
            stop?.let { Point.fromLngLat(it.longitude, it.latitude).toFeatureCollection() } ?: FeatureCollection.fromFeatures(emptyList())
        )
        (s.getSource("bus") as? GeoJsonSource)?.setGeoJson(
            bus?.let { Point.fromLngLat(it.longitude, it.latitude).toFeatureCollection() } ?: FeatureCollection.fromFeatures(emptyList())
        )
        val map = mapRef.value
        if (follow.value && map != null) fit(map, listOfNotNull(bus, stop) + zoneCorners, (FIT_PADDING_DP * context.resources.displayMetrics.density).toInt())
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp)) {
            TextButton(onClick = onBack) { Text("Back") }
            Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                RunTitle(run, current?.reading?.runLabel ?: run.key, MaterialTheme.typography.titleMedium)
                current?.let { if (it.line.isNotEmpty()) Text(it.line, color = if (it.old) MaterialTheme.colorScheme.error else Color.Unspecified) }
                current?.checkedAt?.let { Text("checked ${it.format(CHECKED)}", style = MaterialTheme.typography.bodySmall) }
            }
            AlarmSwitchCompact(run)
            RefreshRing(monitored = run.key in active, nextCheck = nextCheck, refreshing = refreshing) {
                askedAt = LocalTime.now()
                if (run.key in active) {
                    // The monitoring loop checks now and starts its 30 s again from there.
                    Session.checkNow()
                } else {
                    refreshing = true
                    scope.launch {
                        try {
                            Session.poll(context, run, refreshButton = true)
                        } catch (_: Exception) {
                            // Session.poll already logged it.
                        } finally {
                            refreshing = false
                        }
                    }
                }
            }
        }
        Box(Modifier.fillMaxSize()) {
            AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
            // Only after the user moved the map by hand: turns the automatic fitting back on.
            if (!follow.value) FilledTonalButton(
                onClick = {
                    follow.value = true
                    val r = current?.reading
                    val points = listOfNotNull(
                        if (r?.busLat != null && r.busLon != null) LatLng(r.busLat, r.busLon) else null,
                        (run.lat ?: r?.stopLat)?.let { lat -> (run.lon ?: r?.stopLon)?.let { LatLng(lat, it) } },
                    ) + zoneCorners
                    mapRef.value?.let { fit(it, points, (FIT_PADDING_DP * context.resources.displayMetrics.density).toInt()) }
                },
                modifier = Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(16.dp),
            ) { Text("Fit view") }
            SnackbarHost(snackbar, Modifier.align(Alignment.TopCenter))
        }
    }
}

private const val FIT_PADDING_DP = 64
private const val BULLETIN_MS = 8_000L
private const val MAX_FIT_ZOOM = 16.0

/** Shows all the points together (bus, stop, zone corners); with one point only, centres on it. */
private fun fit(map: MapLibreMap, points: List<LatLng>, paddingPx: Int) {
    when (points.size) {
        0 -> return
        1 -> map.animateCamera(CameraUpdateFactory.newLatLngZoom(points[0], 15.0), 600)
        else -> {
            val bounds = LatLngBounds.Builder().includes(points).build()
            val camera = map.getCameraForLatLngBounds(bounds, intArrayOf(paddingPx, paddingPx, paddingPx, paddingPx)) ?: return
            val target = camera.target ?: return
            // When the bus is at the stop, don't zoom in to the level of a single house.
            val zoom = minOf(camera.zoom, MAX_FIT_ZOOM)
            map.animateCamera(CameraUpdateFactory.newCameraPosition(CameraPosition.Builder().target(target).zoom(zoom).build()), 600)
        }
    }
}

private fun Point.toFeatureCollection(): FeatureCollection =
    FeatureCollection.fromFeature(Feature.fromGeometry(this))
