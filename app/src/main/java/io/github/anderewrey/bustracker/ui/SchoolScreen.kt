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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.ui.draw.alpha
import io.github.anderewrey.bustracker.data.clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.TextStyle
import java.util.Locale
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
import io.github.anderewrey.bustracker.data.fitSpots
import org.maplibre.android.style.layers.PropertyFactory.textOffset
import org.maplibre.android.style.layers.PropertyFactory.textHaloWidth
import org.maplibre.android.style.layers.PropertyFactory.textHaloColor
import org.maplibre.android.style.layers.PropertyFactory.textColor
import org.maplibre.android.style.layers.PropertyFactory.textSize
import org.maplibre.android.style.layers.PropertyFactory.textFont
import org.maplibre.android.style.layers.PropertyFactory.textField
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.SymbolLayer
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
    val parkedAtAll by Session.parkedAt.collectAsState()
    val parkedPmAll by Session.parkedPm.collectAsState()
    val parkedPmAtAll by Session.parkedPmAt.collectAsState()
    // Where the bus parks on arrival (AM) and waits before leaving (PM); [parked]: one of them, for
    // the single-spot button when only one is known.
    val parkedAm = parkedAll[name]
    val parkedPm = parkedPmAll[name]
    val parked = parkedAm ?: parkedPm
    val parkedWhen = if (parkedAm != null) parkedAtAll[name] else parkedPmAtAll[name]
    val fit = if (parkedAm != null && parkedPm != null) fitSpots(parkedAm, parkedPm) else null
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
                    // Morning and afternoon spots, each labelled, so the two can be told apart.
                    val spotsShown = listOfNotNull(parkedAm?.let { it to "AM" }, parkedPm?.let { it to "PM" }).map { (at, label) ->
                        Feature.fromGeometry(Point.fromLngLat(at.second, at.first)).also { it.addStringProperty("label", label) }
                    }
                    s.addSource(GeoJsonSource("parked", FeatureCollection.fromFeatures(spotsShown)))
                    s.addLayer(
                        CircleLayer("parked-dot", "parked").withProperties(
                            circleRadius(6f), circleColor("#1565C0"), circleStrokeColor("#FFFFFF"), circleStrokeWidth(2f),
                        )
                    )
                    s.addLayer(
                        SymbolLayer("parked-label", "parked").withProperties(
                            textField(Expression.get("label")), textFont(arrayOf("Noto Sans Regular")), textSize(11f),
                            textColor("#1565C0"), textHaloColor("#FFFFFF"), textHaloWidth(1.5f), textOffset(arrayOf(0f, 1.3f)),
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
            // Where the pin on screen comes from (user, 2026-11-23), and whether it is saved yet.
            val shown = pin.value
            val saved = spot?.takeIf { it.known }
            val unsaved = shown != null && (saved == null ||
                distanceM(shown.latitude, shown.longitude, saved.lat!!, saved.lon!!) >= 1.0 || radius.roundToInt() != saved.radius.roundToInt())
            Text(
                when {
                    shown == null -> "Position unknown: \"Bus parked at the school\" is off for the runs going there."
                    busPlanner != null && distanceM(shown.latitude, shown.longitude, busPlanner.lat, busPlanner.lon) < HERE_M -> "Position: BusPlanner's point"
                    fit != null && distanceM(shown.latitude, shown.longitude, fit.first, fit.second) < HERE_M -> "Position: between the bus spots"
                    listOfNotNull(parkedAm, parkedPm).any { distanceM(shown.latitude, shown.longitude, it.first, it.second) < HERE_M } -> "Position: bus parking"
                    else -> "Position: placed by hand"
                } + if (unsaved) " · not saved" else "",
                style = MaterialTheme.typography.bodySmall,
            )
            if (going.isNotEmpty()) Text("Runs going there: ${going.joinToString { it.key }}", style = MaterialTheme.typography.bodySmall)
            Text("\"At school\" circle: ${radius.roundToInt()} m", style = MaterialTheme.typography.bodyMedium)
            Slider(
                value = radius,
                onValueChange = { radius = (it / 5f).roundToInt() * 5f },
                valueRange = MIN_SCHOOL_RADIUS_M.toFloat()..MAX_SCHOOL_RADIUS_M.toFloat(),
            )
            // Each dot on the map has the button that uses it, with the same mark (user, 2026-11-23;
            // Artboard 1.7): no legend needed. A button about the pin's own place says "here" and rests.
            val p = pin.value
            val parkedM = if (p != null && parked != null) distanceM(p.latitude, p.longitude, parked.first, parked.second) else null
            val busPlannerM = if (p != null && busPlanner != null) distanceM(p.latitude, p.longitude, busPlanner.lat, busPlanner.lon) else null
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (fit != null) {
                    // Both spots known: a circle that holds the two (Artboard 1.7.3).
                    val fitR = fit.third.roundToInt()
                    val fitHere = p != null && distanceM(p.latitude, p.longitude, fit.first, fit.second) < HERE_M && radius.roundToInt() == fitR
                    DotButton(
                        blue = true, double = true,
                        enabled = !fitHere,
                        text = if (fitHere) "Both spots · here" else "Fit both spots · $fitR m",
                    ) {
                        pin.value = LatLng(fit.first, fit.second)
                        radius = fitR.toFloat()
                        mapRef.value?.let { m -> m.cameraPosition = CameraPosition.Builder().target(LatLng(fit.first, fit.second)).zoom(16.0).build() }
                    }
                } else {
                    val parkedHere = parkedM != null && parkedM < HERE_M
                    DotButton(
                        blue = true,
                        enabled = parked != null && !parkedHere,
                        text = when {
                            parked == null -> "Where the bus parks: learned on the next runs"
                            parkedHere -> "Where the bus parked · here"
                            else -> "Use where the bus parked" +
                                (parkedM?.let { " · ${it.roundToInt()} m" } ?: "") +
                                (parkedWhen?.let { " · ${dayAndTime(it)}" } ?: "")
                        },
                    ) {
                        parked?.let { (lat, lon) ->
                            pin.value = LatLng(lat, lon)
                            mapRef.value?.let { m -> m.cameraPosition = CameraPosition.Builder().target(LatLng(lat, lon)).zoom(16.0).build() }
                        }
                    }
                }
                // Back to BusPlanner's point also brings the default circle back, so it stays
                // useful at the point itself while another circle is set. Like the blue button, it
                // only moves the pin: nothing is kept until Save (user, 2026-11-23: it used to save at once).
                val busPlannerHere = busPlannerM != null && busPlannerM < HERE_M
                val defaultCircle = radius.roundToInt() == AT_SCHOOL_M.toInt()
                DotButton(
                    blue = false,
                    enabled = busPlanner != null && (!busPlannerHere || !defaultCircle),
                    text = when {
                        busPlanner == null -> "BusPlanner's point: unknown"
                        busPlannerHere && defaultCircle -> "BusPlanner's point · here"
                        busPlannerHere -> "Back to BusPlanner's point"
                        else -> "Back to BusPlanner's point" + (busPlannerM?.let { " · ${it.roundToInt()} m" } ?: "")
                    },
                ) {
                    busPlanner?.let { b ->
                        pin.value = LatLng(b.lat, b.lon)
                        radius = AT_SCHOOL_M.toFloat()
                        mapRef.value?.let { m -> m.cameraPosition = CameraPosition.Builder().target(LatLng(b.lat, b.lon)).zoom(16.0).build() }
                    }
                }
            }
            Button(enabled = pin.value != null, onClick = {
                val p = pin.value ?: return@Button
                // At BusPlanner's own point (within 1 m): no correction of the position, only the circle.
                val same = busPlanner != null && distanceM(p.latitude, p.longitude, busPlanner.lat, busPlanner.lon) < 1.0
                // BusPlanner's point and the default circle: no correction left (a chosen school stays chosen).
                if (same && radius.roundToInt() == AT_SCHOOL_M.toInt()) {
                    Session.resetSchool(context, name)
                    message = "Saved"
                    return@Button
                }
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

/** Within this distance, a dot is where the pin already is. */
private const val HERE_M = 3.0

/** "today 7:57 AM", or "Mon 7:57 AM" for another day. */
private fun dayAndTime(at: LocalDateTime): String =
    (if (at.toLocalDate() == LocalDate.now()) "today" else at.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)) + " " + clock(at)

/** A button that starts with the mark of the map dot it is about: blue (where the bus parked) or white (BusPlanner's point). */
@Composable
private fun DotButton(blue: Boolean, enabled: Boolean, text: String, double: Boolean = false, onClick: () -> Unit) {
    OutlinedButton(enabled = enabled, onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        repeat(if (double) 2 else 1) {
            Box(
                Modifier
                    .size(if (blue) 12.dp else 10.dp)
                    .alpha(if (enabled) 1f else 0.45f)
                    .clip(CircleShape)
                    .background(if (blue) Color(0xFF1565C0) else Color.White)
                    .border(2.dp, if (blue) Color.White else Color(0xFF6D6D6D), CircleShape)
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(text, modifier = Modifier.weight(1f))
    }
}
