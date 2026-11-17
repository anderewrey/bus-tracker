// SPDX-FileCopyrightText: 2026 anderewrey
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.anderewrey.bustracker.ui

import android.graphics.PointF
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.anderewrey.bustracker.Session
import io.github.anderewrey.bustracker.data.AT_SCHOOL_M
import io.github.anderewrey.bustracker.data.MAX_SCHOOL_RADIUS_M
import io.github.anderewrey.bustracker.data.MIN_SCHOOL_RADIUS_M
import io.github.anderewrey.bustracker.data.PositionSource
import io.github.anderewrey.bustracker.data.SchoolFix
import io.github.anderewrey.bustracker.data.Store
import io.github.anderewrey.bustracker.data.circlePoints
import io.github.anderewrey.bustracker.monitor.distanceM
import kotlinx.coroutines.launch
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.layers.PropertyFactory.fillColor
import org.maplibre.android.style.layers.PropertyFactory.fillOpacity
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon
import kotlin.math.roundToInt

private const val PIN_DP = 28

/**
 * One school (DESIGN.md §5.3): its point and its "at school" circle on a map. Drag the pin (or
 * tap the map) to where the bus really parks, resize the circle, use where the bus was last seen
 * parked, or go back to BusPlanner's position. When its name matched several of BusPlanner's
 * schools, the user chooses the right one first.
 */
@Composable
fun SchoolScreen(name: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val spots by Session.schools.collectAsState()
    val fixes by Session.schoolFixes.collectAsState()
    val parkedAll by Session.parked.collectAsState()
    val optionsAll by Session.schoolOptions.collectAsState()
    val runs by Session.runs.collectAsState()
    val spot = spots.firstOrNull { it.name == name }
    val busPlanner = remember(spots) { Store(context).schools.firstOrNull { it.name.equals(name, ignoreCase = true) } }
    val parked = parkedAll[name]
    val options = optionsAll[name].orEmpty()
    val going = runs.filter { it.school.equals(name, ignoreCase = true) }

    // What the page shows, saved only by Save.
    val pin = remember { mutableStateOf(spot?.takeIf { it.known }?.let { LatLng(it.lat!!, it.lon!!) }) }
    var radius by remember { mutableFloatStateOf((spot?.radius ?: AT_SCHOOL_M).toFloat()) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val mapRef = remember { mutableStateOf<MapLibreMap?>(null) }
    val style = remember { mutableStateOf<Style?>(null) }
    var cameraTick by remember { mutableIntStateOf(0) }

    val mapView = remember {
        MapLibre.getInstance(context)
        MapView(context).apply {
            onCreate(null)
            getMapAsync { map ->
                // Without a position: around the runs' stops, where the school can't be far.
                val home = going.firstOrNull { it.lat != null }?.let { LatLng(it.lat!!, it.lon!!) }
                val target = pin.value ?: home ?: LatLng(0.0, 0.0)
                map.cameraPosition = CameraPosition.Builder().target(target).zoom(if (pin.value != null) 16.0 else if (home != null) 13.0 else 2.0).build()
                map.addOnCameraMoveListener { cameraTick++ }
                map.addOnCameraIdleListener { cameraTick++ }
                // A tap puts the pin there, so a school with no position can be placed.
                map.addOnMapClickListener { point ->
                    pin.value = point
                    true
                }
                mapRef.value = map
                map.setStyle(Style.Builder().fromUri(STYLE_URL)) { s ->
                    hideTransit(s)
                    s.addSource(GeoJsonSource("circle"))
                    s.addLayer(FillLayer("circle-fill", "circle").withProperties(fillColor("#2E7D32"), fillOpacity(0.15f)))
                    s.addLayer(LineLayer("circle-line", "circle").withProperties(lineColor("#2E7D32"), lineWidth(2f)))
                    // BusPlanner's own point, and where the bus was last seen parked.
                    s.addSource(GeoJsonSource("busplanner", busPlanner?.let { FeatureCollection.fromFeature(Feature.fromGeometry(Point.fromLngLat(it.lon, it.lat))) }))
                    s.addLayer(
                        CircleLayer("busplanner-dot", "busplanner").withProperties(
                            circleRadius(5f), circleColor("#FFFFFF"), circleStrokeColor("#6D6D6D"), circleStrokeWidth(2f),
                        )
                    )
                    s.addSource(GeoJsonSource("parked", parked?.let { FeatureCollection.fromFeature(Feature.fromGeometry(Point.fromLngLat(it.second, it.first))) }))
                    s.addLayer(
                        CircleLayer("parked-dot", "parked").withProperties(
                            circleRadius(6f), circleColor("#1565C0"), circleStrokeColor("#FFFFFF"), circleStrokeWidth(2f),
                        )
                    )
                    style.value = s
                }
            }
        }
    }
    MapLifecycle(mapView)

    LaunchedEffect(style.value, pin.value, radius) {
        val s = style.value ?: return@LaunchedEffect
        val p = pin.value
        val shape = if (p == null) FeatureCollection.fromFeatures(emptyList()) else {
            val ring = circlePoints(p.latitude, p.longitude, radius.toDouble()).map { (lat, lon) -> Point.fromLngLat(lon, lat) }
            FeatureCollection.fromFeature(Feature.fromGeometry(Polygon.fromLngLats(listOf(ring))))
        }
        (s.getSource("circle") as? GeoJsonSource)?.setGeoJson(shape)
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("Back") }
            Text(name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
            val map = mapRef.value
            val p = pin.value
            if (map != null && p != null && cameraTick >= 0) {
                val at = map.projection.toScreenLocation(p)
                val half = with(density) { (PIN_DP / 2).dp.toPx() }
                Box(
                    Modifier
                        .offset { IntOffset((at.x - half).roundToInt(), (at.y - half).roundToInt()) }
                        .size(PIN_DP.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF2E7D32))
                        .border(2.dp, Color.White, CircleShape)
                        .pointerInput(Unit) {
                            detectDragGestures { change, drag ->
                                change.consume()
                                val m = mapRef.value ?: return@detectDragGestures
                                val cur = pin.value ?: return@detectDragGestures
                                val now = m.projection.toScreenLocation(cur)
                                pin.value = m.projection.fromScreenLocation(PointF(now.x + drag.x, now.y + drag.y))
                            }
                        }
                )
            }
            Surface(
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.align(Alignment.TopCenter).padding(8.dp),
            ) {
                Text(
                    if (p == null) "Tap the map where the bus parks at the school." else "Drag the pin to where the bus parks.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
        Column(
            Modifier.fillMaxWidth().heightIn(max = 340.dp).navigationBarsPadding().padding(12.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (options.isNotEmpty()) {
                Text("BusPlanner has several schools that could be this one. Choose the right one:", color = MaterialTheme.colorScheme.error)
                options.forEach { option ->
                    OutlinedButton(enabled = !busy, onClick = {
                        busy = true
                        message = null
                        scope.launch {
                            try {
                                Session.chooseSchool(context, name, option)
                                Session.schoolSpot(name)?.takeIf { it.known }?.let { pin.value = LatLng(it.lat!!, it.lon!!) }
                                mapRef.value?.let { m -> pin.value?.let { m.cameraPosition = CameraPosition.Builder().target(it).zoom(16.0).build() } }
                            } catch (e: Exception) {
                                message = "Failed: ${e.message ?: e.javaClass.simpleName}"
                            } finally {
                                busy = false
                            }
                        }
                    }) { Text(option) }
                }
            }
            Text(
                when (spot?.source) {
                    PositionSource.CORRECTED -> "Position: corrected" + (busPlanner?.let { b ->
                        pin.value?.let { " · ${distanceM(it.latitude, it.longitude, b.lat, b.lon).toInt()} m from BusPlanner's point (white dot)" }
                    } ?: "")
                    PositionSource.BUSPLANNER -> "Position: BusPlanner's"
                    else -> "Position unknown: \"Bus parked at the school\" is off for the runs going there."
                },
                style = MaterialTheme.typography.bodySmall,
            )
            if (going.isNotEmpty()) Text("Runs going there: ${going.joinToString { it.key }}", style = MaterialTheme.typography.bodySmall)
            Text("\"At school\" circle: ${radius.roundToInt()} m", style = MaterialTheme.typography.bodyMedium)
            Slider(
                value = radius,
                onValueChange = { radius = (it / 5f).roundToInt() * 5f },
                valueRange = MIN_SCHOOL_RADIUS_M.toFloat()..MAX_SCHOOL_RADIUS_M.toFloat(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = parked != null, onClick = {
                    parked?.let { (lat, lon) ->
                        pin.value = LatLng(lat, lon)
                        mapRef.value?.let { m -> m.cameraPosition = CameraPosition.Builder().target(LatLng(lat, lon)).zoom(16.0).build() }
                    }
                }) { Text("Use where the bus parked") }
                OutlinedButton(enabled = busPlanner != null || fixes[name] != null, onClick = {
                    Session.resetSchool(context, name)
                    pin.value = busPlanner?.let { LatLng(it.lat, it.lon) }
                    radius = AT_SCHOOL_M.toFloat()
                    message = "Back to BusPlanner's position"
                }) { Text("Reset") }
            }
            if (parked == null) Text("The blue dot shows where the bus parked, once a run has ended at the school.", style = MaterialTheme.typography.bodySmall)
            Button(enabled = pin.value != null, onClick = {
                val p = pin.value ?: return@Button
                // At BusPlanner's own point (within 1 m): no correction of the position, only the circle.
                val same = busPlanner != null && distanceM(p.latitude, p.longitude, busPlanner.lat, busPlanner.lon) < 1.0
                Session.setSchoolFix(
                    context,
                    SchoolFix(
                        name, if (same) null else p.latitude, if (same) null else p.longitude,
                        radius.roundToInt().toDouble(), fixes[name]?.match,
                    ),
                )
                message = "Saved"
            }) { Text("Save") }
            message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
        }
    }
}
