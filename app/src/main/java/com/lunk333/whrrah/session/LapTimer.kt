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
    data class Fix(val tMs: Long, val lat: Double, val lon: Double, val speedMps: Double = Double.NaN)
    /** A RaceBox motion sample on the GNSS clock: raw accelerations (G) on its own axes. */
    data class Imu(val tMs: Long, val x: Double, val y: Double, val z: Double)
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

    fun time(fixes: List<Fix>, start: TrackLine?, finish: TrackLine?): Result = time(fixes, start, finish, emptyList())

    /**
     * Times a run. Start: from the **launch** when the car stopped in the stage — the last standstill
     * near the start line before the finish ([stageArea]) — once it has travelled [startDistanceM]
     * (stage → timing gate), the launch pinned by the RaceBox accelerometer ([imu]) when there is one.
     * That matches an optical start gate far better than a GPS line crossing, and copes with a car that
     * rolled over the start line while pulling up, or is staged over it. Without a stop in the stage
     * (no speed in the log, a rolling start) it falls back to the start line crossing.
     * Finish: the first finish line crossing after that. [offsetMs] (calibrated against official
     * times) is added to the lap time.
     */
    fun time(fixes: List<Fix>, start: TrackLine?, finish: TrackLine?, imu: List<Imu>,
             startDistanceM: Double = DEFAULT_START_DISTANCE_M, offsetMs: Long = 0): Result {
        if (start?.headingDeg == null || finish?.headingDeg == null) return Result(null, null, null, "track not defined")
        if (fixes.size < 2) return Result(null, null, null, "no GPS in this run")
        val finishes = crossings(fixes, finish)
        val starts = crossings(fixes, start)
        val stops = fixes.filter { !it.speedMps.isNaN() && it.speedMps < STOP_MPS && inStage(it, start) }
        val first = listOfNotNull(starts.firstOrNull()?.tMs, stops.firstOrNull()?.tMs?.toDouble()).minOrNull()
            ?: return Result(null, null, finishes.firstOrNull()?.tMs?.toLong(), "no start crossing found")
        val fin = finishes.firstOrNull { it.tMs > first }
            ?: return Result(null, starts.lastOrNull()?.tMs?.toLong(), null, "no finish crossing found")

        // Launch: the last standstill in the stage before the finish.
        val stopT = stops.lastOrNull { it.tMs < fin.tMs }?.tMs
        val launchStart = stopT?.let { gpsLaunch ->
            val launch = imuLaunch(imu, fixes, gpsLaunch) ?: gpsLaunch.toDouble()
            travelled(fixes, launch, startDistanceM)?.takeIf { it < fin.tMs }
        }
        val startT: Double
        val note: String?
        if (launchStart != null) {
            startT = launchStart
            note = null
        } else {
            val st = starts.lastOrNull { it.tMs < fin.tMs } ?: return Result(null, null, Math.round(fin.tMs), "no start crossing found")
            startT = st.tMs
            val coarse = maxOf(st.gapMs, fin.gapMs) >= 500   // slower than 2 Hz (phone GPS is ~1 Hz)
            note = when {
                coarse -> "timed from sparse GPS (phone?) — rough"
                fixes.any { !it.speedMps.isNaN() } -> "timed from the start line (no stop in the stage found)"
                else -> null
            }
        }
        return Result(Math.round(fin.tMs - startT) + offsetMs, Math.round(startT), Math.round(fin.tMs), note)
    }

    /** Within the stage area around the start line: up to [STAGE_BEHIND_M] before it to [STAGE_AHEAD_M] past it. */
    fun inStage(f: Fix, start: TrackLine): Boolean {
        val hr = Math.toRadians(start.headingDeg ?: return false)
        val (e, n) = Geo.toLocal(start.lat, start.lon, f.lat, f.lon)
        val along = e * sin(hr) + n * cos(hr)
        val across = e * cos(hr) - n * sin(hr)
        return along > -STAGE_BEHIND_M && along < STAGE_AHEAD_M && abs(across) < maxOf(STAGE_SIDE_M, start.widthM / 2)
    }

    /** Time at which the car has covered [distanceM] since [fromMs], integrating GPS speed. */
    fun travelled(fixes: List<Fix>, fromMs: Double, distanceM: Double): Double? {
        var s = 0.0
        var prev: Fix? = null
        for (f in fixes) {
            if (f.speedMps.isNaN()) return null
            val p = prev
            prev = f
            if (p == null || f.tMs <= fromMs) continue
            val t0 = maxOf(p.tMs.toDouble(), fromMs)
            val v0 = if (p.tMs >= fromMs) p.speedMps else p.speedMps + (f.speedMps - p.speedMps) * (fromMs - p.tMs) / (f.tMs - p.tMs)
            val dt = (f.tMs - t0) / 1000.0
            val ds = (v0 + f.speedMps) / 2 * dt
            if (s + ds >= distanceM) {
                // Within this step, speed changes linearly: solve s + v0·τ + a·τ²/2 = D.
                val a = (f.speedMps - v0) / dt
                val need = distanceM - s
                val tau = if (abs(a) < 1e-6) need / maxOf(v0, 1e-6) else (-v0 + Math.sqrt(maxOf(0.0, v0 * v0 + 2 * a * need))) / a
                return t0 + tau * 1000
            }
            s += ds
        }
        return null
    }

    /**
     * The launch from the RaceBox accelerometer: the first sustained forward acceleration around the
     * GPS launch (the last standstill — GPS speed lags it by ~0.1 s). The RaceBox's forward axis is
     * found from the log itself (its accelerations against the GPS speed changes), so how it's mounted
     * doesn't matter. Null without a usable motion log.
     */
    fun imuLaunch(imu: List<Imu>, fixes: List<Fix>, gpsLaunchMs: Long): Double? {
        if (imu.size < 50) return null
        val w = forwardAxis(imu, fixes) ?: return null
        fun fwd(i: Int) = w[0] * imu[i].x + w[1] * imu[i].y + w[2] * imu[i].z + w[3]
        val from = gpsLaunchMs - 1000; val to = gpsLaunchMs + 500
        for (i in imu.indices) {
            val t = imu[i].tMs
            if (t < from) continue
            if (t > to) break
            if (fwd(i) > 0.1 && (1..7).all { k -> i + k < imu.size && fwd(i + k) > 0.05 }) return t.toDouble()
        }
        return null
    }

    /** Least-squares fit of GPS longitudinal acceleration (G) on the IMU axes: (wx, wy, wz, bias), or null if it doesn't fit. */
    fun forwardAxis(imu: List<Imu>, fixes: List<Fix>): DoubleArray? {
        val rows = ArrayList<DoubleArray>(); val ys = ArrayList<Double>()
        var j = 0
        for (i in 2 until fixes.size - 2 step 2) {
            val f = fixes[i]
            if (f.speedMps.isNaN() || f.speedMps < 1) continue
            val dt = (fixes[i + 2].tMs - fixes[i - 2].tMs) / 1000.0
            if (dt <= 0) continue
            while (j < imu.size - 1 && imu[j + 1].tMs <= f.tMs) j++
            if (abs(imu[j].tMs - f.tMs) > 60) continue
            rows.add(doubleArrayOf(imu[j].x, imu[j].y, imu[j].z, 1.0))
            ys.add((fixes[i + 2].speedMps - fixes[i - 2].speedMps) / dt / 9.81)
        }
        if (rows.size < 50) return null
        val a = Array(4) { r -> DoubleArray(4) { c -> rows.sumOf { it[r] * it[c] } } }
        val b = DoubleArray(4) { r -> rows.indices.sumOf { rows[it][r] * ys[it] } }
        for (i in 0 until 4) {   // Gaussian elimination with partial pivoting
            val p = (i until 4).maxBy { abs(a[it][i]) }
            a[i] = a[p].also { a[p] = a[i] }; b[i] = b[p].also { b[p] = b[i] }
            if (abs(a[i][i]) < 1e-12) return null
            for (r in 0 until 4) if (r != i) {
                val k = a[r][i] / a[i][i]
                for (c in 0 until 4) a[r][c] -= k * a[i][c]
                b[r] -= k * b[i]
            }
        }
        val w = DoubleArray(4) { b[it] / a[it][it] }
        val mean = ys.average()
        val ssTot = ys.sumOf { (it - mean) * (it - mean) }
        val ssRes = rows.indices.sumOf { k -> val p = (0 until 4).sumOf { w[it] * rows[k][it] }; (p - ys[k]) * (p - ys[k]) }
        return if (ssTot > 0 && 1 - ssRes / ssTot > 0.6) w else null
    }

    /** RaceBox motion log (`elapsed_ms,wall_ms,utc_ms,g_x,g_y,g_z,…`) on the GNSS clock, sorted. */
    fun readImuCsv(f: File): List<Imu> {
        if (!f.isFile) return emptyList()
        return f.useLines { ls ->
            ls.drop(1).mapNotNull { l ->
                val c = l.split(',')
                if (c.size < 6) null else runCatching { Imu(c[2].toLong(), c[3].toDouble(), c[4].toDouble(), c[5].toDouble()) }.getOrNull()
            }.filter { it.tMs > 0 }.toList()
        }.sortedBy { it.tMs }
    }

    /** Stage → start gate distance used until it's set for an event. */
    const val DEFAULT_START_DISTANCE_M = 6.0
    /** Below this a car is stopped (staged). */
    const val STOP_MPS = 0.3
    const val STAGE_BEHIND_M = 25.0
    const val STAGE_AHEAD_M = 10.0
    const val STAGE_SIDE_M = 10.0

    /**
     * Read a run's GPS log (`elapsed_ms,utc_ms,lat,lon,…`). Uses the GNSS time column when it's
     * present and moving forward for the whole run, else the phone's receive time.
     */
    fun readGpsCsv(f: File): List<Fix> {
        if (!f.isFile) return emptyList()
        val rows = f.useLines { lines ->
            lines.drop(1).mapNotNull { l ->
                val c = l.split(',')
                if (c.size < 4) null else runCatching {
                    arrayOf(c[0].toLong(), c[1].toLong(), c[2].toDouble(), c[3].toDouble(), c.getOrNull(4)?.toDoubleOrNull() ?: Double.NaN)
                }.getOrNull()
            }.toList()
        }
        val utcOk = rows.size >= 2 && rows.all { (it[1] as Long) > 0 } && rows.zipWithNext().all { (a, b) -> (b[1] as Long) >= (a[1] as Long) }
        return rows.map { Fix(if (utcOk) it[1] as Long else it[0] as Long, it[2] as Double, it[3] as Double, it[4] as Double) }
    }
}
