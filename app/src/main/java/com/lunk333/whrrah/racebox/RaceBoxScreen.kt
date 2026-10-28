package com.lunk333.whrrah.racebox

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Dev options → RaceBox: connect, see the live 25 Hz data, log it to CSV. */
@Composable
fun RaceBoxScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val rb = remember { RaceBox.get(context) }
    var showLog by remember { mutableStateOf(false) }
    val busy = rb.state == RaceBox.State.CONNECTING || rb.state == RaceBox.State.SCANNING

    LazyColumn(modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Card(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Text("RaceBox · ${rb.state.name.lowercase()}", style = MaterialTheme.typography.titleMedium)
                    if (rb.status.isNotEmpty()) Text(rb.status, style = MaterialTheme.typography.bodyMedium)
                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (rb.state == RaceBox.State.SCANNING) OutlinedButton(onClick = rb::stopScan) { Text("Stop scan") }
                else Button(onClick = rb::startScan, enabled = rb.state != RaceBox.State.CONNECTING) { Text("Scan") }
                val last = rb.lastDevice
                if (last != null && rb.state in setOf(RaceBox.State.IDLE, RaceBox.State.FAILED))
                    Button(onClick = { rb.connect(last.address, last.name) }) { Text("Reconnect") }
                if (rb.state == RaceBox.State.CONNECTED || rb.state == RaceBox.State.CONNECTING)
                    OutlinedButton(onClick = rb::disconnect) { Text("Disconnect") }
            }
        }
        if (rb.state == RaceBox.State.SCANNING || (rb.state == RaceBox.State.IDLE && rb.found.isNotEmpty())) {
            if (rb.found.isEmpty()) item { Text("Looking… make sure the RaceBox is on and no other app is connected to it.") }
            items(rb.found, key = { it.address }) { f ->
                Card(Modifier.fillMaxWidth().clickable { rb.connect(f.address, f.name) }) {
                    Row(Modifier.padding(12.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(f.name); Text("${f.rssi} dBm · tap to connect", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        if (rb.state == RaceBox.State.CONNECTED) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Device", style = MaterialTheme.typography.titleSmall)
                        Text("${rb.model ?: "?"} · serial ${rb.serial ?: "?"} · fw ${rb.firmware ?: "?"} · MTU ${rb.mtu}",
                            style = MaterialTheme.typography.bodySmall)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("GNSS: ${rb.gnssConfig ?: "—"}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                            TextButton(onClick = rb::queryGnssConfig) { Text("Query") }
                        }
                    }
                }
            }
            item { LiveCard(rb) }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (rb.logging) OutlinedButton(onClick = rb::stopLogging) { Text("Stop CSV log") }
                    else Button(onClick = rb::startLogging) { Text("Log to CSV") }
                }
            }
            rb.logFile?.let { item { Text("Log: Android/$it", style = MaterialTheme.typography.bodySmall) } }
        }

        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Log", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))
                TextButton(onClick = { showLog = !showLog }) { Text(if (showLog) "Hide" else "Show") }
            }
        }
        if (showLog) items(rb.log.asReversed()) { line ->
            Text(line, fontFamily = FontFamily.Monospace, fontSize = 10.sp, lineHeight = 12.sp)
        }
    }
}

@Composable
private fun LiveCard(rb: RaceBox) {
    val d = rb.latest
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Live", style = MaterialTheme.typography.titleSmall)
            if (d == null) { Text("Waiting for data…"); return@Column }
            val fixColor = when {
                d.goodFix -> Color(0xFF43A047)
                d.fixType >= 2 -> Color(0xFFE0A000)
                else -> Color(0xFFE53935)
            }
            Text(
                when (d.fixType) { 3 -> "3D fix"; 2 -> "2D fix"; else -> "No fix" } + (if (d.fixOk) "" else " (not OK)") +
                    " · ${d.satellites} sats · ±%.2f m · PDOP %.1f".format(d.hAccM, d.pdop),
                color = fixColor, style = MaterialTheme.typography.titleMedium,
            )
            Text("%.1f km/h  (%.1f mph)".format(d.speedMps * 3.6, d.speedMps * 2.23694), style = MaterialTheme.typography.headlineMedium)
            Mono("pos     %.7f, %.7f".format(d.lat, d.lon))
            Mono("heading %6.1f°  (±%.1f°)%s".format(d.headingDeg, d.headingAccDeg, if (d.headingValid) "" else " invalid"))
            Mono("alt     %.1f m MSL · ±%.2f m".format(d.altMslM, d.vAccM))
            val utc = remember { SimpleDateFormat("HH:mm:ss.SSS", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") } }
            Mono("utc     ${utc.format(Date(d.utcMs))}${if (d.timeValid) "" else " (not valid)"} · iTOW ${d.iTowMs}")
            HorizontalDivider()
            Mono("g       x %+.3f  y %+.3f  z %+.3f".format(d.gX, d.gY, d.gZ))
            Mono("°/s     roll %+7.2f  pitch %+7.2f  yaw %+7.2f".format(d.rollRate, d.pitchRate, d.yawRate))
            HorizontalDivider()
            Mono("rate    %.1f Hz · %d packets · %d gaps · %d checksum errors".format(rb.rateHz, rb.packets, rb.gaps, rb.checksumErrors))
            Mono("battery " + if (rb.model?.contains("Micro") == true) "%.1f V".format(d.rawBattery / 10.0)
                else "${d.batteryPercent}%" + if (d.charging) " (charging)" else "")
        }
    }
}

@Composable
private fun Mono(s: String) = Text(s, fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 15.sp)
