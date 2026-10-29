package com.lunk333.whrrah.session

import com.lunk333.whrrah.gps.Geo
import java.io.File
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Point-to-point lap timing from a run's GPS log against the session's start and finish lines.
 *
 * A crossing is a step between consecutive fixes that goes from behind a line to on/after it, in the
 * line's driving direction, within its width. The crossing time is interpolated along that step,
 * so it isn't rounded to the fix rate (40 ms at the RaceBox's 25 Hz).
 */
object LapTimer {
    /** [tMs]: GNSS time when the log has it (the RaceBox's own fix time), else phone receive time. */
    data class Fix(val tMs: Long, val lat: Double, val lon: Double)
    data class Crossing(val tMs: Double, val lateralM: Double, val gapMs: Long)
    data class Result(val lapTimeMs: Long?, val startMs: Long?, val finishMs: Long?, val note: String?)

    /** A step across a line longer than this is too coarse to trust (GPS dropout). */
    const val MAX_GAP_MS = 3_000L

    /** All forward crossings of [line], in time order. */
    fun crossings(fixes: List<Fix>, line: TrackLine): List<Crossing> {
        val h = line.headingDeg ?: return emptyList()
        val hr = Math.toRadians(h)
        val fx = sin(hr); val fy = cos(hr)        // forward (east, north)
        val rx = cos(hr); val ry = -sin(hr)       // right
        val half = line.widthM / 2
        val out = ArrayList<Crossing>()
        var prev: Triple<Double, Double, Fix>? = null   // (along, across, fix)
        for (f in fixes) {
            val (e, n) = Geo.toLocal(line.lat, line.lon, f.lat, f.lon)
            val along = e * fx + n * fy
            val across = e * rx + n * ry
            val p = prev
            if (p != null && p.first < 0 && along >= 0 && f.tMs > p.third.tMs) {
                val t = -p.first / (along - p.first)
                val lateral = p.second + t * (across - p.second)
                val gap = f.tMs - p.third.tMs
                if (abs(lateral) <= half && gap <= MAX_GAP_MS)
                    out.add(Crossing(p.third.tMs + t * gap, lateral, gap))
            }
            prev = Triple(along, across, f)
        }
        return out
    }

    fun time(fixes: List<Fix>, start: TrackLine?, finish: TrackLine?): Result {
        if (start?.headingDeg == null || finish?.headingDeg == null) return Result(null, null, null, "track not defined")
        if (fixes.size < 2) return Result(null, null, null, "no GPS in this run")
        val finishes = crossings(fixes, finish)
        val starts = crossings(fixes, start)
        if (starts.isEmpty()) return Result(null, null, finishes.firstOrNull()?.tMs?.toLong(), "no start crossing found")
        // The first finish after any start; the start is the last one before it (a roll over the line in staging doesn't count).
        val fin = finishes.firstOrNull { it.tMs > starts.first().tMs }
            ?: return Result(null, starts.last().tMs.toLong(), null, "no finish crossing found")
        val st = starts.last { it.tMs < fin.tMs }
        val lap = Math.round(fin.tMs - st.tMs)
        val coarse = maxOf(st.gapMs, fin.gapMs) >= 500   // slower than 2 Hz (phone GPS is ~1 Hz)
        return Result(lap, Math.round(st.tMs), Math.round(fin.tMs), if (coarse) "timed from sparse GPS (phone?) — rough" else null)
    }

    /**
     * Read a run's GPS log (`elapsed_ms,utc_ms,lat,lon,…`). Uses the GNSS time column when it's
     * present and moving forward for the whole run, else the phone's receive time.
     */
    fun readGpsCsv(f: File): List<Fix> {
        if (!f.isFile) return emptyList()
        val rows = f.useLines { lines ->
            lines.drop(1).mapNotNull { l ->
                val c = l.split(',')
                if (c.size < 4) null else runCatching { arrayOf(c[0].toLong(), c[1].toLong(), c[2].toDouble(), c[3].toDouble()) }.getOrNull()
            }.toList()
        }
        val utcOk = rows.size >= 2 && rows.all { (it[1] as Long) > 0 } && rows.zipWithNext().all { (a, b) -> (b[1] as Long) >= (a[1] as Long) }
        return rows.map { Fix(if (utcOk) it[1] as Long else it[0] as Long, it[2] as Double, it[3] as Double) }
    }
}
