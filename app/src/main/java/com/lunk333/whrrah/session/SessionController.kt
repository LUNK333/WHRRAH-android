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
        session = s
        loadWalkPath(s)
        activity = "Session open — folder ${s.id}"
        com.lunk333.whrrah.LinkService.update(app)
        main.post(autoConnect)
    }

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
        val s = session ?: return
        if (e.started) {
            val lap = Lap(n = s.laps.size + 1, recStartWallMs = e.wallMs)
            update(s.copy(laps = s.laps + lap))
            openGpsLog(lap.n)
            activity = "Run ${lap.n} recording — logging ${gps.name}"
        } else {
            val open = s.laps.lastOrNull { it.recording } ?: return
            closeGpsLog()
            updateLap(open.n) { it.copy(recStopWallMs = e.wallMs) }
            activity = "Run ${open.n} stopped — waiting for the clip to show up on the camera"
        }
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
        val lap = s.laps.firstOrNull { it.clip != null && it.gyroFile == null && it.note == null } ?: return
        val f = link.files.firstOrNull { it.name == lap.clip } ?: return
        val out = File(store.gyroDir(s.id), "lap%02d_%s.csv".format(lap.n, f.name.substringBeforeLast('.')))
        val started = link.fetchGyroTo(f, out) { r ->
            r.onSuccess { g ->
                updateLap(lap.n) { it.copy(gyroFile = out.name, gyroSamples = g.samples.size,
                    note = if (g.samples.size == 0) "clip has no gyro (recording format)" else null) }
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
