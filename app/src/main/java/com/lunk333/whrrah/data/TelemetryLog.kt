package com.lunk333.whrrah.data

/**
 * Parsed AiM CSV telemetry log. `timestamps` and each entry in
 * [channelValues] are parallel arrays (one value per sample row).
 */
data class TelemetryLog(
    val channelNames: List<String>,
    val channelUnits: Map<String, String>,
    val timestamps: DoubleArray,
    val channelValues: Map<String, DoubleArray>,
    val beaconMarkers: List<Double>,
    val durationSeconds: Double
) {
    /** Index of the sample whose timestamp is closest to [timeSeconds]. -1 if there are no samples. */
    fun nearestSampleIndex(timeSeconds: Double): Int {
        if (timestamps.isEmpty()) return -1
        var lo = 0
        var hi = timestamps.size - 1
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (timestamps[mid] < timeSeconds) lo = mid + 1 else hi = mid
        }
        if (lo > 0 && kotlin.math.abs(timestamps[lo - 1] - timeSeconds) <=
            kotlin.math.abs(timestamps[lo] - timeSeconds)
        ) {
            return lo - 1
        }
        return lo
    }
}
