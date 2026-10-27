package com.lunk333.whrrah.session

import dev.konraditurbe.osmosis.core.CameraFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

class SessionTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun folderNameIsEventUnderscoreDateMinusBadChars() {
        assertEquals("NE-SVT #16_2026-11-01", Session.folderName("NE-SVT #16", "2026-11-01"))
        assertEquals("a-b-c_2026-11-01", Session.folderName(" a/b:c ", "2026-11-01"))
        assertEquals("Session_2026-11-01", Session.folderName("   ", "2026-11-01"))
    }

    @Test
    fun createSaveLoadRoundTrip() {
        val store = SessionStore(tmp.root)
        val s = store.create("NE-SVT #16", "Luke", "E46 M3")
        assertTrue(File(store.dir(s.id), "session.json").isFile)
        assertTrue(File(store.dir(s.id), "video").isDirectory)
        val full = s.copy(
            start = TrackPoint(42.1, -71.2, 3.5f),
            finish = TrackPoint(42.2, -71.3, null),
            laps = listOf(
                Lap(1, 1000, 2000, clip = "DJI_x.MP4", gyroFile = "lap01.csv", gyroSamples = 120445, gpsFile = "lap01.csv",
                    gpsFixes = 42, lapTimeMs = 61234, syncOffsetMs = -350, note = "n"),
                Lap(2, 3000),
            ),
        )
        store.save(full)
        assertEquals(full, store.load(s.id))
    }

    @Test
    fun secondSessionSameDayGetsSuffix() {
        val store = SessionStore(tmp.root)
        val a = store.create("Event", "", "", nowMs = 1_000)
        val b = store.create("Event", "", "", nowMs = 2_000)
        assertEquals(a.id + "_2", b.id)
        assertEquals(listOf(b.id, a.id), store.list().map { it.id })   // newest first
    }

    @Test
    fun lapStateAndBest() {
        val s = Session("x", "e", "d", "", "", 0, laps = listOf(Lap(1, 0, 10, lapTimeMs = 62_000), Lap(2, 20, 30, lapTimeMs = 60_500), Lap(3, 40)))
        assertEquals(60_500L, s.bestLapMs)
        assertTrue(s.laps[2].recording)
        assertNull(Session("x", "e", "d", "", "", 0).bestLapMs)
    }

    @Test
    fun clipStartTimeFromDjiName() {
        val f = CameraFile(path = "DCIM/DJI_001/DJI_20261027005459_0425_D.MP4", thumbPath = "")
        val expected = SimpleDateFormat("yyyyMMddHHmmss", Locale.US).parse("20261027005459")!!.time
        assertEquals(expected, SessionController.clipStartMs(f))
        assertNotNull(SessionController.clipStartMs(f))
        assertNull(SessionController.clipStartMs(CameraFile(path = "DCIM/x.MP4", thumbPath = "")))
    }
}
