package com.lunk333.whrrah.osmo

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.konraditurbe.osmosis.ble.GattClient
import dev.konraditurbe.osmosis.duml.DjiMessage
import dev.konraditurbe.osmosis.rsdk.RsdkProtocol
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.random.Random

/**
 * The camera over **BLE only**, as a DJI R-SDK remote — what a session uses while driving. No WiFi:
 * the camera stays in capture mode (no viewer-mode lockout) and there's nothing to refresh after
 * each run. Clips are matched to runs later, in one WiFi visit ([OsmoLink] + SessionController's
 * Match clips).
 *
 * Protocol (osmosis RsdkProtocol, from DJI's Osmo-GPS-Controller-Demo): connection request
 * `0x00/0x19` → the camera asks for approval on its screen the first time → it sends its own request
 * (`verify_mode=2`, `verify_data=0` = approved) → we ACK → subscribe to status `0x1D/0x05` → status
 * pushes `0x1D/0x02` (recording state ~2×/s). Record start/stop is `0x1D/0x03`.
 *
 * Recording started anywhere (camera button, DJI Mic remotes, [setRecording]) fires
 * [onRecordingChanged] with phone-clock times. Holds the camera's BLE link, so it's stopped while
 * [OsmoLink] uses the camera (Match clips, Dev options). One instance per process; main thread.
 */
@SuppressLint("MissingPermission")
class CameraMonitor private constructor(private val context: Context) : GattClient.Listener {

    enum class State { OFF, CONNECTING, WAITING_APPROVAL, CONNECTED }

    var state by mutableStateOf(State.OFF); private set
    var message by mutableStateOf(""); private set
    /** null until the first status push arrives (or after the link drops). */
    var recording by mutableStateOf<Boolean?>(null); private set
    var battery by mutableStateOf(-1); private set
    var mode by mutableStateOf<String?>(null); private set
    var lastStatusElapsedMs by mutableStateOf(0L); private set
    /** Phone-clock start of the recording in progress (elapsedRealtime), for the REC timer. */
    var recStartElapsedMs by mutableStateOf(0L); private set
    val log = mutableStateListOf<String>()

    var onRecordingChanged: ((OsmoLink.RecordEvent) -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    private val prefs = context.getSharedPreferences("osmo", Context.MODE_PRIVATE)
    private var gatt: GattClient? = null
    private var address: String? = null
    private var wantLink = false
    private var seq = 0
    private var statusFrames = 0

    // Stable controller identity: the camera remembers its approval by these.
    private val deviceId: Int = prefs.getInt("rsdk_device_id", 0).takeIf { it != 0 }
        ?: (Random.nextInt() or 1).also { prefs.edit().putInt("rsdk_device_id", it).apply() }
    private val controllerMac: ByteArray = prefs.getString("rsdk_mac", null)
        ?.let { hex -> ByteArray(6) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() } }
        ?: ByteArray(6).also { Random.nextBytes(it); prefs.edit().putString("rsdk_mac", it.joinToString("") { b -> "%02x".format(b) }).apply() }

    val connected: Boolean get() = state == State.CONNECTED

    // ---- link --------------------------------------------------------------------------------

    /** Connect to the camera last paired in the camera test bench (or [addr]). Keeps reconnecting. */
    fun start(addr: String? = null) {
        val a = addr ?: prefs.getString("last_addr", null) ?: return fail("No camera paired yet — pair one in Dev options → Camera test bench")
        if (wantLink && gatt != null && address == a) return
        // An old link still going down: wait for it, then start (closing it later would kill the new one).
        if (gatt != null) { stop { start(a) }; return }
        // One BLE link to the camera at a time: drop the WiFi-side link (Dev options) if it's up.
        OsmoLink.get(context).let { if (it.phase != OsmoLink.Phase.IDLE) it.disconnect() }
        address = a
        wantLink = true
        open()
        com.lunk333.whrrah.LinkService.update(context)
    }

    /** True while a BLE link (or a disconnect in progress) is held. */
    val holdsLink: Boolean get() = gatt != null

    private var onStopped: (() -> Unit)? = null

    /**
     * Drop the link. [then] runs once the link is really down: the camera keeps treating a link as an
     * R-SDK remote session (and ignores media pairing on it) until the radio link itself goes, and
     * Android reuses a still-up link for the next connect — so hand-overs must wait for it.
     */
    fun stop(then: (() -> Unit)? = null) {
        wantLink = false
        main.removeCallbacksAndMessages(null)
        state = State.OFF; message = ""; recording = null
        val g = gatt
        if (g == null) { then?.let { main.post(it) }; return }
        onStopped = then
        g.disconnect()
        // If the disconnect callback never comes, give up waiting after a few seconds.
        main.postDelayed({ finishStop() }, 4000)
    }

    private fun finishStop() {
        gatt?.close(); gatt = null
        val cb = onStopped; onStopped = null
        if (cb != null) { logLine("link down — handing over"); main.postDelayed(cb, 1500) }   // let the radio settle
    }

    private fun open() {
        val a = address ?: return
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter ?: return fail("Bluetooth unavailable")
        state = State.CONNECTING; message = "Connecting to the camera over Bluetooth…"
        logLine("connecting to $a")
        // armPairing = false: R-SDK does its own 0x00/0x19 handshake instead of the media pairing.
        gatt = GattClient(context, this, armPairing = false).also { it.connect(adapter.getRemoteDevice(a)) }
    }

    private val reconnect = Runnable { if (wantLink && gatt == null) open() }

    private fun linkDown(why: String) {
        main.removeCallbacks(approvalTimeout); main.removeCallbacks(statusPoll)
        gatt?.close(); gatt = null
        recording = null
        if (!wantLink) { state = State.OFF; return }
        state = State.CONNECTING; message = "$why — reconnecting…"
        main.postDelayed(reconnect, 3000)
    }

    override fun onReady(gatt: GattClient) { main.post {
        state = State.WAITING_APPROVAL
        message = "Approve the remote on the camera's screen if it asks"
        send(RsdkProtocol.SET_GENERAL, RsdkProtocol.ID_CONNECTION, RsdkProtocol.CMD_WAIT_RESULT,
            RsdkProtocol.connectionRequest(deviceId, controllerMac, verifyMode = 0, verifyData = Random.nextInt(0, 10000)))
        main.postDelayed(approvalTimeout, 40_000)
    } }

    private val approvalTimeout = Runnable {
        logLine("no approval within 40 s")
        message = "Camera didn't approve the remote — approve it on the camera, it'll retry"
        gatt?.disconnect()
    }

    override fun onDisconnected() { main.post {
        logLine("BLE disconnected")
        if (!wantLink) { main.removeCallbacksAndMessages(null); finishStop(); return@post }
        linkDown("Camera link dropped")
    } }

    override fun onLog(s: String) = logLine(s)

    override fun onNotification(sourceChar: UUID, raw: ByteArray, parsed: DjiMessage?) {
        val f = RsdkProtocol.parse(raw) ?: return
        main.post {
            when {
                f.cmdSet == RsdkProtocol.SET_GENERAL && f.cmdId == RsdkProtocol.ID_CONNECTION -> onConnectionFrame(f)
                f.cmdSet == RsdkProtocol.SET_CAMERA && f.cmdId == RsdkProtocol.ID_STATUS_PUSH ->
                    RsdkProtocol.parseCameraStatus(f.payload)?.let { onStatus(it, f.payload) }
                f.cmdSet == RsdkProtocol.SET_CAMERA && f.cmdId == RsdkProtocol.ID_STATUS_PUSH_NEW ->
                    RsdkProtocol.parseNewCameraStatus(f.payload)?.let { lastStatusElapsedMs = SystemClock.elapsedRealtime(); mode = it.label }
                f.cmdSet == RsdkProtocol.SET_CAMERA && f.cmdId == RsdkProtocol.ID_RECORD_CTRL ->
                    logLine("record command reply: ${f.payload.joinToString("") { "%02x".format(it) }}")
                f.cmdSet == RsdkProtocol.SET_CAMERA && f.cmdId == RsdkProtocol.ID_STATUS_SUB -> Unit   // subscription ack
                else -> logLine("R-SDK %02x/%02x (%d B)".format(f.cmdSet, f.cmdId, f.payload.size))
            }
        }
    }

    private fun onConnectionFrame(f: RsdkProtocol.Frame) {
        if (state == State.CONNECTED) return
        if (f.isResponse) {
            if (f.payload.size > 4 && f.payload[4].toInt() != 0) logLine("camera rejected the remote (ret_code=${f.payload[4]})")
            return
        }
        if (f.payload.size < 29) return
        val verifyMode = f.payload[26].toInt() and 0xFF
        val verifyData = (f.payload[27].toInt() and 0xFF) or ((f.payload[28].toInt() and 0xFF) shl 8)
        if (verifyMode != 2) return logLine("unexpected verify_mode=$verifyMode")
        if (verifyData != 0) { message = "Camera rejected the remote"; return }
        main.removeCallbacks(approvalTimeout)
        send(RsdkProtocol.SET_GENERAL, RsdkProtocol.ID_CONNECTION, RsdkProtocol.ACK_NO_RESPONSE,
            RsdkProtocol.connectionResponse(deviceId, retCode = 0, cameraReserved = 0), useSeq = f.seq)
        state = State.CONNECTED
        message = "Connected — waiting for camera status"
        logLine("connected as a remote — subscribing to status")
        subscribe()
        main.postDelayed(statusPoll, 1500)
    }

    /** Re-subscribe whenever the feed goes quiet (some bodies answer a subscription only once). */
    private val statusPoll = object : Runnable {
        override fun run() {
            if (state != State.CONNECTED) return
            if (SystemClock.elapsedRealtime() - lastStatusElapsedMs > 2000) subscribe()
            main.postDelayed(this, 1500)
        }
    }

    private fun onStatus(s: RsdkProtocol.CameraStatus, raw: ByteArray) {
        // The first few frames go to the log raw: the Action 4's status layout is only known second-hand.
        if (statusFrames++ < 3) logLine("status: mode=${s.modeName} status=0x%02x rec=${s.recording} t=${s.recordTimeS}s [%s]"
            .format(s.status, raw.joinToString("") { "%02x".format(it) }))
        val now = SystemClock.elapsedRealtime()
        lastStatusElapsedMs = now
        battery = s.battery
        if (mode == null || mode?.contains('·') == false) mode = s.modeName
        message = ""
        val was = recording
        recording = s.recording
        if (s.recording && was != true) recStartElapsedMs = now - s.recordTimeS * 1000L
        if (was == null || was == s.recording) return
        val clipS = if (s.recording) s.recordTimeS else ((now - recStartElapsedMs) / 1000).toInt()
        val backMs = if (s.recording) s.recordTimeS * 1000L else 0L
        val e = OsmoLink.RecordEvent(s.recording, System.currentTimeMillis() - backMs, now - backMs, clipS)
        logLine(if (s.recording) "recording STARTED" else "recording STOPPED (${clipS} s)")
        onRecordingChanged?.invoke(e)
    }

    /** Start or stop recording on the camera (R-SDK record control). The status push confirms it. */
    fun setRecording(start: Boolean): Boolean {
        if (state != State.CONNECTED) return false
        logLine("sending record ${if (start) "start" else "stop"}")
        send(RsdkProtocol.SET_CAMERA, RsdkProtocol.ID_RECORD_CTRL, RsdkProtocol.CMD_WAIT_RESULT, RsdkProtocol.recordControl(deviceId, start))
        return true
    }

    private fun subscribe() = send(RsdkProtocol.SET_CAMERA, RsdkProtocol.ID_STATUS_SUB, RsdkProtocol.CMD_NO_RESPONSE,
        RsdkProtocol.statusSubscription(pushMode = 3, pushFreq = 20))

    private fun send(cmdSet: Int, cmdId: Int, cmdType: Int, payload: ByteArray, useSeq: Int = nextSeq()) {
        gatt?.writeCommand(RsdkProtocol.frame(cmdSet, cmdId, cmdType, payload, useSeq))
    }

    private fun nextSeq(): Int { seq = (seq + 1) and 0xFFFF; return seq }

    private fun fail(msg: String) { state = State.OFF; message = msg; logLine(msg) }

    private fun logLine(s: String) {
        android.util.Log.i("CameraMonitor", s)
        val line = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date()) + "  " + s
        if (Looper.myLooper() == Looper.getMainLooper()) append(line) else main.post { append(line) }
    }

    private fun append(line: String) { log.add(line); while (log.size > 200) log.removeAt(0) }

    companion object {
        @Volatile private var instance: CameraMonitor? = null
        fun get(context: Context): CameraMonitor =
            instance ?: synchronized(this) { instance ?: CameraMonitor(context.applicationContext).also { instance = it } }
    }
}
