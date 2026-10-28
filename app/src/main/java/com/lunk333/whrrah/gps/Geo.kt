package com.lunk333.whrrah.gps

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Small-distance geodesy on a sphere — plenty for lines a few metres wide and courses ~1 km. */
object Geo {
    const val EARTH_R = 6_371_008.8
    const val FT = 0.3048

    data class LatLon(val lat: Double, val lon: Double)

    /** Point [distM] from (lat, lon) along [bearingDeg] (0 = north, 90 = east). */
    fun offset(lat: Double, lon: Double, bearingDeg: Double, distM: Double): LatLon {
        val d = distM / EARTH_R
        val b = Math.toRadians(bearingDeg)
        val p1 = Math.toRadians(lat)
        val l1 = Math.toRadians(lon)
        val p2 = asin(sin(p1) * cos(d) + cos(p1) * sin(d) * cos(b))
        val l2 = l1 + atan2(sin(b) * sin(d) * cos(p1), cos(d) - sin(p1) * sin(p2))
        return LatLon(Math.toDegrees(p2), (Math.toDegrees(l2) + 540) % 360 - 180)
    }

    /** Initial bearing from 1 to 2, degrees 0..360. */
    fun bearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1); val p2 = Math.toRadians(lat2)
        val dl = Math.toRadians(lon2 - lon1)
        val y = sin(dl) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return (Math.toDegrees(atan2(y, x)) + 360) % 360
    }

    /** Great-circle distance in metres. */
    fun distance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1); val p2 = Math.toRadians(lat2)
        val dp = p2 - p1; val dl = Math.toRadians(lon2 - lon1)
        val a = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 2 * EARTH_R * atan2(sqrt(a), sqrt(1 - a))
    }

    /** Local east/north metres of a point relative to an origin (equirectangular; fine at course scale). */
    fun toLocal(originLat: Double, originLon: Double, lat: Double, lon: Double): Pair<Double, Double> {
        val east = Math.toRadians(lon - originLon) * EARTH_R * cos(Math.toRadians(originLat))
        val north = Math.toRadians(lat - originLat) * EARTH_R
        return east to north
    }
}

/** Direction of travel for pinning a start/finish line, from recent fixes. */
object HeadingEstimator {
    /** GPS-reported heading is trusted above this speed (it's noise when walking slowly or standing). */
    const val MIN_SPEED_MPS = 0.5
    /** Otherwise: bearing over the recent track, if it covers at least this far... */
    const val MIN_TRACK_M = 5.0
    /** ...within this window. */
    const val WINDOW_MS = 30_000L

    data class Estimate(val headingDeg: Double, val how: String)

    /** [fixes] oldest first; [nowMs] on the same clock as [GpsFix.elapsedMs]. Null if there's nothing to go on. */
    fun estimate(fixes: List<GpsFix>, nowMs: Long): Estimate? {
        val last = fixes.lastOrNull() ?: return null
        val h = last.headingDeg
        if (h != null && (last.speedMps ?: 0f) >= MIN_SPEED_MPS && nowMs - last.elapsedMs < 2000)
            return Estimate(((h % 360) + 360) % 360.0, "GPS heading at %.1f m/s".format(last.speedMps))
        // Walking pace: use the direction of the last stretch of movement — the newest fix that is at
        // least MIN_TRACK_M from the current position, so standing still on the line afterwards is fine.
        val recent = fixes.filter { nowMs - it.elapsedMs <= WINDOW_MS }
        val from = recent.lastOrNull { Geo.distance(it.lat, it.lon, last.lat, last.lon) >= MIN_TRACK_M } ?: return null
        val d = Geo.distance(from.lat, from.lon, last.lat, last.lon)
        return Estimate(Geo.bearing(from.lat, from.lon, last.lat, last.lon), "your last %.0f m of movement".format(d))
    }
}
