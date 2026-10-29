package com.lunk333.whrrah.session

import com.lunk333.whrrah.session.ClipMatcher.ClipSpan
import com.lunk333.whrrah.session.ClipMatcher.RunSpan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ClipMatcherTest {
    private val t0 = 1_793_000_000_000L
    private fun run(n: Int, startS: Int, lenS: Int) = RunSpan(n, t0 + startS * 1000L, t0 + (startS + lenS) * 1000L)
    /** Clip names on the camera have whole seconds; [clockS] = camera clock ahead of the phone. */
    private fun clip(name: String, startS: Int, lenS: Int, clockS: Long = 0, fracMs: Long = 0) =
        ClipSpan(name, ((t0 + startS * 1000L + clockS * 1000 + fracMs) / 1000) * 1000, lenS)

    // An autocross day: ~2 min runs ~10 min apart.
    private val runs = listOf(run(1, 0, 120), run(2, 600, 115), run(3, 1250, 125), run(4, 1900, 118))

    @Test
    fun cameraClockInSync() {
        val clips = listOf(clip("A", 0, 120, fracMs = 400), clip("B", 600, 115), clip("C", 1250, 125), clip("D", 1900, 118))
        val r = ClipMatcher.match(runs, clips)!!
        assertEquals(listOf("A", "B", "C", "D"), r.matches.map { it.clip })
        assertEquals(0L, r.clockOffsetMs / 1000)
    }

    @Test
    fun cameraClockOffByMinutesOrATimezone() {
        for (clock in listOf(-185L, 47L, -4 * 3600L, 5 * 3600L + 13)) {
            val clips = listOf(clip("A", 0, 120, clock), clip("B", 600, 115, clock), clip("C", 1250, 125, clock), clip("D", 1900, 118, clock))
            val r = ClipMatcher.match(runs, clips)!!
            assertEquals("clock $clock", listOf(1 to "A", 2 to "B", 3 to "C", 4 to "D"), r.matches.map { it.n to it.clip })
            assertEquals(clock.toDouble(), r.clockOffsetMs / 1000.0, 1.0)
        }
    }

    @Test
    fun extraClipsAndUnrecordedRunsAreLeftOut() {
        // A test clip before the event, no clip for run 3 (camera wasn't recording), a stray clip mid-day.
        val clips = listOf(clip("test", -3000, 10), clip("A", 0, 120, 30), clip("B", 600, 115, 30),
            clip("stray", 900, 20, 30), clip("D", 1900, 118, 30))
        val r = ClipMatcher.match(runs, clips)!!
        assertEquals(listOf(1 to "A", 2 to "B", 4 to "D"), r.matches.map { it.n to it.clip })
    }

    @Test
    fun lengthDisambiguatesWhenStartsAreClose() {
        // Two runs 3 s apart (re-run after a false start): the lengths say which clip is which.
        val rs = listOf(run(1, 0, 20), run(2, 3, 120))
        val clips = listOf(clip("short", 1, 20), clip("long", 3, 120))
        val r = ClipMatcher.match(rs, clips)!!
        assertEquals(listOf(1 to "short", 2 to "long"), r.matches.map { it.n to it.clip })
    }

    @Test
    fun residualIsClipStartRelativeToRun() {
        val clips = listOf(clip("A", 0, 120, 60, fracMs = 0), clip("B", 600, 115, 60))
        val r = ClipMatcher.match(runs.take(2), clips)!!
        r.matches.forEach { assertEquals(0.0, it.residualMs / 1000.0, 1.0) }
    }

    @Test
    fun nothingToMatch() {
        assertNull(ClipMatcher.match(emptyList(), listOf(clip("A", 0, 10))))
        assertNull(ClipMatcher.match(runs, emptyList()))
        // Lengths all wrong: no pairing at all.
        assertNull(ClipMatcher.match(runs.take(1), listOf(clip("A", 0, 400))))
    }
}
