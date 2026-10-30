package com.lunk333.whrrah.session

import kotlin.math.abs

/**
 * Pairs camera clips with runs after the event, without trusting the camera's clock.
 *
 * Runs carry phone-clock start/stop times (from the camera's BLE "recording started/stopped" pushes,
 * or the phone's Start run). Clips carry the camera-clock start time in their name
 * (`DJI_yyyyMMddHHmmss_…`) and a length from the camera's list. The camera clock is only set when
 * the app connects over WiFi, so it may be off by seconds, minutes or a whole timezone.
 *
 * So: try every clip-minus-run start difference as the clock offset, keep the one that lines up the
 * most clip/run pairs (start within [startTolMs], length close), refine it as the median, then pair
 * each run with at most one clip.
 */
object ClipMatcher {
    data class RunSpan(val n: Int, val startMs: Long, val stopMs: Long)
    /** [startMs] on the camera's clock; [durationS] 0 = unknown. */
    data class ClipSpan(val name: String, val startMs: Long, val durationS: Int)
    /** [residualMs]: clip start − run start after the clock offset is taken out (+ = clip started later). */
    data class Match(val n: Int, val clip: String, val residualMs: Long)
    /** [clockOffsetMs]: camera clock − phone clock. */
    data class Result(val clockOffsetMs: Long, val matches: List<Match>)

    const val START_TOL_MS = 6_000L
    private const val MAX_OFFSET_MS = 26 * 3_600_000L
    const val KNOWN_OFFSET_TOL_MS = 30_000L
    private const val LONE_MATCH_MAX_OFFSET_MS = 120_000L

    /**
     * The camera clock offset that at least two matched runs agree on (within [KNOWN_OFFSET_TOL_MS]),
     * from each matched run's (clip start − run start). Returns (offset, indexes of the runs that
     * agree), or null when no two agree — a single match can't define the clock.
     */
    fun consensusOffset(diffs: List<Long>): Pair<Long, Set<Int>>? {
        var best: Set<Int> = emptySet()
        for (d in diffs) {
            val members = diffs.indices.filter { abs(diffs[it] - d) <= KNOWN_OFFSET_TOL_MS }.toSet()
            if (members.size > best.size) best = members
        }
        if (best.size < 2) return null
        val sorted = best.map { diffs[it] }.sorted()
        return sorted[sorted.size / 2] to best
    }

    fun durationOk(run: RunSpan, clip: ClipSpan): Boolean {
        if (clip.durationS <= 0) return true
        val runS = (run.stopMs - run.startMs) / 1000.0
        return abs(runS - clip.durationS) <= maxOf(5.0, runS * 0.1)
    }

    /**
     * [knownOffsetMs]: the camera clock offset already found for this session (from earlier matches).
     * New matches must agree with it to within [KNOWN_OFFSET_TOL_MS] — otherwise a lone leftover run
     * can be "matched" to any short clip at any offset.
     */
    fun match(runs: List<RunSpan>, clips: List<ClipSpan>, startTolMs: Long = START_TOL_MS, knownOffsetMs: Long? = null): Result? {
        if (runs.isEmpty() || clips.isEmpty()) return null
        var bestOffset = 0L
        var bestCount = -1
        val candidates = runs.flatMap { r -> clips.map { c -> c.startMs - r.startMs } }
            .filter { abs(it) <= MAX_OFFSET_MS && (knownOffsetMs == null || abs(it - knownOffsetMs) <= KNOWN_OFFSET_TOL_MS) }.distinct()
        for (d in candidates) {
            val n = pairUp(runs, clips, d, startTolMs).size
            if (n > bestCount || (n == bestCount && abs(d) < abs(bestOffset))) { bestCount = n; bestOffset = d }
        }
        if (bestCount <= 0) return null
        // A single pair says nothing about the clock: without a known offset, only trust a small one.
        if (bestCount == 1 && knownOffsetMs == null && abs(bestOffset) > LONE_MATCH_MAX_OFFSET_MS) return null
        // Refine: the median of the matched pairs' raw differences.
        val first = pairUp(runs, clips, bestOffset, startTolMs)
        val diffs = first.map { (r, c) -> c.startMs - r.startMs }.sorted()
        val refined = diffs[diffs.size / 2]
        val second = pairUp(runs, clips, refined, startTolMs)
        val (off, pairs) = if (second.size >= first.size) refined to second else bestOffset to first
        return Result(off, pairs.map { (r, c) -> Match(r.n, c.name, c.startMs - off - r.startMs) }.sortedBy { it.n })
    }

    /** One-to-one pairs within tolerance at clock offset [d], closest first. */
    private fun pairUp(runs: List<RunSpan>, clips: List<ClipSpan>, d: Long, tol: Long): List<Pair<RunSpan, ClipSpan>> {
        val cands = ArrayList<Triple<RunSpan, ClipSpan, Long>>()
        for (r in runs) for (c in clips) {
            val res = abs(c.startMs - d - r.startMs)
            if (res <= tol && durationOk(r, c)) cands.add(Triple(r, c, res))
        }
        cands.sortBy { it.third }
        val usedR = HashSet<Int>(); val usedC = HashSet<String>()
        val out = ArrayList<Pair<RunSpan, ClipSpan>>()
        for ((r, c, _) in cands) {
            if (r.n in usedR || c.name in usedC) continue
            usedR.add(r.n); usedC.add(c.name)
            out.add(r to c)
        }
        return out
    }
}
