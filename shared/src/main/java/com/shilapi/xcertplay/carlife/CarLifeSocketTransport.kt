package com.shilapi.xcertplay.carlife

import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

/**
 * Direct TCP socket transport for Wireless CarLife.
 *
 * Connects directly to the Baidu CarLife App services running on the phone over Wi-Fi / Hotspot LAN.
 * Standard CarLife phone ports (CMD: 7240, VIDEO: 8240, CTRL: 9340, MEDIA: 9240, TTS: 9241, VR: 9242).
 *
 * Compatible with Android 4.2+ (API 17).
 */
class CarLifeSocketTransport(
    val host: String,
    private val log: (String) -> Unit = {},
) : CarLifeTransport {

    private val sockets = ConcurrentHashMap<CarLifeChannel, Socket>()
    private val lock = Any()
    private var closed = false

    override fun openChannel(channel: CarLifeChannel, timeoutMillis: Int): CarLifeStream? {
        val port = channel.mdPort
        log("carlife-socket: connecting to $host:$port for ${channel.name}…")
        val socket = Socket()
        synchronized(lock) {
            if (closed) {
                socket.close()
                return null
            }
            sockets.put(channel, socket)?.close()
        }
        return try {
            socket.tcpNoDelay = true
            socket.keepAlive = true
            if (channel == CarLifeChannel.VIDEO) {
                socket.receiveBufferSize = 512 * 1024
            }
            val address = java.net.InetAddress.getByName(host)
            socket.connect(InetSocketAddress(address, port), timeoutMillis)
            log("carlife-socket: connected ${channel.name} to $host:$port")
            object : CarLifeStream {
                override fun inputStream(): InputStream = socket.getInputStream()
                override fun outputStream(): OutputStream = socket.getOutputStream()
                override fun setReadTimeout(timeoutMillis: Int) {
                    runCatching { socket.soTimeout = timeoutMillis.coerceAtLeast(0) }
                }
                override fun close() {
                    runCatching { socket.close() }
                    sockets.remove(channel, socket)
                }
            }
        } catch (e: Exception) {
            runCatching { socket.close() }
            sockets.remove(channel, socket)
            log("carlife-socket: failed connecting ${channel.name} to $host:$port: ${e.message}")
            null
        }
    }

    override fun close() {
        val closing = synchronized(lock) {
            closed = true
            sockets.values.toList().also { sockets.clear() }
        }
        closing.forEach { socket -> runCatching { socket.close() } }
    }
}
