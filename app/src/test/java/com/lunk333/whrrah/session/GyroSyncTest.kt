package com.lunk333.whrrah.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

class GyroSyncTest {
    private val rnd = Random(42)

    /** An autocross-ish angular speed profile (°/s): ~25 turns of different size and length in 120 s. */
    private val turns = List(25) { Triple(rnd.nextDouble(2.0, 118.0), rnd.nextDouble(15.0, 60.0), rnd.nextDouble(0.4, 2.0)) }
    private fun omega(tS: Double) = turns.sumOf { (c, a, w) -> a * exp(-((tS - c) / w).let { it * it }) }

    /** Camera: 1 kHz quaternions rotating about a tilted axis by the integrated angle, stored as floats. */
    private fun camera(durS: Double): Array<DoubleArray> {
        val n = (durS * 1000).toInt()
        val t = DoubleArray(n); val w = DoubleArray(n); val x = DoubleArray(n); val y = DoubleArray(n); val z = DoubleArray(n)
        val ax = 0.3; val ay = 0.2; val az = sqrt(1 - ax * ax - ay * ay)
        var theta = 0.0
        for (i in 0 until n) {
            t[i] = i.toDouble()
            theta += Math.toRadians(omega(i / 1000.0)) * 0.001
            val s = sin(theta / 2)
            w[i] = cos(theta / 2).toFloat().toDouble(); x[i] = (ax * s).toFloat().toDouble()
            y[i] = (ay * s).toFloat().toDouble(); z[i] = (az * s).toFloat().toDouble()
        }
        return arrayOf(t, w, x, y, z)
    }

    /** RaceBox: 25 Hz rates on its own axes + noise, sample times on the data clock. */
    private fun raceBox(videoStartOnDataClock: Double, fromS: Double, toS: Double, noise: Double = 1.5): Array<DoubleArray> {
        val ts = ArrayList<Double>(); val r = ArrayList<Double>(); val p = ArrayList<Double>(); val yw = ArrayList<Double>()
        var s = fromS
        while (s <= toS) {
            val o = omega(s)
            ts.add(videoStartOnDataClock + s * 1000)
            r.add(o * 0.2 + rnd.nextDouble(-noise, noise)); p.add(o * 0.1 + rnd.nextDouble(-noise, noise))
            yw.add(o * 0.97 + rnd.nextDouble(-noise, noise))
            s += 0.04
        }
        return arrayOf(ts.toDoubleArray(), r.toDoubleArray(), p.toDoubleArray(), yw.toDoubleArray())
    }

    @Test
    fun findsTheTrueOffsetFromABadGuess() {
        val (t, w, x, y, z) = camera(120.0)
        val video = GyroSync.videoSpeed(t, w, x, y, z)
        val trueStart = 1_793_000_000_000.0
        val (dt, r, p, yw) = raceBox(trueStart, 0.0, 120.0)
        val res = GyroSync.align(video, dt, GyroSync.dataSpeed(r, p, yw), estimateMs = trueStart + 1234)!!
        assertEquals(trueStart, res.videoStartMs, 5.0)   // within 5 ms
        assertTrue("r=${res.r}", res.r > 0.95)
        assertTrue("peakRatio=${res.peakRatio}", res.peakRatio > 1.3)
    }

    @Test
    fun clipThatStartsBeforeTheDataAndGuessOffTheOtherWay() {
        val (t, w, x, y, z) = camera(120.0)
        val video = GyroSync.videoSpeed(t, w, x, y, z)
        val trueStart = 5_000_000.0
        val (dt, r, p, yw) = raceBox(trueStart, 6.0, 110.0)   // data only covers part of the clip
        val res = GyroSync.align(video, dt, GyroSync.dataSpeed(r, p, yw), estimateMs = trueStart - 7_500)!!
        assertEquals(trueStart, res.videoStartMs, 5.0)
    }

    @Test
    fun unrelatedDataGivesALowScore() {
        val (t, w, x, y, z) = camera(120.0)
        val video = GyroSync.videoSpeed(t, w, x, y, z)
        val dt = DoubleArray(3000) { it * 40.0 }
        val junk = DoubleArray(3000) { 20 + 15 * sin(it * 2 * PI / 97) + rnd.nextDouble(-10.0, 10.0) }
        val res = GyroSync.align(video, dt, junk, estimateMs = 0.0)
        assertTrue(res == null || res.r < 0.5)
    }

    @Test
    fun tooLittleDataIsNull() {
        val (t, w, x, y, z) = camera(10.0)
        val video = GyroSync.videoSpeed(t, w, x, y, z)
        assertEquals(null, GyroSync.align(video, DoubleArray(10) { it * 40.0 }, DoubleArray(10), 0.0))
    }
}
