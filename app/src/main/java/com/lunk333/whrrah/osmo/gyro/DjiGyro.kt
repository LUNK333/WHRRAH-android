package com.lunk333.whrrah.osmo.gyro

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/*
 * Reads a DJI Osmo clip's 1 kHz orientation track (the `djmd` timed-metadata stream) without
 * downloading the video. Kotlin port of WHRRAH/wifi_gyro_sync/osmo_wifi_gyro.py — see that file
 * and its HANDOFF.md for the format notes and how it was validated against telemetry_parser.
 *
 *  1. Walk the MP4's top-level box headers with tiny reads; fetch `moov` (~1-2 MB, at the END of
 *     DJI files).
 *  2. Find the `djmd` track: one ~1 KB sample per video frame, each its own chunk, interleaved
 *     with video in `mdat` — so one range read per frame.
 *  3. Decode each sample's schema-less protobuf (dvtm_ac203.proto on the Osmo Action 4): ~40
 *     quaternions per frame = 1 kHz.
 *
 * Only some recording formats carry the 1 kHz block; clips without it decode to zero samples.
 */

// -- MP4 box tree ------------------------------------------------------------------------------

class Mp4Track(
    var handler: String = "?",
    var codec: String? = null,
    var timescale: Long = 0,
    var sampleSizes: IntArray = IntArray(0),
    var chunkOffsets: LongArray = LongArray(0),
    /** (first_chunk, samples_per_chunk), 1-based chunk numbers. */
    var stsc: List<Pair<Long, Int>> = emptyList(),
) {
    /** (offset, size) of every sample, via stsc/stco/stsz. */
    fun sampleRanges(): List<Pair<Long, Int>> {
        val out = ArrayList<Pair<Long, Int>>(sampleSizes.size)
        var k = 0
        for ((i, start) in chunkOffsets.withIndex()) {
            val chunk = i + 1L
            val per = stsc.lastOrNull { it.first <= chunk }?.second ?: 1
            var off = start
            repeat(per) {
                if (k >= sampleSizes.size) return out
                out.add(off to sampleSizes[k])
                off += sampleSizes[k]
                k++
            }
        }
        return out
    }
}

private val CONTAINERS = setOf("moov", "trak", "mdia", "minf", "stbl")

private class Box(val type: String, val start: Int, val end: Int)

private fun ByteArray.u32(at: Int): Long = ByteBuffer.wrap(this, at, 4).order(ByteOrder.BIG_ENDIAN).int.toLong() and 0xFFFFFFFFL
private fun ByteArray.u64(at: Int): Long = ByteBuffer.wrap(this, at, 8).order(ByteOrder.BIG_ENDIAN).long
private fun ByteArray.ascii(from: Int, to: Int): String = String(this, from, to - from, Charsets.ISO_8859_1)

/** Child boxes of buf[start, end): (type, body start, box end). */
private fun boxes(buf: ByteArray, start: Int, end: Int): List<Box> {
    val out = ArrayList<Box>()
    var pos = start
    while (pos + 8 <= end) {
        var size = buf.u32(pos)
        val type = buf.ascii(pos + 4, pos + 8)
        var hdr = 8
        if (size == 1L) {
            size = buf.u64(pos + 8); hdr = 16
        } else if (size == 0L) {
            size = (end - pos).toLong()
        }
        if (size < hdr || pos + size > end) break
        out.add(Box(type, pos + hdr, (pos + size).toInt()))
        pos += size.toInt()
    }
    return out
}

/** Top-level header walk (16-byte reads), then one read of moov's body. */
fun readMoov(reader: RangeReader): ByteArray {
    var pos = 0L
    while (pos + 8 <= reader.size) {
        val head = reader.read(pos, 16)
        var size = head.u32(0)
        val type = head.ascii(4, 8)
        var hdr = 8
        if (size == 1L) {
            size = head.u64(8); hdr = 16
        } else if (size == 0L) {
            size = reader.size - pos
        }
        if (size < hdr) break
        if (type == "moov") return reader.read(pos + hdr, (size - hdr).toInt())
        pos += size
    }
    throw IllegalArgumentException("no moov box")
}

fun parseTracks(moov: ByteArray): List<Mp4Track> {
    val tracks = ArrayList<Mp4Track>()
    fun walk(s0: Int, e0: Int, t: Mp4Track?) {
        for (b in boxes(moov, s0, e0)) {
            val s = b.start
            when {
                b.type == "trak" -> Mp4Track().also { tracks.add(it); walk(s, b.end, it) }
                t == null -> if (b.type in CONTAINERS) walk(s, b.end, null)
                b.type == "hdlr" -> t.handler = moov.ascii(s + 8, s + 12)
                b.type == "mdhd" -> t.timescale = moov.u32(s + if (moov[s].toInt() == 0) 12 else 20)
                b.type == "stsd" -> t.codec = moov.ascii(s + 12, s + 16)
                b.type == "stsz" -> {
                    val fixed = moov.u32(s + 4).toInt()
                    val n = moov.u32(s + 8).toInt()
                    t.sampleSizes = if (fixed != 0) IntArray(n) { fixed } else IntArray(n) { moov.u32(s + 12 + 4 * it).toInt() }
                }
                b.type == "stco" -> {
                    val n = moov.u32(s + 4).toInt()
                    t.chunkOffsets = LongArray(n) { moov.u32(s + 8 + 4 * it) }
                }
                b.type == "co64" -> {
                    val n = moov.u32(s + 4).toInt()
                    t.chunkOffsets = LongArray(n) { moov.u64(s + 8 + 8 * it) }
                }
                b.type == "stsc" -> {
                    val n = moov.u32(s + 4).toInt()
                    t.stsc = List(n) { moov.u32(s + 8 + 12 * it) to moov.u32(s + 12 + 12 * it).toInt() }
                }
                b.type in CONTAINERS -> walk(s, b.end, t)
            }
        }
    }
    walk(0, moov.size, null)
    return tracks
}

// -- protobuf (schema-less) --------------------------------------------------------------------

/** One protobuf field: [value] is a Long (varint) or a ByteArray (length-delimited / fixed). */
class PbField(val field: Int, val wireType: Int, val value: Any)

fun pbFields(b: ByteArray): List<PbField> {
    val out = ArrayList<PbField>()
    var i = 0
    fun varint(): Long {
        var r = 0L
        var sh = 0
        while (true) {
            val c = b[i++].toInt() and 0xFF
            r = r or ((c and 0x7F).toLong() shl sh)
            sh += 7
            if (c < 0x80) return r
        }
    }
    while (i < b.size) {
        val key = varint()
        val f = (key ushr 3).toInt()
        val v: Any = when (val wt = (key and 7).toInt()) {
            0 -> varint()
            1 -> b.copyOfRange(i, i + 8).also { i += 8 }
            5 -> b.copyOfRange(i, i + 4).also { i += 4 }
            2 -> { val n = varint().toInt(); b.copyOfRange(i, i + n).also { i += n } }
            else -> throw IllegalArgumentException("wire type $wt")
        }
        out.add(PbField(f, (key and 7).toInt(), v))
    }
    return out
}

/** First value at a field path, e.g. pbGet(frame, 1, 2). */
private fun pbGet(b: ByteArray, vararg path: Int): Any? {
    var cur: Any = b
    for (f in path) {
        val bytes = cur as? ByteArray ?: return null
        cur = pbFields(bytes).firstOrNull { it.field == f }?.value ?: return null
    }
    return cur
}

private fun f32(v: Any?): Double? =
    (v as? ByteArray)?.takeIf { it.size == 4 }?.let { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).float.toDouble() }

private fun quat(b: ByteArray): DoubleArray {
    val q = DoubleArray(4)
    for (fl in pbFields(b)) {
        if (fl.field in 1..4) f32(fl.value)?.let { q[fl.field - 1] = it }
    }
    return q
}

// -- djmd decode -------------------------------------------------------------------------------

class GyroFrame(
    val index: Long,
    val frameTsUs: Long?,
    val imuTsUs: Long?,
    val imuCounter: Long?,
    val imuOffsetMs: Double?,
    /** Raw stored quaternion fields 1..4 (w, x, y, z?), in order — ~40 per frame = 1 kHz. */
    val quats: List<DoubleArray>,
    /** One quaternion per frame (3.2.9), present even when the 1 kHz block is not. */
    val frameQuat: DoubleArray?,
)

data class ClipHeader(
    val proto: String? = null,
    val model: String? = null,
    val firmware: String? = null,
    val imuHz: Long? = null,
    val fps: Double? = null,
)

fun decodeHeader(sample0: ByteArray): ClipHeader {
    val info = pbGet(sample0, 1, 1) as? ByteArray
    fun str(f: Int) = info?.let { (pbGet(it, f) as? ByteArray)?.toString(Charsets.ISO_8859_1) }?.ifEmpty { null }
    return ClipHeader(
        proto = str(1),
        firmware = str(6),
        model = str(10),
        imuHz = pbGet(sample0, 1, 10, 1) as? Long,
        fps = f32(pbGet(sample0, 1, 11, 1)),
    )
}

fun decodeFrame(sample: ByteArray): GyroFrame? {
    val frame = pbGet(sample, 3) as? ByteArray ?: return null
    val ident = pbFields(pbGet(frame, 1) as? ByteArray ?: ByteArray(0))
    val idx = ident.firstOrNull { it.field == 1 }?.value as? Long ?: 0L
    val ts = ident.firstOrNull { it.field == 2 }?.value as? Long
    var imuTs: Long? = null
    var imuCtr: Long? = null
    var off: Double? = null
    val quats = ArrayList<DoubleArray>(42)
    (pbGet(frame, 3, 2) as? ByteArray)?.let { imu ->
        for (fl in pbFields(imu)) when (fl.field) {
            1 -> imuTs = fl.value as? Long
            2 -> imuCtr = fl.value as? Long
            3 -> (fl.value as? ByteArray)?.let { quats.add(quat(it)) }
            4 -> off = f32(fl.value)
        }
    }
    val fq = (pbGet(frame, 2, 9) as? ByteArray)?.let { quat(it) }
    return GyroFrame(idx, ts, imuTs, imuCtr, off, quats, fq)
}

class GyroTrack(
    val header: ClipHeader,
    val frames: List<GyroFrame>,
    val totalFrames: Int,
    /** Frame 0's IMU block timestamp: the t = 0 reference. */
    val imuTs0Us: Long?,
    val requests: Long,
    val bytesRead: Long,
)

/**
 * Fetch + decode djmd samples. [frames] limits which video frames (a 60 s window is enough to
 * correlate); frame 0 is always read too, for the header and the time reference. One range read
 * per frame, spread over [parallelism] threads since per-request latency dominates over WiFi.
 * [progress] gets (frames done, frames wanted) from worker threads.
 */
fun fetchGyro(
    reader: RangeReader,
    frames: IntRange? = null,
    parallelism: Int = 1,
    isCancelled: () -> Boolean = { false },
    progress: ((Int, Int) -> Unit)? = null,
): GyroTrack {
    val moov = readMoov(reader)
    val track = parseTracks(moov).firstOrNull { it.codec == "djmd" }
        ?: throw IllegalArgumentException("no djmd track (not a DJI Osmo clip?)")
    val ranges = track.sampleRanges()
    require(ranges.isNotEmpty()) { "djmd track has no samples" }
    val first = reader.read(ranges[0].first, ranges[0].second)
    val header = decodeHeader(first)
    val f0 = decodeFrame(first)

    val want = (frames ?: ranges.indices).let { maxOf(it.first, 0)..minOf(it.last, ranges.size - 1) }.toList()
    val results = arrayOfNulls<GyroFrame>(want.size)
    val next = AtomicInteger()
    val done = AtomicInteger()
    fun work() {
        while (!isCancelled()) {
            val n = next.getAndIncrement()
            if (n >= want.size) return
            val i = want[n]
            results[n] = if (i == 0) f0 else {
                val (o, len) = ranges[i]
                runCatching { decodeFrame(reader.read(o, len)) }.getOrElse {
                    if (it is java.io.IOException) throw it else null   // garbage frame: skip; I/O: fail
                }
            }
            val d = done.incrementAndGet()
            if (progress != null && (d % 50 == 0 || d == want.size)) progress(d, want.size)
        }
    }
    if (parallelism <= 1) work() else {
        val pool = Executors.newFixedThreadPool(parallelism)
        try {
            val jobs = List(parallelism) { pool.submit(::work) }
            jobs.forEach { it.get() }   // rethrows the first worker failure
        } finally {
            pool.shutdownNow()
        }
    }
    if (isCancelled()) throw InterruptedException("cancelled")
    return GyroTrack(header, results.filterNotNull(), ranges.size, f0?.imuTsUs, reader.requests, reader.bytesRead)
}

/** Column arrays: one row per IMU sample. Times in [tMs] are milliseconds from frame 0's IMU block. */
class GyroSamples(val tMs: DoubleArray, val w: DoubleArray, val x: DoubleArray, val y: DoubleArray, val z: DoubleArray) {
    val size: Int get() = tMs.size
}

/**
 * Every IMU sample on telemetry_parser's timebase:
 *
 *     t_ms = ((imu_ts - imu_ts0) mod 2^32 / 1000 + j) * fps / 25 - imu_offset_ms
 *
 * (j = index within the frame's burst, fps = the header's measured rate). imu_ts is a u32
 * microsecond counter that wraps every 71.6 min — it did mid-clip on a real recording — so take
 * the difference mod 2^32. Quaternions are the raw stored values; telemetry_parser applies a fixed
 * axis remap on top, which leaves angular speed unchanged.
 */
fun samples1kHz(track: GyroTrack): GyroSamples {
    val ts0 = track.imuTs0Us ?: return GyroSamples(DoubleArray(0), DoubleArray(0), DoubleArray(0), DoubleArray(0), DoubleArray(0))
    val scale = (track.header.fps?.takeIf { it != 0.0 } ?: 25.0) / 25.0
    val n = track.frames.sumOf { if (it.imuTsUs != null) it.quats.size else 0 }
    val t = DoubleArray(n); val w = DoubleArray(n); val x = DoubleArray(n); val y = DoubleArray(n); val z = DoubleArray(n)
    var k = 0
    for (fr in track.frames) {
        val ts = fr.imuTsUs ?: continue
        val base = Math.floorMod(ts - ts0, 1L shl 32) / 1000.0
        val off = fr.imuOffsetMs ?: 0.0
        for ((j, q) in fr.quats.withIndex()) {
            t[k] = (base + j) * scale - off
            w[k] = q[0]; x[k] = q[1]; y[k] = q[2]; z[k] = q[3]
            k++
        }
    }
    return GyroSamples(t, w, x, y, z)
}

/**
 * One sample per frame (the last quaternion of each burst), times in **seconds** — the shape
 * desktop WHRRAH's `_quaternion_samples_from_blocks()` returns, so it drops straight into the
 * |angular velocity| + `correlate_signals()` sync step.
 */
fun whrrahSamples(track: GyroTrack): GyroSamples {
    val all = samples1kHz(track)
    val idx = ArrayList<Int>()
    var i = 0
    for (fr in track.frames) {
        if (fr.imuTsUs == null) continue
        i += fr.quats.size
        if (fr.quats.isNotEmpty()) idx.add(i - 1)
    }
    return GyroSamples(
        DoubleArray(idx.size) { all.tMs[idx[it]] / 1000.0 },
        DoubleArray(idx.size) { all.w[idx[it]] },
        DoubleArray(idx.size) { all.x[idx[it]] },
        DoubleArray(idx.size) { all.y[idx[it]] },
        DoubleArray(idx.size) { all.z[idx[it]] },
    )
}
