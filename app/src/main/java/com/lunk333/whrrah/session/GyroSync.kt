package com.lunk333.whrrah.session

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Lines a clip's video up with the run's data by cross-correlating rotation: the camera's own gyro
 * (1 kHz orientation quaternions from the clip's djmd track) against the RaceBox's rotation rates.
 *
 * Uses total angular speed (°/s), so it doesn't matter how the camera and the RaceBox are mounted
 * relative to each other. Coarse search over ±[searchMs] around the timestamp guess, then a 1 ms
 * refinement around the best peak.
 */
object GyroSync {
    /** [videoStartMs]: video t = 0 on the data's clock. [r]: Pearson correlation at the peak. */
    data class Result(val videoStartMs: Double, val r: Double, val peakRatio: Double, val overlapS: Double)

    private const val GRID_MS = 5.0
    private const val SMOOTH_MS = 40.0       // RaceBox reports at 25 Hz: compare like with like
    private const val QUAT_SPAN = 10         // angle between quats 10 samples (~10 ms) apart: less float noise
    private const val MIN_OVERLAP_S = 5.0

    /** Camera angular speed (°/s) on a [GRID_MS] grid from video t = 0, from raw quaternions. */
    fun videoSpeed(tMs: DoubleArray, w: DoubleArray, x: DoubleArray, y: DoubleArray, z: DoubleArray): DoubleArray {
        if (tMs.size <= QUAT_SPAN) return DoubleArray(0)
        val end = tMs.last()
        val n = (end / GRID_MS).toInt() + 1
        val sum = DoubleArray(n); val cnt = IntArray(n)
        for (i in 0 until tMs.size - QUAT_SPAN) {
            val j = i + QUAT_SPAN
            val dt = (tMs[j] - tMs[i]) / 1000.0
            if (dt <= 0) continue
            val dot = abs(w[i] * w[j] + x[i] * x[j] + y[i] * y[j] + z[i] * z[j])
            val deg = Math.toDegrees(2 * acos(min(1.0, dot))) / dt
            val k = (((tMs[i] + tMs[j]) / 2) / GRID_MS).toInt()
            if (k in 0 until n) { sum[k] += deg; cnt[k]++ }
        }
        val raw = DoubleArray(n) { if (cnt[it] > 0) sum[it] / cnt[it] else Double.NaN }
        fill(raw)
        return boxSmooth(raw, (SMOOTH_MS / GRID_MS).toInt())
    }

    /** RaceBox angular speed (°/s) from its roll/pitch/yaw rates. */
    fun dataSpeed(roll: DoubleArray, pitch: DoubleArray, yaw: DoubleArray) =
        DoubleArray(roll.size) { sqrt(roll[it] * roll[it] + pitch[it] * pitch[it] + yaw[it] * yaw[it]) }

    /**
     * Find video t = 0 on the data clock. [video] is [videoSpeed]'s grid; [dataT]/[dataW] the RaceBox
     * samples (ms, °/s); [estimateMs] the timestamp-based guess of video t = 0 on the data clock.
     */
    fun align(video: DoubleArray, dataT: DoubleArray, dataW: DoubleArray, estimateMs: Double, searchMs: Double = 10_000.0): Result? {
        if (video.size < 100 || dataT.size < 50) return null
        // Coarse: 20 ms steps over the search window.
        var best = Double.NEGATIVE_INFINITY; var bestL = 0.0
        val coarse = ArrayList<Pair<Double, Double>>()
        var l = -searchMs
        while (l <= searchMs) {
            val r = corr(video, dataT, dataW, estimateMs + l)
            if (r != null) { coarse.add(l to r.first); if (r.first > best) { best = r.first; bestL = l } }
            l += 20.0
        }
        if (best == Double.NEGATIVE_INFINITY) return null
        // Fine: 1 ms steps around the peak.
        var fineBest = best; var fineL = bestL
        var f = bestL - 40
        while (f <= bestL + 40) {
            corr(video, dataT, dataW, estimateMs + f)?.let { if (it.first > fineBest) { fineBest = it.first; fineL = f } }
            f += 1.0
        }
        // How unique the peak is: best correlation more than 1 s away from it.
        val other = coarse.filter { abs(it.first - fineL) > 1000 }.maxOfOrNull { it.second } ?: 0.0
        val overlap = corr(video, dataT, dataW, estimateMs + fineL)!!.second
        return Result(estimateMs + fineL, fineBest, if (other > 0) fineBest / other else Double.POSITIVE_INFINITY, overlap)
    }

    /** Pearson r between data samples and the video at (dataT − videoStart), and the overlap in s. */
    private fun corr(video: DoubleArray, dataT: DoubleArray, dataW: DoubleArray, videoStart: Double): Pair<Double, Double>? {
        var n = 0; var sx = 0.0; var sy = 0.0; var sxx = 0.0; var syy = 0.0; var sxy = 0.0
        var tFirst = Double.NaN; var tLast = Double.NaN
        for (i in dataT.indices) {
            val g = (dataT[i] - videoStart) / GRID_MS
            if (g < 0 || g >= video.size - 1) continue
            val k = g.toInt(); val fr = g - k
            val v = video[k] * (1 - fr) + video[k + 1] * fr
            val d = dataW[i]
            n++; sx += v; sy += d; sxx += v * v; syy += d * d; sxy += v * d
            if (tFirst.isNaN()) tFirst = dataT[i]; tLast = dataT[i]
        }
        val overlapS = if (n > 0) (tLast - tFirst) / 1000.0 else 0.0
        if (overlapS < MIN_OVERLAP_S) return null
        val cov = sxy - sx * sy / n
        val vx = sxx - sx * sx / n; val vy = syy - sy * sy / n
        if (vx <= 0 || vy <= 0) return null
        return cov / sqrt(vx * vy) to overlapS
    }

    private fun fill(a: DoubleArray) {
        var last = a.firstOrNull { !it.isNaN() } ?: 0.0
        for (i in a.indices) if (a[i].isNaN()) a[i] = last else last = a[i]
    }

    private fun boxSmooth(a: DoubleArray, width: Int): DoubleArray {
        if (width <= 1) return a
        val half = width / 2
        val pre = DoubleArray(a.size + 1)
        for (i in a.indices) pre[i + 1] = pre[i] + a[i]
        return DoubleArray(a.size) { i ->
            val lo = maxOf(0, i - half); val hi = minOf(a.size, i + half + 1)
            (pre[hi] - pre[lo]) / (hi - lo)
        }
    }
}
