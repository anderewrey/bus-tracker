// SPDX-FileCopyrightText: 2026 anderewrey
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.anderewrey.bustracker

import io.github.anderewrey.bustracker.data.clockSeconds
import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.anderewrey.bustracker.data.Run
import io.github.anderewrey.bustracker.data.ownDaysText
import io.github.anderewrey.bustracker.data.Store
import io.github.anderewrey.bustracker.net.LATEST_APK
import io.github.anderewrey.bustracker.net.BadPortal
import io.github.anderewrey.bustracker.net.portalAddress
import io.github.anderewrey.bustracker.monitor.EXTRA_RUN
import io.github.anderewrey.bustracker.monitor.EXTRA_SCREEN
import io.github.anderewrey.bustracker.monitor.EXTRA_SILENCE
import io.github.anderewrey.bustracker.monitor.MonitorService
import io.github.anderewrey.bustracker.monitor.SCREEN_CHANGES
import io.github.anderewrey.bustracker.ui.ChangesScreen
import io.github.anderewrey.bustracker.ui.ImportRow
import io.github.anderewrey.bustracker.ui.RunScreen
import io.github.anderewrey.bustracker.ui.OverviewScreen
import io.github.anderewrey.bustracker.ui.SchoolScreen
import io.github.anderewrey.bustracker.ui.SettingsScreen
import io.github.anderewrey.bustracker.ui.WatchingDaysScreen
import io.github.anderewrey.bustracker.ui.BusPlannerScreen
import io.github.anderewrey.bustracker.ui.AutoStartRow
import io.github.anderewrey.bustracker.monitor.Scheduler
import androidx.compose.material3.FilledTonalButton
import java.time.LocalDate
import androidx.compose.material3.CardDefaults
import androidx.compose.ui.Alignment
import io.github.anderewrey.bustracker.monitor.ensureChannels
import io.github.anderewrey.bustracker.monitor.testRing
import io.github.anderewrey.bustracker.ui.MapScreen
import io.github.anderewrey.bustracker.ui.GeocoderTest
import io.github.anderewrey.bustracker.ui.AlarmRow
import io.github.anderewrey.bustracker.ui.RunTitle
import kotlinx.coroutines.launch
import androidx.lifecycle.lifecycleScope

class MainActivity : ComponentActivity() {
    private val openRun = mutableStateOf<String?>(null)
    private val openChanges = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        ensureChannels(this)
        Store(this).dropObsolete()
        // Also covers the first start after installing this version, before any rescan.
        if (Store(this).runs.isNotEmpty()) Scheduler.reschedule(this)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        openRun.value = intent.getStringExtra(EXTRA_RUN)
        silenceIfAsked(intent)
        openChanges.value = intent.getStringExtra(EXTRA_SCREEN) == SCREEN_CHANGES
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    App(openRun.value, { openRun.value = it }, openChanges.value, { openChanges.value = it })
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // A period under way with its Alarm switch on but not watching (phone off at its start): start it.
        if (Store(this).runs.isNotEmpty()) {
            Session.catchUp(this)
            // Today's BusPlanner notices on every run's card, watched or not.
            lifecycleScope.launch { Session.refreshNotices(this@MainActivity) }
        }
        // A newer official version on GitHub, asked at most once a day (DESIGN.md §8, Updates).
        lifecycleScope.launch { Updates.checkDaily(this@MainActivity) }
    }

    /** Opened from a ringing main-zone alarm: opening it answers it, as Silence does. */
    private fun silenceIfAsked(intent: Intent) {
        val key = intent.getStringExtra(EXTRA_RUN) ?: return
        if (intent.getBooleanExtra(EXTRA_SILENCE, false)) MonitorService.silence(this, key)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(EXTRA_RUN)?.let { openRun.value = it }
        silenceIfAsked(intent)
        if (intent.getStringExtra(EXTRA_SCREEN) == SCREEN_CHANGES) {
            openRun.value = null
            openChanges.value = true
        }
    }
}

@Composable
private fun App(openRun: String?, onOpenRun: (String?) -> Unit, showChanges: Boolean, onShowChanges: (Boolean) -> Unit) {
    val context = LocalContext.current
    val store = remember { Store(context) }
    var loggedIn by remember { mutableStateOf(store.hasLogin) }
    val runs by Session.runs.collectAsState()
    val changes by Session.changes.collectAsState()

    var showDebug by remember { mutableStateOf(false) }
    var pageKey by remember { mutableStateOf<String?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    var showBusPlanner by remember { mutableStateOf(false) }
    var watchingDays by remember { mutableStateOf(false) }
    var showOverview by remember { mutableStateOf(false) }
    var schoolName by remember { mutableStateOf<String?>(null) }

    val mapRun = runs.firstOrNull { it.key == openRun }
    val pageRun = runs.firstOrNull { it.key == pageKey }
    when {
        showDebug -> {
            BackHandler { showDebug = false }
            DebugScreen { showDebug = false }
        }
        pageRun != null && mapRun == null -> {
            BackHandler { pageKey = null }
            RunScreen(pageRun, onBack = { pageKey = null }, onChanges = {
                pageKey = null
                showOverview = false
                showSettings = false
                onShowChanges(true)
            })
        }
        watchingDays && loggedIn -> {
            BackHandler { watchingDays = false }
            WatchingDaysScreen(runs) { watchingDays = false }
        }
        !loggedIn -> LoginScreen(onDebug = { showDebug = true }, onImported = { loggedIn = Store(context).hasLogin }) { loggedIn = true }
        schoolName != null -> {
            BackHandler { schoolName = null }
            SchoolScreen(schoolName!!) { schoolName = null }
        }
        showOverview -> {
            BackHandler { showOverview = false }
            OverviewScreen(
                onBack = { showOverview = false },
                onChanges = {
                    showOverview = false
                    showSettings = false
                    onShowChanges(true)
                },
                onSchool = { schoolName = it },
                onRun = { pageKey = it.key },
            )
        }
        showSettings -> {
            BackHandler { showSettings = false }
            SettingsScreen(onBack = { showSettings = false }, onOverview = { showOverview = true }, onDebug = { showDebug = true }, onImported = { loggedIn = Store(context).hasLogin })
        }
        showBusPlanner && !showChanges && mapRun == null -> {
            BackHandler { showBusPlanner = false }
            BusPlannerScreen(onBack = { showBusPlanner = false }, onShowChanges = { onShowChanges(true) }, onLogout = {
                runs.forEach { MonitorService.stop(context, it.key) }
                Session.logout(context)
                showBusPlanner = false
                showSettings = false
                showOverview = false
                schoolName = null
                loggedIn = false
            })
        }
        mapRun != null -> {
            BackHandler { onOpenRun(null) }
            MapScreen(mapRun) { onOpenRun(null) }
        }
        showChanges -> {
            BackHandler { onShowChanges(false) }
            ChangesScreen(changes) { onShowChanges(false) }
        }
        else -> RunsScreen(
            runs = runs,
            changes = changes.size,
            onShowChanges = { onShowChanges(true) },
            onOpenMap = { onOpenRun(it.key) },
            onOpenPage = { pageKey = it.key },
            onWatchingDays = { watchingDays = true },
            onBusPlanner = { showBusPlanner = true },
            onSettings = { showSettings = true },
        )
    }
}

@Composable
private fun LoginScreen(onDebug: () -> Unit, onImported: () -> Unit, onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // A login saved before the portal field keeps its email: only the portal and password are typed.
    val saved = remember { Store(context) }
    var portal by remember { mutableStateOf(saved.portal?.removePrefix("https://") ?: "") }
    var email by remember { mutableStateOf(saved.email ?: "") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Column(
        Modifier.safeDrawingPadding().padding(24.dp).fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Debug at the top right, as on the runs screen, away from "Log in".
        Row(Modifier.fillMaxWidth()) {
            Text("BusPlanner login", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            TextButton(onClick = onDebug) { Text("Debug") }
        }
        OutlinedTextField(
            portal, { portal = it.trim() }, label = { Text("Portal address") }, singleLine = true,
            placeholder = { Text("example.mybusplanner.ca") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            email, { email = it.trim() }, label = { Text("Email") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            password, { password = it }, label = { Text("Password") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth(),
        )
        Button(
            enabled = !busy && portal.isNotEmpty() && email.isNotEmpty() && password.isNotEmpty(),
            onClick = {
                val address = try {
                    portalAddress(portal)
                } catch (e: BadPortal) {
                    error = e.message
                    return@Button
                }
                busy = true
                error = null
                scope.launch {
                    try {
                        Session.loginAndScan(context, address, email, password)
                        onDone()
                    } catch (e: Exception) {
                        error = e.message ?: e.javaClass.simpleName
                    } finally {
                        busy = false
                    }
                }
            },
        ) { Text(if (busy) "Logging in and scanning…" else "Log in") }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        // A test build starts empty: one tap copies the official app's setup instead.
        ImportRow(confirm = false, onDone = onImported)
    }
}

@Composable
private fun RunsScreen(
    runs: List<Run>,
    changes: Int,
    onShowChanges: () -> Unit,
    onOpenMap: (Run) -> Unit,
    onOpenPage: (Run) -> Unit,
    onWatchingDays: () -> Unit,
    onBusPlanner: () -> Unit,
    onSettings: () -> Unit,
) {
    val status by Session.status.collectAsState()
    val active by Session.active.collectAsState()

    Column(Modifier.safeDrawingPadding().padding(16.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Text("Bus runs", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            // What comes from BusPlanner, and how the phone reacts (user, 2026-11-21).
            TextButton(onClick = onBusPlanner) { Text("BusPlanner ›") }
            TextButton(onClick = onSettings) { Text("Settings ›") }
        }
        // No Play Store: this card is how a parent learns a new version is out (user, 2026-11-22).
        val update by Updates.available.collectAsState()
        val uri = LocalUriHandler.current
        update?.let { latest ->
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            ) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Bus Tracker ${latest.name} is out", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = { uri.openUri(LATEST_APK) }) { Text("Download ›") }
                }
            }
        }
        if (changes > 0) Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        ) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (changes == 1) "Bus schedule changed" else "Bus schedule changed: $changes changes",
                    style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onShowChanges) { Text("See changes") }
            }
        }
        if (runs.isEmpty()) Text("No bus run found on this account.", Modifier.padding(vertical = 8.dp))
        else AutoStartRow(runs, onWatchingDays)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.weight(1f)) {
            items(runs, key = { it.key }) { run ->
                // Tapping the card opens the run's page; its buttons keep their own actions.
                Card(onClick = { onOpenPage(run) }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        // "›": every card that opens something says so (user, 2026-11-21).
                        Row(verticalAlignment = Alignment.Top) {
                            Box(Modifier.weight(1f)) { RunTitle(run, "${run.bus} · ${run.plannedLine}", MaterialTheme.typography.titleMedium) }
                            Text("›", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                        }
                        Text(run.address)
                        // The run's own days, when they aren't the usual Mon–Fri (user, 2026-11-21).
                        ownDaysText(run.days)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        // A run no longer watched shows no line about its bus (DESIGN.md §4).
                        val shown = shownStatus(status[run.key], run.key in active, null)
                        if (shown != null) {
                            Text(
                                shown.line, style = MaterialTheme.typography.bodyMedium,
                                color = if (shown.old) MaterialTheme.colorScheme.error else Color.Unspecified,
                            )
                            // The GPS time on its own line, not with the street (user, 2026-11-27).
                            shown.reading?.reported?.let { Text("GPS $it", style = MaterialTheme.typography.bodySmall) }
                            shown.checkedAt?.let { t -> Text("checked ${clockSeconds(t)}", style = MaterialTheme.typography.bodySmall) }
                        } else if (status[run.key] != null) {
                            Text(NOT_WATCHING, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedButton(onClick = { onOpenMap(run) }) { Text("Open map") }
                            Spacer(Modifier.weight(1f))
                            AlarmRow(run)
                        }
                    }
                }
            }
        }
    }
}

private val FILE_STAMP = DateTimeFormatter.ofPattern("yyMMdd-HHmm")

@Composable
private fun DebugScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val lines by AppLog.lines.collectAsState()
    val listState = rememberLazyListState()
    LaunchedEffect(lines.size) { if (lines.isNotEmpty()) listState.scrollToItem(lines.lastIndex) }
    // Exports every kept day file as one zip, where the user chooses (e.g. Downloads), to share it from there.
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val saved = runCatching { context.contentResolver.openOutputStream(uri)!!.use { AppLog.exportZip(it) } }
        AppLog.log(if (saved.isSuccess) "logs exported" else "logs not exported: ${saved.exceptionOrNull()?.message}")
    }

    Column(Modifier.safeDrawingPadding().padding(8.dp)) {
        Row(Modifier.fillMaxWidth()) {
            TextButton(onClick = onBack) { Text("Back") }
            Text("Debug", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f).padding(top = 6.dp))
            TextButton(onClick = {
                val clip = context.getSystemService(ClipboardManager::class.java)
                clip.setPrimaryClip(ClipData.newPlainText("Bus Tracker log", (listOf(AppLog.build) + lines).joinToString("\n")))
            }) { Text("Copy") }
            TextButton(onClick = {
                export.launch("bustracker-logs-${LocalDateTime.now().format(FILE_STAMP)}.zip")
            }) { Text("Export") }
            TextButton(onClick = { AppLog.clearScreen() }) { Text("Clear") }
        }
        Text(AppLog.version, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 8.dp))
        OutlinedButton(onClick = { testRing(context) }, modifier = Modifier.padding(horizontal = 8.dp)) {
            Text("Test alarm")
        }
        GeocoderTest(Modifier.padding(horizontal = 8.dp))
        if (lines.isEmpty()) Text("Nothing logged yet.", Modifier.padding(8.dp))
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            items(lines) { Text(it, fontFamily = FontFamily.Monospace, fontSize = 11.sp) }
        }
    }
}
