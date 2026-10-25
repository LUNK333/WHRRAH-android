package com.lunk333.whrrah.data

/**
 * Parses AiM RS2-exported CSV logs (see WHRRAH desktop README "Importing
 * data" and source/sample_data.csv for the reference format):
 *
 *   "Format","AiM CSV File"
 *   ...metadata rows ("Key","Value",...)...
 *   "Beacon Markers","406.423","525.005",...    <- lap/segment crossing times
 *   <blank line>
 *   "Time","GPS Speed",...                       <- channel name header row
 *   "s","mph",...                                <- units row
 *   <blank line>
 *   "0.000","2.1793",...                         <- data rows
 */
object CsvTelemetryParser {

    fun parse(lines: List<String>): TelemetryLog {
        var i = 0
        var beaconMarkers: List<Double> = emptyList()

        // Metadata block: one "Key","Value",... row per line until the first blank line.
        while (i < lines.size && lines[i].isNotBlank()) {
            val fields = splitCsvLine(lines[i])
            if (fields.firstOrNull() == "Beacon Markers") {
                beaconMarkers = fields.drop(1).mapNotNull { it.toDoubleOrNull() }
            }
            i++
        }
        while (i < lines.size && lines[i].isBlank()) i++

        require(i < lines.size) { "CSV is missing the channel header row" }
        val header = splitCsvLine(lines[i]); i++

        require(i < lines.size) { "CSV is missing the units row" }
        val units = splitCsvLine(lines[i]); i++

        while (i < lines.size && lines[i].isBlank()) i++

        val channelNames = header.drop(1)
        val channelUnits = channelNames.mapIndexed { idx, name ->
            name to units.getOrElse(idx + 1) { "" }
        }.toMap()

        val timestamps = ArrayList<Double>()
        val valueLists = channelNames.associateWith { ArrayList<Double>() }

        for (j in i until lines.size) {
            val line = lines[j]
            if (line.isBlank()) continue
            val fields = splitCsvLine(line)
            if (fields.size < header.size) continue
            val time = fields[0].toDoubleOrNull() ?: continue
            timestamps.add(time)
            for (c in channelNames.indices) {
                valueLists.getValue(channelNames[c]).add(fields[c + 1].toDoubleOrNull() ?: Double.NaN)
            }
        }

        return TelemetryLog(
            channelNames = channelNames,
            channelUnits = channelUnits,
            timestamps = timestamps.toDoubleArray(),
            channelValues = valueLists.mapValues { it.value.toDoubleArray() },
            beaconMarkers = beaconMarkers,
            durationSeconds = timestamps.lastOrNull() ?: 0.0
        )
    }

    /** Fields in this format are always quoted and never contain an internal comma or quote. */
    private fun splitCsvLine(line: String): List<String> {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return emptyList()
        return trimmed.removePrefix("\"").removeSuffix("\"").split("\",\"")
    }
}
