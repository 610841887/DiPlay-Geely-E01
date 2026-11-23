package com.shilapi.xcertplay.carlife

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * One CarLife message: a fixed header followed by `dataSize` bytes of payload.
 *
 * The official Baidu CarLife specification and native implementations use network byte order
 * (Big-Endian). Control channels (CMD, CTRL) use an 8-byte header: `[uint16 length, uint16 reserved, uint32 type]`.
 * Media streaming channels (VIDEO, MEDIA, etc.) use a 12-byte header: `[uint32 length, uint32 timestamp, uint32 type]`.
 */
data class CarLifeHead(
    /** Message type, one of [CarLifeMessage]. */
    val type: Int,
    /** Payload length in bytes. */
    val dataSize: Int,
    /** Milliseconds, streaming channels only; `0` on [CarLifeChannel.CMD] and [CarLifeChannel.CTRL]. */
    val timestamp: Int = 0,
)

/**
 * Encodes and decodes the CarLife header in Big-Endian network byte order.
 */
object CarLifeFrames {

    /** Writes the header for [channel]. The caller writes the payload itself. */
    fun encodeHead(channel: CarLifeChannel, head: CarLifeHead): ByteArray {
        require(head.dataSize >= 0) { "negative payload: ${head.dataSize}" }
        require(head.dataSize <= channel.maxPayload) {
            "${channel.name} payload ${head.dataSize} exceeds ${channel.maxPayload}"
        }
        val out = ByteArray(channel.headLength)
        if (channel.hasTimestamp) {
            putInt(out, 0, head.dataSize)
            putInt(out, 4, head.timestamp)
            putInt(out, 8, head.type)
        } else {
            putShort(out, 0, head.dataSize)
            putShort(out, 2, 0)
            putInt(out, 4, head.type)
        }
        return out
    }

    /**
     * Reads a header out of [bytes] starting at [offset].
     *
     * Throws [IOException] on a message that claims more payload than [CarLifeChannel.maxPayload]
     * allows. That happens only after byte drift; letting it through would make the reader allocate
     * a multi-megabyte buffer and fail with OOM instead of dropping the connection.
     */
    fun decodeHead(channel: CarLifeChannel, bytes: ByteArray, offset: Int = 0): CarLifeHead {
        if (offset + channel.headLength > bytes.size) {
            throw IOException("short header: need ${channel.headLength} bytes, have ${bytes.size - offset}")
        }
        val head = if (channel.hasTimestamp) {
            val size = getInt(bytes, offset)
            val stamp = getInt(bytes, offset + 4)
            val type = getInt(bytes, offset + 8)
            CarLifeHead(type = type, dataSize = size, timestamp = stamp)
        } else {
            val size = getShort(bytes, offset)
            val type = getInt(bytes, offset + 4)
            CarLifeHead(type = type, dataSize = size)
        }
        if (head.dataSize < 0 || head.dataSize > channel.maxPayload) {
            throw IOException(
                "${channel.name} header claims ${head.dataSize} bytes (max ${channel.maxPayload}); byte drift"
            )
        }
        return head
    }

    private fun putInt(target: ByteArray, offset: Int, value: Int) {
        target[offset] = ((value ushr 24) and 0xff).toByte()
        target[offset + 1] = ((value ushr 16) and 0xff).toByte()
        target[offset + 2] = ((value ushr 8) and 0xff).toByte()
        target[offset + 3] = (value and 0xff).toByte()
    }

    private fun putShort(target: ByteArray, offset: Int, value: Int) {
        target[offset] = ((value ushr 8) and 0xff).toByte()
        target[offset + 1] = (value and 0xff).toByte()
    }

    private fun getInt(source: ByteArray, offset: Int): Int =
        ((source[offset].toInt() and 0xff) shl 24) or
            ((source[offset + 1].toInt() and 0xff) shl 16) or
            ((source[offset + 2].toInt() and 0xff) shl 8) or
            (source[offset + 3].toInt() and 0xff)

    private fun getShort(source: ByteArray, offset: Int): Int =
        ((source[offset].toInt() and 0xff) shl 8) or
            (source[offset + 1].toInt() and 0xff)
}

/** A decoded message, payload included. */
class CarLifeFrame(val head: CarLifeHead, val payload: ByteArray) {
    val type: Int get() = head.type
}

/**
 * Reads whole frames off a channel, one at a time, blocking until each is complete.
 */
class CarLifeFrameReader(
    private val channel: CarLifeChannel,
    private val input: InputStream,
) {
    private val head = ByteArray(channel.headLength)

    fun read(): CarLifeFrame? {
        val first = input.read()
        if (first < 0) return null
        head[0] = first.toByte()
        readFully(head, 1, channel.headLength - 1)
        val decoded = CarLifeFrames.decodeHead(channel, head)
        if (decoded.dataSize == 0) return CarLifeFrame(decoded, EMPTY)
        val payload = ByteArray(decoded.dataSize)
        readFully(payload, 0, payload.size)
        return CarLifeFrame(decoded, payload)
    }

    private fun readFully(target: ByteArray, offset: Int, length: Int) {
        var read = 0
        while (read < length) {
            val count = input.read(target, offset + read, length - read)
            if (count < 0) {
                throw EOFException("${channel.name} stream ended with ${length - read} of $length bytes pending")
            }
            read += count
        }
    }

    private companion object {
        val EMPTY = ByteArray(0)
    }
}

/**
 * Writes frames onto a channel.
 */
class CarLifeFrameWriter(
    private val channel: CarLifeChannel,
    private val output: OutputStream,
    private val now: () -> Long = { System.currentTimeMillis() },
) {
    fun write(type: Int, payload: ByteArray = EMPTY, timestampMillis: Int = 0) {
        val stamp = if (channel.hasTimestamp) {
            if (timestampMillis != 0) timestampMillis else (now() and 0xffffffffL).toInt()
        } else {
            0
        }
        val head = CarLifeFrames.encodeHead(channel, CarLifeHead(type = type, dataSize = payload.size, timestamp = stamp))
        val frame = ByteArray(head.size + payload.size)
        System.arraycopy(head, 0, frame, 0, head.size)
        System.arraycopy(payload, 0, frame, head.size, payload.size)
        output.write(frame)
        output.flush()
    }

    private companion object {
        val EMPTY = ByteArray(0)
    }
}
