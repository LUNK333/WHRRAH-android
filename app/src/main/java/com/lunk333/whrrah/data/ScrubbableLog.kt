package com.lunk333.whrrah.data

import com.lunk333.whrrah.xrk.XrkLog

/** Current lap and elapsed time within it, at a given scrub position. */
data class LapStatus(val lapNum: Int, val elapsedSeconds: Double, val lapDurationSeconds: Double?)

/**
 * Common interface over CSV [TelemetryLog] and XRK [XrkLog] so canvas
 * widgets (M3) can bind to either without caring which format was loaded.
 * The two have different internal shapes (CSV: one shared timebase: XRK:
 * one timebase per channel) - this just hides that behind value-at-time
 * and lap-at-time lookups.
 */
interface ScrubbableLog {
    val channelNames: List<String>
    val durationSeconds: Double

    fun valueAt(channel: String, timeSeconds: Double): Double?
    fun unitOf(channel: String): String

    /** Min/max observed value for a channel, for bar-graph scaling. NaN-safe. */
    fun channelRange(channel: String): Pair<Double, Double>

    fun lapAt(timeSeconds: Double): LapStatus?
}

class CsvScrubbableLog(private val log: TelemetryLog) : ScrubbableLog {
    override val channelNames: List<String> get() = log.channelNames
    override val durationSeconds: Double get() = log.durationSeconds

    override fun valueAt(channel: String, timeSeconds: Double): Double? {
        val index = log.nearestSampleIndex(timeSeconds)
        if (index < 0) return null
        return log.channelValues[channel]?.getOrNull(index)
    }

    override fun unitOf(channel: String): String = log.channelUnits[channel].orEmpty()

    override fun channelRange(channel: String): Pair<Double, Double> {
        val values = log.channelValues[channel] ?: return 0.0 to 1.0
        var min = Double.POSITIVE_INFINITY
        var max = Double.NEGATIVE_INFINITY
        for (v in values) {
            if (v.isNaN()) continue
            if (v < min) min = v
            if (v > max) max = v
        }
        if (!min.isFinite() || !max.isFinite()) return 0.0 to 1.0
        return min to max
    }

    override fun lapAt(timeSeconds: Double): LapStatus? {
        val markers = log.beaconMarkers
        if (markers.isEmpty()) return LapStatus(0, timeSeconds, null)
        var lapStart = 0.0
        for ((i, markerEnd) in markers.withIndex()) {
            if (timeSeconds < markerEnd || i == markers.size - 1) {
                return LapStatus(i, timeSeconds - lapStart, markerEnd - lapStart)
            }
            lapStart = markerEnd
        }
        return null
    }
}

class XrkScrubbableLog(private val log: XrkLog) : ScrubbableLog {
    override val channelNames: List<String> get() = log.channels.map { it.name }
    override val durationSeconds: Double get() = log.durationMs / 1000.0

    private val channelsByName = log.channels.associateBy { it.name }

    override fun valueAt(channel: String, timeSeconds: Double): Double? {
        val ch = channelsByName[channel] ?: return null
        val index = ch.nearestSampleIndex((timeSeconds * 1000).toLong())
        if (index < 0) return null
        return ch.values.getOrNull(index)
    }

    override fun unitOf(channel: String): String = channelsByName[channel]?.units.orEmpty()

    override fun channelRange(channel: String): Pair<Double, Double> {
        val values = channelsByName[channel]?.values ?: return 0.0 to 1.0
        var min = Double.POSITIVE_INFINITY
        var max = Double.NEGATIVE_INFINITY
        for (v in values) {
            if (v.isNaN()) continue
            if (v < min) min = v
            if (v > max) max = v
        }
        if (!min.isFinite() || !max.isFinite()) return 0.0 to 1.0
        return min to max
    }

    override fun lapAt(timeSeconds: Double): LapStatus? {
        val timeMs = (timeSeconds * 1000).toLong()
        val lap = log.laps.firstOrNull { timeMs in it.startTimeMs until it.endTimeMs }
            ?: log.laps.lastOrNull()
            ?: return null
        return LapStatus(
            lapNum = lap.num,
            elapsedSeconds = (timeMs - lap.startTimeMs) / 1000.0,
            lapDurationSeconds = (lap.endTimeMs - lap.startTimeMs) / 1000.0
        )
    }
}
