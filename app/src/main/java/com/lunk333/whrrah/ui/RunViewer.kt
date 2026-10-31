package com.lunk333.whrrah.ui

import android.net.Uri
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.VideoView
import androidx.compose.foundation.Canvas
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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.TextButton
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.foundation.combinedClickable
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.lunk333.whrrah.osmo.OsmoLink
import com.lunk333.whrrah.overlay.LayoutStore
import com.lunk333.whrrah.overlay.OverlayLayout
import com.lunk333.whrrah.overlay.OverlayRenderer
import com.lunk333.whrrah.overlay.RunData
import com.lunk333.whrrah.session.Lap
import com.lunk333.whrrah.session.Session
import com.lunk333.whrrah.session.SessionController
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import dev.konraditurbe.osmosis.core.CameraFile
import dev.konraditurbe.osmosis.core.previewCandidates
import kotlinx.coroutines.delay

/**
 * View runs: joins the camera's WiFi while open and streams each run's clip off the camera —
 * DJI's low-res .LRF proxy first (osmosis's preview approach: the platform player range-reads it
 * over the camera network, no download), falling back to the full-res file. Only clips matched to
 * this session's runs are listed. With a synced run the lap time runs over the video and you can
 * jump to the start / finish line.
 *
 * The overlay draws a desktop WHRRAH layout (from the app's layouts folder, see [LayoutStore]) over
 * the video, framed the way the desktop export crops it, with the run's GPS / RaceBox data.
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
    // Previous / next = the neighbouring viewable runs by number.
    val byNumber = runs.map { it.n }.sorted()
    val prevRun = selected?.let { n -> byNumber.lastOrNull { it < n } }
    val nextRun = selected?.let { n -> byNumber.firstOrNull { it > n } }

    val layouts = remember { LayoutStore(context) }
    val layoutFiles = remember { layouts.list() }
    var layoutName by remember { mutableStateOf(layouts.selected?.takeIf { n -> layoutFiles.any { it.name == n } } ?: layoutFiles.firstOrNull()?.name) }
    var showOverlay by remember { mutableStateOf(layouts.showOverlay) }
    var padStart by remember { mutableStateOf(layouts.padStartS) }
    var padEnd by remember { mutableStateOf(layouts.padEndS) }
    val layoutResult = remember(layoutName) { layoutFiles.firstOrNull { it.name == layoutName }?.let(layouts::load) }
    val layout = layoutResult?.getOrNull()

    var fullscreen by remember { mutableStateOf(false) }
    // Long-press a run → render / export it to the gallery.
    var exportAsk by remember { mutableStateOf<Int?>(null) }
    exportAsk?.let { n ->
        val r = s.laps.firstOrNull { it.n == n }
        val f = r?.clip?.let { c -> link.files.firstOrNull { it.name == c } }
        if (r == null || f == null) exportAsk = null
        else ExportDialog(r, f, s, ctl, if (layout != null) layout else null, showOverlay, padStart, padEnd) { exportAsk = null }
    }
    // Swipe a downloaded run left → confirm → delete the phone's copy.
    var deleteAsk by remember { mutableStateOf<Int?>(null) }
    deleteAsk?.let { n ->
        val clip = s.laps.firstOrNull { it.n == n }?.clip
        val mb = clip?.let { ctl.clipFile(s, it).length() / 1_000_000 } ?: 0
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { deleteAsk = null },
            title = { Text("Delete run $n's clip from the phone?") },
            text = { Text("Removes the downloaded copy ($mb MB). The clip stays on the camera and can be downloaded again.") },
            confirmButton = { TextButton(onClick = { ctl.deleteClipCopy(n); deleteAsk = null }) { Text("Delete", color = Color(0xFFE53935)) } },
            dismissButton = { TextButton(onClick = { deleteAsk = null }) { Text("Cancel") } },
        )
    }
    var settingsOpen by remember { mutableStateOf(false) }
    if (file == null && fullscreen) fullscreen = false
    FullscreenEffect(fullscreen)
    androidx.activity.compose.BackHandler(enabled = fullscreen) { fullscreen = false }

    Column(modifier) {
        // The player sits above the list (and fills the screen in fullscreen) — always in this slot, so
        // switching runs or going fullscreen keeps the same video view.
        if (lap != null && file != null) RunPlayer(
            lap, file.name, file, s, ctl, if (showOverlay) layout else null, fullscreen, padStart, padEnd,
            onFullscreen = { fullscreen = !fullscreen }, onSettings = { settingsOpen = true },
            onPrev = prevRun?.let { { selected = it } }, onNext = nextRun?.let { { selected = it } },
        )
        if (!fullscreen) LazyColumn(Modifier.weight(1f).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
            if (ready && runs.isEmpty()) item {
                Text(if (s.laps.none { it.expectsClip }) "No runs in this session were recorded with the camera."
                    else "None of this session's runs have a clip on the camera yet.", style = MaterialTheme.typography.bodyMedium)
            }
            items(runs, key = { it.n }) { r ->
                val onPhone = remember(r.clip, ctl.downloadsDone) { ctl.clipFile(s, r.clip!!).isFile }
                RunRow(r, best = r.lapTimeMs != null && r.lapTimeMs == s.bestLapMs, selected = r.n == selected,
                    thumb = rememberThumbnail(link, link.files.firstOrNull { it.name == r.clip }, File(ctl.store.dir(s.id), "video/thumbs")),
                    onPhone = onPhone, progress = ctl.downloads[r.clip],
                    onClick = { selected = r.n }, onDownload = { ctl.downloadClip(r.n) }, onDelete = { deleteAsk = r.n },
                    onLongPress = { exportAsk = r.n })
            }
            if (ctl.activity.isNotEmpty()) item { Text(ctl.activity, style = MaterialTheme.typography.bodySmall) }
            item { Spacer(Modifier.height(8.dp)) }
        }
    }

    if (settingsOpen) ViewerSettingsSheet(layouts, layoutFiles.map { it.name }, layoutName, showOverlay, layoutResult?.exceptionOrNull()?.message,
        padStart, padEnd, onShow = { showOverlay = it; layouts.showOverlay = it }, onLayout = { layoutName = it; layouts.selected = it },
        onPadStart = { padStart = it; layouts.padStartS = it }, onPadEnd = { padEnd = it; layouts.padEndS = it }) { settingsOpen = false }
}

/** The player's settings sheet: overlay on/off + layout, and the pad start / end around the lap. */
@Composable
private fun ViewerSettingsSheet(layouts: LayoutStore, names: List<String>, layoutName: String?, showOverlay: Boolean, error: String?,
                                padStart: Int, padEnd: Int, onShow: (Boolean) -> Unit, onLayout: (String) -> Unit,
                                onPadStart: (Int) -> Unit, onPadEnd: (Int) -> Unit, onDismiss: () -> Unit) {
    @OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 32.dp)) {
            Text("Video settings", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 8.dp))
            OverlayControls(names, layoutName, showOverlay, error, onShow = onShow, onLayout = onLayout, folder = layouts.dir.path)
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            PadSetting("Pad start", "before the start line", padStart, onPadStart)
            PadSetting("Pad end", "after the finish line", padEnd, onPadEnd)
        }
    }
}

/**
 * Downloaded runs, from every session: the same player, overlay, settings, export (long press) and
 * delete-from-phone (swipe left) as View runs, but from the phone's copies — no camera needed.
 */
@Composable
fun BrowseDownloadsScreen(modifier: Modifier) {
    val context = LocalContext.current
    val ctl = remember { SessionController.get(context) }
    // (session, run) for every run whose clip is on the phone, newest session first.
    var reload by remember { mutableStateOf(0) }
    val entries by produceState(emptyList<Pair<Session, Lap>>(), reload, ctl.downloadsDone) {
        value = withContext(Dispatchers.IO) {
            ctl.store.list().sortedByDescending { it.createdMs }.flatMap { s ->
                s.laps.filter { it.clip != null && ctl.clipFile(s, it.clip).isFile }.sortedByDescending { it.n }.map { s to it }
            }
        }
    }
    var selected by remember { mutableStateOf<String?>(null) }
    fun key(s: Session, l: Lap) = "${s.id}/${l.n}"
    val cur = entries.firstOrNull { (s, l) -> key(s, l) == selected }
    val idx = entries.indexOfFirst { (s, l) -> key(s, l) == selected }

    val layouts = remember { LayoutStore(context) }
    val layoutFiles = remember { layouts.list() }
    var layoutName by remember { mutableStateOf(layouts.selected?.takeIf { n -> layoutFiles.any { it.name == n } } ?: layoutFiles.firstOrNull()?.name) }
    var showOverlay by remember { mutableStateOf(layouts.showOverlay) }
    var padStart by remember { mutableStateOf(layouts.padStartS) }
    var padEnd by remember { mutableStateOf(layouts.padEndS) }
    val layoutResult = remember(layoutName) { layoutFiles.firstOrNull { it.name == layoutName }?.let(layouts::load) }
    val layout = layoutResult?.getOrNull()

    var fullscreen by remember { mutableStateOf(false) }
    var settingsOpen by remember { mutableStateOf(false) }
    var exportAsk by remember { mutableStateOf<Pair<Session, Lap>?>(null) }
    var deleteAsk by remember { mutableStateOf<Pair<Session, Lap>?>(null) }
    exportAsk?.let { (s, l) -> ExportDialog(l, null, s, ctl, layout, showOverlay, padStart, padEnd) { exportAsk = null } }
    deleteAsk?.let { (s, l) ->
        val mb = ctl.clipFile(s, l.clip!!).length() / 1_000_000
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { deleteAsk = null },
            title = { Text("Delete ${s.event} run ${l.n}'s clip from the phone?") },
            text = { Text("Removes the downloaded copy ($mb MB). If it's still on the camera it can be downloaded again from View runs.") },
            confirmButton = { TextButton(onClick = {
                if (selected == key(s, l)) selected = null
                ctl.deleteClipCopy(l.n, s); deleteAsk = null; reload++
            }) { Text("Delete", color = Color(0xFFE53935)) } },
            dismissButton = { TextButton(onClick = { deleteAsk = null }) { Text("Cancel") } },
        )
    }
    if (cur == null && fullscreen) fullscreen = false
    FullscreenEffect(fullscreen)
    androidx.activity.compose.BackHandler(enabled = fullscreen) { fullscreen = false }

    Column(modifier) {
        if (cur != null) {
            val (s, l) = cur
            // Previous / next = the neighbouring entries in the list (older is further down).
            RunPlayer(l, l.clip!!, null, s, ctl, if (showOverlay) layout else null, fullscreen, padStart, padEnd,
                onFullscreen = { fullscreen = !fullscreen }, onSettings = { settingsOpen = true },
                onPrev = entries.getOrNull(idx + 1)?.let { (ps, pl) -> { selected = key(ps, pl) } },
                onNext = entries.getOrNull(idx - 1)?.let { (ns, nl) -> { selected = key(ns, nl) } })
        }
        if (!fullscreen) LazyColumn(Modifier.weight(1f).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { Spacer(Modifier.height(4.dp)) }
            if (entries.isEmpty()) item {
                Text("No downloaded runs yet. In a session's View runs, swipe a run right to download its clip.",
                    style = MaterialTheme.typography.bodyMedium)
            }
            items(entries, key = { (s, l) -> key(s, l) }) { (s, l) ->
                RunRow(l, best = l.lapTimeMs != null && l.lapTimeMs == s.bestLapMs, selected = key(s, l) == selected,
                    thumb = rememberLocalThumbnail(ctl.clipFile(s, l.clip!!), File(ctl.store.dir(s.id), "video/thumbs")),
                    onPhone = true, progress = null, title = "${s.event}, Run ${l.n}", allowDownload = false,
                    onClick = { selected = key(s, l) }, onDownload = {}, onDelete = { deleteAsk = s to l },
                    onLongPress = { exportAsk = s to l })
            }
            if (ctl.activity.isNotEmpty()) item { Text(ctl.activity, style = MaterialTheme.typography.bodySmall) }
            item { Spacer(Modifier.height(8.dp)) }
        }
    }
    if (settingsOpen) ViewerSettingsSheet(layouts, layoutFiles.map { it.name }, layoutName, showOverlay, layoutResult?.exceptionOrNull()?.message,
        padStart, padEnd, onShow = { showOverlay = it; layouts.showOverlay = it }, onLayout = { layoutName = it; layouts.selected = it },
        onPadStart = { padStart = it; layouts.padStartS = it }, onPadEnd = { padEnd = it; layouts.padEndS = it }) { settingsOpen = false }
}

/** A downloaded clip's thumbnail: the cached one (from View runs), else its first frame, cached. */
@Composable
private fun rememberLocalThumbnail(clip: File, dir: File): androidx.compose.ui.graphics.ImageBitmap? {
    val cached = File(dir, clip.name.substringBeforeLast('.') + ".jpg")
    val bmp by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, clip.path) {
        value = withContext(THUMB_IO) {
            cached.takeIf { it.isFile }?.let { android.graphics.BitmapFactory.decodeFile(it.path) }?.asImageBitmap()
                ?: runCatching {
                    val r = android.media.MediaMetadataRetriever()
                    try {
                        r.setDataSource(clip.path)
                        r.getScaledFrameAtTime(0, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 480, 270)
                    } finally { r.release() }
                }.getOrNull()?.let { frame ->
                    runCatching { dir.mkdirs(); cached.outputStream().use { frame.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, it) } }
                    frame.asImageBitmap()
                }
        }
    }
    return bmp
}

/**
 * One run: thumbnail · "Run N (best)" with the clip name and length · lap time. Swipe it right to
 * download the clip; a badge on the thumbnail shows it's on the phone (or the download's progress).
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun RunRow(r: Lap, best: Boolean, selected: Boolean, thumb: androidx.compose.ui.graphics.ImageBitmap?,
                   onPhone: Boolean, progress: Float?, onClick: () -> Unit, onDownload: () -> Unit, onDelete: () -> Unit,
                   onLongPress: () -> Unit, title: String = "Run ${r.n}", allowDownload: Boolean = true) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val offset = remember { androidx.compose.animation.core.Animatable(0f) }
    val maxPx = with(androidx.compose.ui.platform.LocalDensity.current) { 110.dp.toPx() }
    Box {
        // Revealed behind the card: sliding right, download (or "on the phone"); sliding left (downloaded
        // clips only), delete the phone's copy.
        if (offset.value >= 0f) Row(Modifier.matchParentSize().clip(RoundedCornerShape(12.dp))
            .background(if (onPhone) Color(0xFF455A64) else Color(0xFF2E7D32)).padding(start = 20.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(if (onPhone) "✓ On the phone" else "⬇ Download", color = Color.White, style = MaterialTheme.typography.titleMedium)
        } else Row(Modifier.matchParentSize().clip(RoundedCornerShape(12.dp)).background(Color(0xFFC62828)).padding(end = 20.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.End) {
            Text("Delete from phone", color = Color.White, style = MaterialTheme.typography.titleMedium)
        }
    Card(
        Modifier.fillMaxWidth()
            .offset { androidx.compose.ui.unit.IntOffset(offset.value.toInt(), 0) }
            .pointerInput(r.n, onPhone, progress == null) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        if (offset.value > maxPx * 0.7f) onDownload()
                        else if (offset.value < -maxPx * 0.7f) onDelete()
                        scope.launch { offset.animateTo(0f) }
                    },
                    onDragCancel = { scope.launch { offset.animateTo(0f) } },
                ) { change, dx ->
                    change.consume()
                    scope.launch { offset.snapTo((offset.value + dx).coerceIn(if (onPhone && progress == null) -maxPx else 0f, if (allowDownload) maxPx else 0f)) }
                }
            }
            .combinedClickable(onClick = onClick, onLongClick = onLongPress),
        colors = if (selected) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer) else CardDefaults.cardColors(),
    ) {
        Row(Modifier.padding(8.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.width(112.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(6.dp)).background(Color.Black),
                contentAlignment = Alignment.Center) {
                if (thumb != null) androidx.compose.foundation.Image(thumb, contentDescription = "Run ${r.n}",
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop, modifier = Modifier.fillMaxSize())
                DownloadBadge(onPhone, progress, Modifier.align(Alignment.BottomEnd).padding(4.dp))
            }
            Column(Modifier.weight(1f)) {
                // Long titles (event, run #) wrap to a second line in a smaller size rather than being cut.
                Text(title + if (best) " (best)" else "", maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    style = if (title.length > 10) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge)
                Text(r.clip!!.substringBeforeLast('.'), style = MaterialTheme.typography.bodySmall, maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                r.clipDurationS?.let { Text("%d:%02d".format(it / 60, it % 60), style = MaterialTheme.typography.bodySmall) }
            }
            Text(fmtLap(r.lapTimeMs), style = MaterialTheme.typography.headlineMedium.copy(fontFeatureSettings = "tnum"))
        }
    }
    }
}

/**
 * Render a run's clip into the gallery: with the overlay (cropped to the layout's export frame) and/or
 * trimmed to the padded window around the lap. Uses the phone's copy when there is one, else reads the
 * camera's file over its WiFi (only the trimmed part).
 */
@Composable
private fun ExportDialog(lap: Lap, remote: CameraFile?, s: Session, ctl: SessionController, layout: OverlayLayout?, overlayDefault: Boolean,
                         padStartS: Int, padEndS: Int, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val exporter = remember { com.lunk333.whrrah.export.ClipExporter.get(context) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val local = remember(lap.clip) { lap.clip?.let { ctl.clipFile(s, it) }?.takeIf { it.isFile } }
    val videoStartMs = lap.videoStartMs ?: lap.clipOffsetMs?.let { lap.recStartWallMs + it }
    val canPad = videoStartMs != null && lap.startCrossMs != null && lap.finishCrossMs != null
    var withOverlay by remember { mutableStateOf(overlayDefault && layout != null && videoStartMs != null) }
    var padded by remember { mutableStateOf(canPad) }
    var started by remember { mutableStateOf(false) }
    LaunchedEffect(exporter.running) { while (exporter.running) { exporter.pollProgress(); delay(250) } }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = { if (!exporter.running) onDismiss() },
        title = { Text("Export run ${lap.n}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (!started) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(withOverlay, { withOverlay = it }, enabled = layout != null && videoStartMs != null)
                        Text("With overlay" + when {
                            layout == null -> " (no layout)"
                            videoStartMs == null -> " (run not matched to its clip)"
                            else -> " — ${layout.aspectRatio} crop"
                        })
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(padded, { padded = it }, enabled = canPad)
                        Text(if (canPad) "Padded: $padStartS s before the start to $padEndS s after the finish" else "Padded (needs line crossings + clip timing)")
                    }
                    Text(if (local != null) "From the phone's copy." else "Reads the clip off the camera over WiFi — stay on this screen.",
                        style = MaterialTheme.typography.bodySmall)
                    if (lap.videoStartMs == null && videoStartMs != null && withOverlay)
                        Text("Not gyro-synced: the overlay timing is approximate.", style = MaterialTheme.typography.bodySmall)
                } else {
                    Text(exporter.status)
                    if (exporter.running) {
                        val p = exporter.progress
                        if (p != null) androidx.compose.material3.LinearProgressIndicator(progress = { p }, Modifier.fillMaxWidth())
                        else LinearProgressIndicator(Modifier.fillMaxWidth())
                        p?.let { Text("%d%%".format((it * 100).toInt()), style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
        },
        confirmButton = {
            when {
                !started -> TextButton(enabled = local != null || remote != null, onClick = {
                    started = true
                    scope.launch {
                        val o = if (withOverlay && layout != null && videoStartMs != null) {
                            val data = withContext(Dispatchers.IO) {
                                runCatching {
                                    RunData.load(lap.gpsFile?.let { File(ctl.store.gpsDir(s.id), it) }, lap.imuFile?.let { File(ctl.store.imuDir(s.id), it) },
                                        lap.startCrossMs, lap.finishCrossMs, lap.n, s.laps.filter { it.n < lap.n }.mapNotNull { it.lapTimeMs })
                                }.getOrNull()
                            }
                            com.lunk333.whrrah.export.ClipExporter.Overlay(layout, data, videoStartMs)
                        } else null
                        val start = if (padded && canPad) (lap.startCrossMs!! - padStartS * 1000L - videoStartMs!!).coerceAtLeast(0) else null
                        val end = if (padded && canPad) (lap.finishCrossMs!! + padEndS * 1000L - videoStartMs!!).coerceAtLeast((start ?: 0) + 1000) else null
                        val src = local?.let { Uri.fromFile(it) }
                            ?: Uri.parse("http://${OsmoLink.IP}" + dev.konraditurbe.osmosis.camera.PathAddressing.original(remote!!))
                        val safe = s.event.replace(Regex("[^A-Za-z0-9_-]+"), "_").trim('_')
                        val lapPart = lap.lapTimeMs?.let { "_%d-%02d-%03d".format(it / 60000, (it / 1000) % 60, it % 1000) } ?: ""
                        val name = "WHRRAH_${safe}_run${lap.n}$lapPart" + (if (o != null) "_overlay" else "") + "_${System.currentTimeMillis() / 1000}.mp4"
                        exporter.start(src, name, start, end, o)
                    }
                }) { Text("Export") }
                exporter.running -> TextButton(onClick = { exporter.cancel() }) { Text("Cancel") }
                else -> TextButton(onClick = onDismiss) { Text("Close") }
            }
        },
        dismissButton = { if (!started) TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** On the thumbnail: a green tick when the clip is on the phone, a progress ring while downloading. */
@Composable
private fun DownloadBadge(onPhone: Boolean, progress: Float?, modifier: Modifier) {
    when {
        progress != null -> Box(modifier.size(22.dp).clip(androidx.compose.foundation.shape.CircleShape).background(Color(0xCC000000)),
            contentAlignment = Alignment.Center) {
            if (progress >= 0f) androidx.compose.material3.CircularProgressIndicator(progress = { progress }, Modifier.size(18.dp),
                color = Color(0xFF66BB6A), strokeWidth = 2.5.dp, trackColor = Color(0x44FFFFFF))
            else androidx.compose.material3.CircularProgressIndicator(Modifier.size(18.dp), color = Color(0xFF66BB6A), strokeWidth = 2.5.dp)
        }
        onPhone -> Box(modifier.size(22.dp).clip(androidx.compose.foundation.shape.CircleShape).background(Color(0xFF2E7D32))) {
            Canvas(Modifier.fillMaxSize().padding(5.dp)) {
                val p = androidx.compose.ui.graphics.Path().apply {
                    moveTo(size.width * 0.05f, size.height * 0.55f); lineTo(size.width * 0.4f, size.height * 0.88f); lineTo(size.width * 0.95f, size.height * 0.15f)
                }
                drawPath(p, Color.White, style = androidx.compose.ui.graphics.drawscope.Stroke(width = size.width * 0.2f,
                    cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
            }
        }
    }
}

/**
 * The clip's thumbnail: from the session's cache (`video/thumbs/`), else fetched off the camera once
 * the WiFi is up and cached. Null until there is one.
 */
@Composable
private fun rememberThumbnail(link: OsmoLink, file: CameraFile?, dir: File): androidx.compose.ui.graphics.ImageBitmap? {
    val ready = link.phase == OsmoLink.Phase.READY
    val name = file?.name ?: return null
    val cached = File(dir, name.substringBeforeLast('.') + ".jpg")
    val bmp by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, name, ready) {
        value = withContext(THUMB_IO) {
            fun decode(b: ByteArray) = android.graphics.BitmapFactory.decodeByteArray(b, 0, b.size)?.asImageBitmap()
            cached.takeIf { it.isFile }?.readBytes()?.let(::decode)
                ?: if (ready) runCatching { link.fetchThumbnail(file) }.getOrNull()?.let { bytes ->
                    decode(bytes)?.also { runCatching { dir.mkdirs(); cached.writeBytes(bytes) } }
                } else null
        } ?: value
    }
    return bmp
}

/** Two thumbnail fetches at a time — the camera's HTTP server is small. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
private val THUMB_IO = Dispatchers.IO.limitedParallelism(2)

/**
 * Fullscreen player: landscape (either way round, by the sensor), no system bars, no app header.
 * Undone when it ends or the screen goes away.
 */
@Composable
private fun FullscreenEffect(on: Boolean) {
    val view = androidx.compose.ui.platform.LocalView.current
    DisposableEffect(on) {
        val activity = view.context as? android.app.Activity
        val bars = activity?.window?.let { androidx.core.view.WindowCompat.getInsetsController(it, view) }
        if (on) {
            activity?.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            bars?.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            bars?.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            UiChrome.immersive = true
        }
        onDispose {
            if (on) {
                activity?.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
                bars?.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
                UiChrome.immersive = false
            }
        }
    }
}

/**
 * Streams [file] (proxy first) under the overlay, with YouTube-style controls: a thin progress line
 * along the bottom always; tap the video for play/pause, previous/next run, a seek bar with the
 * time, fullscreen and settings — they fade after a few seconds of playback.
 */
@Composable
private fun RunPlayer(
    lap: Lap, clip: String, remote: CameraFile?, s: Session, ctl: SessionController, layout: OverlayLayout?, fullscreen: Boolean,
    padStartS: Int, padEndS: Int, onFullscreen: () -> Unit, onSettings: () -> Unit, onPrev: (() -> Unit)?, onNext: (() -> Unit)?,
) {
    // The phone's copy first when the clip has been downloaded (full res, no WiFi needed), then the
    // camera's proxy, then its full-res file.
    val local = remember(clip) { ctl.clipFile(s, clip).takeIf { it.isFile } }
    val candidates = remember(clip) {
        listOfNotNull(local?.let { Uri.fromFile(it).toString() }) + (remote?.previewCandidates() ?: emptyList()).map { "http://${OsmoLink.IP}$it" }
    }
    var idx by remember(clip) { mutableStateOf(0) }
    var status by remember(clip) { mutableStateOf("Loading ${if (candidates.size > 1) "preview" else "clip"}…") }
    var posMs by remember(clip) { mutableStateOf(0) }
    var durMs by remember(clip) { mutableStateOf(0) }
    var playing by remember(clip) { mutableStateOf(false) }
    var view by remember { mutableStateOf<VideoView?>(null) }
    // Video frame size (the proxy is smaller but the same shape); until it's known, assume the layout's.
    var videoW by remember(clip) { mutableStateOf(0) }
    var videoH by remember(clip) { mutableStateOf(0) }
    var controls by remember { mutableStateOf(true) }
    var poke by remember { mutableStateOf(0) }          // bumped by any control use: restarts the fade timer
    var scrubMs by remember(clip) { mutableStateOf<Float?>(null) }
    val renderer = remember { OverlayRenderer() }
    val data by produceState<RunData?>(null, lap.n, s.id) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                RunData.load(lap.gpsFile?.let { File(ctl.store.gpsDir(s.id), it) }, lap.imuFile?.let { File(ctl.store.imuDir(s.id), it) },
                    lap.startCrossMs, lap.finishCrossMs, lap.n, s.laps.filter { it.n < lap.n }.mapNotNull { it.lapTimeMs })
            }.getOrNull()
        }
    }
    // Video t = 0 on the data clock: the gyro sync, else roughly from the clip match (phone clock ~ GNSS).
    val videoStartMs = lap.videoStartMs ?: lap.clipOffsetMs?.let { lap.recStartWallMs + it }

    // Data clock time of a video position, and the line crossings on the same clock.
    val synced = lap.videoStartMs != null && lap.startCrossMs != null && lap.finishCrossMs != null

    // The part of the clip that's shown: pad start before the start line to pad end after the finish
    // (desktop's Pad Start / Pad End), as far as the clip and the timing allow; else the whole clip.
    fun window(dur: Int): Pair<Int, Int> {
        val top = if (dur > 0) dur else Int.MAX_VALUE
        val a = if (videoStartMs != null && lap.startCrossMs != null)
            (lap.startCrossMs - padStartS * 1000L - videoStartMs).coerceIn(0L, top.toLong()).toInt() else 0
        val b = if (videoStartMs != null && lap.finishCrossMs != null)
            (lap.finishCrossMs + padEndS * 1000L - videoStartMs).coerceIn(a.toLong(), top.toLong()).toInt() else top
        return a to b
    }
    val (winStart, winEnd) = window(durMs)
    val winLen = if (winEnd == Int.MAX_VALUE) 0 else winEnd - winStart
    val latestWin by androidx.compose.runtime.rememberUpdatedState(winStart to winEnd)
    val windowFor by androidx.compose.runtime.rememberUpdatedState(::window)

    LaunchedEffect(clip) {
        while (true) {
            view?.let {
                if (it.isPlaying || posMs != it.currentPosition) posMs = it.currentPosition
                if (playing != it.isPlaying) playing = it.isPlaying
                if (durMs <= 0 && it.duration > 0) durMs = it.duration
                // Stop at the end of the window.
                if (it.isPlaying && it.currentPosition >= latestWin.second) { it.pause(); it.seekTo(latestWin.second); controls = true }
            }
            delay(40)
        }
    }
    // Controls fade after 3 s while playing; stay up while paused.
    LaunchedEffect(controls, playing, poke, scrubMs) { if (controls && playing && scrubMs == null) { delay(3000); controls = false } }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // Framing: with an overlay, the layout's export crop of its frame (video letterboxed into the
        // frame, as desktop); without, just the video.
        val vw = if (videoW > 0) videoW else layout?.width ?: 16
        val vh = if (videoH > 0) videoH else layout?.height ?: 9
        val frameW = layout?.width?.toDouble() ?: vw.toDouble()
        val frameH = layout?.height?.toDouble() ?: vh.toDouble()
        val fit = minOf(frameW / vw, frameH / vh)
        val vRect = doubleArrayOf((frameW - vw * fit) / 2, (frameH - vh * fit) / 2, vw * fit, vh * fit)
        val crop = layout?.cropRect()?.map { it.toDouble() } ?: listOf(0.0, 0.0, frameW, frameH)
        Box(if (fullscreen) Modifier.fillMaxSize().background(Color.Black) else Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        BoxWithConstraints(Modifier.aspectRatio((crop[2] / crop[3]).toFloat(), matchHeightConstraintsFirst = fullscreen).clipToBounds().background(Color.Black)) {
            val k = constraints.maxWidth / crop[2]
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    val frame = FrameLayout(ctx).apply { clipChildren = true }
                    VideoView(ctx).also { v ->
                        view = v
                        frame.addView(v, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                    }
                    frame
                },
                update = { frame ->
                    val v = frame.getChildAt(0) as VideoView
                    val lp = v.layoutParams as FrameLayout.LayoutParams
                    val w = (vRect[2] * k).toInt(); val h = (vRect[3] * k).toInt()
                    val l = ((vRect[0] - crop[0]) * k).toInt(); val t = ((vRect[1] - crop[1]) * k).toInt()
                    if (lp.width != w || lp.height != h || lp.leftMargin != l || lp.topMargin != t) {
                        lp.width = w; lp.height = h; lp.leftMargin = l; lp.topMargin = t; lp.rightMargin = -w; lp.bottomMargin = -h
                        v.layoutParams = lp
                    }
                    val url = candidates.getOrNull(idx) ?: return@AndroidView
                    if (v.tag != url) {
                        v.tag = url
                        v.setOnPreparedListener { mp ->
                            mp.isLooping = false; status = ""; videoW = mp.videoWidth; videoH = mp.videoHeight; durMs = mp.duration
                            // Open at the window start (pad start before the start line).
                            val open = windowFor(mp.duration).first
                            if (open > 0) { v.seekTo(open); posMs = open }
                            v.start()
                        }
                        v.setOnCompletionListener { controls = true }
                        v.setOnErrorListener { _, what, extra ->
                            if (idx < candidates.size - 1) { idx++; status = "Preview unavailable — trying the full-res clip…" }
                            else status = "Can't play this clip ($what/$extra)"
                            true
                        }
                        v.setVideoURI(Uri.parse(url))
                    }
                },
            )
            val shownMs = scrubMs?.toInt() ?: posMs
            if (layout != null && videoStartMs != null) Canvas(Modifier.fillMaxSize()) {
                val t = (videoStartMs + shownMs) / 1000.0
                drawIntoCanvas { c ->
                    val nc = c.nativeCanvas
                    nc.save()
                    nc.scale(k.toFloat(), k.toFloat())
                    nc.translate(-crop[0].toFloat(), -crop[1].toFloat())
                    renderer.draw(nc, layout, data, t)
                    nc.restore()
                }
            }
            // Lap time over the video, tied to the playback position (synced runs only).
            if (synced && layout == null) {
                val t = lap.videoStartMs!! + shownMs
                val lapMs = lap.finishCrossMs!! - lap.startCrossMs!!
                val elapsed = (t - lap.startCrossMs).coerceIn(0, lapMs)
                Text(
                    fmtLap(elapsed), color = if (t >= lap.finishCrossMs) Color(0xFF7CFC00) else Color.White,
                    fontSize = 28.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.TopStart).padding(8.dp).background(Color(0x99000000)).padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
            if (status.isNotEmpty()) Text(status, color = Color.White, modifier = Modifier.align(Alignment.Center).padding(16.dp))

            // ---- controls -----------------------------------------------------------------------
            Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures(onTap = { controls = !controls; poke++ }) })
            androidx.compose.animation.AnimatedVisibility(controls, Modifier.fillMaxSize(),
                enter = androidx.compose.animation.fadeIn(), exit = androidx.compose.animation.fadeOut()) {
                Box(Modifier.fillMaxSize().background(Color(0x66000000)).pointerInput(Unit) { detectTapGestures(onTap = { controls = false }) }) {
                    PlayerButton(PlayerIcon.SETTINGS, 26.dp, Modifier.align(Alignment.TopEnd).padding(4.dp)) { onSettings(); poke++ }
                    Row(Modifier.align(Alignment.Center), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(36.dp)) {
                        PlayerButton(PlayerIcon.PREV, 30.dp, enabled = onPrev != null) { onPrev?.invoke(); poke++ }
                        PlayerButton(if (playing) PlayerIcon.PAUSE else PlayerIcon.PLAY, 48.dp) {
                            view?.let {
                                if (it.isPlaying) it.pause()
                                else {
                                    // From the end of the window (or outside it), play it again from its start.
                                    if (it.currentPosition >= winEnd - 100 || it.currentPosition < winStart) it.seekTo(winStart)
                                    it.start()
                                }
                            }; poke++
                        }
                        PlayerButton(PlayerIcon.NEXT, 30.dp, enabled = onNext != null) { onNext?.invoke(); poke++ }
                    }
                    Row(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(start = 12.dp, end = 4.dp, bottom = 14.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text("${clock(shownMs - winStart)} / ${clock(if (winLen > 0) winLen else durMs)}", color = Color.White, style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f))
                        PlayerButton(if (fullscreen) PlayerIcon.EXIT_FULLSCREEN else PlayerIcon.FULLSCREEN, 24.dp) { onFullscreen(); poke++ }
                    }
                }
            }
            SeekBar(
                fraction = if (winLen > 0) (shownMs - winStart).toFloat() / winLen else 0f, expanded = controls,
                modifier = Modifier.align(Alignment.BottomCenter),
                onScrub = { f -> scrubMs = winStart + f * winLen; poke++ },
                onScrubEnd = { scrubMs?.let { view?.seekTo(it.toInt()); posMs = it.toInt() }; scrubMs = null },
            )
        }
        }
        if (!fullscreen) Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Run ${lap.n} · ${clip}" + (when {
                    local != null && idx == 0 -> " · from the phone"
                    idx == (if (local != null) 1 else 0) && candidates.size > (if (local != null) 2 else 1) -> " · low-res preview"
                    else -> " · full res" }) +
                (if (layout != null && lap.videoStartMs == null) if (videoStartMs != null) " · overlay timing approximate (not synced)" else " · no overlay: run isn't matched to its clip" else ""),
                style = MaterialTheme.typography.bodySmall)
            if (!synced) Text("Not synced to the data yet — the lap overlay needs a sync (RaceBox + clip gyro).",
                style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** A whole-seconds setting with − / + (0–120 s, as desktop). */
@Composable
private fun PadSetting(label: String, hint: String, value: Int, onChange: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(hint, style = MaterialTheme.typography.bodySmall)
        }
        OutlinedButton(onClick = { onChange((value - 1).coerceIn(0, 120)) }, enabled = value > 0) { Text("−") }
        Text("$value s", style = MaterialTheme.typography.titleMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.width(64.dp))
        OutlinedButton(onClick = { onChange((value + 1).coerceIn(0, 120)) }, enabled = value < 120) { Text("+") }
    }
}

private fun clock(ms: Int): String { val s = (ms.coerceAtLeast(0)) / 1000; return "%d:%02d".format(s / 60, s % 60) }

/**
 * Progress along the bottom of the video: a thin red line normally; with the controls up, a thicker
 * bar with a thumb you can drag (or tap) to seek.
 */
@Composable
private fun SeekBar(fraction: Float, expanded: Boolean, modifier: Modifier, onScrub: (Float) -> Unit, onScrubEnd: () -> Unit) {
    val red = Color(0xFFFF0033)
    val track = Color(0x55FFFFFF)
    val f = fraction.coerceIn(0f, 1f)
    Box(modifier.fillMaxWidth().height(if (expanded) 28.dp else 3.dp)
        .then(if (expanded) Modifier.pointerInput(Unit) {
            detectTapGestures(onTap = { onScrub(it.x / size.width); onScrubEnd() })
        }.pointerInput(Unit) {
            detectHorizontalDragGestures(
                onDragStart = { onScrub(it.x / size.width) },
                onDragEnd = onScrubEnd, onDragCancel = onScrubEnd,
            ) { change, _ -> change.consume(); onScrub((change.position.x / size.width).coerceIn(0f, 1f)) }
        } else Modifier)) {
        Canvas(Modifier.fillMaxSize()) {
            val h = if (expanded) 4.dp.toPx() else size.height
            val y = size.height - h - (if (expanded) 10.dp.toPx() else 0f)
            drawRect(track, androidx.compose.ui.geometry.Offset(0f, y), androidx.compose.ui.geometry.Size(size.width, h))
            drawRect(red, androidx.compose.ui.geometry.Offset(0f, y), androidx.compose.ui.geometry.Size(size.width * f, h))
            if (expanded) drawCircle(red, 7.dp.toPx(), androidx.compose.ui.geometry.Offset(size.width * f, y + h / 2))
        }
    }
}

private enum class PlayerIcon { PLAY, PAUSE, PREV, NEXT, FULLSCREEN, EXIT_FULLSCREEN, SETTINGS }

/** A round, borderless player button with a drawn icon (white; dimmed when disabled). */
@Composable
private fun PlayerButton(icon: PlayerIcon, size: androidx.compose.ui.unit.Dp, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    val c = if (enabled) Color.White else Color(0x55FFFFFF)
    Box(modifier.size(size + 20.dp).clip(androidx.compose.foundation.shape.CircleShape)
        .clickable(enabled = enabled, onClick = onClick), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val w = this.size.width; val h = this.size.height
            val stroke = androidx.compose.ui.graphics.drawscope.Stroke(width = w * 0.1f,
                cap = androidx.compose.ui.graphics.StrokeCap.Square)
            fun tri(x0: Float, x1: Float) = androidx.compose.ui.graphics.Path().apply {
                moveTo(x0, h * 0.15f); lineTo(x1, h / 2); lineTo(x0, h * 0.85f); close()
            }
            when (icon) {
                PlayerIcon.PLAY -> drawPath(tri(w * 0.22f, w * 0.88f), c)
                PlayerIcon.PAUSE -> {
                    drawRect(c, androidx.compose.ui.geometry.Offset(w * 0.22f, h * 0.15f), androidx.compose.ui.geometry.Size(w * 0.18f, h * 0.7f))
                    drawRect(c, androidx.compose.ui.geometry.Offset(w * 0.6f, h * 0.15f), androidx.compose.ui.geometry.Size(w * 0.18f, h * 0.7f))
                }
                PlayerIcon.NEXT -> {
                    drawPath(tri(w * 0.15f, w * 0.72f), c)
                    drawRect(c, androidx.compose.ui.geometry.Offset(w * 0.72f, h * 0.15f), androidx.compose.ui.geometry.Size(w * 0.13f, h * 0.7f))
                }
                PlayerIcon.PREV -> {
                    drawPath(tri(w * 0.85f, w * 0.28f), c)
                    drawRect(c, androidx.compose.ui.geometry.Offset(w * 0.15f, h * 0.15f), androidx.compose.ui.geometry.Size(w * 0.13f, h * 0.7f))
                }
                PlayerIcon.FULLSCREEN, PlayerIcon.EXIT_FULLSCREEN -> {
                    val a = w * 0.12f; val b = w * 0.4f; val e = w * 0.88f; val m = w * 0.6f
                    // Four corners, pointing out (fullscreen) or in (exit).
                    val out = icon == PlayerIcon.FULLSCREEN
                    for ((cx, cy) in listOf(a to a, e to a, a to e, e to e)) {
                        val dx = if (cx < w / 2) 1f else -1f; val dy = if (cy < h / 2) 1f else -1f
                        val px = if (out) cx else cx + dx * (b - a); val py = if (out) cy else cy + dy * (b - a)
                        val sx = if (out) dx else -dx; val sy = if (out) dy else -dy
                        val path = androidx.compose.ui.graphics.Path().apply {
                            moveTo(px, py + sy * (m - b)); lineTo(px, py); lineTo(px + sx * (m - b), py)
                        }
                        drawPath(path, c, style = stroke)
                    }
                }
                PlayerIcon.SETTINGS -> {
                    // Gear: a ring with eight teeth.
                    val cx = w / 2; val cy = h / 2
                    for (i in 0 until 8) {
                        val ang = Math.toRadians(i * 45.0)
                        val x = cx + (w * 0.36f) * kotlin.math.cos(ang).toFloat(); val y = cy + (w * 0.36f) * kotlin.math.sin(ang).toFloat()
                        drawCircle(c, w * 0.09f, androidx.compose.ui.geometry.Offset(x, y))
                    }
                    drawCircle(c, w * 0.32f, androidx.compose.ui.geometry.Offset(cx, cy))
                    drawCircle(Color.Black.copy(alpha = 0.6f), w * 0.13f, androidx.compose.ui.geometry.Offset(cx, cy))
                }
            }
        }
    }
}

private fun fmtLap(ms: Long?): String = ms?.let { "%d:%02d.%03d".format(it / 60000, (it / 1000) % 60, it % 1000) } ?: "—"

/** Show/hide the overlay and pick which layout from the layouts folder. */
@Composable
private fun OverlayControls(names: List<String>, selected: String?, show: Boolean, error: String?,
                            onShow: (Boolean) -> Unit, onLayout: (String) -> Unit, folder: String) {
    var open by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = show, onCheckedChange = onShow, enabled = names.isNotEmpty())
            Text("Show overlay", Modifier.clickable(enabled = names.isNotEmpty()) { onShow(!show) })
            if (names.size > 1) Box {
                TextButton(onClick = { open = true }) { Text((selected ?: "Layout").removeSuffix(".json") + " ▾") }
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    names.forEach { n -> DropdownMenuItem(text = { Text(n.removeSuffix(".json")) }, onClick = { onLayout(n); open = false }) }
                }
            } else if (selected != null) Text("  " + selected.removeSuffix(".json"), style = MaterialTheme.typography.bodySmall)
        }
        if (names.isEmpty()) Text("No layouts — copy a WHRRAH layout .json into $folder", style = MaterialTheme.typography.bodySmall)
        if (error != null) Text("Couldn't read the layout: $error", color = Color(0xFFC62828), style = MaterialTheme.typography.bodySmall)
    }
}
