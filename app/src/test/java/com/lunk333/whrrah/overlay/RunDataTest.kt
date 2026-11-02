package com.lunk333.whrrah.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class RunDataTest {
    private val t0 = 1_793_247_100_000L

    /** A GPS log: 25 Hz, [n] samples from [f] (s → lat, lon, speed m/s, heading deg). */
    private fun gpsLog(n: Int, f: (Double) -> DoubleArray): File {
        val file = File.createTempFile("gps", ".csv").apply { deleteOnExit() }
        file.bufferedWriter().use { w ->
            w.write("elapsed_ms,utc_ms,lat,lon,speed_mps,heading_deg,accuracy_m,source\n")
            for (i in 0 until n) {
                val t = i * 0.04
                val r = f(t)
                w.write("${1000 + i * 40},${t0 + i * 40},${r[0]},${r[1]},${r[2]},${r[3]},0.6,RaceBox\n")
            }
        }
        return file
    }

    @Test
    fun lapLinesForAnAutocrossRun() {
        // Straight north at 10 m/s for 20 s; start line at 5 s, finish at 15 s.
        val gps = gpsLog(500) { t -> doubleArrayOf(42.0 + t * 10 / 111_000.0, -71.0, 10.0, 0.0) }
        val d = RunData.load(gps, null, t0 + 5_000, t0 + 15_000, runNumber = 3, earlierLapsMs = listOf(12_000, 11_000))
        val s = t0 / 1000.0
        assertEquals(0, d.lapNumberAt(s + 2)); assertEquals(1, d.lapNumberAt(s + 6)); assertEquals(2, d.lapNumberAt(s + 16))
        assertEquals(0.0, d.lapTimeAt(s + 2)!!, 1e-9)
        assertEquals(3.0, d.lapTimeAt(s + 8)!!, 1e-6)
        assertEquals(10.0, d.lapTimeAt(s + 19)!!, 1e-6)    // held at the lap time after the finish
        // Before the finish: last / best of the earlier runs; after: this run counts too.
        assertEquals(11.0, d.lastLapAt(s + 8)!!, 1e-9); assertEquals(11.0, d.bestLapAt(s + 8)!!, 1e-9)
        assertEquals(10.0, d.lastLapAt(s + 16)!!, 1e-9); assertEquals(10.0, d.bestLapAt(s + 16)!!, 1e-9)
        // Speed in mph, as AiM logs.
        assertEquals(22.37, d.valueAt(RunData.SPEED, s + 8), 0.01)
        // Track Map dot waits at the start of the outline before the start line.
        assertEquals(d.track.first(), d.positionAt(s + 1))
    }

    @Test
    fun untimedRunHasNoLapTime() {
        val gps = gpsLog(100) { t -> doubleArrayOf(42.0, -71.0, 0.0, Double.NaN) }
        val d = RunData.load(gps, null, null, null, 1, emptyList())
        assertNull(d.lapTimeAt(t0 / 1000.0 + 1)); assertNull(d.bestLapAt(t0 / 1000.0 + 1))
    }

    @Test
    fun gpsGsHaveAimSigns() {
        // A right-hand circle (heading increasing clockwise) of 30 m radius at 15 m/s: 0.765 G lateral.
        val r = 30.0; val v = 15.0
        val gps = gpsLog(400) { t ->
            val a = v * t / r
            doubleArrayOf(42.0 + r * cos(a) / 111_000.0, -71.0 + r * sin(a) / 81_500.0, v, ((a * 180 / PI) + 90) % 360)
        }
        val d = RunData.load(gps, null, null, null, 1, emptyList())
        val mid = t0 / 1000.0 + 8
        assertEquals(v * v / r / 9.80665, d.valueAt(RunData.LAT_ACC, mid), 0.02)
        assertEquals(0.0, d.valueAt(RunData.LON_ACC, mid), 0.01)
        // Speeding up at 2 m/s² in a straight line: + longitudinal.
        val accel = RunData.load(gpsLog(300) { t -> doubleArrayOf(42.0, -71.0, 5 + 2 * t, 0.0) }, null, null, null, 1, emptyList())
        assertEquals(2 / 9.80665, accel.valueAt(RunData.LON_ACC, t0 / 1000.0 + 6), 0.01)
    }

    @Test
    fun imuChannelsAreResampledOntoTheGpsTimes() {
        val gps = gpsLog(50) { doubleArrayOf(42.0, -71.0, 0.0, 0.0) }
        val imu = File.createTempFile("imu", ".csv").apply { deleteOnExit() }
        imu.bufferedWriter().use { w ->
            w.write("elapsed_ms,wall_ms,utc_ms,g_x,g_y,g_z,roll_dps,pitch_dps,yaw_dps\n")
            for (i in 0 until 50) w.write("0,0,${t0 + i * 40},${i * 0.01},0.2,0.98,1,2,3\n")
        }
        val d = RunData.load(gps, imu, null, null, 1, emptyList())
        assertTrue(d.has("InlineAcc") && d.has("YawRate"))
        assertEquals(0.1, d.valueAt("InlineAcc", t0 / 1000.0 + 0.4), 1e-6)
        assertEquals(3.0, d.valueAt("YawRate", t0 / 1000.0 + 1), 1e-9)
    }

    @Test
    fun theTimingCalibrationIsInTheOverlayTime() {
        val gps = gpsLog(500) { t -> doubleArrayOf(42.0 + t * 10 / 111_000.0, -71.0, 10.0, 0.0) }
        // Run timed 5 s → 15 s, calibrated −57 ms: the overlay shows the saved 9.943 at the finish.
        val d = RunData.load(gps, null, t0 + 5_000, t0 + 15_000, 1, emptyList(), offsetMs = -57)
        assertEquals(9.943, d.lapTimeAt(t0 / 1000.0 + 19)!!, 1e-6)
        assertEquals(3.0 - 0.057, d.lapTimeAt(t0 / 1000.0 + 8)!!, 1e-6)
    }
}
