package com.lunk333.whrrah.osmo

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.ContentValues
import android.content.Context
import android.net.LinkProperties
import android.net.Network
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.lunk333.whrrah.osmo.gyro.ChannelRangeReader
import com.lunk333.whrrah.osmo.gyro.GyroSamples
import com.lunk333.whrrah.osmo.gyro.GyroTrack
import com.lunk333.whrrah.osmo.gyro.HttpRangeReader
import com.lunk333.whrrah.osmo.gyro.RangeReader
import com.lunk333.whrrah.osmo.gyro.fetchGyro
import com.lunk333.whrrah.osmo.gyro.samples1kHz
import dev.konraditurbe.osmosis.ble.BleConstants
import dev.konraditurbe.osmosis.ble.GattClient
import dev.konraditurbe.osmosis.camera.CameraSession
import dev.konraditurbe.osmosis.camera.PathAddressing
import dev.konraditurbe.osmosis.core.CameraFile
import dev.konraditurbe.osmosis.core.StorageRules
import dev.konraditurbe.osmosis.duml.DjiMessage
import dev.konraditurbe.osmosis.duml.DjiPairMessagePayload
import dev.konraditurbe.osmosis.duml.OsmoCommands
import dev.konraditurbe.osmosis.net.ApJoiner
import dev.konraditurbe.osmosis.net.HttpClient
import java.io.FileInputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Connects to a DJI Osmo camera and pulls a clip's gyro track over WiFi (tasks B1c–B1f):
 *
 *  1. **BLE** (B1c): GATT `fff5`/`fff4` DUML. Session wake `0x00/0x2b [04 00]` → SetPairingPIN
 *     `0x07/0x45` (approve on the camera the first time) → `0x53/0x10` wake → read SSID
 *     `0x07/0x07` and password `0x07/0x0e`.
 *  2. **WiFi** (B1d): join the camera AP with WifiNetworkSpecifier and bind the process to it.
 *  3. **List** (B1e): DUML-over-UDP datalink media list.
 *  4. **Gyro** (B1f): range-read the clip's `djmd` track over HTTP; see [fetchGyro].
 *
 * The protocol work is vendored from osmosis (`dev.konraditurbe.osmosis`, MIT), whose sequence and
 * timings come from HCI snoops of DJI Mimo and were proven on an Action 4. This class only drives it.
 * The BLE link normally drops once the WiFi side comes up; that's expected.
 *
 * One instance per process ([get]) so a screen rotation doesn't tear the camera session down.
 * State is Compose snapshot state, written only on the main thread.
 */
@SuppressLint("MissingPermission")
class OsmoLink private constructor(private val context: Context) : GattClient.Listener, ApJoiner.Listener {

    enum class Phase { IDLE, SCANNING, CONNECTING, PAIRING, APPROVE_ON_CAMERA, READING_WIFI, NEED_PASSWORD, JOINING_WIFI, LISTING, READY, FAILED }

    data class Found(val name: String, val address: String, val rssi: Int)

    data class GyroResult(
        val clip: String,
        val track: GyroTrack,
        val samples: GyroSamples,
        val seconds: Double,
        val savedTo: String?,
    ) {
        val msPerRequest: Double get() = seconds * 1000 / track.requests.coerceAtLeast(1)
    }

    var phase by mutableStateOf(Phase.IDLE); private set
    var status by mutableStateOf(""); private set
    var ssid by mutableStateOf<String?>(null); private set
    val found = mutableStateListOf<Found>()
    val files = mutableStateListOf<CameraFile>()
    val log = mutableStateListOf<String>()
    var gyroBusy by mutableStateOf(false); private set
    var gyroProgress by mutableStateOf(0f); private set
    var gyro by mutableStateOf<GyroResult?>(null); private set

    private val main = Handler(Looper.getMainLooper())
    private val prefs = context.getSharedPreferences("osmo", Context.MODE_PRIVATE)
    private val http = HttpClient(log = ::logLine)
    private var gatt: GattClient? = null
    private var ap: ApJoiner? = null
    private var session: CameraSession? = null
    private var address: String? = null
    private var password: String? = null
    private var pairStatus = -1
    private var paired = false
    private var joinStarted = false
    private val gyroCancel = AtomicBoolean(false)

    // ---- scan --------------------------------------------------------------------------------

    private val scanner get() =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter?.bluetoothLeScanner

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val rec = result.scanRecord ?: return
            val md = rec.manufacturerSpecificData
            val isDji = (0 until (md?.size() ?: 0)).any { BleConstants.isDjiCompanyId(md!!.keyAt(it)) }
            val name = rec.deviceName ?: result.device.name ?: return
            if (!isDji && !name.startsWith("Osmo")) return
            main.post {
                val f = Found(name, result.device.address, result.rssi)
                val i = found.indexOfFirst { it.address == f.address }
                if (i >= 0) found[i] = f else found.add(f)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            main.post { fail("BLE scan failed (error $errorCode)") }
        }
    }

    val lastCamera: Found? get() = prefs.getString("last_addr", null)?.let { Found(prefs.getString("last_name", null) ?: it, it, 0) }

    fun startScan() {
        val s = scanner ?: return fail("Bluetooth is off or unavailable")
        found.clear()
        setPhase(Phase.SCANNING, "Scanning for DJI cameras… (wake the camera if it's asleep)")
        s.startScan(scanCallback)
        main.postDelayed(scanTimeout, 15_000)
    }

    private val scanTimeout = Runnable { stopScan() }

    fun stopScan() {
        main.removeCallbacks(scanTimeout)
        runCatching { scanner?.stopScan(scanCallback) }
        if (phase == Phase.SCANNING) setPhase(Phase.IDLE, "Scan stopped")
    }

    // ---- BLE: pair + read WiFi credentials (B1c) ---------------------------------------------

    /** [name] is the advertised BLE name, which is also the camera's WiFi SSID if BLE doesn't send one. */
    fun connect(addr: String, name: String?) {
        stopScan()
        disconnect()
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
            ?: return fail("Bluetooth unavailable")
        address = addr
        prefs.edit().putString("last_addr", addr).apply()
        password = prefs.getString("pass_$addr", null)
        pairStatus = -1; paired = false; joinStarted = false
        ssid = name
        prefs.edit().putString("last_name", name).apply()
        setPhase(Phase.CONNECTING, "Connecting to $addr over BLE…")
        gatt = GattClient(context, this).also { it.connect(adapter.getRemoteDevice(addr)) }
    }

    override fun onReady(gatt: GattClient) { main.post {
        setPhase(Phase.PAIRING, "BLE connected — pairing…")
        // Mimo's order: session wake first, pairing a beat later (back-to-back fff5 writes drop).
        gatt.writeCommand(OsmoCommands.sessionPing(OsmoCommands.SESSION_WAKE))
        main.postDelayed({ sendPair() }, 120)
        for (delay in longArrayOf(2500, 5000)) main.postDelayed({
            if (pairStatus == -1 && !paired && this.gatt != null) { logLine("no pairing reply yet — re-sending"); sendPair() }
        }, delay)
    } }

    private fun sendPair() {
        gatt?.writeCommand(OsmoCommands.setPairingPin("osmo", identifier = DjiPairMessagePayload.DEFAULT_IDENTIFIER))
    }

    override fun onNotification(sourceChar: UUID, raw: ByteArray, parsed: DjiMessage?) {
        parsed ?: return
        // The camera sends some messages as REQUESTS (flags 0x40) and drops the link if they go
        // unanswered: reply with flags 0xC0, swapped target, echoed id. Device-info gets our "APP" blob.
        if (parsed.flags == 0x40) {
            val respTarget = ((parsed.target and 0xFF) shl 8) or ((parsed.target shr 8) and 0xFF)
            val payload = if (parsed.cmdSet == 0x00 && parsed.cmdId == 0x81) OsmoCommands.APP_DEVICE_INFO else parsed.payload
            gatt?.writeCommand(DjiMessage(respTarget, parsed.id, (parsed.type and 0xFFFF00) or 0xC0, payload).encode())
            // First-time approval arrives as a 0x07/0x46 request, not a response.
            if (parsed.cmdSet == 0x07 && parsed.cmdId == 0x46) main.post { onPaired() }
            return
        }
        if (parsed.cmdSet != 0x07) return
        val p = parsed.payload
        main.post {
            when (parsed.cmdId) {
                0x45 -> {
                    pairStatus = if (p.size >= 2) p[1].toInt() and 0xFF else 0
                    when (pairStatus) {
                        0x01 -> onPaired()
                        0x02 -> setPhase(Phase.APPROVE_ON_CAMERA, "Approve the connection on the camera's screen")
                        else -> logLine("pairing reply status=0x%02x".format(pairStatus))
                    }
                }
                0x46 -> onPaired()
                0x07 -> statusPackString(p)?.takeIf { it.isNotEmpty() }?.let { ssid = it; logLine("camera SSID: $it") }
                0x0E -> statusPackString(p)?.takeIf { it.isNotEmpty() }?.let { pass ->
                    password = pass
                    address?.let { prefs.edit().putString("pass_$it", pass).apply() }
                    logLine("WiFi password read over BLE (${pass.length} chars)")
                    maybeJoin()
                }
            }
        }
    }

    private fun onPaired() {
        if (paired) return
        paired = true
        setPhase(Phase.READING_WIFI, "Paired — waking the camera's WiFi…")
        // Post-pair sequence and spacing from the Mimo snoop: 0x53/0x10 (the actual wake), then creds.
        main.postDelayed({ gatt?.writeCommand(OsmoCommands.session5310()) }, 100)
        main.postDelayed({ gatt?.writeCommand(OsmoCommands.wifiQuery(0x07, id = 0x8007)) }, 900)
        main.postDelayed({ gatt?.writeCommand(OsmoCommands.wifiQuery(0x0E, id = 0x800E)) }, 1400)
        main.postDelayed({
            if (joinStarted) return@postDelayed
            when {
                ssid == null -> fail("Camera never sent its WiFi name over BLE")
                password != null -> { logLine("no password over BLE — using the saved one"); maybeJoin() }
                else -> setPhase(Phase.NEED_PASSWORD, "Enter the camera's WiFi password (camera Settings → Wireless)")
            }
        }, 4500)
    }

    fun submitPassword(pass: String) {
        password = pass.trim()
        address?.let { prefs.edit().putString("pass_$it", password).apply() }
        maybeJoin()
    }

    private fun statusPackString(p: ByteArray): String? {
        if (p.size < 2) return null
        val len = p[1].toInt() and 0xFF
        return if (2 + len > p.size) null else String(p, 2, len, Charsets.US_ASCII)
    }

    override fun onDisconnected() { main.post {
        logLine("BLE disconnected")
        gatt?.close(); gatt = null
        // Dropping BLE after the WiFi handoff is normal; before it, the connect failed.
        if (!joinStarted && phase != Phase.FAILED && phase != Phase.IDLE) fail("BLE link dropped before WiFi came up — try again")
    } }

    // ---- WiFi join (B1d) ----------------------------------------------------------------------

    private fun maybeJoin() {
        val s = ssid ?: return
        val p = password ?: return
        if (joinStarted) return
        if (Build.VERSION.SDK_INT < 29) return fail("Joining the camera WiFi needs Android 10+")
        joinStarted = true
        setPhase(Phase.JOINING_WIFI, "Joining camera WiFi \"$s\" — accept the Android prompt")
        // The AP takes a few seconds to come up; the system dialog keeps searching until it does.
        main.postDelayed({ ap = ApJoiner(context, this).also { it.join(s, p) } }, 3000)
    }

    override fun onNetwork(network: Network, link: LinkProperties?) { main.post {
        logLine("WiFi up: ${link?.linkAddresses?.joinToString() ?: "?"}")
        gatt?.disconnect()   // done with BLE; the camera expects the handoff
        listFiles()
    } }

    override fun onFailed(reason: String) { main.post { fail(reason) } }

    override fun onLost() { main.post {
        logLine("camera WiFi lost")
        session?.close(); session = null
        if (phase != Phase.IDLE) fail("Camera WiFi dropped (camera asleep or out of range) — reconnect")
    } }

    // ---- file list (B1e) ----------------------------------------------------------------------

    fun listFiles() {
        setPhase(Phase.LISTING, "Reading the camera's file list…")
        Thread {
            session?.close()
            // Action 4 = udp/9004 after a tcp/7001 poke; Action 5 Pro = udp/10004, no poke.
            var s = CameraSession(::logLine)
            var list = runCatching { s.fetchFileList(IP) }.getOrElse { logLine("list error: ${it.message}"); emptyList() }
            if (!s.handshakeOk) {
                s.close()
                logLine("nothing on udp/9004 — trying udp/10004")
                s = CameraSession(::logLine, port = 10004, tcpPoke = false)
                list = runCatching { s.fetchFileList(IP) }.getOrElse { logLine("list error: ${it.message}"); emptyList() }
            }
            if (!s.handshakeOk) { s.close(); main.post { fail("Camera didn't answer the file-list request") }; return@Thread }
            s.startKeepAlive()   // holds the AP up between actions
            session = s
            val videos = list.filter { it.ext == "MP4" || it.ext == "MOV" }
                .map { it.copy(storage = resolveStorage(it)) }
                .sortedWith(compareByDescending<CameraFile> { it.timestamp }.thenByDescending { it.seq })
            main.post {
                files.clear(); files.addAll(videos)
                setPhase(Phase.READY, "Connected — ${videos.size} videos on camera")
            }
        }.start()
    }

    private val storageForBit = HashMap<Int, Int>()

    /** The `/v2?storage=N` mount for a file: from the listing if known, else handle bit + one HEAD (osmosis's rule). */
    private fun resolveStorage(f: CameraFile): Int {
        if (f.storageKnown) return f.storage
        val bit = StorageRules.mountGuess(false, f.handle, f.cmdHandle)
            ?: return intArrayOf(0, 1).firstOrNull { http.headCode(PathAddressing.byPath(it, f.path)) == 200 } ?: 0
        return storageForBit.getOrPut(bit) {
            when {
                http.headCode(PathAddressing.byPath(bit, f.path)) == 200 -> bit
                http.headCode(PathAddressing.byPath(1 - bit, f.path)) == 200 -> 1 - bit
                else -> bit
            }
        }
    }

    // ---- gyro (B1f) ---------------------------------------------------------------------------

    /** Pull [f]'s gyro over WiFi. [windowSeconds] = null for the whole clip. */
    fun fetchCameraGyro(f: CameraFile, windowSeconds: Int?, parallelism: Int) {
        val fps = f.resLabel?.removeSuffix("fps")?.toIntOrNull() ?: 30
        val frames = windowSeconds?.let { 0 until it * fps }
        runGyro(f.name, frames, parallelism) { HttpRangeReader(f.path, f.storage) }
    }

    /** Decode a clip already on the phone (e.g. downloaded with DJI Mimo). */
    fun decodeLocalGyro(uri: Uri, name: String) {
        runGyro(name, null, 1) {
            val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: error("can't open $uri")
            ChannelRangeReader(FileInputStream(pfd.fileDescriptor).channel, pfd)
        }
    }

    fun cancelGyro() = gyroCancel.set(true)

    private fun runGyro(clip: String, frames: IntRange?, parallelism: Int, open: () -> RangeReader) {
        if (gyroBusy) return
        gyroBusy = true; gyroProgress = 0f; gyroCancel.set(false)
        logLine("gyro: $clip ${frames?.let { "frames ${it.first}..${it.last}" } ?: "whole clip"}, $parallelism parallel")
        Thread {
            val t0 = SystemClock.elapsedRealtime()
            val result = runCatching {
                open().use { r ->
                    val track = fetchGyro(r, frames, parallelism, gyroCancel::get) { done, total ->
                        main.post { gyroProgress = done.toFloat() / total }
                    }
                    val secs = (SystemClock.elapsedRealtime() - t0) / 1000.0
                    val samples = samples1kHz(track)
                    GyroResult(clip, track, samples, secs, saveCsv(clip, samples))
                }
            }
            main.post {
                gyroBusy = false
                result.onSuccess { g ->
                    gyro = g
                    logLine("gyro: ${g.samples.size} samples from ${g.track.frames.size}/${g.track.totalFrames} frames, " +
                        "%.1f MB, %d requests in %.1f s = %.1f ms/request".format(
                            g.track.bytesRead / 1e6, g.track.requests, g.seconds, g.msPerRequest))
                    if (g.samples.size == 0) logLine("gyro: no 1 kHz IMU data in this clip — its recording format doesn't carry gyro")
                }.onFailure { logLine("gyro failed: ${it.javaClass.simpleName}: ${it.message}") }
            }
        }.start()
    }

    /** Write t_ms,w,x,y,z to Downloads/WHRRAH/ (same columns as the Python CLI). */
    private fun saveCsv(clip: String, s: GyroSamples): String? {
        if (s.size == 0 || Build.VERSION.SDK_INT < 29) return null
        val name = clip.substringBeforeLast('.') + "_gyro_" + SimpleDateFormat("HHmmss", Locale.US).format(Date()) + ".csv"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/csv")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/WHRRAH")
        }
        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
        context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { w ->
            w.write("t_ms,w,x,y,z\n")
            for (i in 0 until s.size) w.write("${s.tMs[i]},${s.w[i]},${s.x[i]},${s.y[i]},${s.z[i]}\n")
        }
        return "Downloads/WHRRAH/$name"
    }

    // ---- teardown / util ----------------------------------------------------------------------

    fun disconnect() {
        main.removeCallbacksAndMessages(null)
        gyroCancel.set(true)
        session?.let { s -> Thread { runCatching { s.close() } }.start() }
        session = null
        ap?.release(); ap = null
        gatt?.disconnect(); gatt?.close(); gatt = null
        files.clear()
        ssid = null
        setPhase(Phase.IDLE, "Disconnected")
    }

    private fun setPhase(p: Phase, msg: String) {
        phase = p; status = msg; logLine(msg)
    }

    private fun fail(msg: String) = setPhase(Phase.FAILED, msg)

    override fun onLog(s: String) = logLine(s)

    private fun logLine(s: String) {
        val line = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date()) + "  " + s
        android.util.Log.i("OsmoLink", s)
        if (Looper.myLooper() == Looper.getMainLooper()) append(line) else main.post { append(line) }
    }

    private fun append(line: String) {
        log.add(line)
        while (log.size > 400) log.removeAt(0)
    }

    companion object {
        const val IP = "192.168.2.1"

        @Volatile private var instance: OsmoLink? = null
        fun get(context: Context): OsmoLink =
            instance ?: synchronized(this) { instance ?: OsmoLink(context.applicationContext).also { instance = it } }
    }
}
