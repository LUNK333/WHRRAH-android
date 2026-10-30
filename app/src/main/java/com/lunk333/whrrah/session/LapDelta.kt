package com.lunk333.whrrah.session

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/*
 * Live lap delta (docs: "WHRRAH Android — Live Lap Delta Spec"), adapted to autocross: separate
 * start and finish lines on an open course, one timed lap per run, no out-lap and no wrap-around.
 *
 * delta = time into this run − time the reference (the session's best run) took to reach the same
 * distance along the course. + = behind, − = ahead. Each fix is projected onto the reference trace
 * (resampled to 1 m) inside a small window around the last match, so it can't snap to a parallel
 * section of the course.
 */

/** Equirectangular lat/lon → local metres around a fixed origin (fine at course scale). */
class LocalProjector(val lat0: Double, val lon0: Double) {
    private val kx = cos(lat0 * PI / 180) * R * PI / 180
    private val ky = R * PI / 180
    fun toX(lon: Double) = (lon - lon0) * kx
    fun toY(lat: Double) = (lat - lat0) * ky

    companion object { const val R = 6_371_008.8 }
}

/**
 * The reference run: one point per metre of course from the start line (t = 0) to the finish line
 * (t = [lapTime]), each with the time the reference run reached it. Times in seconds.
 */
class ReferenceLap(
    val xs: DoubleArray, val ys: DoubleArray,
    val ss: DoubleArray, val ts: DoubleArray,
    val length: Double, val lapTime: Double,
    /** No fix gap over 0.5 s inside the lap — a rough lap isn't trusted as a reference. */
    val valid: Boolean,
) {
    val n get() = xs.size

    /** Reference time at distance [s], interpolated. */
    fun timeAt(s: Double): Double {
        if (s <= 0) return 0.0
        if (s >= length) return lapTime
        var i = min(n - 2, floor(s).toInt())          // ss[i] = i, except the last point (= length)
        while (i > 0 && ss[i] > s) i--
        while (i < n - 2 && ss[i + 1] < s) i++
        val span = ss[i + 1] - ss[i]
        val u = if (span > 0) (s - ss[i]) / span else 0.0
        return ts[i] + u * (ts[i + 1] - ts[i])
    }

    companion object {
        const val MAX_GAP_MS = 500

        /**
         * From a run's fixes (ms on any clock) and its interpolated line crossings on the same clock.
         * Null if the lap has too few fixes to make a course.
         */
        fun build(fixes: List<LapTimer.Fix>, startMs: Double, finishMs: Double, proj: LocalProjector, stepM: Double = 1.0): ReferenceLap? {
            if (finishMs <= startMs || fixes.size < 2) return null
            fun interp(t: Double): Pair<Double, Double>? {
                val j = fixes.indexOfFirst { it.tMs >= t }
                if (j <= 0) return null
                val a = fixes[j - 1]; val b = fixes[j]
                val f = if (b.tMs > a.tMs) (t - a.tMs) / (b.tMs - a.tMs) else 0.0
                return (a.lat + f * (b.lat - a.lat)) to (a.lon + f * (b.lon - a.lon))
            }
            val p0 = interp(startMs) ?: return null
            val p1 = interp(finishMs) ?: return null
            val inside = fixes.filter { it.tMs > startMs && it.tMs < finishMs }
            val lat = ArrayList<Double>(); val lon = ArrayList<Double>(); val t = ArrayList<Double>()
            lat += p0.first; lon += p0.second; t += 0.0
            for (f in inside) { lat += f.lat; lon += f.lon; t += (f.tMs - startMs) / 1000.0 }
            lat += p1.first; lon += p1.second; t += (finishMs - startMs) / 1000.0
            val gaps = (listOf(startMs) + inside.map { it.tMs.toDouble() } + finishMs).zipWithNext { a, b -> b - a }
            val valid = gaps.all { it <= MAX_GAP_MS }

            // Light smoothing (raw jitter inflates the distance), the line points kept exact.
            val x = smooth(lat.indices.map { proj.toX(lon[it]) })
            val y = smooth(lat.indices.map { proj.toY(lat[it]) })
            val cum = DoubleArray(x.size)
            for (i in 1 until x.size) cum[i] = cum[i - 1] + hypot(x[i] - x[i - 1], y[i] - y[i - 1])
            val length = cum.last()
            if (length < 2 * stepM) return null

            // Resample every stepM, plus the finish line itself as the last point.
            val m = floor(length / stepM).toInt() + 1
            val count = if (m * stepM - stepM < length - 1e-6) m + 1 else m
            val xs = DoubleArray(count); val ys = DoubleArray(count); val ss = DoubleArray(count); val ts = DoubleArray(count)
            var j = 0
            for (k in 0 until count) {
                val s = if (k == count - 1) length else k * stepM
                while (j < cum.size - 2 && cum[j + 1] < s) j++
                val span = cum[j + 1] - cum[j]
                val u = if (span > 0) ((s - cum[j]) / span).coerceIn(0.0, 1.0) else 0.0
                xs[k] = x[j] + u * (x[j + 1] - x[j]); ys[k] = y[j] + u * (y[j + 1] - y[j])
                ss[k] = s; ts[k] = t[j] + u * (t[j + 1] - t[j])
            }
            return ReferenceLap(xs, ys, ss, ts, length, t.last(), valid)
        }

        private fun hypot(a: Double, b: Double) = sqrt(a * a + b * b)

        private fun smooth(v: List<Double>, window: Int = 5): List<Double> {
            val half = window / 2
            if (v.size < window) return v
            return v.indices.map { i -> if (i < half || i >= v.size - half) v[i] else (i - half..i + half).sumOf { v[it] } / window }
        }
    }
}

/**
 * What the display shows. [delta] is smoothed (null = no reference yet, or before the start);
 * [onTrack] false = the last fix was too far off the reference line, [delta] is the held value.
 * [final] = crossed the finish: [delta] is then the exact lap-time difference.
 */
data class DeltaState(
    val lapElapsed: Double?,
    val delta: Double?,
    /** s per s over the last ~0.5 s; − = gaining. */
    val deltaRate: Double?,
    val s: Double?,
    val onTrack: Boolean,
    val final: Boolean = false,
)

/**
 * Feeds one run's fixes (in order, receiver time) through the start and finish lines and the
 * reference. Not thread-safe: call from one thread.
 */
class DeltaTracker(
    private val reference: ReferenceLap?,
    private val proj: LocalProjector,
    private val start: TrackLine?,
    private val finish: TrackLine?,
) {
    var state = DeltaState(null, null, null, null, onTrack = true); private set

    private var running = false
    private var finished = false
    private var tStartMs = 0.0
    private var lastIdx = 0
    private var sPrev = 0.0
    private var ema: Double? = null
    private var offSinceMs: Double? = null
    private var lastFixMs: Double? = null
    private var prevLat = 0.0; private var prevLon = 0.0
    // Last RATE_N (t, ema) samples, a ring, for the delta rate.
    private val rt = DoubleArray(RATE_N); private val rv = DoubleArray(RATE_N)
    private var rn = 0; private var rHead = 0

    fun onFix(tMs: Double, lat: Double, lon: Double, speedMps: Double?) {
        val prevT = lastFixMs
        lastFixMs = tMs
        val hadPrev = prevT != null
        val pLat = prevLat; val pLon = prevLon
        prevLat = lat; prevLon = lon
        if (finished || !hadPrev) return

        val pair = listOf(LapTimer.Fix(prevT!!.toLong(), pLat, pLon), LapTimer.Fix(tMs.toLong(), lat, lon))
        // Finish first (a course whose finish is on its start line still finishes), then the start —
        // again re-arms it (staging creep), as the live timer.
        if (running) finish?.let { fin ->
            LapTimer.crossings(pair, fin).firstOrNull()?.let { c ->
                val lapS = (c.tMs - tStartMs) / 1000
                finished = true
                state = DeltaState(lapS, reference?.let { lapS - it.lapTime }, null, reference?.length, onTrack = true, final = true)
                return
            }
        }
        start?.let { st -> LapTimer.crossings(pair, st).firstOrNull()?.let { c -> arm(c.tMs); return } }
        if (!running) return
        val elapsed = (tMs - tStartMs) / 1000
        val ref = reference ?: run { state = DeltaState(elapsed, null, null, null, onTrack = true); return }

        val px = proj.toX(lon); val py = proj.toY(lat)
        val gapS = (tMs - prevT) / 1000
        val global = gapS > REACQUIRE_S || (offSinceMs?.let { tMs - it > REACQUIRE_S * 1000 } ?: false)
        val extra = if (gapS > 0.1 && speedMps != null) (speedMps * gapS * 1.5).toInt() else 0
        val lo = if (global) 0 else max(0, lastIdx - BACK)
        val hi = if (global) ref.n - 2 else min(ref.n - 2, lastIdx + AHEAD + extra)
        var bestI = -1; var bestU = 0.0; var bestD2 = Double.MAX_VALUE
        for (i in lo..hi) {
            val ax = ref.xs[i]; val ay = ref.ys[i]
            val bx = ref.xs[i + 1] - ax; val by = ref.ys[i + 1] - ay
            val len2 = bx * bx + by * by
            val u = if (len2 > 0) (((px - ax) * bx + (py - ay) * by) / len2).coerceIn(0.0, 1.0) else 0.0
            val dx = px - (ax + u * bx); val dy = py - (ay + u * by)
            val d2 = dx * dx + dy * dy
            if (d2 < bestD2) { bestD2 = d2; bestI = i; bestU = u }
        }
        if (bestI < 0 || bestD2 > LATERAL_M * LATERAL_M) {
            if (offSinceMs == null) offSinceMs = tMs
            state = DeltaState(elapsed, ema, null, sPrev, onTrack = false)
            return
        }
        offSinceMs = null
        lastIdx = bestI
        var s = ref.ss[bestI] + bestU * (ref.ss[bestI + 1] - ref.ss[bestI])
        // No going backwards (jitter at low speed).
        if (s < sPrev - BACKWARD_M) s = sPrev - BACKWARD_M
        sPrev = s
        val raw = elapsed - ref.timeAt(s)
        val e = ema?.let { it + ALPHA * (raw - it) } ?: raw
        ema = e
        rt[rHead] = tMs / 1000; rv[rHead] = e; rHead = (rHead + 1) % RATE_N; if (rn < RATE_N) rn++
        state = DeltaState(elapsed, e, rate(), s, onTrack = true)
    }

    private fun arm(tCrossMs: Double) {
        running = true; tStartMs = tCrossMs
        lastIdx = 0; sPrev = 0.0; ema = null; offSinceMs = null; rn = 0; rHead = 0
        state = DeltaState(0.0, if (reference != null) 0.0 else null, null, 0.0, onTrack = true)
    }

    /** Least-squares slope of the smoothed delta over the ring. */
    private fun rate(): Double? {
        if (rn < 3) return null
        var mt = 0.0; var mv = 0.0
        for (k in 0 until rn) { mt += rt[k]; mv += rv[k] }
        mt /= rn; mv /= rn
        var num = 0.0; var den = 0.0
        for (k in 0 until rn) { val dt = rt[k] - mt; num += dt * (rv[k] - mv); den += dt * dt }
        return if (den > 0) num / den else null
    }

    companion object {
        const val LATERAL_M = 15.0
        const val BACKWARD_M = 2.0
        const val BACK = 10
        const val AHEAD = 40
        const val ALPHA = 0.3
        const val RATE_N = 12
        /** Off-track or a dropout this long (s) → one global search to reacquire. */
        const val REACQUIRE_S = 1.0
    }
}
