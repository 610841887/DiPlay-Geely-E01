package com.shilapi.xcertplay.media

import java.io.IOException

/** Splits a byte stream into complete NAL units, holding back an incomplete tail. */
class AnnexBSplitter(initialCapacity: Int = 128 * 1024) {

    private var buffer = ByteArray(initialCapacity)
    private var size = 0

    fun reset() {
        size = 0
    }

    /** Returns the NAL units completed by [chunk]; the remainder stays buffered. */
    fun feed(chunk: ByteArray): List<ByteArray> {
        append(chunk)
        val units = ArrayList<ByteArray>(8)
        var scan = 0
        while (true) {
            val start = findStartCode(scan)
            if (start < 0) {
                // No start code: either pre-stream noise, or the tail of one split across reads.
                scan = (size - 2).coerceAtLeast(0)
                break
            }
            val nalStart = start + 3
            val next = findStartCode(nalStart)
            if (next < 0) {
                scan = start
                break
            }
            var end = next
            // A four-byte start code carries a leading zero that belongs to the next unit.
            while (end > nalStart && buffer[end - 1].toInt() == 0) end--
            if (end > nalStart) units.add(buffer.copyOfRange(nalStart, end))
            scan = next
        }
        keepFrom(scan.coerceAtMost(size))
        return units
    }

    private fun findStartCode(from: Int): Int {
        var index = from.coerceAtLeast(0)
        while (index + 2 < size) {
            if (buffer[index].toInt() == 0 && buffer[index + 1].toInt() == 0 && buffer[index + 2].toInt() == 1) {
                return index
            }
            index++
        }
        return -1
    }

    private fun append(chunk: ByteArray) {
        if (chunk.size > MAX_BUFFER_BYTES - size) throw IOException("Annex-B video unit exceeds the decoder buffer")
        if (size + chunk.size > buffer.size) {
            var capacity = buffer.size
            while (capacity < size + chunk.size) capacity *= 2
            buffer = buffer.copyOf(capacity)
        }
        System.arraycopy(chunk, 0, buffer, size, chunk.size)
        size += chunk.size
    }

    private fun keepFrom(index: Int) {
        if (index <= 0) return
        if (index >= size) {
            size = 0
            return
        }
        System.arraycopy(buffer, index, buffer, 0, size - index)
        size -= index
    }
    private companion object {
        const val MAX_BUFFER_BYTES = 8 * 1024 * 1024
    }
}
