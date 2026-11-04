package com.waizusora.flowery

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build

/**
 * Discovers USB Audio Class devices and requests Android USB host permission.
 *
 * Permission only grants access to the USB device. It does not claim an audio
 * streaming interface or establish an exclusive/bit-perfect playback route.
 */
class UsbDacController(context: Context) {
    companion object {
        const val ACTION_USB_PERMISSION = "com.waizusora.flowery.action.USB_PERMISSION"
    }

    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(Context.USB_SERVICE) as UsbManager

    data class Device(
        val usbDevice: UsbDevice,
        val displayName: String,
        val identity: String,
        val hasStreamingInterface: Boolean
    )

    fun audioDevices(): List<Device> = manager.deviceList.values
        .filter { device ->
            (0 until device.interfaceCount).any { index ->
                val intf = device.getInterface(index)
                intf.interfaceClass == UsbConstants.USB_CLASS_AUDIO &&
                    intf.interfaceSubclass == 2
            }
        }
        .map { device ->
            val streaming = (0 until device.interfaceCount).any { index ->
                val intf = device.getInterface(index)
                intf.interfaceClass == UsbConstants.USB_CLASS_AUDIO &&
                    intf.interfaceSubclass == 2
            }
            val product = device.productName?.takeIf { it.isNotBlank() } ?: "USB Audio Device"
            Device(
                usbDevice = device,
                displayName = product,
                identity = "%04x:%04x:%d:%d".format(
                    java.util.Locale.ROOT,
                    device.vendorId, device.productId, device.deviceClass, device.deviceSubclass
                ),
                hasStreamingInterface = streaming
            )
        }

    fun hasPermission(device: UsbDevice): Boolean = manager.hasPermission(device)

    fun requestPermission(device: UsbDevice) {
        val intent = Intent(ACTION_USB_PERMISSION).setPackage(appContext.packageName)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        val permissionIntent = PendingIntent.getBroadcast(appContext, device.deviceId, intent, flags)
        manager.requestPermission(device, permissionIntent)
    }

    fun permissionGranted(device: UsbDevice): Boolean = manager.hasPermission(device)
}
