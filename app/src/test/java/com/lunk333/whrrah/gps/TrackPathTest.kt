package com.lunk333.whrrah.gps

import com.lunk333.whrrah.session.TrackLine
import org.junit.Assert.assertEquals
import org.junit.Test

class TrackPathTest {
    private val o = Geo.LatLon(42.0, -71.5)
    private val corner = Geo.offset(o.lat, o.lon, 0.0, 60.0)
    private fun angleDiff(a: Double, b: Double) = ((a - b + 540) % 360) - 180

    /** Walk 60 m north, then 60 m east, a point every metre. */
    private val lWalk = (0..60).map { Geo.offset(o.lat, o.lon, 0.0, it.toDouble()) } +
        (1..60).map { Geo.offset(corner.lat, corner.lon, 90.0, it.toDouble()) }
    private val path = TrackPath(lWalk)

    /** Set 20 m up the first leg, 1 m right (east) of the trace, facing north. */
    private val setAt = Geo.offset(Geo.offset(o.lat, o.lon, 0.0, 20.0).lat, Geo.offset(o.lat, o.lon, 0.0, 20.0).lon, 90.0, 1.0)
    private val line = TrackLine(setAt.lat, setAt.lon, 0.0, 6.0, 1f, setLat = setAt.lat, setLon = setAt.lon, setHeadingDeg = 0.0)

    @Test
    fun pathMeasuresAndProjects() {
        assertEquals(120.0, path.length, 1e-3)
        val p = path.project(setAt)!!
        assertEquals(20.0, p.along, 1e-3)
        assertEquals(1.0, p.lateral, 1e-3)   // + = right of travel
        assertEquals(0.0, angleDiff(0.0, path.headingAt(20.0)), 1e-3)
        assertEquals(0.0, angleDiff(90.0, path.headingAt(90.0)), 1e-3)
    }

    @Test
    fun bigNudgeFollowsTheCourseAroundTheCorner() {
        val moved = line.nudged(60.0, path)   // 20 + 60 = 80 m along = 20 m along the east leg
        assertEquals(60.0, moved.nudgeM, 0.0)
        assertEquals(0.0, angleDiff(90.0, moved.headingDeg!!), 1e-3)          // turned with the course
        val expect = Geo.offset(Geo.offset(corner.lat, corner.lon, 90.0, 20.0).lat, Geo.offset(corner.lat, corner.lon, 90.0, 20.0).lon, 180.0, 1.0)
        assertEquals(0.0, Geo.distance(expect.lat, expect.lon, moved.lat, moved.lon), 0.05)   // still 1 m right of the trace
        // Still square to its (new) heading.
        val (l, r) = moved.endpoints()!!
        assertEquals(0.0, angleDiff(90.0 + 90, Geo.bearing(l.lat, l.lon, r.lat, r.lon)), 1e-2)
    }

    @Test
    fun nudgesAreFromTheSetPointAndUndoIsExact() {
        val stepped = line.nudged(1.0, path).nudged(1.0, path).nudged(1.0, path)
        val once = line.nudged(3.0, path)
        assertEquals(0.0, Geo.distance(stepped.lat, stepped.lon, once.lat, once.lon), 1e-6)
        val undone = stepped.nudged(-stepped.nudgeM, path)
        assertEquals(0.0, undone.nudgeM, 0.0)
        assertEquals(setAt.lat, undone.lat, 0.0)
        assertEquals(setAt.lon, undone.lon, 0.0)
        assertEquals(0.0, undone.headingDeg!!, 0.0)
        assertEquals(undone, TrackLine.fromJson(undone.toJson()))
    }

    @Test
    fun stopsAtTheEndsOfTheWalk() {
        val far = line.nudged(500.0, path)
        assertEquals(100.0, far.nudgeM, 0.01)   // only 100 m of walk left after the set point
        val end = lWalk.last()
        assertEquals(1.0, Geo.distance(end.lat, end.lon, far.lat, far.lon), 0.05)
        assertEquals(-20.0, line.nudged(-500.0, path).nudgeM, 0.01)
    }

    @Test
    fun walkRecordedBackwardsStillMovesTheWayTheLineFaces() {
        val reversed = TrackPath(lWalk.reversed())
        val moved = line.nudged(10.0, reversed)   // line faces north, walk runs south on this leg
        assertEquals(0.0, angleDiff(0.0, moved.headingDeg!!), 1e-3)
        val ahead = Geo.offset(setAt.lat, setAt.lon, 0.0, 10.0)
        assertEquals(0.0, Geo.distance(ahead.lat, ahead.lon, moved.lat, moved.lon), 0.05)
    }

    @Test
    fun farFromTheWalkOrNoWalkGoesStraight() {
        val off = Geo.offset(setAt.lat, setAt.lon, 90.0, 30.0)
        val away = TrackLine(off.lat, off.lon, 0.0, 6.0, null, setLat = off.lat, setLon = off.lon, setHeadingDeg = 0.0)
        for (p in listOf(path, null)) {
            val m = away.nudged(70.0, p)
            val straight = Geo.offset(off.lat, off.lon, 0.0, 70.0)
            assertEquals(0.0, Geo.distance(straight.lat, straight.lon, m.lat, m.lon), 1e-6)
            assertEquals(0.0, m.headingDeg!!, 0.0)
        }
    }

    @Test
    fun linesNudgedTheOldWayCarryOver() {
        // Saved before set-points existed: centre already moved +3 m straight, no setLat/Lon.
        val moved = Geo.offset(setAt.lat, setAt.lon, 0.0, 3.0)
        val old = TrackLine(moved.lat, moved.lon, 0.0, 6.0, null, nudgeM = 3.0)
        val undone = old.nudged(-3.0, path)
        assertEquals(0.0, Geo.distance(setAt.lat, setAt.lon, undone.lat, undone.lon), 1e-6)
    }
}
