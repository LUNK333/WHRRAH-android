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
    val layoutResult = remember(layoutName) { layoutFiles.firstOrNull { it.name == layoutName }?.let(layouts::load) }
    val layout = layoutResult?.getOrNull()

    var fullscreen by remember { mutableStateOf(false) }
    var settingsOpen by remember { mutableStateOf(false) }
    if (file == null && fullscreen) fullscreen = false
    FullscreenEffect(fullscreen)
    androidx.activity.compose.BackHandler(enabled = fullscreen) { fullscreen = false }

    Column(modifier) {
        // The player sits above the list (and fills the screen in fullscreen) — always in this slot, so
        // switching runs or going fullscreen keeps the same video view.
        if (lap != null && file != null) RunPlayer(
            lap, file, s, ctl, if (showOverlay) layout else null, fullscreen,
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
                RunRow(r, best = r.lapTimeMs != null && r.lapTimeMs == s.bestLapMs, selected = r.n == selected,
                    thumb = rememberThumbnail(link, link.files.firstOrNull { it.name == r.clip }, File(ctl.store.dir(s.id), "video/thumbs")),
                    onClick = { selected = r.n })
            }
            item { Spacer(Modifier.height(8.dp)) }
        }
    }

    if (settingsOpen) {
        @OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
        androidx.compose.material3.ModalBottomSheet(onDismissRequest = { settingsOpen = false }) {
            Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 32.dp)) {
                Text("Video settings", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 8.dp))
                OverlayControls(layoutFiles.map { it.name }, layoutName, showOverlay, layoutResult?.exceptionOrNull()?.message,
                    onShow = { showOverlay = it; layouts.showOverlay = it },
                    onLayout = { layoutName = it; layouts.selected = it },
                    folder = layouts.dir.path)
            }
        }
    }
}

/** One run: thumbnail · "Run N (best)" with the clip name and length · lap time. */
@Composable
private fun RunRow(r: Lap, best: Boolean, selected: Boolean, thumb: androidx.compose.ui.graphics.ImageBitmap?, onClick: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = if (selected) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer) else CardDefaults.cardColors(),
    ) {
        Row(Modifier.padding(8.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.width(112.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(6.dp)).background(Color.Black),
                contentAlignment = Alignment.Center) {
                if (thumb != null) androidx.compose.foundation.Image(thumb, contentDescription = "Run ${r.n}",
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
            Column(Modifier.weight(1f)) {
                Text("Run ${r.n}" + if (best) " (best)" else "", style = MaterialTheme.typography.titleLarge, maxLines = 1)
                Text(r.clip!!.substringBeforeLast('.'), style = MaterialTheme.typography.bodySmall, maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                r.clipDurationS?.let { Text("%d:%02d".format(it / 60, it % 60), style = MaterialTheme.typography.bodySmall) }
            }
            Text(fmtLap(r.lapTimeMs), style = MaterialTheme.typography.headlineMedium.copy(fontFeatureSettings = "tnum"))
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
    lap: Lap, file: CameraFile, s: Session, ctl: SessionController, layout: OverlayLayout?, fullscreen: Boolean,
    onFullscreen: () -> Unit, onSettings: () -> Unit, onPrev: (() -> Unit)?, onNext: (() -> Unit)?,
) {
    val candidates = remember(file.path) { file.previewCandidates().map { "http://${OsmoLink.IP}$it" } }
    var idx by remember(file.path) { mutableStateOf(0) }
    var status by remember(file.path) { mutableStateOf("Loading ${if (candidates.size > 1) "preview" else "clip"}…") }
    var posMs by remember(file.path) { mutableStateOf(0) }
    var durMs by remember(file.path) { mutableStateOf(0) }
    var playing by remember(file.path) { mutableStateOf(false) }
    var view by remember { mutableStateOf<VideoView?>(null) }
    // Video frame size (the proxy is smaller but the same shape); until it's known, assume the layout's.
    var videoW by remember(file.path) { mutableStateOf(0) }
    var videoH by remember(file.path) { mutableStateOf(0) }
    var controls by remember { mutableStateOf(true) }
    var poke by remember { mutableStateOf(0) }          // bumped by any control use: restarts the fade timer
    var scrubMs by remember(file.path) { mutableStateOf<Float?>(null) }
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
    fun seekToData(dataMs: Long) { view?.seekTo((dataMs - lap.videoStartMs!!).toInt().coerceAtLeast(0)) }

    LaunchedEffect(file.path) {
        while (true) {
            view?.let {
                if (it.isPlaying || posMs != it.currentPosition) posMs = it.currentPosition
                if (playing != it.isPlaying) playing = it.isPlaying
                if (durMs <= 0 && it.duration > 0) durMs = it.duration
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
                            mp.isLooping = false; status = ""; videoW = mp.videoWidth; videoH = mp.videoHeight; durMs = mp.duration; v.start()
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
                            view?.let { if (it.isPlaying) it.pause() else it.start() }; poke++
                        }
                        PlayerButton(PlayerIcon.NEXT, 30.dp, enabled = onNext != null) { onNext?.invoke(); poke++ }
                    }
                    Row(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(start = 12.dp, end = 4.dp, bottom = 14.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text("${clock(shownMs)} / ${clock(durMs)}", color = Color.White, style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f))
                        PlayerButton(if (fullscreen) PlayerIcon.EXIT_FULLSCREEN else PlayerIcon.FULLSCREEN, 24.dp) { onFullscreen(); poke++ }
                    }
                }
            }
            SeekBar(
                fraction = if (durMs > 0) shownMs.toFloat() / durMs else 0f, expanded = controls,
                modifier = Modifier.align(Alignment.BottomCenter),
                onScrub = { f -> scrubMs = f * durMs; poke++ },
                onScrubEnd = { scrubMs?.let { view?.seekTo(it.toInt()); posMs = it.toInt() }; scrubMs = null },
            )
        }
        }
        if (!fullscreen) Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Run ${lap.n} · ${file.name}" + (if (idx == 0 && candidates.size > 1) " · low-res preview" else " · full res") +
                (if (layout != null && lap.videoStartMs == null) if (videoStartMs != null) " · overlay timing approximate (not synced)" else " · no overlay: run isn't matched to its clip" else ""),
                style = MaterialTheme.typography.bodySmall)
            if (synced) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // A couple of seconds of run-up before each line.
                OutlinedButton(onClick = { seekToData(lap.startCrossMs!! - 2000) }) { Text("Jump to start") }
                OutlinedButton(onClick = { seekToData(lap.finishCrossMs!! - 2000) }) { Text("Jump to finish") }
            } else Text("Not synced to the data yet — the lap overlay and jumps need a sync (RaceBox + clip gyro).",
                style = MaterialTheme.typography.bodySmall)
        }
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
