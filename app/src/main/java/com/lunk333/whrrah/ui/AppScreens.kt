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
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Checkbox
import com.lunk333.whrrah.session.CONE_PENALTY_MS
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.withStyle
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

/** App-wide chrome switches: [immersive] = a screen (the fullscreen video player) has hidden the header. */
object UiChrome {
    var immersive by mutableStateOf(false)
}

enum class Screen { HOME, NEW_SESSION, OPEN_SESSION, SESSION, VIEW_RUNS, BROWSE_DOWNLOADS, SETTINGS, DEV, DEV_CAMERA, DEV_RACEBOX, DEV_DATA, DEV_OVERLAY, DEV_HDR }

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
        val ctl = SessionController.get(LocalContext.current)
        val headerTitle = if (screen == Screen.SESSION) ctl.session?.event ?: title(screen) else title(screen)
        val header = @Composable { atBottom: Boolean ->
            Header(headerTitle, showBack = stack.size > 1, onBack = ::back, atBottom = atBottom) {
                // Not mid-run: closing would cut the run's logging off.
                if (screen == Screen.SESSION) TextButton(onClick = { ctl.close(); back() }, enabled = ctl.currentRun == null) { Text("Close session") }
            }
        }
        // Landscape session screen: no header up top — it slides up from the bottom on a swipe (SessionLandscape).
        val landscapeSession = screen == Screen.SESSION && ctl.session != null &&
            androidx.compose.ui.platform.LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        if (!landscapeSession && !UiChrome.immersive) header(false)
        val m = Modifier.fillMaxSize()
        when (screen) {
            Screen.HOME -> HomeScreen(m, onNew = { go(Screen.NEW_SESSION) }, onOpen = { go(Screen.OPEN_SESSION) },
                onResume = { go(Screen.SESSION) }, onSettings = { go(Screen.SETTINGS) }, onDev = { go(Screen.DEV) },
                onBrowse = { go(Screen.BROWSE_DOWNLOADS) })
            Screen.NEW_SESSION -> NewSessionScreen(m, onCreated = { replace(Screen.SESSION) })
            Screen.OPEN_SESSION -> OpenSessionScreen(m, onOpened = { replace(Screen.SESSION) })
            Screen.SESSION -> SessionScreen(m, onViewRuns = { go(Screen.VIEW_RUNS) }, header = { header(true) })
            Screen.VIEW_RUNS -> RunViewerScreen(m)
            Screen.BROWSE_DOWNLOADS -> BrowseDownloadsScreen(m)
            Screen.SETTINGS -> SettingsScreen(m)
            Screen.DEV -> DevScreen(m, onCamera = { go(Screen.DEV_CAMERA) }, onRaceBox = { go(Screen.DEV_RACEBOX) }, onData = { go(Screen.DEV_DATA) }, onOverlay = { go(Screen.DEV_OVERLAY) }, onHdr = { go(Screen.DEV_HDR) })
            Screen.DEV_RACEBOX -> com.lunk333.whrrah.racebox.RaceBoxScreen(m)
            Screen.DEV_CAMERA -> CameraScreen(m)
            Screen.DEV_DATA -> legacyData(m)
            Screen.DEV_OVERLAY -> OverlayPreviewScreen(m)
            Screen.DEV_HDR -> HdrTestScreen(m)
        }
    }
}

private fun title(s: Screen) = when (s) {
    Screen.HOME -> APP_TITLE
    Screen.NEW_SESSION -> "New session"
    Screen.OPEN_SESSION -> "Open session"
    Screen.SESSION -> "Session"
    Screen.SETTINGS -> "Settings"
    Screen.DEV -> "Dev options"
    Screen.DEV_CAMERA -> "Camera test bench"
    Screen.DEV_RACEBOX -> "RaceBox test panel"
    Screen.DEV_DATA -> "CSV / XRK viewer (legacy)"
    Screen.DEV_OVERLAY -> "Overlay preview"
    Screen.DEV_HDR -> "HDR chevron test"
    Screen.VIEW_RUNS -> "View runs"
    Screen.BROWSE_DOWNLOADS -> "Downloaded runs"
}

@Composable
private fun Header(title: String, showBack: Boolean, onBack: () -> Unit, atBottom: Boolean = false, actions: @Composable () -> Unit = {}) {
    if (atBottom) HorizontalDivider()
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (showBack) TextButton(onClick = onBack) { Text("←  Back") } else Spacer(Modifier.size(16.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 4.dp).weight(1f))
        actions()
    }
    if (!atBottom) HorizontalDivider()
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

private fun formatLap(ms: Long?): String = com.lunk333.whrrah.settings.TimeFormat.run(ms)

// ---- home ------------------------------------------------------------------------------------

@Composable
private fun HomeScreen(
    modifier: Modifier, onNew: () -> Unit, onOpen: () -> Unit, onResume: () -> Unit, onSettings: () -> Unit, onDev: () -> Unit,
    onBrowse: () -> Unit,
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
        OutlinedButton(onClick = onBrowse, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("Browse downloaded runs") }
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
private fun SessionScreen(modifier: Modifier, onViewRuns: () -> Unit, header: @Composable () -> Unit) {
    val context = LocalContext.current
    val ctl = remember { SessionController.get(context) }
    val link = remember { OsmoLink.get(context) }
    val s = ctl.session ?: return Text("No session open.", modifier.padding(16.dp))
    // Phone turned sideways (e.g. in a windshield mount): just the timer and the run button.
    if (androidx.compose.ui.platform.LocalConfiguration.current.orientation ==
        android.content.res.Configuration.ORIENTATION_LANDSCAPE) return SessionLandscape(modifier, ctl, s, header)

    // Long-press a run → confirm → delete it.
    var deleteAsk by remember { mutableStateOf<Int?>(null) }
    // Long-press a run → its menu (driver, DNF, cones, official time, delete).
    var menuFor by remember { mutableStateOf<Int?>(null) }
    menuFor?.let { n ->
        val lap = s.laps.firstOrNull { it.n == n }
        if (lap == null) menuFor = null
        else RunMenu(lap, s, onSave = { d, dnf, cones, off -> ctl.updateRunInfo(n, d, dnf, cones, off); menuFor = null },
            onDelete = { menuFor = null; deleteAsk = n }, onDismiss = { menuFor = null })
    }
    deleteAsk?.let { n ->
        val lap = s.laps.firstOrNull { it.n == n }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { deleteAsk = null },
            title = { Text("Delete run ${lap?.let { s.runNo(it) } ?: n}?") },
            text = {
                Text(if (lap?.recording == true) "Run $n is still recording — stop it first."
                    else "Removes run ${lap?.let { s.runNo(it) } ?: n}" + (lap?.lapTimeMs?.let { " (${formatLap(it)})" } ?: "") +
                        " from the session and deletes its GPS, RaceBox and gyro logs. This can't be undone." +
                        (if (lap?.clip != null) " The video clip stays on the camera." else ""))
            },
            confirmButton = {
                TextButton(onClick = { ctl.deleteRun(n); deleteAsk = null }, enabled = lap?.recording != true) {
                    Text("Delete", color = if (lap?.recording == true) Color.Gray else Color(0xFFE53935))
                }
            },
            dismissButton = { TextButton(onClick = { deleteAsk = null }) { Text("Cancel") } },
        )
    }

    LazyColumn(modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Column(Modifier.padding(top = 8.dp)) {
                Text("${s.date} · ${s.driver.ifBlank { "?" }} · ${s.car.ifBlank { "?" }}", style = MaterialTheme.typography.bodyMedium)
                // Re-timing / calibrating runs in the background: say so (the times update when it's done).
                ctl.busy?.let { what ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                        androidx.compose.material3.CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text(what, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        }
        if (ctl.camera.problems.isNotEmpty()) item { CameraSetupWarning(ctl.camera) }
        item { LapTimerCard(ctl, s) }
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
        items(s.laps.reversed(), key = { it.n }) { lap -> LapRow(lap, s, onLongPress = { menuFor = lap.n }) }

        item {
            Button(onClick = onViewRuns, enabled = !ctl.matching && ctl.currentRun == null && link.lastCamera != null,
                modifier = Modifier.fillMaxWidth()) { Text("View runs (camera WiFi)") }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                OutlinedButton(onClick = ctl::matchClipsNow, enabled = !ctl.matching && ctl.currentRun == null && link.lastCamera != null) {
                    Text(if (ctl.matching) "Matching…" else "Match clips (camera WiFi)")
                }
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
    var confirm by remember { mutableStateOf<WalkConfirm?>(null) }
    val recording = ctl.walkRecording
    confirm?.let { c ->
        val (title, body, action) = when (c) {
            WalkConfirm.NEW -> Triple("Record a new walk?", "Clears the current walk and both start/finish lines, then starts recording. " +
                "Runs keep their times until the new lines are set.", "Reset & record")
            WalkConfirm.CONTINUE -> Triple("Continue the walk?", "Starts recording again and adds to the existing walk. The lines stay as they are.", "Continue")
            WalkConfirm.RESET_START -> Triple("Move the start line here?", "Replaces the start line with one at your current position and direction.", "Move start")
            WalkConfirm.RESET_FINISH -> Triple("Move the finish line here?", "Replaces the finish line with one at your current position and direction.", "Move finish")
        }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(title) }, text = { Text(body) },
            confirmButton = {
                TextButton(onClick = {
                    error = null
                    when (c) {
                        WalkConfirm.NEW -> ctl.startTrackWalk(append = false)
                        WalkConfirm.CONTINUE -> ctl.startTrackWalk(append = true)
                        WalkConfirm.RESET_START -> error = ctl.setLine(true)
                        WalkConfirm.RESET_FINISH -> error = ctl.setLine(false)
                    }
                    confirm = null
                }) { Text(action, color = if (c == WalkConfirm.CONTINUE) Color.Unspecified else Color(0xFFE53935)) }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
        )
    }
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
                        "as you reach them. New start lines are %.0f ft wide and finish lines %.0f ft (Settings), across your direction of travel; ".format(widthFt, AppSettings.get(LocalContext.current).finishLineWidthFt) +
                        "change a line's width below. Times line up with official timing once you calibrate to a few official times.",
                        style = MaterialTheme.typography.bodySmall)
                    if (s.trackWalkFile == null && !s.trackDefined)
                        Button(onClick = { error = null; ctl.startTrackWalk(append = false) }, Modifier.fillMaxWidth().height(56.dp)) { Text("Record track walk") }
                    else {
                        // Both change the track, so: long-press, then confirm.
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            LongPressButton("Record new walk", "long-press · resets the track", Modifier.weight(1f), filled = true) { confirm = WalkConfirm.NEW }
                            if (s.trackWalkFile != null)
                                LongPressButton("Continue walk", "long-press", Modifier.weight(1f), filled = false) { confirm = WalkConfirm.CONTINUE }
                        }
                    }
                    TrackLineSummary("Start", s.start, onWidth = { ctl.setLineWidth(true, it) }) { m, undo -> ctl.nudgeLine(true, m, undo) }
                    TrackLineSummary("Finish", s.finish, onWidth = { ctl.setLineWidth(false, it) }) { m, undo -> ctl.nudgeLine(false, m, undo) }
                    TimingCalibration(ctl, s)
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Dot(Color(0xFFE53935))
                        Text("Recording · %.0f m walked".format(ctl.walkDistanceM) +
                            (fix?.accuracyM?.let { " · GPS ±%.1f m".format(it) } ?: " · waiting for GPS"),
                            modifier = Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodyMedium)
                    }
                    Text("Walk toward each line in the driving direction, stop on it, tap.", style = MaterialTheme.typography.bodySmall)
                    // Big side-by-side buttons: tap to set; once set they light up, and moving the line
                    // takes a long-press + confirm (so a stray tap can't move it).
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        LineButton("Start", s.start, Color(0xFF2E7D32), fix != null, Modifier.weight(1f),
                            onSet = { error = ctl.setLine(true) }, onResetAsk = { confirm = WalkConfirm.RESET_START })
                        LineButton("Finish", s.finish, Color(0xFFC62828), fix != null, Modifier.weight(1f),
                            onSet = { error = ctl.setLine(false) }, onResetAsk = { confirm = WalkConfirm.RESET_FINISH })
                    }
                    OutlinedButton(onClick = ctl::stopTrackWalk, modifier = Modifier.fillMaxWidth()) { Text("Stop recording") }
                }
                error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Color(0xFFE0A000)) }
                if (s.start != null || s.finish != null || ctl.walkPath.isNotEmpty() || recording)
                    TrackSketch(s.start, s.finish, ctl.walkPath, if (recording) fix else null)
            }
        }
    }
}

/**
 * How runs are timed, and the calibration against official times: the clock starts once the car has
 * moved the session's start distance from its launch out of the stage; the offset fitted to the
 * official times entered on runs is added to every time.
 */
@Composable
private fun TimingCalibration(ctl: SessionController, s: Session) {
    var msg by remember { mutableStateOf<String?>(null) }
    val n = s.laps.count { it.officialTimeMs != null }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Timing: from the launch + %.1f m".format(s.startDistanceM) +
            (if (s.timingOffsetMs != 0L) " · calibrated %+.3f s".format(s.timingOffsetMs / 1000.0) else " · not calibrated"),
            style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { msg = null; ctl.calibrateToOfficial { msg = it } }, enabled = n > 0 && ctl.busy == null) {
                Text(if (n > 0) "Calibrate to $n official time${if (n == 1) "" else "s"}" else "Calibrate (enter official times first)")
            }
            if (s.timingOffsetMs != 0L) TextButton(onClick = { ctl.resetCalibration(); msg = "Calibration cleared" }) { Text("Clear") }
        }
        msg?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun TrackLineSummary(label: String, l: TrackLine?, onWidth: (Double) -> Unit, onNudge: (meters: Double, undo: Boolean) -> Unit) {
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
        // Width in place — a finish across a whole runway needs far more than the start.
        if (l?.headingDeg != null) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick = { onWidth(l.widthM - (if (l.widthM > 15) 10.0 else 3.0) * Geo.FT) }) { Text("− width") }
            Text("%.0f ft wide".format(l.widthM / Geo.FT), style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(onClick = { onWidth(l.widthM + (if (l.widthM >= 15) 10.0 else 3.0) * Geo.FT) }) { Text("+ width") }
        }
    }
}

private enum class WalkConfirm { NEW, CONTINUE, RESET_START, RESET_FINISH }

/** A button that only acts on a long-press (with a buzz) — for things a stray tap shouldn't do. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun LongPressButton(label: String, hint: String, modifier: Modifier, filled: Boolean, onLongPress: () -> Unit) {
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(28.dp)
    androidx.compose.material3.Surface(
        modifier = modifier.height(64.dp).clip(shape).combinedClickable(onClick = {}, onLongClick = {
            haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress); onLongPress()
        }),
        shape = shape,
        color = if (filled) MaterialTheme.colorScheme.primary else Color.Transparent,
        contentColor = if (filled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary,
        border = if (filled) null else androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text(label, style = MaterialTheme.typography.titleMedium)
            Text(hint, style = MaterialTheme.typography.labelSmall)
        }
    }
}

/**
 * A big start/finish button for the walk. Not set: tap to set it here. Set: lit in [color] with a
 * tick and the line's details; a tap does nothing, a long-press asks to move it here.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun LineButton(label: String, l: TrackLine?, color: Color, canSet: Boolean, modifier: Modifier,
                       onSet: () -> Unit, onResetAsk: () -> Unit) {
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val set = l != null && l.headingDeg != null
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp)
    androidx.compose.material3.Surface(
        modifier = modifier.height(140.dp).clip(shape).combinedClickable(
            enabled = canSet,
            onClick = { if (!set) onSet() },
            onLongClick = {
                haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                if (set) onResetAsk() else onSet()
            }),
        shape = shape,
        color = if (set) color else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if (set) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
        border = if (set) null else androidx.compose.foundation.BorderStroke(2.dp, color),
    ) {
        Column(Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text(if (set) "✓ $label set" else "Set $label", style = MaterialTheme.typography.headlineSmall,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Text(
                when {
                    !canSet -> "waiting for GPS"
                    !set -> "tap when you're on the line"
                    else -> "facing %.0f°%s\nlong-press to move".format(l!!.headingDeg, l.accuracyM?.let { " · ±%.1f m".format(it) } ?: "")
                },
                style = MaterialTheme.typography.bodySmall, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}

/**
 * Long-press menu for a run: who drove it, DNF, cones hit (2 s each), and the official time (raw, from
 * the timing system — shown against the app's own time to spot a start/finish line that's off).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RunMenu(lap: Lap, s: Session, onSave: (driver: String?, dnf: Boolean, cones: Int, officialMs: Long?) -> Unit,
                    onDelete: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val drivers = remember { (listOf(s.driver) + AppSettings.get(context).drivers).filter { it.isNotBlank() }.distinct() }
    var driver by remember { mutableStateOf(lap.driver ?: s.driver) }
    var dnf by remember { mutableStateOf(lap.dnf) }
    var cones by remember { mutableStateOf(lap.cones) }
    var official by remember { mutableStateOf(lap.officialTimeMs?.let { formatLap(it) } ?: "") }
    val officialMs = parseRunTime(official)
    val officialBad = official.isNotBlank() && officialMs == null
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Run ${s.runNo(lap)}" + (lap.lapTimeMs?.let { " · ${formatLap(it)}" } ?: "")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Driver", style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    drivers.forEach { d ->
                        androidx.compose.material3.FilterChip(selected = driver == d, onClick = { driver = d }, label = { Text(d) })
                    }
                }
                if (drivers.size < 2) Text("Add a co-driver in Settings → Drivers to pick them here.", style = MaterialTheme.typography.bodySmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(dnf, { dnf = it })
                    Column {
                        Text("DNF / off course")
                        Text("Not counted for best or used for the delta", style = MaterialTheme.typography.bodySmall)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Cones", Modifier.weight(1f))
                    OutlinedButton(onClick = { cones = (cones - 1).coerceAtLeast(0) }, enabled = cones > 0) { Text("−") }
                    Text("$cones", style = MaterialTheme.typography.titleMedium)
                    OutlinedButton(onClick = { cones++ }) { Text("+") }
                }
                if (cones > 0) Text("+${cones * 2} s" + (lap.lapTimeMs?.let { " → ${formatLap(it + cones * CONE_PENALTY_MS)}" } ?: ""),
                    style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(value = official, onValueChange = { official = it }, singleLine = true,
                    label = { Text("Official time (raw, e.g. 45.123 or 1:05.123)") }, isError = officialBad,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal))
                if (officialMs != null && lap.lapTimeMs != null) {
                    val d = officialMs - lap.lapTimeMs
                    Text("App's time is %.3f s %s than official".format(kotlin.math.abs(d) / 1000.0, if (d > 0) "shorter" else "longer") +
                        (s.officialDiffMeanMs?.let { " · session average %+.3f s".format(it / 1000.0) } ?: ""),
                        style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = onDelete, enabled = !lap.recording) { Text("Delete run…", color = Color(0xFFE53935)) }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(driver.takeIf { it != s.driver && it.isNotBlank() }, dnf, cones, if (official.isBlank()) null else officialMs) },
                enabled = !officialBad) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** "45.123", "1:05.123" or "1:05" → ms; null if it doesn't parse. */
private fun parseRunTime(t: String): Long? {
    val m = Regex("""^\s*(?:(\d+):)?(\d+(?:\.\d{1,3})?)\s*$""").matchEntire(t) ?: return null
    val min = m.groupValues[1].toLongOrNull() ?: 0
    val sec = m.groupValues[2].toDoubleOrNull() ?: return null
    return min * 60_000 + Math.round(sec * 1000)
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
private fun LapRow(lap: Lap, s: Session, onLongPress: () -> Unit) {
    val fmt = remember { SimpleDateFormat("HH:mm:ss", Locale.US) }
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    // Guest (co-driver) runs get a tinted card so the two drivers' runs are easy to tell apart.
    val guest = lap.driver != null && lap.driver != s.driver
    Card(Modifier.fillMaxWidth().pointerInput(lap.n) {
        detectTapGestures(onLongPress = { haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress); onLongPress() })
    }, colors = if (guest) androidx.compose.material3.CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer) else androidx.compose.material3.CardDefaults.cardColors(),
        // Each driver's best run gets a gold outline.
        border = if (s.isDriverBest(lap)) androidx.compose.foundation.BorderStroke(3.dp, Color(0xFFFFC107)) else null) {
        Column(Modifier.padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text((if (s.isDriverBest(lap)) "★ " else "") + "Run ${s.runNo(lap)}" + s.driverOf(lap).takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty() +
                    if (lap.recording) "  ● recording" else "", style = MaterialTheme.typography.titleMedium)
                Column(horizontalAlignment = Alignment.End) {
                    Text(if (lap.dnf) "DNF" else formatLap(lap.effectiveTimeMs ?: lap.lapTimeMs),
                        style = MaterialTheme.typography.titleMedium, color = if (lap.dnf) Color(0xFFE53935) else Color.Unspecified)
                    // Raw + cones, and the official time with the difference.
                    val extras = listOfNotNull(
                        if (lap.cones > 0 && lap.lapTimeMs != null) "${formatLap(lap.lapTimeMs)} +${lap.cones}×2 s" else null,
                        if (lap.dnf && lap.lapTimeMs != null) "(${formatLap(lap.lapTimeMs)})" else null,
                        lap.officialTimeMs?.let { o -> "official ${formatLap(o)}" + (lap.officialDiffMs?.let { " (%+.3f)".format(it / 1000.0) } ?: "") },
                    )
                    if (extras.isNotEmpty()) Text(extras.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                }
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
        item { Toggle("Show times in seconds (65.123 instead of 1:05.123)", settings.timesInSeconds, settings::changeTimesInSeconds) }
        item { Toggle("Full screen brightness on the landscape timer", settings.forceFullBrightness, settings::changeForceFullBrightness) }
        item { DeltaStyleSetting(settings) }
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
private fun DevScreen(modifier: Modifier, onCamera: () -> Unit, onRaceBox: () -> Unit, onData: () -> Unit, onOverlay: () -> Unit, onHdr: () -> Unit) {
    Column(modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(onClick = onCamera, modifier = Modifier.fillMaxWidth()) { Text("Camera test bench (pair, clips, gyro, loop tests)") }
        Button(onClick = onRaceBox, modifier = Modifier.fillMaxWidth()) { Text("RaceBox test panel (connect, live data, CSV log)") }
        Button(onClick = onOverlay, modifier = Modifier.fillMaxWidth()) { Text("Overlay preview (layout + a saved run, no video)") }
        Button(onClick = onHdr, modifier = Modifier.fillMaxWidth()) { Text("HDR chevron test (brighter delta bar)") }
        OutlinedButton(onClick = onData, modifier = Modifier.fillMaxWidth()) { Text("CSV / XRK viewer (legacy)") }
    }
}

/** Chevrons or boxes for the landscape timer's delta bar. */
@Composable
private fun DeltaStyleSetting(settings: AppSettings) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Delta bar style", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        for ((label, boxes) in listOf("‹‹ Chevrons" to false, "▮▮ Boxes" to true)) {
            if (settings.deltaBoxes == boxes) Button(onClick = {}) { Text(label) }
            else OutlinedButton(onClick = { settings.changeDeltaBoxes(boxes) }) { Text(label) }
        }
    }
}

@Composable
private fun LineWidthSetting(settings: AppSettings) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text("Start / finish line width", style = MaterialTheme.typography.titleSmall)
            for ((label, ft, set) in listOf(Triple("Start", settings.lineWidthFt, settings::changeLineWidthFt),
                                            Triple("Finish", settings.finishLineWidthFt, settings::changeFinishLineWidthFt))) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(label, Modifier.width(56.dp))
                    OutlinedButton(onClick = { set(ft - 5) }) { Text("−5") }
                    Text("%.0f ft  (%.1f m)".format(ft, ft * Geo.FT),
                        style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 12.dp))
                    OutlinedButton(onClick = { set(ft + 5) }) { Text("+5") }
                }
            }
            Text("For lines set from now on. A session's own lines: change their width on the session's track card.",
                style = MaterialTheme.typography.bodySmall)
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
        ) { Text("Stop run ${ctl.session?.runNo(run) ?: run.n}  ·  %d:%02d  ·  %d GPS fixes".format(secs / 60, secs % 60, run.gpsFixes)) }
    }
}

/**
 * Big lap timer: live while a run is between the lines, frozen on the lap time at the finish,
 * otherwise the last run's time.
 */
@Composable
private fun LapTimerCard(ctl: SessionController, s: Session) {
    val (ms, caption, color) = lapTimerState(ctl, s)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text("Lap Time", style = MaterialTheme.typography.titleMedium)
            Text(
                fmtTimer(ms),
                style = MaterialTheme.typography.displayLarge.copy(fontFeatureSettings = "tnum"),   // digits don't jiggle
                color = color,
            )
            Text(caption, style = MaterialTheme.typography.titleMedium)
        }
    }
}

private fun fmtTimer(ms: Long?) = com.lunk333.whrrah.settings.TimeFormat.run(ms, com.lunk333.whrrah.settings.TimeFormat.placeholder)

/** What the lap timer shows: (time, caption, colour). Ticks itself while a lap is live. */
@Composable
private fun lapTimerState(ctl: SessionController, s: Session): Triple<Long?, String, Color> {
    val run = ctl.currentRun
    val start = ctl.liveStartElapsedMs
    val live = run != null && start != null && ctl.liveLapMs == null
    var now by remember { mutableStateOf(android.os.SystemClock.elapsedRealtime()) }
    LaunchedEffect(live) { while (live) { now = android.os.SystemClock.elapsedRealtime(); kotlinx.coroutines.delay(31) } }
    val last = s.laps.lastOrNull { !it.recording }
    return when {
        run != null && ctl.liveLapMs != null -> Triple(ctl.liveLapMs, "Run ${s.runNo(run)} · finished", Color(0xFF43A047))
        live -> Triple((now - start!!).toLong().coerceAtLeast(0), "Run ${s.runNo(run!!)} · on course", Color(0xFFE53935))
        run != null -> Triple(0L, "Run ${s.runNo(run)} · waiting for the start line", MaterialTheme.colorScheme.onSurface)
        last != null -> Triple(last.lapTimeMs, "Run ${s.runNo(last)}" + (if (last.lapTimeMs == null) " · no time" else "") +
            (if (last.dnf) " · DNF" else "") + (if (last.cones > 0) " · +${last.cones} cone${if (last.cones > 1) "s" else ""}" else "") +
            (if (s.isDriverBest(last)) " · best" else ""), MaterialTheme.colorScheme.onSurface)
        else -> Triple(0L, "No runs yet", MaterialTheme.colorScheme.onSurface)
    }
}

/**
 * Landscape session: the lap timer fills the left ~4/5 (time sized to the space), the run button the
 * right. Everything else (track, run list, clips) is on the portrait screen.
 */
@Composable
private fun SessionLandscape(modifier: Modifier, ctl: SessionController, s: Session, header: @Composable () -> Unit) {
    val (ms, caption, color) = lapTimerState(ctl, s)
    // Full screen here: hide the status and navigation bars — a swipe from the edge brings them back for a moment.
    val view = androidx.compose.ui.platform.LocalView.current
    DisposableEffect(view) {
        val window = (view.context as? android.app.Activity)?.window
        val bars = window?.let { androidx.core.view.WindowCompat.getInsetsController(it, view) }
        bars?.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        bars?.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        onDispose { bars?.show(androidx.core.view.WindowInsetsCompat.Type.systemBars()) }
    }
    // The header (Back / Close session) is hidden; swipe up anywhere to bring it up from the bottom,
    // swipe down or wait a few seconds to put it away.
    var showHeader by remember { mutableStateOf(false) }
    LaunchedEffect(showHeader) { if (showHeader) { kotlinx.coroutines.delay(HEADER_SHOW_MS); showHeader = false } }
    val swipe = with(androidx.compose.ui.platform.LocalDensity.current) { 48.dp.toPx() }
    val settings = remember { AppSettings.get(ctl.app) }
    FullBrightnessEffect(settings.forceFullBrightness)
    val hdrBoost = rememberHdrBoost(settings.hdrChevrons, settings.hdrManualBoost)
    // Panel states (design notes: "Landscape lap time panel states"):
    //  1 not recording — wide Start run button (1/3), no delta bar: the last run's time big;
    //  2 recording, before the start line — narrower button, delta bar shown;
    //  3 on course — the timer fills the screen, a blinking red dot says it's recording, and a
    //    sideways swipe brings the Stop button in for a few seconds;
    //  4 past the finish (still recording) — back to state 1's sizes.
    val recording = ctl.currentRun != null
    val state = when {
        !recording -> 1
        ctl.liveLapMs != null -> 4
        ctl.liveStartElapsedMs != null -> 3
        else -> 2
    }
    var stopShown by remember { mutableStateOf(false) }
    LaunchedEffect(state) { stopShown = false }
    LaunchedEffect(stopShown) { if (stopShown) { kotlinx.coroutines.delay(STOP_SHOW_MS); stopShown = false } }
    val showDelta = state == 2 || state == 3
    val buttonFrac by androidx.compose.animation.core.animateFloatAsState(when {
        state == 3 && !stopShown -> 0f
        state == 2 || state == 3 -> 1f / 5.5f
        else -> 1f / 3
    }, label = "runButton")
    Box(modifier.pointerInput(Unit) {
        var dy = 0f
        detectVerticalDragGestures(onDragStart = { dy = 0f }, onDragEnd = {
            if (dy < -swipe) showHeader = true else if (dy > swipe) showHeader = false
        }) { _, d -> dy += d }
    }.pointerInput(state) {
        // On course: a sideways swipe (either way) brings the Stop button in.
        var dx = 0f
        if (state == 3) detectHorizontalDragGestures(onDragStart = { dx = 0f }, onDragEnd = {
            if (kotlin.math.abs(dx) > swipe) stopShown = true
        }) { _, d -> dx += d }
    }) {
    Row(Modifier.fillMaxSize().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(if (buttonFrac > 0.01f) 12.dp else 0.dp)) {
        Card(Modifier.weight(1f - buttonFrac).fillMaxHeight()) {
            androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
                // With the delta bar (states 2, 3) it takes 2/3 of the height above the time; otherwise
                // the time has it all.
                val avail = maxHeight.value - 90f
                val barH by androidx.compose.animation.core.animateFloatAsState(if (showDelta) avail * 2f / 3 else 0f, label = "deltaBar")
                // "0:00.000" with tabular digits is ~5 em wide; also leave room for the text lines and the bar.
                val panelW = maxWidth.value
                val byWidth = panelW / 5.2f
                val byHeight = (avail - barH - 8f) / 1.25f
                val size = minOf(byWidth, byHeight).coerceAtLeast(24f)
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
                    Text("Lap Time", style = MaterialTheme.typography.titleMedium)
                    if (barH > 1f) DeltaChevrons(ctl.liveDelta, barH, panelW, hdrBoost, settings.deltaBoxes)
                    Text(fmtTimer(ms), color = color, maxLines = 1, softWrap = false,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.fillMaxWidth(),
                        style = MaterialTheme.typography.displayLarge.copy(fontFeatureSettings = "tnum",
                            fontSize = size.sp, lineHeight = (size * 1.1f).sp))
                    val big = MaterialTheme.typography.titleMedium.let { it.copy(fontSize = it.fontSize * 1.5f) }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(caption, style = big)
                        Text("Best " + (s.bestLapMs?.let(::fmtTimer) ?: "—"), style = big)
                    }
                }
            }
        }
        if (buttonFrac > 0.01f) Column(Modifier.weight(buttonFrac).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PermissionCard(onGranted = ctl::startGps)
            if (ctl.camera.problems.isNotEmpty())
                Text("⚠ Check the camera", color = Color(0xFFC62828), style = MaterialTheme.typography.titleSmall)
            LandscapeRunButton(ctl, Modifier.fillMaxWidth().weight(1f))
        }
    }
    // Recording indicator while the timer has the whole screen.
    // In line with the run / best captions, between them.
    if (state == 3) RecordingDot(Modifier.align(Alignment.BottomCenter).padding(bottom = 32.dp))
    androidx.compose.animation.AnimatedVisibility(
        visible = showHeader, modifier = Modifier.align(Alignment.BottomCenter),
        enter = androidx.compose.animation.slideInVertically { it }, exit = androidx.compose.animation.slideOutVertically { it },
    ) {
        androidx.compose.material3.Surface(color = MaterialTheme.colorScheme.background) { Column { header() } }
    }
    }
}

/** A red dot blinking about once a second. */
@Composable
private fun RecordingDot(modifier: Modifier) {
    val t = androidx.compose.animation.core.rememberInfiniteTransition(label = "rec")
    val alpha by t.animateFloat(1f, 0.15f, androidx.compose.animation.core.infiniteRepeatable(
        androidx.compose.animation.core.tween(600), androidx.compose.animation.core.RepeatMode.Reverse), label = "recAlpha")
    Box(modifier.size(18.dp).clip(CircleShape).background(Color(0xFFE53935).copy(alpha = alpha)))
}

private const val STOP_SHOW_MS = 5000L
private const val HEADER_SHOW_MS = 5000L

/**
 * Live delta to the session's best run as a chevron bar, `<<<<<<<<<< +0.42 >>>>>>>>>>`, drawn to the
 * full width: each chevron is 0.2 s; behind lights the left ones red from the centre out, ahead the
 * right ones green. Greyed while the car is off the reference line (the last value held); all dim
 * with no reference yet.
 */
@Composable
private fun DeltaChevrons(st: com.lunk333.whrrah.session.DeltaState?, heightDp: Float, widthDp: Float, hdrBoost: Float = 1f, boxes: Boolean = false) {
    val d = st?.delta
    val ticks = if (d == null) 0 else minOf(DELTA_CHEVRONS, (kotlin.math.abs(d) / DELTA_TICK_S + 1e-9).toInt())
    val held = st != null && !st.onTrack
    val dim = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
    val red = if (held) Color.Gray else Color(0xFFE53935)
    val green = if (held) Color.Gray else Color(0xFF43A047)
    val numColor = when {
        d == null || held -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
        d > 0 -> red
        d < 0 -> green
        else -> MaterialTheme.colorScheme.onSurface
    }
    Row(Modifier.fillMaxWidth().height(heightDp.dp), verticalAlignment = Alignment.CenterVertically) {
        // Left half: index 0 is the outermost; behind lights from the centre out.
        ChevronHalf(pointLeft = true, Modifier.weight(1f).fillMaxHeight(), hdrBoost, boxes) { i ->
            if (d != null && d > 0 && i >= DELTA_CHEVRONS - ticks) red to true else dim to false
        }
        Text(if (d == null) "—" else (if (d < 0) "−" else "+") + "%.2f".format(kotlin.math.abs(d)),
            color = numColor, maxLines = 1, softWrap = false, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.weight(2 * DELTA_NUMBER_FRAC / (1 - DELTA_NUMBER_FRAC)),   // halves weigh 1 each
            style = MaterialTheme.typography.titleLarge.copy(fontFeatureSettings = "tnum",
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                // As tall as the bar allows, but "+0.00" has to fit its share of the width.
                fontSize = minOf(heightDp * 0.55f, widthDp * DELTA_NUMBER_FRAC / 3.4f).sp))
        // Right half: index 0 is next to the centre; ahead lights from the centre out.
        ChevronHalf(pointLeft = false, Modifier.weight(1f).fillMaxHeight(), hdrBoost, boxes) { i ->
            if (d != null && d < 0 && i < ticks) green to true else dim to false
        }
    }
}

/**
 * [DELTA_CHEVRONS] chevrons filling the space, each coloured by [color] (index left to right → colour,
 * lit). Lit ones are drawn [hdrBoost]× brighter (extended range: shows in an HDR window only).
 * [boxes]: filled rounded boxes instead of chevrons.
 */
@Composable
private fun ChevronHalf(pointLeft: Boolean, modifier: Modifier, hdrBoost: Float = 1f, boxes: Boolean = false, color: (Int) -> Pair<Color, Boolean>) {
    val colors = (0 until DELTA_CHEVRONS).map(color)
    val paint = remember { android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.STROKE; strokeCap = android.graphics.Paint.Cap.ROUND; strokeJoin = android.graphics.Paint.Join.ROUND
    } }
    androidx.compose.foundation.Canvas(modifier) {
        val cell = size.width / DELTA_CHEVRONS
        val w = cell * 0.62f
        val h = minOf(size.height * 0.8f, cell * 4f)   // tall but still chevron-shaped
        val stroke = cell * 0.24f
        val cy = size.height / 2
        for (i in 0 until DELTA_CHEVRONS) {
            val cx = cell * (i + 0.5f)
            val tip = if (pointLeft) cx - w / 2 else cx + w / 2
            val back = if (pointLeft) cx + w / 2 else cx - w / 2
            val (c, lit) = colors[i]
            paint.setColor(hdrColor(c, if (lit) hdrBoost else 1f))
            if (boxes) {
                // A filled box per 0.2 s: most of the cell wide, as tall as a chevron.
                paint.style = android.graphics.Paint.Style.FILL
                val bw = cell * 0.72f
                drawContext.canvas.nativeCanvas.drawRoundRect(cx - bw / 2, cy - h / 2, cx + bw / 2, cy + h / 2,
                    cell * 0.12f, cell * 0.12f, paint)
            } else {
                paint.style = android.graphics.Paint.Style.STROKE
                paint.strokeWidth = stroke
                val path = android.graphics.Path().apply {
                    moveTo(back, cy - h / 2); lineTo(tip, cy); lineTo(back, cy + h / 2)
                }
                drawContext.canvas.nativeCanvas.drawPath(path, paint)
            }
        }
    }
}

/**
 * Dev: compare the delta chevrons drawn normally (top) and in HDR (bottom), switch HDR on/off for the
 * landscape timer, and set the brightness multiplier (auto = the display's current headroom).
 */
@Composable
private fun HdrTestScreen(modifier: Modifier) {
    val context = LocalContext.current
    val settings = remember { AppSettings.get(context) }
    val view = androidx.compose.ui.platform.LocalView.current
    val boost = rememberHdrBoost(settings.hdrChevrons, settings.hdrManualBoost)
    var ratio by remember { mutableStateOf(hdrSdrRatioNow(view)) }
    LaunchedEffect(Unit) { while (true) { ratio = hdrSdrRatioNow(view); kotlinx.coroutines.delay(500) } }
    val caps = remember { view.display?.hdrCapabilities }
    var sample by remember { mutableStateOf(1.0) }       // + behind (red) / − ahead (green)
    Column(modifier.padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(listOf(
            "Android API ${Build.VERSION.SDK_INT}" + if (!hdrSupported) " — HDR drawing needs Android 14+" else "",
            "Display HDR: " + (caps?.let { c -> "types ${c.supportedHdrTypes.joinToString()}, peak ${c.desiredMaxLuminance.toInt()} nits" } ?: "—"),
            "HDR/SDR headroom now: " + (ratio?.let { "%.2f×".format(it) } ?: "not reported") +
                if (!settings.hdrChevrons) " (window not in HDR mode — turn it on below)" else "",
            "Chevrons drawn at: %.2f×".format(boost),
        ).joinToString("\n"), style = MaterialTheme.typography.bodyMedium)
        Toggle("Use HDR for the timer's delta chevrons", settings.hdrChevrons, settings::changeHdrChevrons)
        Text("Brightness multiplier: " + if (settings.hdrManualBoost <= 0f) "auto (display headroom)" else "%.1f×".format(settings.hdrManualBoost),
            style = MaterialTheme.typography.bodyMedium)
        androidx.compose.material3.Slider(value = settings.hdrManualBoost, onValueChange = { settings.changeHdrManualBoost(if (it < 1f) 0f else it) },
            valueRange = 0f..8f, enabled = settings.hdrChevrons)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { sample = 1.0 }) { Text("Behind (red)") }
            OutlinedButton(onClick = { sample = -1.0 }) { Text("Ahead (green)") }
        }
        val st = com.lunk333.whrrah.session.DeltaState(10.0, sample, null, 50.0, onTrack = true)
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
            val w = maxWidth.value
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Normal", style = MaterialTheme.typography.titleSmall)
                DeltaChevrons(st, 90f, w, 1f, settings.deltaBoxes)
                Text("HDR", style = MaterialTheme.typography.titleSmall)
                DeltaChevrons(st, 90f, w, boost, settings.deltaBoxes)
            }
        }
        Text("The HDR row only looks brighter while the switch is on. The boost is biggest indoors at a medium " +
            "brightness and shrinks as the screen nears its peak (e.g. in sunlight).", style = MaterialTheme.typography.bodySmall)
    }
}

private const val DELTA_CHEVRONS = 10
private const val DELTA_TICK_S = 0.2
/** Share of the bar's width for the number in the middle. */
private const val DELTA_NUMBER_FRAC = 0.2f

/** [RunButton] as a tall block for the landscape screen. */
@Composable
private fun LandscapeRunButton(ctl: SessionController, modifier: Modifier) {
    val run = ctl.currentRun
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(run?.n) { while (run != null) { now = System.currentTimeMillis(); kotlinx.coroutines.delay(500) } }
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp)
    if (run == null) {
        val context = LocalContext.current
        val link = remember { OsmoLink.get(context) }
        Button(onClick = ctl::startRunFromPhone, modifier = modifier, shape = shape) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Start run", style = MaterialTheme.typography.headlineSmall)
                Text(if (ctl.camera.connected || link.canControlRecording) "+ camera" else "phone only", style = MaterialTheme.typography.bodyMedium)
            }
        }
    } else {
        val secs = ((now - run.recStartWallMs) / 1000).coerceAtLeast(0)
        Button(onClick = ctl::stopRunFromPhone, modifier = modifier, shape = shape,
            colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = Color(0xFFE53935))) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Stop run ${ctl.session?.runNo(run) ?: run.n}", style = MaterialTheme.typography.headlineSmall)
                Text("%d:%02d".format(secs / 60, secs % 60), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/** Red banner when the camera's setup would spoil a run (wrong mode, stabilization on, FOV changed). */
@Composable
private fun CameraSetupWarning(cam: com.lunk333.whrrah.osmo.CameraMonitor) {
    Card(Modifier.fillMaxWidth(), colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = Color(0xFFC62828))) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("⚠ Check the camera", style = MaterialTheme.typography.titleMedium, color = Color.White)
            cam.problems.forEach { Text(it, style = MaterialTheme.typography.bodyMedium, color = Color.White) }
            if (cam.setup?.mode != com.lunk333.whrrah.osmo.CameraMonitor.MODE_VIDEO)
                Button(onClick = { cam.switchToVideo() }, colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                    containerColor = Color.White, contentColor = Color(0xFFC62828))) { Text("Switch to video") }
        }
    }
}
