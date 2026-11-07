package com.lunk333.whrrah.esp32

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
import android.os.ParcelUuid
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.BufferedWriter
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * BLE client for the WHRRAH-CAN ESP32 logger ([CanProtocol]) — for the Dev options test panel.
 *
 * Connect → high connection priority + MTU 247 → discover → subscribe to CONTROL and DATA → read INFO
 * → GET_STATUS + a few PINGs for the clock offset. DATA is parsed on the BLE thread into counters that a
 * 1 Hz tick publishes to Compose state (a busy bus sends hundreds of packets a second).
 */
@SuppressLint("MissingPermission")
class CanLink private constructor(private val context: Context) {

    enum class State { IDLE, SCANNING, CONNECTING, CONNECTED, FAILED }

    data class Found(val name: String, val address: String, val rssi: Int)
    /** One CAN ID: frames seen, rate over the last second, latest payload. */
    data class FrameStat(val id: Long, val extended: Boolean, val count: Long, val rateHz: Double, val data: ByteArray, val changed: Boolean)
    data class ChannelValue(val channel: Int, val value: Float, val count: Long)

    var state by mutableStateOf(State.IDLE); private set
    var status by mutableStateOf(""); private set
    val found = mutableStateListOf<Found>()
    val log = mutableStateListOf<String>()

    var info by mutableStateOf<String?>(null); private set
    var mtu by mutableStateOf(23); private set
    var espStatus by mutableStateOf<CanProtocol.Status?>(null); private set
    var streamFlags by mutableStateOf<Int?>(null); private set
    var filterCount by mutableStateOf<Int?>(null); private set

    /** Best (lowest round trip) PING so far: round trip ms, ESP32 − phone clock µs. */
    var rttMs by mutableStateOf<Double?>(null); private set
    var offsetUs by mutableStateOf<Long?>(null); private set
    var lastRttMs by mutableStateOf<Double?>(null); private set
    var pings by mutableStateOf(0); private set
    /** Phone arrival − ESP32 record time for the newest record (needs [offsetUs]). */
    var latencyMs by mutableStateOf<Double?>(null); private set

    var packets by mutableStateOf(0L); private set
    var packetRateHz by mutableStateOf(0.0); private set
    var recordRateHz by mutableStateOf(0.0); private set
    var kbps by mutableStateOf(0.0); private set
    var lostPackets by mutableStateOf(0L); private set
    var badPackets by mutableStateOf(0L); private set
    var frames by mutableStateOf<List<FrameStat>>(emptyList()); private set
    var channels by mutableStateOf<List<ChannelValue>>(emptyList()); private set

    var logging by mutableStateOf(false); private set
    var logFile by mutableStateOf<String?>(null); private set

    private val main = Handler(Looper.getMainLooper())
    private val prefs = context.getSharedPreferences("esp32", Context.MODE_PRIVATE)
    private val adapter get() = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    private var gatt: GattLink? = null
    private var wantLink = false
    private var writer: BufferedWriter? = null

    // Written on the BLE thread, read by the tick; guarded by `lock`.
    private val lock = Any()
    private class IdAcc(val id: Long, val ext: Boolean) { var count = 0L; var lastTick = 0L; var data = ByteArray(0); var changedAt = 0L }
    private val ids = HashMap<Long, IdAcc>()
    private val chans = HashMap<Int, ChannelValue>()
    private var nPackets = 0L; private var nRecords = 0L; private var nBytes = 0L; private var nLost = 0L; private var nBad = 0L
    private var lastSeq = -1
    private var lastRecordUs = 0L; private var lastArrivalUs = 0L
    private var tickPackets = 0L; private var tickRecords = 0L; private var tickBytes = 0L; private var tickAt = 0L

    private val pendingPings = HashMap<Int, Long>()
    private var nextToken = 1

    val lastDevice: Found? get() = prefs.getString("addr", null)?.let { Found(prefs.getString("name", null) ?: it, it, 0) }

    // ---- scan --------------------------------------------------------------------------------

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, r: ScanResult) {
            val n = r.scanRecord?.deviceName ?: r.device.name
            val hasService = r.scanRecord?.serviceUuids?.contains(ParcelUuid(CanProtocol.SERVICE)) == true
            if (!hasService && n?.startsWith(CanProtocol.DEVICE_NAME) != true) return
            main.post {
                val f = Found(n ?: CanProtocol.DEVICE_NAME, r.device.address, r.rssi)
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
        state = State.SCANNING; status = "Scanning for ${CanProtocol.DEVICE_NAME}…"
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
        resetStats()
        logLine("connecting to $addr")
        gatt = GattLink().also { it.gatt = a.getRemoteDevice(addr).connectGatt(context, false, it, BluetoothDevice.TRANSPORT_LE) }
    }

    fun disconnect() {
        wantLink = false
        main.removeCallbacks(reconnect)
        main.removeCallbacks(tick)
        stopLogging()
        gatt?.let { runCatching { it.gatt?.disconnect(); it.gatt?.close() } }
        gatt = null
        if (state != State.SCANNING) { state = State.IDLE; status = "" }
    }

    private val reconnect = Runnable { prefs.getString("addr", null)?.let { if (wantLink && gatt == null) open(it) } }

    fun resetStats() {
        synchronized(lock) {
            ids.clear(); chans.clear()
            nPackets = 0; nRecords = 0; nBytes = 0; nLost = 0; nBad = 0; lastSeq = -1
            tickPackets = 0; tickRecords = 0; tickBytes = 0; tickAt = SystemClock.elapsedRealtime()
            pendingPings.clear()
        }
        packets = 0; packetRateHz = 0.0; recordRateHz = 0.0; kbps = 0.0; lostPackets = 0; badPackets = 0
        frames = emptyList(); channels = emptyList(); latencyMs = null
        rttMs = null; offsetUs = null; lastRttMs = null; pings = 0
    }

    // ---- commands ----------------------------------------------------------------------------

    fun requestStatus() = send(CanProtocol.getStatus())
    fun setStream(flags: Int) = send(CanProtocol.setStream(flags))
    fun setFilter(idList: List<Int>) = send(CanProtocol.setRawFilter(idList))

    /** [n] PINGs 100 ms apart; the lowest round trip sets the clock offset. */
    fun ping(n: Int = 1) {
        for (i in 0 until n) main.postDelayed({
            val token = nextToken++
            gatt?.write(CanProtocol.ping(token)) { synchronized(lock) { pendingPings[token] = nowUs() } }
        }, i * 100L)
    }

    private fun send(b: ByteArray) {
        val g = gatt ?: return
        g.write(b)
    }

    // ---- CSV log -----------------------------------------------------------------------------

    fun startLogging() {
        if (logging) return
        val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), "esp32").also { it.mkdirs() }
        val f = File(dir, "can_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".csv")
        writer = f.bufferedWriter().also { it.write("phone_us,esp_us,kind,id,dlc,data,value\n") }
        logFile = f.absolutePath.substringAfter("/Android/")
        logging = true
        logLine("logging to ${f.name}")
    }

    fun stopLogging() {
        runCatching { writer?.close() }
        writer = null
        logging = false
    }

    // ---- incoming ----------------------------------------------------------------------------

    private fun onData(b: ByteArray) {
        val now = nowUs()
        val p = CanProtocol.parseData(b)
        synchronized(lock) {
            nPackets++; nBytes += b.size
            if (p == null) { nBad++; return }
            if (lastSeq >= 0) nLost += CanProtocol.seqGap(lastSeq, p.seq)
            lastSeq = p.seq
            if (p.unknownKind != null || p.truncated) nBad++
            nRecords += p.records.size
            for (r in p.records) when (r) {
                is CanProtocol.Frame -> {
                    val a = ids.getOrPut(r.id or (if (r.extended) 1L shl 32 else 0)) { IdAcc(r.id, r.extended) }
                    a.count++
                    if (!a.data.contentEquals(r.data)) { a.data = r.data; a.changedAt = SystemClock.elapsedRealtime() }
                }
                is CanProtocol.Channel -> chans[r.channel] = ChannelValue(r.channel, r.value, (chans[r.channel]?.count ?: 0) + 1)
            }
            p.records.lastOrNull()?.let { lastRecordUs = it.tUs; lastArrivalUs = now }
        }
        writer?.let { w ->
            runCatching {
                for (r in p?.records.orEmpty()) when (r) {
                    is CanProtocol.Frame -> w.write("$now,${r.tUs},${if (r.extended) "can29" else "can11"},%X,${r.data.size},${hex(r.data, "")},\n".format(r.id))
                    is CanProtocol.Channel -> w.write("$now,${r.tUs},channel,%04X,,,${r.value}\n".format(r.channel))
                }
            }
        }
    }

    private fun onReply(b: ByteArray) {
        val now = nowUs()
        when (val r = CanProtocol.parseReply(b)) {
            is CanProtocol.Pong -> {
                val t0 = synchronized(lock) { pendingPings.remove(r.token) }
                if (t0 == null) { main.post { logLine("PONG for unknown token ${r.token}") }; return }
                val rtt = (now - t0) / 1000.0
                val off = CanProtocol.clockOffsetUs(t0, now, r.espUs)
                main.post {
                    pings++; lastRttMs = rtt
                    if (rttMs == null || rtt < rttMs!!) { rttMs = rtt; offsetUs = off }
                    logLine("PONG rtt %.1f ms, offset %+d µs".format(rtt, off))
                }
            }
            is CanProtocol.StreamSet -> main.post { streamFlags = r.flags; logLine("stream flags = 0x%02x".format(r.flags)) }
            is CanProtocol.FilterSet -> main.post { filterCount = r.count; logLine(if (r.count == 0) "raw filter cleared (all IDs)" else "raw filter: ${r.count} IDs") }
            is CanProtocol.StatusReply -> main.post {
                espStatus = r.status
                if (streamFlags == null) streamFlags = r.status.streamFlags
            }
            is CanProtocol.Error -> main.post { logLine("ERROR for opcode 0x%02x: %s".format(r.opcode, r.reason)) }
            is CanProtocol.Unknown -> main.post { logLine("CONTROL ? ${hex(r.bytes, " ")}") }
        }
    }

    /** Publishes the counters once a second (and asks for STATUS every other second). */
    private val tick = object : Runnable {
        var n = 0
        override fun run() {
            val t = SystemClock.elapsedRealtime()
            synchronized(lock) {
                val dt = (t - tickAt).coerceAtLeast(1) / 1000.0
                packetRateHz = (nPackets - tickPackets) / dt
                recordRateHz = (nRecords - tickRecords) / dt
                kbps = (nBytes - tickBytes) * 8 / 1000.0 / dt
                tickPackets = nPackets; tickRecords = nRecords; tickBytes = nBytes; tickAt = t
                packets = nPackets; lostPackets = nLost; badPackets = nBad
                frames = ids.values.sortedBy { it.id }.map { a ->
                    val rate = (a.count - a.lastTick) / dt
                    a.lastTick = a.count
                    FrameStat(a.id, a.ext, a.count, rate, a.data, t - a.changedAt < 1000)
                }
                channels = chans.values.sortedBy { it.channel }
                latencyMs = offsetUs?.takeIf { lastArrivalUs != 0L }?.let { (lastArrivalUs - (lastRecordUs - it)) / 1000.0 }
            }
            if (autoStatus && n++ % 2 == 0) requestStatus()
            main.postDelayed(this, 1000)
        }
    }
    var autoStatus by mutableStateOf(true)

    // ---- GATT --------------------------------------------------------------------------------

    /** One GATT connection with a serial operation queue (Android allows one outstanding op). */
    private inner class GattLink : BluetoothGattCallback() {
        var gatt: BluetoothGatt? = null
        private val ops = ArrayDeque<() -> Unit>()
        private var busy = false
        private var control: BluetoothGattCharacteristic? = null

        private fun enqueue(op: () -> Unit) = synchronized(ops) { ops.addLast(op); if (!busy) next() }
        private fun next() = synchronized(ops) {
            val op = ops.pollFirst()
            busy = op != null
            op?.invoke()
        }

        /** Write to CONTROL; [onSent] runs as the write is issued (PING timestamps). */
        fun write(bytes: ByteArray, onSent: (() -> Unit)? = null) {
            val g = gatt ?: return
            val c = control ?: return
            enqueue {
                onSent?.invoke()
                @Suppress("DEPRECATION")
                val ok = if (Build.VERSION.SDK_INT >= 33) g.writeCharacteristic(c, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == 0
                else { c.value = bytes; c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT; g.writeCharacteristic(c) }
                if (!ok) { main.post { logLine("write failed: ${hex(bytes, " ")}") }; next() }
            }
        }

        override fun onConnectionStateChange(g: BluetoothGatt, st: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                main.post { logLine("connected (status=$st) — requesting MTU ${CanProtocol.MTU}") }
                g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                g.requestMtu(CanProtocol.MTU)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                g.close()
                main.post {
                    if (this@CanLink.gatt !== this) return@post
                    this@CanLink.gatt = null
                    main.removeCallbacks(tick)
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

        override fun onServicesDiscovered(g: BluetoothGatt, st: Int) {
            val svc = g.getService(CanProtocol.SERVICE)
            val data = svc?.getCharacteristic(CanProtocol.DATA)
            control = svc?.getCharacteristic(CanProtocol.CONTROL)
            val infoC = svc?.getCharacteristic(CanProtocol.INFO)
            val ctl = control
            if (data == null || ctl == null) { main.post { fail("Not a WHRRAH-CAN device (no service ${CanProtocol.SERVICE})") }; return }
            for (c in listOf(ctl, data)) {
                g.setCharacteristicNotification(c, true)
                enqueue {
                    val d = c.getDescriptor(CCCD)
                    @Suppress("DEPRECATION")
                    if (d == null) next()
                    else if (Build.VERSION.SDK_INT >= 33) g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                    else { d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE; g.writeDescriptor(d) }
                }
            }
            infoC?.let { c -> enqueue { if (!g.readCharacteristic(c)) next() } }
            main.post {
                state = State.CONNECTED; status = "Connected"
                logLine("subscribed to CONTROL + DATA")
                synchronized(lock) { tickAt = SystemClock.elapsedRealtime() }
                main.removeCallbacks(tick); main.postDelayed(tick, 1000)
            }
            write(CanProtocol.getStatus())
            main.post { ping(5) }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, st: Int) { next() }
        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, st: Int) {
            if (st != BluetoothGatt.GATT_SUCCESS) main.post { logLine("CONTROL write status $st") }
            next()
        }

        @Deprecated("API < 33")
        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, st: Int) { onRead(c.uuid, c.value ?: ByteArray(0)) }
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, st: Int) { onRead(c.uuid, value) }

        private fun onRead(uuid: UUID, v: ByteArray) {
            if (uuid == CanProtocol.INFO) {
                val s = String(v, Charsets.UTF_8).trim()
                main.post { info = s; logLine("INFO: $s") }
            }
            next()
        }

        @Deprecated("API < 33")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) { onNotify(c.uuid, c.value ?: return) }
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) { onNotify(c.uuid, value) }

        private fun onNotify(uuid: UUID, v: ByteArray) = when (uuid) {
            CanProtocol.DATA -> onData(v)
            CanProtocol.CONTROL -> onReply(v)
            else -> Unit
        }
    }

    // ---- util --------------------------------------------------------------------------------

    private fun nowUs() = SystemClock.elapsedRealtimeNanos() / 1000

    private fun fail(msg: String) {
        state = State.FAILED; status = msg
        logLine(msg)
    }

    private fun logLine(s: String) {
        android.util.Log.i("CanLink", s)
        log.add(SimpleDateFormat("HH:mm:ss", Locale.US).format(Date()) + "  " + s)
        while (log.size > 200) log.removeAt(0)
    }

    companion object {
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        fun hex(b: ByteArray, sep: String) = b.joinToString(sep) { "%02X".format(it) }

        @Volatile private var instance: CanLink? = null
        fun get(context: Context): CanLink =
            instance ?: synchronized(this) { instance ?: CanLink(context.applicationContext).also { instance = it } }
    }
}
