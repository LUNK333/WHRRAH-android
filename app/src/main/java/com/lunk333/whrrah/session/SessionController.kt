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
import kotlin.math.abs

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
        link.onFilesListed = { videos, _ -> matchClips(videos) }
        // Both feed the log; phone fixes are dropped while the RaceBox is streaming.
        phoneGps.onFix = { if (!raceBox.connected) onFix(it, phoneGps.name) }
        raceBox.onFix = { onFix(it, raceBox.name) }
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
                note = lap.note ?: "interrupted — the app was closed mid-run")
        }).also { store.save(it) }
        loadWalkPath(s)
        activity = "Session open — folder ${s.id}"
        com.lunk333.whrrah.LinkService.update(app)
        main.post(autoConnect)
    }

    /** Wall-clock time of the last fix in a run's GPS log (its utc_ms column), if it has any. */
    private fun lastFixWallMs(s: Session, lap: Lap): Long? = runCatching {
        File(store.gpsDir(s.id), lap.gpsFile ?: return null).useLines { lines ->
            lines.drop(1).lastOrNull()?.split(',')?.getOrNull(1)?.toLongOrNull()?.takeIf { it > 0 }
        }
    }.getOrNull()

    /** Start the GPS (call once location permission is granted). */
    fun startGps() {
        if (session == null) return
        raceBox.start()   // reconnects the remembered RaceBox, if any
        if (settings.phoneGpsFallback) phoneGps.start()
    }

    // ---- camera auto-connect -----------------------------------------------------------------

    private val autoConnect = object : Runnable {
        override fun run() {
            if (session == null) return
            val cam = link.lastCamera
            if (settings.autoConnectCamera && cam != null &&
                link.phase in setOf(OsmoLink.Phase.IDLE, OsmoLink.Phase.FAILED)) {
                activity = "Trying to connect to ${cam.name}…"
                link.connect(cam.address, cam.name)
            }
            main.postDelayed(this, AUTO_CONNECT_MS)
        }
    }

    // ---- laps --------------------------------------------------------------------------------

    private fun onRecordingChanged(e: OsmoLink.RecordEvent) {
        if (e.started) beginRun(e.wallMs, manual = false) else endRun(e.wallMs, fromCamera = true)
    }

    /** The run in progress, if any. */
    val currentRun: Lap? get() = session?.laps?.lastOrNull { it.recording }

    /** Phone button: start a run without the camera (e.g. camera not connected). */
    fun startRunFromPhone() {
        beginRun(System.currentTimeMillis(), manual = true)
        val n = currentRun?.n ?: return
        // Also start the camera if it's on WiFi. Its "recording started" push then joins this run,
        // and the run is treated as a camera run (clip matched, gyro pulled) from here on.
        if (link.canControlRecording && link.recording != true) {
            activity = "Run $n recording — starting the camera…"
            link.setRecording(true) { ok, detail ->
                if (ok) updateLap(n) { it.copy(manual = false) }
                activity = if (ok) "Run $n recording — camera recording too" else "Run $n recording — camera didn't start ($detail)"
            }
        }
    }

    /** Phone button: end the run in progress, whoever started it (and stop the camera if it's recording). */
    fun stopRunFromPhone() {
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
        openGpsLog(lap.n)
        activity = "Run ${lap.n} recording — logging ${gps.name}"
    }

    private fun endRun(wallMs: Long, fromCamera: Boolean) {
        val open = currentRun ?: return
        // A camera stop ends a phone-started run too, but a phone-started run doesn't wait for a clip.
        closeGpsLog()
        updateLap(open.n) { it.copy(recStopWallMs = wallMs) }
        activity = "Run ${open.n} stopped" + if (fromCamera || !open.manual) " — waiting for the clip to show up on the camera" else ""
    }

    /** Give each finished lap the clip whose start time matches its recording start. */
    private fun matchClips(videos: List<CameraFile>) {
        val s = session ?: return
        val taken = s.laps.mapNotNull { it.clip }.toSet()
        for (lap in s.laps.filter { !it.recording && it.clip == null }) {
            val clip = videos.filter { it.name !in taken }
                .mapNotNull { f -> clipStartMs(f)?.let { f to abs(it - lap.recStartWallMs) } }
                .filter { it.second <= CLIP_MATCH_MS }
                .minByOrNull { it.second }?.first ?: continue
            updateLap(lap.n) { it.copy(clip = clip.name) }
            activity = "Run ${lap.n} ↔ ${clip.name}"
        }
        fetchNextGyro()
    }

    /** One gyro fetch at a time; picks the next lap that has a clip but no gyro yet. */
    private fun fetchNextGyro() {
        if (!settings.autoFetchGyro) return
        val s = session ?: return
        val lap = s.laps.firstOrNull { it.clip != null && it.gyroFile == null && it.note != NO_GYRO_NOTE } ?: return
        val f = link.files.firstOrNull { it.name == lap.clip } ?: return
        val out = File(store.gyroDir(s.id), "lap%02d_%s.csv".format(lap.n, f.name.substringBeforeLast('.')))
        val started = link.fetchGyroTo(f, out) { r ->
            r.onSuccess { g ->
                updateLap(lap.n) { it.copy(gyroFile = out.name, gyroSamples = g.samples.size,
                    note = if (g.samples.size == 0) NO_GYRO_NOTE else it.note) }
                activity = "Run ${lap.n}: gyro saved (${g.samples.size} samples, %.1f s)".format(g.seconds)
            }.onFailure { activity = "Run ${lap.n}: gyro fetch failed (${it.message}) — will retry" }
            main.postDelayed(::fetchNextGyro, if (r.isSuccess) 0 else 5000)
        }
        if (!started) main.postDelayed(::fetchNextGyro, 3000)   // another fetch is running
        else activity = "Run ${lap.n}: pulling gyro from ${f.name}…"
    }

    /** Retry clip matching + gyro for laps that are missing them (e.g. after reconnecting). */
    fun retryPending() {
        if (link.phase == OsmoLink.Phase.READY) link.listFiles() else matchClips(link.files.toList())
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
    }

    /** The last ~30 s of fixes from the active source, for working out the direction of travel. */
    private val recentFixes = ArrayDeque<GpsFix>()

    private fun onFix(fix: GpsFix, source: String) {
        recentFixes.addLast(fix)
        while (recentFixes.isNotEmpty() && fix.elapsedMs - recentFixes.first().elapsedMs > HeadingEstimator.WINDOW_MS + 2000) recentFixes.removeFirst()
        walkWriter?.let { ww ->
            runCatching { ww.write("${fix.elapsedMs},${fix.utcMs},${fix.lat},${fix.lon},${fix.speedMps ?: ""},${fix.headingDeg ?: ""},${fix.accuracyM ?: ""},$source\n") }
            addWalkPoint(fix.lat, fix.lon, fix.accuracyM)
        }
        val w = gpsWriter ?: return
        w.write("${fix.elapsedMs},${fix.utcMs},${fix.lat},${fix.lon},${fix.speedMps ?: ""},${fix.headingDeg ?: ""},${fix.accuracyM ?: ""},$source\n")
        val n = gpsLapN
        session?.let { s -> session = s.copy(laps = s.laps.map { if (it.n == n) it.copy(gpsFixes = it.gpsFixes + 1) else it }) }
    }

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
        val moved = l.nudged(if (undo) -l.nudgeM else meters)
        update(if (start) s.copy(start = moved) else s.copy(finish = moved))
        activity = (if (start) "Start" else "Finish") + " line " +
            (if (moved.nudgeM == 0.0) "back where it was set" else "%+.0f m from where it was set".format(moved.nudgeM))
    }

    fun setLine(start: Boolean): String? {
        val s = session ?: return "No session open"
        val fix = gps.lastFix ?: return "No GPS fix yet"
        val est = HeadingEstimator.estimate(recentFixes.toList(), android.os.SystemClock.elapsedRealtime())
            ?: return "Can't tell which way you're going — walk a few steps in the driving direction, then tap again"
        val line = TrackLine(fix.lat, fix.lon, est.headingDeg, settings.lineWidthFt * Geo.FT, fix.accuracyM, est.how)
        update(if (start) s.copy(start = line) else s.copy(finish = line))
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
        /** A clip starts within this of the recording-start push (the camera's clock is set from the phone's). */
        private const val CLIP_MATCH_MS = 20_000L

        /** Clip start from its DJI name (`DJI_yyyyMMddHHmmss_…`), camera local time = phone local time. */
        fun clipStartMs(f: CameraFile): Long? = f.timestamp.takeIf { it.length == 14 }?.let {
            runCatching { SimpleDateFormat("yyyyMMddHHmmss", Locale.US).parse(it)?.time }.getOrNull()
        }

        @Volatile private var instance: SessionController? = null
        fun get(context: Context): SessionController =
            instance ?: synchronized(this) { instance ?: SessionController(context).also { instance = it } }
    }
}
