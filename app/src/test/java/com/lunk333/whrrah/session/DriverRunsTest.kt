package com.lunk333.whrrah.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DriverRunsTest {
    private fun lap(n: Int, driver: String?, ms: Long?, cones: Int = 0, dnf: Boolean = false) =
        Lap(n = n, recStartWallMs = n * 1000L, recStopWallMs = n * 1000L + 500, driver = driver, lapTimeMs = ms, cones = cones, dnf = dnf)

    // Co-driving: runs alternate, one was deleted (no 4), one is a DNF, one has cones.
    private val s = Session("x", "E", "2026-11-01", "LUNK", "car", 0, laps = listOf(
        lap(1, null, 61_000), lap(2, "Ralph", 60_500), lap(3, null, 59_900, cones = 1),
        lap(5, "Ralph", 58_000, dnf = true), lap(6, null, 60_100), lap(7, "Ralph", 60_200)))

    @Test
    fun runsAreNumberedPerDriver() {
        assertEquals(listOf(1, 1, 2, 2, 3, 3), s.laps.map { s.runNo(it) })
        assertEquals(listOf("LUNK", "Ralph", "LUNK", "Ralph", "LUNK", "Ralph"), s.laps.map { s.driverOf(it) })
    }

    @Test
    fun eachDriverHasTheirOwnBestWithConesAndWithoutDnfs() {
        // LUNK: 61.0, 59.9+2 = 61.9, 60.1 → run 6. Ralph: 60.5, DNF, 60.2 → run 7.
        assertEquals(listOf(6, 7), s.laps.filter { s.isDriverBest(it) }.map { it.n })
        assertEquals(60_100L, s.bestFor(s.laps[0]))
        assertFalse(s.isDriverBest(s.laps[3]))   // the DNF's raw 58.0 doesn't count
        assertTrue(s.isDriverBest(s.laps[5]))
    }
}
