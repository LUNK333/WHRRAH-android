package com.lunk333.whrrah.osmo

import android.content.SharedPreferences
import android.os.Handler
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.konraditurbe.osmosis.rsdk.RsdkProtocol
import kotlin.random.Random

/**
 * Is the camera recording? Speaks DJI's R-SDK remote protocol over the BLE link [OsmoLink] holds —
 * the same GATT characteristics as the media DUML, told apart by start byte (R-SDK `0xAA`, DUML
 * `0x55`), so monitoring and the WiFi clip fetch share one connection and run together.
 *
 * Handshake (from osmosis's RsdkController / DJI's Osmo-GPS-Controller-Demo): Connection Request
 * `0x00/0x19` → the camera asks for approval on its screen the first time → it answers with its own
 * request (`verify_mode=2`, `verify_data=0` = approved) → we ACK → subscribe to status `0x1D/0x05`.
 * Status pushes `0x1D/0x02` then carry recording state + elapsed record time ~2x a second.
 *
 * Recording started from anywhere (camera button, DJI Mic remotes) shows up here — the cue to
 * start/stop a logging session. Each start/stop is kept as a [RecordEvent] with phone clock times,
 * the start back-dated by the camera's own elapsed counter.
 *
 * All calls on the main thread.
 */
class RecordingMonitor internal constructor(
    private val main: Handler,
    prefs: SharedPreferences,
    private val write: (ByteArray) -> Boolean,
    private val log: (String) -> Unit,
) {
    enum class State { OFF, WAITING_APPROVAL, CONNECTED, FAILED }

    /** [wallMs]/[elapsedMs] are when the change happened (a start is back-dated by the camera's counter). */
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

    /** Fires on every recording start/stop (for wiring session logging to it). */
    var onRecordingChanged: ((RecordEvent) -> Unit)? = null

    // Stable controller identity: the camera remembers its approval by these.
    private val deviceId: Int = prefs.getInt("rsdk_device_id", 0).takeIf { it != 0 }
        ?: (Random.nextInt() or 1).also { prefs.edit().putInt("rsdk_device_id", it).apply() }
    private val controllerMac: ByteArray = prefs.getString("rsdk_mac", null)
        ?.let { hex -> ByteArray(6) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() } }
        ?: ByteArray(6).also { Random.nextBytes(it); prefs.edit().putString("rsdk_mac", it.joinToString("") { b -> "%02x".format(b) }).apply() }

    private var seq = 0
    private var unhandled = 0

    private val approvalTimeout = Runnable { fail("Camera didn't approve the remote — approve it on the camera's screen, then reconnect") }

    /** Re-subscribe whenever the feed goes quiet (some bodies answer a subscription only once). */
    private val statusPoll = object : Runnable {
        override fun run() {
            if (state != State.CONNECTED) return
            if (SystemClock.elapsedRealtime() - lastStatusElapsedMs > 2000) subscribe()
            main.postDelayed(this, 1500)
        }
    }

    /** BLE link is up: ask to connect as a remote. */
    fun begin() {
        stopTimers()
        recording = null
        state = State.WAITING_APPROVAL
        message = "Approve the remote on the camera's screen if it asks"
        send(RsdkProtocol.SET_GENERAL, RsdkProtocol.ID_CONNECTION, RsdkProtocol.CMD_WAIT_RESULT,
            RsdkProtocol.connectionRequest(deviceId, controllerMac, verifyMode = 0, verifyData = Random.nextInt(0, 10000)))
        main.postDelayed(approvalTimeout, 40_000)
    }

    /** BLE link went down (OsmoLink reconnects and calls [begin] again). */
    fun linkDown() {
        stopTimers()
        recording = null
        if (state != State.OFF && state != State.FAILED) { state = State.OFF; message = "BLE link down — reconnecting…" }
    }

    fun reset() {
        stopTimers()
        recording = null
        state = State.OFF
        message = ""
    }

    /** A notification starting with 0xAA. */
    fun onFrame(raw: ByteArray) {
        val f = RsdkProtocol.parse(raw) ?: return
        when {
            f.cmdSet == RsdkProtocol.SET_GENERAL && f.cmdId == RsdkProtocol.ID_CONNECTION -> onConnectionFrame(f)
            f.cmdSet == RsdkProtocol.SET_CAMERA && f.cmdId == RsdkProtocol.ID_STATUS_PUSH ->
                RsdkProtocol.parseCameraStatus(f.payload)?.let(::onStatus)
            f.cmdSet == RsdkProtocol.SET_CAMERA && f.cmdId == RsdkProtocol.ID_STATUS_PUSH_NEW ->
                RsdkProtocol.parseNewCameraStatus(f.payload)?.let { lastStatusElapsedMs = SystemClock.elapsedRealtime(); mode = it.label }
            else -> if (unhandled++ < 10) log("R-SDK: unhandled %02x/%02x (%d B)".format(f.cmdSet, f.cmdId, f.payload.size))
        }
    }

    private fun onConnectionFrame(f: RsdkProtocol.Frame) {
        if (state == State.CONNECTED) return
        if (f.isResponse) {
            if (f.payload.size > 4 && f.payload[4].toInt() != 0) fail("Camera rejected the remote (ret_code=${f.payload[4].toInt()})")
            return
        }
        if (f.payload.size < 29) return
        val verifyMode = f.payload[26].toInt() and 0xFF
        val verifyData = (f.payload[27].toInt() and 0xFF) or ((f.payload[28].toInt() and 0xFF) shl 8)
        if (verifyMode != 2) { log("R-SDK: unexpected verify_mode=$verifyMode"); return }
        if (verifyData != 0) return fail("Camera rejected the remote")
        main.removeCallbacks(approvalTimeout)
        send(RsdkProtocol.SET_GENERAL, RsdkProtocol.ID_CONNECTION, RsdkProtocol.ACK_NO_RESPONSE,
            RsdkProtocol.connectionResponse(deviceId, retCode = 0, cameraReserved = 0), useSeq = f.seq)
        state = State.CONNECTED
        message = "Waiting for camera status…"
        log("R-SDK: connected as a remote — subscribing to camera status")
        subscribe()
        main.postDelayed(statusPoll, 1500)
    }

    private fun onStatus(s: RsdkProtocol.CameraStatus) {
        lastStatusElapsedMs = SystemClock.elapsedRealtime()
        battery = s.battery
        recordTimeS = s.recordTimeS
        if (mode == null || mode?.contains('·') == false) mode = s.modeName
        message = ""
        val was = recording
        recording = s.recording
        if (was != null && was != s.recording) {
            val backMs = if (s.recording) s.recordTimeS * 1000L else 0L
            val e = RecordEvent(s.recording, System.currentTimeMillis() - backMs, SystemClock.elapsedRealtime() - backMs, s.recordTimeS)
            events.add(0, e)
            log(if (s.recording) "camera: recording STARTED" else "camera: recording STOPPED after ${s.recordTimeS} s")
            onRecordingChanged?.invoke(e)
        }
    }

    private fun subscribe() = send(RsdkProtocol.SET_CAMERA, RsdkProtocol.ID_STATUS_SUB, RsdkProtocol.CMD_NO_RESPONSE,
        RsdkProtocol.statusSubscription(pushMode = 3, pushFreq = 20))

    private fun send(cmdSet: Int, cmdId: Int, cmdType: Int, payload: ByteArray, useSeq: Int = nextSeq()) {
        write(RsdkProtocol.frame(cmdSet, cmdId, cmdType, payload, useSeq))
    }

    private fun nextSeq(): Int { seq = (seq + 1) and 0xFFFF; return seq }

    private fun stopTimers() {
        main.removeCallbacks(approvalTimeout)
        main.removeCallbacks(statusPoll)
    }

    private fun fail(reason: String) {
        stopTimers()
        state = State.FAILED
        message = reason
        log("monitor: $reason")
    }
}
