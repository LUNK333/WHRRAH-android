package com.lunk333.whrrah.session

import com.lunk333.whrrah.gps.Geo
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A start or finish line of a point-to-point autocross course: [widthM] wide, centred on (lat, lon),
 * at right angles to [headingDeg] — the direction of travel when it was set. Crossing it in that
 * direction is what lap timing (L3) looks for.
 */
data class TrackLine(
    val lat: Double,
    val lon: Double,
    /** Direction of travel, degrees from north. Null only for lines saved before headings existed. */
    val headingDeg: Double?,
    val widthM: Double,
    val accuracyM: Float?,
    /** How the heading was found, for display ("GPS heading at 1.2 m/s", "your last 6 m of movement"). */
    val headingSource: String? = null,
    /** Net calibration nudge along [headingDeg] since the line was set, metres (+ = further along the course). */
    val nudgeM: Double = 0.0,
) {
    /**
     * The same line moved [meters] along its heading (negative = back), still square to the heading.
     * Used to calibrate against official timing. Point-only lines (no heading) can't be nudged.
     */
    fun nudged(meters: Double): TrackLine {
        val h = headingDeg ?: return this
        val c = Geo.offset(lat, lon, h, meters)
        return copy(lat = c.lat, lon = c.lon, nudgeM = nudgeM + meters)
    }

    /** The two ends of the line (left, right as seen travelling along [headingDeg]). Null without a heading. */
    fun endpoints(): Pair<Geo.LatLon, Geo.LatLon>? {
        val h = headingDeg ?: return null
        return Geo.offset(lat, lon, h - 90, widthM / 2) to Geo.offset(lat, lon, h + 90, widthM / 2)
    }

    fun toJson(): JSONObject = JSONObject().put("lat", lat).put("lon", lon).put("widthM", widthM)
        .putOpt("headingDeg", headingDeg).putOpt("headingSource", headingSource).put("nudgeM", nudgeM)
        .apply { accuracyM?.let { put("accuracyM", it.toDouble()) } }

    companion object {
        fun fromJson(o: JSONObject?) = o?.let {
            TrackLine(
                lat = it.getDouble("lat"),
                lon = it.getDouble("lon"),
                headingDeg = if (it.has("headingDeg") && !it.isNull("headingDeg")) it.getDouble("headingDeg") else null,
                widthM = it.optDouble("widthM", 20 * Geo.FT),
                accuracyM = if (it.has("accuracyM")) it.getDouble("accuracyM").toFloat() else null,
                headingSource = it.optStringOrNull("headingSource"),
                nudgeM = it.optDouble("nudgeM", 0.0),
            )
        }
    }
}

/**
 * One run through the course. Bounded by the camera's recording (started from the mic remote ~30 s
 * before the start line, stopped ~30 s after the finish). Times are the phone's wall clock.
 */
data class Lap(
    val n: Int,
    val recStartWallMs: Long,
    val recStopWallMs: Long? = null,
    /** Camera clip that covers this lap, once matched from the post-recording listing. */
    val clip: String? = null,
    /** Gyro CSV (t_ms,w,x,y,z) in the session's gyro/ folder, once fetched. */
    val gyroFile: String? = null,
    val gyroSamples: Int? = null,
    /** GPS log in the session's gps/ folder (written while recording). */
    val gpsFile: String? = null,
    val gpsFixes: Int = 0,
    /** Start-to-finish line crossing time. Needs GPS (L3). */
    val lapTimeMs: Long? = null,
    /** Video-to-data offset from gyro correlation. Needs RaceBox IMU (I2/I4). */
    val syncOffsetMs: Long? = null,
    val note: String? = null,
    /** Started from the phone's Start run button rather than by the camera recording. */
    val manual: Boolean = false,
) {
    val recording: Boolean get() = recStopWallMs == null

    fun toJson(): JSONObject = JSONObject()
        .put("n", n).put("recStartWallMs", recStartWallMs)
        .putOpt("recStopWallMs", recStopWallMs).putOpt("clip", clip)
        .putOpt("gyroFile", gyroFile).putOpt("gyroSamples", gyroSamples)
        .putOpt("gpsFile", gpsFile).put("gpsFixes", gpsFixes)
        .putOpt("lapTimeMs", lapTimeMs).putOpt("syncOffsetMs", syncOffsetMs).putOpt("note", note).put("manual", manual)

    companion object {
        fun fromJson(o: JSONObject) = Lap(
            n = o.getInt("n"),
            recStartWallMs = o.getLong("recStartWallMs"),
            recStopWallMs = o.optLongOrNull("recStopWallMs"),
            clip = o.optStringOrNull("clip"),
            gyroFile = o.optStringOrNull("gyroFile"),
            gyroSamples = o.optLongOrNull("gyroSamples")?.toInt(),
            gpsFile = o.optStringOrNull("gpsFile"),
            gpsFixes = o.optInt("gpsFixes", 0),
            lapTimeMs = o.optLongOrNull("lapTimeMs"),
            syncOffsetMs = o.optLongOrNull("syncOffsetMs"),
            note = o.optStringOrNull("note"),
            manual = o.optBoolean("manual", false),
        )
    }
}

/** A session = one event (e.g. "NE-SVT #16") on one day, for one driver + car. Stored as session.json. */
data class Session(
    /** Folder name, `<event>_<yyyy-MM-dd>` (suffixed _2, _3… if taken). */
    val id: String,
    val event: String,
    val date: String,
    val driver: String,
    val car: String,
    val createdMs: Long,
    val start: TrackLine? = null,
    val finish: TrackLine? = null,
    val laps: List<Lap> = emptyList(),
    /** GPS trace of the course walk, in the session's gps/ folder, once one has been recorded. */
    val trackWalkFile: String? = null,
) {
    val trackDefined: Boolean get() = start?.headingDeg != null && finish?.headingDeg != null
    val bestLapMs: Long? get() = laps.mapNotNull { it.lapTimeMs }.minOrNull()

    fun toJson(): JSONObject = JSONObject()
        .put("version", 1)
        .put("id", id).put("event", event).put("date", date).put("driver", driver).put("car", car)
        .put("createdMs", createdMs)
        .putOpt("start", start?.toJson()).putOpt("finish", finish?.toJson()).putOpt("trackWalkFile", trackWalkFile)
        .put("laps", JSONArray().apply { laps.forEach { put(it.toJson()) } })

    companion object {
        fun fromJson(o: JSONObject) = Session(
            id = o.getString("id"),
            event = o.getString("event"),
            date = o.getString("date"),
            driver = o.optString("driver"),
            car = o.optString("car"),
            createdMs = o.getLong("createdMs"),
            start = TrackLine.fromJson(o.optJSONObject("start")),
            finish = TrackLine.fromJson(o.optJSONObject("finish")),
            trackWalkFile = o.optStringOrNull("trackWalkFile"),
            laps = o.optJSONArray("laps")?.let { a -> List(a.length()) { Lap.fromJson(a.getJSONObject(it)) } } ?: emptyList(),
        )

        /** `NE-SVT #16` + `2026-11-01` → `NE-SVT #16_2026-11-01`, minus characters filesystems reject. */
        fun folderName(event: String, date: String): String =
            (event.trim().replace(Regex("""[\\/:*?"<>|\p{Cntrl}]"""), "-").ifEmpty { "Session" } + "_" + date).take(80)

        fun today(nowMs: Long = System.currentTimeMillis()): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(nowMs))
    }
}

/**
 * Session folders under [root] (app-specific external storage, so no permissions and visible over
 * USB at Android/data/com.lunk333.whrrah/files/sessions):
 *
 *     <event>_<date>/session.json
 *     <event>_<date>/gyro/lapNN_<clip>.csv
 *     <event>_<date>/gps/lapNN.csv
 *     <event>_<date>/video/          (clips, once downloading is added)
 */
class SessionStore(val root: File) {
    fun dir(id: String) = File(root, id)
    fun gyroDir(id: String) = File(dir(id), "gyro").also { it.mkdirs() }
    fun gpsDir(id: String) = File(dir(id), "gps").also { it.mkdirs() }

    fun create(event: String, driver: String, car: String, nowMs: Long = System.currentTimeMillis()): Session {
        root.mkdirs()
        val date = Session.today(nowMs)
        val base = Session.folderName(event, date)
        var id = base
        var k = 2
        while (dir(id).exists()) id = "${base}_${k++}"
        val s = Session(id, event.trim(), date, driver.trim(), car.trim(), nowMs)
        dir(id).mkdirs()
        File(dir(id), "video").mkdirs()
        save(s)
        return s
    }

    fun save(s: Session) {
        val f = File(dir(s.id), "session.json")
        val tmp = File(dir(s.id), "session.json.tmp")
        tmp.writeText(s.toJson().toString(2))
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }

    fun load(id: String): Session? = runCatching { Session.fromJson(JSONObject(File(dir(id), "session.json").readText())) }.getOrNull()

    /** All sessions, newest first. */
    fun list(): List<Session> = (root.listFiles() ?: emptyArray())
        .filter { File(it, "session.json").isFile }
        .mapNotNull { load(it.name) }
        .sortedByDescending { it.createdMs }
}

private fun JSONObject.optStringOrNull(k: String): String? = if (has(k) && !isNull(k)) getString(k) else null
private fun JSONObject.optLongOrNull(k: String): Long? = if (has(k) && !isNull(k)) getLong(k) else null
