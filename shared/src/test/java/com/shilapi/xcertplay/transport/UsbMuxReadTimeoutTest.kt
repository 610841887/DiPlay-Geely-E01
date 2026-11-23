package com.shilapi.xcertplay.transport

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbRequest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter
import java.nio.ByteBuffer
import java.util.concurrent.TimeoutException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], manifest = Config.NONE, shadows = [
    UsbMuxReadTimeoutTest.ReadRequest::class, UsbMuxReadTimeoutTest.ReadConnection::class,
])
class UsbMuxReadTimeoutTest {
    @Before fun reset() {
        waits = 0
        cancelSucceeds = false
        drainTimesOut = false
        payload = byteArrayOf(1, 2, 3)
    }

    @Test fun completionWinningTheCancelRaceKeepsItsBytesAndSession() {
        session().use { session ->
            assertArrayEquals(payload, session.read(1))
            // The same session must still be usable after the raced completion.
            waits = 0
            assertArrayEquals(payload, session.read(1))
        }
    }

    @Test fun successfulCancelAlsoPreservesAnyCompletedBytes() {
        cancelSucceeds = true
        session().use { assertArrayEquals(payload, it.read(1)) }
    }

    @Test fun drainedEmptyCancellationIsOnlyATimeout() {
        cancelSucceeds = true
        payload = byteArrayOf()
        session().use { assertNull(it.read(1)) }
    }

    @Test fun anUndrainableRequestFailsInsteadOfReusingItsBuffer() {
        drainTimesOut = true
        session().use { session ->
            assertThrows(IphoneUsbException.DeviceUnavailable::class.java) { session.read(1) }
            val beforeRetry = waits
            assertThrows(IphoneUsbException.DeviceUnavailable::class.java) { session.read(1) }
            assertEquals(beforeRetry, waits)
        }
    }

    private fun session(): Iap2UsbSession {
        val connection = ReflectionHelpers.callConstructor(UsbDeviceConnection::class.java,
            ClassParameter.from(UsbDevice::class.java, null))
        fun endpoint(address: Int): UsbEndpoint = ReflectionHelpers.callConstructor(UsbEndpoint::class.java,
            ClassParameter.from(Int::class.javaPrimitiveType, address),
            ClassParameter.from(Int::class.javaPrimitiveType, 2),
            ClassParameter.from(Int::class.javaPrimitiveType, 512),
            ClassParameter.from(Int::class.javaPrimitiveType, 0))
        return Iap2UsbSession(connection, endpoint(1), endpoint(0x81))
    }

    @Implements(UsbRequest::class)
    class ReadRequest {
        @RealObject lateinit var real: UsbRequest
        @Implementation fun initialize(connection: UsbDeviceConnection, endpoint: UsbEndpoint) = true
        @Implementation fun queue(buffer: ByteBuffer): Boolean {
            request = real
            readBuffer = buffer
            return true
        }
        @Implementation fun cancel(): Boolean = cancelSucceeds
        @Implementation fun close() {}
    }

    @Implements(UsbDeviceConnection::class)
    class ReadConnection {
        @Implementation fun requestWait(timeout: Long): UsbRequest {
            waits++
            if (waits == 1 || drainTimesOut) throw TimeoutException()
            readBuffer.put(payload)
            return request
        }
        @Implementation fun close() {}
    }

    companion object {
        private var waits = 0
        private var cancelSucceeds = false
        private var drainTimesOut = false
        private var payload = byteArrayOf()
        private lateinit var request: UsbRequest
        private lateinit var readBuffer: ByteBuffer
    }
}
