package com.lunk333.whrrah.session

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.lunk333.whrrah.gps.GpsFix
import com.lunk333.whrrah.gps.GpsSource
import com.lunk333.whrrah.gps.PhoneGps
import com.lunk333.whrrah.osmo.OsmoLink
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
    /** Where fixes come from. The RaceBox (B2) takes over from the phone here when it's added. */
    val gps: GpsSource get() = phoneGps

    var session by mutableStateOf<Session?>(null); private set
    /** One-line note on what the session machinery last did (shown on the session screen). */
    var activity by mutableStateOf(""); private set

    private var gpsWriter: BufferedWriter? = null
    private var gpsLapN = 0

    init {
        link.onRecordingChanged = { e -> onRecordingChanged(e) }
        link.onFilesListed = { videos, _ -> matchClips(videos) }
        phoneGps.onFix = ::onFix
    }

    // ---- open / close ------------------------------------------------------------------------

    fun create(event: String, driver: String, car: String): Session {
        settings.rememberChoice(driver, car)
        return store.create(event, driver, car).also(::activate)
    }

    fun open(id: String): Boolean = store.load(id)?.also(::activate) != null

    fun close() {
        closeGpsLog()
        main.removeCallbacks(autoConnect)
        gps.stop()
        session = null
        activity = ""
    }

    private fun activate(s: Session) {
        close()
        session = s
        activity = "Session open — folder ${s.id}"
        main.post(autoConnect)
    }

    /** Start the GPS (call once location permission is granted). */
    fun startGps() { if (session != null && settings.phoneGpsFallback) gps.start() }

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

    private fun onFix(fix: GpsFix) {
        val w = gpsWriter ?: return
        w.write("${fix.elapsedMs},${fix.utcMs},${fix.lat},${fix.lon},${fix.speedMps ?: ""},${fix.headingDeg ?: ""},${fix.accuracyM ?: ""},${gps.name}\n")
        val n = gpsLapN
        session?.let { s -> session = s.copy(laps = s.laps.map { if (it.n == n) it.copy(gpsFixes = it.gpsFixes + 1) else it }) }
    }

    // ---- track -------------------------------------------------------------------------------

    /** Pin the start (or finish) line at the current GPS fix. False if there's no fix yet. */
    fun setTrackPoint(start: Boolean): Boolean {
        val s = session ?: return false
        val fix = gps.lastFix ?: return false
        val p = TrackPoint(fix.lat, fix.lon, fix.accuracyM)
        update(if (start) s.copy(start = p) else s.copy(finish = p))
        activity = (if (start) "Start" else "Finish") + " set (±%.0f m)".format(fix.accuracyM ?: 0f)
        return true
    }

    // ---- util --------------------------------------------------------------------------------

    private fun update(s: Session) { session = s; store.save(s) }

    private fun updateLap(n: Int, f: (Lap) -> Lap) {
        val s = session ?: return
        update(s.copy(laps = s.laps.map { if (it.n == n) f(it) else it }))
    }

    companion object {
        private const val AUTO_CONNECT_MS = 20_000L
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
