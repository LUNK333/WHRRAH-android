package com.lunk333.whrrah.osmo.gyro

import java.io.Closeable
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.atomic.AtomicLong

/**
 * Random-access reads of a clip, local or on the camera. Implementations must be safe to call
 * from several threads at once — [fetchGyro] fans requests out in parallel.
 */
abstract class RangeReader : Closeable {
    abstract val size: Long

    private val requestCount = AtomicLong()
    private val byteCount = AtomicLong()
    val requests: Long get() = requestCount.get()
    val bytesRead: Long get() = byteCount.get()

    fun read(offset: Long, length: Int): ByteArray {
        val data = readImpl(offset, length)
        requestCount.incrementAndGet()
        byteCount.addAndGet(data.size.toLong())
        return data
    }

    protected abstract fun readImpl(offset: Long, length: Int): ByteArray
}

/** A local file, or a `content://` video opened as a file descriptor. Positional reads are thread-safe. */
class ChannelRangeReader(
    private val channel: FileChannel,
    private val owner: Closeable = channel,
) : RangeReader() {
    override val size: Long = channel.size()

    override fun readImpl(offset: Long, length: Int): ByteArray {
        val buf = ByteBuffer.allocate(length)
        var pos = offset
        while (buf.hasRemaining()) {
            val n = channel.read(buf, pos)
            if (n < 0) break
            pos += n
        }
        return buf.array().copyOf(buf.position())
    }

    override fun close() = owner.close()

    companion object {
        fun open(path: String): ChannelRangeReader {
            val raf = RandomAccessFile(path, "r")
            return ChannelRangeReader(raf.channel, raf)
        }
    }
}

/**
 * A clip on the camera: `GET /v2?storage=<0 SD | 1 internal>&path=DCIM/...` with `Range` headers.
 * lighttpd, no auth, camera at 192.168.2.1. The process must already be bound to the camera's
 * network (see OsmoLink / ApJoiner). One connection per call; HttpURLConnection keeps them alive.
 */
class HttpRangeReader(
    path: String,
    storage: Int,
    host: String = "192.168.2.1",
    private val timeoutMs: Int = 15_000,
) : RangeReader() {
    private val url = URL("http://$host/v2?storage=$storage&path=${encodePath(path)}")
    override val size: Long = headSize()

    private fun open(method: String): HttpURLConnection =
        (url.openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
        }

    private fun headSize(): Long {
        val c = open("HEAD")
        try {
            if (c.responseCode != 200) throw IOException("HEAD $url -> HTTP ${c.responseCode}")
            return c.getHeaderField("Content-Length")?.toLongOrNull()
                ?: throw IOException("HEAD $url: no Content-Length")
        } finally {
            c.disconnect()
        }
    }

    override fun readImpl(offset: Long, length: Int): ByteArray {
        var last: IOException? = null
        // Mid-transfer 404/500s are transient on Action bodies, so retry before giving up.
        repeat(3) {
            val c = open("GET")
            c.setRequestProperty("Range", "bytes=$offset-${offset + length - 1}")
            try {
                val code = c.responseCode
                val body = c.inputStream.use { it.readBytes() }
                when (code) {
                    206 -> return body
                    200 -> return body.copyOfRange(offset.toInt(), (offset + length).toInt())
                    else -> last = IOException("GET $url range $offset+$length -> HTTP $code")
                }
            } catch (e: IOException) {
                last = e
            } finally {
                c.disconnect()
            }
        }
        throw last!!
    }

    override fun close() {}

    companion object {
        /** Percent-encode like Python's `quote(path, safe='/')`. */
        fun encodePath(path: String): String =
            path.split('/').joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
    }
}
