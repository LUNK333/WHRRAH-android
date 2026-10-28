package com.lunk333.whrrah.racebox

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.lunk333.whrrah.gps.GpsFix
import com.lunk333.whrrah.gps.GpsSource
import java.io.BufferedWriter
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * RaceBox Mini / Mini S / Micro over BLE (RaceBox BLE Protocol Description rev 9).
 *
 * Connect → MTU 517 + high connection priority (it streams ~25 × 88 B/s) → discover → subscribe to
 * the UART TX characteristic → read Device Information (model / serial / firmware) → query the GNSS
 * config (fw 3.3+). The 25 Hz Data Message starts as soon as notifications are on.
 *
 * Also a [GpsSource], so an open session logs RaceBox fixes instead of phone GPS when it's connected.
 * One instance per process; UI state is Compose snapshot state, written on the main thread.
 */
@SuppressLint("MissingPermission")
class RaceBox private constructor(private val context: Context) : GpsSource {

    enum class State { IDLE, SCANNING, CONNECTING, CONNECTED, FAILED }

    data class Found(val name: String, val address: String, val rssi: Int)

    override val name = "RaceBox"
    var state by mutableStateOf(State.IDLE); private set
    var status by mutableStateOf(""); private set
    val found = mutableStateListOf<Found>()
    val log = mutableStateListOf<String>()

    override val connected: Boolean get() = state == State.CONNECTED && latest != null
    override var lastFix by mutableStateOf<GpsFix?>(null); private set
    override var onFix: ((GpsFix) -> Unit)? = null

    /** Latest decoded Data Message. */
    var latest by mutableStateOf<RaceBoxData?>(null); private set
    var model by mutableStateOf<String?>(null); private set
    var serial by mutableStateOf<String?>(null); private set
    var firmware by mutableStateOf<String?>(null); private set
    var gnssConfig by mutableStateOf<String?>(null); private set
    var mtu by mutableStateOf(23); private set

    /** Data Messages per second over the last ~2 s. */
    var rateHz by mutableStateOf(0.0); private set
    var packets by mutableStateOf(0L); private set
    var checksumErrors by mutableStateOf(0L); private set
    /** Gaps in iTOW bigger than 1.5 × the nominal 40 ms period — dropped messages. */
    var gaps by mutableStateOf(0L); private set

    var logging by mutableStateOf(false); private set
    var logFile by mutableStateOf<String?>(null); private set

    private val main = Handler(Looper.getMainLooper())
    private val prefs = context.getSharedPreferences("racebox", Context.MODE_PRIVATE)
    private val adapter get() = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    private var gatt: GattLink? = null
    private var wantLink = false
    private val framer = UbxFramer()
    private val arrivals = ArrayDeque<Long>()
    private var lastItow = -1L
    private var writer: BufferedWriter? = null

    val lastDevice: Found? get() = prefs.getString("addr", null)?.let { Found(prefs.getString("name", null) ?: it, it, 0) }

    // ---- scan --------------------------------------------------------------------------------

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, r: ScanResult) {
            val n = r.scanRecord?.deviceName ?: r.device.name ?: return
            if (!n.startsWith("RaceBox")) return
            main.post {
                val f = Found(n, r.device.address, r.rssi)
                val i = found.indexOfFirst { it.address == f.address }
                if (i >= 0) found[i] = f else found.add(f)
            }
        }

        override fun onScanFailed(errorCode: Int) { main.post { fail("BLE scan failed ($errorCode)") } }
    }
    private val scanTimeout = Runnable { stopScan() }

    fun startScan() {
        val s = adapter?.bluetoothLeScanner ?: return fail("Bluetooth is off")
        found.clear()
        state = State.SCANNING; status = "Scanning for RaceBox…"
        s.startScan(scanCallback)
        main.postDelayed(scanTimeout, 15_000)
    }

    fun stopScan() {
        main.removeCallbacks(scanTimeout)
        runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
        if (state == State.SCANNING) { state = State.IDLE; status = "" }
    }

    // ---- connect -----------------------------------------------------------------------------

    fun connect(addr: String, devName: String?) {
        stopScan()
        disconnect()
        prefs.edit().putString("addr", addr).putString("name", devName).apply()
        wantLink = true
        open(addr)
    }

    private fun open(addr: String) {
        val a = adapter ?: return fail("Bluetooth unavailable")
        state = State.CONNECTING; status = "Connecting…"
        latest = null; packets = 0; gaps = 0; lastItow = -1; arrivals.clear()
        logLine("connecting to $addr")
        gatt = GattLink(addr).also { it.gatt = a.getRemoteDevice(addr).connectGatt(context, false, it, BluetoothDevice.TRANSPORT_LE) }
    }

    fun disconnect() {
        wantLink = false
        main.removeCallbacks(reconnect)
        stopLogging()
        gatt?.let { runCatching { it.gatt?.disconnect(); it.gatt?.close() } }
        gatt = null
        if (state != State.SCANNING) { state = State.IDLE; status = "" }
        latest = null
    }

    private val reconnect = Runnable { prefs.getString("addr", null)?.let { if (wantLink && gatt == null) open(it) } }

    /** Ask for the GNSS receiver config (fw 3.3+); the reply is shown in [gnssConfig]. */
    fun queryGnssConfig() { gatt?.write(Ubx.frame(Ubx.CLASS_RACEBOX, Ubx.ID_GNSS_CONFIG)) }

    // GpsSource: the session starts/stops it; connecting is up to the user (or a remembered device).
    override fun start() { if (gatt == null) lastDevice?.let { connect(it.address, it.name) } }
    override fun stop() = disconnect()

    // ---- CSV log (test panel) -----------------------------------------------------------------

    fun startLogging() {
        if (logging) return
        val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), "racebox").also { it.mkdirs() }
        val f = File(dir, "racebox_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".csv")
        writer = f.bufferedWriter().also {
            it.write("elapsed_ms,utc_ms,itow_ms,fix,fix_ok,sats,lat,lon,alt_msl_m,h_acc_m,speed_mps,heading_deg,g_x,g_y,g_z,roll_dps,pitch_dps,yaw_dps,battery\n")
        }
        logFile = f.absolutePath.substringAfter("/Android/")
        logging = true
        logLine("logging to ${f.name}")
    }

    fun stopLogging() {
        runCatching { writer?.close() }
        writer = null
        logging = false
    }

    // ---- packets -----------------------------------------------------------------------------

    private fun onChunk(chunk: ByteArray) {
        val now = SystemClock.elapsedRealtime()
        for (p in framer.feed(chunk)) {
            if (p.cls != Ubx.CLASS_RACEBOX) continue
            when (p.id) {
                Ubx.ID_DATA -> RaceBoxData.parse(p.payload)?.let { onData(it, now) }
                Ubx.ID_GNSS_CONFIG -> if (p.payload.size >= 3) {
                    val platform = when (p.payload[0].toInt()) { 4 -> "automotive"; 5 -> "sea"; 6 -> "airborne <1g"; 8 -> "airborne <4g"; else -> "model ${p.payload[0]}" }
                    val cfg = "platform $platform · ${if (p.payload[1].toInt() != 0) "3D" else "ground"} speed · min accuracy ${p.payload[2]} m"
                    main.post { gnssConfig = cfg; logLine("GNSS config: $cfg") }
                }
                Ubx.ID_ACK, Ubx.ID_NACK -> main.post {
                    logLine((if (p.id == Ubx.ID_ACK) "ACK" else "NACK") + " for 0x%02x/0x%02x".format(p.payload.getOrNull(0), p.payload.getOrNull(1)))
                    if (p.id == Ubx.ID_NACK && p.payload.getOrNull(1)?.toInt() == Ubx.ID_GNSS_CONFIG) gnssConfig = "not supported (firmware < 3.3)"
                }
                else -> main.post { logLine("packet 0x%02x/0x%02x (%d B)".format(p.cls, p.id, p.payload.size)) }
            }
        }
        val errs = framer.checksumErrors
        if (errs != checksumErrors) main.post { checksumErrors = errs }
    }

    private fun onData(d: RaceBoxData, elapsed: Long) {
        // Written on the BLE thread; stopLogging() may close it from the main thread at any moment.
        writer?.let { w ->
            runCatching {
                w.write("$elapsed,${d.utcMs},${d.iTowMs},${d.fixType},${if (d.fixOk) 1 else 0},${d.satellites},${d.lat},${d.lon}," +
                    "${d.altMslM},${d.hAccM},${d.speedMps},${d.headingDeg},${d.gX},${d.gY},${d.gZ},${d.rollRate},${d.pitchRate},${d.yawRate},${d.rawBattery}\n")
            }
        }
        val gap = lastItow >= 0 && d.iTowMs - lastItow > 60
        lastItow = d.iTowMs
        main.post {
            latest = d
            packets++
            if (gap) gaps++
            arrivals.addLast(elapsed)
            while (arrivals.isNotEmpty() && elapsed - arrivals.first() > 2000) arrivals.removeFirst()
            rateHz = if (arrivals.size > 1) (arrivals.size - 1) * 1000.0 / (arrivals.last() - arrivals.first()).coerceAtLeast(1) else 0.0
            if (state == State.CONNECTED && status.startsWith("Connected")) status = "Streaming"
            if (d.latLonValid && d.fixType >= 2) {
                val fix = GpsFix(elapsed, d.utcMs, d.lat, d.lon, d.speedMps.toFloat(),
                    if (d.headingValid) d.headingDeg.toFloat() else null, d.hAccM.toFloat())
                lastFix = fix
                onFix?.invoke(fix)
            }
        }
    }

    // ---- GATT --------------------------------------------------------------------------------

    /** One GATT connection with a serial operation queue (Android allows one outstanding op). */
    private inner class GattLink(val addr: String) : BluetoothGattCallback() {
        var gatt: BluetoothGatt? = null
        private val ops = ArrayDeque<() -> Unit>()
        private var busy = false
        private var rx: BluetoothGattCharacteristic? = null

        private fun enqueue(op: () -> Unit) = synchronized(ops) { ops.addLast(op); if (!busy) next() }
        private fun next() = synchronized(ops) {
            val op = ops.pollFirst()
            busy = op != null
            op?.invoke()
        }

        fun write(bytes: ByteArray) {
            val g = gatt ?: return
            val c = rx ?: return
            enqueue {
                @Suppress("DEPRECATION")
                val ok = if (Build.VERSION.SDK_INT >= 33) g.writeCharacteristic(c, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == 0
                else { c.value = bytes; c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT; g.writeCharacteristic(c) }
                if (!ok) next()
            }
        }

        override fun onConnectionStateChange(g: BluetoothGatt, st: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                main.post { logLine("connected (status=$st) — requesting MTU 517") }
                g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                g.requestMtu(517)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                g.close()
                main.post {
                    if (this@RaceBox.gatt !== this) return@post
                    this@RaceBox.gatt = null
                    latest = null
                    logLine("disconnected (status=$st)")
                    if (wantLink) {
                        state = State.CONNECTING; status = "Link lost — reconnecting…"
                        main.postDelayed(reconnect, 2000)
                    } else { state = State.IDLE; status = "" }
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, m: Int, st: Int) {
            main.post { mtu = m; logLine("MTU $m") }
            g.discoverServices()
        }

        @Suppress("DEPRECATION")
        override fun onServicesDiscovered(g: BluetoothGatt, st: Int) {
            val uart = g.getService(UART)
            val tx = uart?.getCharacteristic(TX)
            rx = uart?.getCharacteristic(RX)
            if (tx == null || rx == null) { main.post { fail("Not a RaceBox (no UART service)") }; return }
            g.setCharacteristicNotification(tx, true)
            enqueue {
                val d = tx.getDescriptor(CCCD)
                if (Build.VERSION.SDK_INT >= 33) g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                else { d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE; g.writeDescriptor(d) }
            }
            val dis = g.getService(DIS)
            for (uuid in listOf(DIS_MODEL, DIS_SERIAL, DIS_FW)) dis?.getCharacteristic(uuid)?.let { c -> enqueue { if (!g.readCharacteristic(c)) next() } }
            main.post {
                state = State.CONNECTED; status = "Connected — waiting for data"
                logLine("subscribed to data stream")
            }
            write(Ubx.frame(Ubx.CLASS_RACEBOX, Ubx.ID_GNSS_CONFIG))
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, st: Int) { next() }
        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, st: Int) { next() }

        @Deprecated("API < 33")
        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, st: Int) { onRead(c.uuid, c.value ?: ByteArray(0)) }
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, st: Int) { onRead(c.uuid, value) }

        private fun onRead(uuid: UUID, v: ByteArray) {
            val s = String(v, Charsets.UTF_8).trim()
            main.post {
                when (uuid) { DIS_MODEL -> model = s; DIS_SERIAL -> serial = s; DIS_FW -> firmware = s }
                logLine("device info: $s")
            }
            next()
        }

        @Deprecated("API < 33")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) { onChunk(c.value ?: return) }
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) { onChunk(value) }
    }

    // ---- util --------------------------------------------------------------------------------

    private fun fail(msg: String) {
        state = State.FAILED; status = msg
        logLine(msg)
    }

    private fun logLine(s: String) {
        android.util.Log.i("RaceBox", s)
        log.add(SimpleDateFormat("HH:mm:ss", Locale.US).format(Date()) + "  " + s)
        while (log.size > 200) log.removeAt(0)
    }

    companion object {
        val UART: UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
        val RX: UUID = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E")
        val TX: UUID = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E")
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        val DIS: UUID = UUID.fromString("0000180a-0000-1000-8000-00805f9b34fb")
        val DIS_MODEL: UUID = UUID.fromString("00002a24-0000-1000-8000-00805f9b34fb")
        val DIS_SERIAL: UUID = UUID.fromString("00002a25-0000-1000-8000-00805f9b34fb")
        val DIS_FW: UUID = UUID.fromString("00002a26-0000-1000-8000-00805f9b34fb")

        @Volatile private var instance: RaceBox? = null
        fun get(context: Context): RaceBox =
            instance ?: synchronized(this) { instance ?: RaceBox(context.applicationContext).also { instance = it } }
    }
}
