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
    val app: android.content.Context = context.applicationContext
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

    // ---- clip downloads ------------------------------------------------------------------------

    /** Clips being downloaded: name → progress 0..1 (−1 = size unknown). */
    val downloads = androidx.compose.runtime.mutableStateMapOf<String, Float>()
    /** Bumped when a download finishes, so "is it on the phone" gets re-read. */
    var downloadsDone by mutableStateOf(0); private set

    /** Where a session's clip is kept on the phone (the session's video/ folder). */
    fun clipFile(s: Session, clip: String) = File(store.dir(s.id), "video/$clip")

    /** Delete the phone's copy of run [n]'s clip (the camera keeps its own). */
    fun deleteClipCopy(n: Int, inSession: Session? = session) {
        val s = inSession ?: return
        val clip = s.laps.firstOrNull { it.n == n }?.clip ?: return
        if (clip in downloads) { activity = "Run $n's clip is still downloading"; return }
        val f = clipFile(s, clip)
        val mb = f.length() / 1_000_000
        f.delete(); File(f.path + ".part").delete()
        downloadsDone++
        activity = "Deleted run $n's clip from the phone ($mb MB freed)"
    }

    /** Download run [n]'s clip (full res) off the camera into the session; needs the camera WiFi up (View runs). */
    fun downloadClip(n: Int) {
        val s = session ?: return
        val clip = s.laps.firstOrNull { it.n == n }?.clip ?: return
        val out = clipFile(s, clip)
        if (out.isFile) { activity = "Run $n's clip is already on the phone"; return }
        if (clip in downloads) return
        val f = link.files.firstOrNull { it.name == clip } ?: run { activity = "Run $n's clip isn't in the camera's list"; return }
        downloads[clip] = 0f
        activity = "Downloading run $n's clip…"
        Thread {
            var shown = -1
            val ok = runCatching {
                link.downloadTo(f, out) { done, total ->
                    val pct = if (total > 0) (done * 100 / total).toInt() else -1
                    if (pct != shown) { shown = pct; main.post { if (clip in downloads) downloads[clip] = if (pct < 0) -1f else pct / 100f } }
                }
            }.getOrDefault(false)
            main.post {
                downloads.remove(clip); downloadsDone++
                activity = if (ok) "Run $n's clip is on the phone (%d MB)".format(out.length() / 1_000_000)
                    else "Run $n's clip didn't finish downloading — swipe it again to resume"
            }
        }.start()
    }

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
        dropInconsistentClips()
        activity = "Session open — folder ${s.id}"
        retimeAll {
            // Sync any run that has both the clip's gyro and RaceBox data but no sync yet.
            session?.laps?.filter { it.gyroFile != null && it.imuFile != null && it.videoStartMs == null }?.forEach { syncRun(it.n) }
        }
        com.lunk333.whrrah.LinkService.update(app)
        main.post(autoConnect)
    }

    // ---- lap timing ----------------------------------------------------------------------------

    /** Time one finished run from its GPS log against the current start/finish lines. */
    private fun timeRun(n: Int) {
        val s = session ?: return
        val lap = s.laps.firstOrNull { it.n == n && !it.recording } ?: return
        val r = rawTiming(s, lap, s.timingOffsetMs)
        updateLap(n) { it.copy(lapTimeMs = r.lapTimeMs, startCrossMs = r.startMs, finishCrossMs = r.finishMs, timingNote = r.note) }
        if (r.lapTimeMs != null) activity = "Run $n: " + com.lunk333.whrrah.settings.TimeFormat.run(r.lapTimeMs)
    }

    /** Times [lap] from its logs (GPS + RaceBox motion for the launch) with the session's start distance and [offsetMs]. */
    private fun rawTiming(s: Session, lap: Lap, offsetMs: Long): LapTimer.Result {
        val fixes = lap.gpsFile?.let { cachedLog(File(store.gpsDir(s.id), it), LapTimer::readGpsCsv) } ?: emptyList()
        val imu = lap.imuFile?.let { cachedLog(File(store.imuDir(s.id), it), LapTimer::readImuCsv) } ?: emptyList()
        return LapTimer.time(fixes, s.start, s.finish, imu, s.startDistanceM, offsetMs)
    }

    /**
     * Parsed run logs, kept in memory (keyed by path + size + time, so a log still being written is
     * read afresh): re-timing a session after a line change doesn't re-read every file from storage.
     */
    private val logCache = object : LinkedHashMap<String, List<*>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<*>>?) = size > 120
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> cachedLog(f: File, read: (File) -> List<T>): List<T> {
        val key = "${f.path}|${f.length()}|${f.lastModified()}"
        synchronized(logCache) { logCache[key]?.let { return it as List<T> } }
        val v = read(f)
        synchronized(logCache) { logCache[key] = v }
        return v
    }

    /** Re-timing / calibrating in the background: what's happening, for a spinner (null = idle). */
    var busy by mutableStateOf<String?>(null); private set
    private val timingExec = java.util.concurrent.Executors.newSingleThreadExecutor()
    /** Bumped by every re-time: only the latest one's results are applied. */
    private var retimeGen = 0

    /**
     * Fit the timing offset to the official times entered on runs (long-press a run): the mean of
     * official − app, leaving out any run more than 0.5 s off the rest (a typo, a rerun). Re-times
     * every run. Returns what it did, for the screen.
     */
    fun calibrateToOfficial(onResult: (String) -> Unit) {
        val s = session ?: return onResult("No session open")
        busy = "Calibrating to official times…"
        timingExec.execute {
            val pairs = s.laps.filter { it.officialTimeMs != null && !it.recording }.mapNotNull { lap ->
                runCatching { rawTiming(s, lap, 0) }.getOrNull()?.lapTimeMs?.let { lap.n to (lap.officialTimeMs!! - it) }
            }
            main.post {
                busy = null
                if (pairs.isEmpty()) return@post onResult("Enter official times on a few timed runs first (long-press a run)")
                val cur = session?.takeIf { it.id == s.id } ?: return@post
                val msg = calibrationResult(pairs)
                update(cur.copy(timingOffsetMs = msg.first))
                retimeAll { onResult(msg.second) }
            }
        }
    }

    /** The offset and the message, from (run, official − app) pairs. */
    private fun calibrationResult(pairs: List<Pair<Int, Long>>): Pair<Long, String> {
        val median = pairs.map { it.second }.sorted()[pairs.size / 2]
        val kept = pairs.filter { kotlin.math.abs(it.second - median) <= 500 }
        val left = pairs - kept.toSet()
        val off = Math.round(kept.map { it.second }.average())
        val spread = kept.maxOf { kotlin.math.abs(it.second - off) }
        return off to "Calibrated from ${kept.size} official time${if (kept.size == 1) "" else "s"}: %+.3f s, runs agree within ±%.3f s".format(off / 1000.0, spread / 1000.0) +
            (if (left.isNotEmpty()) " (left out run " + left.joinToString { "${it.first}" } + ": %+.2f s off — typo?".format((left.first().second - off) / 1000.0) + ")" else "")
    }

    fun resetCalibration() {
        val s = session ?: return
        update(s.copy(timingOffsetMs = 0)); retimeAll()
    }

    /** Change a line's width in place (e.g. a runway-wide finish), keeping where it is. */
    fun setLineWidth(start: Boolean, widthM: Double) {
        val s = session ?: return
        val l = (if (start) s.start else s.finish) ?: return
        val w = widthM.coerceIn(1.5, 120.0)
        update(if (start) s.copy(start = l.copy(widthM = w)) else s.copy(finish = l.copy(widthM = w)))
        retimeAll()
    }

    /** Re-time every finished run (after a line is set or nudged, or when the session opens). */
    fun retimeAll(onDone: (() -> Unit)? = null) {
        val s = session ?: return onDone?.invoke() ?: Unit
        if (!s.trackDefined) return onDone?.invoke() ?: Unit
        val laps = s.laps.filter { !it.recording }
        val gen = ++retimeGen
        busy = "Re-timing ${laps.size} runs…"
        // Off the main thread (reading every run's logs takes a few seconds on the phone), applied in one update.
        timingExec.execute {
            val results = laps.associate { it.n to runCatching { rawTiming(s, it, s.timingOffsetMs) }.getOrNull() }
            main.post {
                if (gen != retimeGen) return@post          // a newer re-time is on its way
                busy = null
                val cur = session?.takeIf { it.id == s.id } ?: return@post
                update(cur.copy(laps = cur.laps.map { l ->
                    val r = results[l.n] ?: return@map l
                    if (l.recording) l else l.copy(lapTimeMs = r.lapTimeMs, startCrossMs = r.startMs, finishCrossMs = r.finishMs, timingNote = r.note)
                }))
                val timed = session?.laps?.count { it.lapTimeMs != null } ?: 0
                if (laps.isNotEmpty()) activity = "Re-timed ${laps.size} runs — $timed have times"
                onDone?.invoke()
            }
        }
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
            if (settings.autoConnectCamera && !matching && !viewing && idleWifi &&
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
        // After the highest number, not the count: a deleted run's number (and log files) aren't reused mid-list.
        val lap = Lap(n = (s.laps.maxOfOrNull { it.n } ?: 0) + 1, recStartWallMs = wallMs, manual = manual)
        update(s.copy(laps = s.laps + lap))
        liveStartElapsedMs = null; liveLapMs = null; liveLastFix = null
        liveStageT = null; liveDist = 0.0; liveRunT = null; liveLastV = null
        startDelta(s)
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
        if (setupCheckRunning) return onSetupCheckList(videos)
        dropInconsistentClips()
        val s = session ?: return
        val taken = s.laps.mapNotNull { it.clip }.toSet()
        val runs = s.laps.filter { !it.recording && it.clip == null && it.expectsClip }
            .map { ClipMatcher.RunSpan(it.n, it.recStartWallMs, it.recStopWallMs!!) }
        val clips = videos.filter { it.name !in taken && it.name !in s.removedClips }
            .mapNotNull { f -> clipStartMs(f)?.let { ClipMatcher.ClipSpan(f.name, it, f.durationSec) } }
        val result = ClipMatcher.match(runs, clips, knownOffsetMs = clockConsensus(s)?.first)
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
            // With the new matches there may now be a consensus that an older match disagrees with.
            dropInconsistentClips()
            session?.let { s2 -> clockConsensus(s2)?.let { (off, _) -> if (s2.cameraClockOffsetMs != off) update(s2.copy(cameraClockOffsetMs = off)) } }
        } else if (runs.isNotEmpty()) {
            activity = "No clips on the camera line up with the ${runs.size} unmatched runs"
        }
        fetchNextGyro()
    }

    // ---- Check camera setup: a 3 s test clip, then is there gyro in it? -----------------------

    /** Result / progress of the last setup check (Dev options), and whether one is running. */
    var setupCheck by mutableStateOf<String?>(null); private set
    var setupCheckOk by mutableStateOf<Boolean?>(null); private set
    var setupCheckRunning by mutableStateOf(false); private set

    /**
     * FOV can't be read over Bluetooth, so prove the setup the direct way: record a ~3 s clip over
     * Bluetooth, fetch it over WiFi, and see whether it carries the 1 kHz gyro. Session must be closed
     * (the test recording would otherwise become a run). The clip is left on the camera.
     */
    fun checkCameraSetup() {
        if (setupCheckRunning) return
        setupCheckOk = null
        when {
            session != null -> { setupCheck = "Close the session first — the test recording would become a run"; return }
            !camera.connected -> { setupCheck = "Connect the camera over Bluetooth first"; return }
            camera.recording == true -> { setupCheck = "The camera is already recording — stop it first"; return }
            link.lastCamera == null -> { setupCheck = "Pair the camera over WiFi once first (Scan above)"; return }
        }
        setupCheckRunning = true
        setupCheck = "Recording a 3 s test clip…"
        camera.setRecording(true)
        main.postDelayed(checkStop, 4500)
        main.postDelayed(checkTimeout, 120_000)
    }

    private val checkStop = Runnable {
        if (camera.recording != true) return@Runnable finishSetupCheck(false, "The camera didn't start recording — is it in Video mode?")
        camera.setRecording(false)
        setupCheck = "Stopping, then joining the camera's WiFi…"
        main.postDelayed(checkConnect, 3000)
    }

    private val checkConnect = Runnable {
        val cam = link.lastCamera ?: return@Runnable finishSetupCheck(false, "No camera paired")
        link.connect(cam.address, cam.name)   // waits for the Bluetooth remote to let go first
        main.post(checkWatch)
    }

    private val checkWatch = object : Runnable {
        override fun run() {
            if (!setupCheckRunning) return
            if (link.phase == OsmoLink.Phase.FAILED) return finishSetupCheck(false, "WiFi: ${link.status}")
            if (link.phase != OsmoLink.Phase.READY) setupCheck = "WiFi: ${link.status}"
            main.postDelayed(this, 1000)
        }
    }

    private val checkTimeout = Runnable { if (setupCheckRunning) finishSetupCheck(false, "Timed out") }

    private fun onSetupCheckList(videos: List<CameraFile>) {
        // The test clip is the newest one (the camera's clock may be off, so don't match by time).
        val clip = videos.maxByOrNull { it.timestamp } ?: return finishSetupCheck(false, "No clips on the camera")
        setupCheck = "Reading the gyro track of ${clip.name}…"
        val out = File(app.cacheDir, "setup_check_gyro.csv")
        val started = link.fetchGyroTo(clip, out) { r ->
            r.onSuccess { g ->
                val setup = camera.setup?.let { " (${it.modeName}, stabilization ${it.eisName}, res ${it.resolution}, fps ${it.fps})" } ?: ""
                if (g.samples.size > 0) finishSetupCheck(true, "✓ Gyro present: ${g.samples.size} samples in ${clip.name}$setup")
                else finishSetupCheck(false, "✗ No gyro in ${clip.name}$setup — set FOV to Wide, stabilization off, " +
                    "and a recording format that keeps gyro")
            }.onFailure { finishSetupCheck(false, "Couldn't read the clip: ${it.message}") }
        }
        if (!started) main.postDelayed({ onSetupCheckList(videos) }, 2000)
    }

    private fun finishSetupCheck(ok: Boolean, msg: String) {
        main.removeCallbacks(checkStop); main.removeCallbacks(checkConnect)
        main.removeCallbacks(checkWatch); main.removeCallbacks(checkTimeout)
        setupCheckRunning = false
        setupCheckOk = ok
        setupCheck = msg
        if (link.phase != OsmoLink.Phase.IDLE) link.disconnect()
        main.postDelayed({ camera.start() }, 2000)   // back to the Bluetooth remote
    }

    /**
     * Un-match runs whose clip doesn't fit the session's camera clock offset (> 30 s out) — from before
     * the matcher was tied to it, a lone run could be given a random clip. Clears its gyro + sync too.
     */
    /** Matched runs and the clock offset two or more of them agree on (null = no consensus yet). */
    private fun clockConsensus(s: Session): Pair<Long, List<Lap>>? {
        val matched = s.laps.filter { it.clip != null && it.clipStartMs != null }
        val c = ClipMatcher.consensusOffset(matched.map { it.clipStartMs!! - it.recStartWallMs }) ?: return null
        return c.first to matched.filterIndexed { i, _ -> i !in c.second }
    }

    private fun dropInconsistentClips() {
        val s = session ?: return
        // Only trust a clock that two or more matched runs agree on; drop the matches that don't fit it.
        val (_, bad) = clockConsensus(s) ?: return
        if (bad.isEmpty()) return
        update(s.copy(laps = s.laps.map { l -> if (l !in bad) l else l.copy(clip = null, clipStartMs = null, clipDurationS = null,
            clipOffsetMs = null, gyroFile = null, gyroSamples = null, videoStartMs = null, syncOffsetMs = null, syncR = null) }))
        activity = "Un-matched run " + bad.joinToString { "${it.n}" } + " — its clip didn't fit this session's camera clock"
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

    // ---- View runs: hold the camera WiFi while the viewer is open ----------------------------

    /** True while the run viewer has the camera on WiFi (the Bluetooth monitor is paused). */
    var viewing by mutableStateOf(false); private set

    /**
     * Join the camera's WiFi for the viewer: lists clips (matching any runs that still lack one) and
     * stays up so clips can stream. [stopViewing] drops WiFi and goes back to Bluetooth.
     */
    fun startViewing() {
        if (viewing || matching || currentRun != null) return
        val cam = link.lastCamera ?: run { activity = "No camera paired yet — pair one in Dev options"; return }
        viewing = true
        activity = "Viewer: handing the camera over from Bluetooth to WiFi…"
        link.connect(cam.address, cam.name)
    }

    fun stopViewing() {
        if (!viewing) return
        viewing = false
        link.disconnect()
        activity = "Viewer closed"
        main.postDelayed(autoConnect, 2000)   // back to the Bluetooth monitor
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

    // Launch-based live start (same rule as LapTimer): receiver time of the last standstill in the
    // stage, distance covered since, and the start once it reaches the session's start distance.
    private var liveStageT: Double? = null
    private var liveDist = 0.0
    private var liveRunT: Double? = null
    private var liveLastV: Double? = null

    private fun fixT(f: GpsFix) = (if (f.utcMs > 0) f.utcMs else f.elapsedMs).toDouble()

    /**
     * The live timer, one fix at a time. Start: a stop in the stage (re)arms it — dropping a start
     * seen while pulling up — and the clock starts once the car has moved the start distance from that
     * launch; with no stop in the stage, the start line crossing. Finish: the finish line crossing.
     * The saved time from the log (with the RaceBox accelerometer) replaces this when the run stops.
     */
    private fun liveTiming(fix: GpsFix) {
        val s = session ?: return
        val prev = liveLastFix
        liveLastFix = fix
        if (currentRun == null || prev == null || liveLapMs != null) return
        val st = s.start ?: return
        val tNow = fixT(fix); val tPrev = fixT(prev)
        // Speed: the receiver's, unless it's missing or plainly wrong (0 while the position is moving
        // — some GPS sources do that); then from the position change.
        val dtS = (tNow - tPrev) / 1000
        val vPos = if (dtS > 0) com.lunk333.whrrah.gps.Geo.distance(prev.lat, prev.lon, fix.lat, fix.lon) / dtS else 0.0
        val vRep = fix.speedMps?.toDouble()
        val v = if (vRep == null || (vRep < 0.5 && vPos > 3.0)) vPos else vRep
        val pv = liveLastV ?: v
        liveLastV = v
        if (v < LapTimer.STOP_MPS && LapTimer.inStage(LapTimer.Fix(tNow.toLong(), fix.lat, fix.lon), st)) {
            liveStageT = tNow; liveDist = 0.0
            if (liveRunT != null) { liveRunT = null; liveStartElapsedMs = null; deltaTracker?.disarm() }
            return
        }
        if (liveRunT == null) {
            if (liveStageT != null) {
                val ds = (pv + v) / 2 * (tNow - tPrev) / 1000
                if (liveDist + ds >= s.startDistanceM) {
                    val k = if (ds > 0) (s.startDistanceM - liveDist) / ds else 1.0
                    startLive(tPrev + k * (tNow - tPrev), fix)
                }
                liveDist += ds
            } else if (liveStageT == null) {
                val pair = listOf(LapTimer.Fix(tPrev.toLong(), prev.lat, prev.lon), LapTimer.Fix(tNow.toLong(), fix.lat, fix.lon))
                LapTimer.crossings(pair, st).firstOrNull()?.let { startLive(it.tMs, fix) }
            }
        }
        val runT = liveRunT ?: return
        val pair = listOf(LapTimer.Fix(tPrev.toLong(), prev.lat, prev.lon), LapTimer.Fix(tNow.toLong(), fix.lat, fix.lon))
        s.finish?.let { fin -> LapTimer.crossings(pair, fin).firstOrNull()?.let { liveLapMs = Math.round(it.tMs - runT) + s.timingOffsetMs } }
    }

    private fun startLive(tStart: Double, fix: GpsFix) {
        liveRunT = tStart
        liveStartElapsedMs = fix.elapsedMs - (fixT(fix) - tStart)   // the display ticks on the phone's clock
        deltaTracker?.startAt(tStart)
    }

    // ---- live delta -----------------------------------------------------------------------------

    /** Live delta to the session's best run, for the run in progress (held after it stops). */
    var liveDelta by mutableStateOf<DeltaState?>(null); private set
    /** The run the live delta is measured against (null = no reference yet: the first timed run becomes it). */
    var deltaRefRun by mutableStateOf<Int?>(null); private set
    private var deltaTracker: DeltaTracker? = null
    private var deltaRefCache: Pair<String, ReferenceLap>? = null

    /**
     * Picks the reference at the start of a run — the fastest timed run whose log has no gaps — so it
     * never changes mid-run; a new best becomes the reference from the next run on.
     */
    private fun startDelta(s: Session) {
        liveDelta = null; deltaTracker = null; deltaRefRun = null
        val st = s.start ?: return
        val proj = LocalProjector(st.lat, st.lon)
        var ref: ReferenceLap? = null
        // DNFs never; cones don't matter here (the line through the course is what's compared).
        for (lap in s.laps.filter { !it.dnf && it.lapTimeMs != null && it.startCrossMs != null && it.finishCrossMs != null && it.gpsFile != null }.sortedBy { it.lapTimeMs }) {
            val key = "${s.id}/${lap.n}/${lap.startCrossMs}/${lap.finishCrossMs}/${st.lat},${st.lon}"
            val r = deltaRefCache?.takeIf { it.first == key }?.second ?: runCatching {
                ReferenceLap.build(LapTimer.readGpsCsv(File(store.gpsDir(s.id), lap.gpsFile!!)), lap.startCrossMs!!.toDouble(), lap.finishCrossMs!!.toDouble(), proj)
            }.getOrNull()?.also { deltaRefCache = key to it }
            if (r != null && r.valid) { ref = r; deltaRefRun = lap.n; break }
        }
        // Started by the controller (launch-based), finished by the tracker at the finish line.
        deltaTracker = DeltaTracker(ref, proj, null, s.finish)
    }

    private fun liveDeltaFix(fix: GpsFix) {
        if (currentRun == null) return
        val tr = deltaTracker ?: return
        // Receiver time (the RaceBox's own fix time), not the phone's arrival time.
        tr.onFix((if (fix.utcMs > 0) fix.utcMs else fix.elapsedMs).toDouble(), fix.lat, fix.lon, fix.speedMps?.toDouble())
        if (tr.state != liveDelta) liveDelta = tr.state
    }

    /** The last ~30 s of fixes from the active source, for working out the direction of travel. */
    private val recentFixes = ArrayDeque<GpsFix>()

    private fun onFix(fix: GpsFix, source: String) {
        recentFixes.addLast(fix)
        liveTiming(fix)
        liveDeltaFix(fix)
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
        val widthFt = if (start) settings.lineWidthFt else settings.finishLineWidthFt
        val line = TrackLine(fix.lat, fix.lon, est.headingDeg, widthFt * Geo.FT, fix.accuracyM, est.how,
            setLat = fix.lat, setLon = fix.lon, setHeadingDeg = est.headingDeg)
        update(if (start) s.copy(start = line) else s.copy(finish = line))
        retimeAll()
        activity = (if (start) "Start" else "Finish") + " line set: %.0f ft wide, facing %.0f° (from %s, ±%.1f m)"
            .format(widthFt, est.headingDeg, est.how, fix.accuracyM ?: 0f)
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
    /** [append] = continue the existing walk; otherwise a new walk, which also clears both lines (a fresh track). */
    fun startTrackWalk(append: Boolean) {
        var s = session ?: return
        stopTrackWalk()
        if (!append && (s.start != null || s.finish != null)) { s = s.copy(start = null, finish = null); update(s) }
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

    /**
     * Take run [n]'s clip out of [inSession] (a test clip, the wrong clip…): the run keeps its data but
     * loses the clip, its sync and gyro, and the phone's copy; the clip is never matched again.
     * The clip itself stays on the camera.
     */
    fun removeClipFromSession(n: Int, inSession: Session) {
        val open = session?.takeIf { it.id == inSession.id }
        val s = open ?: inSession
        val lap = s.laps.firstOrNull { it.n == n } ?: return
        val clip = lap.clip ?: return
        if (clip in downloads) { activity = "Run $n's clip is still downloading"; return }
        runCatching { clipFile(s, clip).delete(); java.io.File(clipFile(s, clip).path + ".part").delete() }
        runCatching { File(store.dir(s.id), "video/thumbs/" + clip.substringBeforeLast('.') + ".jpg").delete() }
        lap.gyroFile?.let { runCatching { File(store.gyroDir(s.id), it).delete() } }
        val updated = s.copy(removedClips = (s.removedClips + clip).distinct(), laps = s.laps.map { l ->
            if (l.n != n) l else l.copy(clip = null, clipStartMs = null, clipDurationS = null, clipOffsetMs = null,
                gyroFile = null, gyroSamples = null, videoStartMs = null, syncOffsetMs = null, syncR = null)
        })
        if (open != null) update(updated) else store.save(updated)
        downloadsDone++
        activity = "Removed $clip from run $n — it won't be matched again"
    }

    /** The run menu's edits: who drove it, DNF, cones hit, official time. */
    fun updateRunInfo(n: Int, driver: String?, dnf: Boolean, cones: Int, officialTimeMs: Long?) {
        updateLap(n) { it.copy(driver = driver, dnf = dnf, cones = cones.coerceAtLeast(0), officialTimeMs = officialTimeMs) }
    }

    /**
     * Delete a finished run: drops it from the session and deletes its GPS, RaceBox and gyro logs.
     * The camera clip is left on the camera. Other runs keep their numbers.
     */
    fun deleteRun(n: Int) {
        val s = session ?: return
        val lap = s.laps.firstOrNull { it.n == n } ?: return
        if (lap.recording || currentRun?.n == n) { activity = "Stop run $n before deleting it"; return }
        lap.gpsFile?.let { runCatching { File(store.gpsDir(s.id), it).delete() } }
        lap.imuFile?.let { runCatching { File(store.imuDir(s.id), it).delete() } }
        lap.gyroFile?.let { runCatching { File(store.gyroDir(s.id), it).delete() } }
        update(s.copy(laps = s.laps.filter { it.n != n }))
        activity = "Deleted run $n"
    }

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
