package com.shilapi.xcertplay.carlife

import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream

/**
 * Underlying stream abstraction for a CarLife channel (CMD, VIDEO, CTRL, etc.).
 *
 * Can be backed by a direct TCP [java.net.Socket] for wireless Wi-Fi connections,
 * or by an ADB stream for wired debugging connections.
 */
interface CarLifeStream : Closeable {
    fun inputStream(): InputStream
    fun outputStream(): OutputStream
    /** Sets the per-read socket timeout in milliseconds (0 disables timeout). */
    fun setReadTimeout(timeoutMillis: Int) {}
}

/**
 * Transport layer capable of opening streams for CarLife channels.
 */
interface CarLifeTransport : Closeable {
    /**
     * Opens a communication channel to the CarLife peer.
     *
     * @param channel The channel to open (e.g., CMD, VIDEO, CTRL).
     * @param timeoutMillis Maximum milliseconds to wait for connection.
     * @return Open [CarLifeStream] or null if connection was refused/timed out.
     */
    fun openChannel(channel: CarLifeChannel, timeoutMillis: Int = 8_000): CarLifeStream?
}
