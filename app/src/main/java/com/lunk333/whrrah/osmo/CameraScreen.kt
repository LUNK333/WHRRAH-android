package com.lunk333.whrrah.osmo

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.lunk333.whrrah.osmo.gyro.GyroSamples
import dev.konraditurbe.osmosis.core.CameraFile
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.min

/** Test bench for the DJI camera link (B1c–B1f): pair, join WiFi, list clips, pull gyro, see timings. */
@Composable
fun CameraScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val link = remember { OsmoLink.get(context) }

    val perms = remember {
        if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }
    fun granted() = perms.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
    var hasPerms by remember { mutableStateOf(granted()) }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { hasPerms = granted() }

    var selected by remember { mutableStateOf<CameraFile?>(null) }
    var windowSec by remember { mutableStateOf<Int?>(60) }
    var parallel by remember { mutableStateOf(4) }
    var password by remember { mutableStateOf("") }
    var showLog by remember { mutableStateOf(false) }

    val pickLocal = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) link.decodeLocalGyro(uri, displayName(context, uri))
    }

    val busy = link.phase in setOf(OsmoLink.Phase.CONNECTING, OsmoLink.Phase.PAIRING, OsmoLink.Phase.APPROVE_ON_CAMERA,
        OsmoLink.Phase.READING_WIFI, OsmoLink.Phase.JOINING_WIFI, OsmoLink.Phase.LISTING)

    LazyColumn(modifier = modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { RecordingCard(link) }
        item {
            Card(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Text("DJI camera · ${link.phase.name.replace('_', ' ').lowercase()}", style = MaterialTheme.typography.titleMedium)
                    Text(link.status, style = MaterialTheme.typography.bodyMedium)
                    link.ssid?.let { Text("WiFi: $it", style = MaterialTheme.typography.bodySmall) }
                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                }
            }
        }

        item {
            if (!hasPerms) {
                Button(onClick = { permLauncher.launch(perms) }) { Text("Grant Bluetooth permission") }
            } else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (link.phase == OsmoLink.Phase.SCANNING) OutlinedButton(onClick = link::stopScan) { Text("Stop scan") }
                else Button(onClick = link::startScan, enabled = !busy) { Text("Scan") }
                val last = link.lastCamera
                if (last != null && link.phase !in setOf(OsmoLink.Phase.READY, OsmoLink.Phase.SCANNING) && !busy)
                    Button(onClick = { link.connect(last.address, last.name) }) { Text("Reconnect ${last.name}") }
                if (link.phase == OsmoLink.Phase.READY) Button(onClick = link::listFiles) { Text("Refresh list") }
                if (link.phase != OsmoLink.Phase.IDLE) OutlinedButton(onClick = { selected = null; link.disconnect() }) { Text("Disconnect") }
            }
        }

        if (link.phase == OsmoLink.Phase.SCANNING || (link.phase == OsmoLink.Phase.IDLE && link.found.isNotEmpty())) {
            if (link.found.isEmpty()) item { Text("Looking… make sure the camera is on and Mimo isn't connected to it.") }
            items(link.found, key = { it.address }) { f ->
                Card(Modifier.fillMaxWidth().clickable { link.connect(f.address, f.name) }) {
                    Row(Modifier.padding(12.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(f.name); Text("${f.rssi} dBm · tap to connect", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        if (link.phase == OsmoLink.Phase.NEED_PASSWORD) item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = password, onValueChange = { password = it }, label = { Text("Camera WiFi password") },
                    singleLine = true, modifier = Modifier.weight(1f))
                Button(onClick = { link.submitPassword(password) }, enabled = password.isNotBlank()) { Text("Join") }
            }
        }

        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Gyro fetch", style = MaterialTheme.typography.titleSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Window", Modifier.padding(top = 8.dp))
                        FilterChip(windowSec == 10, { windowSec = 10 }, { Text("10 s") })
                        FilterChip(windowSec == 60, { windowSec = 60 }, { Text("60 s") })
                        FilterChip(windowSec == null, { windowSec = null }, { Text("Whole clip") })
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Parallel", Modifier.padding(top = 8.dp))
                        for (n in listOf(1, 4, 8)) FilterChip(parallel == n, { parallel = n }, { Text("$n") })
                    }
                    Text(selected?.let { "Selected: ${it.name}" } ?: "Pick a clip below (camera), or decode one already on the phone.",
                        style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { selected?.let { link.fetchCameraGyro(it, windowSec, parallel) } },
                            enabled = selected != null && link.phase == OsmoLink.Phase.READY && !link.gyroBusy) { Text("Fetch from camera") }
                        OutlinedButton(onClick = { pickLocal.launch(arrayOf("video/*")) }, enabled = !link.gyroBusy) { Text("Phone file…") }
                        if (link.gyroBusy) TextButton(onClick = link::cancelGyro) { Text("Cancel") }
                    }
                    if (link.gyroBusy) LinearProgressIndicator(progress = { link.gyroProgress }, modifier = Modifier.fillMaxWidth())
                    link.gyro?.let { g ->
                        HorizontalDivider()
                        Text(g.clip, style = MaterialTheme.typography.bodyMedium)
                        Text("${g.track.header.model ?: "?"} fw ${g.track.header.firmware ?: "?"}, " +
                            "%.3f fps".format(g.track.header.fps ?: 0.0), style = MaterialTheme.typography.bodySmall)
                        Text("${g.samples.size} samples · ${g.track.frames.size}/${g.track.totalFrames} frames", style = MaterialTheme.typography.bodySmall)
                        Text("%.1f MB · %d requests · %.1f s · %.1f ms/request".format(
                            g.track.bytesRead / 1e6, g.track.requests, g.seconds, g.msPerRequest), style = MaterialTheme.typography.bodySmall)
                        g.savedTo?.let { Text("Saved $it", style = MaterialTheme.typography.bodySmall) }
                        if (g.samples.size > 1) {
                            Text("Angular speed (°/s) — shake the camera while recording to see spikes", style = MaterialTheme.typography.bodySmall)
                            AngularSpeedPlot(g.samples)
                        } else Text("No 1 kHz gyro in this clip (recording format without gyro).", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        if (link.files.isNotEmpty()) {
            item { Text("Videos on camera (newest first)", style = MaterialTheme.typography.titleSmall) }
            items(link.files, key = { "${it.storage}:${it.path}" }) { f ->
                val isSel = selected?.path == f.path
                val isNew = f.path in link.newPaths
                Card(
                    Modifier.fillMaxWidth().clickable { selected = f },
                    colors = if (isNew) CardDefaults.cardColors(containerColor = Color(0x6643A047)) else CardDefaults.cardColors(),
                ) {
                    Column(Modifier.padding(10.dp)) {
                        Text((if (isSel) "▶ " else "") + (if (isNew) "NEW · " else "") + f.name, style = MaterialTheme.typography.bodyMedium)
                        Text(listOfNotNull(f.dateTaken.ifEmpty { null }, f.durationSec.takeIf { it > 0 }?.let { "%d:%02d".format(it / 60, it % 60) },
                            f.resolution, f.resLabel, f.sizeBytes.takeIf { it > 0 }?.let { "%.1f GB".format(it / 1e9) },
                            if (f.storage == 1) "internal" else "SD").joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Log", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))
                TextButton(onClick = { showLog = !showLog }) { Text(if (showLog) "Hide" else "Show") }
            }
        }
        if (showLog) items(link.log.asReversed().take(150)) { line ->
            Text(line, fontFamily = FontFamily.Monospace, fontSize = 10.sp, lineHeight = 12.sp)
        }
    }
}

/** |angular velocity| between consecutive samples, decimated to at most ~600 points, as a line plot. */
@Composable
private fun AngularSpeedPlot(s: GyroSamples) {
    val color = MaterialTheme.colorScheme.primary
    val points = remember(s) {
        val step = maxOf(1, s.size / 600)
        val out = ArrayList<Pair<Double, Double>>()
        var i = step
        while (i < s.size) {
            val j = i - step
            val dot = abs(s.w[i] * s.w[j] + s.x[i] * s.x[j] + s.y[i] * s.y[j] + s.z[i] * s.z[j])
            val dt = (s.tMs[i] - s.tMs[j]) / 1000.0
            if (dt > 0) out.add(s.tMs[i] / 1000.0 to Math.toDegrees(2 * acos(min(1.0, dot))) / dt)
            i += step
        }
        out
    }
    if (points.size < 2) return
    val t0 = points.first().first
    val t1 = points.last().first
    val vMax = points.maxOf { it.second }.coerceAtLeast(1.0)
    Text("%.1f–%.1f s · peak %.0f °/s".format(t0, t1, vMax), style = MaterialTheme.typography.bodySmall)
    Canvas(Modifier.fillMaxWidth().height(120.dp)) {
        val path = Path()
        points.forEachIndexed { k, (t, v) ->
            val p = Offset(((t - t0) / (t1 - t0)).toFloat() * size.width, size.height - (v / vMax).toFloat() * size.height)
            if (k == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
        }
        drawPath(path, color, style = Stroke(width = 2f))
    }
}

private fun displayName(context: android.content.Context, uri: Uri): String =
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
        if (it.moveToFirst()) it.getString(0) else null
    } ?: uri.lastPathSegment ?: "clip"

/** Is the camera recording? From the camera's status pushes on the WiFi datalink. */
@Composable
private fun RecordingCard(link: OsmoLink) {
    // Tick so the staleness check stays current between pushes.
    var now by remember { mutableStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(Unit) { while (true) { delay(500); now = SystemClock.elapsedRealtime() } }

    val ageS = if (link.lastStatusElapsedMs == 0L) null else (now - link.lastStatusElapsedMs) / 1000.0
    val (light, label) = when {
        link.inPlayback && link.recording == false && ageS != null && ageS <= 4 ->
            Color(0xFF1E88E5) to "Viewer mode — can't record yet"
        link.recording == null -> Color.Gray to (if (link.phase == OsmoLink.Phase.READY) "Waiting for camera status…" else "Not connected (needs WiFi)")
        ageS == null || ageS > 4 -> Color(0xFFE0A000) to "No status from camera"
        link.recording == true -> Color(0xFFE53935) to "REC  %d:%02d".format(link.recordTimeS / 60, link.recordTimeS % 60)
        else -> Color(0xFF43A047) to "Standby — not recording"
    }
    Card(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(28.dp).clip(CircleShape).background(light))
                Text(label, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(start = 12.dp))
            }
            ageS?.let { Text("updated %.1f s ago".format(it), style = MaterialTheme.typography.bodySmall) }
            val fmt = remember { SimpleDateFormat("HH:mm:ss", Locale.US) }
            OutlinedButton(
                onClick = { link.runRecordLoopTest(10) },
                enabled = link.phase == OsmoLink.Phase.READY && link.recording == false && !link.testRunning,
            ) { Text(if (link.testRunning) "Testing…" else "Test: record 10 s → refresh") }
            link.testMarks.forEach { m ->
                Text("+%5.1f s  %s".format(m.tS, m.what), fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 13.sp)
            }
            link.recordEvents.take(4).forEach { e ->
                Text((if (e.started) "● started " else "■ stopped ") + fmt.format(Date(e.wallMs)) +
                    (if (!e.started) " (%d:%02d)".format(e.recordTimeS / 60, e.recordTimeS % 60) else ""),
                    style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
