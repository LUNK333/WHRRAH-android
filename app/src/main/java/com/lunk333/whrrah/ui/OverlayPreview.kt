package com.lunk333.whrrah.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.lunk333.whrrah.overlay.LayoutStore
import com.lunk333.whrrah.overlay.OverlayRenderer
import com.lunk333.whrrah.overlay.RunData
import com.lunk333.whrrah.session.SessionController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Dev: a layout drawn over a plain green frame (the desktop editor's green screen) with a saved run's
 * data and a time slider — checks the widgets without the camera or a clip.
 */
@Composable
fun OverlayPreviewScreen(modifier: Modifier) {
    val context = LocalContext.current
    val ctl = remember { SessionController.get(context) }
    val layouts = remember { LayoutStore(context) }
    val files = remember { layouts.list() }
    // Every run with GPS, newest session first.
    val runs = remember {
        ctl.store.list().sortedByDescending { it.createdMs }.flatMap { s -> s.laps.filter { it.gpsFixes > 0 }.map { s to it } }
    }
    var fileIdx by remember { mutableStateOf(0) }
    var runIdx by remember { mutableStateOf(runs.indexOfFirst { it.second.startCrossMs != null }.coerceAtLeast(0)) }
    val layout = remember(fileIdx) { files.getOrNull(fileIdx)?.let { layouts.load(it).getOrNull() } }
    val run = runs.getOrNull(runIdx)
    val data by produceState<RunData?>(null, runIdx) {
        value = run?.let { (s, lap) ->
            withContext(Dispatchers.IO) {
                RunData.load(lap.gpsFile?.let { File(ctl.store.gpsDir(s.id), it) }, lap.imuFile?.let { File(ctl.store.imuDir(s.id), it) },
                    lap.startCrossMs, lap.finishCrossMs, lap.n, s.laps.filter { it.n < lap.n }.mapNotNull { it.lapTimeMs })
            }
        }
    }
    var frac by remember { mutableFloatStateOf(0.5f) }
    val renderer = remember { OverlayRenderer() }
    var pickLayout by remember { mutableStateOf(false) }
    var pickRun by remember { mutableStateOf(false) }

    Column(modifier.padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box {
            OutlinedButton(onClick = { pickLayout = true }) { Text("Layout: " + (files.getOrNull(fileIdx)?.name ?: "none")) }
            DropdownMenu(pickLayout, { pickLayout = false }) {
                files.forEachIndexed { i, f -> DropdownMenuItem(text = { Text(f.name) }, onClick = { fileIdx = i; pickLayout = false }) }
            }
        }
        Box {
            OutlinedButton(onClick = { pickRun = true }) { Text(run?.let { "${it.first.event} · run ${it.second.n}" } ?: "No runs with GPS") }
            DropdownMenu(pickRun, { pickRun = false }) {
                runs.forEachIndexed { i, (s, l) -> DropdownMenuItem(text = { Text("${s.event} · run ${l.n}") }, onClick = { runIdx = i; pickRun = false }) }
            }
        }
        val d = data
        if (layout == null) { Text("No layout"); return@Column }
        val crop = layout.cropRect()
        val ts = d?.timestamps
        val t = if (ts != null && ts.isNotEmpty()) ts.first() + (ts.last() - ts.first()) * frac else 0.0
        Box(Modifier.fillMaxWidth().aspectRatio(crop[2].toFloat() / crop[3]).background(Color(0xFF00B400))) {
            Canvas(Modifier.fillMaxSize()) {
                val k = size.width / crop[2]
                drawIntoCanvas { c ->
                    val nc = c.nativeCanvas
                    nc.save(); nc.scale(k, k); nc.translate(-crop[0].toFloat(), -crop[1].toFloat())
                    renderer.draw(nc, layout, d, t)
                    nc.restore()
                }
            }
        }
        Slider(value = frac, onValueChange = { frac = it })
        if (d != null && ts != null && ts.isNotEmpty()) Text(
            "t = %.2f s into the log · lap %d · speed %.1f mph · lon %.2f G · lat %.2f G".format(
                t - ts.first(), d.lapNumberAt(t), d.valueAt(RunData.SPEED, t), d.valueAt(RunData.LON_ACC, t), d.valueAt(RunData.LAT_ACC, t)),
            style = MaterialTheme.typography.bodySmall)
        Text("Layouts folder: ${layouts.dir.path}", style = MaterialTheme.typography.bodySmall)
    }
}
