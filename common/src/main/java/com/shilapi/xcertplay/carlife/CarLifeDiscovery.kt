package com.shilapi.xcertplay.carlife

import android.content.Context
import android.net.wifi.WifiManager
import android.os.SystemClock
import com.shilapi.xcertplay.isProjectionIpv4
import java.io.Closeable
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

internal object CarLifeDiscovery {
    private const val BEACON_PORT = 7999
    private const val SEARCH_MILLIS = 6_000

    fun autoDiscover(context: Context, onResult: (String?) -> Unit): Closeable {
        val cancelled = AtomicBoolean(false)
        val socket = runCatching { DatagramSocket(BEACON_PORT) }.getOrNull()
        val deadline = SystemClock.elapsedRealtime() + SEARCH_MILLIS
        val worker = Thread({
            val host = try {
                knownPeers(context).firstOrNull { !cancelled.get() && probe(it) }
                    ?: socket?.let { awaitBeacon(it, deadline, cancelled) }
            } finally {
                socket?.close()
            }
            if (!cancelled.get()) onResult(host)
        }, "carlife-discovery").apply { isDaemon = true; start() }
        return Closeable {
            cancelled.set(true)
            socket?.close()
            worker.interrupt()
        }
    }

    @Suppress("DEPRECATION")
    private fun knownPeers(context: Context): Set<String> = buildSet {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        runCatching { wifi?.dhcpInfo?.gateway }.getOrNull()?.takeIf { it != 0 }?.let { gateway ->
            add((0..3).joinToString(".") { shift -> ((gateway ushr (8 * shift)) and 255).toString() })
        }
        // Older E01 releases expose current neighbors on the hotspot. Read only actual entries.
        runCatching {
            File("/proc/net/arp").useLines { lines ->
                lines.drop(1).take(16).forEach { line ->
                    val fields = line.trim().split(Regex("\\s+"))
                    if (fields.size == 6 && fields[2] == "0x2" && isProjectionIpv4(fields[0])) add(fields[0])
                }
            }
        }
    }

    private fun probe(host: String): Boolean = runCatching {
        Socket().use { it.connect(InetSocketAddress(host, CarLifeChannel.CMD.mdPort), 180) }
    }.isSuccess

    private fun awaitBeacon(socket: DatagramSocket, deadline: Long, cancelled: AtomicBoolean): String? {
        val buffer = ByteArray(512)
        val packet = DatagramPacket(buffer, buffer.size)
        while (!cancelled.get()) {
            val remaining = deadline - SystemClock.elapsedRealtime()
            if (remaining <= 0) return null
            socket.soTimeout = remaining.toInt()
            packet.length = buffer.size
            if (runCatching { socket.receive(packet) }.isFailure) return null
            val sender = packet.address as? Inet4Address ?: continue
            if (sender.isLoopbackAddress) continue
            val content = String(buffer, 0, packet.length, Charsets.UTF_8)
            if (content.contains("CarLife", ignoreCase = true)) return sender.hostAddress
        }
        return null
    }
}
