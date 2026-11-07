package com.lunk333.whrrah.esp32

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/**
 * The WHRRAH-CAN (ESP32 CAN logger) BLE protocol, v1 — see WHRRAH-esp32 `docs/ble-protocol.md`.
 * Pure Kotlin (no Android) so it's unit-tested on the JVM. Everything is little-endian.
 */
object CanProtocol {
    const val VERSION = 1
    const val DEVICE_NAME = "WHRRAH-CAN"
    const val MTU = 247

    val SERVICE: UUID = UUID.fromString("57480001-db62-4a79-b1b8-484cf11adae5")
    val DATA: UUID = UUID.fromString("57480002-db62-4a79-b1b8-484cf11adae5")
    val CONTROL: UUID = UUID.fromString("57480003-db62-4a79-b1b8-484cf11adae5")
    val INFO: UUID = UUID.fromString("57480004-db62-4a79-b1b8-484cf11adae5")

    const val KIND_CAN11 = 0x01
    const val KIND_CHANNEL = 0x02
    const val KIND_CAN29 = 0x03

    const val OP_PING = 0x01
    const val OP_SET_STREAM = 0x02
    const val OP_SET_RAW_FILTER = 0x03
    const val OP_GET_STATUS = 0x04
    const val OP_ERROR = 0xFF
    const val REPLY = 0x80

    /** SET_STREAM flags. */
    const val STREAM_CHANNELS = 0x01
    const val STREAM_RAW = 0x02
    const val MAX_FILTER_IDS = 16

    sealed interface Record { val tUs: Long }
    /** A raw CAN frame; [tUs] is on the ESP32's clock (base_us + dt_us). */
    data class Frame(override val tUs: Long, val id: Long, val extended: Boolean, val data: ByteArray) : Record {
        override fun equals(other: Any?) = other is Frame && tUs == other.tUs && id == other.id && extended == other.extended && data.contentEquals(other.data)
        override fun hashCode() = (tUs * 31 + id).hashCode() * 31 + data.contentHashCode()
    }
    /** A decoded channel value. */
    data class Channel(override val tUs: Long, val channel: Int, val value: Float) : Record

    /**
     * One DATA notification. [unknownKind] = a record kind this app doesn't know (the rest of the packet
     * was skipped, as the spec says); [truncated] = a record ran past the end of the packet.
     */
    class Packet(val seq: Int, val baseUs: Long, val records: List<Record>, val unknownKind: Int? = null, val truncated: Boolean = false)

    /** A DATA notification, or null if it isn't a v1 packet. */
    fun parseData(b: ByteArray): Packet? {
        if (b.size < 10 || b[0].toInt() and 0xFF != VERSION) return null
        val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        val seq = b[1].toInt() and 0xFF
        val base = bb.getLong(2)
        val out = ArrayList<Record>()
        var p = 10
        while (p < b.size) {
            val kind = b[p].toInt() and 0xFF
            val need = when (kind) { KIND_CAN11 -> 8; KIND_CHANNEL -> 11; KIND_CAN29 -> 10; else -> return Packet(seq, base, out, unknownKind = kind) }
            if (p + need > b.size) return Packet(seq, base, out, truncated = true)
            val t = base + (bb.getInt(p + 1).toLong() and 0xFFFFFFFFL)
            when (kind) {
                KIND_CHANNEL -> { out += Channel(t, bb.getShort(p + 5).toInt() and 0xFFFF, bb.getFloat(p + 7)); p += need }
                else -> {
                    val ext = kind == KIND_CAN29
                    val id = if (ext) bb.getInt(p + 5).toLong() and 0xFFFFFFFFL else (bb.getShort(p + 5).toLong() and 0xFFFF)
                    val dlc = b[p + need - 1].toInt() and 0xFF
                    if (p + need + dlc > b.size) return Packet(seq, base, out, truncated = true)
                    out += Frame(t, id, ext, b.copyOfRange(p + need, p + need + dlc))
                    p += need + dlc
                }
            }
        }
        return Packet(seq, base, out)
    }

    /** Packets lost between two sequence numbers (seq is a u8 that wraps). */
    fun seqGap(prev: Int, cur: Int): Int = ((cur - prev - 1) and 0xFF)

    // ---- CONTROL ---------------------------------------------------------------------------------

    fun ping(token: Int): ByteArray = le(5).put(OP_PING.toByte()).putInt(token).array()
    fun setStream(flags: Int): ByteArray = byteArrayOf(OP_SET_STREAM.toByte(), flags.toByte())
    /** Empty = pass every ID. */
    fun setRawFilter(ids: List<Int>): ByteArray {
        require(ids.size <= MAX_FILTER_IDS) { "at most $MAX_FILTER_IDS IDs" }
        val bb = le(2 + 2 * ids.size).put(OP_SET_RAW_FILTER.toByte()).put(ids.size.toByte())
        ids.forEach { bb.putShort(it.toShort()) }
        return bb.array()
    }
    fun getStatus(): ByteArray = byteArrayOf(OP_GET_STATUS.toByte())

    data class Status(
        val proto: Int, val espUs: Long, val canRx: Long, val canLost: Long, val bleDropped: Long,
        val twaiState: Int, val streamFlags: Int, val fake: Boolean, val mtu: Int,
    ) {
        val twaiName get() = when (twaiState) { 0 -> "stopped"; 1 -> "running"; 2 -> "bus-off"; 3 -> "recovering"; else -> "state $twaiState" }
    }

    sealed interface Reply
    data class Pong(val token: Int, val espUs: Long) : Reply
    data class StreamSet(val flags: Int) : Reply
    data class FilterSet(val count: Int) : Reply
    data class StatusReply(val status: Status) : Reply
    data class Error(val opcode: Int, val code: Int) : Reply {
        val reason get() = when (code) { 1 -> "unknown opcode"; 2 -> "bad length"; else -> "code $code" }
    }
    data class Unknown(val bytes: ByteArray) : Reply

    /** A CONTROL notification. */
    fun parseReply(b: ByteArray): Reply {
        if (b.isEmpty()) return Unknown(b)
        val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        return when (b[0].toInt() and 0xFF) {
            REPLY or OP_PING -> if (b.size >= 13) Pong(bb.getInt(1), bb.getLong(5)) else Unknown(b)
            REPLY or OP_SET_STREAM -> if (b.size >= 2) StreamSet(b[1].toInt() and 0xFF) else Unknown(b)
            REPLY or OP_SET_RAW_FILTER -> if (b.size >= 2) FilterSet(b[1].toInt() and 0xFF) else Unknown(b)
            REPLY or OP_GET_STATUS -> if (b.size >= 27) StatusReply(Status(
                proto = b[1].toInt() and 0xFF, espUs = bb.getLong(2),
                canRx = bb.getInt(10).toLong() and 0xFFFFFFFFL, canLost = bb.getInt(14).toLong() and 0xFFFFFFFFL,
                bleDropped = bb.getInt(18).toLong() and 0xFFFFFFFFL,
                twaiState = b[22].toInt() and 0xFF, streamFlags = b[23].toInt() and 0xFF, fake = b[24].toInt() != 0,
                mtu = bb.getShort(25).toInt() and 0xFFFF,
            )) else Unknown(b)
            OP_ERROR -> if (b.size >= 3) Error(b[1].toInt() and 0xFF, b[2].toInt() and 0xFF) else Unknown(b)
            else -> Unknown(b)
        }
    }

    /**
     * ESP32 clock − phone clock (µs) from one PING: assumes the reply was stamped half-way through the
     * round trip. [t0Us]/[t1Us] = phone time the PING was sent / the reply arrived.
     */
    fun clockOffsetUs(t0Us: Long, t1Us: Long, espUs: Long): Long = espUs - (t0Us + t1Us) / 2

    /** "0x1F0, 1f5 316" → [0x1F0, 0x1F5, 0x316]; null if anything isn't an 11-bit hex ID. */
    fun parseIdList(s: String): List<Int>? {
        val parts = s.split(',', ' ', ';').map { it.trim() }.filter { it.isNotEmpty() }
        return parts.map { p ->
            val v = p.removePrefix("0x").removePrefix("0X").toIntOrNull(16) ?: return null
            if (v !in 0..0x7FF) return null
            v
        }
    }

    // ---- channels --------------------------------------------------------------------------------

    data class ChannelInfo(val name: String, val unit: String, val decimals: Int)

    val CHANNELS: Map<Int, ChannelInfo> = mapOf(
        0x0001 to ChannelInfo("Engine RPM", "rpm", 0),
        0x0002 to ChannelInfo("Pedal", "%", 1),
        0x0003 to ChannelInfo("Coolant", "°C", 1),
        0x0004 to ChannelInfo("Oil temp", "°C", 1),
        0x0005 to ChannelInfo("Vehicle speed", "km/h", 1),
        0x0006 to ChannelInfo("Torque", "%", 1),
        0x0007 to ChannelInfo("Outside temp", "°C", 1),
        0x0008 to ChannelInfo("Fuel", "l", 1),
        0x0009 to ChannelInfo("Ambient pressure", "mbar", 0),
        0x0010 to ChannelInfo("Wheel FL", "km/h", 1),
        0x0011 to ChannelInfo("Wheel FR", "km/h", 1),
        0x0012 to ChannelInfo("Wheel RL", "km/h", 1),
        0x0013 to ChannelInfo("Wheel RR", "km/h", 1),
        0x0020 to ChannelInfo("Steering angle", "°", 1),
        0x0021 to ChannelInfo("Steering rate", "°/s", 0),
        0x0030 to ChannelInfo("Brake switch", "", 0),
        0x0031 to ChannelInfo("Clutch switch", "", 0),
        0x0032 to ChannelInfo("Brake pressure", "bar", 1),
    )

    fun channelInfo(ch: Int) = CHANNELS[ch] ?: ChannelInfo("Channel 0x%04X".format(ch), "", 3)

    private fun le(n: Int) = ByteBuffer.allocate(n).order(ByteOrder.LITTLE_ENDIAN)
}
