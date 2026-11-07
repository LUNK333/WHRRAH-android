package com.lunk333.whrrah.esp32

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class CanProtocolTest {

    private fun le(n: Int) = ByteBuffer.allocate(n).order(ByteOrder.LITTLE_ENDIAN)

    private fun header(seq: Int, base: Long) = le(10).put(1).put(seq.toByte()).putLong(base).array()

    @Test fun parsesAllRecordKinds() {
        val can11 = le(8 + 3).put(0x01).putInt(100).putShort(0x316).put(3).put(byteArrayOf(0x0A, 0x0B, 0x0C)).array()
        val chan = le(11).put(0x02).putInt(250).putShort(0x0001).putFloat(3456.5f).array()
        val can29 = le(10 + 2).put(0x03).putInt(0xFFFFFFF0.toInt()).putInt(0x18FEF100).put(2).put(byteArrayOf(1, 2)).array()
        val p = CanProtocol.parseData(header(7, 1_000_000L) + can11 + chan + can29)!!
        assertEquals(7, p.seq)
        assertEquals(1_000_000L, p.baseUs)
        assertEquals(3, p.records.size)
        val f = p.records[0] as CanProtocol.Frame
        assertEquals(1_000_100L, f.tUs); assertEquals(0x316L, f.id); assertEquals(false, f.extended)
        assertArrayEquals(byteArrayOf(0x0A, 0x0B, 0x0C), f.data)
        val c = p.records[1] as CanProtocol.Channel
        assertEquals(1_000_250L, c.tUs); assertEquals(1, c.channel); assertEquals(3456.5f, c.value)
        val x = p.records[2] as CanProtocol.Frame
        assertEquals(1_000_000L + 0xFFFFFFF0L, x.tUs)            // dt_us is unsigned
        assertEquals(0x18FEF100L, x.id); assertTrue(x.extended)
        assertNull(p.unknownKind); assertEquals(false, p.truncated)
    }

    @Test fun unknownKindSkipsRestAndTruncationIsFlagged() {
        val chan = le(11).put(0x02).putInt(0).putShort(5).putFloat(42f).array()
        val p = CanProtocol.parseData(header(0, 0) + chan + byteArrayOf(0x7E, 1, 2, 3))!!
        assertEquals(1, p.records.size); assertEquals(0x7E, p.unknownKind)
        val t = CanProtocol.parseData(header(0, 0) + le(8).put(0x01).putInt(0).putShort(0x100).put(8).array() + byteArrayOf(1, 2))!!
        assertTrue(t.truncated); assertEquals(0, t.records.size)
        assertNull(CanProtocol.parseData(byteArrayOf(2, 0, 0, 0, 0, 0, 0, 0, 0, 0)))   // wrong version
        assertNull(CanProtocol.parseData(byteArrayOf(1, 0)))
    }

    @Test fun seqGapWraps() {
        assertEquals(0, CanProtocol.seqGap(4, 5))
        assertEquals(2, CanProtocol.seqGap(4, 7))
        assertEquals(0, CanProtocol.seqGap(255, 0))
        assertEquals(1, CanProtocol.seqGap(254, 0))
    }

    @Test fun parsesStatusAndReplies() {
        val st = le(27).put(0x84.toByte()).put(1).putLong(12_345_678L).putInt(1000).putInt(3).putInt(-1)
            .put(1).put(3).put(1).putShort(247).array()
        val s = (CanProtocol.parseReply(st) as CanProtocol.StatusReply).status
        assertEquals(1, s.proto); assertEquals(12_345_678L, s.espUs); assertEquals(1000L, s.canRx); assertEquals(3L, s.canLost)
        assertEquals(0xFFFFFFFFL, s.bleDropped); assertEquals("running", s.twaiName); assertEquals(3, s.streamFlags)
        assertTrue(s.fake); assertEquals(247, s.mtu)

        val pong = le(13).put(0x81.toByte()).putInt(42).putLong(99L).array()
        assertEquals(CanProtocol.Pong(42, 99L), CanProtocol.parseReply(pong))
        assertEquals(CanProtocol.StreamSet(2), CanProtocol.parseReply(byteArrayOf(0x82.toByte(), 2)))
        assertEquals(CanProtocol.FilterSet(0), CanProtocol.parseReply(byteArrayOf(0x83.toByte(), 0)))
        val e = CanProtocol.parseReply(byteArrayOf(0xFF.toByte(), 3, 2)) as CanProtocol.Error
        assertEquals(3, e.opcode); assertEquals("bad length", e.reason)
        assertTrue(CanProtocol.parseReply(byteArrayOf(0x84.toByte(), 1)) is CanProtocol.Unknown)
    }

    @Test fun buildsCommands() {
        assertArrayEquals(byteArrayOf(1, 0x78, 0x56, 0x34, 0x12), CanProtocol.ping(0x12345678))
        assertArrayEquals(byteArrayOf(2, 3), CanProtocol.setStream(3))
        assertArrayEquals(byteArrayOf(3, 2, 0xF0.toByte(), 0x01, 0x16, 0x03), CanProtocol.setRawFilter(listOf(0x1F0, 0x316)))
        assertArrayEquals(byteArrayOf(3, 0), CanProtocol.setRawFilter(emptyList()))
        assertArrayEquals(byteArrayOf(4), CanProtocol.getStatus())
    }

    @Test fun idListAndOffset() {
        assertEquals(listOf(0x1F0, 0x1F5, 0x316), CanProtocol.parseIdList("0x1F0, 1f5 316"))
        assertEquals(emptyList<Int>(), CanProtocol.parseIdList(" "))
        assertNull(CanProtocol.parseIdList("800"))
        assertNull(CanProtocol.parseIdList("zz"))
        // Sent at 1000, reply at 3000, ESP32 stamped 52000 → offset 50000.
        assertEquals(50_000L, CanProtocol.clockOffsetUs(1000, 3000, 52_000))
    }
}
