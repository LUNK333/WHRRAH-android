package com.lunk333.whrrah.racebox

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/** Golden values from the worked examples in RaceBox BLE Protocol Description rev 9 (pages 7-8, 10-11). */
class UbxTest {
    private fun hex(s: String) = s.split(' ').filter { it.isNotEmpty() }.map { it.toInt(16).toByte() }.toByteArray()

    private val example = hex(
        "B5 62 FF 01 50 00 A0 E7 0C 07 E6 07 01 0A 08 33 08 37 19 00 00 00 2A AD 4D 0E 03 01 EA 0B C6 93 E1 0D " +
            "3B 37 6F 19 61 8C 09 00 0F 01 09 00 9C 03 00 00 2C 07 00 00 23 00 00 00 00 00 00 00 D0 00 00 00 " +
            "88 A9 DD 00 2C 01 00 59 FD FF 71 00 CE 03 2F FF 56 00 FC FF 06 DB"
    )

    @Test
    fun docExampleDecodes() {
        val pkts = UbxFramer().feed(example)
        assertEquals(1, pkts.size)
        assertEquals(0xFF, pkts[0].cls)
        assertEquals(0x01, pkts[0].id)
        val d = RaceBoxData.parse(pkts[0].payload)!!
        assertEquals(118286240L, d.iTowMs)
        val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { clear(); set(2022, 0, 10, 8, 51, 8) }.timeInMillis
        assertEquals(utc + 239, d.utcMs)
        assertTrue(d.timeValid)
        assertEquals(25L, d.timeAccuracyNs)
        assertEquals(3, d.fixType)
        assertTrue(d.fixOk)
        assertTrue(d.goodFix)
        assertEquals(11, d.satellites)
        assertEquals(23.2887238, d.lon, 1e-9)
        assertEquals(42.6719035, d.lat, 1e-9)
        assertTrue(d.latLonValid)
        assertEquals(625.761, d.altWgsM, 1e-9)
        assertEquals(590.095, d.altMslM, 1e-9)
        assertEquals(0.924, d.hAccM, 1e-9)
        assertEquals(1.836, d.vAccM, 1e-9)
        assertEquals(0.035, d.speedMps, 1e-9)
        assertEquals(0.0, d.headingDeg, 1e-9)
        assertEquals(0.208, d.speedAccMps, 1e-9)
        assertEquals(145.26856, d.headingAccDeg, 1e-9)
        assertEquals(3.0, d.pdop, 1e-9)
        assertEquals(89, d.batteryPercent)
        assertFalse(d.charging)
        assertEquals(-0.003, d.gX, 1e-9)
        assertEquals(0.113, d.gY, 1e-9)
        assertEquals(0.974, d.gZ, 1e-9)
        assertEquals(-2.09, d.rollRate, 1e-9)
        assertEquals(0.86, d.pitchRate, 1e-9)
        assertEquals(-0.04, d.yawRate, 1e-9)
    }

    @Test
    fun packetSplitAcrossNotificationsAndGluedToTheNext() {
        val f = UbxFramer()
        val two = example + example
        var got = 0
        // 20-byte chunks: the default-MTU worst case.
        for (i in two.indices step 20) got += f.feed(two.copyOfRange(i, minOf(i + 20, two.size))).size
        assertEquals(2, got)
        assertEquals(0L, f.checksumErrors)
    }

    @Test
    fun garbageAndCorruptPacketsAreSkipped() {
        val f = UbxFramer()
        val bad = example.copyOf().also { it[30] = (it[30] + 1).toByte() }
        val pkts = f.feed(hex("00 11 B5 22") + bad + example)
        assertEquals(1, pkts.size)
        assertEquals(1L, f.checksumErrors)
    }

    @Test
    fun frameMatchesDocExamples() {
        // GNSS config write (page 10) and recording-status query (page 11).
        assertArrayEquals(hex("B5 62 FF 27 03 00 08 01 02 34 0E"), Ubx.frame(0xFF, 0x27, hex("08 01 02")))
        assertArrayEquals(hex("B5 62 FF 22 00 00 21 62"), Ubx.frame(0xFF, 0x22))
    }
}
