package com.lunk333.whrrah.osmo.gyro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Pins the Kotlin decoder to the reference Python one (WHRRAH/wifi_gyro_sync/osmo_wifi_gyro.py,
 * itself validated against telemetry_parser). Fixture: 75 real djmd frames from an Osmo Action 4
 * clip, repackaged into a small MP4 by tools/make_gyro_fixture.py, with expected values written
 * by the Python decoder from the same bytes.
 */
class DjiGyroTest {
    private fun resource(name: String): File =
        File(javaClass.classLoader!!.getResource("gyro/$name")!!.toURI())

    private fun csvRows(name: String): List<List<String>> =
        resource(name).readLines().drop(1).filter { it.isNotBlank() }.map { it.split(',') }

    private fun assertSamples(expectedCsv: String, actual: GyroSamples) {
        val rows = csvRows(expectedCsv)
        assertEquals("sample count", rows.size, actual.size)
        for ((i, r) in rows.withIndex()) {
            val exp = r.map { it.toDouble() }
            assertEquals("t_ms[$i]", exp[0], actual.tMs[i], 1e-9)
            assertEquals("w[$i]", exp[1], actual.w[i], 1e-12)
            assertEquals("x[$i]", exp[2], actual.x[i], 1e-12)
            assertEquals("y[$i]", exp[3], actual.y[i], 1e-12)
            assertEquals("z[$i]", exp[4], actual.z[i], 1e-12)
        }
    }

    private fun open() = ChannelRangeReader.open(resource("oa4_75f.mp4").path)

    @Test
    fun wholeClipMatchesPython() {
        open().use { r -> assertSamples("oa4_75f_1khz.csv", samples1kHz(fetchGyro(r))) }
    }

    @Test
    fun windowNotStartingAtFrameZeroMatchesPython() {
        open().use { r -> assertSamples("oa4_75f_window_1khz.csv", samples1kHz(fetchGyro(r, 30 until 60))) }
    }

    @Test
    fun parallelFetchGivesSameResultInOrder() {
        val serial = open().use { samples1kHz(fetchGyro(it)) }
        val parallel = open().use { samples1kHz(fetchGyro(it, parallelism = 6)) }
        assertTrue(serial.tMs.contentEquals(parallel.tMs))
        assertTrue(serial.w.contentEquals(parallel.w))
    }

    @Test
    fun headerAndFrameFieldsMatchPython() {
        val rows = csvRows("oa4_75f_frames.csv")
        open().use { r ->
            val track = fetchGyro(r)
            assertEquals(75, track.totalFrames)
            assertEquals(rows.size, track.frames.size)
            val h = track.header
            assertEquals(rows[0][5], h.model)
            assertEquals(rows[0][6], h.firmware)
            assertEquals(rows[0][7].toLong(), h.imuHz)
            assertEquals(rows[0][8].toDouble(), h.fps!!, 1e-12)
            for ((i, row) in rows.withIndex()) {
                val fr = track.frames[i]
                assertEquals(row[0].toLong(), fr.index)
                assertEquals(row[1].toLong(), fr.frameTsUs)
                assertEquals(row[2].toLong(), fr.imuTsUs)
                assertEquals(row[3].toLong(), fr.imuCounter)
                assertEquals(row[4].toInt(), fr.quats.size)
            }
            // moov walk: 1 header read per top-level box (ftyp, free, mdat, moov) + moov body + 75 frames.
            assertEquals(4 + 1 + 75L, track.requests)
        }
    }

    @Test
    fun whrrahSamplesAreLastQuatOfEachFrameInSeconds() {
        open().use { r ->
            val track = fetchGyro(r)
            val all = samples1kHz(track)
            val perFrame = whrrahSamples(track)
            assertEquals(track.frames.size, perFrame.size)
            val lastIdx = track.frames.first().quats.size - 1
            assertEquals(all.tMs[lastIdx] / 1000.0, perFrame.tMs[0], 1e-12)
            assertEquals(all.w[lastIdx], perFrame.w[0], 0.0)
        }
    }

    @Test
    fun imuTimestampWrapIsHandled() {
        val h = ClipHeader(fps = 25.0)
        fun fr(ts: Long) = GyroFrame(0, null, ts, null, 0.0, listOf(doubleArrayOf(1.0, 0.0, 0.0, 0.0)), null)
        val ts0 = (1L shl 32) - 500   // 500 us before the u32 wraps
        val s = samples1kHz(GyroTrack(h, listOf(fr(ts0), fr(1500)), 2, ts0, 0, 0))
        assertEquals(0.0, s.tMs[0], 1e-12)
        assertEquals(2.0, s.tMs[1], 1e-12)   // 500 + 1500 us later, not -71.6 min
    }

    @Test
    fun encodePathMatchesPythonQuote() {
        assertEquals("DCIM/DJI_001/DJI_20260516154430_0280_D.MP4",
            HttpRangeReader.encodePath("DCIM/DJI_001/DJI_20260516154430_0280_D.MP4"))
        assertEquals("a%20b/c%2Bd", HttpRangeReader.encodePath("a b/c+d"))
    }
}
