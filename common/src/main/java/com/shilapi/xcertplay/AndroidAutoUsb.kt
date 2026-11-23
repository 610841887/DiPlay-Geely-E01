package com.shilapi.xcertplay

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.shilapi.xcertplay.aap.UsbAccessoryTransport
import com.shilapi.xcertplay.aap.UsbAoaHandshake
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.mirroring.PhoneClassifier
import com.shilapi.xcertplay.mirroring.PhoneKind
import com.shilapi.xcertplay.mirroring.UsbDeviceIdentity
import com.shilapi.xcertplay.mirroring.UsbInterfaceIdentity
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Runs on the projection worker, including the second Android permission grant after AOA START. */
internal class AndroidAutoUsb(private val context: Context, private val status: (String) -> Unit) {
    private val usb = context.getSystemService(Context.USB_SERVICE) as? UsbManager

    fun connect(): UsbAccessoryTransport {
        val manager = usb ?: throw IOException(context.getString(R.string.aa_error_open_usb_failed))
        val phone = awaitPhone(manager)
        requirePermission(manager, phone)
        status(context.getString(R.string.aa_status_usb_attached))
        val accessory = if (UsbAoaHandshake.isAccessory(phone)) phone else switchToAccessory(manager, phone)
        requirePermission(manager, accessory)
        if (Thread.currentThread().isInterrupted) throw InterruptedException()
        status(context.getString(R.string.aa_status_usb_transport))
        return UsbAccessoryTransport.open(manager, accessory)
            ?: throw IOException(context.getString(R.string.aa_error_no_bulk_endpoints))
    }

    private fun awaitPhone(manager: UsbManager): UsbDevice {
        var previousCount = -1
        while (true) {
            val phones = manager.deviceList.values.filter(::isAndroidPhone)
            phones.singleOrNull()?.let { return it }
            if (phones.size != previousCount) {
                status(context.getString(if (phones.isEmpty()) R.string.aa_usb_waiting_phone else R.string.aa_usb_multiple_phones))
                previousCount = phones.size
            }
            Thread.sleep(500)
        }
    }

    private fun switchToAccessory(manager: UsbManager, phone: UsbDevice): UsbDevice {
        val previous = manager.deviceList.values.filter(UsbAoaHandshake::isAccessory).mapTo(mutableSetOf()) { it.deviceId }
        val connection = manager.openDevice(phone)
            ?: throw IOException(context.getString(R.string.aa_error_open_usb_failed))
        val version = try {
            UsbAoaHandshake.run(connection)
        } finally {
            connection.close()
        }
        if (version == 0) throw IOException(context.getString(R.string.aa_error_no_aoa))
        status(context.getString(R.string.aa_status_aoa, version))
        val deadline = SystemClock.elapsedRealtime() + 8_000
        while (SystemClock.elapsedRealtime() < deadline) {
            val candidates = manager.deviceList.values.filter {
                UsbAoaHandshake.isAccessory(it) && it.deviceId !in previous
            }
            candidates.singleOrNull()?.let { return it }
            Thread.sleep(100)
        }
        throw IOException(context.getString(R.string.aa_error_accessory_missing))
    }

    private fun requirePermission(manager: UsbManager, device: UsbDevice) {
        if (Thread.currentThread().isInterrupted) throw InterruptedException()
        if (manager.hasPermission(device)) return
        status(context.getString(R.string.aa_desktop_waiting_usb_permission))
        val action = "${context.packageName}.AAP_USB_PERMISSION"
        val result = CountDownLatch(1)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val grantedDevice = IntentCompat.getParcelableExtra(intent, UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                if (grantedDevice?.deviceId == device.deviceId) result.countDown()
            }
        }
        val flags = PendingIntent.FLAG_CANCEL_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
        val reply = PendingIntent.getBroadcast(context, device.deviceId, Intent(action).setPackage(context.packageName), flags)
        ContextCompat.registerReceiver(context, receiver, IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED)
        try {
            manager.requestPermission(device, reply)
            result.await(45, TimeUnit.SECONDS)
        } finally {
            reply.cancel()
            context.unregisterReceiver(receiver)
        }
        if (!manager.hasPermission(device)) throw IOException(context.getString(R.string.aa_usb_permission_required))
    }

    private fun isAndroidPhone(device: UsbDevice): Boolean {
        if (device.interfaceCount == 0) return false
        val identity = UsbDeviceIdentity(device.vendorId, device.productId, List(device.interfaceCount) { index ->
            val iface = device.getInterface(index)
            UsbInterfaceIdentity(iface.interfaceClass, iface.interfaceSubclass, iface.interfaceProtocol)
        })
        return PhoneClassifier.classify(identity) == PhoneKind.ANDROID
    }
}
