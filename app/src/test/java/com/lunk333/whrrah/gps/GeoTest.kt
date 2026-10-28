package com.lunk333.whrrah.gps

import com.lunk333.whrrah.session.TrackLine
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoTest {
    private val lat0 = 42.0
    private val lon0 = -71.5

    private fun angleDiff(a: Double, b: Double) = ((a - b + 540) % 360) - 180

    @Test
    fun offsetDistanceAndBearingAgree() {
        for (b in listOf(0.0, 37.0, 90.0, 181.0, 300.0)) {
            val p = Geo.offset(lat0, lon0, b, 50.0)
            assertEquals(50.0, Geo.distance(lat0, lon0, p.lat, p.lon), 1e-6)
            assertEquals(0.0, angleDiff(b, Geo.bearing(lat0, lon0, p.lat, p.lon)), 1e-6)
        }
    }

    @Test
    fun lineIsWidthLongCentredAndPerpendicularToHeading() {
        val width = 20 * Geo.FT
        for (h in listOf(0.0, 45.0, 123.4, 270.0)) {
            val (left, right) = TrackLine(lat0, lon0, h, width, 1f).endpoints()!!
            assertEquals(width, Geo.distance(left.lat, left.lon, right.lat, right.lon), 1e-6)
            assertEquals(width / 2, Geo.distance(lat0, lon0, left.lat, left.lon), 1e-6)
            assertEquals(width / 2, Geo.distance(lat0, lon0, right.lat, right.lon), 1e-6)
            // left→right runs at heading + 90°: square to the direction of travel.
            assertEquals(0.0, angleDiff(h + 90, Geo.bearing(left.lat, left.lon, right.lat, right.lon)), 1e-3)
        }
        assertNull(TrackLine(lat0, lon0, null, width, null).endpoints())
    }

    @Test
    fun oldPointOnlyLinesLoadWithoutHeading() {
        val l = TrackLine.fromJson(JSONObject("""{"lat":42.1,"lon":-71.2,"accuracyM":5}"""))!!
        assertNull(l.headingDeg)
        assertEquals(20 * Geo.FT, l.widthM, 1e-9)
    }

    private fun fix(t: Long, p: Geo.LatLon, speed: Float? = null, heading: Float? = null) =
        GpsFix(t, 0, p.lat, p.lon, speed, heading, 1f)

    @Test
    fun movingUsesGpsHeading() {
        val e = HeadingEstimator.estimate(listOf(fix(1000, Geo.LatLon(lat0, lon0), 8f, 271f)), 1500)!!
        assertEquals(271.0, e.headingDeg, 1e-9)
    }

    @Test
    fun walkingThenStoppingUsesTheDirectionWalked() {
        // Walk 12 m on a bearing of 60° over 10 s, then stand still (speed ~0, GPS heading junk) for 8 s.
        val fixes = ArrayList<GpsFix>()
        for (i in 0..10) fixes.add(fix(i * 1000L, Geo.offset(lat0, lon0, 60.0, i * 1.2), 1.2f, 200f + i * 7))
        val stop = Geo.offset(lat0, lon0, 60.0, 12.0)
        for (i in 11..18) fixes.add(fix(i * 1000L, stop, 0.1f, 5f))
        val e = HeadingEstimator.estimate(fixes, 18_000)!!
        assertEquals(0.0, angleDiff(60.0, e.headingDeg), 0.5)
        assertTrue(e.how.contains("movement"))
    }

    @Test
    fun standingStillWithJitterGivesNoHeading() {
        val fixes = (0..20).map { i -> fix(i * 1000L, Geo.offset(lat0, lon0, i * 47.0, 1.5), 0.2f, (i * 33).toFloat()) }
        assertNull(HeadingEstimator.estimate(fixes, 20_000))
    }

    @Test
    fun movementOlderThanTheWindowIsIgnored() {
        val fixes = listOf(fix(0, Geo.LatLon(lat0, lon0)), fix(1000, Geo.offset(lat0, lon0, 90.0, 10.0)))
        assertNotNull(HeadingEstimator.estimate(fixes, 2000))
        assertNull(HeadingEstimator.estimate(fixes + fix(40_000, Geo.offset(lat0, lon0, 90.0, 10.0), 0f), 40_000))
    }
}
