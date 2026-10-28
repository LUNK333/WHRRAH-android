package com.lunk333.whrrah.gps

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * The course-walk trace as a polyline you can measure along (arc length, metres), for sliding a
 * start/finish line along the course. Works in local east/north metres around the first point.
 */
class TrackPath(points: List<Geo.LatLon>) {
    private val oLat = points.firstOrNull()?.lat ?: 0.0
    private val oLon = points.firstOrNull()?.lon ?: 0.0
    private val x: DoubleArray
    private val y: DoubleArray
    /** Cumulative distance along the path at each point. */
    private val s: DoubleArray
    val length: Double

    init {
        val local = points.map { Geo.toLocal(oLat, oLon, it.lat, it.lon) }
        x = DoubleArray(local.size) { local[it].first }
        y = DoubleArray(local.size) { local[it].second }
        s = DoubleArray(local.size)
        for (i in 1 until local.size) s[i] = s[i - 1] + hypot(x[i] - x[i - 1], y[i] - y[i - 1])
        length = s.lastOrNull() ?: 0.0
    }

    val usable: Boolean get() = x.size >= 2 && length >= 2.0

    /** Nearest point on the path: (arc length there, signed sideways offset — + is right of travel, distance). */
    data class Projection(val along: Double, val lateral: Double, val distance: Double)

    fun project(p: Geo.LatLon): Projection? {
        if (!usable) return null
        val (px, py) = Geo.toLocal(oLat, oLon, p.lat, p.lon)
        var best: Projection? = null
        for (i in 0 until x.size - 1) {
            val dx = x[i + 1] - x[i]; val dy = y[i + 1] - y[i]
            val len2 = dx * dx + dy * dy
            if (len2 == 0.0) continue
            val t = (((px - x[i]) * dx + (py - y[i]) * dy) / len2).coerceIn(0.0, 1.0)
            val cx = x[i] + t * dx; val cy = y[i] + t * dy
            val d = hypot(px - cx, py - cy)
            if (best == null || d < best.distance) {
                val segLen = Math.sqrt(len2)
                // Right of travel: cross product of the segment direction with (point - closest) < 0.
                val cross = dx * (py - cy) - dy * (px - cx)
                best = Projection(s[i] + t * segLen, if (cross < 0) d else -d, d)
            }
        }
        return best
    }

    /** Point at [along] metres (clamped to the path), in local coordinates. */
    private fun localAt(along: Double): Pair<Double, Double> {
        val a = along.coerceIn(0.0, length)
        var i = s.indexOfLast { it <= a }.coerceIn(0, x.size - 2)
        while (i < x.size - 2 && s[i + 1] - s[i] == 0.0) i++
        val seg = s[i + 1] - s[i]
        val t = if (seg == 0.0) 0.0 else (a - s[i]) / seg
        return (x[i] + t * (x[i + 1] - x[i])) to (y[i] + t * (y[i + 1] - y[i]))
    }

    /** Direction of the path at [along], degrees from north, averaged over ±[windowM] to smooth GPS wobble. */
    fun headingAt(along: Double, windowM: Double = 5.0): Double {
        val (ax, ay) = localAt(along - windowM)
        val (bx, by) = localAt(along + windowM)
        return (Math.toDegrees(atan2(bx - ax, by - ay)) + 360) % 360
    }

    /**
     * The point [lateral] metres to the right of the path at [along] (right of the direction
     * [headingDeg]), as lat/lon.
     */
    fun pointAt(along: Double, lateral: Double, headingDeg: Double): Geo.LatLon {
        val (cx, cy) = localAt(along)
        val r = Math.toRadians(headingDeg + 90)
        val ex = cx + lateral * sin(r)
        val ny = cy + lateral * cos(r)
        val lat = oLat + Math.toDegrees(ny / Geo.EARTH_R)
        val lon = oLon + Math.toDegrees(ex / (Geo.EARTH_R * cos(Math.toRadians(oLat))))
        return Geo.LatLon(lat, lon)
    }

    companion object {
        /** A line set further than this from the walk isn't on it; nudge it in a straight line instead. */
        const val MAX_SNAP_M = 15.0
    }
}
