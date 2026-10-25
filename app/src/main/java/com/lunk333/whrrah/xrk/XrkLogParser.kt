package com.lunk333.whrrah.xrk

import org.json.JSONObject

/** Parses the JSON produced by [XrkNative.parseFullJson] into an [XrkLog]. */
object XrkLogParser {
    fun parse(json: String): XrkLog {
        val root = JSONObject(json)
        if (root.has("error")) error(root.getString("error"))

        val channelsArray = root.getJSONArray("channels")
        val channels = (0 until channelsArray.length()).map { i ->
            val c = channelsArray.getJSONObject(i)
            val timecodesArray = c.getJSONArray("timecodes_ms")
            val valuesArray = c.getJSONArray("values")
            val timecodes = LongArray(timecodesArray.length()) { timecodesArray.getLong(it) }
            val values = DoubleArray(valuesArray.length()) { valuesArray.getDouble(it) }
            XrkChannel(
                name = c.getString("name"),
                units = c.optString("units"),
                timecodesMs = timecodes,
                values = values
            )
        }

        val lapsArray = root.getJSONArray("laps")
        val laps = (0 until lapsArray.length()).map { i ->
            val l = lapsArray.getJSONObject(i)
            XrkLap(
                num = l.getInt("num"),
                startTimeMs = l.getLong("start_time_ms"),
                endTimeMs = l.getLong("end_time_ms")
            )
        }

        val metadata = root.getJSONObject("metadata")

        return XrkLog(
            channels = channels,
            laps = laps,
            vehicle = metadata.optString("vehicle").takeIf { it.isNotBlank() },
            driver = metadata.optString("driver").takeIf { it.isNotBlank() },
            durationMs = root.getLong("duration_ms")
        )
    }
}
