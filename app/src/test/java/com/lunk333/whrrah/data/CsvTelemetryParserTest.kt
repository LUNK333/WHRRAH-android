package com.lunk333.whrrah.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CsvTelemetryParserTest {

    private val sample = """
        "Format","AiM CSV File"
        "Session","Palmer ACW"
        "Beacon Markers","406.423","525.005","1526"
        "Segment Times","6:46.423","1:58.582","1:49.536"

        "Time","GPS Speed","RPM"
        "s","mph","rpm"

        "0.000","2.1793","1450.0000"
        "0.050","2.2000","1460.0000"
        "0.100","2.4000","1470.0000"
    """.trimIndent().lines()

    @Test
    fun parsesChannelsAndUnits() {
        val log = CsvTelemetryParser.parse(sample)
        assertEquals(listOf("GPS Speed", "RPM"), log.channelNames)
        assertEquals("mph", log.channelUnits["GPS Speed"])
        assertEquals("rpm", log.channelUnits["RPM"])
    }

    @Test
    fun parsesBeaconMarkers() {
        val log = CsvTelemetryParser.parse(sample)
        assertEquals(listOf(406.423, 525.005, 1526.0), log.beaconMarkers)
    }

    @Test
    fun parsesSamplesAndDuration() {
        val log = CsvTelemetryParser.parse(sample)
        assertEquals(3, log.timestamps.size)
        assertEquals(0.1, log.durationSeconds, 0.0001)
        assertEquals(1470.0, log.channelValues.getValue("RPM")[2], 0.0001)
    }

    @Test
    fun nearestSampleIndexFindsClosest() {
        val log = CsvTelemetryParser.parse(sample)
        assertEquals(0, log.nearestSampleIndex(0.0))
        assertEquals(1, log.nearestSampleIndex(0.06))
        assertEquals(2, log.nearestSampleIndex(0.2))
        assertTrue(log.nearestSampleIndex(0.075) in 0..1)
    }
}
