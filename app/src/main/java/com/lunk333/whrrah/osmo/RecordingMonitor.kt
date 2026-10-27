package com.lunk333.whrrah.osmo

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.konraditurbe.osmosis.rsdk.RsdkController
import dev.konraditurbe.osmosis.rsdk.RsdkProtocol

/**
 * Watches whether the camera is recording, over BLE only — no WiFi, so it can stay up for a whole
 * session in the car. Uses DJI's R-SDK remote protocol (vendored from osmosis): connection request
 * (approve on the camera the first time), then a camera-status subscription that pushes mode,
 * recording state and elapsed record time ~2x a second.
 *
 * Recording started from anywhere (camera button, DJI Mic remotes) shows up here, which is the cue
 * to start/stop a logging session. Each start/stop is kept as a [RecordEvent] with phone clock
 * times, back-dated by the camera's own elapsed counter, so it can later anchor video to data.
 *
 * Holds the camera's BLE link, so it can't run at the same time as [OsmoLink]'s WiFi fetch flow.
 * One instance per process; state is Compose snapshot state, written on the main thread.
 */
@SuppressLint("MissingPermission")
class RecordingMonitor private constructor(private val context: Context) : RsdkController.Listener {

    enum class State { OFF, CONNECTING, CONNECTED, FAILED }

    /** [wallMs]/[elapsedMs] are when the change happened (start back-dated by the camera's counter). */
    data class RecordEvent(val started: Boolean, val wallMs: Long, val elapsedMs: Long, val recordTimeS: Int)

    var state by mutableStateOf(State.OFF); private set
    var message by mutableStateOf(""); private set
    /** null until the first status push arrives. */
    var recording by mutableStateOf<Boolean?>(null); private set
    var recordTimeS by mutableStateOf(0); private set
    var battery by mutableStateOf(-1); private set
    var mode by mutableStateOf<String?>(null); private set
    var lastStatusElapsedMs by mutableStateOf(0L); private set
    val events = mutableStateListOf<RecordEvent>()

    /** Called on the main thread on every recording start/stop (for wiring session logging to it). */
    var onRecordingChanged: ((RecordEvent) -> Unit)? = null

    private var controller: RsdkController? = null
    private val link get() = OsmoLink.get(context)

    fun start(address: String) {
        stop()
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
            ?: return fail("Bluetooth unavailable")
        link.disconnect()   // one BLE link to the camera at a time
        recording = null
        state = State.CONNECTING
        message = "Connecting — approve the remote on the camera's screen if it asks"
        controller = RsdkController(context, this).also { it.connect(adapter.getRemoteDevice(address)) }
    }

    fun stop() {
        controller?.disconnect()
        controller = null
        if (state != State.FAILED) { state = State.OFF; message = "" }
        recording = null
    }

    override fun onLog(s: String) = link.appendLog(s)

    override fun onConnected() {
        state = State.CONNECTED
        message = "Connected — waiting for camera status"
    }

    override fun onStatus(status: RsdkProtocol.CameraStatus) {
        lastStatusElapsedMs = SystemClock.elapsedRealtime()
        battery = status.battery
        recordTimeS = status.recordTimeS
        if (mode == null || !mode!!.contains('·')) mode = status.modeName
        val was = recording
        val now = status.recording
        recording = now
        message = "Connected"
        if (was != null && was != now) {
            // A start is back-dated by the camera's elapsed counter (we see it up to a push late).
            val backMs = if (now) status.recordTimeS * 1000L else 0L
            val e = RecordEvent(now, System.currentTimeMillis() - backMs, SystemClock.elapsedRealtime() - backMs, status.recordTimeS)
            events.add(0, e)
            link.appendLog(if (now) "camera: recording STARTED" else "camera: recording STOPPED after ${status.recordTimeS} s")
            onRecordingChanged?.invoke(e)
        }
    }

    override fun onModeInfo(info: RsdkProtocol.ModeInfo) {
        lastStatusElapsedMs = SystemClock.elapsedRealtime()
        mode = info.label
    }

    override fun onDisconnected() {
        if (state == State.OFF) return
        controller = null
        recording = null
        fail("Camera BLE link dropped (camera off or out of range)")
    }

    override fun onFailed(reason: String) = fail(reason)

    private fun fail(reason: String) {
        state = State.FAILED
        message = reason
        link.appendLog("monitor: $reason")
    }

    companion object {
        @Volatile private var instance: RecordingMonitor? = null
        fun get(context: Context): RecordingMonitor =
            instance ?: synchronized(this) { instance ?: RecordingMonitor(context.applicationContext).also { instance = it } }
    }
}
