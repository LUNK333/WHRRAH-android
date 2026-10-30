package com.lunk333.whrrah.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

class LapDeltaTest {
    private val lat0 = 42.7642; private val lon0 = -71.4947
    private val proj = LocalProjector(lat0, lon0)
    private fun lat(y: Double) = lat0 + y / (LocalProjector.R * PI / 180)
    private fun lon(x: Double) = lon0 + x / (cos(lat0 * PI / 180) * LocalProjector.R * PI / 180)

    /**
     * Open U course: 100 m north up x = 0, a right-hand hairpin of radius [r], 100 m back south at
     * x = 2r. Start line across the first straight at y = 10, finish across the return at y = 10.
     */
    private inner class Course(val r: Double) {
        val straight = 100.0
        val length = 2 * straight + PI * r
        /** Position at distance d along the course (starting 20 m before the line, at y = -10). */
        fun at(d: Double): Pair<Double, Double> = when {
            d < straight + 10 -> 0.0 to (d - 10)
            d < straight + 10 + PI * r -> {
                val a = (d - straight - 10) / r       // 0..π, around the centre (r, straight)
                (r - r * cos(a)) to (straight + r * sin(a))
            }
            else -> 2 * r to (straight - (d - straight - 10 - PI * r))
        }
        val start = TrackLine(lat(10.0), lon(0.0), 0.0, 12.0, null)
        val finish = TrackLine(lat(10.0), lon(2 * r), 180.0, 12.0, null)

        /** 25 Hz fixes driving the whole thing at [v] m/s, from t0; [drop] = (from s, to s) with no fixes. */
        fun drive(v: Double, t0: Long = 1_000_000L, drop: Pair<Double, Double>? = null): List<LapTimer.Fix> {
            val total = length + 20
            return (0..(total / v * 25).toInt()).mapNotNull { i ->
                val t = i * 0.04
                if (drop != null && t >= drop.first && t < drop.second) return@mapNotNull null
                val (x, y) = at(v * t)
                LapTimer.Fix(t0 + Math.round(t * 1000), lat(y), lon(x))
            }
        }
    }

    private fun reference(c: Course, v: Double): ReferenceLap {
        val fixes = c.drive(v)
        val r = LapTimer.time(fixes, c.start, c.finish)
        return ReferenceLap.build(fixes, r.startMs!!.toDouble(), r.finishMs!!.toDouble(), proj)!!
    }

    @Test
    fun referenceIsTheCourseFromLineToLine() {
        val c = Course(15.0)
        val ref = reference(c, 15.0)
        val lineToLine = c.length - 20       // 10 m of each straight is outside the lines
        assertEquals(lineToLine, ref.length, 1.0)
        assertEquals(lineToLine / 15.0, ref.lapTime, 0.002)
        assertEquals(0.0, ref.ts[0], 1e-9)
        assertEquals(ref.lapTime, ref.ts.last(), 1e-9)
        assertEquals(1.0, ref.ss[1] - ref.ss[0], 1e-9)
        assertTrue(ref.valid)
    }

    @Test
    fun onePercentFasterGainsSteadilyToExactlyTheLapDifference() {
        val c = Course(15.0)
        val ref = reference(c, 15.0)
        val tr = DeltaTracker(ref, proj, c.start, c.finish)
        val v = 15.0 * 1.01
        val samples = ArrayList<Pair<Double, Double>>()   // (elapsed, delta)
        var finalState: DeltaState? = null
        for (f in c.drive(v)) {
            tr.onFix(f.tMs.toDouble(), f.lat, f.lon, v)
            val st = tr.state
            if (st.final) { finalState = st; break }
            if (st.delta != null && st.lapElapsed != null && st.lapElapsed > 0.5) samples += st.lapElapsed to st.delta
        }
        val fin = finalState!!
        // Exact at the line: own lap time − reference lap time = −1/101 of it.
        assertEquals(ref.lapTime / 1.01 - ref.lapTime, fin.delta!!, 0.003)
        // Mid-course it falls linearly with time (−(1 − 1/1.01) per second), within smoothing / jitter.
        for ((e, d) in samples) assertEquals("at $e s", -e * (1 - 1 / 1.01), d, 0.03)
        // Always gaining.
        assertTrue(samples.drop(5).all { it.second < 0 })
    }

    @Test
    fun hairpinWithStraightsTenMetresApartNeverJumpsAcross() {
        val c = Course(5.0)                        // the return straight runs 10 m from the first one
        val ref = reference(c, 12.0)
        val tr = DeltaTracker(ref, proj, c.start, c.finish)
        var lastS = -1.0
        for (f in c.drive(12.0)) {
            tr.onFix(f.tMs.toDouble(), f.lat, f.lon, 12.0)
            val st = tr.state
            if (st.final) break
            val s = st.s ?: continue
            // s only ever creeps forward — a jump to the other straight would leap 100+ m either way.
            if (lastS >= 0) assertTrue("s jumped $lastS → $s", s - lastS in -2.0..5.0)
            lastS = s
            assertEquals(0.0, st.delta ?: 0.0, 0.05)    // same speed as the reference
        }
    }

    @Test
    fun dropoutHoldsThenReacquires() {
        val c = Course(15.0)
        val ref = reference(c, 15.0)
        val tr = DeltaTracker(ref, proj, c.start, c.finish)
        // 3 s with no fixes mid first straight (start crossed at ~1.3 s).
        val fixes = c.drive(15.0, drop = 3.0 to 6.0)
        var afterGap: DeltaState? = null
        for (f in fixes) {
            tr.onFix(f.tMs.toDouble(), f.lat, f.lon, 15.0)
            if (f.tMs - 1_000_000L in 6_000..6_100) afterGap = tr.state
        }
        val st = afterGap!!
        assertTrue(st.onTrack)
        // Back on the right spot: (6 s − the start crossing at 20 m) × 15 m/s along, delta ≈ 0.
        assertEquals((6.0 - 20.0 / 15.0) * 15, st.s!!, 3.0)
        assertEquals(0.0, st.delta!!, 0.05)
        assertTrue(tr.state.final)
    }

    @Test
    fun offTheReferenceLineGreysOut() {
        val c = Course(15.0)
        val ref = reference(c, 15.0)
        val tr = DeltaTracker(ref, proj, c.start, c.finish)
        val fixes = c.drive(15.0)
        var off: DeltaState? = null
        for (f in fixes) {
            val t = (f.tMs - 1_000_000L) / 1000.0
            // 2–3 s in, pretend the car is 25 m left of the course.
            val lonX = if (t in 2.0..3.0) lon(-25.0) else f.lon
            tr.onFix(f.tMs.toDouble(), f.lat, lonX, 15.0)
            if (abs(t - 2.52) < 0.005) off = tr.state
        }
        assertFalse(off!!.onTrack)
        assertNotNull(off.delta)                    // the last good value, held
        assertTrue(tr.state.final)
    }

    @Test
    fun noReferenceStillTimesButHasNoDelta() {
        val c = Course(15.0)
        val tr = DeltaTracker(null, proj, c.start, c.finish)
        for (f in c.drive(15.0).take(100)) tr.onFix(f.tMs.toDouble(), f.lat, f.lon, 15.0)
        assertNotNull(tr.state.lapElapsed)
        assertNull(tr.state.delta)
    }

    @Test
    fun aLapWithAGapIsNotAValidReference() {
        val c = Course(15.0)
        val fixes = c.drive(15.0, drop = 4.0 to 4.8)
        val r = LapTimer.time(fixes, c.start, c.finish)
        assertFalse(ReferenceLap.build(fixes, r.startMs!!.toDouble(), r.finishMs!!.toDouble(), proj)!!.valid)
    }

    @Test
    fun onFixIsCheap() {
        val c = Course(15.0)
        val ref = reference(c, 15.0)
        val fixes = c.drive(15.0)
        repeat(3) { val tr = DeltaTracker(ref, proj, c.start, c.finish); for (f in fixes) tr.onFix(f.tMs.toDouble(), f.lat, f.lon, 15.0) }
        val t0 = System.nanoTime()
        val tr = DeltaTracker(ref, proj, c.start, c.finish)
        for (f in fixes) tr.onFix(f.tMs.toDouble(), f.lat, f.lon, 15.0)
        val perFixUs = (System.nanoTime() - t0) / 1000.0 / fixes.size
        assertTrue("$perFixUs µs per fix", perFixUs < 200)
    }
}
