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
import com.lunk333.whrrah.session.TrackPoint
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
    val gps = remember { SessionController.get(context) }.gps
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val camColor = when {
                link.recording == true -> Color(0xFFE53935)
                link.phase == OsmoLink.Phase.READY -> Color(0xFF43A047)
                link.phase == OsmoLink.Phase.FAILED -> Color(0xFFE0A000)
                link.phase == OsmoLink.Phase.IDLE -> Color.Gray
                else -> Color(0xFF1E88E5)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Dot(camColor)
                Text(
                    "Camera: " + when {
                        link.recording == true -> "recording"
                        link.phase == OsmoLink.Phase.READY && link.inPlayback -> "connected (viewer mode — can't record yet)"
                        link.phase == OsmoLink.Phase.READY -> "connected, ready"
                        link.phase == OsmoLink.Phase.IDLE -> link.lastCamera?.let { "not connected (${it.name})" } ?: "none set up — pair one in Dev options"
                        else -> link.status
                    },
                    modifier = Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodyMedium,
                )
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

        item { TrackCard(s, canSet = ctl.gps.lastFix != null, onSet = { ctl.setTrackPoint(it) }) }

        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                Stat("Runs", "${s.laps.size}")
                Stat("Best", formatLap(s.bestLapMs))
            }
        }
        item {
            Text("Start recording from the mic remote ~30 s before the start line and stop ~30 s after the finish — each recording is one run.",
                style = MaterialTheme.typography.bodySmall)
        }
        items(s.laps.reversed(), key = { it.n }) { lap -> LapRow(lap) }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                OutlinedButton(onClick = ctl::retryPending, enabled = link.phase == OsmoLink.Phase.READY) { Text("Refresh clips") }
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
private fun TrackCard(s: Session, canSet: Boolean, onSet: (start: Boolean) -> Unit) {
    var open by remember { mutableStateOf(!s.trackDefined) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Track" + if (s.trackDefined) " — defined" else " — not defined", style = MaterialTheme.typography.titleSmall)
                TextButton(onClick = { open = !open }) { Text(if (open) "Hide" else "Define track") }
            }
            if (open) {
                Text("Point-to-point: stand on each line during the course walk and tap.", style = MaterialTheme.typography.bodySmall)
                TrackPointRow("Start", s.start, canSet) { onSet(true) }
                TrackPointRow("Finish", s.finish, canSet) { onSet(false) }
                if (!canSet) Text("Waiting for a GPS fix…", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun TrackPointRow(label: String, p: TrackPoint?, canSet: Boolean, onSet: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(p?.let { "%.6f, %.6f%s".format(it.lat, it.lon, it.accuracyM?.let { a -> " (±%.0f m)".format(a) } ?: "") } ?: "not set",
                style = MaterialTheme.typography.bodySmall)
        }
        Button(onClick = onSet, enabled = canSet) { Text(if (p == null) "Set $label" else "Reset") }
    }
}

@Composable
private fun LapRow(lap: Lap) {
    val fmt = remember { SimpleDateFormat("HH:mm:ss", Locale.US) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Run ${lap.n}" + if (lap.recording) "  ● recording" else "", style = MaterialTheme.typography.titleMedium)
                Text(formatLap(lap.lapTimeMs), style = MaterialTheme.typography.titleMedium)
            }
            val recLen = lap.recStopWallMs?.let { "%d s".format((it - lap.recStartWallMs) / 1000) }
            Text(listOfNotNull(fmt.format(Date(lap.recStartWallMs)), recLen?.let { "rec $it" },
                "GPS ${lap.gpsFixes} fixes").joinToString(" · "), style = MaterialTheme.typography.bodySmall)
            Text(
                listOf(
                    "clip " + (lap.clip ?: if (lap.recording) "—" else "waiting…"),
                    "gyro " + (lap.gyroSamples?.let { "✓ $it" } ?: if (lap.clip != null) "pending" else "—"),
                    "sync " + (lap.syncOffsetMs?.let { "%+d ms".format(it) } ?: "— (needs RaceBox)"),
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
            )
            lap.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Color(0xFFE0A000)) }
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
