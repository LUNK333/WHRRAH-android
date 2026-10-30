package com.lunk333.whrrah.ui

import android.net.Uri
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.lunk333.whrrah.osmo.OsmoLink
import com.lunk333.whrrah.session.Lap
import com.lunk333.whrrah.session.SessionController
import dev.konraditurbe.osmosis.core.CameraFile
import dev.konraditurbe.osmosis.core.previewCandidates
import kotlinx.coroutines.delay

/**
 * View runs: joins the camera's WiFi while open and streams each run's clip off the camera —
 * DJI's low-res .LRF proxy first (osmosis's preview approach: the platform player range-reads it
 * over the camera network, no download), falling back to the full-res file. Only clips matched to
 * this session's runs are listed. With a synced run the lap time runs over the video and you can
 * jump to the start / finish line.
 */
@Composable
fun RunViewerScreen(modifier: Modifier) {
    val context = LocalContext.current
    val ctl = remember { SessionController.get(context) }
    val link = remember { OsmoLink.get(context) }
    val s = ctl.session ?: return Text("No session open.", modifier.padding(16.dp))

    DisposableEffect(Unit) {
        ctl.startViewing()
        onDispose { ctl.stopViewing() }
    }

    val ready = link.phase == OsmoLink.Phase.READY
    // A run is viewable once it has a clip and that clip is in the camera's list.
    val runs = s.laps.filter { lap -> lap.clip != null && link.files.any { it.name == lap.clip } }.reversed()
    var selected by remember { mutableStateOf<Int?>(null) }
    val lap = s.laps.firstOrNull { it.n == selected }
    val file = lap?.clip?.let { c -> link.files.firstOrNull { it.name == c } }

    LazyColumn(modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Column(Modifier.padding(top = 8.dp)) {
                Text(s.event, style = MaterialTheme.typography.titleLarge)
                if (!ready) {
                    Text(if (link.phase == OsmoLink.Phase.FAILED) "Camera WiFi: ${link.status}" else "Connecting to the camera's WiFi… ${link.status}",
                        style = MaterialTheme.typography.bodySmall)
                    if (link.phase != OsmoLink.Phase.FAILED) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
                }
            }
        }
        if (lap != null && file != null) item(key = "player-${lap.n}") { RunPlayer(lap, file) }
        if (ready && runs.isEmpty()) item {
            Text(if (s.laps.none { it.expectsClip }) "No runs in this session were recorded with the camera."
                else "None of this session's runs have a clip on the camera yet.", style = MaterialTheme.typography.bodyMedium)
        }
        items(runs, key = { it.n }) { r ->
            Card(
                Modifier.fillMaxWidth().clickable { selected = r.n },
                colors = if (r.n == selected) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer) else CardDefaults.cardColors(),
            ) {
                Row(Modifier.padding(12.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column {
                        Text("Run ${r.n}" + if (r.lapTimeMs != null && r.lapTimeMs == s.bestLapMs) "  · best" else "", style = MaterialTheme.typography.titleMedium)
                        Text(r.clip!!.substringBeforeLast('.') + (r.clipDurationS?.let { " · ${it}s" } ?: "") +
                            (if (r.videoStartMs != null) " · synced" else ""), style = MaterialTheme.typography.bodySmall)
                    }
                    Text(fmtLap(r.lapTimeMs), style = MaterialTheme.typography.titleLarge)
                }
            }
        }
    }
}

/** Streams [file] (proxy first) and overlays the run's lap time when the run is synced. */
@Composable
private fun RunPlayer(lap: Lap, file: CameraFile) {
    val candidates = remember(file.path) { file.previewCandidates().map { "http://${OsmoLink.IP}$it" } }
    var idx by remember(file.path) { mutableStateOf(0) }
    var status by remember(file.path) { mutableStateOf("Loading ${if (candidates.size > 1) "preview" else "clip"}…") }
    var posMs by remember(file.path) { mutableStateOf(0) }
    var view by remember { mutableStateOf<VideoView?>(null) }

    // Data clock time of a video position, and the line crossings on the same clock.
    val synced = lap.videoStartMs != null && lap.startCrossMs != null && lap.finishCrossMs != null
    fun seekToData(dataMs: Long) { view?.seekTo((dataMs - lap.videoStartMs!!).toInt().coerceAtLeast(0)) }

    LaunchedEffect(file.path) { while (true) { view?.let { if (it.isPlaying || posMs != it.currentPosition) posMs = it.currentPosition }; delay(40) } }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black)) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    VideoView(ctx).also { v ->
                        view = v
                        v.setMediaController(MediaController(ctx).also { it.setAnchorView(v) })
                    }
                },
                update = { v ->
                    val url = candidates.getOrNull(idx) ?: return@AndroidView
                    if (v.tag != url) {
                        v.tag = url
                        v.setOnPreparedListener { mp -> mp.isLooping = false; status = ""; v.start() }
                        v.setOnErrorListener { _, what, extra ->
                            if (idx < candidates.size - 1) { idx++; status = "Preview unavailable — trying the full-res clip…" }
                            else status = "Can't play this clip ($what/$extra)"
                            true
                        }
                        v.setVideoURI(Uri.parse(url))
                    }
                },
            )
            // Lap time over the video, tied to the playback position (synced runs only).
            if (synced) {
                val t = lap.videoStartMs!! + posMs
                val lapMs = lap.finishCrossMs!! - lap.startCrossMs!!
                val elapsed = (t - lap.startCrossMs).coerceIn(0, lapMs)
                Text(
                    fmtLap(elapsed), color = if (t >= lap.finishCrossMs) Color(0xFF7CFC00) else Color.White,
                    fontSize = 28.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).background(Color(0x99000000)).padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
            if (status.isNotEmpty()) Text(status, color = Color.White, modifier = Modifier.align(Alignment.Center).padding(16.dp))
        }
        Text("Run ${lap.n} · ${file.name}" + if (idx == 0 && candidates.size > 1) " · low-res preview" else " · full res",
            style = MaterialTheme.typography.bodySmall)
        if (synced) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // A couple of seconds of run-up before each line.
            OutlinedButton(onClick = { seekToData(lap.startCrossMs!! - 2000) }) { Text("Jump to start") }
            OutlinedButton(onClick = { seekToData(lap.finishCrossMs!! - 2000) }) { Text("Jump to finish") }
        } else Text("Not synced to the data yet — the lap overlay and jumps need a sync (RaceBox + clip gyro).",
            style = MaterialTheme.typography.bodySmall)
    }
}

private fun fmtLap(ms: Long?): String = ms?.let { "%d:%02d.%03d".format(it / 60000, (it / 1000) % 60, it % 1000) } ?: "—"
