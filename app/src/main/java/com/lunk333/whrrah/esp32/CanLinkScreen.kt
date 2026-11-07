package com.lunk333.whrrah.esp32

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
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Dev options → ESP32 CAN logger: connect over BLE, STATUS, PING / clock offset, stream controls, live frames and channels. */
@Composable
fun CanLinkScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val link = remember { CanLink.get(context) }
    var showLog by remember { mutableStateOf(false) }
    var showFrames by remember { mutableStateOf(true) }
    val busy = link.state == CanLink.State.CONNECTING || link.state == CanLink.State.SCANNING

    LazyColumn(modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Card(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Text("${CanProtocol.DEVICE_NAME} · ${link.state.name.lowercase()}", style = MaterialTheme.typography.titleMedium)
                    if (link.status.isNotEmpty()) Text(link.status, style = MaterialTheme.typography.bodyMedium)
                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (link.state == CanLink.State.SCANNING) OutlinedButton(onClick = link::stopScan) { Text("Stop scan") }
                else Button(onClick = link::startScan, enabled = link.state != CanLink.State.CONNECTING) { Text("Scan") }
                val last = link.lastDevice
                if (last != null && link.state in setOf(CanLink.State.IDLE, CanLink.State.FAILED))
                    Button(onClick = { link.connect(last.address, last.name) }) { Text("Reconnect") }
                if (link.state == CanLink.State.CONNECTED || link.state == CanLink.State.CONNECTING)
                    OutlinedButton(onClick = link::disconnect) { Text("Disconnect") }
            }
        }
        if (link.state == CanLink.State.SCANNING || (link.state == CanLink.State.IDLE && link.found.isNotEmpty())) {
            if (link.found.isEmpty()) item { Text("Looking… make sure the ESP32 is powered and no other app is connected to it.") }
            items(link.found, key = { it.address }) { f ->
                Card(Modifier.fillMaxWidth().clickable { link.connect(f.address, f.name) }) {
                    Row(Modifier.padding(12.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(f.name); Text("${f.rssi} dBm · tap to connect", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        if (link.state == CanLink.State.CONNECTED) {
            item { DeviceCard(link) }
            item { SyncCard(link) }
            item { StreamCard(link) }
            item { TrafficCard(link) }
            if (link.channels.isNotEmpty()) item { ChannelsCard(link) }
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Raw CAN IDs (${link.frames.size})", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = { showFrames = !showFrames }) { Text(if (showFrames) "Hide" else "Show") }
                }
            }
            if (showFrames) {
                if (link.frames.isEmpty()) item { Text("No raw frames yet (raw stream off, filtered out, or the bus is quiet).", style = MaterialTheme.typography.bodySmall) }
                item { Mono("  ID      n        Hz   data") }
                items(link.frames, key = { it.id + if (it.extended) (1L shl 32) else 0 }) { f ->
                    Mono(
                        (if (f.extended) "%08X".format(f.id) else "     %03X".format(f.id)) +
                            " %6d %7.1f   %s".format(f.count, f.rateHz, CanLink.hex(f.data, " ")),
                        color = if (f.changed) MaterialTheme.colorScheme.primary else Color.Unspecified,
                    )
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                    if (link.logging) OutlinedButton(onClick = link::stopLogging) { Text("Stop CSV log") }
                    else Button(onClick = link::startLogging) { Text("Log to CSV") }
                    OutlinedButton(onClick = link::resetStats) { Text("Reset counters") }
                }
            }
            link.logFile?.let { item { Text("Log: Android/$it", style = MaterialTheme.typography.bodySmall) } }
        }

        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Log", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))
                TextButton(onClick = { showLog = !showLog }) { Text(if (showLog) "Hide" else "Show") }
            }
        }
        if (showLog) items(link.log.asReversed()) { line ->
            Text(line, fontFamily = FontFamily.Monospace, fontSize = 10.sp, lineHeight = 12.sp)
        }
    }
}

@Composable
private fun DeviceCard(link: CanLink) {
    val st = link.espStatus
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Device", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = link.autoStatus, onCheckedChange = { link.autoStatus = it })
                    Text("auto", style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = link::requestStatus) { Text("Status") }
            }
            Mono(link.info ?: "INFO: —")
            Mono("phone MTU ${link.mtu}" + (st?.let { " · ESP32 MTU ${it.mtu}" } ?: ""))
            if (st == null) { Mono("STATUS: waiting…"); return@Column }
            val busColor = when (st.twaiState) { 1 -> Color(0xFF43A047); 3 -> Color(0xFFE0A000); else -> Color(0xFFE53935) }
            Text("CAN ${st.twaiName}" + (if (st.fake) " · FAKE frames" else "") + (if (st.proto != CanProtocol.VERSION) " · proto ${st.proto}!" else ""),
                color = busColor, style = MaterialTheme.typography.titleMedium)
            Mono("can rx      ${st.canRx}")
            Mono("can lost    ${st.canLost}")
            Mono("ble dropped ${st.bleDropped}")
            Mono("uptime      %.1f s".format(st.espUs / 1e6))
        }
    }
}

@Composable
private fun SyncCard(link: CanLink) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Time sync (PING)", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                TextButton(onClick = { link.ping() }) { Text("Ping") }
                TextButton(onClick = { link.ping(20) }) { Text("×20") }
            }
            Mono("pings       ${link.pings}")
            Mono("last rtt    " + (link.lastRttMs?.let { "%.1f ms".format(it) } ?: "—"))
            Mono("best rtt    " + (link.rttMs?.let { "%.1f ms".format(it) } ?: "—"))
            Mono("offset      " + (link.offsetUs?.let { "%+.3f s  (ESP32 − phone)".format(it / 1e6) } ?: "—"))
            Mono("latency     " + (link.latencyMs?.let { "%.1f ms  (record → phone)".format(it) } ?: "—"))
        }
    }
}

@Composable
private fun StreamCard(link: CanLink) {
    val flags = link.streamFlags ?: (CanProtocol.STREAM_CHANNELS or CanProtocol.STREAM_RAW)
    var filter by remember { mutableStateOf("") }
    var filterError by remember { mutableStateOf<String?>(null) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Stream", style = MaterialTheme.typography.titleSmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = flags and CanProtocol.STREAM_CHANNELS != 0,
                    onCheckedChange = { link.setStream(flags xor CanProtocol.STREAM_CHANNELS) })
                Text("Channels", Modifier.padding(end = 12.dp))
                Checkbox(checked = flags and CanProtocol.STREAM_RAW != 0,
                    onCheckedChange = { link.setStream(flags xor CanProtocol.STREAM_RAW) })
                Text("Raw CAN")
            }
            OutlinedTextField(
                value = filter, onValueChange = { filter = it; filterError = null }, singleLine = true,
                label = { Text("Raw ID filter (hex, e.g. 1F0, 316)") }, isError = filterError != null,
                supportingText = { Text(filterError ?: (link.filterCount?.let { if (it == 0) "Passing all IDs" else "Filtering to $it IDs" } ?: "Empty = all IDs")) },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    val ids = CanProtocol.parseIdList(filter)
                    filterError = when {
                        ids == null -> "11-bit hex IDs (000–7FF), separated by commas or spaces"
                        ids.size > CanProtocol.MAX_FILTER_IDS -> "At most ${CanProtocol.MAX_FILTER_IDS} IDs"
                        else -> { link.setFilter(ids); null }
                    }
                }) { Text("Apply filter") }
                TextButton(onClick = { filter = ""; filterError = null; link.setFilter(emptyList()) }) { Text("All IDs") }
            }
        }
    }
}

@Composable
private fun TrafficCard(link: CanLink) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("DATA", style = MaterialTheme.typography.titleSmall)
            Mono("packets     ${link.packets}  (%.1f /s, %.1f kbit/s)".format(link.packetRateHz, link.kbps))
            Mono("records     %.0f /s".format(link.recordRateHz))
            Mono("lost (seq)  ${link.lostPackets}", color = if (link.lostPackets > 0) Color(0xFFE0A000) else Color.Unspecified)
            Mono("malformed   ${link.badPackets}", color = if (link.badPackets > 0) Color(0xFFE53935) else Color.Unspecified)
        }
    }
}

@Composable
private fun ChannelsCard(link: CanLink) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Channels", style = MaterialTheme.typography.titleSmall)
            HorizontalDivider()
            for (c in link.channels) {
                val i = CanProtocol.channelInfo(c.channel)
                Mono("%-17s %10s %s".format(i.name, "%.${i.decimals}f".format(c.value), i.unit))
            }
        }
    }
}

@Composable
private fun Mono(s: String, color: Color = Color.Unspecified) =
    Text(s, fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 15.sp, color = color)
