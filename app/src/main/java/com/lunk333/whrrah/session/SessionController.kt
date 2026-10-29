package com.lunk333.whrrah.session

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.lunk333.whrrah.gps.Geo
import com.lunk333.whrrah.gps.GpsFix
import com.lunk333.whrrah.gps.HeadingEstimator
import com.lunk333.whrrah.gps.TrackPath
import com.lunk333.whrrah.gps.GpsSource
import com.lunk333.whrrah.gps.PhoneGps
import com.lunk333.whrrah.osmo.OsmoLink
import com.lunk333.whrrah.racebox.RaceBox
import com.lunk333.whrrah.settings.AppSettings
import dev.konraditurbe.osmosis.core.CameraFile
import java.io.BufferedWriter
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * The open session and everything that runs while it's open (quick-autocross flow):
 *
 *  - keeps the camera connected ([OsmoLink]), retrying like DJI Mimo does;
 *  - a camera recording start (from the mic remote) opens a new [Lap] and starts logging GPS;
 *    the stop closes it;
 *  - the post-recording clip listing is matched to the lap by the clip's timestamp, and the clip's
 *    whole gyro track is pulled into the session folder;
 *  - start/finish line points are pinned from the current GPS fix.
 *
 * Lap time (line crossings, L3) and sync offset (gyro vs RaceBox IMU, I4) are placeholders until the
 * RaceBox is in. One instance per process; all state on the main thread.
 */
class SessionController private constructor(context: Context) {
    private val app = context.applicationContext
    val store = SessionStore(File(app.getExternalFilesDir(null), "sessions"))
    private val link = OsmoLink.get(app)
    /** The camera over Bluetooth during the session (recording start/stop); WiFi only for Match clips. */
    val camera = com.lunk333.whrrah.osmo.CameraMonitor.get(app)
    private val settings = AppSettings.get(app)
    private val main = Handler(Looper.getMainLooper())

    val phoneGps = PhoneGps(app)
    val raceBox = RaceBox.get(app)
    /** Where fixes come from: the RaceBox when it's streaming, else the phone. */
    val gps: GpsSource get() = if (raceBox.connected) raceBox else phoneGps

    var session by mutableStateOf<Session?>(null); private set
    /** One-line note on what the session machinery last did (shown on the session screen). */
    var activity by mutableStateOf(""); private set

    private var gpsWriter: BufferedWriter? = null
    private var gpsLapN = 0

    init {
        link.onRecordingChanged = { e -> onRecordingChanged(e) }
        camera.onRecordingChanged = { e -> onRecordingChanged(e) }
        link.onFilesListed = { videos, _ -> matchClips(videos) }
        // Both feed the log; phone fixes are dropped while the RaceBox is streaming.
        phoneGps.onFix = { if (!raceBox.connected) onFix(it, phoneGps.name) }
        raceBox.onFix = { onFix(it, raceBox.name) }
        raceBox.onData = { d, elapsed -> onImu(d, elapsed) }
    }

    // ---- open / close ------------------------------------------------------------------------

    fun create(event: String, driver: String, car: String): Session {
        settings.rememberChoice(driver, car)
        return store.create(event, driver, car).also(::activate)
    }

    fun open(id: String): Boolean = store.load(id)?.also(::activate) != null

    fun close() {
        stopTrackWalk()
        closeGpsLog()
        main.removeCallbacks(autoConnect)
        if (matching) finishMatching("Session closed")
        phoneGps.stop()   // the RaceBox link is left up — it's cheap and reconnecting takes a while
        session = null
        activity = ""
    }

    private fun activate(s: Session) {
        close()
        // A run still "recording" on open was cut off (app killed or crashed mid-run): its GPS log
        // stopped then, so close it at its last fix (or its start) rather than leave it running.
        val stale = s.laps.filter { it.recording }
        session = if (stale.isEmpty()) s else s.copy(laps = s.laps.map { lap ->
            if (!lap.recording) lap else lap.copy(recStopWallMs = lastFixWallMs(s, lap) ?: lap.recStartWallMs,
                gpsFixes = gpsFixCount(s, lap) ?: lap.gpsFixes,
                note = lap.note ?: "interrupted — the app was closed mid-run")
        }).also { store.save(it) }
        loadWalkPath(s)
        retimeAll()
        // Sync any run that has both the clip's gyro and RaceBox data but no sync yet.
        session?.laps?.filter { it.gyroFile != null && it.imuFile != null && it.videoStartMs == null }?.forEach { syncRun(it.n) }
        activity = "Session open — folder ${s.id}"
        com.lunk333.whrrah.LinkService.update(app)
        main.post(autoConnect)
    }

    // ---- lap timing ----------------------------------------------------------------------------

    /** Time one finished run from its GPS log against the current start/finish lines. */
    private fun timeRun(n: Int) {
        val s = session ?: return
        val lap = s.laps.firstOrNull { it.n == n && !it.recording } ?: return
        val fixes = lap.gpsFile?.let { LapTimer.readGpsCsv(File(store.gpsDir(s.id), it)) } ?: emptyList()
        val r = LapTimer.time(fixes, s.start, s.finish)
        updateLap(n) { it.copy(lapTimeMs = r.lapTimeMs, startCrossMs = r.startMs, finishCrossMs = r.finishMs, timingNote = r.note) }
        if (r.lapTimeMs != null) activity = "Run $n: %d:%06.3f".format(r.lapTimeMs / 60000, (r.lapTimeMs % 60000) / 1000.0)
    }

    /** Re-time every finished run (after a line is set or nudged, or when the session opens). */
    fun retimeAll() {
        val s = session ?: return
        if (!s.trackDefined) return
        s.laps.filter { !it.recording }.forEach { timeRun(it.n) }
        val timed = session?.laps?.count { it.lapTimeMs != null } ?: 0
        if (s.laps.isNotEmpty()) activity = "Re-timed ${s.laps.count { !it.recording }} runs — $timed have times"
    }

    /** Wall-clock time of the last fix in a run's GPS log (its utc_ms column), if it has any. */
    private fun lastFixWallMs(s: Session, lap: Lap): Long? = runCatching {
        File(store.gpsDir(s.id), lap.gpsFile ?: return null).useLines { lines ->
            lines.drop(1).lastOrNull()?.split(',')?.getOrNull(1)?.toLongOrNull()?.takeIf { it > 0 }
        }
    }.getOrNull()

    /** Fixes actually in a run's GPS log (its in-memory counter is only saved at the end of the run). */
    private fun gpsFixCount(s: Session, lap: Lap): Int? = runCatching {
        File(store.gpsDir(s.id), lap.gpsFile ?: return null).useLines { lines -> (lines.count() - 1).coerceAtLeast(0) }
    }.getOrNull()

    /** Start the GPS (call once location permission is granted). */
    fun startGps() {
        if (session == null) return
        raceBox.start()   // reconnects the remembered RaceBox, if any
        if (settings.phoneGpsFallback) phoneGps.start()
    }

    // ---- camera auto-connect -----------------------------------------------------------------

    /** Keeps the camera's Bluetooth link up while the session is open (it reconnects itself once started). */
    private val autoConnect = object : Runnable {
        override fun run() {
            if (session == null) return
            val idleWifi = link.phase in setOf(OsmoLink.Phase.IDLE, OsmoLink.Phase.FAILED)
            if (settings.autoConnectCamera && !matching && idleWifi &&
                camera.state == com.lunk333.whrrah.osmo.CameraMonitor.State.OFF && link.lastCamera != null) {
                camera.start()
            }
            main.postDelayed(this, AUTO_CONNECT_MS)
        }
    }

    // ---- laps --------------------------------------------------------------------------------

    private fun onRecordingChanged(e: OsmoLink.RecordEvent) {
        if (e.started) {
            beginRun(e.wallMs, manual = false)
            currentRun?.let { r -> if (!r.cameraRecorded) updateLap(r.n) { it.copy(cameraRecorded = true) } }
        } else endRun(e.wallMs, fromCamera = true)
    }

    /** The run in progress, if any. */
    val currentRun: Lap? get() = session?.laps?.lastOrNull { it.recording }

    /** Phone button: start a run without the camera (e.g. camera not connected). */
    fun startRunFromPhone() {
        beginRun(System.currentTimeMillis(), manual = true)
        val n = currentRun?.n ?: return
        // Also start the camera. Its "recording started" push then joins this run (cameraRecorded),
        // so a clip gets matched to it later.
        if (camera.connected && camera.recording != true) {
            activity = if (camera.setRecording(true)) "Run $n recording — starting the camera…" else "Run $n recording — camera not connected"
        } else if (link.canControlRecording && link.recording != true) {
            activity = "Run $n recording — starting the camera…"
            link.setRecording(true) { ok, detail ->
                if (ok) updateLap(n) { it.copy(manual = false) }
                activity = if (ok) "Run $n recording — camera recording too" else "Run $n recording — camera didn't start ($detail)"
            }
        }
    }

    /** Phone button: end the run in progress, whoever started it (and stop the camera if it's recording). */
    fun stopRunFromPhone() {
        if (camera.connected && camera.recording == true) {
            endRun(System.currentTimeMillis(), fromCamera = true)
            camera.setRecording(false)
            return
        }
        val stopCamera = link.canControlRecording && link.recording == true
        endRun(System.currentTimeMillis(), fromCamera = stopCamera)
        if (stopCamera) link.setRecording(false) { ok, detail -> if (!ok) activity = "Camera didn't stop recording ($detail) — stop it on the camera" }
    }

    private fun beginRun(wallMs: Long, manual: Boolean) {
        val s = session ?: return
        // One run at a time: a camera start during a phone-started run (or vice versa) joins it.
        currentRun?.let { activity = "Run ${it.n} already recording"; return }
        val lap = Lap(n = s.laps.size + 1, recStartWallMs = wallMs, manual = manual)
        update(s.copy(laps = s.laps + lap))
        liveStartElapsedMs = null; liveLapMs = null; liveLastFix = null
        openGpsLog(lap.n)
        activity = "Run ${lap.n} recording — logging ${gps.name}"
    }

    private fun endRun(wallMs: Long, fromCamera: Boolean) {
        val open = currentRun ?: return
        // A camera stop ends a phone-started run too, but a phone-started run doesn't wait for a clip.
        closeGpsLog()
        updateLap(open.n) { it.copy(recStopWallMs = wallMs) }
        timeRun(open.n)
        activity = "Run ${open.n} stopped" + if (fromCamera || open.expectsClip) " — clip gets matched when you tap Match clips" else ""
    }

    /**
     * Give finished runs their clips: [ClipMatcher] lines up clip start times (camera clock) and
     * lengths with run start/stop times (phone clock), working out the camera clock offset itself.
     */
    private fun matchClips(videos: List<CameraFile>) {
        val s = session ?: return
        val taken = s.laps.mapNotNull { it.clip }.toSet()
        val runs = s.laps.filter { !it.recording && it.clip == null && it.expectsClip }
            .map { ClipMatcher.RunSpan(it.n, it.recStartWallMs, it.recStopWallMs!!) }
        val clips = videos.filter { it.name !in taken }
            .mapNotNull { f -> clipStartMs(f)?.let { ClipMatcher.ClipSpan(f.name, it, f.durationSec) } }
        val result = ClipMatcher.match(runs, clips)
        if (result != null) {
            val byName = videos.associateBy { it.name }
            var cur = session ?: return
            cur = cur.copy(cameraClockOffsetMs = result.clockOffsetMs, laps = cur.laps.map { lap ->
                val m = result.matches.firstOrNull { it.n == lap.n } ?: return@map lap
                val f = byName[m.clip]
                lap.copy(clip = m.clip, clipStartMs = f?.let { clipStartMs(it) }, clipDurationS = f?.durationSec?.takeIf { it > 0 },
                    clipOffsetMs = m.residualMs)
            })
            update(cur)
            activity = "Matched ${result.matches.size} of ${runs.size} runs to clips (camera clock %+.0f s)".format(result.clockOffsetMs / 1000.0)
        } else if (runs.isNotEmpty()) {
            activity = "No clips on the camera line up with the ${runs.size} unmatched runs"
        }
        fetchNextGyro()
    }

    // ---- Match clips: one WiFi visit after the event -----------------------------------------

    /** True while Match clips has the camera on WiFi (the Bluetooth monitor is paused). */
    var matching by mutableStateOf(false); private set

    /**
     * Connect to the camera's WiFi once: list clips, match them to runs, pull each matched clip's gyro,
     * then drop WiFi and go back to the Bluetooth monitor.
     */
    fun matchClipsNow() {
        val cam = link.lastCamera ?: run { activity = "No camera paired yet — pair one in Dev options"; return }
        if (matching || currentRun != null) return
        matching = true
        activity = "Match clips: handing the camera over from Bluetooth to WiFi…"
        // OsmoLink waits for the remote's link to be fully down before it connects.
        link.connect(cam.address, cam.name)
        main.postDelayed(matchTimeout, 5 * 60_000L)
        main.post(watchMatch)
    }

    private val matchTimeout = Runnable { if (matching) finishMatching("Match clips timed out") }

    /** Ends the visit if the WiFi side fails. */
    private val watchMatch = object : Runnable {
        override fun run() {
            if (!matching) return
            if (link.phase == OsmoLink.Phase.FAILED) return finishMatching("Match clips: ${link.status}")
            main.postDelayed(this, 1000)
        }
    }

    private fun finishMatching(why: String) {
        if (!matching) return
        matching = false
        main.removeCallbacks(matchTimeout); main.removeCallbacks(watchMatch)
        link.disconnect()
        activity = why
        main.postDelayed(autoConnect, 2000)   // back to the Bluetooth monitor
    }

    /** One gyro fetch at a time; picks the next lap that has a clip but no gyro yet. */
    private fun fetchNextGyro() {
        if (!settings.autoFetchGyro) { if (matching) finishMatching("Match clips done (gyro pull is off in Settings)"); return }
        val s = session ?: return
        val lap = s.laps.firstOrNull { it.clip != null && it.gyroFile == null && it.note != NO_GYRO_NOTE &&
            link.files.any { f -> f.name == it.clip } }
        if (lap == null) {
            if (matching) finishMatching("Match clips done — " + s.laps.count { it.clip != null } + " runs have clips, " +
                s.laps.count { it.gyroFile != null } + " have gyro")
            return
        }
        val f = link.files.first { it.name == lap.clip }
        val out = File(store.gyroDir(s.id), "lap%02d_%s.csv".format(lap.n, f.name.substringBeforeLast('.')))
        val started = link.fetchGyroTo(f, out) { r ->
            r.onSuccess { g ->
                updateLap(lap.n) { it.copy(gyroFile = out.name, gyroSamples = g.samples.size,
                    note = if (g.samples.size == 0) NO_GYRO_NOTE else it.note) }
                activity = "Run ${lap.n}: gyro saved (${g.samples.size} samples, %.1f s)".format(g.seconds)
                syncRun(lap.n)
            }.onFailure { activity = "Run ${lap.n}: gyro fetch failed (${it.message}) — will retry" }
            main.postDelayed(::fetchNextGyro, if (r.isSuccess) 0 else 5000)
        }
        if (!started) main.postDelayed(::fetchNextGyro, 3000)   // another fetch is running
        else activity = "Run ${lap.n}: pulling gyro from ${f.name}…"
    }

    /** Retry clip matching + gyro for laps that are missing them (e.g. after reconnecting). */
    fun retryPending() {
        if (link.phase == OsmoLink.Phase.READY) link.listFiles() else matchClipsNow()
    }

    // ---- GPS ---------------------------------------------------------------------------------

    private fun openGpsLog(n: Int) {
        val s = session ?: return
        closeGpsLog()
        val f = File(store.gpsDir(s.id), "lap%02d.csv".format(n))
        gpsWriter = f.bufferedWriter().also { it.write("elapsed_ms,utc_ms,lat,lon,speed_mps,heading_deg,accuracy_m,source\n") }
        gpsLapN = n
        updateLap(n) { it.copy(gpsFile = f.name) }
    }

    private fun closeGpsLog() {
        runCatching { gpsWriter?.close() }
        gpsWriter = null
        gpsLapN = 0
        runCatching { imuWriter?.close() }
        imuWriter = null
    }

    // ---- RaceBox IMU log (for video sync) ----------------------------------------------------

    private var imuWriter: java.io.BufferedWriter? = null

    /** Log RaceBox motion while a run is recording; the file is created on the first sample. */
    private fun onImu(d: com.lunk333.whrrah.racebox.RaceBoxData, elapsed: Long) {
        val n = gpsLapN.takeIf { it > 0 } ?: return
        val s = session ?: return
        val w = imuWriter ?: run {
            val f = File(store.imuDir(s.id), "lap%02d.csv".format(n))
            updateLap(n) { it.copy(imuFile = f.name) }
            f.bufferedWriter().also {
                it.write("elapsed_ms,wall_ms,utc_ms,g_x,g_y,g_z,roll_dps,pitch_dps,yaw_dps\n")
                imuWriter = it
            }
        }
        runCatching {
            w.write("$elapsed,${System.currentTimeMillis()},${if (d.timeValid) d.utcMs else 0},${d.gX},${d.gY},${d.gZ},${d.rollRate},${d.pitchRate},${d.yawRate}\n")
        }
    }

    // ---- video sync ----------------------------------------------------------------------------

    /**
     * Find where the run's clip sits on the data clock: correlate the clip's gyro with the RaceBox's
     * rotation rates, starting from the timestamp guess (run start + clip offset). Runs off the main thread.
     */
    fun syncRun(n: Int) {
        val s = session ?: return
        val lap = s.laps.firstOrNull { it.n == n } ?: return
        val gyro = lap.gyroFile?.let { File(store.gyroDir(s.id), it) }?.takeIf { it.isFile } ?: return
        val imu = lap.imuFile?.let { File(store.imuDir(s.id), it) }?.takeIf { it.isFile } ?: return
        val sid = s.id
        Thread {
            val res = runCatching { syncFromFiles(gyro, imu, lap.recStartWallMs + (lap.clipOffsetMs ?: 0)) }.getOrNull()
            main.post {
                if (session?.id != sid) return@post
                if (res == null) { activity = "Run $n: couldn't sync the video (not enough overlapping motion)"; return@post }
                val (videoStart, estimate, r) = res
                updateLap(n) { it.copy(videoStartMs = Math.round(videoStart), syncOffsetMs = Math.round(videoStart - estimate), syncR = r) }
                activity = "Run $n: video synced %+.0f ms from the timestamps (match %.2f)".format(videoStart - estimate, r)
            }
        }.apply { isDaemon = true }.start()
    }

    /** (video start on the data clock, the timestamp guess on that clock, correlation). */
    private fun syncFromFiles(gyro: File, imu: File, estimateWallMs: Long): Triple<Double, Double, Double>? {
        val t = ArrayList<Double>(); val w = ArrayList<Double>(); val x = ArrayList<Double>(); val y = ArrayList<Double>(); val z = ArrayList<Double>()
        gyro.useLines { lines -> lines.drop(1).forEach { l ->
            val c = l.split(','); if (c.size >= 5) { t.add(c[0].toDouble()); w.add(c[1].toDouble()); x.add(c[2].toDouble()); y.add(c[3].toDouble()); z.add(c[4].toDouble()) }
        } }
        val video = GyroSync.videoSpeed(t.toDoubleArray(), w.toDoubleArray(), x.toDoubleArray(), y.toDoubleArray(), z.toDoubleArray())
        // Data clock: GNSS UTC when every sample has it, else the phone's wall clock (what the guess is on).
        val rows = imu.useLines { lines -> lines.drop(1).mapNotNull { l -> l.split(',').takeIf { it.size >= 9 } }.toList() }
        val useUtc = rows.isNotEmpty() && rows.all { (it[2].toLongOrNull() ?: 0) > 0 }
        val dt = DoubleArray(rows.size) { (if (useUtc) rows[it][2] else rows[it][1]).toDouble() }
        val speed = GyroSync.dataSpeed(DoubleArray(rows.size) { rows[it][6].toDouble() },
            DoubleArray(rows.size) { rows[it][7].toDouble() }, DoubleArray(rows.size) { rows[it][8].toDouble() })
        // Guess on the data clock: wall + (UTC − wall) at the first sample when the data is on UTC.
        val estimate = estimateWallMs + if (useUtc) (rows[0][2].toDouble() - rows[0][1].toDouble()) else 0.0
        val r = GyroSync.align(video, dt, speed, estimate) ?: return null
        return Triple(r.videoStartMs, estimate, r.r)
    }

    // ---- live lap timer ------------------------------------------------------------------------

    /** Phone elapsedRealtime of the start-line crossing in the run in progress (null = not crossed yet). */
    var liveStartElapsedMs by mutableStateOf<Double?>(null); private set
    /** The run in progress' lap time once it crosses the finish. */
    var liveLapMs by mutableStateOf<Long?>(null); private set
    private var liveLastFix: GpsFix? = null

    /**
     * Same crossing rule as [LapTimer], one fix at a time, on the phone's clock (so the display can
     * tick). Crossing the start again before the finish re-arms it (staging creep). The saved time
     * from the log replaces this when the run stops.
     */
    private fun liveTiming(fix: GpsFix) {
        val s = session ?: return
        val prev = liveLastFix
        liveLastFix = fix
        if (currentRun == null || prev == null || liveLapMs != null) return
        val pair = listOf(LapTimer.Fix(prev.elapsedMs, prev.lat, prev.lon), LapTimer.Fix(fix.elapsedMs, fix.lat, fix.lon))
        s.start?.let { st -> LapTimer.crossings(pair, st).firstOrNull()?.let { liveStartElapsedMs = it.tMs; return } }
        val start = liveStartElapsedMs ?: return
        s.finish?.let { fin -> LapTimer.crossings(pair, fin).firstOrNull()?.let { liveLapMs = Math.round(it.tMs - start) } }
    }

    /** The last ~30 s of fixes from the active source, for working out the direction of travel. */
    private val recentFixes = ArrayDeque<GpsFix>()

    private fun onFix(fix: GpsFix, source: String) {
        recentFixes.addLast(fix)
        liveTiming(fix)
        while (recentFixes.isNotEmpty() && fix.elapsedMs - recentFixes.first().elapsedMs > HeadingEstimator.WINDOW_MS + 2000) recentFixes.removeFirst()
        walkWriter?.let { ww ->
            runCatching { ww.write("${fix.elapsedMs},${fix.utcMs},${fix.lat},${fix.lon},${fix.speedMps ?: ""},${fix.headingDeg ?: ""},${fix.accuracyM ?: ""},$source\n") }
            addWalkPoint(fix.lat, fix.lon, fix.accuracyM)
        }
        // Flush the logs about once a second: if the app is killed mid-run, the run keeps its GPS
        // (and the reopened session can close it at its real last fix) instead of losing the buffer.
        if (fix.elapsedMs - lastFlushMs >= 1000) {
            lastFlushMs = fix.elapsedMs
            runCatching { walkWriter?.flush() }
            runCatching { gpsWriter?.flush() }
            runCatching { imuWriter?.flush() }
        }
        val w = gpsWriter ?: return
        w.write("${fix.elapsedMs},${fix.utcMs},${fix.lat},${fix.lon},${fix.speedMps ?: ""},${fix.headingDeg ?: ""},${fix.accuracyM ?: ""},$source\n")
        val n = gpsLapN
        session?.let { s -> session = s.copy(laps = s.laps.map { if (it.n == n) it.copy(gpsFixes = it.gpsFixes + 1) else it }) }
    }

    private var lastFlushMs = 0L

    // ---- track -------------------------------------------------------------------------------

    /** Pin the start (or finish) line at the current GPS fix. False if there's no fix yet. */
    /**
     * Set the start (or finish) line: [AppSettings.lineWidthFt] wide, centred on the current fix,
     * at right angles to the direction of travel. Returns an error to show, or null on success.
     */
    /** Move the start (or finish) line [meters] along its heading; 0 with [undo] puts it back where it was set. */
    fun nudgeLine(start: Boolean, meters: Double, undo: Boolean = false) {
        val s = session ?: return
        val l = (if (start) s.start else s.finish) ?: return
        // Slide along the course walk when there is one (so a big nudge follows the course).
        val path = TrackPath(walkPath.toList()).takeIf { it.usable }
        val moved = l.nudged(if (undo) -l.nudgeM else meters, path)
        update(if (start) s.copy(start = moved) else s.copy(finish = moved))
        retimeAll()
        activity = (if (start) "Start" else "Finish") + " line " +
            (if (moved.nudgeM == 0.0) "back where it was set" else "%+.0f m from where it was set".format(moved.nudgeM))
    }

    fun setLine(start: Boolean): String? {
        val s = session ?: return "No session open"
        val fix = gps.lastFix ?: return "No GPS fix yet"
        val est = HeadingEstimator.estimate(recentFixes.toList(), android.os.SystemClock.elapsedRealtime())
            ?: return "Can't tell which way you're going — walk a few steps in the driving direction, then tap again"
        val line = TrackLine(fix.lat, fix.lon, est.headingDeg, settings.lineWidthFt * Geo.FT, fix.accuracyM, est.how,
            setLat = fix.lat, setLon = fix.lon, setHeadingDeg = est.headingDeg)
        update(if (start) s.copy(start = line) else s.copy(finish = line))
        retimeAll()
        activity = (if (start) "Start" else "Finish") + " line set: %.0f ft wide, facing %.0f° (from %s, ±%.1f m)"
            .format(settings.lineWidthFt, est.headingDeg, est.how, fix.accuracyM ?: 0f)
        return null
    }

    // ---- track walk --------------------------------------------------------------------------

    /** True while the course walk is being recorded (the start/finish buttons are shown then). */
    var walkRecording by mutableStateOf(false); private set
    /** The walk trace for the sketch, thinned to points >= [WALK_SPACING_M] apart. */
    val walkPath = mutableStateListOf<Geo.LatLon>()
    var walkDistanceM by mutableStateOf(0.0); private set
    private var walkWriter: BufferedWriter? = null

    /** Start recording the course walk. [append] continues the existing trace instead of starting over. */
    fun startTrackWalk(append: Boolean) {
        val s = session ?: return
        stopTrackWalk()
        startGps()
        val f = File(store.gpsDir(s.id), WALK_FILE)
        val keep = append && f.isFile
        if (!keep) { walkPath.clear(); walkDistanceM = 0.0 }
        walkWriter = java.io.FileWriter(f, keep).buffered().also {
            if (!keep) it.write("elapsed_ms,utc_ms,lat,lon,speed_mps,heading_deg,accuracy_m,source\n")
        }
        walkRecording = true
        update(s.copy(trackWalkFile = WALK_FILE))
        activity = if (keep) "Track walk: continuing" else "Track walk: recording — set the start and finish lines as you reach them"
    }

    fun stopTrackWalk() {
        val w = walkWriter ?: return
        runCatching { w.close() }
        walkWriter = null
        walkRecording = false
        activity = "Track walk saved (%.0f m)".format(walkDistanceM)
    }

    private fun addWalkPoint(lat: Double, lon: Double, accuracyM: Float? = null) {
        if (accuracyM != null && accuracyM > WALK_MAX_ACCURACY_M) return   // too vague to draw
        val last = walkPath.lastOrNull()
        if (last == null) { walkPath.add(Geo.LatLon(lat, lon)); return }
        val d = Geo.distance(last.lat, last.lon, lat, lon)
        if (d > WALK_MAX_JUMP_M) {
            // Nobody walks 100 m between fixes: one end is a bad fix. Early on it's the start (a stale
            // first fix), so restart the trace; later, drop the outlier.
            if (walkPath.size <= 3) { walkPath.clear(); walkPath.add(Geo.LatLon(lat, lon)); walkDistanceM = 0.0 }
            return
        }
        if (d >= WALK_SPACING_M) { walkPath.add(Geo.LatLon(lat, lon)); walkDistanceM += d }
    }

    /** Re-read a saved walk so the sketch shows it when the session is reopened. */
    private fun loadWalkPath(s: Session) {
        walkPath.clear(); walkDistanceM = 0.0
        val f = File(store.gpsDir(s.id), s.trackWalkFile ?: return)
        if (!f.isFile) return
        runCatching {
            f.useLines { lines ->
                lines.drop(1).forEach { line ->
                    val c = line.split(',')
                    if (c.size >= 7) addWalkPoint(c[2].toDouble(), c[3].toDouble(), c[6].toFloatOrNull())
                }
            }
        }
    }

    /** Current position + travel direction, for the track sketch. */
    val currentFix: GpsFix? get() = gps.lastFix

    // ---- util --------------------------------------------------------------------------------

    private fun update(s: Session) { session = s; store.save(s) }

    private fun updateLap(n: Int, f: (Lap) -> Lap) {
        val s = session ?: return
        update(s.copy(laps = s.laps.map { if (it.n == n) f(it) else it }))
    }

    companion object {
        private const val AUTO_CONNECT_MS = 20_000L
        private const val NO_GYRO_NOTE = "clip has no gyro (recording format)"
        private const val WALK_FILE = "trackwalk.csv"
        private const val WALK_SPACING_M = 1.0
        private const val WALK_MAX_ACCURACY_M = 25f
        private const val WALK_MAX_JUMP_M = 100.0

        /** Clip start from its DJI name (`DJI_yyyyMMddHHmmss_…`), camera local time = phone local time. */
        fun clipStartMs(f: CameraFile): Long? = f.timestamp.takeIf { it.length == 14 }?.let {
            runCatching { SimpleDateFormat("yyyyMMddHHmmss", Locale.US).parse(it)?.time }.getOrNull()
        }

        @Volatile private var instance: SessionController? = null
        fun get(context: Context): SessionController =
            instance ?: synchronized(this) { instance ?: SessionController(context).also { instance = it } }
    }
}
