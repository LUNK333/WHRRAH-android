package com.lunk333.whrrah.xrk

/**
 * One XRK channel. Unlike CSV rows (one shared timestamp column for every
 * channel), each XRK channel has its own timebase - [timecodesMs] and
 * [values] are parallel arrays for this channel only.
 */
data class XrkChannel(
    val name: String,
    val units: String,
    val timecodesMs: LongArray,
    val values: DoubleArray
) {
    /** Index of this channel's sample closest to [timeMs]. -1 if the channel has no samples. */
    fun nearestSampleIndex(timeMs: Long): Int {
        if (timecodesMs.isEmpty()) return -1
        var lo = 0
        var hi = timecodesMs.size - 1
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (timecodesMs[mid] < timeMs) lo = mid + 1 else hi = mid
        }
        if (lo > 0 && kotlin.math.abs(timecodesMs[lo - 1] - timeMs) <= kotlin.math.abs(timecodesMs[lo] - timeMs)) {
            return lo - 1
        }
        return lo
    }
}

data class XrkLap(val num: Int, val startTimeMs: Long, val endTimeMs: Long)

data class XrkLog(
    val channels: List<XrkChannel>,
    val laps: List<XrkLap>,
    val vehicle: String?,
    val driver: String?,
    val durationMs: Long
)
