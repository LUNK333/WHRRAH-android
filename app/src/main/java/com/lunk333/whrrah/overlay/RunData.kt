package com.lunk333.whrrah.overlay

import java.io.File
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min

/**
 * One run's data for the overlay widgets — the phone's counterpart of desktop WHRRAH's `DataLog`.
 *
 * Channels use the desktop's (AiM) names so a desktop layout binds without changes:
 * - from the GPS log: `GPS Latitude`, `GPS Longitude`, `GPS Speed` (mph, as AiM CSVs),
 *   `GPS Heading` (deg), and the GPS-derived `GPS LonAcc` / `GPS LatAcc` (G; + = accelerating /
 *   turning right, as in AiM logs — the desktop G Meter flips both by default);
 * - from the RaceBox motion log: `InlineAcc`, `LateralAcc`, `VerticalAcc` (G) and `RollRate`,
 *   `PitchRate`, `YawRate` (deg/s) in the car's own axes whatever way the RaceBox is mounted
 *   ([vehicleFrame]): + = accelerating / pushed right (right turn) / up; roll + = right side down,
 *   pitch + = nose up, yaw + = turning right.
 *
 * Time is seconds on the run's data clock (GNSS UTC ms / 1000 with the RaceBox), the same clock as
 * the run's line crossings and its video sync.
 *
 * Laps are an autocross run, not a session of circuit laps: lap 0 before the start line, lap 1 on
 * course, then finished. "Time" counts from the start and holds the lap time after the finish;
 * "Last" / "Best" come from this session's earlier runs (plus this one once it's finished); "Lap"
 * is the run number.
 */
class RunData(
    val timestamps: DoubleArray,
    val channels: Map<String, DoubleArray>,
    /** Start / finish line crossings (s), when the run was timed. */
    val startS: Double?,
    val finishS: Double?,
    val runNumber: Int,
    /** Lap times (s) of this session's runs before this one. */
    val earlierLapsS: List<Double>,
) {
    /** Reference outline for the Track Map: this run's start→finish, smoothed, ≤ ~200 points. */
    val track: List<Pair<Double, Double>>
    /** (latMin, latMax, lonMin, lonMax) of [track], or null with no GPS. */
    val trackBounds: DoubleArray?
    private val trackStart: Pair<Double, Double>?

    init {
        val lat = channels[LAT]; val lon = channels[LON]
        if (lat == null || lon == null || timestamps.isEmpty()) {
            track = emptyList(); trackBounds = null; trackStart = null
        } else {
            var idx = timestamps.indices.filter { (startS == null || timestamps[it] >= startS) && (finishS == null || timestamps[it] < finishS) }
            if (idx.isEmpty()) idx = timestamps.indices.toList()
            val sl = smooth(idx.map { lat[it] }); val so = smooth(idx.map { lon[it] })
            val step = max(1, sl.size / 200)
            track = (sl.indices step step).map { sl[it] to so[it] }
            trackBounds = doubleArrayOf(sl.min(), sl.max(), so.min(), so.max())
            trackStart = sl[0] to so[0]
        }
    }

    fun has(channel: String) = channel in channels

    /** Linear interpolation, clamped to the ends; 0 for an unknown channel (as desktop). */
    fun valueAt(channel: String, t: Double): Double {
        val v = channels[channel] ?: return 0.0
        val ts = timestamps
        if (ts.isEmpty()) return 0.0
        if (t <= ts[0]) return v[0]
        if (t >= ts.last()) return v.last()
        var lo = 0; var hi = ts.size - 1
        while (lo < hi - 1) { val mid = (lo + hi) / 2; if (ts[mid] <= t) lo = mid else hi = mid }
        val a = if (ts[hi] != ts[lo]) (t - ts[lo]) / (ts[hi] - ts[lo]) else 0.0
        return v[lo] + a * (v[hi] - v[lo])
    }

    /** 0 before the start line, 1 on course, 2 after the finish. */
    fun lapNumberAt(t: Double): Int = when {
        startS == null || t < startS -> 0
        finishS == null || t < finishS -> 1
        else -> 2
    }

    /** Lap Timer "Time": counting from the start, held at the lap time after the finish; null untimed. */
    fun lapTimeAt(t: Double): Double? = when {
        startS == null -> null
        t < startS -> 0.0
        finishS != null && t >= finishS -> finishS - startS
        else -> t - startS
    }

    private fun lapsSoFar(t: Double): List<Double> =
        if (startS != null && finishS != null && t >= finishS) earlierLapsS + (finishS - startS) else earlierLapsS

    fun lastLapAt(t: Double): Double? = lapsSoFar(t).lastOrNull()
    fun bestLapAt(t: Double): Double? = lapsSoFar(t).minOrNull()

    /** Track Map dot: held at the start of the outline before the start line (as desktop's outlap). */
    fun positionAt(t: Double): Pair<Double, Double>? {
        if (!has(LAT) || !has(LON)) return null
        if (lapNumberAt(t) == 0 && trackStart != null) return trackStart
        return valueAt(LAT, t) to valueAt(LON, t)
    }

    /**
     * (fractions 0..1 across [t0, t1], values) at [n] even samples, dropping any outside the log
     * rather than clamping (desktop `trace_values`). Null with no data.
     */
    fun traceValues(channel: String, t0: Double, t1: Double, n: Int): Pair<DoubleArray, DoubleArray>? {
        if (channel !in channels || timestamps.size < 2 || t1 <= t0) return null
        val lo = max(t0, timestamps[0]); val hi = min(t1, timestamps.last())
        if (hi <= lo) return null
        val frac = DoubleArray(n); val vals = DoubleArray(n)
        for (i in 0 until n) {
            val t = lo + (hi - lo) * i / (n - 1).coerceAtLeast(1)
            frac[i] = (t - t0) / (t1 - t0); vals[i] = valueAt(channel, t)
        }
        return frac to vals
    }

    companion object {
        const val LAT = "GPS Latitude"
        const val LON = "GPS Longitude"
        const val SPEED = "GPS Speed"
        const val HEADING = "GPS Heading"
        const val LON_ACC = "GPS LonAcc"
        const val LAT_ACC = "GPS LatAcc"
        private const val MPH_PER_MPS = 2.236936
        private const val G = 9.80665
        /** Below this, GPS heading is noise — no turning G. */
        private const val MIN_TURN_SPEED_MPS = 2.0

        /**
         * Builds a run's data from its logs: [gps] (`elapsed_ms,utc_ms,lat,lon,speed_mps,heading_deg,…`)
         * and optionally [imu] (`elapsed_ms,wall_ms,utc_ms,g_x,g_y,g_z,roll_dps,pitch_dps,yaw_dps`).
         * Times in ms on the data clock.
         */
        /**
         * [offsetMs]: the session's timing calibration (added to every lap time). It's applied by
         * starting the clock that much earlier, so the running time and the time at the finish both
         * match the run's saved time.
         */
        fun load(gps: File?, imu: File?, startMs: Long?, finishMs: Long?, runNumber: Int, earlierLapsMs: List<Long>, offsetMs: Long = 0): RunData {
            val rows = gps?.takeIf { it.isFile }?.useLines { ls ->
                ls.drop(1).mapNotNull { l ->
                    val c = l.split(',')
                    if (c.size < 6) null else runCatching {
                        doubleArrayOf(c[0].toDouble(), c[1].toDouble(), c[2].toDouble(), c[3].toDouble(),
                            c[4].toDoubleOrNull() ?: Double.NaN, c[5].toDoubleOrNull() ?: Double.NaN)
                    }.getOrNull()
                }.toList()
            } ?: emptyList()
            // GNSS time when it's there and moving forward (as LapTimer.readGpsCsv), else the phone's.
            val utcOk = rows.size >= 2 && rows.all { it[1] > 0 } && rows.zipWithNext().all { (a, b) -> b[1] >= a[1] }
            val ts = DoubleArray(rows.size) { (if (utcOk) rows[it][1] else rows[it][0]) / 1000.0 }
            val ch = LinkedHashMap<String, DoubleArray>()
            if (rows.isNotEmpty()) {
                ch[LAT] = DoubleArray(rows.size) { rows[it][2] }
                ch[LON] = DoubleArray(rows.size) { rows[it][3] }
                val speed = DoubleArray(rows.size) { rows[it][4].takeUnless(Double::isNaN) ?: 0.0 }
                ch[SPEED] = DoubleArray(rows.size) { speed[it] * MPH_PER_MPS }
                // Logs from before the RaceBox heading was kept have none: work it out from the track.
                val logged = DoubleArray(rows.size) { rows[it][5] }
                val heading = fillHeading(if (logged.count { !it.isNaN() } > rows.size / 10) logged
                    else trackHeading(DoubleArray(rows.size) { rows[it][2] }, DoubleArray(rows.size) { rows[it][3] }, speed))
                ch[HEADING] = heading
                val (lonG, latG) = gpsAccel(ts, speed, heading)
                ch[LON_ACC] = lonG; ch[LAT_ACC] = latG
            }
            val data = RunData(ts, ch, startMs?.let { (it - offsetMs) / 1000.0 }, finishMs?.div(1000.0), runNumber, earlierLapsMs.map { it / 1000.0 })
            return if (imu != null && imu.isFile && ts.isNotEmpty()) data.withImu(imu) else data
        }

        /** RaceBox motion channels resampled onto the GPS timestamps (both logs come from the same packets). */
        private fun RunData.withImu(f: File): RunData {
            val rows = f.useLines { ls ->
                ls.drop(1).mapNotNull { l ->
                    val c = l.split(',')
                    if (c.size < 9) null else runCatching { DoubleArray(7) { i -> if (i == 0) c[2].toDouble() / 1000 else c[2 + i].toDouble() } }.getOrNull()
                }.toList()
            }.filter { it[0] > 0 }.sortedBy { it[0] }
            if (rows.size < 2) return this
            val its = DoubleArray(rows.size) { rows[it][0] }
            // Into the car's axes when the mounting can be worked out from the run; else the raw axes.
            val frame = vehicleFrame(rows)
            val vehicle = rows.map { r ->
                if (frame == null) r.copyOf() else {
                    val (fw, rt, up) = frame
                    val a = doubleArrayOf(r[1], r[2], r[3]); val w = doubleArrayOf(r[4], r[5], r[6])
                    doubleArrayOf(r[0], dot(a, fw), dot(a, rt), dot(a, up), dot(w, fw), dot(w, rt), -dot(w, up))
                }
            }
            val names = listOf("InlineAcc", "LateralAcc", "VerticalAcc", "RollRate", "PitchRate", "YawRate")
            val ch = LinkedHashMap(channels)
            names.forEachIndexed { k, name ->
                val src = RunData(its, mapOf(name to DoubleArray(rows.size) { vehicle[it][k + 1] }), null, null, 0, emptyList())
                ch[name] = DoubleArray(timestamps.size) { src.valueAt(name, timestamps[it]) }
            }
            return RunData(timestamps, ch, startS, finishS, runNumber, earlierLapsS)
        }

        /**
         * The RaceBox's mounting, from the run itself: "up" is the average acceleration reading while the
         * car is stopped (gravity), "forward" the axis whose acceleration matches the GPS speed changes
         * (made square to up), "right" = forward × up. Unit vectors in the RaceBox's axes, or null when the
         * run has no stop or too little driving to tell.
         * [rows]: (t s, gx, gy, gz, roll, pitch, yaw) — RaceBox axes.
         */
        private fun RunData.vehicleFrame(rows: List<DoubleArray>): Triple<DoubleArray, DoubleArray, DoubleArray>? {
            val speed = channels[SPEED] ?: return null
            val lat = channels[LAT] ?: return null
            val lon = channels[LON] ?: return null
            // Up: mean reading while stopped (≥ 1 s of samples).
            val still = rows.filter { valueAt(SPEED, it[0]) / MPH_PER_MPS < 0.2 }
            if (still.size < 25) return null
            val up = norm(doubleArrayOf(still.sumOf { it[1] }, still.sumOf { it[2] }, still.sumOf { it[3] })) ?: return null
            // Forward: least-squares fit of GPS longitudinal acceleration on the three axes.
            val fixes = timestamps.indices.map { i ->
                com.lunk333.whrrah.session.LapTimer.Fix(Math.round(timestamps[i] * 1000), lat[i], lon[i], speed[i] / MPH_PER_MPS)
            }
            val imu = rows.map { com.lunk333.whrrah.session.LapTimer.Imu(Math.round(it[0] * 1000), it[1], it[2], it[3]) }
            val w = com.lunk333.whrrah.session.LapTimer.forwardAxis(imu, fixes) ?: return null
            val raw = doubleArrayOf(w[0], w[1], w[2])
            val fw = norm(doubleArrayOf(raw[0] - dot(raw, up) * up[0], raw[1] - dot(raw, up) * up[1], raw[2] - dot(raw, up) * up[2])) ?: return null
            val rt = cross(fw, up)
            return Triple(fw, rt, up)
        }

        private fun dot(a: DoubleArray, b: DoubleArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
        private fun cross(a: DoubleArray, b: DoubleArray) =
            doubleArrayOf(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])
        private fun norm(v: DoubleArray): DoubleArray? {
            val l = Math.sqrt(dot(v, v))
            return if (l < 1e-6) null else doubleArrayOf(v[0] / l, v[1] / l, v[2] / l)
        }

        /**
         * Direction of travel from the positions themselves (bearing from 3 fixes back to 3 ahead),
         * where the car is moving at least [MIN_TURN_SPEED_MPS]; NaN elsewhere.
         */
        internal fun trackHeading(lat: DoubleArray, lon: DoubleArray, speedMps: DoubleArray): DoubleArray {
            val n = lat.size
            return DoubleArray(n) { i ->
                val a = maxOf(0, i - 3); val b = minOf(n - 1, i + 3)
                if (b <= a || speedMps[i] < MIN_TURN_SPEED_MPS) Double.NaN else {
                    val y = (lat[b] - lat[a]) * 111_320.0
                    val x = (lon[b] - lon[a]) * 111_320.0 * kotlin.math.cos(Math.toRadians(lat[i]))
                    if (x * x + y * y < 0.25) Double.NaN else (Math.toDegrees(kotlin.math.atan2(x, y)) + 360) % 360
                }
            }
        }

        /** The RaceBox leaves heading blank when stationary: carry the last known one (else 0). */
        private fun fillHeading(h: DoubleArray): DoubleArray {
            var last = h.firstOrNull { !it.isNaN() } ?: 0.0
            return DoubleArray(h.size) { i -> if (h[i].isNaN()) last else h[i].also { last = it } }
        }

        /**
         * Longitudinal G = d(speed)/dt, lateral G = speed × d(heading)/dt (+ = turning right, heading
         * increasing clockwise), each from a centred difference over ±[HALF] samples and then smoothed —
         * 25 Hz GPS is too jittery to difference sample-to-sample.
         */
        internal fun gpsAccel(ts: DoubleArray, speedMps: DoubleArray, headingDeg: DoubleArray): Pair<DoubleArray, DoubleArray> {
            val n = ts.size
            val lon = DoubleArray(n); val lat = DoubleArray(n)
            val unwrapped = DoubleArray(n)
            for (i in 0 until n) {
                unwrapped[i] = if (i == 0) headingDeg[0] else {
                    var d = headingDeg[i] - headingDeg[i - 1]
                    while (d > 180) d -= 360
                    while (d < -180) d += 360
                    unwrapped[i - 1] + d
                }
            }
            val sp = smooth(speedMps.toList(), 5)
            for (i in 0 until n) {
                val a = max(0, i - HALF); val b = min(n - 1, i + HALF)
                val dt = ts[b] - ts[a]
                if (b <= a || dt <= 0) continue
                lon[i] = (sp[b] - sp[a]) / dt / G
                val v = sp[i]
                if (v >= MIN_TURN_SPEED_MPS) lat[i] = v * ((unwrapped[b] - unwrapped[a]) / dt * PI / 180) / G
            }
            return smooth(lon.toList(), 5).toDoubleArray() to smooth(lat.toList(), 5).toDoubleArray()
        }

        private const val HALF = 3

        /** Centred moving average with the raw values kept at the edges (desktop `_smooth`). */
        internal fun smooth(v: List<Double>, window: Int = 5): List<Double> {
            if (v.size < window) return v
            val half = window / 2
            return v.indices.map { i -> if (i < half || i >= v.size - half) v[i] else (i - half..i + half).sumOf { v[it] } / window }
        }
    }
}
