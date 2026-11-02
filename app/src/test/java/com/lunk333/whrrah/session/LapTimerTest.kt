package com.lunk333.whrrah.session

import com.lunk333.whrrah.gps.Geo
import com.lunk333.whrrah.session.LapTimer.Fix
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LapTimerTest {
    @get:Rule val tmp = TemporaryFolder()

    private val o = Geo.LatLon(42.0, -71.5)
    private val width = 20 * Geo.FT
    /** Start 50 m north of o, finish 450 m north, both facing north. */
    private val start = Geo.offset(o.lat, o.lon, 0.0, 50.0).let { TrackLine(it.lat, it.lon, 0.0, width, 1f) }
    private val finish = Geo.offset(o.lat, o.lon, 0.0, 450.0).let { TrackLine(it.lat, it.lon, 0.0, width, 1f) }

    /** Drive north from o at [speed] m/s, a fix every [stepMs], starting at t0, [sideM] east of centre. */
    private fun drive(speed: Double, stepMs: Long, durationS: Double, t0: Long = 1_000_000, sideM: Double = 0.0, phaseMs: Long = 0): List<Fix> {
        val out = ArrayList<Fix>()
        var t = phaseMs
        while (t <= durationS * 1000) {
            val p = Geo.offset(o.lat, o.lon, 0.0, speed * t / 1000.0).let { Geo.offset(it.lat, it.lon, 90.0, sideM) }
            out.add(Fix(t0 + t, p.lat, p.lon)); t += stepMs
        }
        return out
    }

    @Test
    fun timesAStraightRunExactlyBetween25HzFixes() {
        // 400 m between lines at 17.3 m/s = 23.121 s. Fix phase deliberately not aligned with the lines.
        val r = LapTimer.time(drive(17.3, 40, 40.0, phaseMs = 13), start, finish)
        assertEquals(400 / 17.3 * 1000, r.lapTimeMs!!.toDouble(), 2.0)
        assertNull(r.note)
        assertEquals(1_000_000 + 50 / 17.3 * 1000, r.startMs!!.toDouble(), 2.0)
    }

    @Test
    fun onePerSecondGpsStillTimesButIsFlaggedRough() {
        val r = LapTimer.time(drive(17.3, 1000, 40.0, phaseMs = 370), start, finish)
        assertEquals(400 / 17.3 * 1000, r.lapTimeMs!!.toDouble(), 5.0)   // constant speed: interpolation is still exact
        assertEquals("timed from sparse GPS (phone?) — rough", r.note)
    }

    @Test
    fun outsideTheLineWidthDoesNotCount() {
        val r = LapTimer.time(drive(17.3, 40, 40.0, sideM = width / 2 + 0.5), start, finish)
        assertNull(r.lapTimeMs)
        assertEquals("no start crossing found", r.note)
        assertEquals(1, LapTimer.crossings(drive(17.3, 40, 40.0, sideM = width / 2 - 0.5), start).size)
    }

    @Test
    fun drivingTheWrongWayDoesNotCount() {
        val back = drive(17.3, 40, 40.0).reversed().mapIndexed { i, f -> f.copy(tMs = 1_000_000L + i * 40) }
        assertEquals(0, LapTimer.crossings(back, start).size)
        assertEquals("no start crossing found", LapTimer.time(back, start, finish).note)
    }

    @Test
    fun rollingOverTheStartInStagingUsesTheLastStartBeforeTheFinish() {
        // Creep over the start, back up behind it, then the real launch 30 s later.
        val creep = listOf(-3.0, 1.0, -4.0).mapIndexed { i, d -> Geo.offset(start.lat, start.lon, 0.0, d).let { Fix(500_000L + i * 1000, it.lat, it.lon) } }
        val run = drive(17.3, 40, 40.0, t0 = 530_000)
        val r = LapTimer.time(creep + run, start, finish)
        assertEquals(400 / 17.3 * 1000, r.lapTimeMs!!.toDouble(), 2.0)
    }

    @Test
    fun missingFinishOrTrack() {
        assertEquals("no finish crossing found", LapTimer.time(drive(17.3, 40, 10.0), start, finish).note)
        assertEquals("track not defined", LapTimer.time(drive(17.3, 40, 40.0), start, null).note)
        assertEquals("no GPS in this run", LapTimer.time(emptyList(), start, finish).note)
    }

    @Test
    fun aGpsDropoutAcrossTheLineIsNotTrusted() {
        val fixes = drive(17.3, 40, 40.0).filterNot { it.tMs - 1_000_000 in 1_500..6_000 }   // 4.5 s hole over the start
        assertEquals("no start crossing found", LapTimer.time(fixes, start, finish).note)
    }

    @Test
    fun csvUsesGnssTimeWhenItIsThere() {
        val f = tmp.newFile("lap01.csv")
        f.writeText("elapsed_ms,utc_ms,lat,lon,speed_mps,heading_deg,accuracy_m,source\n" +
            "100,5000,42.0,-71.5,,,1,RaceBox\n" + "180,5040,42.0001,-71.5,,,1,RaceBox\n")
        assertEquals(listOf(5000L, 5040L), LapTimer.readGpsCsv(f).map { it.tMs })
        val g = tmp.newFile("lap02.csv")
        g.writeText("elapsed_ms,utc_ms,lat,lon\n100,0,42.0,-71.5\n180,0,42.0001,-71.5\n")
        assertEquals(listOf(100L, 180L), LapTimer.readGpsCsv(g).map { it.tMs })
    }

    /**
     * A launch: stopped at [stopAtM] metres north of o (start line at 50 m) until t0+2 s, then
     * 3 m/s² up to 20 m/s, on to the finish. 25 Hz, with speed. [creep]: first rolls over the start
     * line from 40 m to 52 m while pulling up, then stops at [stopAtM].
     */
    private fun launchRun(stopAtM: Double, creep: Boolean = false): List<Fix> {
        val out = ArrayList<Fix>()
        var t = 0L; val t0 = 1_000_000L
        fun at(d: Double, v: Double) = Geo.offset(o.lat, o.lon, 0.0, d).let { out.add(Fix(t0 + t, it.lat, it.lon, v)) }
        if (creep) { var d = 40.0; while (d < 52.0) { at(d, 2.0); d += 2.0 * 0.04; t += 40 } }
        while (t < (if (creep) 8000 else 2000)) { at(stopAtM, 0.0); t += 40 }
        val tl = t
        while (true) {
            val tau = (t - tl) / 1000.0
            val (d, v) = if (tau < 20.0 / 3) (stopAtM + 1.5 * tau * tau) to 3 * tau
                         else (stopAtM + 20 * 20 / 6.0 + 20 * (tau - 20.0 / 3)) to 20.0
            if (d > 480) break
            at(d, v); t += 40
        }
        return out
    }

    /** Expected lap from the launch: start after [dist] m at 3 m/s², finish at 450 m. */
    private fun expected(stopAtM: Double, dist: Double): Double {
        val tStart = Math.sqrt(2 * dist / 3)
        val dAcc = 20 * 20 / 6.0
        val tFin = if (450 - stopAtM <= dAcc) Math.sqrt(2 * (450 - stopAtM) / 3) else 20.0 / 3 + (450 - stopAtM - dAcc) / 20
        return (tFin - tStart) * 1000
    }

    @Test
    fun startsFromTheLaunchOnceTheCarHasMovedTheStartDistance() {
        val r = LapTimer.time(launchRun(46.0), start, finish, emptyList(), startDistanceM = 6.0)
        assertNull(r.note)
        assertEquals(expected(46.0, 6.0), r.lapTimeMs!!.toDouble(), 25.0)
        // The calibration offset is added.
        assertEquals(r.lapTimeMs!! + 57, LapTimer.time(launchRun(46.0), start, finish, emptyList(), 6.0, offsetMs = 57).lapTimeMs)
    }

    @Test
    fun aCarStagedOverTheStartLineIsStillTimed() {
        // Stopped 1 m past the start line: no crossing at the launch, but the launch still starts it.
        val r = LapTimer.time(launchRun(51.0), start, finish, emptyList(), startDistanceM = 6.0)
        assertEquals(expected(51.0, 6.0), r.lapTimeMs!!.toDouble(), 25.0)
    }

    @Test
    fun rollingOverTheStartLineWhilePullingUpDoesNotStartIt() {
        // Crosses the line at 50 m while pulling up, stops 2 m past it, then launches.
        val r = LapTimer.time(launchRun(52.0, creep = true), start, finish, emptyList(), startDistanceM = 6.0)
        assertEquals(expected(52.0, 6.0), r.lapTimeMs!!.toDouble(), 25.0)
    }

    @Test
    fun withoutAStopInTheStageItFallsBackToTheStartLine() {
        val rolling = drive(17.3, 40, 40.0, phaseMs = 13).map { it.copy(speedMps = 17.3) }
        val r = LapTimer.time(rolling, start, finish, emptyList())
        assertEquals(400 / 17.3 * 1000, r.lapTimeMs!!.toDouble(), 2.0)
        assertEquals("timed from the start line (no stop in the stage found)", r.note)
    }
}
