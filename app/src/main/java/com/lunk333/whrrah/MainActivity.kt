package com.lunk333.whrrah

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lunk333.whrrah.canvas.OverlayCanvas
import com.lunk333.whrrah.canvas.OverlayWidget
import com.lunk333.whrrah.canvas.WidgetType
import com.lunk333.whrrah.data.CsvScrubbableLog
import com.lunk333.whrrah.data.CsvTelemetryParser
import com.lunk333.whrrah.data.ScrubbableLog
import com.lunk333.whrrah.data.TelemetryLog
import com.lunk333.whrrah.data.XrkScrubbableLog
import com.lunk333.whrrah.ui.WhrrahApp
import com.lunk333.whrrah.ui.theme.WHRRAHTheme
import com.lunk333.whrrah.xrk.XrkLog
import com.lunk333.whrrah.xrk.XrkLogParser
import com.lunk333.whrrah.xrk.XrkNative
import com.lunk333.whrrah.video.VideoPlayerScreen
import java.io.File

// M0: CSV import + scrubber. Validates the data model and SAF file access
// before any video/canvas work starts (design doc §5, milestone M0).
class MainActivity : ComponentActivity() {
    private var sharedXrkUri by mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIncomingIntent(intent)
        setContent {
            WHRRAHTheme {
                Scaffold { innerPadding ->
                    WhrrahApp(
                        modifier = Modifier.fillMaxSize().padding(innerPadding),
                        legacyData = { m -> TelemetryScreen(modifier = m, incomingXrkUri = sharedXrkUri) },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    /**
     * Handles WHRRAH being used as a share target (manifest §SEND filter) -
     * RaceStudio3 Mobile has no "save to folder" export, only app-to-app
     * share, so this is how .xrk files actually get into WHRRAH from it.
     */
    private fun handleIncomingIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val uri = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }
        if (uri != null) sharedXrkUri = uri
    }
}

@Composable
fun TelemetryScreen(modifier: Modifier = Modifier, incomingXrkUri: Uri? = null) {
    var telemetryLog by remember { mutableStateOf<TelemetryLog?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var scrubTime by remember { mutableStateOf(0f) }

    var xrkLog by remember { mutableStateOf<XrkLog?>(null) }
    var xrkError by remember { mutableStateOf<String?>(null) }
    var xrkScrubTime by remember { mutableStateOf(0f) }

    var videoUri by remember { mutableStateOf<Uri?>(null) }

    // M3: canvas binds to whichever telemetry source was loaded most
    // recently, via the ScrubbableLog abstraction (so it doesn't care if
    // that's CSV or XRK data).
    var activeLog by remember { mutableStateOf<ScrubbableLog?>(null) }
    var canvasWidgets by remember { mutableStateOf<List<OverlayWidget>>(emptyList()) }
    var nextWidgetId by remember { mutableStateOf(0) }
    var canvasScrubTime by remember { mutableStateOf(0f) }

    val context = androidx.compose.ui.platform.LocalContext.current
    val openCsvLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            val lines = context.contentResolver.openInputStream(uri)
                ?.bufferedReader()
                ?.readLines()
                ?: error("Could not open the selected file")
            CsvTelemetryParser.parse(lines)
        }.onSuccess { log ->
            telemetryLog = log
            error = null
            scrubTime = 0f
            activeLog = CsvScrubbableLog(log)
            canvasScrubTime = 0f
        }.onFailure { e ->
            error = e.message ?: "Failed to parse CSV"
        }
    }

    fun loadXrk(uri: Uri) {
        runCatching {
            // XrkNative needs a real filesystem path (it shells out to libxrk's
            // file-reading entry point), so copy the SAF/share-provided content
            // out of cacheDir first - it can't read a content:// Uri directly.
            val tempFile = File(context.cacheDir, "import.xrk")
            context.contentResolver.openInputStream(uri)?.use { input ->
                tempFile.outputStream().use { output -> input.copyTo(output) }
            } ?: error("Could not open the selected file")

            XrkLogParser.parse(XrkNative.parseFullJson(tempFile.absolutePath))
        }.onSuccess { log ->
            xrkLog = log
            xrkError = null
            xrkScrubTime = 0f
            activeLog = XrkScrubbableLog(log)
            canvasScrubTime = 0f
        }.onFailure { e ->
            xrkError = e.message ?: "Failed to parse XRK"
        }
    }

    val openXrkLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) loadXrk(uri)
    }

    // Handles WHRRAH being used as a RaceStudio3 Mobile share target -
    // see MainActivity.handleIncomingIntent.
    LaunchedEffect(incomingXrkUri) {
        incomingXrkUri?.let { loadXrk(it) }
    }

    val openVideoLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        videoUri = uri
    }

    Column(modifier = modifier.padding(16.dp)) {
        Row {
            Button(onClick = { openCsvLauncher.launch(arrayOf("*/*")) }) {
                Text("Open CSV…")
            }
            Button(
                onClick = { openXrkLauncher.launch(arrayOf("*/*")) },
                modifier = Modifier.padding(start = 8.dp)
            ) {
                Text("Open XRK…")
            }
            Button(
                onClick = { openVideoLauncher.launch(arrayOf("video/*")) },
                modifier = Modifier.padding(start = 8.dp)
            ) {
                Text("Open Video…")
            }
        }

        error?.let {
            Text(text = "CSV error: $it", modifier = Modifier.padding(top = 8.dp))
        }
        xrkError?.let {
            Text(text = "XRK error: $it", modifier = Modifier.padding(top = 8.dp))
        }

        videoUri?.let { uri ->
            Divider(modifier = Modifier.padding(vertical = 8.dp))
            VideoPlayerScreen(uri = uri)
        }

        activeLog?.let { log ->
            Divider(modifier = Modifier.padding(vertical = 8.dp))
            Text("Canvas (bound to most recently loaded CSV/XRK data)", modifier = Modifier.padding(bottom = 4.dp))
            Row {
                Button(onClick = {
                    canvasWidgets = canvasWidgets + OverlayWidget(id = nextWidgetId++, type = WidgetType.LAP_TIMER)
                }) {
                    Text("+ Lap Timer")
                }
                Button(
                    onClick = {
                        canvasWidgets = canvasWidgets + OverlayWidget(
                            id = nextWidgetId++,
                            type = WidgetType.NUMERIC_DISPLAY,
                            channel = log.channelNames.firstOrNull()
                        )
                    },
                    modifier = Modifier.padding(start = 8.dp)
                ) {
                    Text("+ Numeric")
                }
                Button(
                    onClick = {
                        canvasWidgets = canvasWidgets + OverlayWidget(
                            id = nextWidgetId++,
                            type = WidgetType.BAR_GRAPH,
                            channel = log.channelNames.firstOrNull()
                        )
                    },
                    modifier = Modifier.padding(start = 8.dp)
                ) {
                    Text("+ Bar Graph")
                }
            }
            Text(
                text = "Drag a widget's title to move it, drag the corner handle to resize, " +
                    "tap › to cycle its channel, tap × to remove it.",
                modifier = Modifier.padding(vertical = 4.dp)
            )

            OverlayCanvas(
                widgets = canvasWidgets,
                onWidgetsChange = { canvasWidgets = it },
                log = log,
                timeSeconds = canvasScrubTime.toDouble(),
                modifier = Modifier.padding(vertical = 8.dp)
            )

            Slider(
                value = canvasScrubTime,
                onValueChange = { canvasScrubTime = it },
                valueRange = 0f..log.durationSeconds.toFloat().coerceAtLeast(0f)
            )
            Text(text = formatDuration(canvasScrubTime.toDouble()))
        }

        xrkLog?.let { log ->
            Divider(modifier = Modifier.padding(vertical = 8.dp))
            Text(
                text = "XRK: ${log.vehicle ?: "?"} / ${log.driver ?: "?"}, " +
                    "${log.channels.size} channels, ${formatDuration(log.durationMs / 1000.0)} duration, " +
                    "${log.laps.size} laps",
                modifier = Modifier.padding(bottom = 8.dp)
            )

            Slider(
                value = xrkScrubTime,
                onValueChange = { xrkScrubTime = it },
                valueRange = 0f..(log.durationMs / 1000.0).toFloat().coerceAtLeast(0f)
            )
            Text(text = formatDuration(xrkScrubTime.toDouble()))

            Divider(modifier = Modifier.padding(vertical = 8.dp))

            val scrubTimeMs = (xrkScrubTime * 1000).toLong()
            LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
                items(log.channels) { ch ->
                    // Each channel has its own timebase, so the nearest-sample
                    // lookup is per-channel rather than a single shared index
                    // (unlike CSV, where every channel shares one timestamp column).
                    val index = ch.nearestSampleIndex(scrubTimeMs)
                    val value = if (index >= 0) ch.values[index] else Double.NaN
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(ch.name)
                        Text("%.3f %s".format(value, ch.units))
                    }
                }
            }
            Divider(modifier = Modifier.padding(vertical = 8.dp))
        }

        val log = telemetryLog
        if (log != null) {
            Text(
                text = "${log.channelNames.size} channels, " +
                    "${log.timestamps.size} samples, " +
                    "${formatDuration(log.durationSeconds)} duration, " +
                    "${log.beaconMarkers.size.coerceAtLeast(1) - 1} laps",
                modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
            )

            Slider(
                value = scrubTime,
                onValueChange = { scrubTime = it },
                valueRange = 0f..log.durationSeconds.toFloat().coerceAtLeast(0f)
            )
            Text(text = formatDuration(scrubTime.toDouble()))

            Divider(modifier = Modifier.padding(vertical = 8.dp))

            val index = log.nearestSampleIndex(scrubTime.toDouble())
            LazyColumn {
                items(log.channelNames) { name ->
                    val value = if (index >= 0) log.channelValues.getValue(name)[index] else Double.NaN
                    val unit = log.channelUnits[name].orEmpty()
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(name)
                        Text("%.3f %s".format(value, unit))
                    }
                }
            }
        }
    }
}

private fun formatDuration(seconds: Double): String {
    val totalMs = (seconds * 1000).toLong()
    val m = totalMs / 60000
    val s = (totalMs % 60000) / 1000
    val ms = totalMs % 1000
    return "%d:%02d.%03d".format(m, s, ms)
}
