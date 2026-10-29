package com.lunk333.whrrah.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.lunk333.whrrah.osmo.CameraScreen
import com.lunk333.whrrah.osmo.OsmoLink
import com.lunk333.whrrah.session.Lap
import com.lunk333.whrrah.session.Session
import com.lunk333.whrrah.session.SessionController
import com.lunk333.whrrah.gps.Geo
import com.lunk333.whrrah.gps.GpsFix
import com.lunk333.whrrah.session.TrackLine
import com.lunk333.whrrah.settings.AppSettings
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class Screen { HOME, NEW_SESSION, OPEN_SESSION, SESSION, SETTINGS, DEV, DEV_CAMERA, DEV_RACEBOX, DEV_DATA }

/** Whole app: a back stack of [Screen]s. [legacyData] is the old CSV/XRK viewer, kept under Dev options. */
@Composable
fun WhrrahApp(modifier: Modifier = Modifier, legacyData: @Composable (Modifier) -> Unit) {
    val stack = remember { mutableStateListOf(Screen.HOME) }
    fun go(s: Screen) = stack.add(s)
    fun back() { if (stack.size > 1) stack.removeAt(stack.lastIndex) }
    fun replace(s: Screen) { stack.removeAt(stack.lastIndex); stack.add(s) }
    BackHandler(enabled = stack.size > 1) { back() }

    val screen = stack.last()
    Column(modifier.fillMaxSize()) {
        Header(title(screen), showBack = stack.size > 1, onBack = ::back)
        val m = Modifier.fillMaxSize()
        when (screen) {
            Screen.HOME -> HomeScreen(m, onNew = { go(Screen.NEW_SESSION) }, onOpen = { go(Screen.OPEN_SESSION) },
                onResume = { go(Screen.SESSION) }, onSettings = { go(Screen.SETTINGS) }, onDev = { go(Screen.DEV) })
            Screen.NEW_SESSION -> NewSessionScreen(m, onCreated = { replace(Screen.SESSION) })
            Screen.OPEN_SESSION -> OpenSessionScreen(m, onOpened = { replace(Screen.SESSION) })
            Screen.SESSION -> SessionScreen(m, onClosed = ::back)
            Screen.SETTINGS -> SettingsScreen(m)
            Screen.DEV -> DevScreen(m, onCamera = { go(Screen.DEV_CAMERA) }, onRaceBox = { go(Screen.DEV_RACEBOX) }, onData = { go(Screen.DEV_DATA) })
            Screen.DEV_RACEBOX -> com.lunk333.whrrah.racebox.RaceBoxScreen(m)
            Screen.DEV_CAMERA -> CameraScreen(m)
            Screen.DEV_DATA -> legacyData(m)
        }
    }
}

private fun title(s: Screen) = when (s) {
    Screen.HOME -> "WHRRAH"
    Screen.NEW_SESSION -> "New session"
    Screen.OPEN_SESSION -> "Open session"
    Screen.SESSION -> "Session"
    Screen.SETTINGS -> "Settings"
    Screen.DEV -> "Dev options"
    Screen.DEV_CAMERA -> "Camera test bench"
    Screen.DEV_RACEBOX -> "RaceBox test panel"
    Screen.DEV_DATA -> "CSV / XRK viewer (legacy)"
}

@Composable
private fun Header(title: String, showBack: Boolean, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (showBack) TextButton(onClick = onBack) { Text("←  Back") } else Spacer(Modifier.size(16.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 4.dp))
    }
    HorizontalDivider()
}

// ---- shared bits -----------------------------------------------------------------------------

private val PERMS: Array<String> = when {
    // Notifications: the background-connection notification (LinkService) is hidden without it.
    Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.POST_NOTIFICATIONS)
    Build.VERSION.SDK_INT >= 31 -> arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.ACCESS_FINE_LOCATION)
    else -> arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
}

/** Bluetooth (camera) + location (GPS) permissions; [onGranted] runs once they're all in. */
@Composable
private fun PermissionCard(onGranted: () -> Unit = {}) {
    val context = LocalContext.current
    fun granted() = PERMS.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
    var ok by remember { mutableStateOf(granted()) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        ok = granted(); if (ok) onGranted()
    }
    LaunchedEffect(Unit) { if (ok) onGranted() }
    if (!ok) Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text("WHRRAH needs Bluetooth (camera) and location (GPS) permission.")
            Button(onClick = { launcher.launch(PERMS) }, modifier = Modifier.padding(top = 8.dp)) { Text("Grant permissions") }
        }
    }
}

@Composable
private fun Dot(color: Color) = Box(Modifier.size(12.dp).clip(CircleShape).background(color))

/** Camera + GPS connection status, used on the home and session screens. */
@Composable
private fun ConnectionStatus() {
    val context = LocalContext.current
    val link = remember { OsmoLink.get(context) }
    val ctl = remember { SessionController.get(context) }
    val cam = ctl.camera
    val gps = ctl.gps
    var now by remember { mutableStateOf(android.os.SystemClock.elapsedRealtime()) }
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(500); now = android.os.SystemClock.elapsedRealtime() } }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            // The WiFi link only runs for Match clips / Dev options; otherwise it's the Bluetooth monitor.
            val wifi = link.phase !in setOf(OsmoLink.Phase.IDLE, OsmoLink.Phase.SCANNING)
            val (camColor, camText) = when {
                wifi -> (if (link.phase == OsmoLink.Phase.FAILED) Color(0xFFE0A000) else Color(0xFF1E88E5)) to "WiFi — ${link.status}"
                cam.recording == true -> Color(0xFFE53935) to ((now - cam.recStartElapsedMs) / 1000).let { "REC %d:%02d".format(it / 60, it % 60) }
                cam.connected && cam.recording == false -> Color(0xFF43A047) to ("standby" + (cam.battery.takeIf { it in 0..100 }?.let { " · battery $it%" } ?: ""))
                cam.connected -> Color(0xFF1E88E5) to "connected, waiting for status"
                cam.state == com.lunk333.whrrah.osmo.CameraMonitor.State.WAITING_APPROVAL -> Color(0xFF1E88E5) to "approve the remote on the camera's screen"
                cam.state == com.lunk333.whrrah.osmo.CameraMonitor.State.CONNECTING -> Color(0xFF1E88E5) to "connecting over Bluetooth…"
                else -> Color.Gray to (link.lastCamera?.let { "not connected (${it.name})" } ?: "none set up — pair one in Dev options")
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Dot(camColor)
                Text("Camera: $camText", modifier = Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodyMedium)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                val fix = gps.lastFix
                Dot(if (gps.connected) Color(0xFF43A047) else Color.Gray)
                Text(
                    "GPS: " + (if (gps.connected && fix != null) "${gps.name}, ±%.0f m".format(fix.accuracyM ?: 0f)
                    else "no fix (RaceBox not connected, phone GPS off)"),
                    modifier = Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

private fun formatLap(ms: Long?): String = ms?.let { "%d:%02d.%03d".format(it / 60000, (it / 1000) % 60, it % 1000) } ?: "—"

// ---- home ------------------------------------------------------------------------------------

@Composable
private fun HomeScreen(
    modifier: Modifier, onNew: () -> Unit, onOpen: () -> Unit, onResume: () -> Unit, onSettings: () -> Unit, onDev: () -> Unit,
) {
    val context = LocalContext.current
    val sessions = remember { SessionController.get(context) }
    Column(modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PermissionCard()
        sessions.session?.let { s ->
            Button(onClick = onResume, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("Back to ${s.event}") }
        }
        Button(onClick = onNew, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("New session") }
        OutlinedButton(onClick = onOpen, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("Open session") }
        OutlinedButton(onClick = onSettings, modifier = Modifier.fillMaxWidth()) { Text("Settings") }
        OutlinedButton(onClick = onDev, modifier = Modifier.fillMaxWidth()) { Text("Dev options") }
        ConnectionStatus()
    }
}

// ---- new / open session ----------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NewSessionScreen(modifier: Modifier, onCreated: () -> Unit) {
    val context = LocalContext.current
    val settings = remember { AppSettings.get(context) }
    val sessions = remember { SessionController.get(context) }
    var event by remember { mutableStateOf("") }
    var driver by remember { mutableStateOf(settings.lastDriver) }
    var car by remember { mutableStateOf(settings.lastCar) }

    Column(modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(event, { event = it }, label = { Text("Event name") }, placeholder = { Text("NE-SVT #16") },
            singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(driver, { driver = it }, label = { Text("Driver") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        if (settings.drivers.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            settings.drivers.forEach { d -> FilterChip(driver == d, { driver = d }, { Text(d) }) }
        }
        OutlinedTextField(car, { car = it }, label = { Text("Car") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        if (settings.cars.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            settings.cars.forEach { c -> FilterChip(car == c, { car = c }, { Text(c) }) }
        }
        Text("Folder: " + com.lunk333.whrrah.session.Session.folderName(event.ifBlank { "Session" }, Session.today()),
            style = MaterialTheme.typography.bodySmall)
        Button(
            onClick = { sessions.create(event.ifBlank { "Session" }, driver, car); onCreated() },
            enabled = event.isNotBlank(), modifier = Modifier.fillMaxWidth().height(56.dp),
        ) { Text("Create session") }
    }
}

@Composable
private fun OpenSessionScreen(modifier: Modifier, onOpened: () -> Unit) {
    val context = LocalContext.current
    val sessions = remember { SessionController.get(context) }
    val list = remember { sessions.store.list() }
    LazyColumn(modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Spacer(Modifier.height(8.dp)) }
        if (list.isEmpty()) item { Text("No sessions yet.") }
        items(list, key = { it.id }) { s ->
            Card(Modifier.fillMaxWidth().clickable { if (sessions.open(s.id)) onOpened() }) {
                Column(Modifier.padding(12.dp)) {
                    Text(s.event, style = MaterialTheme.typography.titleMedium)
                    Text("${s.date} · ${s.driver.ifBlank { "?" }} · ${s.car.ifBlank { "?" }} · ${s.laps.size} runs · best ${formatLap(s.bestLapMs)}",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

// ---- session ---------------------------------------------------------------------------------

@Composable
private fun SessionScreen(modifier: Modifier, onClosed: () -> Unit) {
    val context = LocalContext.current
    val ctl = remember { SessionController.get(context) }
    val link = remember { OsmoLink.get(context) }
    val s = ctl.session ?: return Text("No session open.", modifier.padding(16.dp))

    LazyColumn(modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Column(Modifier.padding(top = 8.dp)) {
                Text(s.event, style = MaterialTheme.typography.headlineSmall)
                Text("${s.date} · ${s.driver.ifBlank { "?" }} · ${s.car.ifBlank { "?" }}", style = MaterialTheme.typography.bodyMedium)
            }
        }
        item { PermissionCard(onGranted = ctl::startGps) }
        item { ConnectionStatus() }
        if (ctl.activity.isNotEmpty()) item { Text(ctl.activity, style = MaterialTheme.typography.bodySmall) }

        item { TrackCard(ctl, s, fix = ctl.currentFix, widthFt = AppSettings.get(context).lineWidthFt) }

        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                Stat("Runs", "${s.laps.size}")
                Stat("Best", formatLap(s.bestLapMs))
            }
        }
        item { RunButton(ctl) }
        item {
            Text("Start recording from the mic remote ~30 s before the start line and stop ~30 s after the finish — each recording is one run.",
                style = MaterialTheme.typography.bodySmall)
        }
        items(s.laps.reversed(), key = { it.n }) { lap -> LapRow(lap) }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                OutlinedButton(onClick = ctl::matchClipsNow, enabled = !ctl.matching && ctl.currentRun == null && link.lastCamera != null) {
                    Text(if (ctl.matching) "Matching…" else "Match clips (camera WiFi)")
                }
                OutlinedButton(onClick = { ctl.close(); onClosed() }) { Text("Close session") }
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.headlineMedium)
        Text(label, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun TrackCard(ctl: SessionController, s: Session, fix: GpsFix?, widthFt: Float) {
    var open by remember { mutableStateOf(!s.trackDefined) }
    var error by remember { mutableStateOf<String?>(null) }
    val recording = ctl.walkRecording
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Track" + when {
                        recording -> " — recording walk"
                        s.trackDefined -> " — defined"
                        else -> " — not defined"
                    },
                    style = MaterialTheme.typography.titleSmall,
                )
                if (!recording) TextButton(onClick = { open = !open }) { Text(if (open) "Hide" else "Show") }
            }
            if (open || recording) {
                if (!recording) {
                    Text("Start recording at the beginning of the course walk, then set the start and finish lines " +
                        "as you reach them. Lines are %.0f ft wide (Settings), across your direction of travel. ".format(widthFt) +
                        "To match official timing, nudge a line along the course: + is further down the course, − is back.",
                        style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { error = null; ctl.startTrackWalk(append = false) }) {
                            Text(if (s.trackWalkFile == null) "Record track walk" else "Record new walk")
                        }
                        if (s.trackWalkFile != null) OutlinedButton(onClick = { error = null; ctl.startTrackWalk(append = true) }) { Text("Continue walk") }
                    }
                    TrackLineSummary("Start", s.start) { m, undo -> ctl.nudgeLine(true, m, undo) }
                    TrackLineSummary("Finish", s.finish) { m, undo -> ctl.nudgeLine(false, m, undo) }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Dot(Color(0xFFE53935))
                        Text("Recording · %.0f m walked".format(ctl.walkDistanceM) +
                            (fix?.accuracyM?.let { " · GPS ±%.1f m".format(it) } ?: " · waiting for GPS"),
                            modifier = Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodyMedium)
                    }
                    Text("Walk toward each line in the driving direction, stop on it, tap.", style = MaterialTheme.typography.bodySmall)
                    TrackLineRow("Start", s.start, fix != null) { error = ctl.setLine(true) }
                    TrackLineRow("Finish", s.finish, fix != null) { error = ctl.setLine(false) }
                    OutlinedButton(onClick = ctl::stopTrackWalk) { Text("Stop recording") }
                }
                error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Color(0xFFE0A000)) }
                if (s.start != null || s.finish != null || ctl.walkPath.isNotEmpty() || recording)
                    TrackSketch(s.start, s.finish, ctl.walkPath, if (recording) fix else null)
            }
        }
    }
}

@Composable
private fun TrackLineSummary(label: String, l: TrackLine?, onNudge: (meters: Double, undo: Boolean) -> Unit) {
    Column {
        Text(
            "$label: " + when {
                l == null -> "not set"
                l.headingDeg == null -> "point only — record a walk to set it as a line"
                else -> "facing %.0f° · %.0f ft%s%s".format(l.headingDeg, l.widthM / Geo.FT,
                    l.accuracyM?.let { " · ±%.1f m".format(it) } ?: "",
                    if (l.nudgeM != 0.0) " · moved %+.0f m".format(l.nudgeM) else "")
            },
            style = MaterialTheme.typography.bodySmall,
        )
        // Calibration against official timing: slide the line along the course, keeping it square.
        if (l?.headingDeg != null) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick = { onNudge(-1.0, false) }) { Text("− 1 m") }
            Text("%+.0f m".format(l.nudgeM), style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(onClick = { onNudge(1.0, false) }) { Text("+ 1 m") }
            if (l.nudgeM != 0.0) TextButton(onClick = { onNudge(0.0, true) }) { Text("Undo nudges") }
        }
    }
}

@Composable
private fun TrackLineRow(label: String, l: TrackLine?, canSet: Boolean, onSet: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                when {
                    l == null -> "not set"
                    l.headingDeg == null -> "point only (set before lines existed) — reset it"
                    else -> "%.6f, %.6f · facing %.0f° · %.0f ft%s".format(l.lat, l.lon, l.headingDeg, l.widthM / Geo.FT,
                        l.accuracyM?.let { a -> " · ±%.1f m".format(a) } ?: "")
                },
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Button(onClick = onSet, enabled = canSet) { Text(if (l == null) "Set $label" else "Reset") }
    }
}

/**
 * Top-down sketch (north up): start line green, finish red, each with an arrow for the driving
 * direction, and the current position as a dot. Scaled to fit; just a sanity check on the walk.
 */
@Composable
private fun TrackSketch(start: TrackLine?, finish: TrackLine?, walk: List<Geo.LatLon>, fix: GpsFix?) {
    val pts = buildList {
        addAll(walk)
        for (l in listOfNotNull(start, finish)) { add(Geo.LatLon(l.lat, l.lon)); l.endpoints()?.let { add(it.first); add(it.second) } }
        fix?.let { add(Geo.LatLon(it.lat, it.lon)) }
    }
    if (pts.isEmpty()) return
    val oLat = pts.map { it.lat }.average()
    val oLon = pts.map { it.lon }.average()
    val local = pts.map { Geo.toLocal(oLat, oLon, it.lat, it.lon) }
    val span = maxOf(20.0, local.maxOf { kotlin.math.abs(it.first) }, local.maxOf { kotlin.math.abs(it.second) }) * 1.25
    val onSurface = MaterialTheme.colorScheme.onSurface
    androidx.compose.foundation.Canvas(Modifier.fillMaxWidth().height(180.dp)) {
        val scale = (minOf(size.width, size.height) / 2f) / span.toFloat()
        fun xy(p: Geo.LatLon): androidx.compose.ui.geometry.Offset {
            val (e, n) = Geo.toLocal(oLat, oLon, p.lat, p.lon)
            return androidx.compose.ui.geometry.Offset(size.width / 2 + (e * scale).toFloat(), size.height / 2 - (n * scale).toFloat())
        }
        // Walk trace underneath the lines.
        if (walk.size > 1) {
            val path = androidx.compose.ui.graphics.Path()
            walk.forEachIndexed { i, p -> val o = xy(p); if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y) }
            drawPath(path, onSurface.copy(alpha = 0.35f), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3f))
        }
        for ((l, c) in listOf(start to Color(0xFF43A047), finish to Color(0xFFE53935))) {
            if (l == null) continue
            val ends = l.endpoints()
            if (ends == null) { drawCircle(c, 6f, xy(Geo.LatLon(l.lat, l.lon))); continue }
            drawLine(c, xy(ends.first), xy(ends.second), strokeWidth = 8f)
            // Driving-direction arrow from the centre.
            val tip = Geo.offset(l.lat, l.lon, l.headingDeg!!, span * 0.35)
            drawLine(c, xy(Geo.LatLon(l.lat, l.lon)), xy(tip), strokeWidth = 3f)
            for (side in listOf(-150.0, 150.0)) drawLine(c, xy(tip), xy(Geo.offset(tip.lat, tip.lon, l.headingDeg + side, span * 0.08)), strokeWidth = 3f)
        }
        fix?.let { drawCircle(onSurface, 7f, xy(Geo.LatLon(it.lat, it.lon))) }
        // Scale bar: 10 m.
        val bar = (10 * scale).toFloat()
        drawLine(onSurface, androidx.compose.ui.geometry.Offset(8f, size.height - 8f), androidx.compose.ui.geometry.Offset(8f + bar, size.height - 8f), strokeWidth = 3f)
    }
    Text("north up · bar = 10 m · grey = walk" + if (fix != null) " · dot = you" else "", style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun LapRow(lap: Lap) {
    val fmt = remember { SimpleDateFormat("HH:mm:ss", Locale.US) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Run ${lap.n}" + (if (lap.manual) " (phone)" else "") + if (lap.recording) "  ● recording" else "",
                    style = MaterialTheme.typography.titleMedium)
                Text(formatLap(lap.lapTimeMs), style = MaterialTheme.typography.titleMedium)
            }
            val recLen = lap.recStopWallMs?.let { "%d s".format((it - lap.recStartWallMs) / 1000) }
            Text(listOfNotNull(fmt.format(Date(lap.recStartWallMs)), recLen?.let { "rec $it" },
                "GPS ${lap.gpsFixes} fixes").joinToString(" · "), style = MaterialTheme.typography.bodySmall)
            Text(
                listOf(
                    "clip " + when {
                        lap.clip != null -> lap.clip.substringBeforeLast('.').takeLast(9) +
                            (lap.clipOffsetMs?.let { " (%+.1f s)".format(it / 1000.0) } ?: "")
                        lap.recording -> "—"
                        lap.expectsClip -> "not matched yet"
                        else -> "none (phone only)"
                    },
                    "gyro " + (lap.gyroSamples?.let { "✓ $it" } ?: if (lap.clip != null) "pending" else "—"),
                    "sync " + when {
                        lap.syncOffsetMs != null && (lap.syncR ?: 0.0) >= 0.6 -> "%+d ms".format(lap.syncOffsetMs)
                        lap.syncOffsetMs != null -> "%+d ms? (weak match %.2f)".format(lap.syncOffsetMs, lap.syncR ?: 0.0)
                        lap.imuFile == null -> "— (needs RaceBox data)"
                        lap.gyroFile == null -> "— (needs clip gyro)"
                        else -> "—"
                    },
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
            )
            lap.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Color(0xFFE0A000)) }
            lap.timingNote?.let { Text("timing: $it", style = MaterialTheme.typography.bodySmall,
                color = if (lap.lapTimeMs == null) Color.Gray else Color(0xFFE0A000)) }
        }
    }
}

// ---- settings / dev --------------------------------------------------------------------------

@Composable
private fun SettingsScreen(modifier: Modifier) {
    val context = LocalContext.current
    val settings = remember { AppSettings.get(context) }
    val link = remember { OsmoLink.get(context) }
    LazyColumn(modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Spacer(Modifier.height(4.dp)) }
        item { EditableList("Drivers", settings.drivers, settings::addDriver, settings::removeDriver) }
        item { EditableList("Cars", settings.cars, settings::addCar, settings::removeCar) }
        item { LineWidthSetting(settings) }
        item { Toggle("Auto-connect camera while a session is open", settings.autoConnectCamera, settings::changeAutoConnectCamera) }
        item { Toggle("Pull each new clip's gyro automatically", settings.autoFetchGyro, settings::changeAutoFetchGyro) }
        item { Toggle("Use phone GPS when no RaceBox is connected", settings.phoneGpsFallback, settings::changePhoneGpsFallback) }
        item {
            Text("Camera: " + (link.lastCamera?.name ?: "none — pair one in Dev options → Camera test bench"),
                style = MaterialTheme.typography.bodyMedium)
        }
        item {
            val rb = remember { com.lunk333.whrrah.racebox.RaceBox.get(context) }
            Text("RaceBox: " + (rb.lastDevice?.name ?: "none — connect one in Dev options → RaceBox test panel"),
                style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun Toggle(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(value, onChange)
    }
}

@Composable
private fun EditableList(title: String, values: List<String>, onAdd: (String) -> Unit, onRemove: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            values.forEach { v ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(v); TextButton(onClick = { onRemove(v) }) { Text("Remove") }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(text, { text = it }, singleLine = true, modifier = Modifier.weight(1f), label = { Text("Add") })
                TextButton(onClick = { onAdd(text); text = "" }, enabled = text.isNotBlank()) { Text("Add") }
            }
        }
    }
}

@Composable
private fun DevScreen(modifier: Modifier, onCamera: () -> Unit, onRaceBox: () -> Unit, onData: () -> Unit) {
    Column(modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(onClick = onCamera, modifier = Modifier.fillMaxWidth()) { Text("Camera test bench (pair, clips, gyro, loop tests)") }
        Button(onClick = onRaceBox, modifier = Modifier.fillMaxWidth()) { Text("RaceBox test panel (connect, live data, CSV log)") }
        OutlinedButton(onClick = onData, modifier = Modifier.fillMaxWidth()) { Text("CSV / XRK viewer (legacy)") }
    }
}

@Composable
private fun LineWidthSetting(settings: AppSettings) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text("Start/finish line width", style = MaterialTheme.typography.titleSmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { settings.changeLineWidthFt(settings.lineWidthFt - 5) }) { Text("−5") }
                Text("%.0f ft  (%.1f m)".format(settings.lineWidthFt, settings.lineWidthFt * Geo.FT),
                    style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp))
                OutlinedButton(onClick = { settings.changeLineWidthFt(settings.lineWidthFt + 5) }) { Text("+5") }
            }
            Text("Applies to lines set from now on; reset a line to apply a new width to it.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

/**
 * Start/stop a run from the phone — for testing without the camera. Normally the camera's recording
 * (from the mic remote) starts and stops runs on its own.
 */
@Composable
private fun RunButton(ctl: SessionController) {
    val run = ctl.currentRun
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(run?.n) { while (run != null) { now = System.currentTimeMillis(); kotlinx.coroutines.delay(500) } }
    if (run == null) {
        val context = LocalContext.current
        val link = remember { OsmoLink.get(context) }
        OutlinedButton(onClick = ctl::startRunFromPhone, modifier = Modifier.fillMaxWidth().height(56.dp)) {
            Text(if (ctl.camera.connected || link.canControlRecording) "Start run + camera recording" else "Start run (phone only — camera not connected)")
        }
    } else {
        val secs = ((now - run.recStartWallMs) / 1000).coerceAtLeast(0)
        Button(
            onClick = ctl::stopRunFromPhone, modifier = Modifier.fillMaxWidth().height(56.dp),
            colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = Color(0xFFE53935)),
        ) { Text("Stop run ${run.n}  ·  %d:%02d  ·  %d GPS fixes".format(secs / 60, secs % 60, run.gpsFixes)) }
    }
}
