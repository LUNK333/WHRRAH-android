package com.lunk333.whrrah.racebox

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Calendar
import java.util.TimeZone

/*
 * RaceBox Mini / Mini S / Micro protocol (RaceBox BLE Protocol Description rev 9): u-blox UBX
 * framing over a Nordic-UART-style BLE service.
 *
 *     B5 62 | class | id | len u16-LE | payload | CK_A CK_B
 *
 * Checksum: 8-bit Fletcher over class..payload. Packets can be split across BLE notifications and a
 * notification can carry several, so [UbxFramer] buffers and re-syncs on the header.
 */

data class UbxPacket(val cls: Int, val id: Int, val payload: ByteArray)

object Ubx {
    const val CLASS_RACEBOX = 0xFF
    const val ID_DATA = 0x01          // RaceBox Data Message, 80 B, up to 25 Hz
    const val ID_ACK = 0x02
    const val ID_NACK = 0x03
    const val ID_GNSS_CONFIG = 0x27   // fw 3.3+: empty payload = read, 3 B = write

    /** Fletcher checksum over [from, to) of [b]. */
    fun checksum(b: ByteArray, from: Int, to: Int): Pair<Int, Int> {
        var a = 0
        var c = 0
        for (i in from until to) { a = (a + (b[i].toInt() and 0xFF)) and 0xFF; c = (c + a) and 0xFF }
        return a to c
    }

    /** A complete packet ready to write to the RX characteristic. */
    fun frame(cls: Int, id: Int, payload: ByteArray = ByteArray(0)): ByteArray {
        val out = ByteArray(8 + payload.size)
        out[0] = 0xB5.toByte(); out[1] = 0x62
        out[2] = cls.toByte(); out[3] = id.toByte()
        out[4] = (payload.size and 0xFF).toByte(); out[5] = (payload.size shr 8).toByte()
        payload.copyInto(out, 6)
        val (a, c) = checksum(out, 2, 6 + payload.size)
        out[6 + payload.size] = a.toByte(); out[7 + payload.size] = c.toByte()
        return out
    }
}

/** Reassembles UBX packets from arbitrary notification chunks. Not thread-safe; feed from one thread. */
class UbxFramer(private val maxPayload: Int = 504) {
    private var buf = ByteArray(2048)
    private var len = 0
    var checksumErrors = 0L; private set
    var discardedBytes = 0L; private set

    fun feed(chunk: ByteArray): List<UbxPacket> {
        if (len + chunk.size > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, len + chunk.size))
        chunk.copyInto(buf, len)
        len += chunk.size
        val out = ArrayList<UbxPacket>(2)
        var pos = 0
        while (true) {
            // Re-sync on the header.
            while (pos + 1 < len && !(buf[pos] == 0xB5.toByte() && buf[pos + 1] == 0x62.toByte())) { pos++; discardedBytes++ }
            if (len - pos < 6) break
            val plen = (buf[pos + 4].toInt() and 0xFF) or ((buf[pos + 5].toInt() and 0xFF) shl 8)
            if (plen > maxPayload) { pos++; discardedBytes++; continue }   // not a real header
            if (len - pos < 8 + plen) break                                 // wait for the rest
            val (a, c) = Ubx.checksum(buf, pos + 2, pos + 6 + plen)
            if (a == (buf[pos + 6 + plen].toInt() and 0xFF) && c == (buf[pos + 7 + plen].toInt() and 0xFF)) {
                out.add(UbxPacket(buf[pos + 2].toInt() and 0xFF, buf[pos + 3].toInt() and 0xFF, buf.copyOfRange(pos + 6, pos + 6 + plen)))
                pos += 8 + plen
            } else {
                checksumErrors++
                pos++   // header was a coincidence or the packet is damaged: resync past it
                discardedBytes++
            }
        }
        if (pos > 0) { buf.copyInto(buf, 0, pos, len); len -= pos }
        // A lone trailing byte is only worth keeping if it could start the next header.
        if (len == 1 && buf[0] != 0xB5.toByte()) { len = 0; discardedBytes++ }
        return out
    }
}

/** The 80-byte RaceBox Data Message (class 0xFF, id 0x01), in natural units. */
data class RaceBoxData(
    val iTowMs: Long,
    /** UTC of the fix, from the date/time fields + signed nanoseconds. Only meaningful if [timeValid]. */
    val utcMs: Long,
    val timeValid: Boolean,
    val timeAccuracyNs: Long,
    /** 0 no fix, 2 = 2D, 3 = 3D. */
    val fixType: Int,
    /** Bit 0 of the fix flags: the receiver considers the solution good. */
    val fixOk: Boolean,
    val headingValid: Boolean,
    val satellites: Int,
    val lat: Double,
    val lon: Double,
    val latLonValid: Boolean,
    val altWgsM: Double,
    val altMslM: Double,
    val hAccM: Double,
    val vAccM: Double,
    val speedMps: Double,
    val headingDeg: Double,
    val speedAccMps: Double,
    val headingAccDeg: Double,
    val pdop: Double,
    /** Mini / Mini S: battery %, with [charging]. Micro: input volts ×10 (see [rawBattery]). */
    val batteryPercent: Int,
    val charging: Boolean,
    val rawBattery: Int,
    /** Acceleration in g: X front/back, Y right/left, Z up/down. */
    val gX: Double, val gY: Double, val gZ: Double,
    /** Rotation rate in °/s: X roll, Y pitch, Z yaw. */
    val rollRate: Double, val pitchRate: Double, val yawRate: Double,
) {
    /** The doc's recommended "good location" test. */
    val goodFix: Boolean get() = fixOk && fixType == 3

    companion object {
        const val SIZE = 80

        fun parse(p: ByteArray): RaceBoxData? {
            if (p.size < SIZE) return null
            val b = ByteBuffer.wrap(p).order(ByteOrder.LITTLE_ENDIAN)
            fun u8(o: Int) = p[o].toInt() and 0xFF
            fun u16(o: Int) = b.getShort(o).toInt() and 0xFFFF
            fun i16(o: Int) = b.getShort(o).toInt()
            fun i32(o: Int) = b.getInt(o)
            fun u32(o: Int) = b.getInt(o).toLong() and 0xFFFFFFFFL

            val validity = u8(11)
            val nanos = i32(16)
            val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
                clear()
                set(u16(4), u8(6) - 1, u8(7), u8(8), u8(9), u8(10))
            }
            val fixFlags = u8(21)
            val battery = u8(67)
            return RaceBoxData(
                iTowMs = u32(0),
                utcMs = cal.timeInMillis + Math.floorDiv(nanos, 1_000_000),
                timeValid = (validity and 0x03) == 0x03,
                timeAccuracyNs = u32(12),
                fixType = u8(20),
                fixOk = (fixFlags and 0x01) != 0,
                headingValid = (fixFlags and 0x20) != 0,
                satellites = u8(23),
                lon = i32(24) / 1e7,
                lat = i32(28) / 1e7,
                latLonValid = (u8(66) and 0x01) == 0,
                altWgsM = i32(32) / 1000.0,
                altMslM = i32(36) / 1000.0,
                hAccM = u32(40) / 1000.0,
                vAccM = u32(44) / 1000.0,
                speedMps = i32(48) / 1000.0,
                headingDeg = i32(52) / 1e5,
                speedAccMps = u32(56) / 1000.0,
                headingAccDeg = u32(60) / 1e5,
                pdop = u16(64) / 100.0,
                batteryPercent = battery and 0x7F,
                charging = (battery and 0x80) != 0,
                rawBattery = battery,
                gX = i16(68) / 1000.0, gY = i16(70) / 1000.0, gZ = i16(72) / 1000.0,
                rollRate = i16(74) / 100.0, pitchRate = i16(76) / 100.0, yawRate = i16(78) / 100.0,
            )
        }
    }
}
