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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import io.github.anderewrey.bustracker.AppLog
import io.github.anderewrey.bustracker.data.Run
import io.github.anderewrey.bustracker.data.Zone
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
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
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon
import kotlin.math.roundToInt

private const val DOT_DP = 28

/**
 * Draws or changes a zone (DESIGN.md §6.2). The map moves as usual all the time. With no [zone],
 * each tap on the map places the next corner, joined to the previous one by a straight line;
 * Undo removes the last one; Done closes the shape (last corner back to the first). Corners are
 * dots that can be dragged, before and after Done. [taken] tells a name another zone already has
 * (Save waits for a new one). [onSave] gets the name and corners.
 */
@Composable
fun ZoneEditorScreen(
    run: Run,
    zone: Zone?,
    taken: (String) -> Boolean,
    onCancel: () -> Unit,
    onSave: (String, List<Pair<Double, Double>>) -> Unit,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    var name by remember { mutableStateOf(zone?.name ?: "") }
    val corners = remember { mutableStateListOf<LatLng>().apply { zone?.corners?.forEach { (lat, lon) -> add(LatLng(lat, lon)) } } }
    // Closed: the user confirmed the outline (Done), or an existing zone.
    var closed by remember { mutableStateOf(zone != null) }
    val mapRef = remember { mutableStateOf<MapLibreMap?>(null) }
    val style = remember { mutableStateOf<Style?>(null) }
    // Bumped on every camera move, so the dots follow the map.
    var cameraTick by remember { mutableIntStateOf(0) }

    val mapView = remember {
        MapLibre.getInstance(context)
        MapView(context).apply {
            onCreate(null)
            getMapAsync { map ->
                val stop = if (run.lat != null && run.lon != null) LatLng(run.lat, run.lon) else null
                val target = stop ?: corners.firstOrNull() ?: LatLng(0.0, 0.0)
                map.cameraPosition = CameraPosition.Builder().target(target).zoom(if (stop != null || corners.isNotEmpty()) 14.5 else 2.0).build()
                map.addOnCameraMoveListener { cameraTick++ }
                map.addOnCameraIdleListener { cameraTick++ }
                // A tap (not a drag or a double tap) places the next corner.
                map.addOnMapClickListener { point ->
                    if (closed) return@addOnMapClickListener false
                    corners.add(point)
                    true
                }
                mapRef.value = map
                map.setStyle(Style.Builder().fromUri(STYLE_URL)) { s ->
                    hideTransit(s)
                    s.addSource(GeoJsonSource("zone"))
                    s.addLayer(FillLayer("zone-fill", "zone").withProperties(fillColor("#F2A900"), fillOpacity(0.2f)))
                    s.addLayer(LineLayer("zone-line", "zone").withProperties(lineColor("#C78A00"), lineWidth(2f)))
                    s.addSource(GeoJsonSource("stop", stop?.let { FeatureCollection.fromFeature(Feature.fromGeometry(Point.fromLngLat(it.longitude, it.latitude))) }))
                    s.addLayer(
                        CircleLayer("stop-dot", "stop").withProperties(
                            circleRadius(7f), circleColor("#1565C0"), circleStrokeColor("#FFFFFF"), circleStrokeWidth(2f),
                        )
                    )
                    // An existing zone: show all of it.
                    if (corners.size >= 3) {
                        val pad = (48 * context.resources.displayMetrics.density).toInt()
                        map.getCameraForLatLngBounds(LatLngBounds.Builder().includes(corners.toList()).build(), intArrayOf(pad, pad, pad, pad))
                            ?.let { map.cameraPosition = it }
                    }
                    style.value = s
                }
            }
        }
    }
    MapLifecycle(mapView)

    // Open: straight lines between the corners in tap order. Closed: the filled shape.
    LaunchedEffect(style.value, corners.toList(), closed) {
        val s = style.value ?: return@LaunchedEffect
        val points = corners.map { Point.fromLngLat(it.longitude, it.latitude) }
        val shape = when {
            closed && points.size >= 3 -> FeatureCollection.fromFeature(Feature.fromGeometry(Polygon.fromLngLats(listOf(points + points.first()))))
            points.size >= 2 -> FeatureCollection.fromFeature(Feature.fromGeometry(LineString.fromLngLats(points)))
            else -> FeatureCollection.fromFeatures(emptyList())
        }
        (s.getSource("zone") as? GeoJsonSource)?.setGeoJson(shape)
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onCancel) { Text("Cancel") }
            Text(
                if (zone == null) "New main zone · ${run.key}" else "Main zone · ${zone.name}",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
            val map = mapRef.value
            // cameraTick is read so the dots move with the map.
            if (map != null && cameraTick >= 0) corners.forEachIndexed { i, corner ->
                val p = map.projection.toScreenLocation(corner)
                val half = with(density) { (DOT_DP / 2).dp.toPx() }
                Box(
                    Modifier
                        .offset { IntOffset((p.x - half).roundToInt(), (p.y - half).roundToInt()) }
                        .size(DOT_DP.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFF2A900))
                        .border(2.dp, Color(0xFF1F1F1F), CircleShape)
                        .pointerInput(i) {
                            detectDragGestures { change, drag ->
                                change.consume()
                                val m = mapRef.value ?: return@detectDragGestures
                                val now = m.projection.toScreenLocation(corners[i])
                                corners[i] = m.projection.fromScreenLocation(PointF(now.x + drag.x, now.y + drag.y))
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
                    when {
                        closed -> "Drag the dots to adjust."
                        corners.isEmpty() -> "Tap the map to place the corners, in order."
                        corners.size < 3 -> "Tap the next corner."
                        else -> "Tap the next corner, or Done."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val duplicate = name.isNotBlank() && taken(name)
            OutlinedTextField(
                name, { name = it }, label = { Text("Zone name") }, singleLine = true,
                isError = duplicate,
                supportingText = if (duplicate) ({ Text("Another zone has this name") }) else null,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (closed) {
                    OutlinedButton(onClick = {
                        corners.clear()
                        closed = false
                    }) { Text("Redraw") }
                } else {
                    OutlinedButton(enabled = corners.isNotEmpty(), onClick = { corners.removeAt(corners.size - 1) }) { Text("Undo") }
                    OutlinedButton(enabled = corners.size >= 3, onClick = {
                        closed = true
                        AppLog.log("zone outline: ${corners.size} corners")
                    }) { Text("Done") }
                }
                Box(Modifier.weight(1f))
                Button(
                    enabled = closed && corners.size >= 3 && name.isNotBlank() && !duplicate,
                    onClick = { onSave(name.trim(), corners.map { it.latitude to it.longitude }) },
                ) { Text("Save") }
            }
        }
    }
}
