package com.shilapi.xcertplay.carlife

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.Executors

class CarLifeTransportTest {

    @Test
    fun socketTransportConnectsAndExchangesStreams() {
        val server = ServerSocket(0)
        val port = server.localPort
        val executor = Executors.newSingleThreadExecutor()

        try {
            executor.execute {
                val client = server.accept()
                client.outputStream.write(byteArrayOf(1, 2, 3, 4))
                client.outputStream.flush()
                val buf = ByteArray(4)
                client.inputStream.read(buf)
                client.close()
            }

            val transport = object : CarLifeTransport {
                override fun openChannel(channel: CarLifeChannel, timeoutMillis: Int): CarLifeStream {
                    val socket = java.net.Socket("127.0.0.1", port)
                    return object : CarLifeStream {
                        override fun inputStream() = socket.getInputStream()
                        override fun outputStream() = socket.getOutputStream()
                        override fun close() = socket.close()
                    }
                }
                override fun close() {}
            }

            val stream = transport.openChannel(CarLifeChannel.CMD)
            assertNotNull(stream)
            val incoming = ByteArray(4)
            stream!!.inputStream().read(incoming)
            assertEquals(1.toByte(), incoming[0])
            assertEquals(2.toByte(), incoming[1])
            assertEquals(3.toByte(), incoming[2])
            assertEquals(4.toByte(), incoming[3])
            stream.outputStream().write(incoming)
            stream.close()
        } finally {
            server.close()
            executor.shutdownNow()
        }
    }
}
